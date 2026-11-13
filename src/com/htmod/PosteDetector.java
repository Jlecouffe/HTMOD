/*
 * HTMOD : detection du POCSAG dans l'audio Bluetooth d'un poste Benshi (RT-660 mesure), canal en audio plat.
 *
 * La voie audio du poste coupe tout sous 290 Hz (filtre anti-CTCSS en mur de brique, -30 a -50 dB) et la
 * reponse resonne pendant 10 a 20 ms ; avec une excursion de 4,5 kHz l'audio est de plus ecrete a 0,955 de la
 * pleine echelle et compresse. Un comparateur ou un egaliseur lineaire n'y decode rien.
 *
 * Ici le poste est simule : sa reponse impulsionnelle a ete mesuree (sequences pseudo-aleatoires emises par un
 * PlutoSDR, moindres carres), d'ou la reponse a un bit p(t). Un Viterbi a 256 etats (8 derniers bits) compare,
 * sur chaque creneau de bit, le signal recu a ecretage(A * somme des contributions des bits), les bits plus
 * anciens etant pris sur le meilleur survivant ; decision avec un retard de 32 bits.
 * Horloge : avance/retard d'un echantillon sur le meilleur chemin. Preambule : reconnu a sa periodicite sur
 * 2 bits (correlation > 0,85), puis phase et gain cales sur le gabarit du preambule a travers le poste.
 * Valide sur des trames reelles a 512, 1200 et 2400 bauds, excursions 3,5 a 4,5 kHz.
 */
package com.htmod;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PosteDetector {
    private static final int M = 8, D = 32;
    private static final double CLIP = 0.955, K_PHASE = 0.15, K_FREQ = 0.004;
    private static final double RHO_MIN = 0.85, DATA_GAIN = 1.15, A0 = 1.2;
    private static final Map<String, double[]> MODELS = new ConcurrentHashMap<>();

    /** Reponse impulsionnelle mesuree (ressource poste_rt660_<debit>.txt, aussi dans l'APK Android). */
    static double[] model(int baud) {
        String n = baud >= 2400 ? "poste_rt660_2400.txt" : "poste_rt660_1200.txt";
        double[] cached = MODELS.get(n);
        if (cached != null) return cached;
        List<Double> v = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(PosteDetector.class.getResourceAsStream(n), StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (!l.isEmpty() && !l.startsWith("#")) v.add(Double.parseDouble(l));
            }
        } catch (Exception e) {
            throw new IllegalStateException("modele de poste illisible : " + n, e);
        }
        double[] h = new double[v.size()];
        for (int i = 0; i < h.length; i++) h[i] = v.get(i);
        MODELS.put(n, h);
        return h;
    }

    private final double T;
    private final double[] p, pre;
    private final int S = 1 << M, P = 1 << (M + 1), J;
    private final double[] metric = new double[S], nmetric = new double[S];
    private final long[] hist = new long[S], nhist = new long[S];
    private final double[] buf = new double[1 << 15];
    private final int mask = buf.length - 1;
    private final int wMax;
    private final double[][] q;
    private final double[] old, rr, v, bm;
    private long n, slots, lastAcq = -1000;
    private double tk, freq, A = A0, bestRatio = 1;

    PosteDetector(int sampleRate, int baud) {
        T = sampleRate / (double) baud;
        double[] h = model(baud);
        int plen = h.length + (int) Math.ceil(T) + 2, rect = (int) Math.round(T);
        p = new double[plen];
        for (int i = 0; i < plen; i++) {
            double s = 0;
            for (int m = 0; m < rect; m++) { int k = i - m; if (k >= 0 && k < h.length) s += h[k]; }
            p[i] = s;
        }
        J = Math.min(62, (int) (plen / T) + 1);
        int np = (int) Math.ceil(8 * T), nper = (int) (plen / (2 * T)) + 2;
        pre = new double[np];
        for (int i = 0; i < np; i++) {
            double tau = i / 4.0, s = 0;
            for (int j = 0; j < 2 * nper; j++) s += ((j & 1) == 0 ? 1 : -1) * pAt(tau + j * T);
            pre[i] = s;
        }
        wMax = (int) Math.ceil(T * 1.1) + 2;
        q = new double[M + 1][wMax];
        old = new double[wMax]; rr = new double[wMax]; v = new double[wMax]; bm = new double[P];
    }

    private double pAt(double x) {
        if (x < 0) return 0;
        int i = (int) x;
        if (i + 1 >= p.length) return 0;
        double f = x - i;
        return p[i] * (1 - f) + p[i + 1] * f;
    }

    private double preAt(double tau) {
        double per = 2 * T;
        tau %= per;
        if (tau < 0) tau += per;
        return pre[Math.min(pre.length - 1, (int) Math.round(tau * 4))];
    }

    private double r(long i) { return buf[(int) (i & mask)]; }

    private static double clip(double x) { return x > CLIP ? CLIP : x < -CLIP ? -CLIP : x; }

    /** Echantillon suivant (pleine echelle = 1) ; renvoie un bit decide (0 ou 1) ou -1. synced : synchro POCSAG en cours. */
    int next(double x, boolean synced) {
        buf[(int) (n & mask)] = x;
        n++;
        double tStep = T + freq;
        if (n - 1 < (long) Math.ceil(tk + tStep) + 2) return -1;
        int b = slot(tStep);
        slots++;
        if (!synced && (slots & 15) == 0) acquire(tStep); // fenetre jusqu'a la fin du creneau courant
        tk += tStep;
        return b;
    }

    private int slot(double tStep) {
        long s0 = (long) Math.ceil(tk), s1 = (long) Math.ceil(tk + tStep);
        int W = (int) Math.min(wMax, s1 - s0);
        for (int j = 0; j <= M; j++) for (int i = 0; i < W; i++) q[j][i] = pAt(s0 + i - (tk - j * tStep));
        int best = 0;
        for (int s = 1; s < S; s++) if (metric[s] < metric[best]) best = s;
        long hb = hist[best];
        for (int i = 0; i < W; i++) old[i] = 0;
        for (int j = M + 1; j <= J; j++) {
            double sg = ((hb >>> (j - 1)) & 1) == 1 ? -1 : 1;
            for (int i = 0; i < W; i++) old[i] += sg * pAt(s0 + i - (tk - j * tStep));
        }
        for (int i = 0; i < W; i++) rr[i] = r(s0 + i);
        // metriques de branche des 2^(M+1) motifs (bit 0 = bit courant), parcourus en code de Gray
        for (int i = 0; i < W; i++) { double s = old[i]; for (int j = 0; j <= M; j++) s += q[j][i]; v[i] = s; }
        int g = 0;
        for (int c = 0; c < P; c++) {
            if (c > 0) {
                int j = Integer.numberOfTrailingZeros(c);
                g ^= 1 << j;
                double sg = ((g >> j) & 1) == 1 ? -2 : 2;
                double[] qj = q[j];
                for (int i = 0; i < W; i++) v[i] += sg * qj[i];
            }
            double e = 0;
            for (int i = 0; i < W; i++) { double d = rr[i] - clip(A * v[i]); e += d * d; }
            bm[g] = e;
        }
        // Viterbi : etat = M derniers bits (bit 0 = le plus recent)
        double mn = Double.MAX_VALUE;
        for (int s2 = 0; s2 < S; s2++) {
            int bk = s2 & 1, pa = s2 >>> 1, pb = pa | (1 << (M - 1));
            double ma = metric[pa] + bm[((pa << 1) | bk) & (P - 1)], mb = metric[pb] + bm[((pb << 1) | bk) & (P - 1)];
            if (ma <= mb) { nmetric[s2] = ma; nhist[s2] = (hist[pa] << 1) | bk; }
            else { nmetric[s2] = mb; nhist[s2] = (hist[pb] << 1) | bk; }
            if (nmetric[s2] < mn) mn = nmetric[s2];
        }
        int nb = 0;
        for (int s2 = 0; s2 < S; s2++) { metric[s2] = nmetric[s2] - mn; hist[s2] = nhist[s2]; if (metric[s2] == 0) nb = s2; }
        // horloge : meme motif un echantillon plus tot / plus tard, si la decision est nette
        int pat = (int) (hist[nb] & (P - 1));
        if (bm[pat ^ 1] > bm[pat] * 1.5) {
            double em = 0, ep = 0;
            for (int sh = -1; sh <= 1; sh += 2) {
                double e = 0;
                for (int i = 0; i < W; i++) {
                    double s = old[i];
                    for (int j = 0; j <= M; j++) { double qq = pAt(s0 + i - (tk + sh - j * tStep)); s += ((pat >> j) & 1) == 1 ? -qq : qq; }
                    double d = rr[i] - clip(A * s);
                    e += d * d;
                }
                if (sh < 0) em = e; else ep = e;
            }
            double c = (em - ep) / (em + ep + 1e-12);
            tk += K_PHASE * c;
            // les horloges reelles different de quelques dizaines de ppm : une grande plage laisserait le bruit
            // pousser la frequence en butee pendant les longues attentes
            freq = Math.max(-0.003 * T, Math.min(0.003 * T, freq + K_FREQ * c));
        }
        return (int) ((hist[nb] >>> D) & 1);
    }

    /** Preambule reconnu a sa periodicite sur 2 bits ; phase et gain cales sur son gabarit (32 phases x 2 polarites). */
    private void acquire(double tStep) {
        long a0 = (long) Math.ceil(tk - 24 * tStep), a1 = (long) Math.ceil(tk + tStep);
        if (a0 < 0 || a0 < n - buf.length + 4) return;
        double en = 0, c12 = 0, e2 = 0, per = 2 * tStep;
        for (long t = a0; t < a1; t++) {
            double x = r(t), u = t - per;
            long i = (long) Math.floor(u);
            double f = u - i, y = r(i) * (1 - f) + r(i + 1) * f;
            en += x * x; c12 += x * y; e2 += y * y;
        }
        double rho = c12 / Math.sqrt(en * e2 + 1e-12);
        if (rho < RHO_MIN) return;
        double bestE = Double.MAX_VALUE, bestPh = 0, bestA = A;
        for (int k = 0; k < 32; k++) {
            double ph = (k - 16) * T / 32.0;
            for (int pol = -1; pol <= 1; pol += 2) {
                double num = 0, den = 0;
                for (long t = a0; t < a1; t++) {
                    double y = pol * preAt(t - (tk + ph)), x = r(t);
                    if (Math.abs(x) > 0.9 * CLIP) continue;
                    num += x * y; den += y * y;
                }
                if (den <= 0 || num <= 0) continue;
                double gn = Math.max(0.4, Math.min(2.5, num / den)), e = 0;
                for (long t = a0; t < a1; t++) { double d = r(t) - clip(gn * pol * preAt(t - (tk + ph))); e += d * d; }
                if (e < bestE) { bestE = e; bestPh = ph; bestA = gn; }
            }
        }
        double ratio = bestE / (en + 1e-12);
        boolean samePreamble = slots - lastAcq <= 32;
        if (!samePreamble) { bestRatio = ratio; freq = 0; } // nouvelle emission : l'horloge repart de zero
        else if (ratio > 1.5 * bestRatio) return; // fenetre a cheval sur la fin du preambule
        bestRatio = Math.min(bestRatio, ratio);
        tk += bestPh;
        A = bestA * DATA_GAIN;
        lastAcq = slots;
    }
}
