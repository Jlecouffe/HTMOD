/*
 * HTMOD Windows : onglet POCSAG.
 *  - Reception : decodage permanent de l'audio recu (512 / 1200 / 2400 bauds, polarite auto,
 *    BCH 2 bits), tableau des messages, notification Windows, journal decodages.csv.
 *  - Emission : RIC, fonction, debit, alphanumerique / numerique, niveau, pre-compensation,
 *    polarite ; le signal est rendu en PCM 32 kHz et emis par le poste (Transmitter). Emission
 *    limitee aux bandes radioamateur (144-146 / 430-440 MHz), apres confirmation.
 */
package com.htmod;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

final class PocsagPanel extends JPanel {
    interface Host {
        Radio radio();
        Transmitter transmitter();
        boolean sweeping();
        void log(String s);
        void notify(String title, String text);
        void csv(String type, String id, String details);
        /** WAV de diagnostic (si « Enregistrer les WAV » est actif). */
        void saveWav(String prefix, short[] pcm, int n);
        /** Message recu, a transmettre ailleurs (groupe WhatsApp...) : type, texte, heure. */
        default void forward(Pocsag.Message m, String type, String body, String time) {}
    }

    private final Host host;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final MsgModel model = new MsgModel();
    private final JLabel syncLbl = Shack.label(Tr.t("En écoute…"), Shack.mono(11f), Shack.CYAN);
    private final JLabel countLbl = Shack.label(Tr.t("0 message"), Shack.mono(11f), Shack.MUTED);
    private final JLabel txLbl = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    private final Shack.Key sendKey = new Shack.Key(Tr.t("ÉMETTRE")).primary().font(Shack.bold(13f));
    private final ArrayBlockingQueue<short[]> rx = new ArrayBlockingQueue<>(256);
    private volatile Pocsag decoder;

    private int func, baudIdx, levelIdx;
    private boolean numeric, precomp, invert, afsk;
    private static final int[] LEVELS = {40, 60, 80, 100};

    PocsagPanel(Host host) {
        super(new BorderLayout(14, 0));
        this.host = host;
        setOpaque(false);
        func = prefs.getInt("pocsagFunc", 3);
        baudIdx = prefs.getInt("pocsagBaudIdx", 1);
        numeric = prefs.getBoolean("pocsagNumeric", false);
        levelIdx = prefs.getInt("pocsagLevelIdx", 2);
        precomp = prefs.getBoolean("pocsagPre", false);
        invert = prefs.getBoolean("pocsagInv", false);
        afsk = prefs.getBoolean("pocsagAfsk", false);

        // --- reception
        Shack.Card rxCard = new Shack.Card(16);
        rxCard.setLayout(new BorderLayout());
        rxCard.setBorder(new EmptyBorder(12, 2, 6, 2));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(new EmptyBorder(0, 14, 10, 16));
        JPanel ht = new JPanel();
        ht.setOpaque(false);
        ht.setLayout(new BoxLayout(ht, BoxLayout.Y_AXIS));
        ht.add(Shack.label(Tr.t("MESSAGES REÇUS · 512 / 1200 / 2400 BAUDS"), Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED));
        ht.add(syncLbl);
        head.add(ht, BorderLayout.WEST);
        JPanel he = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        he.setOpaque(false);
        he.add(countLbl);
        head.add(he, BorderLayout.EAST);
        rxCard.add(head, BorderLayout.NORTH);
        JTable table = new JTable(model);
        Shack.styleTable(table, 0, 1, 3);
        int[] w = {86, 90, 84, 84, 70, 320};
        for (int i = 0; i < w.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
            if (i < 5) table.getColumnModel().getColumn(i).setMinWidth(w[i]);
        }
        rxCard.add(Shack.scroll(table), BorderLayout.CENTER);
        JTextField rawFrame = Shack.field(prefs.get("pocsagRawFrame", ""), 36);
        rawFrame.setToolTipText(Tr.t("Symboles acceptés : 0123456789*U -)(. Les retours à la ligne sont ignorés."));
        JTextField rawText = Shack.field("", 36);
        rawText.setEditable(false);
        rawText.setForeground(Shack.MUTED);
        rawText.setToolTipText(Tr.t("Le texte alpha reconstitué apparaîtra ici."));
        Shack.Key rawDecode = new Shack.Key(Tr.t("DÉCODER")).font(Shack.mono(11f));
        rawDecode.addActionListener(e -> decodeNumericFrame(rawFrame, rawText));
        rawFrame.addActionListener(e -> decodeNumericFrame(rawFrame, rawText));
        JPanel rawBar = new JPanel();
        rawBar.setOpaque(false);
        rawBar.setLayout(new BoxLayout(rawBar, BoxLayout.Y_AXIS));
        JPanel rawInput = new JPanel(new BorderLayout(8, 0));
        rawInput.setOpaque(false);
        rawInput.add(Shack.label(Tr.t("TRAME"), Shack.mono(11f), Shack.MUTED), BorderLayout.WEST);
        rawInput.add(rawFrame, BorderLayout.CENTER);
        rawInput.add(rawDecode, BorderLayout.EAST);
        JPanel rawOutput = new JPanel(new BorderLayout(8, 0));
        rawOutput.setOpaque(false);
        rawOutput.setBorder(new EmptyBorder(5, 0, 3, 0));
        rawOutput.add(Shack.label(Tr.t("TEXTE"), Shack.mono(11f), Shack.MUTED), BorderLayout.WEST);
        rawOutput.add(rawText, BorderLayout.CENTER);
        rawBar.add(rawInput);
        rawBar.add(rawOutput);
        rxCard.add(rawBar, BorderLayout.SOUTH);
        add(rxCard, BorderLayout.CENTER);

        // --- emission
        Shack.Card tx = new Shack.Card(16);
        tx.setLayout(new BoxLayout(tx, BoxLayout.Y_AXIS));
        tx.setPreferredSize(new Dimension(420, 100));
        tx.add(left(Shack.label(Tr.t("ÉMISSION"), Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED)));
        tx.add(left(caption(Tr.t("RIC (adresse du récepteur)"))));
        JTextField ric = Shack.field(prefs.get("pocsagRic", ""), 10);
        tx.add(left(ric));
        tx.add(left(caption(Tr.t("Fonction"))));
        tx.add(left(keys(new String[]{"0", "1", "2", "3"}, func, i -> func = i)));
        tx.add(left(caption(Tr.t("Débit"))));
        tx.add(left(keys(new String[]{"512 bd", "1200 bd", "2400 bd"}, baudIdx, i -> baudIdx = i)));
        tx.add(left(caption(Tr.t("Type"))));
        tx.add(left(keys(new String[]{Tr.t("Alphanumérique"), Tr.t("Numérique")}, numeric ? 1 : 0, i -> numeric = i == 1)));
        tx.add(left(caption(Tr.t("Message (vide = bip seul)"))));
        JTextField msg = Shack.field(prefs.get("pocsagMsg", ""), 24);
        tx.add(left(msg));
        tx.add(left(caption(Tr.t("Niveau audio"))));
        tx.add(left(keys(new String[]{"40 %", "60 %", "80 %", "100 %"}, levelIdx, i -> levelIdx = i)));
        Shack.Switch pre = new Shack.Switch(Tr.t("Pré-compensation (pré-accentuation)"), precomp);
        pre.addActionListener(e -> precomp = pre.isOn());
        Shack.Switch inv = new Shack.Switch(Tr.t("Inverser la polarité"), invert);
        inv.addActionListener(e -> invert = inv.isOn());
        Shack.Switch afskSw = new Shack.Switch(Tr.t("Modulation AFSK 1200/2200 Hz (entre postes Benshi)"), afsk);
        afskSw.setToolTipText(Tr.t("Passe le passe-bande 300-3100 Hz des postes ; décodé par HTMod, pas par un pager du commerce."));
        afskSw.addActionListener(e -> afsk = afskSw.isOn());
        for (Shack.Switch s : new Shack.Switch[]{pre, inv, afskSw}) {
            s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            tx.add(left(s));
        }
        tx.add(Box.createVerticalStrut(6));
        tx.add(left(txLbl));
        tx.add(Box.createVerticalStrut(8));
        sendKey.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        sendKey.setPreferredSize(new Dimension(380, 48));
        sendKey.addActionListener(e -> send(ric.getText().trim(), msg.getText()));
        tx.add(left(sendKey));
        tx.add(Box.createVerticalStrut(8));
        JLabel note = new JLabel(Tr.t("<html><body style='width:290px'>Émission sur la fréquence du canal ou celle accordée ")
                + Tr.t("dans l'onglet VFO, réservée aux radioamateurs sur une fréquence autorisée (ex. DAPNET 439,9875 MHz). ")
                + Tr.t("Vérifiez la réception avec un second poste ou un SDR.</body></html>"));
        note.setFont(Shack.body(11.5f));
        note.setForeground(Shack.MUTED);
        tx.add(left(note));
        tx.add(Box.createVerticalGlue());
        add(tx, BorderLayout.EAST);

        startDecoder();
        new Timer(500, e -> refreshStatus()).start();
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (c instanceof JTextField) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return c;
    }

    private static JLabel caption(String s) {
        JLabel l = Shack.label(s, Shack.body(12f), Shack.MUTED);
        l.setBorder(new EmptyBorder(10, 2, 4, 0));
        return l;
    }

    private static JPanel keys(String[] labels, int sel, java.util.function.IntConsumer on) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        Shack.choice(p, labels, sel, on);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        return p;
    }

    /** Reinterpretation immediate des symboles affiches par un pager en mode numerique. */
    private void decodeNumericFrame(JTextField input, JTextField output) {
        try {
            Pocsag.AlphaDecode decoded = Pocsag.decodeNumericSymbolsAsAlpha(input.getText());
            prefs.put("pocsagRawFrame", input.getText());
            String status = decoded.bitOffset == 0 ? Tr.t("ASCII 7 bits, alignement standard.")
                    : Tr.t("ASCII 7 bits, décalage détecté : ") + decoded.bitOffset + Tr.t(" bit(s).");
            if (decoded.corrupt > 0) status += " " + decoded.corrupt + Tr.t(" octet(s) non imprimable(s) indiqué(s) par ·.");
            output.setText(decoded.text);
            output.setForeground(decoded.corrupt == 0 ? Shack.GREEN : Shack.AMBER);
            output.setToolTipText(status);
        } catch (IllegalArgumentException ex) {
            output.setText(ex.getMessage());
            output.setForeground(Shack.RED);
            output.setToolTipText(ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ Reception

    // --- diagnostic d'une reception (squelch ouvert) : duree, niveau, synchros, WAV
    private static final int BURST_MAX = Decoders.SAMPLE_RATE * 60;
    private short[] burst = new short[Decoders.SAMPLE_RATE * 4];
    private int burstN;
    private long burstTotal;
    private double burstSq;
    private int burstPeak;
    private long burstHz;

    private void burstAdd(short[] b) {
        if (burstTotal == 0) { Radio r = host.radio(); burstHz = r.isRunning() ? r.currentRxHz() : 0; }
        burstTotal += b.length;
        for (short s : b) { burstSq += s * (double) s; burstPeak = Math.max(burstPeak, Math.abs(s)); }
        if (burstN + b.length <= BURST_MAX) {
            if (burstN + b.length > burst.length) burst = java.util.Arrays.copyOf(burst, Math.min(BURST_MAX, Math.max(burst.length * 2, burstN + b.length)));
            System.arraycopy(b, 0, burst, burstN, b.length);
            burstN += b.length;
        }
    }

    /** Fin de reception : une ligne de journal (et un WAV) pour savoir ce que le poste a vraiment transmis. */
    private void burstEnd(String sync) {
        double secs = burstTotal / (double) Decoders.SAMPLE_RATE;
        if (secs >= 0.3) {
            double rms = Math.sqrt(burstSq / burstTotal);
            host.log(String.format(Locale.ROOT, Tr.t("POCSAG : réception %.1f s"), secs)
                    + (burstHz > 0 ? " · " + Bandscope.mhz(burstHz) + " MHz" : "")
                    + String.format(Locale.ROOT, Tr.t(" · niveau %.0f dBFS (crête %.0f dBFS)"), 20 * Math.log10(Math.max(1, rms) / 32768.0),
                            20 * Math.log10(Math.max(1, burstPeak) / 32768.0))
                    + Tr.t(" · synchro : ") + (sync.isEmpty() ? Tr.t("aucune") : sync));
            if (secs >= 0.5) host.saveWav("poste", burst, burstN);
        }
        burstN = 0;
        burstTotal = 0;
        burstSq = 0;
        burstPeak = 0;
    }

    private void startDecoder() {
        decoder = new Pocsag(Decoders.SAMPLE_RATE, this::onMessage);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    short[] b = rx.poll(600, TimeUnit.MILLISECONDS);
                    if (b == null) { // fin de reception
                        decoder.flush();
                        if (burstTotal > 0) burstEnd(decoder.takeSyncSummary());
                        continue;
                    }
                    burstAdd(b);
                    decoder.process(b, 0, b.length);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    host.log(Tr.t("POCSAG : ") + t2);
                }
            }
        }, "htmod-pocsag");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    /** Audio recu (le tableau peut etre reutilise par l'appelant). */
    void feed(short[] pcm, int len) {
        if (!rx.offer(java.util.Arrays.copyOf(pcm, len))) rx.poll();
    }

    /** Decode un fichier (WAV), hors ligne. */
    void decodeFile(short[] pcm, int rate) {
        new Thread(() -> {
            int[] n = {0};
            Pocsag d = new Pocsag(rate, m -> { n[0]++; onMessage(m); });
            d.process(pcm, 0, pcm.length);
            d.flush();
            host.log(Tr.t("Fichier : ") + n[0] + Tr.t(" message(s) POCSAG"));
        }, "htmod-pocsag-fichier").start();
    }

    private void onMessage(Pocsag.Message m) {
        String type = m.toneOnly ? "bip" : m.isNumeric ? "num" : "alpha";
        String body = m.toneOnly ? Tr.t("(bip seul)") : m.text();
        String rate = m.baud + " bd" + (m.afsk ? " AFSK" : "");
        host.log("POCSAG RIC " + m.address + " F" + m.function + " " + rate + " " + type + " [" + m.demod + (m.votes > 1 ? " ×" + m.votes : "") + "] : " + body.replace('\n', ' '));
        host.csv("POCSAG", String.valueOf(m.address), rate + " / F" + m.function + " / " + type + " / " + body.replace('\n', ' '));
        host.notify("POCSAG RIC " + m.address + " (F" + m.function + ")", body);
        host.forward(m, type, body, new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()));
        SwingUtilities.invokeLater(() -> {
            model.add(m, type, body);
            countLbl.setText(model.getRowCount() + Tr.t(" message(s)"));
        });
    }

    private void refreshStatus() {
        Pocsag d = decoder;
        List<String> s = d == null ? new ArrayList<String>() : d.syncedBauds();
        syncLbl.setText(host.sweeping() ? Tr.t("Réception en pause (balayage bandscope)")
                : s.isEmpty() ? Tr.t("En écoute… (aucune synchro)") : Tr.t("Synchronisé : ") + s + Tr.t(" bauds"));
        if (host.transmitter().isBusy()) return;
        Radio r = host.radio();
        long hz = r.isRunning() ? r.currentTxHz() : 0;
        txLbl.setText(!r.isRunning() ? Tr.t("Poste non connecté")
                : Tr.t("Fréquence d'émission : ") + (hz > 0 ? Bandscope.mhz(hz) + " MHz" + (amateur(hz) ? "" : Tr.t("  (hors bande radioamateur)")) : Tr.t("inconnue")));
        txLbl.setForeground(hz > 0 && !amateur(hz) ? Shack.RED : Shack.MUTED);
    }

    static boolean amateur(long hz) {
        return (hz >= 144_000_000 && hz <= 146_000_000) || (hz >= 430_000_000 && hz <= 440_000_000);
    }

    // ------------------------------------------------------------------ Emission

    private void send(String ricText, String text) {
        send(ricText, text, msg -> JOptionPane.showConfirmDialog(this, msg, Tr.t("Émettre le message POCSAG ?"), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION, this::warn);
    }

    /** Emission ; confirm = demande de confirmation (texte), warn = message d'erreur (Swing ou page web). */
    void send(String ricText, String text, java.util.function.Predicate<String> confirm, java.util.function.Consumer<String> warn) {
        Radio r = host.radio();
        Transmitter t = host.transmitter();
        if (t.isBusy()) { t.cancel(); return; } // la touche sert alors d'arret
        if (!r.isRunning() || !r.audioOpen()) { warn.accept(Tr.t("Connectez d'abord le poste (canal audio ouvert).")); return; }
        if (host.sweeping()) { warn.accept(Tr.t("Arrêtez d'abord le balayage du bandscope.")); return; }
        int address;
        try {
            address = Integer.parseInt(ricText);
            if (address < 0 || address > 2097151) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            warn.accept(Tr.t("RIC invalide (0 à 2097151)."));
            return;
        }
        List<Integer> payload;
        try {
            payload = text.isEmpty() ? new ArrayList<>() : numeric ? Pocsag.numericPayload(text) : Pocsag.alphaPayload(text);
        } catch (IllegalArgumentException e) {
            warn.accept(e.getMessage());
            return;
        }
        if (afsk && Pocsag.BAUDS[baudIdx] == 2400) { warn.accept(Tr.t("AFSK : choisissez 512 ou 1200 bauds.")); return; }
        long hz = r.currentTxHz();
        if (hz <= 0) { warn.accept(Tr.t("Fréquence d'émission inconnue (lecture des canaux en cours, ou émission interdite sur ce canal).")); return; }
        if (!amateur(hz)) {
            warn.accept(Tr.t("Émission refusée : ") + Bandscope.mhz(hz) + Tr.t(" MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz)."));
            return;
        }
        prefs.put("pocsagRic", ricText);
        prefs.put("pocsagMsg", text);
        prefs.putInt("pocsagFunc", func);
        prefs.putInt("pocsagBaudIdx", baudIdx);
        prefs.putBoolean("pocsagNumeric", numeric);
        prefs.putInt("pocsagLevelIdx", levelIdx);
        prefs.putBoolean("pocsagPre", precomp);
        prefs.putBoolean("pocsagInv", invert);
        prefs.putBoolean("pocsagAfsk", afsk);
        int baud = Pocsag.BAUDS[baudIdx];
        boolean[] bits = Pocsag.bitstream(Pocsag.codewords(address, func, payload));
        short[] pcm = afsk ? Pocsag.waveformAfsk(bits, baud, Decoders.SAMPLE_RATE, LEVELS[levelIdx] / 100.0, invert)
                : Pocsag.waveform(bits, baud, Decoders.SAMPLE_RATE, LEVELS[levelIdx] / 100.0, invert, precomp);
        double secs = pcm.length / (double) Decoders.SAMPLE_RATE;
        if (!confirm.test("RIC " + address + Tr.t(" · fonction ") + func + " · " + baud + Tr.t(" bauds · ") + (afsk ? "AFSK · " : "") + String.format(Locale.ROOT, "%.1f", secs)
                        + Tr.t(" s d'émission\nFréquence : ") + Bandscope.mhz(hz) + " MHz\n\n"
                        + Tr.t("Je confirme être radioamateur et émettre sur une fréquence autorisée."))) return;
        boolean started = t.transmit(pcm, true, new Transmitter.Listener() {
            @Override public void onTxStart(double seconds) {
                SwingUtilities.invokeLater(() -> {
                    sendKey.setText(Tr.t("ARRÊTER L'ÉMISSION"));
                    txLbl.setText(Tr.t("Émission en cours sur ") + Bandscope.mhz(hz) + " MHz (" + String.format(Locale.ROOT, "%.1f", seconds) + " s)…");
                    txLbl.setForeground(Shack.AMBER);
                });
                host.log(Tr.t("POCSAG : émission RIC ") + address + Tr.t(" sur ") + Bandscope.mhz(hz) + " MHz, " + baud + " bd" + (afsk ? " AFSK" : ""));
            }

            @Override public void onTxEnd(boolean cancelled) {
                SwingUtilities.invokeLater(() -> {
                    sendKey.setText(Tr.t("ÉMETTRE"));
                    txLbl.setText(cancelled ? Tr.t("Émission interrompue.") : Tr.t("Message émis."));
                    txLbl.setForeground(cancelled ? Shack.RED : Shack.GREEN);
                });
                host.log(Tr.t("POCSAG : ") + (cancelled ? Tr.t("émission interrompue") : Tr.t("message émis")));
            }
        });
        if (!started) warn.accept(Tr.t("Émission impossible : une émission est déjà en cours ou le canal audio est fermé."));
    }

    // ------------------------------------------------------------------ Interface web

    java.util.Map<String, Object> web() {
        List<Object[]> rows = model.rows.size() > 300 ? new ArrayList<>(model.rows.subList(0, 300)) : new ArrayList<>(model.rows);
        java.awt.Color fg = txLbl.getForeground();
        return Json.obj("rows", rows, "sync", syncLbl.getText(), "count", countLbl.getText(), "tx", txLbl.getText(), "txColor", fg,
                "busy", host.transmitter().isBusy(), "sendKey", sendKey.getText(),
                "ric", prefs.get("pocsagRic", ""), "msg", prefs.get("pocsagMsg", ""), "func", func, "baud", baudIdx,
                "numeric", numeric, "level", levelIdx, "pre", precomp, "inv", invert, "afsk", afsk);
    }

    /** Commande web ; renvoie un message d'erreur a afficher, ou null. */
    String webCmd(String a, java.util.Map<String, String> p) {
        if ("stop".equals(a)) { host.transmitter().cancel(); return null; }
        if (!"send".equals(a)) return null;
        func = Integer.parseInt(p.getOrDefault("func", "3"));
        baudIdx = Integer.parseInt(p.getOrDefault("baud", "1"));
        numeric = "1".equals(p.get("numeric"));
        levelIdx = Integer.parseInt(p.getOrDefault("level", "2"));
        precomp = "1".equals(p.get("pre"));
        invert = "1".equals(p.get("inv"));
        afsk = "1".equals(p.get("afsk"));
        String[] err = {null};
        send(p.getOrDefault("ric", "").trim(), p.getOrDefault("msg", ""), msg -> true, s -> err[0] = s);
        return err[0];
    }

    private void warn(String s) {
        JOptionPane.showMessageDialog(this, s, "POCSAG", JOptionPane.WARNING_MESSAGE);
    }

    // ------------------------------------------------------------------ Tableau

    private static final class MsgModel extends AbstractTableModel {
        private static final String[] COLS = {Tr.t("Heure"), "RIC", Tr.t("Fonction"), Tr.t("Débit"), Tr.t("Type"), Tr.t("Message")};
        private final List<Object[]> rows = new ArrayList<>();
        private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

        void add(Pocsag.Message m, String type, String body) {
            rows.add(0, new Object[]{tf.format(new Date()), String.valueOf(m.address), "F" + m.function, m.baud + " bd" + (m.afsk ? " AFSK" : ""),
                    type + (m.badCodewords > 0 ? " ?" : ""), body.replace('\n', ' ')});
            if (rows.size() > 2000) rows.remove(rows.size() - 1);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
    }
}
