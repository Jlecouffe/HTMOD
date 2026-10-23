/*
 * HTMOD Windows : onglet SSTV.
 *  - Reception : decodage permanent de l'audio recu (detection VIS, tous les modes de Sstv), image
 *    affichee ligne a ligne, enregistree en PNG dans Documents\HTMod\sstv, galerie des receptions.
 *  - Emission : image choisie (ou mire), recadree au format du mode, texte (indicatif) incruste,
 *    emise par le poste (Transmitter) apres confirmation, bandes radioamateur seulement.
 */
package com.htmod;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

final class SstvPanel extends JPanel {
    interface Host {
        Radio radio();
        Transmitter transmitter();
        boolean sweeping();
        void log(String s);
        void notify(String title, String text);
        void csv(String type, String id, String details);
    }

    private static final int[] LEVELS = {40, 60, 80, 100};

    private final Host host;
    private final File dir;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final ArrayBlockingQueue<short[]> rx = new ArrayBlockingQueue<>(512);
    private volatile Sstv.Decoder decoder;

    private final View rxView = new View("En attente d'une image SSTV…");
    private final View txView = new View("Aucune image");
    private final JLabel rxState = Shack.label("En écoute · détection VIS automatique", Shack.mono(11.5f), Shack.CYAN);
    private final JLabel rxMode = Shack.label(" ", Shack.mono(11f), Shack.MUTED);
    private final JPanel gallery = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JLabel txInfo = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    private final JLabel txFreq = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    private final Shack.Key sendKey = new Shack.Key("ÉMETTRE L'IMAGE").primary().font(Shack.bold(13f));
    private final JTextField overlay;
    private BufferedImage source;
    private Sstv.Mode txMode;
    private int levelIdx;
    private volatile boolean rxOn;
    private Shack.Switch autoSw;

    SstvPanel(Host host, File outDir) {
        super(new BorderLayout(14, 0));
        this.host = host;
        this.dir = new File(outDir, "sstv");
        setOpaque(false);
        txMode = Sstv.byName(prefs.get("sstvMode", "Robot 36"));
        if (txMode == null) txMode = Sstv.MODES[0];
        levelIdx = prefs.getInt("sstvLevelIdx", 2);
        rxOn = prefs.getBoolean("sstvRx", true);

        // --- reception
        Shack.Card rc = new Shack.Card(16);
        rc.setLayout(new BorderLayout(0, 10));
        JPanel head = new JPanel(new BorderLayout(16, 0));
        head.setOpaque(false);
        JPanel ht = new JPanel();
        ht.setOpaque(false);
        ht.setLayout(new BoxLayout(ht, BoxLayout.Y_AXIS));
        ht.add(left(Shack.label("ROBOT 36 / 72 · MARTIN M1 / M2 · SCOTTIE S1 / S2 / DX · PD 90 À 240", Shack.tracked(Shack.mono(10f), 0.1f), Shack.MUTED)));
        ht.add(Box.createVerticalStrut(6));
        ht.add(left(rxState));
        ht.add(Box.createVerticalStrut(2));
        ht.add(left(rxMode));
        ht.setBorder(new EmptyBorder(6, 0, 0, 0));
        head.add(ht, BorderLayout.CENTER);
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        JLabel title = Shack.label("RÉCEPTION SSTV", Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED);
        top.add(title, BorderLayout.WEST);
        JPanel hk = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        hk.setOpaque(false);
        Shack.Switch auto = autoSw = new Shack.Switch("Réception", rxOn);
        auto.setPreferredSize(new Dimension(150, 34));
        auto.addActionListener(e -> {
            rxOn = auto.isOn();
            prefs.putBoolean("sstvRx", rxOn);
            if (!rxOn) { rx.clear(); Sstv.Decoder d = decoder; if (d != null) d.stop(); }
            refreshStatus();
        });
        hk.add(auto);
        Shack.Key stop = new Shack.Key("TERMINER").font(Shack.bold(11.5f));
        stop.addActionListener(e -> { Sstv.Decoder d = decoder; if (d != null) d.stop(); });
        hk.add(stop);
        Shack.Key open = new Shack.Key("DOSSIER").font(Shack.bold(11.5f));
        open.addActionListener(e -> openDir());
        hk.add(open);
        top.add(hk, BorderLayout.EAST);
        head.add(top, BorderLayout.NORTH);
        rc.add(head, BorderLayout.NORTH);
        Shack.Well w = new Shack.Well(12);
        w.setLayout(new BorderLayout());
        w.setBorder(new EmptyBorder(8, 8, 8, 8));
        w.add(rxView);
        rc.add(w, BorderLayout.CENTER);
        gallery.setOpaque(false);
        JScrollPane gs = Shack.scroll(gallery);
        gs.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        gs.setPreferredSize(new Dimension(100, 104));
        gs.setOpaque(false);
        gs.getViewport().setOpaque(false);
        rc.add(gs, BorderLayout.SOUTH);
        add(rc, BorderLayout.CENTER);

        // --- emission
        Shack.Card tx = new Shack.Card(16);
        tx.setLayout(new BoxLayout(tx, BoxLayout.Y_AXIS));
        tx.setPreferredSize(new Dimension(420, 100));
        tx.add(left(Shack.label("ÉMISSION", Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED)));
        tx.add(Box.createVerticalStrut(8));
        Shack.Well pw = new Shack.Well(12);
        pw.setLayout(new BorderLayout());
        pw.setBorder(new EmptyBorder(6, 6, 6, 6));
        pw.add(txView);
        pw.setPreferredSize(new Dimension(380, 240));
        pw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));
        tx.add(left(pw));
        tx.add(Box.createVerticalStrut(8));
        JPanel ik = new JPanel(new GridLayout(1, 2, 8, 0));
        ik.setOpaque(false);
        Shack.Key pick = new Shack.Key("CHOISIR UNE IMAGE…").font(Shack.bold(11.5f));
        pick.addActionListener(e -> chooseImage());
        Shack.Key card = new Shack.Key("MIRE DE TEST").font(Shack.bold(11.5f));
        card.addActionListener(e -> { source = null; prefs.remove("sstvImage"); rebuildPreview(); });
        ik.add(pick);
        ik.add(card);
        ik.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        tx.add(left(ik));
        tx.add(left(caption("Mode")));
        JPanel mk = new JPanel(new GridLayout(0, 4, 6, 6));
        mk.setOpaque(false);
        String[] names = new String[Sstv.MODES.length];
        int sel = 0;
        for (int i = 0; i < names.length; i++) {
            names[i] = Sstv.MODES[i].name.replace("Robot ", "R").replace("Martin ", "").replace("Scottie ", "").replace("PD ", "PD");
            if (Sstv.MODES[i] == txMode) sel = i;
        }
        Shack.Key[] modeKeys = Shack.choice(mk, names, sel, i -> {
            txMode = Sstv.MODES[i];
            prefs.put("sstvMode", txMode.name);
            rebuildPreview();
        });
        for (Shack.Key k : modeKeys) k.setPreferredSize(new Dimension(80, 34));
        mk.setMaximumSize(new Dimension(Integer.MAX_VALUE, 3 * 40));
        tx.add(left(mk));
        tx.add(left(caption("Texte incrusté (indicatif)")));
        overlay = Shack.field(prefs.get("sstvText", ""), 16);
        overlay.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        overlay.addActionListener(e -> rebuildPreview());
        overlay.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { rebuildPreview(); }
        });
        tx.add(left(overlay));
        tx.add(left(caption("Niveau audio")));
        JPanel lv = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lv.setOpaque(false);
        Shack.choice(lv, new String[]{"40 %", "60 %", "80 %", "100 %"}, levelIdx, i -> { levelIdx = i; prefs.putInt("sstvLevelIdx", i); });
        lv.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        tx.add(left(lv));
        tx.add(Box.createVerticalStrut(8));
        tx.add(left(txInfo));
        tx.add(left(txFreq));
        tx.add(Box.createVerticalStrut(8));
        sendKey.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        sendKey.setPreferredSize(new Dimension(380, 48));
        sendKey.addActionListener(e -> send());
        tx.add(left(sendKey));
        tx.add(Box.createVerticalStrut(8));
        JLabel note = new JLabel("<html><body style='width:290px'>Émission sur la fréquence du canal ou celle accordée dans "
                + "l'onglet VFO, réservée aux radioamateurs (ex. 145,500 MHz puis dégagement, 433,400 MHz…). Pensez au "
                + "temps d'émission du poste (TOT) pour les modes longs.</body></html>");
        note.setFont(Shack.body(11.5f));
        note.setForeground(Shack.MUTED);
        tx.add(left(note));
        tx.add(Box.createVerticalGlue());
        add(Shack.scroll(tx), BorderLayout.EAST);

        String last = prefs.get("sstvImage", "");
        if (!last.isEmpty()) {
            try { source = ImageIO.read(new File(last)); } catch (Exception ignore) { source = null; }
        }
        rebuildPreview();
        loadGallery();
        startDecoder();
        new Timer(1000, e -> refreshStatus()).start();
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JLabel caption(String s) {
        JLabel l = Shack.label(s, Shack.body(12f), Shack.MUTED);
        l.setBorder(new EmptyBorder(10, 2, 4, 0));
        return l;
    }

    // ------------------------------------------------------------------ Reception

    private void startDecoder() {
        decoder = new Sstv.Decoder(Decoders.SAMPLE_RATE, new Sstv.Decoder.Listener() {
            @Override public void onStart(Sstv.Mode m) {
                host.log("SSTV : réception " + m.name + " (VIS " + m.vis + ")");
                SwingUtilities.invokeLater(() -> { rxView.set(null); refreshStatus(); });
            }

            @Override public void onProgress(Sstv.Mode m, BufferedImage img, int rows) {
                rxView.setLive(img);
            }

            @Override public void onDone(Sstv.Mode m, BufferedImage img, boolean complete) { received(m, img, complete); }
        });
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    short[] b = rx.poll(600, TimeUnit.MILLISECONDS);
                    if (b == null) { decoder.idle(); continue; }
                    decoder.process(b, 0, b.length);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    host.log("SSTV : " + t2);
                }
            }
        }, "htmod-sstv");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    /** Audio recu (le tableau peut etre reutilise par l'appelant). */
    void feed(short[] pcm, int len) {
        if (!rxOn) return;
        if (!rx.offer(Arrays.copyOf(pcm, len))) rx.poll();
    }

    private void received(Sstv.Mode m, BufferedImage img, boolean complete) {
        Sstv.Decoder d = decoder;
        int rows = 0;
        // lignes decodees : on coupe l'image partielle a la derniere ligne recue
        if (!complete) {
            rows = lastRows(img);
            if (rows < 16) {
                host.log("SSTV : " + m.name + " interrompue trop tôt, image ignorée");
                SwingUtilities.invokeLater(this::refreshStatus);
                return;
            }
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        File f = new File(dir, stamp + "_" + m.name.replace(" ", "") + (complete ? "" : "_partiel") + ".png");
        try {
            dir.mkdirs();
            ImageIO.write(img, "png", f);
        } catch (Exception e) {
            host.log("SSTV : enregistrement impossible : " + e.getMessage());
        }
        String what = m.name + (complete ? "" : " (partielle, " + rows + " lignes)");
        host.log("SSTV : image reçue " + what + " → " + f.getName());
        host.csv("SSTV", m.name, f.getName() + (complete ? "" : " / partielle"));
        host.notify("Image SSTV reçue", what);
        SwingUtilities.invokeLater(() -> {
            rxView.set(img);
            addThumb(f, img, true);
            refreshStatus();
        });
        if (d == null) return;
    }

    /** Derniere ligne non noire (une image interrompue garde le fond noir en bas). */
    private static int lastRows(BufferedImage img) {
        int w = img.getWidth();
        int[] row = new int[w];
        for (int y = img.getHeight() - 1; y >= 0; y--) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int p : row) if ((p & 0xFFFFFF) != 0) return y + 1;
        }
        return 0;
    }

    private void loadGallery() {
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (fs == null) return;
        Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
        new Thread(() -> {
            for (int i = Math.min(fs.length, 40) - 1; i >= 0; i--) {
                File f = fs[i];
                try {
                    BufferedImage im = ImageIO.read(f);
                    if (im != null) SwingUtilities.invokeLater(() -> addThumb(f, im, false));
                } catch (Exception ignore) {}
            }
        }, "htmod-sstv-galerie").start();
    }

    private void addThumb(File f, BufferedImage im, boolean show) {
        int h = 86, w = Math.max(40, im.getWidth() * h / im.getHeight());
        BufferedImage th = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = th.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(im, 0, 0, w, h, null);
        g.dispose();
        JLabel l = new JLabel(new javax.swing.ImageIcon(th));
        l.setToolTipText(f.getName());
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.setBorder(javax.swing.BorderFactory.createLineBorder(Shack.EDGE));
        l.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    try { Desktop.getDesktop().open(f); } catch (Exception ex) { host.log("SSTV : " + ex.getMessage()); }
                } else {
                    rxView.set(im);
                    rxMode.setText(f.getName());
                }
            }
        });
        gallery.add(l, 0);
        gallery.revalidate();
        gallery.repaint();
        if (show) rxMode.setText(f.getName());
    }

    private void openDir() {
        try {
            dir.mkdirs();
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            host.log("SSTV : " + e.getMessage());
        }
    }

    private void refreshStatus() {
        Sstv.Decoder d = decoder;
        if (!rxOn) { rxState.setText("Réception coupée"); rxState.setForeground(Shack.MUTED); }
        else if (host.sweeping()) { rxState.setText("Réception en pause (balayage bandscope)"); rxState.setForeground(Shack.MUTED); }
        else if (d != null && d.busy()) {
            Sstv.Mode m = d.mode();
            if (m != null) {
                rxState.setText("Réception " + m.name + " · " + d.rowsDone() + " / " + m.height + " lignes");
                rxState.setForeground(Shack.AMBER);
            }
        } else { rxState.setText("En écoute · détection VIS automatique"); rxState.setForeground(Shack.CYAN); }
        if (host.transmitter().isBusy()) return;
        Radio r = host.radio();
        long hz = r.isRunning() ? r.currentTxHz() : 0;
        txFreq.setText(!r.isRunning() ? "Poste non connecté"
                : "Fréquence d'émission : " + (hz > 0 ? Bandscope.mhz(hz) + " MHz" + (PocsagPanel.amateur(hz) ? "" : "  (hors bande radioamateur)") : "inconnue"));
        txFreq.setForeground(hz > 0 && !PocsagPanel.amateur(hz) ? Shack.RED : Shack.MUTED);
    }

    // ------------------------------------------------------------------ Emission

    private void chooseImage() {
        JFileChooser fc = new JFileChooser(prefs.get("sstvDir", System.getProperty("user.home")));
        fc.setFileFilter(new FileNameExtensionFilter("Images (PNG, JPEG, GIF, BMP)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            BufferedImage im = ImageIO.read(f);
            if (im == null) throw new Exception("format non reconnu");
            source = im;
            prefs.put("sstvImage", f.getAbsolutePath());
            prefs.put("sstvDir", f.getParent());
            rebuildPreview();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Image illisible : " + e.getMessage(), "SSTV", JOptionPane.WARNING_MESSAGE);
        }
    }

    private BufferedImage txImage() {
        String t = overlay.getText();
        return source == null ? Sstv.testCard(txMode, t) : Sstv.prepare(source, txMode, t);
    }

    private void rebuildPreview() {
        prefs.put("sstvText", overlay.getText().trim());
        txView.set(txImage());
        if (!host.transmitter().isBusy())
            txInfo.setText(txMode.name + " · " + txMode.width + "×" + txMode.height + " · "
                    + String.format(Locale.ROOT, "%.0f s", txMode.seconds()));
    }

    private void send() {
        send(msg -> JOptionPane.showConfirmDialog(this, msg, "Émettre l'image SSTV ?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION, this::warn);
    }

    /** Emission ; confirm = demande de confirmation, warn = message d'erreur (Swing ou page web). */
    void send(java.util.function.Predicate<String> confirm, java.util.function.Consumer<String> warn) {
        Radio r = host.radio();
        Transmitter t = host.transmitter();
        if (t.isBusy()) { t.cancel(); return; }
        if (!r.isRunning() || !r.audioOpen()) { warn.accept("Connectez d'abord le poste (canal audio ouvert)."); return; }
        if (host.sweeping()) { warn.accept("Arrêtez d'abord le balayage du bandscope."); return; }
        long hz = r.currentTxHz();
        if (hz <= 0) { warn.accept("Fréquence d'émission inconnue (lecture des canaux en cours, ou émission interdite sur ce canal)."); return; }
        if (!PocsagPanel.amateur(hz)) {
            warn.accept("Émission refusée : " + Bandscope.mhz(hz) + " MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz).");
            return;
        }
        Sstv.Mode m = txMode;
        BufferedImage img = txImage();
        short[] pcm = Sstv.encode(img, m, Decoders.SAMPLE_RATE, LEVELS[levelIdx] / 100.0);
        double secs = pcm.length / (double) Decoders.SAMPLE_RATE;
        if (!confirm.test(m.name + " · " + String.format(Locale.ROOT, "%.0f", secs) + " s d'émission continue\nFréquence : "
                        + Bandscope.mhz(hz) + " MHz\n\nJe confirme être radioamateur et émettre sur une fréquence autorisée.")) return;
        long t0 = System.currentTimeMillis();
        Timer progress = new Timer(1000, e -> {
            if (!t.isBusy()) return;
            long el = (System.currentTimeMillis() - t0) / 1000;
            txInfo.setText("Émission " + m.name + " : " + el + " / " + Math.round(secs) + " s");
        });
        boolean started = t.transmit(pcm, true, new Transmitter.Listener() {
            @Override public void onTxStart(double seconds) {
                SwingUtilities.invokeLater(() -> {
                    sendKey.setText("ARRÊTER L'ÉMISSION");
                    txInfo.setForeground(Shack.AMBER);
                    progress.start();
                });
                host.log("SSTV : émission " + m.name + " sur " + Bandscope.mhz(hz) + " MHz ("
                        + String.format(Locale.ROOT, "%.0f", seconds) + " s)");
            }

            @Override public void onTxEnd(boolean cancelled) {
                SwingUtilities.invokeLater(() -> {
                    progress.stop();
                    sendKey.setText("ÉMETTRE L'IMAGE");
                    txInfo.setText(cancelled ? "Émission interrompue." : "Image émise (" + m.name + ").");
                    txInfo.setForeground(cancelled ? Shack.RED : Shack.GREEN);
                });
                host.log("SSTV : " + (cancelled ? "émission interrompue" : "image émise"));
            }
        });
        if (!started) warn.accept("Émission impossible : une émission est déjà en cours ou le canal audio est fermé.");
    }

    // ------------------------------------------------------------------ Interface web

    java.util.Map<String, Object> web() {
        java.util.List<String> files = new java.util.ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (fs != null) {
            Arrays.sort(fs, (a, b) -> b.getName().compareTo(a.getName()));
            for (int i = 0; i < Math.min(60, fs.length); i++) files.add(fs[i].getName());
        }
        java.util.List<String> modes = new java.util.ArrayList<>();
        for (Sstv.Mode m : Sstv.MODES) modes.add(m.name);
        return Json.obj("rxOn", rxOn, "state", rxState.getText(), "stateColor", rxState.getForeground(), "mode", rxMode.getText(),
                "rxVersion", rxView.version, "hasRx", rxView.img != null, "txVersion", txView.version, "gallery", files,
                "modes", modes, "txMode", txMode.name, "level", levelIdx, "text", overlay.getText(), "txInfo", txInfo.getText(),
                "txInfoColor", txInfo.getForeground(), "txFreq", txFreq.getText(), "txFreqColor", txFreq.getForeground(),
                "busy", host.transmitter().isBusy(), "sendKey", sendKey.getText(), "custom", source != null);
    }

    /** Image recue affichee (null si aucune). */
    BufferedImage webRxImage() { return rxView.img; }

    /** Apercu de l'image a emettre. */
    BufferedImage webTxImage() { return txView.img; }

    /** Fichier de la galerie (nom simple, sans chemin), ou null. */
    File webFile(String name) {
        if (name == null || !name.matches("[\\w.\\-]+\\.png")) return null;
        File f = new File(dir, name);
        return f.isFile() ? f : null;
    }

    /** Image envoyee depuis la page web. */
    String webUpload(byte[] data) {
        try {
            BufferedImage im = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (im == null) return "Format d'image non reconnu (PNG, JPEG, GIF, BMP).";
            source = im;
            prefs.remove("sstvImage");
            rebuildPreview();
            return null;
        } catch (Exception e) {
            return "Image illisible : " + e.getMessage();
        }
    }

    /** Commande web ; renvoie un message d'erreur ou null. */
    String webCmd(String a, java.util.Map<String, String> p) {
        switch (a) {
            case "rx":
                rxOn = "1".equals(p.get("on"));
                prefs.putBoolean("sstvRx", rxOn);
                if (autoSw != null) autoSw.setOn(rxOn);
                if (!rxOn) { rx.clear(); Sstv.Decoder d = decoder; if (d != null) d.stop(); }
                refreshStatus();
                return null;
            case "finish": { Sstv.Decoder d = decoder; if (d != null) d.stop(); return null; }
            case "mode": {
                Sstv.Mode m = Sstv.byName(p.get("v"));
                if (m != null) { txMode = m; prefs.put("sstvMode", m.name); rebuildPreview(); }
                return null;
            }
            case "level": levelIdx = Integer.parseInt(p.get("v")); prefs.putInt("sstvLevelIdx", levelIdx); return null;
            case "text": overlay.setText(p.getOrDefault("v", "")); rebuildPreview(); return null;
            case "testcard": source = null; prefs.remove("sstvImage"); rebuildPreview(); return null;
            case "show": {
                File f = webFile(p.get("name"));
                if (f != null) {
                    try { rxView.set(ImageIO.read(f)); rxMode.setText(f.getName()); } catch (Exception ignore) {}
                }
                return null;
            }
            case "send": {
                String[] err = {null};
                send(msg -> true, s -> err[0] = s);
                return err[0];
            }
            default: return null;
        }
    }

    private void warn(String s) {        JOptionPane.showMessageDialog(this, s, "SSTV", JOptionPane.WARNING_MESSAGE);
    }

    /** Image ajustee a la zone (proportions conservees), avec texte d'attente. */
    private static final class View extends JComponent {
        private final String empty;
        private volatile BufferedImage img;
        private volatile boolean repaintPending;
        volatile long version;

        View(String empty) { this.empty = empty; setPreferredSize(new Dimension(320, 256)); }

        void set(BufferedImage im) { img = im; version++; repaint(); }

        /** Image en cours de decodage (appele hors du fil Swing, redessins regroupes). */
        void setLive(BufferedImage im) {
            img = im;
            version++;
            if (repaintPending) return;
            repaintPending = true;
            SwingUtilities.invokeLater(() -> { repaintPending = false; repaint(); });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            BufferedImage im = img;
            if (im == null) {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(Shack.mono(12f));
                g.setColor(Shack.MUTED);
                int tw = g.getFontMetrics().stringWidth(empty);
                g.drawString(empty, (getWidth() - tw) / 2, getHeight() / 2);
            } else {
                double s = Math.min(getWidth() / (double) im.getWidth(), getHeight() / (double) im.getHeight());
                int w = (int) (im.getWidth() * s), h = (int) (im.getHeight() * s);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        s >= 2 ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.BLACK);
                g.drawImage(im, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            }
            g.dispose();
        }
    }
}
