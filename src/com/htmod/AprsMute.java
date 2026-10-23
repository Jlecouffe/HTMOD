/*
 * HTMOD Windows : coupure du son des trames APRS sur le haut-parleur du PC (le decodage n'est pas
 * touche). Une trame est reperee de deux facons :
 *  - le poste recoit sur un canal APRS (nom contenant « APRS ») ou une frequence APRS connue ;
 *  - l'audio est domine par les tonalites AFSK 1200 / 2200 Hz : part de l'energie dans deux filtres
 *    passe-bande (biquads) centres sur ces frequences, par blocs de 8 ms.
 * Le son est coupe pendant la trame et 400 ms apres (queue de trame), remplace par du silence.
 */
package com.htmod;

final class AprsMute {
    private static final long[] APRS_HZ = {144_800_000, 144_390_000, 144_640_000, 144_575_000, 144_930_000, 145_175_000, 145_825_000};
    private static final int BLOCK = 256, HOLD_MS = 400;

    private final double[] f1, f2;         // b0, b2, a1, a2 (b1 = 0), normalises
    private double x1, x2, y11, y12, y21, y22;
    private double band, energy;
    private int n;
    private int hits;
    private long holdUntil;

    AprsMute(int rate) {
        f1 = bandpass(1200, 3, rate);
        f2 = bandpass(2200, 4, rate);
    }

    /** Passe-bande RBJ (gain 1 au centre). */
    private static double[] bandpass(double f, double q, int rate) {
        double w = 2 * Math.PI * f / rate, al = Math.sin(w) / (2 * q), a0 = 1 + al;
        return new double[]{al / a0, -al / a0, -2 * Math.cos(w) / a0, (1 - al) / a0};
    }

    static boolean aprsChannel(String channelName, long rxHz) {
        if (channelName != null && channelName.toUpperCase(java.util.Locale.ROOT).contains("APRS")) return true;
        for (long f : APRS_HZ) if (Math.abs(rxHz - f) < 3_000) return true;
        return false;
    }

    /**
     * Analyse le bloc et le remplace par du silence (copie) s'il fait partie d'une trame APRS ;
     * renvoie le tableau a jouer.
     */
    short[] filter(short[] pcm, int len, boolean aprsChannel) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < len; i++) {
            double x = pcm[i] / 32768.0;
            double y1 = f1[0] * x + f1[1] * x2 - f1[2] * y11 - f1[3] * y12;
            double y2 = f2[0] * x + f2[1] * x2 - f2[2] * y21 - f2[3] * y22;
            x2 = x1; x1 = x;
            y12 = y11; y11 = y1;
            y22 = y21; y21 = y2;
            band += y1 * y1 + y2 * y2;
            energy += x * x;
            if (++n == BLOCK) {
                double tones = band / BLOCK, mean = energy / BLOCK;
                // deux blocs AFSK d'affilee (~16 ms) suffisent ; signal assez fort pour ne pas reagir au souffle
                if (mean > 1e-5 && tones > 0.6 * mean) { if (++hits >= 2) holdUntil = now + HOLD_MS; }
                else hits = 0;
                band = energy = 0;
                n = 0;
            }
        }
        if (aprsChannel) holdUntil = now + HOLD_MS;
        if (now < holdUntil) return new short[len];
        return pcm;
    }
}
