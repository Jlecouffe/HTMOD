/*
 * HTMOD Windows : SSTV (television a balayage lent), emission et reception.
 *  - Modes : Robot 36 / 72, Martin M1 / M2, Scottie S1 / S2 / DX, PD 90 / 120 / 160 / 180 / 240
 *    (timings des specifications de JL Barber / MMSSTV / QSSTV), en-tete VIS 8 bits.
 *  - Encodeur : oscillateur a phase continue, temps exact (pas d'erreur cumulee), 1500 Hz = noir,
 *    2300 Hz = blanc, synchro 1200 Hz.
 *  - Decodeur : melange a 1900 Hz, filtre passe-bas, frequence instantanee (16 kHz), detection du
 *    VIS (au milliseconde puis front de depart affine a l'echantillon), decodage ligne a ligne avec
 *    suivi des tops de synchro (regression lineaire : corrige l'inclinaison due aux horloges).
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

final class Sstv {
    private Sstv() {}

    static final int KIND_RGB = 0, KIND_R36 = 1, KIND_R72 = 2, KIND_PD = 3;
    // canaux d'un balayage
    static final int R = 0, G = 1, B = 2, Y0 = 3, Y1 = 4, U = 5, V = 6, C36 = 7;
    // frequence speciale : separateur Robot 36 (1500 Hz ligne paire, 2300 Hz ligne impaire)
    private static final double SEP36 = -1;

    /** Segment d'une ligne : ton fixe (freq > 0) ou balayage d'un canal. */
    static final class Seg {
        final double ms, freq;
        final int channel;
        Seg(double ms, double freq) { this.ms = ms; this.freq = freq; this.channel = -1; }
        Seg(double ms, int channel, boolean scan) { this.ms = ms; this.freq = 0; this.channel = channel; }
    }

    static final class Mode {
        final String name;
        final int vis, width, height, kind;
        final double initMs;          // synchro initiale unique (Scottie)
        final Seg[] segs;
        final double unitMs, syncOffMs, syncMs;
        final double[] segStart;

        Mode(String name, int vis, int width, int height, int kind, double initMs, Seg... segs) {
            this.name = name;
            this.vis = vis;
            this.width = width;
            this.height = height;
            this.kind = kind;
            this.initMs = initMs;
            this.segs = segs;
            segStart = new double[segs.length];
            double t = 0, so = -1, sl = 0;
            for (int i = 0; i < segs.length; i++) {
                segStart[i] = t;
                if (so < 0 && segs[i].freq == 1200) { so = t; sl = segs[i].ms; }
                t += segs[i].ms;
            }
            unitMs = t;
            syncOffMs = so;
            syncMs = sl;
        }

        /** Nombre de balayages (lignes, ou paires de lignes en PD). */
        int units() { return kind == KIND_PD ? height / 2 : height; }

        double seconds() { return (initMs + units() * unitMs) / 1000.0 + 0.91; }

        @Override public String toString() { return name; }
    }

    private static Seg tone(double ms, double f) { return new Seg(ms, f); }
    private static Seg scan(double ms, int ch) { return new Seg(ms, ch, true); }

    private static Mode martin(String n, int vis, double scanMs) {
        return new Mode(n, vis, 320, 256, KIND_RGB, 0, tone(4.862, 1200), tone(0.572, 1500), scan(scanMs, G), tone(0.572, 1500),
                scan(scanMs, B), tone(0.572, 1500), scan(scanMs, R), tone(0.572, 1500));
    }

    private static Mode scottie(String n, int vis, double scanMs) {
        return new Mode(n, vis, 320, 256, KIND_RGB, 9, tone(1.5, 1500), scan(scanMs, G), tone(1.5, 1500), scan(scanMs, B),
                tone(9, 1200), tone(1.5, 1500), scan(scanMs, R));
    }

    private static Mode pd(String n, int vis, int w, int h, double pxMs) {
        double s = w * pxMs;
        return new Mode(n, vis, w, h, KIND_PD, 0, tone(20, 1200), tone(2.08, 1500), scan(s, Y0), scan(s, V), scan(s, U), scan(s, Y1));
    }

    static final Mode[] MODES = {
        new Mode("Robot 36", 8, 320, 240, KIND_R36, 0, tone(9, 1200), tone(3, 1500), scan(88, Y0), tone(4.5, SEP36),
                tone(1.5, 1900), scan(44, C36)),
        new Mode("Robot 72", 12, 320, 240, KIND_R72, 0, tone(9, 1200), tone(3, 1500), scan(138, Y0), tone(4.5, 1500),
                tone(1.5, 1900), scan(69, V), tone(4.5, 2300), tone(1.5, 1500), scan(69, U)),
        martin("Martin M1", 44, 146.432),
        martin("Martin M2", 40, 73.216),
        scottie("Scottie S1", 60, 138.24),
        scottie("Scottie S2", 56, 88.064),
        scottie("Scottie DX", 76, 345.6),
        pd("PD 90", 99, 320, 256, 0.532),
        pd("PD 120", 95, 640, 496, 0.19),
        pd("PD 160", 98, 512, 400, 0.382),
        pd("PD 180", 96, 640, 496, 0.286),
        pd("PD 240", 97, 640, 496, 0.382),
    };

    static Mode byVis(int vis) {
        for (Mode m : MODES) if (m.vis == vis) return m;
        return null;
    }

    static Mode byName(String name) {
        for (Mode m : MODES) if (m.name.equals(name)) return m;
        return null;
    }

    // ------------------------------------------------------------------ Couleur (YCbCr BT.601, plage studio)

    static int[] toYuv(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int y = clamp(16 + (65.738 * r + 129.057 * g + 25.064 * b) / 256);
        int u = clamp(128 + (-37.945 * r - 74.494 * g + 112.439 * b) / 256);
        int v = clamp(128 + (112.439 * r - 94.154 * g - 18.285 * b) / 256);
        return new int[]{y, u, v};
    }

    static int fromYuv(double y, double u, double v) {
        double yy = 298.082 * (y - 16);
        int r = clamp((yy + 408.583 * (v - 128)) / 256);
        int g = clamp((yy - 100.291 * (u - 128) - 208.120 * (v - 128)) / 256);
        int b = clamp((yy + 516.411 * (u - 128)) / 256);
        return (r << 16) | (g << 8) | b;
    }

    private static int clamp(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) Math.round(v); }

    // ------------------------------------------------------------------ Encodeur

    /** Image au format du mode : recadree pour remplir, avec texte eventuel en haut a gauche. */
    static BufferedImage prepare(BufferedImage src, Mode m, String text) {
        BufferedImage out = new BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, m.width, m.height);
        if (src != null) {
            double s = Math.max(m.width / (double) src.getWidth(), m.height / (double) src.getHeight());
            int w = (int) Math.round(src.getWidth() * s), h = (int) Math.round(src.getHeight() * s);
            g.drawImage(src, (m.width - w) / 2, (m.height - h) / 2, w, h, null);
        }
        if (text != null && !text.trim().isEmpty()) {
            Font f = new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, m.height / 9));
            GlyphVector gv = f.createGlyphVector(g.getFontRenderContext(), text.trim());
            java.awt.Shape sh = gv.getOutline(m.width / 40f, m.height / 40f + f.getSize());
            g.setStroke(new BasicStroke(Math.max(3f, m.height / 70f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Color.BLACK);
            g.draw(sh);
            g.setColor(new Color(0xFFC83C));
            g.fill(sh);
        }
        g.dispose();
        return out;
    }

    /** Mire de test (barres de couleur, degrade, texte) au format du mode. */
    static BufferedImage testCard(Mode m, String text) {
        BufferedImage img = new BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int[] bars = {0xFFFFFF, 0xFFFF00, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0x0000FF, 0x000000};
        int bh = m.height * 3 / 5;
        for (int i = 0; i < bars.length; i++) {
            g.setColor(new Color(bars[i]));
            g.fillRect(i * m.width / bars.length, 0, (i + 1) * m.width / bars.length - i * m.width / bars.length, bh);
        }
        g.setPaint(new GradientPaint(0, 0, Color.BLACK, m.width, 0, Color.WHITE));
        g.fillRect(0, bh, m.width, m.height / 10);
        g.setColor(new Color(0x12161C));
        g.fillRect(0, bh + m.height / 10, m.width, m.height);
        g.setColor(new Color(0xFFC83C));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(14, m.height / 10)));
        String t = text == null || text.trim().isEmpty() ? "HTMod SSTV" : text.trim();
        g.drawString(t, m.width / 20, m.height - m.height / 10);
        g.setColor(new Color(0x9AA4B2));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, m.height / 20)));
        g.drawString(m.name, m.width / 20, m.height - m.height / 30);
        g.dispose();
        return img;
    }

    /** Signal SSTV complet (VIS + image) en PCM a rate Hz, amplitude level (0..1). */
    static short[] encode(BufferedImage img, Mode m, int rate, double level) {
        Enc e = new Enc(rate, level, (int) (m.seconds() * rate) + rate);
        e.tone(1900, 300);
        e.tone(1200, 10);
        e.tone(1900, 300);
        e.tone(1200, 30);
        int ones = 0;
        for (int i = 0; i < 7; i++) {
            int bit = (m.vis >> i) & 1;
            ones += bit;
            e.tone(bit == 1 ? 1100 : 1300, 30);
        }
        e.tone((ones & 1) == 1 ? 1100 : 1300, 30); // parite paire
        e.tone(1200, 30);
        if (m.initMs > 0) e.tone(1200, m.initMs);
        int w = m.width;
        int[][] rgb = new int[m.height][w];
        for (int y = 0; y < m.height; y++) img.getRGB(0, y, w, 1, rgb[y], 0, w);
        int[] vals = new int[w];
        for (int k = 0; k < m.units(); k++) {
            for (Seg s : m.segs) {
                if (s.channel < 0) {
                    double f = s.freq == SEP36 ? ((k & 1) == 0 ? 1500 : 2300) : s.freq;
                    e.tone(f, s.ms);
                } else {
                    channelValues(m, rgb, k, s.channel, vals);
                    e.scan(vals, s.ms);
                }
            }
        }
        e.tone(1500, 10);
        e.fadeOut();
        return e.result();
    }

    private static void channelValues(Mode m, int[][] rgb, int k, int ch, int[] out) {
        int w = m.width;
        for (int x = 0; x < w; x++) {
            int v;
            switch (ch) {
                case R: v = (rgb[k][x] >> 16) & 0xFF; break;
                case G: v = (rgb[k][x] >> 8) & 0xFF; break;
                case B: v = rgb[k][x] & 0xFF; break;
                case Y0: v = toYuv(rgb[m.kind == KIND_PD ? 2 * k : k][x])[0]; break;
                case Y1: v = toYuv(rgb[2 * k + 1][x])[0]; break;
                case U: case V: {
                    int idx = ch == U ? 1 : 2;
                    if (m.kind == KIND_PD) v = (toYuv(rgb[2 * k][x])[idx] + toYuv(rgb[2 * k + 1][x])[idx] + 1) / 2;
                    else v = toYuv(rgb[k][x])[idx];
                    break;
                }
                default: { // C36 : V (R-Y) sur les lignes paires, U (B-Y) sur les impaires, moyenne de la paire
                    int a = k & ~1, b = Math.min(a + 1, m.height - 1);
                    int idx = (k & 1) == 0 ? 2 : 1;
                    v = (toYuv(rgb[a][x])[idx] + toYuv(rgb[b][x])[idx] + 1) / 2;
                }
            }
            out[x] = v;
        }
    }

    /** Generateur a phase continue ; le temps est cumule en ms exactes. */
    private static final class Enc {
        final int rate;
        final double amp;
        short[] buf;
        int n;
        double phase, tMs;

        Enc(int rate, double level, int cap) {
            this.rate = rate;
            this.amp = 32767 * Math.max(0.05, Math.min(1, level));
            buf = new short[cap];
        }

        private int endSample(double ms) { return (int) Math.round(ms * rate / 1000.0); }

        private void put(double f) {
            if (n == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
            phase += 2 * Math.PI * f / rate;
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            buf[n++] = (short) Math.round(amp * Math.sin(phase));
        }

        void tone(double f, double ms) {
            tMs += ms;
            int end = endSample(tMs);
            while (n < end) put(f);
        }

        void scan(int[] vals, double ms) {
            double start = tMs;
            tMs += ms;
            int end = endSample(tMs);
            int w = vals.length;
            while (n < end) {
                double t = (n + 0.5) * 1000.0 / rate - start;
                int i = (int) (t / ms * w);
                if (i < 0) i = 0;
                if (i >= w) i = w - 1;
                put(1500 + vals[i] * 800.0 / 255.0);
            }
        }

        void fadeOut() {
            int len = Math.min(n, rate / 200);
            for (int i = 0; i < len; i++) buf[n - len + i] = (short) (buf[n - len + i] * (len - i) / (double) len);
        }

        short[] result() { return java.util.Arrays.copyOf(buf, n); }
    }

    // ------------------------------------------------------------------ Decodeur

    static final class Decoder {
        interface Listener {
            void onStart(Mode m);
            void onProgress(Mode m, BufferedImage img, int rowsDone);
            void onDone(Mode m, BufferedImage img, boolean complete);
        }

        private static final int FS = 16000, MS = FS / 1000;
        private final Listener listener;
        private final int dec;
        private final double dph;
        private double ph;
        private final float[] taps;
        private final float[] bi, bq;
        private int bp, decCount;
        private double li = 1, lq = 0;

        // frequence instantanee (anneau de 1 s) et moyennes a la milliseconde (anneau de 2 s)
        private final float[] ring = new float[1 << 15];
        private long n;
        private final float[] msRing = new float[2048];
        private long msCount;
        private double msAcc;
        private int msN;
        private long holdoffMs;

        // image en cours
        private Mode mode;
        private BufferedImage img;
        private float[] buf;
        private long imgStart;
        private int unit;
        private int[][] chroma; // Robot 36 : V de la ligne paire
        private int[] yPrev;
        private final List<double[]> syncPts = new ArrayList<>();
        private double fitA, fitB;
        private boolean fitOk;
        private int idle;

        Decoder(int rate, Listener l) {
            listener = l;
            dec = Math.max(1, rate / FS);
            dph = 2 * Math.PI * 1900 / rate;
            int nt = 64;
            taps = new float[nt];
            double fc = 1250.0 / rate;
            double sum = 0;
            for (int i = 0; i < nt; i++) {
                double x = i - (nt - 1) / 2.0;
                double s = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
                double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (nt - 1));
                taps[i] = (float) (s * w);
                sum += s * w;
            }
            for (int i = 0; i < nt; i++) taps[i] /= sum;
            bi = new float[nt * 2];
            bq = new float[nt * 2];
        }

        boolean busy() { return mode != null; }

        Mode mode() { return mode; }

        int rowsDone() { return mode == null ? 0 : Math.min(mode.height, unit * (mode.kind == KIND_PD ? 2 : 1)); }

        void process(short[] pcm, int off, int len) {
            idle = 0;
            int nt = taps.length;
            for (int k = off; k < off + len; k++) {
                double x = pcm[k] / 32768.0;
                float i = (float) (x * Math.cos(ph)), q = (float) (-x * Math.sin(ph));
                ph += dph;
                if (ph > 2 * Math.PI) ph -= 2 * Math.PI;
                bi[bp] = bi[bp + nt] = i;
                bq[bp] = bq[bp + nt] = q;
                bp = (bp + 1) % nt;
                if (++decCount < dec) continue;
                decCount = 0;
                double zi = 0, zq = 0;
                for (int t = 0; t < nt; t++) { zi += taps[t] * bi[bp + t]; zq += taps[t] * bq[bp + t]; }
                double re = zi * li + zq * lq, im = zq * li - zi * lq;
                li = zi;
                lq = zq;
                double f = 1900 + Math.atan2(im, re) * FS / (2 * Math.PI);
                if (f < 900) f = 900;
                if (f > 2700) f = 2700;
                push((float) f);
            }
        }

        /** Pas d'audio depuis un moment (squelch ferme) : apres ~3 s, l'image en cours est terminee. */
        void idle() {
            if (mode != null && ++idle >= 5) finish(false);
        }

        /** Abandonne l'image en cours (en la rendant telle quelle). */
        void stop() { if (mode != null) finish(false); }

        private void push(float f) {
            ring[(int) (n & (ring.length - 1))] = f;
            if (mode != null) {
                long idx = n - imgStart;
                if (idx >= 0 && idx < buf.length) buf[(int) idx] = f;
            }
            n++;
            msAcc += f;
            if (++msN == MS) {
                msRing[(int) (msCount & 2047)] = (float) (msAcc / MS);
                msCount++;
                msAcc = 0;
                msN = 0;
                checkVis();
            }
            if (mode != null && (n & 63) == 0) decodeUnits();
        }

        private double avg(int fromAgo, int toAgo) {
            double s = 0;
            for (int a = fromAgo; a > toAgo; a--) s += msRing[(int) ((msCount - a) & 2047)];
            return s / (fromAgo - toAgo);
        }

        private void checkVis() {
            if (msCount < 920 || msCount < holdoffMs) return;
            if (Math.abs(avg(297, 273) - 1200) > 90) return;           // bit de depart
            if (Math.abs(avg(560, 310) - 1900) > 90) return;           // seconde porteuse
            if (Math.abs(avg(27, 3) - 1200) > 90) return;              // bit d'arret
            int code = 0, ones = 0;
            for (int k = 0; k < 8; k++) {
                double f = avg(267 - 30 * k, 243 - 30 * k);
                if (f < 1030 || f > 1370) return;
                int bit = f < 1200 ? 1 : 0;
                ones += bit;
                if (k < 7) code |= bit << k;
            }
            if ((ones & 1) != 0) return;                               // parite paire
            Mode m = byVis(code);
            if (m == null) return;
            // front 1900 -> 1200 du bit de depart, a l'echantillon pres
            long e0 = n - 300L * MS, best = -1;
            for (long s = e0 - 200; s <= e0 + 200; s++) {
                if (s < 5 || n - s > ring.length - 8) continue;
                if (smooth(s - 1) >= 1550 && smooth(s) < 1550 && (best < 0 || Math.abs(s - e0) < Math.abs(best - e0))) best = s;
            }
            if (best < 0) best = e0;
            if (mode != null) finish(false);
            start(m, best + 300L * MS + Math.round(m.initMs * MS));
            holdoffMs = msCount + 1000;
        }

        private double smooth(long s) {
            double a = 0;
            for (long k = s - 4; k < s + 4; k++) a += ring[(int) (k & (ring.length - 1))];
            return a / 8;
        }

        private void start(Mode m, long startSample) {
            mode = m;
            img = new BufferedImage(m.width, m.height, BufferedImage.TYPE_INT_RGB);
            buf = new float[(int) ((m.units() * m.unitMs + 200) * MS)];
            java.util.Arrays.fill(buf, 1500);
            imgStart = startSample;
            for (long s = Math.max(startSample, n - ring.length + 1); s < n; s++) buf[(int) (s - startSample)] = ring[(int) (s & (ring.length - 1))];
            unit = 0;
            syncPts.clear();
            fitOk = false;
            chroma = null;
            yPrev = null;
            idle = 0;
            listener.onStart(m);
        }

        private double unitStart(int k) { return fitOk ? fitA + fitB * k : k * mode.unitMs * MS; }

        private void decodeUnits() {
            Mode m = mode;
            long filled = n - imgStart;
            int margin = 8 * MS;
            while (mode == m && unit < m.units()) {
                double st = unitStart(unit);
                if (filled < st + m.unitMs * MS + margin) return;
                decodeUnit(unit, st);
                trackSync(unit, st);
                unit++;
                listener.onProgress(m, img, rowsDone());
            }
            if (mode == m && unit >= m.units()) finish(true);
        }

        private void trackSync(int k, double st) {
            Mode m = mode;
            int syncS = (int) Math.round(m.syncMs * MS);
            int pred = (int) Math.round(st + m.syncOffMs * MS);
            int win = 6 * MS;
            int lo = Math.max(0, pred - win), hi = Math.min(buf.length - syncS - 1, pred + win);
            if (hi <= lo) return;
            int bestCount = -1, bestFirst = -1, bestLast = -1;
            int count = 0;
            for (int i = lo; i < lo + syncS; i++) if (buf[i] < 1350) count++;
            for (int p = lo; p <= hi; p++) {
                if (count > bestCount) { bestCount = count; bestFirst = bestLast = p; }
                else if (count == bestCount && bestLast == p - 1) bestLast = p;
                if (buf[p] < 1350) count--;
                if (buf[p + syncS] < 1350) count++;
            }
            if (bestCount < syncS * 0.6) return;
            double p = (bestFirst + bestLast) / 2.0;
            syncPts.add(new double[]{k, p - m.syncOffMs * MS});
            refit();
        }

        private void refit() {
            double nominal = mode.unitMs * MS;
            List<double[]> pts = new ArrayList<>(syncPts);
            for (int pass = 0; pass < 3; pass++) {
                if (pts.size() < 6) return;
                double sx = 0, sy = 0, sxx = 0, sxy = 0;
                for (double[] q : pts) { sx += q[0]; sy += q[1]; sxx += q[0] * q[0]; sxy += q[0] * q[1]; }
                int c = pts.size();
                double den = c * sxx - sx * sx;
                if (den <= 0) return;
                double b = (c * sxy - sx * sy) / den, a = (sy - b * sx) / c;
                if (Math.abs(b - nominal) > nominal * 0.02) return;
                List<double[]> keep = new ArrayList<>();
                for (double[] q : pts) if (Math.abs(q[1] - (a + b * q[0])) < 2 * MS) keep.add(q);
                if (keep.size() == pts.size() || pass == 2) {
                    if (keep.size() >= 6) { fitA = a; fitB = b; fitOk = true; }
                    return;
                }
                pts = keep;
            }
        }

        private int[] sample(double st, double ms, int w) {
            int[] out = new int[w];
            double len = ms * MS, px = len / w;
            for (int x = 0; x < w; x++) {
                double a = st + x * px, b = a + px;
                int ia = (int) Math.floor(a), ib = Math.max(ia + 1, (int) Math.ceil(b));
                double s = 0;
                int c = 0;
                for (int i = ia; i < ib; i++) if (i >= 0 && i < buf.length) { s += buf[i]; c++; }
                double f = c > 0 ? s / c : 1500;
                out[x] = clamp((f - 1500) * 255 / 800.0);
            }
            return out;
        }

        private void decodeUnit(int k, double st) {
            Mode m = mode;
            int w = m.width;
            int[][] ch = new int[8][];
            for (int i = 0; i < m.segs.length; i++) {
                Seg s = m.segs[i];
                if (s.channel >= 0) ch[s.channel] = sample(st + m.segStart[i] * MS, s.ms, w);
            }
            int[] row = new int[w];
            switch (m.kind) {
                case KIND_RGB:
                    for (int x = 0; x < w; x++) row[x] = (ch[R][x] << 16) | (ch[G][x] << 8) | ch[B][x];
                    img.setRGB(0, k, w, 1, row, 0, w);
                    break;
                case KIND_R72:
                    for (int x = 0; x < w; x++) row[x] = fromYuv(ch[Y0][x], ch[U][x], ch[V][x]);
                    img.setRGB(0, k, w, 1, row, 0, w);
                    break;
                case KIND_PD:
                    for (int x = 0; x < w; x++) row[x] = fromYuv(ch[Y0][x], ch[U][x], ch[V][x]);
                    img.setRGB(0, 2 * k, w, 1, row, 0, w);
                    for (int x = 0; x < w; x++) row[x] = fromYuv(ch[Y1][x], ch[U][x], ch[V][x]);
                    img.setRGB(0, 2 * k + 1, w, 1, row, 0, w);
                    break;
                default: { // Robot 36
                    boolean even = (k & 1) == 0;
                    if (even) {
                        chroma = new int[][]{ch[C36]};
                        yPrev = ch[Y0];
                        // apercu immediat avec chroma neutre-ancienne
                        for (int x = 0; x < w; x++) row[x] = fromYuv(ch[Y0][x], 128, ch[C36][x]);
                        img.setRGB(0, k, w, 1, row, 0, w);
                    } else {
                        int[] vv = chroma != null ? chroma[0] : ch[C36];
                        int[] y0 = yPrev != null ? yPrev : ch[Y0];
                        for (int x = 0; x < w; x++) row[x] = fromYuv(y0[x], ch[C36][x], vv[x]);
                        img.setRGB(0, k - 1, w, 1, row, 0, w);
                        for (int x = 0; x < w; x++) row[x] = fromYuv(ch[Y0][x], ch[C36][x], vv[x]);
                        img.setRGB(0, k, w, 1, row, 0, w);
                    }
                }
            }
        }

        private void finish(boolean complete) {
            Mode m = mode;
            BufferedImage im = img;
            mode = null;
            img = null;
            buf = null;
            if (m != null) listener.onDone(m, im, complete);
        }
    }
}
