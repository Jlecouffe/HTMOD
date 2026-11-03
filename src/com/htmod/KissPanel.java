/*
 * HTMOD Windows : reglages du TNC KISS sur port COM, dans la carte RADIO (mode « PORT COM ») :
 * port, debit, relecture des ports. La connexion passe par le bouton SE CONNECTER de la carte ;
 * les trames recues rejoignent le trafic APRS (origine KISS).
 */
package com.htmod;

import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.util.List;
import java.util.prefs.Preferences;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

final class KissPanel extends JPanel implements KissTnc.Listener {
    interface Host {
        void frame(byte[] ax25);
        void log(String s);
        /** Etat du port (fil Swing) : texte pour la ligne d'etat, connecte ou non. */
        void kissStatus(String text, boolean connected);
    }

    private final Host host;
    private final File bridgeExe;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final KissTnc tnc;
    private final JComboBox<String> portBox = new JComboBox<>(), baudBox = new JComboBox<>();
    private volatile boolean active; // ouvert ou en cours d'ouverture

    KissPanel(File bridgeExe, Host host) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        this.host = host;
        this.bridgeExe = bridgeExe;
        tnc = new KissTnc(bridgeExe, this);
        setOpaque(false);
        portBox.setFont(Shack.body(13f));
        portBox.setPreferredSize(new Dimension(108, 34));
        portBox.setToolTipText(Tr.t("Port série du TNC KISS (USB-série, Bluetooth SPP, port virtuel com0com…)"));
        portBox.addActionListener(e -> { if (portBox.getSelectedItem() != null) prefs.put("kissPort", (String) portBox.getSelectedItem()); });
        baudBox.setFont(Shack.body(13f));
        baudBox.setPreferredSize(new Dimension(96, 34));
        for (int b : KissTnc.BAUDS) baudBox.addItem(String.valueOf(b));
        baudBox.setSelectedItem(String.valueOf(prefs.getInt("kissBaud", 9600)));
        baudBox.setToolTipText(Tr.t("Débit du port série (souvent 9600 ; 1200 pour certains TNC anciens)"));
        baudBox.addActionListener(e -> prefs.putInt("kissBaud", Integer.parseInt((String) baudBox.getSelectedItem())));
        Shack.Key refresh = new Shack.Key("↻").font(Shack.bold(15f));
        refresh.setToolTipText(Tr.t("Relire la liste des ports COM"));
        refresh.setPreferredSize(new Dimension(42, 34));
        refresh.addActionListener(e -> refreshPorts());
        add(portBox);
        add(baudBox);
        add(refresh);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        refreshPorts();
    }

    void refreshPorts() {
        new Thread(() -> {
            List<String> ports;
            try { ports = KissTnc.ports(bridgeExe); } catch (Exception e) { ports = new java.util.ArrayList<>(); }
            List<String> l = ports;
            SwingUtilities.invokeLater(() -> {
                String keep = prefs.get("kissPort", "");
                portBox.removeAllItems();
                for (String p : l) portBox.addItem(p);
                if (!keep.isEmpty() && !l.contains(keep)) portBox.addItem(keep); // port memorise absent (adaptateur debranche)
                if (!keep.isEmpty()) portBox.setSelectedItem(keep);
                if (l.isEmpty() && !active) host.kissStatus(Tr.t("Aucun port COM détecté"), false);
            });
        }, "htmod-kiss-ports").start();
    }

    boolean isActive() { return active; }
    String port() { return prefs.get("kissPort", ""); }
    int baud() { return prefs.getInt("kissBaud", 9600); }
    KissTnc tnc() { return tnc; }

    /** Ouvre le port choisi ; renvoie un message d'erreur ou null. */
    String connect() {
        String p = (String) portBox.getSelectedItem();
        if (p == null || p.isEmpty()) p = port();
        if (p.isEmpty()) return Tr.t("Choisissez un port COM.");
        prefs.put("kissPort", p);
        try {
            tnc.connect(p, baud());
            active = true;
            return null;
        } catch (Exception e) {
            return Tr.t("Ouverture impossible : ") + e.getMessage();
        }
    }

    void disconnect() {
        active = false;
        tnc.disconnect();
        host.kissStatus(Tr.t("Déconnecté"), false);
        host.log(Tr.t("TNC KISS : port fermé"));
    }

    // ------------------------------------------------------------------ KissTnc.Listener (fils de fond)

    @Override public void onKissFrame(byte[] ax25) {
        host.frame(ax25);
        int n = tnc.frameCount();
        SwingUtilities.invokeLater(() -> host.kissStatus(Tr.t("TNC KISS sur ") + tnc.port() + " · " + n + Tr.t(" trame(s) reçue(s)"), true));
    }

    @Override public void onKissStatus(String text, boolean connected) {
        if (!connected && !text.startsWith(Tr.t("Ouverture"))) active = false;
        SwingUtilities.invokeLater(() -> host.kissStatus(text, connected));
    }

    @Override public void onKissLog(String text) { host.log(text); }
}
