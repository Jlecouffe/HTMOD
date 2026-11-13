/*
 * HTMOD Windows : onglet VFO.
 *  - Ecoute des 2 VFO : double veille (reglage double_channel du poste, comme HTCommander), avec
 *    les canaux du VFO A et du VFO B et le VFO en reception. Les decodeurs suivent le VFO qui recoit.
 *  - Groupes et canaux memoire : choix du groupe (SET_REGION) et du canal de chaque VFO (reglages).
 *  - Changer de frequence : accord du poste sur une frequence libre en mode frequence (commande
 *    validee sur RT-660 : rien n'est ecrit en memoire), retour aux canaux memoire.
 */
package com.htmod;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

final class VfoPanel extends JPanel {
    interface Host {
        Radio radio();
        void log(String s);
        /** Arrete le balayage du bandscope (avant un accord manuel). */
        void stopSweep();
        /** Emission (PTT avec le micro du PC). */
        Transmitter transmitter();
    }

    private final Host host;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final JLabel vfoA = Shack.label("–", Shack.display(22f), Shack.TEXT);
    private final JLabel vfoB = Shack.label("–", Shack.display(22f), Shack.TEXT);
    private final JLabel freqA = Shack.label(" ", Shack.mono(12f), Shack.MUTED);
    private final JLabel freqB = Shack.label(" ", Shack.mono(12f), Shack.MUTED);
    private final Shack.Chip dualChip = new Shack.Chip();
    private final Shack.Key dualOn = new Shack.Key(Tr.t("DOUBLE VEILLE")).font(Shack.bold(12f));
    private final Shack.Key dualOff = new Shack.Key(Tr.t("UN SEUL VFO")).font(Shack.bold(12f));
    private final JLabel tuneState = Shack.label(" ", Shack.mono(12f), Shack.CYAN);
    private final JTextField freq;
    private volatile boolean pending, chPending;
    private final JComboBox<String> groupBox = new JComboBox<>();
    private boolean fillingGroups;
    private final List<Integer> rows = new ArrayList<>();
    private final ChannelModel chModel = new ChannelModel();
    private final JTable chTable = new JTable(chModel);
    private final JLabel chState = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    // poste : volume, squelch, PTT
    private final JLabel volLbl = Shack.label("–", Shack.display(22f), Shack.TEXT);
    private final JLabel sqLbl = Shack.label("–", Shack.display(22f), Shack.TEXT);
    private final Shack.Key pttKey = new Shack.Key(Tr.t("PTT · MAINTENIR POUR ÉMETTRE")).primary().font(Shack.bold(14f));
    private final JLabel pttState = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);
    private final MicMeter meter = new MicMeter();
    private static final int[] GAINS = {50, 100, 150, 200};
    private int gainIdx;
    private volatile boolean pttHeld;
    private long pttStart;

    VfoPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        setOpaque(false);
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));

        // --- ecoute des 2 VFO
        col.add(left(Shack.section(Tr.t("Écoute des 2 VFO"))));
        Shack.Card dw = new Shack.Card(16);
        dw.setLayout(new BoxLayout(dw, BoxLayout.Y_AXIS));
        JPanel ab = new JPanel(new java.awt.GridLayout(1, 2, 16, 0));
        ab.setOpaque(false);
        ab.add(vfoBox("VFO A", vfoA, freqA));
        ab.add(vfoBox("VFO B", vfoB, freqB));
        dw.add(left(ab));
        dw.add(Box.createVerticalStrut(10));
        JPanel chipRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        chipRow.setOpaque(false);
        chipRow.add(dualChip);
        dw.add(left(chipRow));
        dw.add(Box.createVerticalStrut(10));
        JPanel keys = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        keys.setOpaque(false);
        for (Shack.Key k : new Shack.Key[]{dualOn, dualOff}) {
            k.setPreferredSize(new Dimension(170, 42));
            keys.add(k);
        }
        dualOn.addActionListener(e -> setDual(true));
        dualOff.addActionListener(e -> setDual(false));
        dw.add(left(keys));
        dw.add(Box.createVerticalStrut(8));
        dw.add(left(note(Tr.t("Le poste surveille le VFO A et le VFO B et bascule sur celui qui reçoit (canaux choisis sur le ")
                + Tr.t("poste). Les décodeurs (APRS, SARSAT, sondes, POCSAG) suivent le VFO qui reçoit."))));
        col.add(fill(dw));

        // --- poste : volume, squelch, emission
        col.add(left(Shack.section(Tr.t("Poste : volume, squelch, émission"))));
        Shack.Card pc = new Shack.Card(16);
        pc.setLayout(new BoxLayout(pc, BoxLayout.Y_AXIS));
        JPanel vs = new JPanel(new java.awt.GridLayout(1, 2, 16, 0));
        vs.setOpaque(false);
        vs.add(stepper(Tr.t("VOLUME DU POSTE (0–15)"), volLbl, d -> {
            Radio r = host.radio();
            if (!r.isRunning() || r.volume() < 0) return;
            r.setVolume(r.volume() + d);
            refresh();
        }));
        vs.add(stepper(Tr.t("SQUELCH (0 = OUVERT · 9)"), sqLbl, d -> {
            Radio r = host.radio();
            if (!r.isRunning() || r.squelch() < 0) return;
            r.setSquelch(r.squelch() + d);
        }));
        pc.add(left(vs));
        pc.add(Box.createVerticalStrut(12));
        JPanel pr0 = new JPanel(new BorderLayout(12, 0));
        pr0.setOpaque(false);
        pttKey.setPreferredSize(new Dimension(330, 60));
        pttKey.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (SwingUtilities.isLeftMouseButton(e)) pttDown(); }
            @Override public void mouseReleased(MouseEvent e) { pttUp(); }
        });
        pr0.add(pttKey, BorderLayout.WEST);
        JPanel side = new JPanel();
        side.setOpaque(false);
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.add(left(Shack.label(Tr.t("MICRO DU PC"), Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED)));
        side.add(Box.createVerticalStrut(4));
        meter.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(meter);
        side.add(Box.createVerticalStrut(6));
        JPanel gk = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        gk.setOpaque(false);
        gk.add(Shack.label("GAIN", Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED));
        gainIdx = Math.max(0, Math.min(GAINS.length - 1, prefs.getInt("pttGainIdx", 1)));
        Shack.Key[] gks = Shack.choice(gk, new String[]{"50 %", "100 %", "150 %", "200 %"}, gainIdx, i -> {
            gainIdx = i;
            prefs.putInt("pttGainIdx", i);
        });
        for (Shack.Key k : gks) k.setPreferredSize(new Dimension(64, 32));
        side.add(left(gk));
        pr0.add(side, BorderLayout.CENTER);
        pc.add(left(pr0));
        pc.add(Box.createVerticalStrut(8));
        pc.add(left(pttState));
        pc.add(Box.createVerticalStrut(4));
        pc.add(left(note(Tr.t("Maintenez le bouton PTT enfoncé pour parler dans le micro du PC : le poste émet sur la fréquence du ")
                + Tr.t("VFO actif (ou celle accordée ci-dessous) et repasse en réception au relâchement. Coupure de sécurité ")
                + Tr.t("après 3 minutes. Le volume et le squelch sont ceux du poste lui-même."))));
        col.add(fill(pc));

        // --- groupes et canaux memoire
        col.add(left(Shack.section(Tr.t("Groupes et canaux mémoire"))));
        Shack.Card gc = new Shack.Card(16);
        gc.setLayout(new BoxLayout(gc, BoxLayout.Y_AXIS));
        JPanel gr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        gr.setOpaque(false);
        gr.add(Shack.label(Tr.t("GROUPE"), Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED));
        groupBox.setFont(Shack.body(13f));
        groupBox.setPreferredSize(new Dimension(280, 36));
        groupBox.addActionListener(e -> {
            if (fillingGroups) return;
            int g = groupBox.getSelectedIndex();
            Radio r = host.radio();
            if (g >= 0 && r.isRunning() && g != r.currentRegion()) {
                host.stopSweep();
                r.backToChannels();
                r.setRegion(g);
                chState.setText(Tr.t("Chargement du groupe ") + (g + 1) + "…");
            }
        });
        gr.add(groupBox);
        Shack.Key toA = new Shack.Key(Tr.t("METTRE SUR VFO A")).primary().font(Shack.bold(12f));
        Shack.Key toB = new Shack.Key(Tr.t("METTRE SUR VFO B")).font(Shack.bold(12f));
        for (Shack.Key k : new Shack.Key[]{toA, toB}) { k.setPreferredSize(new Dimension(170, 40)); gr.add(k); }
        toA.addActionListener(e -> assign(1));
        toB.addActionListener(e -> assign(2));
        gc.add(left(gr));
        gc.add(Box.createVerticalStrut(8));
        JPanel gr2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        gr2.setOpaque(false);
        Shack.Key addCh = new Shack.Key(Tr.t("AJOUTER UNE FRÉQUENCE")).font(Shack.bold(12f));
        addCh.setToolTipText(Tr.t("Enregistrer une nouvelle fréquence dans le premier canal libre du groupe affiché"));
        addCh.addActionListener(e -> addChannel());
        Shack.Key renGroup = new Shack.Key(Tr.t("RENOMMER LE GROUPE")).font(Shack.bold(12f));
        renGroup.addActionListener(e -> renameGroup());
        for (Shack.Key k : new Shack.Key[]{addCh, renGroup}) { k.setPreferredSize(new Dimension(k.getPreferredSize().width + 10, 38)); gr2.add(k); }
        gc.add(left(gr2));
        gc.add(Box.createVerticalStrut(10));
        Shack.styleTable(chTable, 0, 2, 3, 4);
        chTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] cw = {50, 170, 120, 120, 70};
        for (int i = 0; i < cw.length; i++) chTable.getColumnModel().getColumn(i).setPreferredWidth(cw[i]);
        chTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) assign(1);
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int r = chTable.rowAtPoint(e.getPoint());
                if (r >= 0) chTable.setRowSelectionInterval(r, r);
                channelMenu(r >= 0 && r < rows.size() ? rows.get(r) : -1).show(chTable, e.getX(), e.getY());
            }
        });
        javax.swing.JScrollPane sp = Shack.scroll(chTable);
        sp.setPreferredSize(new Dimension(600, 330));
        gc.add(fill(sp));
        gc.add(Box.createVerticalStrut(8));
        gc.add(left(chState));
        gc.add(Box.createVerticalStrut(4));
        gc.add(left(note(Tr.t("Double-clic : canal sur le VFO A. Clic droit : modifier le canal, le mettre sur le VFO A ou B, ")
                + Tr.t("exporter le groupe en CSV. Le changement de groupe recharge les canaux du poste."))));
        col.add(fill(gc));

        // --- changer de frequence
        col.add(left(Shack.section(Tr.t("Changer de fréquence"))));
        Shack.Card fq = new Shack.Card(16);
        fq.setLayout(new BoxLayout(fq, BoxLayout.Y_AXIS));
        fq.add(left(Shack.label(Tr.t("FRÉQUENCE (MHz) · RÉCEPTION ET ÉMISSION"), Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED)));
        fq.add(Box.createVerticalStrut(6));
        JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        fr.setOpaque(false);
        freq = Shack.field(prefs.get("vfoFreq", ""), 12);
        freq.setFont(Shack.mono(18f));
        freq.addActionListener(e -> tune());
        fr.add(freq);
        Shack.Key tuneKey = new Shack.Key(Tr.t("ACCORDER")).primary().font(Shack.bold(12.5f));
        tuneKey.setPreferredSize(new Dimension(140, 44));
        tuneKey.addActionListener(e -> tune());
        fr.add(tuneKey);
        Shack.Key back = new Shack.Key(Tr.t("RETOUR AUX CANAUX")).font(Shack.bold(12f));
        back.setPreferredSize(new Dimension(190, 44));
        back.addActionListener(e -> backToChannels());
        fr.add(back);
        fq.add(left(fr));
        fq.add(Box.createVerticalStrut(10));
        JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        pr.setOpaque(false);
        String[][] presets = {{Tr.t("DAPNET 439,9875"), "439.9875"}, {Tr.t("APRS 144,800"), "144.800"}, {Tr.t("ISS 145,800"), "145.800"},
            {Tr.t("Appel 2 m 145,500"), "145.500"}, {Tr.t("Appel 70 cm 433,500"), "433.500"}};
        for (String[] p : presets) {
            Shack.Key k = new Shack.Key(p[0]).font(Shack.bold(11.5f));
            k.addActionListener(e -> { freq.setText(p[1]); tune(); });
            pr.add(k);
        }
        fq.add(left(pr));
        fq.add(Box.createVerticalStrut(10));
        fq.add(left(tuneState));
        fq.add(Box.createVerticalStrut(6));
        fq.add(left(note(Tr.t("Le mode fréquence du poste est utilisé : aucun canal n'est modifié en mémoire. L'émission (PTT, ")
                + Tr.t("POCSAG) se fait sur la fréquence accordée. Le bandscope se centre sur cette fréquence."))));
        col.add(fill(fq));
        col.add(Box.createVerticalGlue());
        add(Shack.scroll(col), BorderLayout.CENTER);
        new Timer(1000, e -> refresh()).start();
        refresh();
    }

    private JComponent vfoBox(String title, JLabel name, JLabel f) {
        Shack.Well w = new Shack.Well(12);
        w.setLayout(new BoxLayout(w, BoxLayout.Y_AXIS));
        w.setBorder(new EmptyBorder(10, 14, 10, 14));
        w.add(left(Shack.label(title, Shack.tracked(Shack.mono(10f), 0.16f), Shack.MUTED)));
        w.add(left(name));
        w.add(left(f));
        return w;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static JComponent fill(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    private static JLabel note(String s) {
        JLabel l = new JLabel("<html><body style='width:640px'>" + s + "</body></html>");
        l.setFont(Shack.body(11.5f));
        l.setForeground(Shack.MUTED);
        return l;
    }

    /** Rafraichissement (fil Swing), aussi appele quand les reglages ou l'etat changent. */
    void refreshSoon() {
        if (pending) return;
        pending = true;
        SwingUtilities.invokeLater(() -> { pending = false; refresh(); });
    }

    private void refresh() {
        Radio r = host.radio();
        boolean on = r.isRunning();
        int vol = on ? r.volume() : -1, sq = on ? r.squelch() : -1;
        volLbl.setText(vol < 0 ? "–" : String.valueOf(vol));
        sqLbl.setText(sq < 0 ? "–" : String.valueOf(sq));
        if (pttHeld) {
            long s = (System.currentTimeMillis() - pttStart) / 1000;
            pttKey.setText(Tr.t("ÉMISSION… ") + s + Tr.t(" s · RELÂCHER"));
            pttState.setText((r.isInTx() ? Tr.t("Le poste émet") : Tr.t("Envoi de l'audio au poste…")) + Tr.t(" sur ") + Bandscope.mhz(r.currentTxHz()) + " MHz");
            pttState.setForeground(Shack.RED);
        } else {
            pttKey.setText(Tr.t("PTT · MAINTENIR POUR ÉMETTRE"));
            if (System.currentTimeMillis() > errUntil || !on) {
                long tx = on ? r.currentTxHz() : 0;
                pttState.setText(!on ? Tr.t("Poste non connecté") : tx > 0 ? Tr.t("Émission sur ") + Bandscope.mhz(tx) + " MHz" : Tr.t("Fréquence d'émission inconnue"));
                pttState.setForeground(Shack.MUTED);
            }
        }
        meter.repaint();
        int a = r.channelA(), b = r.channelB(), dual = r.dualWatch(), act = r.activeVfo();
        vfoA.setText(on && a >= 0 ? label(r, a) : "–");
        vfoB.setText(on && b >= 0 ? label(r, b) : "–");
        freqA.setText(on && a >= 0 ? hz(r.channelRxHz(a)) : " ");
        freqB.setText(on && b >= 0 ? hz(r.channelRxHz(b)) : " ");
        boolean dualOnNow = dual > 0;
        vfoA.setForeground(dualOnNow && act == 1 ? Shack.CYAN : Shack.TEXT);
        vfoB.setForeground(dualOnNow && act == 2 ? Shack.CYAN : dualOnNow ? Shack.TEXT : Shack.MUTED);
        if (!on) dualChip.set(Tr.t("POSTE NON CONNECTÉ"), Shack.MUTED, Shack.MUTED);
        else if (dual < 0) dualChip.set(Tr.t("LECTURE DES RÉGLAGES…"), Shack.MUTED, Shack.MUTED);
        else if (dualOnNow) dualChip.set(Tr.t("DOUBLE VEILLE ACTIVE") + (act == 1 ? Tr.t(" · RÉCEPTION SUR A") : act == 2 ? Tr.t(" · RÉCEPTION SUR B") : ""), Shack.CYAN, Shack.TEXT);
        else dualChip.set(Tr.t("UN SEUL VFO (A)"), Shack.AMBER, Shack.TEXT2);
        dualOn.setSelected(on && dualOnNow);
        dualOff.setSelected(on && dual == 0);
        long u = r.userVfoHz();
        if (chModel.getRowCount() > 0) chModel.fireTableRowsUpdated(0, chModel.getRowCount() - 1);
        if (!on && !rows.isEmpty()) reloadChannels();
        tuneState.setText(!on ? " " : u > 0 ? Tr.t("Accordé sur ") + Bandscope.mhz(u) + Tr.t(" MHz (mode fréquence)") : Tr.t("Sur les canaux mémoire"));
    }

    /** Reglage « − valeur + ». */
    private JComponent stepper(String title, JLabel value, java.util.function.IntConsumer onStep) {
        Shack.Well w = new Shack.Well(12);
        w.setLayout(new BorderLayout(10, 0));
        w.setBorder(new EmptyBorder(10, 14, 10, 10));
        JPanel l = new JPanel();
        l.setOpaque(false);
        l.setLayout(new BoxLayout(l, BoxLayout.Y_AXIS));
        l.add(left(Shack.label(title, Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED)));
        l.add(left(value));
        w.add(l, BorderLayout.CENTER);
        JPanel k = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        k.setOpaque(false);
        Shack.Key minus = new Shack.Key("−").font(Shack.bold(16f));
        Shack.Key plus = new Shack.Key("+").font(Shack.bold(16f));
        for (Shack.Key b : new Shack.Key[]{minus, plus}) { b.setPreferredSize(new Dimension(46, 40)); k.add(b); }
        minus.addActionListener(e -> onStep.accept(-1));
        plus.addActionListener(e -> onStep.accept(1));
        w.add(k, BorderLayout.EAST);
        return w;
    }

    private void pttDown() {
        if (pttHeld) return;
        Radio r = host.radio();
        Transmitter t = host.transmitter();
        if (!r.isRunning() || !r.audioOpen()) { pttError(Tr.t("Poste non connecté (canal audio fermé)")); return; }
        if (t.isBusy()) { pttError(Tr.t("Une émission est déjà en cours (POCSAG / SSTV)")); return; }
        long hz = r.currentTxHz();
        if (hz <= 0) { pttError(Tr.t("Émission impossible : fréquence d'émission inconnue ou canal en réception seule")); return; }
        host.stopSweep();
        pttHeld = true;
        pttStart = System.currentTimeMillis();
        try {
            boolean ok = t.startLive(GAINS[gainIdx] / 100.0, 180, new Transmitter.Listener() {
                @Override public void onTxStart(double seconds) { host.log(Tr.t("PTT : émission sur ") + Bandscope.mhz(hz) + " MHz"); }
                @Override public void onTxEnd(boolean stopped) {
                    pttHeld = false;
                    long s = (System.currentTimeMillis() - pttStart) / 1000;
                    host.log(Tr.t("PTT : fin d'émission (") + s + " s" + (stopped ? Tr.t(", coupure de sécurité") : "") + ")");
                    SwingUtilities.invokeLater(() -> refresh());
                }
            });
            if (!ok) { pttHeld = false; pttError(Tr.t("Émission impossible (canal audio fermé)")); }
        } catch (IllegalStateException e) {
            pttHeld = false;
            pttError(Tr.t("Émission impossible : ") + e.getMessage());
        }
        refresh();
    }

    private long errUntil;

    private void pttError(String s) {
        pttState.setText(s);
        pttState.setForeground(Shack.RED);
        errUntil = System.currentTimeMillis() + 5000;
    }

    private void pttUp() {
        if (!pttHeld) return;
        host.transmitter().stopLive();
    }

    /** Barre de niveau du micro. */
    private final class MicMeter extends JComponent {
        MicMeter() {
            setPreferredSize(new Dimension(260, 12));
            setMaximumSize(new Dimension(360, 12));
            new Timer(80, e -> { if (pttHeld) repaint(); }).start();
        }

        @Override
        protected void paintComponent(java.awt.Graphics g0) {
            java.awt.Graphics2D g = Shack.smooth(g0);
            int w = getWidth(), h = getHeight();
            g.setColor(Shack.WELL);
            g.fillRoundRect(0, 0, w, h, h, h);
            int lv = pttHeld ? host.transmitter().micLevel() : 0;
            int fw = (int) (w * Math.min(1, lv / 100.0));
            if (fw > 0) {
                g.setColor(lv > 90 ? Shack.RED : lv > 60 ? Shack.AMBER : Shack.GREEN);
                g.fillRoundRect(0, 0, fw, h, h, h);
            }
            g.dispose();
        }
    }

    /** Canaux ou groupes relus par le poste. */
    void channelsChanged() {
        if (chPending) return;
        chPending = true;
        SwingUtilities.invokeLater(() -> { chPending = false; reloadChannels(); });
    }

    private void reloadChannels() {
        Radio r = host.radio();
        int sel = chTable.getSelectedRow();
        Integer keep = sel >= 0 && sel < rows.size() ? rows.get(sel) : null;
        rows.clear();
        if (r.isRunning())
            for (int i = 0; i < r.channelCount(); i++)
                if (r.channelRxHz(i) > 0 || !r.channelName(i).isEmpty()) rows.add(i);
        chModel.fireTableDataChanged();
        if (keep != null && rows.indexOf(keep) >= 0) { int i = rows.indexOf(keep); chTable.setRowSelectionInterval(i, i); }
        // groupes
        fillingGroups = true;
        int n = r.isRunning() ? r.regionCount() : 0;
        if (groupBox.getItemCount() != n) {
            groupBox.removeAllItems();
            for (int i = 0; i < n; i++) groupBox.addItem("");
        }
        for (int i = 0; i < n; i++) {
            String name = Tr.t("Groupe ") + (i + 1) + (r.regionName(i).isEmpty() ? "" : " · " + r.regionName(i));
            if (!name.equals(groupBox.getItemAt(i))) { groupBox.removeItemAt(i); groupBox.insertItemAt(name, i); }
        }
        int cur = r.currentRegion();
        if (cur >= 0 && cur < n && groupBox.getSelectedIndex() != cur) groupBox.setSelectedIndex(cur);
        fillingGroups = false;
        groupBox.setEnabled(n > 1);
        chState.setText(!r.isRunning() ? " " : rows.size() + Tr.t(" canal(aux) dans le groupe") + (cur >= 0 ? " " + (cur + 1) : ""));
    }

    private void assign(int vfo) {
        Radio r = host.radio();
        if (!r.isRunning()) { JOptionPane.showMessageDialog(this, Tr.t("Connectez d'abord le poste.")); return; }
        int sel = chTable.getSelectedRow();
        if (sel < 0 || sel >= rows.size()) { JOptionPane.showMessageDialog(this, Tr.t("Choisissez un canal dans la liste.")); return; }
        if (r.userVfoHz() > 0 || r.isFreqMode()) { host.stopSweep(); r.backToChannels(); }
        if (!r.setVfoChannel(vfo, rows.get(sel))) host.log(Tr.t("Canal : réglages du poste pas encore lus."));
    }

    private final class ChannelModel extends AbstractTableModel {
        private final String[] cols = {Tr.t("N°"), Tr.t("Nom"), Tr.t("Réception"), Tr.t("Émission"), "VFO"};
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int row, int c) {
            Radio r = host.radio();
            int ch = rows.get(row);
            switch (c) {
                case 0: return String.valueOf(ch + 1);
                case 1: return r.channelName(ch);
                case 2: return mhz(r.channelRxHz(ch));
                case 3: {
                    long rx = r.channelRxHz(ch), tx = r.channelTxHz(ch);
                    if (tx <= 0) return Tr.t("RX seul");
                    if (tx == rx) return mhz(tx);
                    double d = (tx - rx) / 1e6;
                    return mhz(tx) + String.format(Locale.ROOT, " (%+.1f)", d);
                }
                default: {
                    boolean a = ch == r.channelA(), b = ch == r.channelB() && r.dualWatch() > 0;
                    return a && b ? "A+B" : a ? "A" : b ? "B" : ch == r.channelB() ? "(B)" : "";
                }
            }
        }
    }

    private static String mhz(long hz) { return hz > 0 ? String.format(Locale.ROOT, "%.4f", hz / 1e6) : "—"; }

    private static String label(Radio r, int ch) {
        String n = r.channelName(ch);
        return Tr.t("Canal ") + (ch + 1) + (n.isEmpty() ? "" : " · " + n);
    }

    private static String hz(long hz) { return hz > 0 ? String.format(Locale.ROOT, "%.4f MHz", hz / 1e6) : " "; }

    private void setDual(boolean on) {
        Radio r = host.radio();
        if (!r.isRunning()) return;
        if (on && r.userVfoHz() > 0) host.log(Tr.t("Double veille : retour aux canaux mémoire (le mode fréquence n'écoute qu'une fréquence)"));
        if (on) { host.stopSweep(); r.backToChannels(); }
        if (!r.setDualWatch(on)) { host.log(Tr.t("Double veille : réglages du poste pas encore lus.")); return; }
        host.log(Tr.t("Double veille ") + (on ? Tr.t("activée") : Tr.t("désactivée")));
    }

    private void tune() {
        Radio r = host.radio();
        if (!r.isRunning()) { JOptionPane.showMessageDialog(this, Tr.t("Connectez d'abord le poste.")); return; }
        long hz;
        try {
            hz = Math.round(Double.parseDouble(freq.getText().trim().replace(',', '.')) * 1e6);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, Tr.t("Fréquence invalide."));
            return;
        }
        if (!((hz >= 136_000_000 && hz <= 174_000_000) || (hz >= 400_000_000 && hz <= 520_000_000))) {
            JOptionPane.showMessageDialog(this, Tr.t("Hors des bandes du poste (136–174 / 400–520 MHz)."));
            return;
        }
        prefs.put("vfoFreq", freq.getText().trim());
        host.stopSweep();
        r.tuneUser(hz);
        host.log(Tr.t("Accord sur ") + Bandscope.mhz(hz) + Tr.t(" MHz (mode fréquence)"));
        refresh();
    }

    private void backToChannels() {
        Radio r = host.radio();
        if (!r.isRunning()) return;
        host.stopSweep();
        r.backToChannels();
        host.log(Tr.t("Retour aux canaux mémoire"));
        refresh();
    }

    // ------------------------------------------------------------------ Canaux : clic droit, modification, export CSV

    private javax.swing.JPopupMenu channelMenu(int ch) {
        javax.swing.JPopupMenu m = new javax.swing.JPopupMenu();
        Radio r = host.radio();
        boolean on = r.isRunning() && ch >= 0;
        m.add(menuItem(Tr.t("Modifier le canal") + (ch >= 0 ? " " + (ch + 1) : "") + "…", on, () -> editChannel(ch)));
        m.add(menuItem(Tr.t("Mettre sur le VFO A"), on, () -> assign(1)));
        m.add(menuItem(Tr.t("Mettre sur le VFO B"), on, () -> assign(2)));
        m.addSeparator();
        m.add(menuItem(Tr.t("Ajouter une fréquence au groupe…"), r.isRunning(), this::addChannel));
        m.add(menuItem(Tr.t("Renommer le groupe…"), r.isRunning() && r.regionCount() > 0, this::renameGroup));
        m.addSeparator();
        m.add(menuItem(Tr.t("Exporter le groupe en CSV…"), r.isRunning() && !rows.isEmpty(), this::exportCsv));
        return m;
    }

    private static javax.swing.JMenuItem menuItem(String text, boolean enabled, Runnable run) {
        javax.swing.JMenuItem i = new javax.swing.JMenuItem(text);
        i.setBorder(new EmptyBorder(7, 10, 7, 18));
        i.setEnabled(enabled);
        i.addActionListener(e -> run.run());
        return i;
    }

    // ------------------------------------------------------------------ Groupes : ajout d'une frequence, nom

    /** Nouvelle frequence : premier canal libre du groupe courant, ouvert dans la fenetre de modification. */
    private void addChannel() {
        String err = addChannelCheck();
        if (err != null) { JOptionPane.showMessageDialog(this, err); return; }
        editChannel(host.radio().firstFreeChannel(), true);
    }

    private String addChannelCheck() {
        Radio r = host.radio();
        if (!r.isRunning()) return Tr.t("Connectez d'abord le poste.");
        if (r.firstFreeChannel() < 0)
            return r.channelCount() == 0 ? Tr.t("Canaux pas encore lus, réessayez dans un instant.")
                    : Tr.t("Plus de canal libre dans ce groupe (") + r.channelCount() + Tr.t(" canaux) : modifiez un canal existant ou changez de groupe.");
        return null;
    }

    private void renameGroup() {
        Radio r = host.radio();
        if (!r.isRunning()) { JOptionPane.showMessageDialog(this, Tr.t("Connectez d'abord le poste.")); return; }
        int g = r.currentRegion();
        if (g < 0 || g >= r.regionCount()) { JOptionPane.showMessageDialog(this, Tr.t("Groupe courant inconnu, réessayez dans un instant.")); return; }
        JTextField name = Shack.field(r.regionName(g), 14);
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(left(cap(Tr.t("Nouveau nom du groupe ") + (g + 1) + Tr.t(" (10 caractères au plus)"))));
        p.add(left(name));
        String[] opts = {Tr.t("RENOMMER"), Tr.t("ANNULER")};
        if (JOptionPane.showOptionDialog(this, p, Tr.t("Renommer le groupe"), JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]) != 0) return;
        String err = renameGroup(g, name.getText());
        if (err != null) JOptionPane.showMessageDialog(this, err);
    }

    private String renameGroup(int g, String name) {
        Radio r = host.radio();
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) return Tr.t("Nom vide.");
        if (nm.getBytes(java.nio.charset.Charset.forName("GBK")).length > 10) return Tr.t("Nom trop long (10 caractères au plus).");
        if (!r.isRunning() || !r.setRegionName(g, nm)) return Tr.t("Groupe inconnu ou poste déconnecté.");
        return null;
    }

    /** Fenetre de modification d'un canal memoire, puis ecriture dans le poste (WRITE_RF_CH). */
    private void editChannel(int ch) { editChannel(ch, false); }

    /** isNew : canal libre a remplir (valeurs par defaut : 25 kHz, puissance haute, FM). */
    private void editChannel(int ch, boolean isNew) {
        Radio r = host.radio();
        Radio.Channel c = r.channel(ch);
        if (c == null) { JOptionPane.showMessageDialog(this, Tr.t("Canal pas encore lu par le poste, réessayez dans un instant.")); return; }
        if (isNew) {
            c.wide = true;
            c.maxPower = true;
            c.medPower = false;
            c.txDisable = false;
            c.rxMod = c.txMod = 0;
            c.rxSub = c.txSub = 0;
        }
        javax.swing.JDialog d = new javax.swing.JDialog(SwingUtilities.getWindowAncestor(this), isNew ? Tr.t("Nouvelle fréquence") : Tr.t("Canal ") + (ch + 1));
        d.setModal(true);
        JPanel p = new JPanel();
        p.setBackground(Shack.BG);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(16, 18, 14, 18));
        JTextField name = Shack.field(c.name, 14);
        JTextField rx = Shack.field(c.rxHz > 0 ? mhz(c.rxHz) : "", 12), tx = Shack.field(c.txHz > 0 ? mhz(c.txHz) : "", 12);
        JTextField rxTone = Shack.field(Radio.Channel.subText(c.rxSub), 8), txTone = Shack.field(Radio.Channel.subText(c.txSub), 8);
        int[] power = {c.maxPower ? 0 : c.medPower ? 1 : 2}, bw = {c.wide ? 0 : 1}, mod = {c.rxMod == 1 ? 1 : 0};
        Shack.Switch scan = new Shack.Switch(Tr.t("Inclus dans le balayage (scan)"), c.scan);
        Shack.Switch txOff = new Shack.Switch(Tr.t("Émission interdite sur ce canal"), c.txDisable);
        Shack.Switch flat = new Shack.Switch(Tr.t("Audio plat (sans désaccentuation) : POCSAG, SARSAT, sondes"), c.preDeEmphBypass);
        flat.setToolTipText(Tr.t("Coupe la désaccentuation 750 µs du poste en réception (et la pré-accentuation en émission). Le passe-bande 300-3100 Hz reste."));
        String grp = r.regionName(r.currentRegion());
        p.add(left(Shack.label((isNew ? Tr.t("NOUVELLE FRÉQUENCE · CANAL LIBRE ") : Tr.t("CANAL ")) + (ch + 1) + Tr.t(" · GROUPE ") + (r.currentRegion() + 1)
                + (grp.isEmpty() ? "" : " " + grp.toUpperCase(Locale.ROOT)), Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED)));
        p.add(left(cap(Tr.t("Nom (10 caractères au plus)"))));
        p.add(left(name));
        p.add(left(cap(Tr.t("Fréquences (MHz) : réception · émission (vide = même que la réception)"))));
        p.add(left(rowOf(rx, tx)));
        p.add(left(cap(Tr.t("Sous-tons : réception · émission (88.5 = CTCSS, D023 = DCS, vide = aucun)"))));
        p.add(left(rowOf(rxTone, txTone)));
        p.add(left(cap(Tr.t("Puissance d'émission"))));
        p.add(left(keysOf(new String[]{Tr.t("HAUTE"), Tr.t("MOYENNE"), Tr.t("BASSE")}, power)));
        p.add(left(cap(Tr.t("Largeur de bande"))));
        p.add(left(keysOf(new String[]{"25 kHz", "12,5 kHz"}, bw)));
        p.add(left(cap(Tr.t("Modulation"))));
        p.add(left(keysOf(new String[]{"FM", "AM"}, mod)));
        p.add(Box.createVerticalStrut(6));
        for (Shack.Switch s : new Shack.Switch[]{scan, txOff, flat}) { s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34)); p.add(left(s)); }
        JPanel br = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        br.setOpaque(false);
        Shack.Key cancel = new Shack.Key(Tr.t("ANNULER")).font(Shack.bold(12f));
        cancel.addActionListener(e -> d.dispose());
        Shack.Key ok = new Shack.Key(Tr.t("ENREGISTRER DANS LE POSTE")).primary().font(Shack.bold(12f));
        ok.addActionListener(e -> {
            try {
                Radio.Channel n = Radio.Channel.parse(c.toBytes()); // copie (options non editees conservees)
                String nm = name.getText().trim();
                if (nm.getBytes(java.nio.charset.Charset.forName("GBK")).length > 10) throw new IllegalArgumentException(Tr.t("Nom trop long (10 caractères au plus)."));
                n.name = nm;
                n.rxHz = parseMhz(rx.getText(), Tr.t("réception"));
                n.txHz = tx.getText().trim().isEmpty() ? n.rxHz : parseMhz(tx.getText(), Tr.t("émission"));
                n.rxSub = Radio.Channel.parseSub(rxTone.getText());
                n.txSub = Radio.Channel.parseSub(txTone.getText());
                n.maxPower = power[0] == 0;
                n.medPower = power[0] == 1;
                n.wide = bw[0] == 0;
                n.rxMod = n.txMod = mod[0];
                n.scan = scan.isOn();
                n.txDisable = txOff.isOn();
                n.preDeEmphBypass = flat.isOn();
                if (!r.isRunning()) throw new IllegalArgumentException(Tr.t("Poste déconnecté."));
                r.writeChannel(n);
                d.dispose();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(d, Tr.t("Valeur invalide : ") + ex.getMessage());
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(d, ex.getMessage());
            }
        });
        br.add(cancel);
        br.add(ok);
        p.add(left(br));
        d.setContentPane(p);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private static long parseMhz(String s, String what) {
        double v;
        try { v = Double.parseDouble(s.trim().replace(',', '.')); } catch (NumberFormatException e) { throw new IllegalArgumentException(Tr.t("Fréquence de ") + what + Tr.t(" invalide.")); }
        long hz = Math.round(v * 1e6);
        if (hz < 18_000_000L || hz > 1_000_000_000L) throw new IllegalArgumentException(Tr.t("Fréquence de ") + what + Tr.t(" hors plage : ") + s.trim() + " MHz.");
        return hz;
    }

    private static JLabel cap(String s) {
        JLabel l = Shack.label(s, Shack.body(12f), Shack.MUTED);
        l.setBorder(new EmptyBorder(10, 2, 4, 0));
        return l;
    }

    private static JPanel rowOf(JComponent... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        r.setOpaque(false);
        for (JComponent c : cs) r.add(c);
        return r;
    }

    private static JPanel keysOf(String[] labels, int[] sel) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        r.setOpaque(false);
        Shack.choice(r, labels, sel[0], i -> sel[0] = i);
        return r;
    }

    /** Exporte les canaux du groupe affiche au format CSV de HTCommander (reimportable). */
    private void exportCsv() {
        Radio r = host.radio();
        int g = r.currentRegion();
        String gname = r.regionName(g);
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser(prefs.get("csvDir", System.getProperty("user.home")));
        String base = "groupe-" + (g + 1) + (gname.isEmpty() ? "" : "-" + gname.replaceAll("[^\\w\\-]+", "_"));
        fc.setSelectedFile(new java.io.File(fc.getCurrentDirectory(), base + ".csv"));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(Tr.t("Fichier CSV"), "csv"));
        if (fc.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) f = new java.io.File(f.getParentFile(), f.getName() + ".csv");
        prefs.put("csvDir", f.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("title,tx_freq,rx_freq,tx_sub_audio(CTCSS=freq/DCS=number),rx_sub_audio(CTCSS=freq/DCS=number),tx_power(H/M/L),"
                + "bandwidth(12500/25000),scan(0=OFF/1=ON),talk around(0=OFF/1=ON),pre_de_emph_bypass(0=OFF/1=ON),sign(0=OFF/1=ON),"
                + "tx_dis(0=OFF/1=ON),mute(0=OFF/1=ON),rx_modulation(0=FM/1=AM),tx_modulation(0=FM/1=AM)\r\n");
        int n = 0, missing = 0;
        for (int ch : rows) {
            Radio.Channel c = r.channel(ch);
            if (c == null) { missing++; continue; }
            if (c.rxHz == 0) continue;
            long txHz = c.txHz > 0 ? c.txHz : c.rxHz;
            sb.append(csv(c.name)).append(',').append(txHz).append(',').append(c.rxHz).append(',').append(c.txSub).append(',').append(c.rxSub)
                    .append(',').append(c.maxPower ? "H" : c.medPower ? "M" : "L").append(',').append(c.wide ? 25000 : 12500)
                    .append(',').append(c.scan ? 1 : 0).append(',').append(c.talkAround ? 1 : 0).append(',').append(c.preDeEmphBypass ? 1 : 0)
                    .append(',').append(c.sign ? 1 : 0).append(',').append(c.txDisable ? 1 : 0).append(',').append(c.mute() ? 1 : 0)
                    .append(',').append(c.rxMod).append(',').append(c.txMod).append("\r\n");
            n++;
        }
        try {
            java.nio.file.Files.write(f.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            host.log(Tr.t("Export CSV : ") + n + Tr.t(" canal(aux) du groupe ") + (g + 1) + " → " + f.getAbsolutePath());
            JOptionPane.showMessageDialog(this, n + Tr.t(" canal(aux) exporté(s) dans\n") + f.getAbsolutePath()
                    + (missing > 0 ? "\n(" + missing + Tr.t(" canal(aux) pas encore lu(s), ignoré(s))") : ""));
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, Tr.t("Export impossible : ") + e.getMessage());
        }
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    // ------------------------------------------------------------------ Interface web

    java.util.Map<String, Object> web() {
        Radio r = host.radio();
        java.util.List<Object> chans = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] row = new Object[5];
            for (int c = 0; c < 5; c++) row[c] = chModel.getValueAt(i, c);
            chans.add(Json.obj("ch", rows.get(i), "cells", row));
        }
        java.util.List<String> groups = new java.util.ArrayList<>();
        for (int i = 0; i < groupBox.getItemCount(); i++) groups.add(groupBox.getItemAt(i));
        return Json.obj("on", r.isRunning(), "vfoA", vfoA.getText(), "vfoB", vfoB.getText(), "freqA", freqA.getText(), "freqB", freqB.getText(),
                "colA", vfoA.getForeground(), "colB", vfoB.getForeground(), "dual", r.dualWatch(),
                "chip", dualChip.text(), "chipDot", dualChip.dot(), "chipFg", dualChip.fg(),
                "vol", volLbl.getText(), "sq", sqLbl.getText(), "pttKey", pttKey.getText(), "pttState", pttState.getText(),
                "pttColor", pttState.getForeground(), "ptt", pttHeld, "mic", pttHeld ? host.transmitter().micLevel() : 0, "gain", gainIdx,
                "groups", groups, "group", groupBox.getSelectedIndex(), "chState", chState.getText(), "channels", chans,
                "tuneState", tuneState.getText(), "freq", freq.getText());
    }

    /** Commande web ; renvoie un message d'erreur ou null. */
    String webCmd(String a, java.util.Map<String, String> p) {
        Radio r = host.radio();
        switch (a) {
            case "dual": setDual("1".equals(p.get("on"))); break;
            case "vol": if (r.isRunning() && r.volume() >= 0) r.setVolume(r.volume() + Integer.parseInt(p.get("d"))); break;
            case "sq": if (r.isRunning() && r.squelch() >= 0) r.setSquelch(r.squelch() + Integer.parseInt(p.get("d"))); break;
            case "pttDown": pttDown(); break;
            case "pttUp": pttUp(); break;
            case "gain": gainIdx = Integer.parseInt(p.get("v")); prefs.putInt("pttGainIdx", gainIdx); break;
            case "group": {
                int g = Integer.parseInt(p.get("v"));
                if (g >= 0 && g < groupBox.getItemCount()) groupBox.setSelectedIndex(g); // meme chemin que la liste du PC
                break;
            }
            case "assign": {
                if (!r.isRunning()) return Tr.t("Connectez d'abord le poste.");
                int ch = Integer.parseInt(p.get("ch"));
                if (r.userVfoHz() > 0 || r.isFreqMode()) { host.stopSweep(); r.backToChannels(); }
                if (!r.setVfoChannel(Integer.parseInt(p.get("vfo")), ch)) return Tr.t("Réglages du poste pas encore lus.");
                break;
            }
            case "tune": {
                if (!r.isRunning()) return Tr.t("Connectez d'abord le poste.");
                long hz;
                try { hz = Math.round(Double.parseDouble(p.getOrDefault("f", "").trim().replace(',', '.')) * 1e6); }
                catch (NumberFormatException e) { return Tr.t("Fréquence invalide."); }
                if (!((hz >= 136_000_000 && hz <= 174_000_000) || (hz >= 400_000_000 && hz <= 520_000_000)))
                    return Tr.t("Hors des bandes du poste (136–174 / 400–520 MHz).");
                freq.setText(p.get("f").trim());
                tune();
                break;
            }
            case "back": backToChannels(); break;
            case "renameGroup": {
                if (!r.isRunning()) return Tr.t("Connectez d'abord le poste.");
                String err = renameGroup(r.currentRegion(), p.get("name"));
                if (err != null) return err;
                break;
            }
            case "addChannel": {
                String err = addChannelCheck();
                if (err != null) return err;
                Radio.Channel c = r.channel(r.firstFreeChannel());
                if (c == null) return Tr.t("Canal libre pas encore lu, réessayez.");
                try {
                    String nm = p.getOrDefault("name", "").trim();
                    if (nm.getBytes(java.nio.charset.Charset.forName("GBK")).length > 10) return Tr.t("Nom trop long (10 caractères au plus).");
                    c.name = nm;
                    c.rxHz = parseMhz(p.getOrDefault("rx", ""), Tr.t("réception"));
                    String tx = p.getOrDefault("tx", "").trim();
                    c.txHz = tx.isEmpty() ? c.rxHz : parseMhz(tx, Tr.t("émission"));
                    c.rxSub = 0;
                    c.txSub = Radio.Channel.parseSub(p.getOrDefault("tone", ""));
                    c.wide = true;
                    c.maxPower = true;
                    c.medPower = false;
                    c.txDisable = false;
                    c.rxMod = c.txMod = 0;
                } catch (IllegalArgumentException e) {
                    return e.getMessage();
                }
                r.writeChannel(c);
                break;
            }
            default: break;
        }
        refresh();
        return null;
    }
}
