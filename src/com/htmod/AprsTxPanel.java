/*
 * HTMOD Windows : emission APRS (colonne droite de l'onglet APRS).
 * Les parametres viennent du poste (READ_BSS_SETTINGS, GET_APRS_PATH, GET_POSITION) : indicatif et SSID,
 * symbole, commentaire de balise, intervalle, chemin, position GPS (a defaut, la position d'observation
 * de l'onglet Satellites). La trame est confiee au TNC du poste (HT_SEND_DATA), qui l'emet en AFSK 1200
 * sur le canal nomme « APRS » s'il existe, sinon sur le canal du VFO A ; pas besoin du canal audio.
 * Emission manuelle (ÉMETTRE TRAME) ou balise automatique a l'intervalle du poste, en bande radioamateur.
 */
package com.htmod;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

final class AprsTxPanel extends Shack.Card {
    interface Host {
        Radio radio();
        void log(String s);
        /** Trame emise : ajoutee au trafic APRS comme une trame recue. */
        void sent(byte[] frame);
    }

    static final String TOCALL = "APZHTM"; // APZ = logiciel experimental (liste des tocalls APRS)
    static final String DEFAULT_PATH = "WIDE1-1,WIDE2-1";

    private final Host host;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final JTextArea params = new JTextArea();
    private final JTextField infoField = Shack.field("", 24);
    private final JLabel statusLbl = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    private final Shack.Key sendKey = new Shack.Key("ÉMETTRE TRAME").primary().font(Shack.bold(13f));
    private final Shack.Switch autoSw = new Shack.Switch("Balise automatique (intervalle du poste)", false);
    private volatile byte[] pending;
    private long lastSent;

    AprsTxPanel(Host host) {
        super(16);
        this.host = host;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(12, 16, 12, 16));
        setPreferredSize(new Dimension(400, 100));
        add(left(Shack.label("ÉMISSION APRS · RÉGLAGES DU POSTE", Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED)));
        add(Box.createVerticalStrut(8));
        params.setEditable(false);
        params.setOpaque(false);
        params.setFont(Shack.mono(12f));
        params.setForeground(Shack.TEXT2);
        params.setLineWrap(true);
        params.setWrapStyleWord(true);
        params.setMaximumSize(new Dimension(Integer.MAX_VALUE, 220));
        add(left(params));
        Shack.Key reload = new Shack.Key("RELIRE LE POSTE").font(Shack.bold(11.5f));
        reload.addActionListener(e -> reload());
        reload.setPreferredSize(new Dimension(170, 36));
        reload.setMaximumSize(new Dimension(170, 36));
        add(left(reload));
        add(Box.createVerticalStrut(10));
        add(left(caption("Champ d'information (vide = balise de position)")));
        infoField.setText(prefs.get("aprsTxInfo", ""));
        infoField.setToolTipText("Ex. « >En écoute » (statut) ou « :F4ABC    :Bonjour » (message) ; vide = position + commentaire du poste");
        infoField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        add(left(infoField));
        add(Box.createVerticalStrut(6));
        autoSw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        autoSw.addActionListener(e -> setAuto(autoSw.isOn(), this::confirm, this::warn));
        add(left(autoSw));
        add(Box.createVerticalStrut(6));
        add(left(statusLbl));
        add(Box.createVerticalStrut(8));
        sendKey.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        sendKey.setPreferredSize(new Dimension(360, 48));
        sendKey.addActionListener(e -> {
            prefs.put("aprsTxInfo", infoField.getText());
            send(infoField.getText(), this::warn);
        });
        add(left(sendKey));
        add(Box.createVerticalStrut(8));
        JLabel note = new JLabel("<html><body style='width:270px'>La trame est modulée par le TNC du poste (AFSK 1200) "
                + "et émise dès que le canal est libre, sur le canal nommé « APRS » s'il existe, sinon sur celui du VFO A. "
                + "Indicatif, symbole, chemin et commentaire se règlent dans le poste ou son appli. Réservé aux radioamateurs.</body></html>");
        note.setFont(Shack.body(11.5f));
        note.setForeground(Shack.MUTED);
        add(left(note));
        add(Box.createVerticalGlue());
        new Timer(1000, e -> tickAuto()).start();
        refresh();
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JLabel caption(String s) {
        JLabel l = Shack.label(s, Shack.body(12f), Shack.MUTED);
        l.setBorder(new EmptyBorder(6, 0, 4, 0));
        return l;
    }

    private void warn(String s) { JOptionPane.showMessageDialog(this, s, "APRS", JOptionPane.WARNING_MESSAGE); }

    private boolean confirm(String s) {
        return JOptionPane.showConfirmDialog(this, s, "APRS", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void status(String s, Color c) {
        statusLbl.setText(s);
        statusLbl.setForeground(c);
    }

    void reload() {
        Radio r = host.radio();
        if (!r.isRunning()) { warn("Connectez d'abord le poste."); return; }
        r.readAprsSettings();
        status("Lecture des réglages APRS du poste…", Shack.MUTED);
    }

    // ------------------------------------------------------------------ Parametres

    /** Canal d'emission : canal memoire nomme « APRS », sinon -1 (canal du VFO A). */
    private static int aprsChannel(Radio r) {
        for (int i = 0; i < r.channelCount(); i++)
            if (r.channelName(i).toUpperCase(Locale.ROOT).contains("APRS")) return i;
        return -1;
    }

    private static long txHz(Radio r, int ch) {
        if (ch < 0) ch = r.channelA();
        Radio.Channel c = r.channel(ch);
        if (c != null && c.txDisable) return 0;
        return r.channelTxHz(ch);
    }

    private static List<String> path(Radio r) {
        String p = r.aprsPath();
        if (p == null || p.trim().isEmpty()) p = DEFAULT_PATH;
        List<String> l = new ArrayList<>();
        for (String s : p.split("[,\\s]+")) if (!s.isEmpty()) l.add(s.toUpperCase(Locale.ROOT));
        return l;
    }

    /** Position a emettre : GPS du poste, sinon position d'observation ; null si aucune. */
    private double[] position(Radio r) {
        if (r.gpsLocked() && !Double.isNaN(r.gpsLat())) return new double[]{r.gpsLat(), r.gpsLon(), 1};
        try {
            double lat = Double.parseDouble(prefs.get("obsLat", "")), lon = Double.parseDouble(prefs.get("obsLon", ""));
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return new double[]{lat, lon, 0};
        } catch (NumberFormatException ignore) {
        }
        return null;
    }

    /** Rafraichit l'affichage des parametres lus dans le poste (fil Swing). */
    void refresh() {
        Radio r = host.radio();
        Radio.AprsSettings a = r.aprsSettings();
        StringBuilder sb = new StringBuilder();
        if (!r.isRunning()) sb.append("Poste non connecté.");
        else if (a == null) sb.append("Réglages APRS pas encore lus…");
        else {
            int ch = aprsChannel(r);
            long hz = txHz(r, ch);
            double[] pos = position(r);
            sb.append("Indicatif   ").append(a.source().isEmpty() ? "(non réglé dans le poste)" : a.source()).append('\n');
            sb.append("Symbole     ").append(a.symbol.isEmpty() ? "(aucun, /[ par défaut)" : a.symbol + "  " + Aprs.symbolName(symTable(a), symCode(a))).append('\n');
            sb.append("Chemin      ").append(String.join(",", path(r))).append(r.aprsPath() == null || r.aprsPath().isEmpty() ? " (défaut)" : "").append('\n');
            sb.append("Commentaire ").append(a.beacon.isEmpty() ? "(aucun)" : a.beacon).append('\n');
            sb.append("Intervalle  ").append(a.intervalS > 0 ? a.intervalS + " s" : "(non réglé)")
                    .append(a.shareLocation ? " · partage de position actif" : "").append(a.smartBeacon ? " · SmartBeacon" : "").append('\n');
            sb.append("Format      ").append(a.packetFormat == 1 ? "APRS" : "BSS (le poste émet en BSS ; HTMod émet en APRS)").append('\n');
            sb.append("Position    ").append(pos == null ? "aucune (GPS du poste sans fix, pas de position d'observation)"
                    : Aprs.fmtPos(pos[0], pos[1]) + (pos[2] > 0 ? " (GPS du poste)" : " (position d'observation)")).append('\n');
            sb.append("Canal       ").append(ch >= 0 ? (ch + 1) + " " + r.channelName(ch) : "VFO A (pas de canal « APRS »)")
                    .append(hz > 0 ? " · " + Bandscope.mhz(hz) + " MHz" + (PocsagPanel.amateur(hz) ? "" : " (hors bande radioamateur)") : " · émission interdite");
        }
        params.setText(sb.toString());
        boolean busy = pending != null;
        sendKey.setText(busy ? "EN ATTENTE DU CANAL…" : "ÉMETTRE TRAME");
    }

    private static char symTable(Radio.AprsSettings a) { return a.symbol.length() >= 2 ? a.symbol.charAt(0) : '/'; }

    private static char symCode(Radio.AprsSettings a) {
        return a.symbol.length() >= 2 ? a.symbol.charAt(1) : a.symbol.length() == 1 ? a.symbol.charAt(0) : '[';
    }

    /** Champ d'information « !DDMM.mmN/DDDMM.mmE[commentaire » (position non compressee, sans messagerie). */
    static String positionInfo(double lat, double lon, char table, char code, String comment) {
        double la = Math.abs(lat), lo = Math.abs(lon);
        int ld = (int) la, od = (int) lo;
        double lm = (la - ld) * 60, om = (lo - od) * 60;
        if (lm >= 59.995) { ld++; lm = 0; }
        if (om >= 59.995) { od++; om = 0; }
        return String.format(Locale.ROOT, "!%02d%05.2f%c%c%03d%05.2f%c%c%s", ld, lm, lat < 0 ? 'S' : 'N', table,
                od, om, lon < 0 ? 'W' : 'E', code, comment == null ? "" : comment);
    }

    // ------------------------------------------------------------------ Emission

    /** Emet une trame ; info vide = balise de position. warn recoit le message d'erreur (Swing ou page web). */
    void send(String info, java.util.function.Consumer<String> warn) {
        Radio r = host.radio();
        if (!r.isRunning()) { warn.accept("Connectez d'abord le poste."); return; }
        Radio.AprsSettings a = r.aprsSettings();
        if (a == null) { r.readAprsSettings(); warn.accept("Réglages APRS du poste pas encore lus, réessayez dans un instant."); return; }
        if (a.source().isEmpty()) { warn.accept("Aucun indicatif APRS réglé dans le poste (réglages APRS / BSS du poste ou de son appli)."); return; }
        if (pending != null || r.tncBusy()) { warn.accept("Une trame attend déjà que le canal se libère."); return; }
        int ch = aprsChannel(r);
        long hz = txHz(r, ch);
        if (hz <= 0) { warn.accept("Émission interdite sur le canal d'émission (ou canaux pas encore lus)."); return; }
        if (!PocsagPanel.amateur(hz)) {
            warn.accept("Émission refusée : " + Bandscope.mhz(hz) + " MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz).");
            return;
        }
        info = info == null ? "" : info.trim();
        if (info.isEmpty()) {
            double[] pos = position(r);
            info = pos == null ? ">" + a.beacon : positionInfo(pos[0], pos[1], symTable(a), symCode(a), a.beacon);
        }
        byte[] frame;
        try {
            frame = Ax25.encodeUi(a.source(), TOCALL, path(r), info);
        } catch (IllegalArgumentException e) {
            warn.accept("Trame impossible : " + e.getMessage());
            return;
        }
        if (!r.sendTncFrame(frame, ch)) { warn.accept("Le TNC du poste est occupé, réessayez."); return; }
        pending = frame;
        lastSent = System.currentTimeMillis();
        Ax25 p = Ax25.parse(frame);
        host.log("APRS : émission " + (p == null ? "" : p.tnc2()) + " (canal " + (ch >= 0 ? ch + 1 : "VFO A") + ", " + Bandscope.mhz(hz) + " MHz)");
        status("En attente du canal libre…", Shack.AMBER);
        refresh();
    }

    /** Resultat du TNC (Radio.Listener.onTncSent), sur n'importe quel fil. */
    void onSent(boolean ok) {
        byte[] f = pending;
        pending = null;
        if (ok && f != null) host.sent(f);
        SwingUtilities.invokeLater(() -> {
            status(ok ? "Trame émise à " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new java.util.Date())
                    : "Trame non émise (canal occupé ou refus du poste).", ok ? Shack.GREEN : Shack.RED);
            refresh();
        });
    }

    void onDisconnected() {
        pending = null;
        SwingUtilities.invokeLater(this::refresh);
    }

    private void setAuto(boolean on, java.util.function.Predicate<String> confirm, java.util.function.Consumer<String> warn) {
        if (on) {
            Radio r = host.radio();
            Radio.AprsSettings a = r.aprsSettings();
            if (!r.isRunning() || a == null || a.source().isEmpty()) {
                warn.accept(!r.isRunning() ? "Connectez d'abord le poste." : "Réglages APRS du poste non lus ou sans indicatif.");
                on = false;
            } else if (!confirm.test("Balise automatique : une position toutes les " + interval(a) + " s, en " + a.source()
                    + ", tant que HTMod est connecté.\nContinuer ?")) {
                on = false;
            }
        }
        autoSw.setOn(on);
        if (on) lastSent = 0; // premiere balise tout de suite
        host.log("APRS : balise automatique " + (on ? "activée" : "arrêtée"));
    }

    /** Intervalle de la balise automatique : celui du poste, 60 s au minimum (10 min si non regle). */
    private static int interval(Radio.AprsSettings a) { return a.intervalS > 0 ? Math.max(60, a.intervalS) : 600; }

    private void tickAuto() {
        if (!autoSw.isOn()) return;
        Radio r = host.radio();
        Radio.AprsSettings a = r.aprsSettings();
        if (!r.isRunning() || a == null || pending != null) return;
        if (System.currentTimeMillis() - lastSent < interval(a) * 1000L) return;
        send("", s -> {
            lastSent = System.currentTimeMillis(); // pas de nouvel essai avant l'intervalle suivant
            status("Balise automatique : " + s, Shack.RED);
        });
    }

    // ------------------------------------------------------------------ Interface web

    java.util.Map<String, Object> web() {
        return Json.obj("params", params.getText(), "status", statusLbl.getText(), "statusColor", statusLbl.getForeground(),
                "sendKey", sendKey.getText(), "auto", autoSw.isOn(), "info", prefs.get("aprsTxInfo", ""));
    }

    /** Commande web ; renvoie un message d'erreur a afficher, ou null. */
    String webCmd(String a, java.util.Map<String, String> p) {
        String[] err = {null};
        switch (a) {
            case "send":
                String info = p.getOrDefault("info", "");
                prefs.put("aprsTxInfo", info);
                infoField.setText(info);
                send(info, s -> err[0] = s);
                break;
            case "auto": setAuto("1".equals(p.get("on")), s -> true, s -> err[0] = s); break;
            case "reload":
                if (host.radio().isRunning()) host.radio().readAprsSettings();
                else err[0] = "Connectez d'abord le poste.";
                break;
            default: break;
        }
        return err[0];
    }

}
