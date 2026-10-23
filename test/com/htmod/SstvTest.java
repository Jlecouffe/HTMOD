package com.htmod;

import java.awt.image.BufferedImage;
import java.util.Random;

/** Aller-retour SSTV : encodage, bruit, decalage d'horloge, decodage (VIS, image). */
final class SstvTest {
    private SstvTest() {}

    static void run() {
        System.out.println("SSTV :");
        String[] modes = {"Robot 36", "Robot 72", "Martin M1", "Martin M2", "Scottie S1", "Scottie S2", "PD 90", "PD 120", "PD 180"};
        for (String name : modes) {
            Sstv.Mode m = Sstv.byName(name);
            boolean skew = !name.equals("Martin M2");
            roundTrip(m, skew ? 1.002 : 1.0, 0.04);
        }
        muteCheck();
    }

    /** Coupure du son APRS : AFSK 1200 bauds coupee, voix (melange de frequences) et souffle conserves. */
    private static void muteCheck() {
        int rate = 32000, n = rate / 2;
        Random rnd = new Random(3);
        short[] afsk = new short[n], voice = new short[n], hiss = new short[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            boolean mark = ((i * 1200 / rate) * 2654435761L & 4) == 0; // bits pseudo-aleatoires
            ph += 2 * Math.PI * (mark ? 1200 : 2200) / rate;
            afsk[i] = (short) (12000 * Math.sin(ph) + rnd.nextGaussian() * 600);
            double t = i / (double) rate;
            voice[i] = (short) (5000 * Math.sin(2 * Math.PI * 180 * t) + 4000 * Math.sin(2 * Math.PI * 520 * t + 1)
                    + 3000 * Math.sin(2 * Math.PI * 930 * t) + 2500 * Math.sin(2 * Math.PI * 1650 * t + 2) + rnd.nextGaussian() * 800);
            hiss[i] = (short) (rnd.nextGaussian() * 6000);
        }
        WinTest.check("son APRS : trame AFSK coupée", muted(afsk));
        WinTest.check("son APRS : voix et souffle conservés", !muted(voice) && !muted(hiss));
    }

    private static boolean muted(short[] pcm) {
        AprsMute m = new AprsMute(32000);
        int silent = 0, blocks = 0;
        for (int i = 0; i + 640 <= pcm.length; i += 640) {
            short[] b = java.util.Arrays.copyOfRange(pcm, i, i + 640);
            short[] out = m.filter(b, b.length, false);
            if (i >= 6400) { blocks++; if (out != b) silent++; }
        }
        return silent > blocks * 0.9;
    }

    private static void roundTrip(Sstv.Mode m, double ratio, double noise) {
        BufferedImage src = Sstv.testCard(m, "F4TEST");
        short[] pcm = Sstv.encode(src, m, 32000, 0.7);
        // 0,7 s de bruit avant, decalage d'horloge (reechantillonnage lineaire), bruit blanc
        Random rnd = new Random(1);
        int pre = 22400;
        int outLen = (int) ((pcm.length) / ratio) + pre + 32000;
        short[] in = new short[outLen];
        for (int i = 0; i < outLen; i++) {
            double v = 0;
            int j = i - pre;
            if (j >= 0) {
                double p = j * ratio;
                int a = (int) p;
                if (a + 1 < pcm.length) v = pcm[a] + (pcm[a + 1] - pcm[a]) * (p - a);
            }
            v += rnd.nextGaussian() * noise * 32767;
            in[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        final Sstv.Mode[] got = {null};
        final BufferedImage[] img = {null};
        final boolean[] complete = {false};
        Sstv.Decoder d = new Sstv.Decoder(32000, new Sstv.Decoder.Listener() {
            @Override public void onStart(Sstv.Mode mode) { got[0] = mode; }
            @Override public void onProgress(Sstv.Mode mode, BufferedImage im, int rows) {}
            @Override public void onDone(Sstv.Mode mode, BufferedImage im, boolean c) { img[0] = im; complete[0] = c; }
        });
        for (int i = 0; i < in.length; i += 1000) d.process(in, i, Math.min(1000, in.length - i));
        double err = img[0] == null ? 999 : meanError(src, img[0]);
        WinTest.check(String.format(java.util.Locale.ROOT, "%-10s VIS %d %s, écart moyen %.1f / 255%s", m.name, m.vis,
                got[0] == m ? "reconnu" : "NON reconnu", err, ratio != 1.0 ? " (horloge +0,2 %)" : ""),
                got[0] == m && complete[0] && err < 14);
    }

    /** Ecart moyen par composante, en ignorant les 4 % de bord (filtrage aux transitions). */
    private static double meanError(BufferedImage a, BufferedImage b) {
        int w = a.getWidth(), h = a.getHeight(), bx = w / 25;
        double s = 0;
        long c = 0;
        for (int y = 2; y < h - 2; y++)
            for (int x = bx; x < w - bx; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                for (int sh = 0; sh <= 16; sh += 8) { s += Math.abs(((p >> sh) & 0xFF) - ((q >> sh) & 0xFF)); c++; }
            }
        return s / c;
    }
}
