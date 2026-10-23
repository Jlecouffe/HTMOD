/*
 * HTMOD Windows : bandscope par balayage. Le poste ne fournit que l'audio demodule ; on l'accorde
 * donc pas a pas en mode frequence (FREQ_MODE_SET_PAR, rien n'est ecrit en memoire) autour de la
 * frequence du canal et on releve son S-metre (GET_HT_STATUS, 0..15) a chaque pas.
 * Valide sur RT-660 : ~190 ms par pas, retour automatique sur le canal a l'arret.
 * Pendant un balayage, le poste n'ecoute plus le canal : les decodages SARSAT/sondes sont en pause.
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

final class Bandscope extends JPanel {
    private static final int SETTLE_MS = 140;   // le S-metre suit le nouvel accord apres ~100 ms
    private static final int STATUS_WAIT_MS = 500;
    private static final int HISTORY = 120;      // balayages gardes dans la cascade

    interface Host {
        Radio radio();
        void log(String s);
        /** Le balayage demarre / s'arrete (pour mettre les decodages en pause). */
        void sweeping(boolean on);
    }

    private final Host host;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final LinkedBlockingQueue<int[]> status = new LinkedBlockingQueue<>();

    // reglages
    private volatile int halfSpanHz = prefs.getInt("bsHalfSpan", 500_000);
    private volatile int stepHz = prefs.getInt("bsStep", 12_500);

    // etat du balayage (lu par le fil Swing)
    private volatile boolean running;
    private volatile long centerHz, parkedHz;
    private volatile int[] levels = new int[0];       // balayage courant (-1 = pas encore mesure)
    private volatile int[] peaks = new int[0];
    private volatile boolean[] squelch = new boolean[0];
    private volatile int cursor = -1;
    private volatile int sweeps;
    private volatile long sweepMs;
    private final int[][] history = new int[HISTORY][];
    private int histHead;
    private Thread worker;

    private final Spectrum spectrum = new Spectrum();
    private final Shack.Key startKey = new Shack.Key("BALAYER").primary().font(Shack.bold(12.5f));
    private final Shack.Key backKey = new Shack.Key("RETOUR AU CANAL").font(Shack.bold(11.5f));
    private final JLabel centerLbl = Shack.label("—", Shack.display(26f), Shack.TEXT);
    private final JLabel infoLbl = Shack.label("Arrêté", Shack.mono(11f), Shack.MUTED);
    private final Shack.Key[] spanKeys, stepKeys;
    private static final int[] SPANS = {100_000, 250_000, 500_000, 1_000_000};
    private static final int[] STEPS = {6_250, 8_333, 12_500, 25_000}; // 8,33 kHz = 25 / 3 kHz (bande aviation)

    Bandscope(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);

        // --- en-tete : frequence centrale, largeur, pas, commandes
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(0, 4, 10, 0));
        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new javax.swing.BoxLayout(left, javax.swing.BoxLayout.Y_AXIS));
        left.add(Shack.label("BANDSCOPE · FRÉQUENCE CENTRALE", Shack.tracked(Shack.mono(10f), 0.16f), Shack.MUTED));
        left.add(centerLbl);
        left.add(infoLbl);
        head.add(left, BorderLayout.WEST);

        JPanel ctl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        ctl.setOpaque(false);
        ctl.add(Shack.label("LARGEUR ±", Shack.tracked(Shack.mono(10f), 0.12f), Shack.MUTED));
        spanKeys = new Shack.Key[SPANS.length];
        for (int i = 0; i < SPANS.length; i++) {
            int v = SPANS[i];
            Shack.Key k = small(v >= 1_000_000 ? (v / 1_000_000) + " MHz" : (v / 1000) + " kHz");
            k.addActionListener(e -> setSpan(v));
            spanKeys[i] = k;
            ctl.add(k);
        }
        ctl.add(Box.createHorizontalStrut(10));
        ctl.add(Shack.label("PAS", Shack.tracked(Shack.mono(10f), 0.12f), Shack.MUTED));
        stepKeys = new Shack.Key[STEPS.length];
        for (int i = 0; i < STEPS.length; i++) {
            int v = STEPS[i];
            Shack.Key k = small(v % 1000 == 0 ? String.valueOf(v / 1000) : String.format(Locale.ROOT, "%.2f", v / 1000.0).replaceAll("0$", ""));
            k.addActionListener(e -> setStep(v));
            stepKeys[i] = k;
            ctl.add(k);
        }
        ctl.add(Box.createHorizontalStrut(10));
        backKey.setPreferredSize(new Dimension(150, 42));
        backKey.setVisible(false);
        backKey.addActionListener(e -> backToChannel());
        ctl.add(backKey);
        startKey.setPreferredSize(new Dimension(130, 42));
        startKey.addActionListener(e -> { if (running) stop(true); else start(); });
        ctl.add(startKey);
        head.add(ctl, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        Shack.Well well = new Shack.Well(14);
        well.setLayout(new BorderLayout());
        well.setBorder(new EmptyBorder(8, 8, 6, 8));
        well.add(spectrum);
        add(well, BorderLayout.CENTER);
        refreshKeys();
    }

    private static Shack.Key small(String t) {
        Shack.Key k = new Shack.Key(t).font(Shack.bold(11.5f));
        k.setPreferredSize(new Dimension(Math.max(58, k.getPreferredSize().width - 6), 38));
        return k;
    }

    private void refreshKeys() {
        for (int i = 0; i < SPANS.length; i++) spanKeys[i].setSelected(SPANS[i] == halfSpanHz);
        for (int i = 0; i < STEPS.length; i++) stepKeys[i].setSelected(STEPS[i] == stepHz);
        startKey.setText(running ? "ARRÊTER" : "BALAYER");
        backKey.setVisible(!running && parkedHz != 0);
        int n = 2 * halfSpanHz / stepHz + 1;
        if (!running) infoLbl.setText(parkedHz != 0
                ? "Calé sur " + mhz(parkedHz) + " MHz (mode fréquence) · " + n + " pas par balayage"
                : "Arrêté · " + n + " pas, ~" + Math.round(n * 0.19) + " s par balayage");
    }

    private void setSpan(int v) { halfSpanHz = v; prefs.putInt("bsHalfSpan", v); restartIfRunning(); }
    private void setStep(int v) { stepHz = v; prefs.putInt("bsStep", v); restartIfRunning(); }

    private void restartIfRunning() {
        if (running) { stop(false); start(); } else { refreshKeys(); spectrum.repaint(); }
    }

    static String mhz(long hz) { return String.format(Locale.ROOT, "%.4f", hz / 1e6); }

    // ------------------------------------------------------------------ Moteur

    /** S-metre recu du poste (appele depuis le fil de liaison). */
    void onHtStatus(int rssi, boolean sq) { status.offer(new int[]{rssi, sq ? 1 : 0}); }

    /** Le canal (et donc la frequence centrale) a change hors balayage. */
    void refreshCenter() {
        if (running || parkedHz != 0) return;
        long c = host.radio().currentRxHz();
        if (c > 0 && c != centerHz) {
            centerHz = c;
            SwingUtilities.invokeLater(() -> { centerLbl.setText(mhz(c) + " MHz"); spectrum.repaint(); });
        }
    }

    void start() {
        Radio r = host.radio();
        if (!r.isRunning()) { host.log("Bandscope : connectez d'abord le poste."); return; }
        long c = parkedHz != 0 ? centerHz : r.currentRxHz();
        if (c <= 0) { host.log("Bandscope : fréquence du canal encore inconnue (lecture des canaux en cours)."); return; }
        centerHz = c;
        parkedHz = 0;
        int n = 2 * halfSpanHz / stepHz + 1;
        levels = new int[n];
        peaks = new int[n];
        squelch = new boolean[n];
        java.util.Arrays.fill(levels, -1);
        synchronized (history) { java.util.Arrays.fill(history, null); histHead = 0; }
        sweeps = 0;
        running = true;
        host.sweeping(true);
        centerLbl.setText(mhz(c) + " MHz");
        refreshKeys();
        host.log("Bandscope : balayage de " + mhz(c - halfSpanHz) + " à " + mhz(c + halfSpanHz) + " MHz, pas " + stepHz + " Hz");
        final int fHalf = halfSpanHz, fStep = stepHz;
        worker = new Thread(() -> loop(c, fHalf, fStep, n), "htmod-bandscope");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop(long center, int half, int step, int n) {
        Radio r = host.radio();
        try {
            while (running && r.isRunning()) {
                long t0 = System.currentTimeMillis();
                int[] lv = levels;
                for (int i = 0; i < n && running && r.isRunning(); i++) {
                    long f = center - half + (long) i * step;
                    status.clear();
                    r.tuneVfo(f, center, false);
                    Thread.sleep(SETTLE_MS);
                    status.clear();
                    r.requestHtStatus();
                    int[] s = status.poll(STATUS_WAIT_MS, TimeUnit.MILLISECONDS);
                    lv[i] = s == null ? 0 : s[0];
                    squelch[i] = s != null && s[1] == 1;
                    int[] pk = peaks;
                    pk[i] = Math.max(lv[i], pk[i]);
                    cursor = i;
                    spectrum.repaintSoon();
                }
                if (!running) break;
                sweepMs = System.currentTimeMillis() - t0;
                sweeps++;
                synchronized (history) {
                    history[histHead] = lv.clone();
                    histHead = (histHead + 1) % HISTORY;
                }
                // decroissance lente de la crete maintenue
                int[] pk = peaks;
                if (sweeps % 3 == 0) for (int i = 0; i < pk.length; i++) pk[i] = Math.max(lv[i], pk[i] - 1);
                SwingUtilities.invokeLater(() -> infoLbl.setText("Balayage n° " + sweeps + " · " + n + " pas en "
                        + String.format(Locale.ROOT, "%.1f", sweepMs / 1000.0) + " s · décodages SARSAT/sondes en pause"));
            }
        } catch (InterruptedException ignore) {
        } finally {
            cursor = -1;
            spectrum.repaintSoon();
        }
    }

    /** Arrete le balayage ; backToChannel = renvoyer le poste sur son canal memoire. */
    void stop(boolean backToChannel) {
        if (!running) return;
        running = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        if (backToChannel && host.radio().isRunning()) host.radio().restoreAfterSweep(); // canal, ou frequence de l'onglet VFO
        host.sweeping(false);
        SwingUtilities.invokeLater(this::refreshKeys);
        host.log("Bandscope : arrêté" + (backToChannel ? ", retour au canal" : ""));
    }

    /** Liaison perdue : rien a envoyer au poste. */
    void onDisconnected() {
        running = false;
        parkedHz = 0;
        host.sweeping(false);
        SwingUtilities.invokeLater(this::refreshKeys);
    }

    /** Double-clic : arret du balayage et ecoute de la frequence choisie (le poste reste en mode frequence). */
    private void park(long f) {
        Radio r = host.radio();
        if (!r.isRunning()) return;
        boolean was = running;
        running = false;
        Thread w = worker;
        if (w != null) w.interrupt();
        try { if (w != null) w.join(800); } catch (InterruptedException ignore) {}
        if (was) host.sweeping(false);
        r.tuneVfo(f, centerHz, false);
        parkedHz = f;
        host.log("Bandscope : écoute de " + mhz(f) + " MHz (RETOUR AU CANAL pour revenir)");
        refreshKeys();
    }

    /** Accord manuel (onglet VFO) : arret du balayage sans renvoyer le poste sur son canal. */
    void cancelForManualTune() {
        if (running) stop(false);
        parkedHz = 0;
        SwingUtilities.invokeLater(this::refreshKeys);
    }

    private void backToChannel() {
        parkedHz = 0;
        if (host.radio().isRunning()) host.radio().restoreAfterSweep();
        host.log("Bandscope : retour au canal");
        refreshKeys();
        refreshCenter();
    }

    // ------------------------------------------------------------------ Interface web

    /** Etat pour l'interface web (fil Swing). */
    java.util.Map<String, Object> web() {
        java.util.List<int[]> hist = new java.util.ArrayList<>();
        synchronized (history) {
            for (int k = 0; k < HISTORY; k++) {
                int[] hl = history[(histHead - 1 - k + HISTORY * 2) % HISTORY];
                if (hl == null) break;
                hist.add(hl);
            }
        }
        return Json.obj("running", running, "center", centerHz, "parked", parkedHz, "half", halfSpanHz, "step", stepHz,
                "levels", levels, "peaks", peaks, "sq", squelch, "cursor", cursor, "sweeps", sweeps,
                "info", infoLbl.getText(), "spans", SPANS, "steps", STEPS, "history", hist);
    }

    /** Commande de l'interface web (fil Swing). */
    void webCmd(String a, java.util.Map<String, String> p) {
        switch (a) {
            case "start": if (running) stop(true); else start(); break;
            case "span": setSpan(Integer.parseInt(p.get("v"))); break;
            case "step": setStep(Integer.parseInt(p.get("v"))); break;
            case "park": if (centerHz > 0) park(Long.parseLong(p.get("f"))); break;
            case "back": backToChannel(); break;
            default: break;
        }
    }

    // ------------------------------------------------------------------ Affichage

    /** Palette de SMeterWaterfallView (Radtel Tools) : bleu nuit -> cyan -> vert -> jaune -> rouge. */
    static Color levelColor(int level) {
        float p = Math.max(0, Math.min(15, level)) / 15f;
        if (p <= 0.01f) return new Color(0x0A0A2A);
        if (p < 0.33f) return lerp(0x0A0A6E, 0x00C8C8, p / 0.33f);
        if (p < 0.66f) return lerp(0x00C8C8, 0x3DDC84, (p - 0.33f) / 0.33f);
        if (p < 0.9f) return lerp(0x3DDC84, 0xFFC107, (p - 0.66f) / 0.24f);
        return lerp(0xFFC107, 0xFF3B30, (p - 0.9f) / 0.1f);
    }

    private static Color lerp(int a, int b, float t) {
        t = Math.max(0, Math.min(1, t));
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return new Color(r, g, bl);
    }

    /** S-metre 0..15 -> libelle approximatif (S1..S9, +10..+40 dB). */
    static String sUnits(int level) {
        if (level <= 0) return "S0";
        if (level <= 9) return "S" + level;
        return "S9+" + (level - 9) * 10;
    }

    private final class Spectrum extends JComponent {
        private volatile boolean pending;
        private int mouseX = -1;

        Spectrum() {
            setPreferredSize(new Dimension(800, 500));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) { mouseX = e.getX(); repaint(); }
                @Override public void mouseExited(MouseEvent e) { mouseX = -1; repaint(); }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && centerHz > 0) park(freqAt(e.getX()));
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            setToolTipText("Double-clic : écouter cette fréquence");
        }

        void repaintSoon() {
            if (pending) return;
            pending = true;
            SwingUtilities.invokeLater(() -> { pending = false; repaint(); });
        }

        private int plotX0() { return 46; }
        private int plotW() { return getWidth() - plotX0() - 10; }

        private long freqAt(int x) {
            int n = 2 * halfSpanHz / stepHz + 1;
            int i = (int) Math.round((x - plotX0()) / (double) plotW() * (n - 1));
            i = Math.max(0, Math.min(n - 1, i));
            return centerHz - halfSpanHz + (long) i * stepHz;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Shack.smooth(g0);
            int w = getWidth(), h = getHeight();
            int x0 = plotX0(), pw = plotW();
            int specTop = 18, specH = (int) (h * 0.48), axisY = specTop + specH;
            int wfTop = axisY + 44, wfH = h - wfTop - 4;
            int[] lv = levels, pk = peaks;
            boolean[] sq = squelch;
            int n = lv.length > 0 ? lv.length : 2 * halfSpanHz / stepHz + 1;

            // grille et echelle S
            g.setFont(Shack.mono(10f));
            for (int s = 0; s <= 15; s += 3) {
                int y = axisY - Math.round(s / 15f * specH);
                g.setColor(Shack.alpha(Shack.EDGE, s == 0 ? 255 : 140));
                g.drawLine(x0, y, x0 + pw, y);
                g.setColor(Shack.MUTED);
                String l = sUnits(s);
                g.drawString(l, x0 - 8 - g.getFontMetrics().stringWidth(l), y + 4);
            }
            // graduations de frequence
            int tick = halfSpanHz >= 500_000 ? 100_000 : halfSpanHz >= 250_000 ? 50_000 : 25_000;
            long c = centerHz;
            if (c > 0) {
                for (long f = ((c - halfSpanHz) / tick + 1) * tick; f <= c + halfSpanHz; f += tick) {
                    int x = x0 + (int) Math.round((f - (c - halfSpanHz)) / (double) (2 * halfSpanHz) * pw);
                    g.setColor(Shack.alpha(Shack.EDGE, 120));
                    g.drawLine(x, specTop, x, axisY);
                    g.setColor(Shack.MUTED);
                    String l = String.format(Locale.ROOT, "%.3f", f / 1e6);
                    int lw = g.getFontMetrics().stringWidth(l);
                    if (x - lw / 2 < x0 - 4 || x + lw / 2 > x0 + pw + 4) continue; // etiquette coupee : on la saute
                    g.drawString(l, x - lw / 2, axisY + 15);
                }
            } else {
                g.setFont(Shack.body(13f));
                g.setColor(Shack.MUTED);
                g.drawString("Connectez le poste puis cliquez sur BALAYER : le bandscope se centre sur la fréquence du canal.", x0 + 10, specTop + 30);
            }

            // barres de niveau (une par pas)
            double bw = pw / (double) Math.max(1, n - 1);
            for (int i = 0; i < lv.length; i++) {
                int x = x0 + (int) Math.round(i * bw - bw / 2);
                int wbar = Math.max(1, (int) Math.round(bw) - (bw > 4 ? 1 : 0));
                if (pk.length == lv.length && pk[i] > 0) {
                    int yp = axisY - Math.round(pk[i] / 15f * specH);
                    g.setColor(Shack.alpha(Shack.AMBER, 110));
                    g.fillRect(x, yp, wbar, 2);
                }
                if (lv[i] < 0) continue;
                int hh = Math.max(1, Math.round(lv[i] / 15f * specH));
                Color col = sq.length == lv.length && sq[i] ? Shack.AMBER : Shack.CYAN;
                g.setColor(Shack.alpha(col, 60));
                g.fillRect(x, axisY - hh, wbar, hh);
                g.setColor(col);
                g.fillRect(x, axisY - hh, wbar, Math.min(2, hh));
            }
            // marqueur central (votre frequence)
            if (c > 0) {
                int xc = x0 + pw / 2;
                g.setColor(Shack.AMBER);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 6f}, 0f));
                g.drawLine(xc, specTop - 6, xc, h - 4);
                g.setStroke(new BasicStroke(1f));
                g.setFont(Shack.monoBold(11f));
                String l = (parkedHz != 0 ? "canal " : "") + mhz(c);
                g.drawString(l, xc + 6, specTop + 4);
            }
            // position du balayage
            int cur = cursor;
            if (running && cur >= 0) {
                int x = x0 + (int) Math.round(cur * bw);
                g.setColor(Shack.alpha(Shack.CYAN, 160));
                g.drawLine(x, specTop, x, axisY);
            }
            // frequence ecoutee (double-clic)
            if (parkedHz != 0 && c > 0) {
                int x = x0 + (int) Math.round((parkedHz - (c - halfSpanHz)) / (double) (2 * halfSpanHz) * pw);
                g.setColor(Shack.GREEN);
                g.setStroke(new BasicStroke(2f));
                g.drawLine(x, specTop, x, axisY);
                g.setStroke(new BasicStroke(1f));
            }

            // cascade des balayages (le plus recent en haut)
            g.setColor(Shack.alpha(Shack.EDGE, 200));
            g.drawRect(x0, wfTop, pw, wfH);
            g.setFont(Shack.tracked(Shack.mono(9.5f), 0.14f));
            g.setColor(Shack.MUTED);
            g.drawString("CASCADE DES BALAYAGES", x0, wfTop - 6);
            int rowH = 6; // une ligne par balayage, la plus recente en haut
            BufferedImage row = null;
            synchronized (history) {
                for (int k = 0; k < HISTORY; k++) {
                    int[] hl = history[(histHead - 1 - k + HISTORY * 2) % HISTORY];
                    if (hl == null) break;
                    int y = wfTop + 1 + k * rowH;
                    if (y + rowH > wfTop + wfH) break;
                    if (row == null || row.getWidth() != hl.length) row = new BufferedImage(hl.length, 1, BufferedImage.TYPE_INT_RGB);
                    for (int i = 0; i < hl.length; i++) row.setRGB(i, 0, levelColor(hl[i]).getRGB());
                    g.drawImage(row, x0 + 1, y, pw - 1, rowH, null);
                }
            }

            // lecture au curseur
            if (mouseX >= x0 && mouseX <= x0 + pw && c > 0) {
                long f = freqAt(mouseX);
                int i = (int) ((f - (c - halfSpanHz)) / stepHz);
                String l = mhz(f) + " MHz" + (i >= 0 && i < lv.length && lv[i] >= 0 ? "  " + sUnits(lv[i]) : "");
                g.setColor(Shack.alpha(Shack.TEXT, 90));
                g.drawLine(mouseX, specTop, mouseX, axisY);
                g.setFont(Shack.mono(11f));
                FontMetrics fm = g.getFontMetrics();
                int lw = fm.stringWidth(l) + 12;
                int lx = Math.min(mouseX + 8, w - lw - 4);
                g.setColor(Shack.alpha(Shack.BG, 220));
                g.fillRoundRect(lx, specTop + 14, lw, 20, 8, 8);
                g.setColor(Shack.TEXT);
                g.drawString(l, lx + 6, specTop + 28);
            }
            g.dispose();
        }
    }
}
