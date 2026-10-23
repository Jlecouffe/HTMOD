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
    }

    private final Host host;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final MsgModel model = new MsgModel();
    private final JLabel syncLbl = Shack.label("En écoute…", Shack.mono(11f), Shack.CYAN);
    private final JLabel countLbl = Shack.label("0 message", Shack.mono(11f), Shack.MUTED);
    private final JLabel txLbl = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    private final Shack.Key sendKey = new Shack.Key("ÉMETTRE").primary().font(Shack.bold(13f));
    private final ArrayBlockingQueue<short[]> rx = new ArrayBlockingQueue<>(256);
    private volatile Pocsag decoder;

    private int func, baudIdx, levelIdx;
    private boolean numeric, precomp, invert;
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
        ht.add(Shack.label("MESSAGES REÇUS · 512 / 1200 / 2400 BAUDS", Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED));
        ht.add(syncLbl);
        head.add(ht, BorderLayout.WEST);
        head.add(countLbl, BorderLayout.EAST);
        rxCard.add(head, BorderLayout.NORTH);
        JTable table = new JTable(model);
        Shack.styleTable(table, 0, 1, 3);
        int[] w = {86, 90, 84, 84, 70, 320};
        for (int i = 0; i < w.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
            if (i < 5) table.getColumnModel().getColumn(i).setMinWidth(w[i]);
        }
        rxCard.add(Shack.scroll(table), BorderLayout.CENTER);
        add(rxCard, BorderLayout.CENTER);

        // --- emission
        Shack.Card tx = new Shack.Card(16);
        tx.setLayout(new BoxLayout(tx, BoxLayout.Y_AXIS));
        tx.setPreferredSize(new Dimension(420, 100));
        tx.add(left(Shack.label("ÉMISSION", Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED)));
        tx.add(left(caption("RIC (adresse du récepteur)")));
        JTextField ric = Shack.field(prefs.get("pocsagRic", ""), 10);
        tx.add(left(ric));
        tx.add(left(caption("Fonction")));
        tx.add(left(keys(new String[]{"0", "1", "2", "3"}, func, i -> func = i)));
        tx.add(left(caption("Débit")));
        tx.add(left(keys(new String[]{"512 bd", "1200 bd", "2400 bd"}, baudIdx, i -> baudIdx = i)));
        tx.add(left(caption("Type")));
        tx.add(left(keys(new String[]{"Alphanumérique", "Numérique"}, numeric ? 1 : 0, i -> numeric = i == 1)));
        tx.add(left(caption("Message (vide = bip seul)")));
        JTextField msg = Shack.field(prefs.get("pocsagMsg", ""), 24);
        tx.add(left(msg));
        tx.add(left(caption("Niveau audio")));
        tx.add(left(keys(new String[]{"40 %", "60 %", "80 %", "100 %"}, levelIdx, i -> levelIdx = i)));
        Shack.Switch pre = new Shack.Switch("Pré-compensation (pré-accentuation)", precomp);
        pre.addActionListener(e -> precomp = pre.isOn());
        Shack.Switch inv = new Shack.Switch("Inverser la polarité", invert);
        inv.addActionListener(e -> invert = inv.isOn());
        for (Shack.Switch s : new Shack.Switch[]{pre, inv}) {
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
        JLabel note = new JLabel("<html><body style='width:290px'>Émission sur la fréquence du canal ou celle accordée "
                + "dans l'onglet VFO, réservée aux radioamateurs sur une fréquence autorisée (ex. DAPNET 439,9875 MHz). "
                + "Vérifiez la réception avec un second poste ou un SDR.</body></html>");
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

    // ------------------------------------------------------------------ Reception

    private void startDecoder() {
        decoder = new Pocsag(Decoders.SAMPLE_RATE, this::onMessage);
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    short[] b = rx.poll(600, TimeUnit.MILLISECONDS);
                    if (b == null) { decoder.flush(); continue; } // fin de reception
                    decoder.process(b, 0, b.length);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t2) {
                    host.log("POCSAG : " + t2);
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
            host.log("Fichier : " + n[0] + " message(s) POCSAG");
        }, "htmod-pocsag-fichier").start();
    }

    private void onMessage(Pocsag.Message m) {
        String type = m.toneOnly ? "bip" : m.isNumeric ? "num" : "alpha";
        String body = m.toneOnly ? "(bip seul)" : m.text();
        host.log("POCSAG RIC " + m.address + " F" + m.function + " " + m.baud + " bd " + type + " : " + body.replace('\n', ' '));
        host.csv("POCSAG", String.valueOf(m.address), m.baud + " bd / F" + m.function + " / " + type + " / " + body.replace('\n', ' '));
        host.notify("POCSAG RIC " + m.address + " (F" + m.function + ")", body);
        SwingUtilities.invokeLater(() -> {
            model.add(m, type, body);
            countLbl.setText(model.getRowCount() + " message(s)");
        });
    }

    private void refreshStatus() {
        Pocsag d = decoder;
        List<Integer> s = d == null ? new ArrayList<Integer>() : d.syncedBauds();
        syncLbl.setText(host.sweeping() ? "Réception en pause (balayage bandscope)"
                : s.isEmpty() ? "En écoute… (aucune synchro)" : "Synchronisé : " + s + " bauds");
        if (host.transmitter().isBusy()) return;
        Radio r = host.radio();
        long hz = r.isRunning() ? r.currentTxHz() : 0;
        txLbl.setText(!r.isRunning() ? "Poste non connecté"
                : "Fréquence d'émission : " + (hz > 0 ? Bandscope.mhz(hz) + " MHz" + (amateur(hz) ? "" : "  (hors bande radioamateur)") : "inconnue"));
        txLbl.setForeground(hz > 0 && !amateur(hz) ? Shack.RED : Shack.MUTED);
    }

    static boolean amateur(long hz) {
        return (hz >= 144_000_000 && hz <= 146_000_000) || (hz >= 430_000_000 && hz <= 440_000_000);
    }

    // ------------------------------------------------------------------ Emission

    private void send(String ricText, String text) {
        send(ricText, text, msg -> JOptionPane.showConfirmDialog(this, msg, "Émettre le message POCSAG ?", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION, this::warn);
    }

    /** Emission ; confirm = demande de confirmation (texte), warn = message d'erreur (Swing ou page web). */
    void send(String ricText, String text, java.util.function.Predicate<String> confirm, java.util.function.Consumer<String> warn) {
        Radio r = host.radio();
        Transmitter t = host.transmitter();
        if (t.isBusy()) { t.cancel(); return; } // la touche sert alors d'arret
        if (!r.isRunning() || !r.audioOpen()) { warn.accept("Connectez d'abord le poste (canal audio ouvert)."); return; }
        if (host.sweeping()) { warn.accept("Arrêtez d'abord le balayage du bandscope."); return; }
        int address;
        try {
            address = Integer.parseInt(ricText);
            if (address < 0 || address > 2097151) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            warn.accept("RIC invalide (0 à 2097151).");
            return;
        }
        List<Integer> payload;
        try {
            payload = text.isEmpty() ? new ArrayList<>() : numeric ? Pocsag.numericPayload(text) : Pocsag.alphaPayload(text);
        } catch (IllegalArgumentException e) {
            warn.accept(e.getMessage());
            return;
        }
        long hz = r.currentTxHz();
        if (hz <= 0) { warn.accept("Fréquence d'émission inconnue (lecture des canaux en cours, ou émission interdite sur ce canal)."); return; }
        if (!amateur(hz)) {
            warn.accept("Émission refusée : " + Bandscope.mhz(hz) + " MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz).");
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
        int baud = Pocsag.BAUDS[baudIdx];
        short[] pcm = Pocsag.waveform(Pocsag.bitstream(Pocsag.codewords(address, func, payload)), baud, Decoders.SAMPLE_RATE,
                LEVELS[levelIdx] / 100.0, invert, precomp);
        double secs = pcm.length / (double) Decoders.SAMPLE_RATE;
        if (!confirm.test("RIC " + address + " · fonction " + func + " · " + baud + " bauds · " + String.format(Locale.ROOT, "%.1f", secs)
                        + " s d'émission\nFréquence : " + Bandscope.mhz(hz) + " MHz\n\n"
                        + "Je confirme être radioamateur et émettre sur une fréquence autorisée.")) return;
        boolean started = t.transmit(pcm, true, new Transmitter.Listener() {
            @Override public void onTxStart(double seconds) {
                SwingUtilities.invokeLater(() -> {
                    sendKey.setText("ARRÊTER L'ÉMISSION");
                    txLbl.setText("Émission en cours sur " + Bandscope.mhz(hz) + " MHz (" + String.format(Locale.ROOT, "%.1f", seconds) + " s)…");
                    txLbl.setForeground(Shack.AMBER);
                });
                host.log("POCSAG : émission RIC " + address + " sur " + Bandscope.mhz(hz) + " MHz, " + baud + " bd");
            }

            @Override public void onTxEnd(boolean cancelled) {
                SwingUtilities.invokeLater(() -> {
                    sendKey.setText("ÉMETTRE");
                    txLbl.setText(cancelled ? "Émission interrompue." : "Message émis.");
                    txLbl.setForeground(cancelled ? Shack.RED : Shack.GREEN);
                });
                host.log("POCSAG : " + (cancelled ? "émission interrompue" : "message émis"));
            }
        });
        if (!started) warn.accept("Émission impossible : une émission est déjà en cours ou le canal audio est fermé.");
    }

    // ------------------------------------------------------------------ Interface web

    java.util.Map<String, Object> web() {
        List<Object[]> rows = model.rows.size() > 300 ? new ArrayList<>(model.rows.subList(0, 300)) : new ArrayList<>(model.rows);
        java.awt.Color fg = txLbl.getForeground();
        return Json.obj("rows", rows, "sync", syncLbl.getText(), "count", countLbl.getText(), "tx", txLbl.getText(), "txColor", fg,
                "busy", host.transmitter().isBusy(), "sendKey", sendKey.getText(),
                "ric", prefs.get("pocsagRic", ""), "msg", prefs.get("pocsagMsg", ""), "func", func, "baud", baudIdx,
                "numeric", numeric, "level", levelIdx, "pre", precomp, "inv", invert);
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
        String[] err = {null};
        send(p.getOrDefault("ric", "").trim(), p.getOrDefault("msg", ""), msg -> true, s -> err[0] = s);
        return err[0];
    }

    private void warn(String s) {
        JOptionPane.showMessageDialog(this, s, "POCSAG", JOptionPane.WARNING_MESSAGE);
    }

    // ------------------------------------------------------------------ Tableau

    private static final class MsgModel extends AbstractTableModel {
        private static final String[] COLS = {"Heure", "RIC", "Fonction", "Débit", "Type", "Message"};
        private final List<Object[]> rows = new ArrayList<>();
        private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

        void add(Pocsag.Message m, String type, String body) {
            rows.add(0, new Object[]{tf.format(new Date()), String.valueOf(m.address), "F" + m.function, m.baud + " bd",
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
