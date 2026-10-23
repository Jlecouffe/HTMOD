/*
 * HTMOD Windows : waterfall (spectrogramme) de l'audio recu, en 2D ou 3D (portage Swing de
 * Waterfall.java). Le poste n'envoie que l'audio demodule (PCM 16 bits / 32 kHz) : c'est donc
 * le spectre de l'audio, de 0 a 16 kHz (frequence de Nyquist du flux), pas un spectre RF.
 *  - 2D : waterfall classique defilant (temps vers le bas) ;
 *  - 3D : surface en perspective (hauteur = puissance, profondeur = temps).
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

final class WaterfallPanel extends JPanel {
    private static final int FFT = 1024;           // 32 ms par colonne
    private static final int BINS = FFT / 2;       // 512 bins -> 0..16 kHz
    private static final int HISTORY = 260;        // lignes de temps (2D)
    private static final int COLS3D = 100;         // colonnes de frequence (3D)
    private static final int ROWS3D = 50;          // lignes de temps (3D, ~1,6 s d'historique)
    private static final int AXIS = 18;

    private volatile boolean mode3d;
    private volatile boolean active = true;

    private final short[] ring = new short[FFT * 8];
    private int head, tail, count;
    private final Object lock = new Object();

    private final int[] pixels = new int[BINS * HISTORY];
    private final float[] mags = new float[COLS3D * ROWS3D]; // historique 3D (0 = recent)
    private final BufferedImage img = new BufferedImage(BINS, HISTORY, BufferedImage.TYPE_INT_RGB);

    private final double[] window = hann(FFT);
    private final double[] re = new double[FFT];
    private final double[] im = new double[FFT];
    private final int[] rowColors = new int[BINS];
    private final float[] rowMags = new float[COLS3D];
    private volatile boolean repaintPending;
    /** Recoit chaque nouvelle colonne (100 valeurs 0..1, de 0 a 16 kHz) : mini-spectre de la carte radio. */
    volatile java.util.function.Consumer<float[]> onColumn;

    WaterfallPanel() {
        setBackground(Shack.WELL);
        setPreferredSize(new Dimension(640, 260));
        java.util.Arrays.fill(pixels, 0x000080);
        Thread t = new Thread(this::loop, "htmod-waterfall");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    void set3d(boolean v) { mode3d = v; repaint(); }
    boolean is3d() { return mode3d; }
    void setActive(boolean v) { active = v; }

    void feed(short[] pcm, int off, int len) {
        if (!active) return;
        synchronized (lock) {
            for (int i = 0; i < len; i++) {
                if (count == ring.length) { head = (head + 1) % ring.length; count--; }
                ring[tail] = pcm[off + i];
                tail = (tail + 1) % ring.length;
                count++;
            }
            lock.notifyAll();
        }
    }

    private void loop() {
        short[] frame = new short[FFT];
        while (true) {
            try {
                synchronized (lock) {
                    while (count < FFT) lock.wait(500);
                    for (int i = 0; i < FFT; i++) {
                        frame[i] = ring[head];
                        head = (head + 1) % ring.length;
                    }
                    count -= FFT;
                }
                computeColumn(frame);
                pushColumn();
            } catch (InterruptedException e) {
                return;
            } catch (Throwable ignore) {
            }
        }
    }

    private void computeColumn(short[] frame) {
        for (int i = 0; i < FFT; i++) { re[i] = frame[i] * window[i]; im[i] = 0.0; }
        fft(re, im);
        java.util.Arrays.fill(rowMags, 0f);
        for (int k = 0; k < BINS; k++) {
            // PCM brut (+-32768) : on normalise en pleine echelle avant le passage en dB
            double mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / FFT / 32768.0;
            double db = 20.0 * Math.log10(mag + 1e-9);
            double n = (db + 90.0) / 70.0;       // -90..-20 dBFS -> 0..1
            if (n < 0) n = 0; else if (n > 1) n = 1;
            rowColors[k] = colorMap(n);
            int c = k * COLS3D / BINS;            // regroupement pour la 3D (max)
            if (n > rowMags[c]) rowMags[c] = (float) n;
        }
    }

    private void pushColumn() {
        synchronized (pixels) {
            System.arraycopy(pixels, 0, pixels, BINS, BINS * (HISTORY - 1));
            System.arraycopy(rowColors, 0, pixels, 0, BINS);
        }
        synchronized (mags) {
            System.arraycopy(mags, 0, mags, COLS3D, COLS3D * (ROWS3D - 1));
            System.arraycopy(rowMags, 0, mags, 0, COLS3D);
        }
        java.util.function.Consumer<float[]> oc = onColumn;
        if (oc != null) oc.accept(rowMags.clone());
        if (!repaintPending) {
            repaintPending = true;
            SwingUtilities.invokeLater(() -> { repaintPending = false; repaint(); });
        }
    }

    private static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
        return w;
    }

    private static void fft(double[] xr, double[] xi) {
        int n = xr.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = xr[i]; xr[i] = xr[j]; xr[j] = t;
                t = xi[i]; xi[i] = xi[j]; xi[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double tr = cr * xr[b] - ci * xi[b];
                    double ti = cr * xi[b] + ci * xr[b];
                    xr[b] = xr[a] - tr; xi[b] = xi[a] - ti;
                    xr[a] += tr; xi[a] += ti;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }

    static int colorMap(double v) {
        double x = v * 5.0;
        int r, g, b;
        if (x < 1)      { r = 0;               g = 0;               b = (int) (128 + 127 * x); }
        else if (x < 2) { r = 0;               g = (int) (255 * (x - 1)); b = 255; }
        else if (x < 3) { r = 0;               g = 255;             b = (int) (255 * (3 - x)); }
        else if (x < 4) { r = (int) (255 * (x - 3)); g = 255;        b = 0; }
        else            { r = 255;             g = (int) (255 - 200 * (x - 4)); b = (int) (200 * (x - 4)); }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int c) { return c < 0 ? 0 : (c > 255 ? 255 : c); }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        int w = getWidth(), h = getHeight();
        if (mode3d) draw3D(g, w, h); else draw2D(g, w, h);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(Shack.mono(11f));
        for (int khz = 0; khz <= 16; khz += 2) {
            int lx = (int) (w * (khz / 16f));
            if (!mode3d) {
                g.setColor(new Color(255, 255, 255, 40));
                g.drawLine(lx, 0, lx, h - AXIS);
            }
            g.setColor(khz % 4 == 0 ? Shack.TEXT2 : Shack.MUTED);
            String s = khz + "k";
            int fx = khz == 16 ? lx - g.getFontMetrics().stringWidth(s) - 2 : (khz > 0 ? lx - 6 : lx + 2);
            g.drawString(s, fx, h - 4);
        }
    }

    private void draw2D(Graphics2D g, int w, int h) {
        synchronized (pixels) { img.setRGB(0, 0, BINS, HISTORY, pixels, 0, BINS); }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h - AXIS, null);
    }

    // Surface 3D facon "chaine de montagnes" : chaque ligne de temps est une crete (hauteur =
    // puissance), remplie en opaque pour occulter les lignes du fond. Dessinee de la plus
    // ancienne (au fond, en haut, attenuee) a la plus recente (devant).
    private void draw3D(Graphics2D g, int w, int h) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int topY = 6;
        int botY = h - AXIS;
        float plotH = botY - topY;
        float[] snap = new float[COLS3D * ROWS3D];
        synchronized (mags) { System.arraycopy(mags, 0, snap, 0, snap.length); }

        int[] xs = new int[COLS3D + 2], ys = new int[COLS3D + 2];
        for (int r = ROWS3D - 1; r >= 0; r--) {
            float depth = r / (float) (ROWS3D - 1);   // 0 = devant, 1 = fond
            float scaleX = 1f - 0.42f * depth;         // le fond est plus etroit
            float rowW = w * scaleX;
            float ox = (w - rowW) * 0.5f + depth * (w * 0.015f);
            float baseY = botY - depth * plotH * 0.72f; // le fond remonte
            float hScale = plotH * 0.34f * (1f - 0.35f * depth);
            float dim = 1f - 0.55f * depth;             // le fond est assombri (profondeur)

            for (int c = 0; c < COLS3D; c++) {
                xs[c + 1] = Math.round(ox + c / (float) (COLS3D - 1) * rowW);
                ys[c + 1] = Math.round(baseY - snap[r * COLS3D + c] * hScale);
            }
            xs[0] = Math.round(ox); ys[0] = Math.round(baseY);
            xs[COLS3D + 1] = Math.round(ox + rowW); ys[COLS3D + 1] = Math.round(baseY);
            g.setColor(new Color((int) (9 * dim), (int) (15 * dim), (int) (34 * dim)));
            g.fillPolygon(new Polygon(xs, ys, COLS3D + 2));

            g.setStroke(new BasicStroke(1.5f - 0.6f * depth));
            for (int c = 1; c < COLS3D; c++) {
                int rgb = colorMap(snap[r * COLS3D + c]);
                g.setColor(new Color((int) (((rgb >> 16) & 255) * dim), (int) (((rgb >> 8) & 255) * dim), (int) ((rgb & 255) * dim)));
                g.drawLine(xs[c], ys[c], xs[c + 1], ys[c + 1]);
            }
        }
    }
}
