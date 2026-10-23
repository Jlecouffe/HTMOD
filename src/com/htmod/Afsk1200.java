/*
 * HTMOD Windows : demodulateur AFSK 1200 bauds (Bell 202 : mark 1200 Hz, space 2200 Hz), pour l'APRS.
 * Correlateurs mark/space sur une duree de bit, recuperation d'horloge par PLL numerique (facon
 * Direwolf), NRZI, HDLC (fanions, de-bourrage, FCS). Deux "trancheurs" en parallele : difference
 * brute, et difference normalisee par AGC (compense la desaccentuation FM qui attenue le space).
 */
package com.htmod;

import java.util.ArrayDeque;

final class Afsk1200 {
    interface FrameListener { void onFrame(byte[] frame); }

    private final FrameListener listener;
    private final int rate;
    private final int win;                // echantillons par bit (arrondi)
    private final double[] cm, sm, cs, ss; // oscillateurs precalcules sur une periode commune
    private final int oscLen;
    private int oscPos;

    // fenetres glissantes des produits I/Q
    private final double[] miR, miI, spR, spI;
    private double sMiR, sMiI, sSpR, sSpI;
    private int wpos;

    // AGC du trancheur normalise
    private double mPeak, mValley, sPeak, sValley;

    private final Slicer[] slicers;

    // dedoublonnage entre trancheurs
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    Afsk1200(int sampleRate, FrameListener l) {
        this.listener = l;
        this.rate = sampleRate;
        this.win = Math.max(4, (int) Math.round(sampleRate / 1200.0));
        // periode commune : 1200 et 2200 Hz retombent en phase toutes les 1/200 s
        this.oscLen = sampleRate % 200 == 0 ? sampleRate / 200 : sampleRate;
        cm = new double[oscLen]; sm = new double[oscLen]; cs = new double[oscLen]; ss = new double[oscLen];
        for (int i = 0; i < oscLen; i++) {
            double t = (double) i / sampleRate;
            cm[i] = Math.cos(2 * Math.PI * 1200 * t); sm[i] = Math.sin(2 * Math.PI * 1200 * t);
            cs[i] = Math.cos(2 * Math.PI * 2200 * t); ss[i] = Math.sin(2 * Math.PI * 2200 * t);
        }
        miR = new double[win]; miI = new double[win]; spR = new double[win]; spI = new double[win];
        slicers = new Slicer[]{new Slicer(), new Slicer()};
    }

    void process(short[] pcm, int off, int len) {
        for (int n = off; n < off + len; n++) {
            double x = pcm[n] / 32768.0;
            int k = oscPos;
            oscPos = oscPos + 1 == oscLen ? 0 : oscPos + 1;
            double a = x * cm[k], b = x * sm[k], c = x * cs[k], d = x * ss[k];
            sMiR += a - miR[wpos]; miR[wpos] = a;
            sMiI += b - miI[wpos]; miI[wpos] = b;
            sSpR += c - spR[wpos]; spR[wpos] = c;
            sSpI += d - spI[wpos]; spI[wpos] = d;
            wpos = wpos + 1 == win ? 0 : wpos + 1;

            double m = Math.sqrt(sMiR * sMiR + sMiI * sMiI);
            double s = Math.sqrt(sSpR * sSpR + sSpI * sSpI);

            // AGC : attaque rapide, relachement lent
            mPeak = m > mPeak ? m * 0.25 + mPeak * 0.75 : mPeak * 0.9995 + m * 0.0005;
            mValley = m < mValley ? m * 0.25 + mValley * 0.75 : mValley * 0.9995 + m * 0.0005;
            sPeak = s > sPeak ? s * 0.25 + sPeak * 0.75 : sPeak * 0.9995 + s * 0.0005;
            sValley = s < sValley ? s * 0.25 + sValley * 0.75 : sValley * 0.9995 + s * 0.0005;
            double mn = mPeak - mValley > 1e-9 ? (m - 0.5 * (mPeak + mValley)) / (mPeak - mValley) : 0;
            double sn = sPeak - sValley > 1e-9 ? (s - 0.5 * (sPeak + sValley)) / (sPeak - sValley) : 0;

            slicers[0].sample(m - s);
            slicers[1].sample(mn - sn);
        }
    }

    private void emit(byte[] frame) {
        String key = java.util.Arrays.toString(frame);
        synchronized (recent) {
            if (recent.contains(key)) return;
            recent.addLast(key);
            if (recent.size() > 8) recent.removeFirst();
        }
        listener.onFrame(frame);
    }

    /** PLL + NRZI + HDLC pour un signal de decision. */
    private final class Slicer {
        private int phase;          // accumulateur de phase 32 bits (debordement = instant d'echantillonnage)
        private final int step = (int) Math.round(4294967296.0 * 1200.0 / rate);
        private boolean prevDemod;
        private boolean prevBit;
        // HDLC
        private int pat;            // 8 derniers bits (apres NRZI)
        private int ones;
        private final byte[] buf = new byte[400];
        private int nbytes, nbits, acc;
        private boolean inFrame;

        void sample(double v) {
            boolean demod = v > 0;
            int prev = phase;
            phase += step;
            // instant d'echantillonnage : debordement de l'accumulateur signe (milieu du bit)
            if (prev >= 0 && phase < 0) bit(demod);
            // transition du signal : on recale la phase vers 0 (milieu des transitions)
            if (demod != prevDemod) phase = (int) (phase * 0.72);
            prevDemod = demod;
        }

        private void bit(boolean level) {
            boolean b = level == prevBit; // NRZI : pas de changement = 1
            prevBit = level;
            pat = ((pat >> 1) | (b ? 0x80 : 0)) & 0xFF;

            if (pat == 0x7E) { // fanion
                if (inFrame && nbytes >= 17) finish();
                inFrame = true;
                nbytes = 0; nbits = 0; acc = 0; ones = 0;
                return;
            }
            if (pat == 0xFE || pat == 0xFF) { // 7 uns ou plus : abandon
                inFrame = false;
                ones = 0;
                return;
            }
            if (!inFrame) return;
            if (b) {
                ones++;
            } else {
                if (ones == 5) { ones = 0; return; } // bit de bourrage
                ones = 0;
            }
            acc = (acc >> 1) | (b ? 0x80 : 0);
            if (++nbits == 8) {
                if (nbytes >= buf.length) { inFrame = false; return; }
                buf[nbytes++] = (byte) acc;
                nbits = 0;
                acc = 0;
            }
        }

        private void finish() {
            // Le fanion de fin a ete decale dans les 7 derniers bits : nbits contient les bits
            // du fanion deja accumules (0111111 sans le dernier 0) -> l'octet partiel est ignore.
            int n = nbytes;
            if (n < 17) return;
            int fcs = (buf[n - 2] & 0xFF) | ((buf[n - 1] & 0xFF) << 8);
            if (Ax25.fcs(buf, 0, n - 2) != fcs) return;
            byte[] f = new byte[n - 2];
            System.arraycopy(buf, 0, f, 0, f.length);
            emit(f);
        }
    }
}
