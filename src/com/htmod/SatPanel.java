/*
 * HTMOD Windows : onglet SATELLITES.
 *  - Catalogue : serveur Benshi (TLE + configurations radio), base FM/SSTV SatNOGS, CelesTrak,
 *    satellites ajoutes par l'utilisateur ; filtres TOUS / SSTV / VISIBLES et recherche.
 *  - Carte : position de tous les satellites filtres, trace au sol et empreinte du satellite choisi,
 *    position d'observation.
 *  - Fiche : azimut / elevation / distance / altitude, configurations radio corrigees du Doppler,
 *    passages des prochaines 24 h, indicateur SSTV, poursuite Doppler par le poste (mode frequence
 *    du poste + affichage du satellite sur son ecran, comme HTCommander).
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;

final class SatPanel extends JPanel implements MapPanel.Overlay {
    interface Host {
        Radio radio();
        void log(String s);
        void stopSweep();
    }

    private static final int F_ALL = 0, F_SSTV = 1, F_VISIBLE = 2;

    private final Host host;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final SatCatalog catalog;
    private final MapPanel map;
    private final ListModel model = new ListModel();
    private final JTable table = new JTable(model);
    private final JTextField search = Shack.field("", 12);
    private final JTextField latF, lonF;
    private final JLabel status = Shack.label(" ", Shack.mono(11f), Shack.MUTED);
    private int filter;
    /** Elevation minimale (deg) : satellites visibles au-dessus, ou dont un passage dans les 24 h l'atteint. */
    private volatile int minEl = prefs.getInt("satMinEl", 0);
    private static final int[] MIN_ELS = {0, 10, 20, 30, 45};

    // fiche
    private final JLabel dName = Shack.label("Aucun satellite", Shack.display(22f), Shack.TEXT);
    private final JLabel dSub = Shack.label(" ", Shack.mono(11f), Shack.MUTED);
    private final JLabel dPos = Shack.label(" ", Shack.mono(12.5f), Shack.TEXT2);
    private final JTextArea dRf = area(), dPasses = area();
    private final Shack.Switch sstvSw = new Shack.Switch("Satellite SSTV", false);
    private final Shack.Key trackKey = new Shack.Key("SUIVRE AVEC LE POSTE").primary().font(Shack.bold(12.5f));
    private final JPanel rfChoice = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
    private int rfIndex;

    // etat
    private volatile SatTrack.Observer observer;
    private volatile List<SatCatalog.Sat> shown = new ArrayList<>();
    private final Map<Integer, SatTrack.Look> looks = new HashMap<>();
    private final Map<Integer, SatTrack.Pass> nextPass = new HashMap<>();
    private SatCatalog.Sat selected;
    private List<double[]> track = new ArrayList<>();
    private long trackAt;
    private volatile boolean tracking;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.ROOT), dhm = new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT);

    SatPanel(Host host, File outDir) {
        super(new BorderLayout(12, 0));
        this.host = host;
        setOpaque(false);
        catalog = new SatCatalog(new File(outDir, "satellites"));
        map = new MapPanel(new File(outDir, "tiles"), "satMap");
        map.setOverlay(this);
        latF = Shack.field(prefs.get("obsLat", "48.8566"), 8);
        lonF = Shack.field(prefs.get("obsLon", "2.3522"), 8);
        setObserver();

        add(leftColumn(), BorderLayout.WEST);
        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        Shack.Well mw = new Shack.Well(14);
        mw.setLayout(new BorderLayout());
        mw.setBorder(new EmptyBorder(1, 1, 1, 1));
        mw.add(map);
        center.add(mw, BorderLayout.CENTER);
        center.add(detail(), BorderLayout.SOUTH);
        add(center, BorderLayout.CENTER);

        new Thread(() -> {
            catalog.loadLocal();
            SwingUtilities.invokeLater(this::refilter);
            String m = catalog.refresh();
            host.log("Satellites : " + m);
            SwingUtilities.invokeLater(() -> { status.setText(m); refilter(); });
            computePasses();
        }, "htmod-satellites").start();
        new Timer(2000, e -> tick()).start();
        new Timer(300_000, e -> new Thread(this::computePasses, "htmod-passages").start()).start();
    }

    private static JTextArea area() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setOpaque(false);
        a.setFont(Shack.mono(12f));
        a.setForeground(Shack.TEXT2);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        return a;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    // ------------------------------------------------------------------ Colonne de gauche

    private JComponent leftColumn() {
        JPanel col = new JPanel(new BorderLayout(0, 8));
        col.setOpaque(false);
        col.setPreferredSize(new Dimension(340, 100));
        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        Shack.Card obs = new Shack.Card(14);
        obs.setLayout(new BoxLayout(obs, BoxLayout.Y_AXIS));
        obs.setBorder(new EmptyBorder(10, 14, 10, 14));
        obs.add(left(Shack.label("POSITION D'OBSERVATION (LAT / LON EN DEGRÉS)", Shack.tracked(Shack.mono(10f), 0.12f), Shack.MUTED)));
        JPanel or = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        or.setOpaque(false);
        or.add(latF);
        or.add(lonF);
        Shack.Key ok = new Shack.Key("OK").font(Shack.bold(12f));
        ok.setPreferredSize(new Dimension(56, 38));
        ok.addActionListener(e -> { setObserver(); new Thread(this::computePasses, "htmod-passages").start(); });
        or.add(ok);
        obs.add(left(or));
        top.add(left(obs));

        JPanel fr = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
        fr.setOpaque(false);
        Shack.choice(fr, new String[]{"TOUS", "SSTV", "VISIBLES"}, 0, i -> { filter = i; refilter(); });
        top.add(left(fr));
        JPanel er = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        er.setOpaque(false);
        JLabel el = Shack.label("ÉLÉV. MIN", Shack.tracked(Shack.mono(10f), 0.12f), Shack.MUTED);
        el.setBorder(new EmptyBorder(0, 2, 0, 4));
        er.add(el);
        int elSel = 0;
        String[] elLabels = new String[MIN_ELS.length];
        for (int i = 0; i < MIN_ELS.length; i++) { elLabels[i] = MIN_ELS[i] + "°"; if (MIN_ELS[i] == minEl) elSel = i; }
        Shack.Key[] elKeys = Shack.choice(er, elLabels, elSel, i -> {
            minEl = MIN_ELS[i];
            prefs.putInt("satMinEl", minEl);
            refilter();
            new Thread(() -> { computePasses(); SwingUtilities.invokeLater(this::refilter); }, "htmod-passages").start();
            if (selected != null) computePassesFor(selected);
        });
        for (Shack.Key k : elKeys) k.setPreferredSize(new Dimension(48, 34));
        er.setToolTipText("Élévation minimale : satellites au-dessus maintenant, ou dont un passage des 24 h l'atteint");
        er.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        top.add(left(er));
        top.add(Box.createVerticalStrut(8));
        search.setToolTipText("Rechercher un satellite (nom ou n° NORAD)");
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refilter(); }
            public void removeUpdate(DocumentEvent e) { refilter(); }
            public void changedUpdate(DocumentEvent e) { refilter(); }
        });
        top.add(left(search));
        col.add(top, BorderLayout.NORTH);

        Shack.styleTable(table, 1, 2);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] w = {120, 56, 104, 48};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        table.getColumnModel().getColumn(3).setMinWidth(48);
        table.getColumnModel().getColumn(2).setMinWidth(104);
        table.getColumnModel().getColumn(1).setMinWidth(52);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = table.getSelectedRow();
            if (r >= 0 && r < shown.size()) select(shown.get(table.convertRowIndexToModel(r)), false);
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && selected != null) {
                    SatTrack.Look l = looks.get(selected.norad);
                    if (l != null) map.centerOn(l.latDeg, l.lonDeg);
                }
            }
        });
        Shack.Card list = new Shack.Card(14);
        list.setLayout(new BorderLayout());
        list.setBorder(new EmptyBorder(8, 2, 6, 2));
        list.add(Shack.scroll(table), BorderLayout.CENTER);
        col.add(list, BorderLayout.CENTER);

        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        JPanel br = new JPanel(new GridLayout(1, 3, 6, 0));
        br.setOpaque(false);
        Shack.Key refresh = new Shack.Key("ACTUALISER").font(Shack.bold(11.5f));
        refresh.setToolTipText("Recharger la liste du serveur Benshi (et les TLE CelesTrak si besoin)");
        refresh.addActionListener(e -> new Thread(() -> {
            String m = catalog.refresh();
            host.log("Satellites : " + m);
            SwingUtilities.invokeLater(() -> { status.setText(m); refilter(); });
            computePasses();
        }, "htmod-satellites").start());
        Shack.Key add = new Shack.Key("AJOUTER…").font(Shack.bold(11.5f));
        add.addActionListener(e -> addDialog());
        Shack.Key del = new Shack.Key("SUPPRIMER").font(Shack.bold(11.5f));
        del.setToolTipText("Supprimer un satellite ajouté par vous");
        del.addActionListener(e -> removeSelected());
        br.add(refresh);
        br.add(add);
        br.add(del);
        br.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        br.setPreferredSize(new Dimension(370, 42));
        bottom.add(left(br));
        status.setBorder(new EmptyBorder(6, 4, 0, 0));
        bottom.add(left(status));
        col.add(bottom, BorderLayout.SOUTH);
        return col;
    }

    private void setObserver() {
        try {
            double la = Double.parseDouble(latF.getText().trim().replace(',', '.'));
            double lo = Double.parseDouble(lonF.getText().trim().replace(',', '.'));
            if (Math.abs(la) > 90 || Math.abs(lo) > 180) throw new NumberFormatException();
            observer = new SatTrack.Observer(la, lo, 0.05);
            prefs.put("obsLat", latF.getText().trim());
            prefs.put("obsLon", lonF.getText().trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "Position invalide (latitude -90..90, longitude -180..180).");
        }
    }

    private void refilter() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<SatCatalog.Sat> l = new ArrayList<>();
        for (SatCatalog.Sat s : catalog.all()) {
            if (filter == F_SSTV && !catalog.isSstv(s)) continue;
            SatTrack.Look k = looks.get(s.norad);
            if (filter == F_VISIBLE) {
                if (k == null || k.elDeg <= 0 || k.elDeg < minEl) continue;
            } else if (minEl > 0) {
                if (!(k != null && k.elDeg >= minEl) && !nextPass.containsKey(s.norad)) continue;
            }
            if (!q.isEmpty() && !s.name.toLowerCase(Locale.ROOT).contains(q) && !String.valueOf(s.norad).contains(q)) continue;
            l.add(s);
        }
        shown = l;
        int keep = selected == null ? -1 : l.indexOf(selected);
        model.fireTableDataChanged();
        if (keep >= 0) table.getSelectionModel().setSelectionInterval(keep, keep);
        map.repaint();
    }

    // ------------------------------------------------------------------ Fiche

    /** Fiche sous la carte : satellite et position | configurations radio et suivi | passages. */
    private JComponent detail() {
        Shack.Card c = new Shack.Card(16);
        c.setLayout(new GridLayout(1, 3, 18, 0));
        c.setPreferredSize(new Dimension(800, 250));
        JPanel a = column(), b = column(), d = column();
        a.add(left(Shack.label("SATELLITE", Shack.tracked(Shack.mono(10f), 0.16f), Shack.MUTED)));
        a.add(left(dName));
        a.add(left(dSub));
        a.add(left(gap(8)));
        a.add(left(dPos));
        a.add(left(gap(8)));
        sstvSw.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        sstvSw.addActionListener(e -> {
            if (selected != null) { catalog.setSstv(selected, sstvSw.isOn()); refilter(); }
        });
        a.add(left(sstvSw));
        b.add(left(Shack.label("RADIO (DOPPLER CORRIGÉ)", Shack.tracked(Shack.mono(10f), 0.12f), Shack.MUTED)));
        rfChoice.setOpaque(false);
        b.add(left(rfChoice));
        b.add(left(dRf));
        Shack.Key editKey = new Shack.Key("MODIFIER").font(Shack.bold(11f));
        editKey.setToolTipText("Modifier les fréquences de la configuration choisie");
        editKey.setPreferredSize(new Dimension(110, 32));
        editKey.setMaximumSize(new Dimension(110, 32));
        editKey.addActionListener(e -> editDialog());
        b.add(left(editKey));
        b.add(left(gap(8)));
        trackKey.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        trackKey.setPreferredSize(new Dimension(260, 46));
        trackKey.addActionListener(e -> toggleTracking());
        b.add(left(trackKey));
        d.add(left(Shack.label("PASSAGES (24 H, HEURE LOCALE)", Shack.tracked(Shack.mono(10f), 0.12f), Shack.MUTED)));
        d.add(left(dPasses));
        c.add(a);
        c.add(b);
        c.add(d);
        return c;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    private static JComponent gap(int h) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(1, h));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
        return p;
    }

    private void select(SatCatalog.Sat s, boolean fromMap) {
        if (s == selected) return;
        if (tracking) stopTracking();
        selected = s;
        rfIndex = 0;
        trackAt = 0;
        sstvSw.setOn(catalog.isSstv(s));
        rfChoice.removeAll();
        for (int i = 0; i < s.rf.size(); i++) {
            final int idx = i;
            Shack.Key k = new Shack.Key(s.rf.get(i).name.isEmpty() ? "FM" : s.rf.get(i).name).font(Shack.bold(11f));
            k.setPreferredSize(new Dimension(Math.max(60, k.getPreferredSize().width), 34));
            k.setSelected(i == 0);
            k.addActionListener(e -> {
                rfIndex = idx;
                for (Component cc : rfChoice.getComponents()) if (cc instanceof Shack.Key) ((Shack.Key) cc).setSelected(cc == k);
                updateDetail();
            });
            rfChoice.add(k);
        }
        rfChoice.revalidate();
        if (fromMap) {
            int i = shown.indexOf(s);
            if (i >= 0) table.getSelectionModel().setSelectionInterval(i, i);
        }
        updateTrack();
        updateDetail();
        computePassesFor(s);
        map.repaint();
    }

    private void updateTrack() {
        SatCatalog.Sat s = selected;
        if (s == null) return;
        long now = System.currentTimeMillis();
        try {
            track = SatTrack.groundTrack(s.prop(), now - 50 * 60_000L, now + 100 * 60_000L, 30_000);
        } catch (RuntimeException e) {
            track = new ArrayList<>();
        }
        trackAt = now;
    }

    private void updateDetail() {
        SatCatalog.Sat s = selected;
        if (s == null) return;
        dName.setText(s.name);
        dName.setForeground(catalog.isSstv(s) ? Shack.AMBER : Shack.TEXT);
        dSub.setText("NORAD " + s.norad + " · source " + s.source + (catalog.isSstv(s) ? " · SSTV" : ""));
        SatTrack.Look l;
        try { l = SatTrack.look(s.prop(), System.currentTimeMillis(), observer); } catch (RuntimeException e) { l = null; }
        if (l == null) { dPos.setText("Position incalculable (TLE trop ancien ?)"); dRf.setText(""); return; }
        dPos.setText(String.format(Locale.ROOT, "<html>Az %.1f°  ·  Él <b>%.1f°</b>  ·  %.0f km<br>Alt %.0f km  ·  %.3f, %.3f<br>%s</html>",
                l.azDeg, l.elDeg, l.rangeKm, l.altKm, l.latDeg, l.lonDeg,
                l.elDeg > 0 ? "<font color='#5FC8E8'>VISIBLE</font>" + String.format(Locale.ROOT, "  ·  vitesse radiale %+.2f km/s", l.rangeRateKms)
                        : "sous l'horizon"));
        StringBuilder rf = new StringBuilder();
        if (s.rf.isEmpty()) rf.append("Aucune configuration radio connue (ajoutez-en une avec AJOUTER…).");
        else {
            SatCatalog.Rf r = s.rf.get(Math.min(rfIndex, s.rf.size() - 1));
            rf.append(r.label()).append('\n');
            if (r.downHz <= 0) { dRf.setText(rf.toString().trim()); return; }
            rf.append("Réception : ").append(SatCatalog.mhz(l.downlink(r.downHz))).append(" MHz")
                    .append(String.format(Locale.ROOT, " (%+.1f kHz)", (l.downlink(r.downHz) - r.downHz) / 1000.0));
            if (r.upHz > 0) rf.append("\nÉmission : ").append(SatCatalog.mhz(l.uplink(r.upHz))).append(" MHz")
                    .append(String.format(Locale.ROOT, " (%+.1f kHz)", (l.uplink(r.upHz) - r.upHz) / 1000.0));
        }
        dRf.setText(rf.toString());
    }

    private void computePassesFor(SatCatalog.Sat s) {
        SatTrack.Observer o = observer;
        if (o == null) return;
        new Thread(() -> {
            List<SatTrack.Pass> ps;
            try { ps = SatTrack.passes(s.prop(), o, System.currentTimeMillis(), 24, minEl); } catch (RuntimeException e) { ps = new ArrayList<>(); }
            StringBuilder sb = new StringBuilder();
            for (SatTrack.Pass p : ps) {
                sb.append(dhm.format(new Date(p.aosMs))).append(" → ").append(hm.format(new Date(p.losMs)))
                        .append(String.format(Locale.ROOT, "  max %2.0f°  az %3.0f→%3.0f", p.maxElDeg, p.aosAzDeg, p.losAzDeg)).append('\n');
                if (sb.length() > 1400) break;
            }
            String txt = ps.isEmpty() ? "Aucun passage au-dessus de l'horizon dans les 24 h." : sb.toString().trim();
            SwingUtilities.invokeLater(() -> { if (selected == s) dPasses.setText(txt); });
        }, "htmod-passages-sat").start();
    }

    /** Prochain passage de chaque satellite (fil de fond). */
    private void computePasses() {
        SatTrack.Observer o = observer;
        if (o == null) return;
        long now = System.currentTimeMillis();
        Map<Integer, SatTrack.Pass> m = new HashMap<>();
        for (SatCatalog.Sat s : catalog.all()) {
            try {
                List<SatTrack.Pass> ps = SatTrack.passes(s.prop(), o, now, 24, minEl);
                if (!ps.isEmpty()) m.put(s.norad, ps.get(0));
            } catch (RuntimeException ignore) {
            }
        }
        SwingUtilities.invokeLater(() -> {
            nextPass.clear();
            nextPass.putAll(m);
            model.fireTableRowsUpdated(0, Math.max(0, shown.size() - 1));
        });
    }

    // ------------------------------------------------------------------ Mise a jour periodique

    private void tick() {
        long now = System.currentTimeMillis();
        SatTrack.Observer o = observer;
        for (SatCatalog.Sat s : catalog.all()) {
            try {
                SatTrack.Look l = SatTrack.look(s.prop(), now, o);
                if (l != null) looks.put(s.norad, l);
            } catch (RuntimeException ignore) {
            }
        }
        if (filter == F_VISIBLE || minEl > 0) refilter();
        else if (!shown.isEmpty()) model.fireTableRowsUpdated(0, shown.size() - 1);
        if (selected != null) {
            if (now - trackAt > 30_000) updateTrack();
            updateDetail();
            if (tracking) doTrack();
        }
        map.repaint();
    }

    // ------------------------------------------------------------------ Poursuite Doppler par le poste

    private void toggleTracking() {
        if (tracking) { stopTracking(); return; }
        SatCatalog.Sat s = selected;
        Radio r = host.radio();
        if (s == null) return;
        if (!r.isRunning()) { JOptionPane.showMessageDialog(this, "Connectez d'abord le poste."); return; }
        if (s.rf.isEmpty()) { JOptionPane.showMessageDialog(this, "Ce satellite n'a pas de configuration radio."); return; }
        if (s.rf.get(Math.min(rfIndex, s.rf.size() - 1)).downHz <= 0) {
            JOptionPane.showMessageDialog(this, "Pas de fréquence pour cette configuration : réglez-la avec MODIFIER.");
            return;
        }
        host.stopSweep();
        tracking = true;
        trackKey.setText("ARRÊTER LE SUIVI");
        host.log("Suivi Doppler de " + s.name + " (" + s.rf.get(Math.min(rfIndex, s.rf.size() - 1)).label() + ")");
        doTrack();
    }

    private void doTrack() {
        SatCatalog.Sat s = selected;
        Radio r = host.radio();
        if (s == null || !r.isRunning()) { stopTracking(); return; }
        SatTrack.Look l = SatTrack.look(s.prop(), System.currentTimeMillis(), observer);
        if (l == null) return;
        SatCatalog.Rf rf = s.rf.get(Math.min(rfIndex, s.rf.size() - 1));
        if (rf.downHz <= 0) { stopTracking(); return; }
        long rx = l.downlink(rf.downHz), tx = rf.upHz > 0 ? l.uplink(rf.upHz) : rx;
        SatTrack.Pass p = nextPass.get(s.norad);
        int secs = p == null ? 0 : (int) Math.max(0, (p.aosMs - System.currentTimeMillis()) / 1000);
        String n = s.name.length() > 20 ? s.name.substring(0, 20) : s.name;
        r.setSatelliteInfo(n, l.azDeg, l.elDeg, l.rangeKm, l.altKm, l.elDeg > 0 ? 0 : secs);
        r.tuneVfo(rx, tx, rf.downAm, rf.downTone, rf.upTone);
    }

    private void stopTracking() {
        if (!tracking) return;
        tracking = false;
        trackKey.setText("SUIVRE AVEC LE POSTE");
        Radio r = host.radio();
        if (r.isRunning()) r.restoreAfterSweep(); // canal, ou frequence de l'onglet VFO
        host.log("Suivi Doppler arrêté");
    }

    /** Liaison perdue. */
    void onDisconnected() {
        tracking = false;
        SwingUtilities.invokeLater(() -> trackKey.setText("SUIVRE AVEC LE POSTE"));
    }

    // ------------------------------------------------------------------ Modification des frequences

    private void editDialog() {
        SatCatalog.Sat s = selected;
        if (s == null) { JOptionPane.showMessageDialog(this, "Choisissez un satellite."); return; }
        if (s.rf.isEmpty()) { JOptionPane.showMessageDialog(this, "Ce satellite n'a pas de configuration radio (ajoutez-en une avec AJOUTER…)."); return; }
        SatCatalog.Rf r = s.rf.get(Math.min(rfIndex, s.rf.size() - 1));
        JTextField down = Shack.field(r.downHz > 0 ? SatCatalog.mhz(r.downHz) : "", 10), up = Shack.field(r.upHz > 0 ? SatCatalog.mhz(r.upHz) : "", 10),
                tone = Shack.field(r.upTone > 0 ? String.format(Locale.ROOT, "%.1f", r.upTone / 100.0) : "", 6);
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(left(cap("Réception (descente), MHz")));
        p.add(left(down));
        p.add(left(cap("Émission (montée), MHz — vide = pas d'émission")));
        p.add(left(up));
        p.add(left(cap("CTCSS à l'émission, Hz — vide = aucun")));
        p.add(left(tone));
        if (r.orig != null)
            p.add(left(cap("Origine : ↓ " + SatCatalog.mhz(r.orig[0]) + (r.orig[1] > 0 ? "  ↑ " + SatCatalog.mhz(r.orig[1]) : ""))));
        String[] opts = r.orig != null ? new String[]{"ENREGISTRER", "RÉTABLIR L'ORIGINE", "ANNULER"} : new String[]{"ENREGISTRER", "ANNULER"};
        int c = JOptionPane.showOptionDialog(this, p, s.name + " · " + (r.name.isEmpty() ? "FM" : r.name), JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
        String err;
        if (c == 0) err = editRf(s, r, down.getText(), up.getText(), tone.getText());
        else if (c == 1 && r.orig != null) err = resetRf(s, r);
        else return;
        if (err != null) JOptionPane.showMessageDialog(this, err);
    }

    /** Enregistre les frequences saisies (MHz, Hz) ; renvoie un message d'erreur ou null. */
    private String editRf(SatCatalog.Sat s, SatCatalog.Rf r, String down, String up, String tone) {
        long d, u;
        int t;
        try {
            d = Math.round(Double.parseDouble(down.trim().replace(',', '.')) * 1e6);
            u = up.trim().isEmpty() ? 0 : Math.round(Double.parseDouble(up.trim().replace(',', '.')) * 1e6);
            t = tone.trim().isEmpty() ? 0 : (int) Math.round(Double.parseDouble(tone.trim().replace(',', '.')) * 100);
        } catch (NumberFormatException e) {
            return "Valeur invalide.";
        }
        if (d < 1_000_000L || d > 6_000_000_000L || u < 0 || u > 6_000_000_000L) return "Fréquence hors plage.";
        if (t != 0 && (t < 6000 || t > 26000)) return "CTCSS hors plage (60–260 Hz).";
        try {
            catalog.editRf(s, r, d, u, t);
        } catch (IOException e) {
            return "Enregistrement impossible : " + e.getMessage();
        }
        host.log("Satellites : " + s.name + " " + r.label());
        updateDetail();
        if (tracking) doTrack();
        return null;
    }

    private String resetRf(SatCatalog.Sat s, SatCatalog.Rf r) {
        try {
            catalog.resetRf(s, r);
        } catch (IOException e) {
            return "Enregistrement impossible : " + e.getMessage();
        }
        host.log("Satellites : " + s.name + " fréquences d'origine " + r.label());
        updateDetail();
        if (tracking) doTrack();
        return null;
    }

    // ------------------------------------------------------------------ Ajout / suppression

    private void addDialog() {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(this), "Ajouter un satellite");
        d.setModal(true);
        JPanel p = new JPanel();
        p.setBackground(Shack.BG);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(16, 18, 16, 18));
        JTextField name = Shack.field("", 20), norad = Shack.field("", 8), l1 = Shack.field("", 69), l2 = Shack.field("", 69);
        JTextField down = Shack.field("", 10), up = Shack.field("", 10), tone = Shack.field("", 6), rfName = Shack.field("FM", 10);
        l1.setFont(Shack.mono(12f));
        l2.setFont(Shack.mono(12f));
        Shack.Switch sstv = new Shack.Switch("Satellite SSTV", false);
        p.add(left(cap("Nom")));
        p.add(left(name));
        p.add(left(cap("N° NORAD (pour récupérer le TLE sur CelesTrak)")));
        JPanel nr = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        nr.setOpaque(false);
        nr.add(norad);
        Shack.Key fetch = new Shack.Key("RÉCUPÉRER LE TLE").font(Shack.bold(11.5f));
        fetch.addActionListener(e -> {
            try {
                String[] t = catalog.fetchTleByNorad(Integer.parseInt(norad.getText().trim()));
                if (name.getText().trim().isEmpty()) name.setText(t[0]);
                l1.setText(t[1]);
                l2.setText(t[2]);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "TLE introuvable : " + ex.getMessage());
            }
        });
        nr.add(fetch);
        p.add(left(nr));
        p.add(left(cap("TLE ligne 1")));
        p.add(left(l1));
        p.add(left(cap("TLE ligne 2")));
        p.add(left(l2));
        p.add(left(cap("Configuration radio (facultatif) : nom, descente MHz, montée MHz, CTCSS Hz")));
        JPanel rr = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        rr.setOpaque(false);
        rr.add(rfName);
        rr.add(down);
        rr.add(up);
        rr.add(tone);
        p.add(left(rr));
        sstv.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        p.add(left(sstv));
        JPanel br = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        br.setOpaque(false);
        Shack.Key cancel = new Shack.Key("ANNULER").font(Shack.bold(12f));
        cancel.addActionListener(e -> d.dispose());
        Shack.Key ok = new Shack.Key("AJOUTER").primary().font(Shack.bold(12f));
        ok.addActionListener(e -> {
            try {
                SatCatalog.Rf rf = null;
                if (!down.getText().trim().isEmpty()) {
                    rf = new SatCatalog.Rf();
                    rf.name = rfName.getText().trim();
                    rf.downHz = Math.round(Double.parseDouble(down.getText().trim().replace(',', '.')) * 1e6);
                    if (!up.getText().trim().isEmpty()) rf.upHz = Math.round(Double.parseDouble(up.getText().trim().replace(',', '.')) * 1e6);
                    if (!tone.getText().trim().isEmpty()) rf.upTone = (int) Math.round(Double.parseDouble(tone.getText().trim().replace(',', '.')) * 100);
                }
                String n = name.getText().trim();
                catalog.addUser(n.isEmpty() ? "Satellite perso" : n, l1.getText().trim(), l2.getText().trim(), rf, sstv.isOn());
                host.log("Satellite ajouté : " + n);
                d.dispose();
                refilter();
                new Thread(this::computePasses, "htmod-passages").start();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(d, "Impossible d'ajouter : " + ex.getMessage());
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

    private static JLabel cap(String s) {
        JLabel l = Shack.label(s, Shack.body(12f), Shack.MUTED);
        l.setBorder(new EmptyBorder(10, 2, 4, 0));
        return l;
    }

    private void removeSelected() {
        SatCatalog.Sat s = selected;
        if (s == null) return;
        if (!SatCatalog.SRC_USER.equals(s.source)) {
            JOptionPane.showMessageDialog(this, "Seuls les satellites ajoutés par vous peuvent être supprimés (le filtre SSTV permet de masquer les autres).");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, "Supprimer « " + s.name + " » ?", "Satellites", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            catalog.removeUser(s.norad);
            selected = null;
            refilter();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Suppression impossible : " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ Interface web

    java.util.Map<String, Object> web() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < shown.size(); i++) {
            SatCatalog.Sat s = shown.get(i);
            SatTrack.Look l = looks.get(s.norad);
            list.add(Json.obj("n", s.name, "id", s.norad, "el", l == null ? null : Math.round(l.elDeg * 10) / 10.0,
                    "next", model.getValueAt(i, 2), "sstv", catalog.isSstv(s), "vis", l != null && l.elDeg > 0,
                    "lat", l == null ? null : l.latDeg, "lon", l == null ? null : l.lonDeg));
        }
        Map<String, Object> sel = null;
        SatCatalog.Sat s = selected;
        if (s != null) {
            SatTrack.Look l = looks.get(s.norad);
            List<String> rfs = new ArrayList<>();
            for (SatCatalog.Rf r : s.rf) rfs.add(r.name.isEmpty() ? "FM" : r.name);
            List<double[]> tr = track;
            int nowIdx = tr.isEmpty() ? 0 : (int) Math.max(0, Math.min(tr.size() - 1, (System.currentTimeMillis() - (trackAt - 50 * 60_000L)) / 30_000L));
            sel = Json.obj("id", s.norad, "name", dName.getText(), "sub", dSub.getText(), "sstv", catalog.isSstv(s),
                    "user", SatCatalog.SRC_USER.equals(s.source), "rfs", rfs, "rf", rfIndex, "rfText", dRf.getText(),
                    "passes", dPasses.getText(), "tracking", tracking, "trackKey", trackKey.getText(),
                    "look", l == null ? null : Json.obj("az", l.azDeg, "el", l.elDeg, "range", l.rangeKm, "alt", l.altKm, "lat", l.latDeg,
                            "lon", l.lonDeg, "rr", l.rangeRateKms, "foot", l.footprintKm),
                    "track", tr, "nowIdx", nowIdx, "edit", rfEditState(s));
        }
        SatTrack.Observer o = observer;
        return Json.obj("list", list, "total", catalog.all().size(), "filter", filter, "minEl", minEl, "search", search.getText(),
                "status", status.getText(), "obsLat", latF.getText(), "obsLon", lonF.getText(),
                "obs", o == null ? null : new double[]{o.latDeg, o.lonDeg}, "sel", sel);
    }

    /** Valeurs de la configuration choisie pour la fenetre MODIFIER de la page web. */
    private Map<String, Object> rfEditState(SatCatalog.Sat s) {
        if (s.rf.isEmpty()) return null;
        SatCatalog.Rf r = s.rf.get(Math.min(rfIndex, s.rf.size() - 1));
        return Json.obj("down", r.downHz > 0 ? SatCatalog.mhz(r.downHz) : "","up", r.upHz > 0 ? SatCatalog.mhz(r.upHz) : "",
                "tone", r.upTone > 0 ? String.format(Locale.ROOT, "%.1f", r.upTone / 100.0) : "",
                "orig", r.orig == null ? null : "↓ " + SatCatalog.mhz(r.orig[0]) + (r.orig[1] > 0 ? "  ↑ " + SatCatalog.mhz(r.orig[1]) : ""));
    }

    /** Commande web : renvoie null, un message d'erreur (String) ou des donnees (Map). */
    Object webCmd(String a, Map<String, String> p) {
        switch (a) {
            case "rfEdit":
            case "rfReset": {
                SatCatalog.Sat s = selected;
                if (s == null || s.rf.isEmpty()) return "Choisissez un satellite avec une configuration radio.";
                SatCatalog.Rf r = s.rf.get(Math.min(rfIndex, s.rf.size() - 1));
                return a.equals("rfReset") ? resetRf(s, r)
                        : editRf(s, r, p.getOrDefault("down", ""), p.getOrDefault("up", ""), p.getOrDefault("tone", ""));
            }
            case "select": {
                int id = Integer.parseInt(p.get("id"));
                SatCatalog.Sat s = catalog.get(id);
                if (s != null) select(s, true);
                return null;
            }
            case "filter": filter = Integer.parseInt(p.get("v")); refilter(); return null;
            case "minEl":
                minEl = Integer.parseInt(p.get("v"));
                prefs.putInt("satMinEl", minEl);
                refilter();
                new Thread(() -> { computePasses(); SwingUtilities.invokeLater(this::refilter); }, "htmod-passages").start();
                if (selected != null) computePassesFor(selected);
                return null;
            case "search": search.setText(p.getOrDefault("q", "")); return null;
            case "obs": {
                try {
                    double la = Double.parseDouble(p.get("lat").trim().replace(',', '.')), lo = Double.parseDouble(p.get("lon").trim().replace(',', '.'));
                    if (Math.abs(la) > 90 || Math.abs(lo) > 180) throw new NumberFormatException();
                } catch (Exception e) {
                    return "Position invalide (latitude -90..90, longitude -180..180).";
                }
                latF.setText(p.get("lat").trim());
                lonF.setText(p.get("lon").trim());
                setObserver();
                new Thread(this::computePasses, "htmod-passages").start();
                if (selected != null) computePassesFor(selected);
                return null;
            }
            case "rf": {
                SatCatalog.Sat s = selected;
                if (s == null) return null;
                rfIndex = Math.max(0, Math.min(s.rf.size() - 1, Integer.parseInt(p.get("v"))));
                int i = 0;
                for (Component cc : rfChoice.getComponents()) if (cc instanceof Shack.Key) ((Shack.Key) cc).setSelected(i++ == rfIndex);
                updateDetail();
                return null;
            }
            case "track": {
                SatCatalog.Sat s = selected;
                if (!tracking) {
                    if (s == null) return "Choisissez un satellite.";
                    if (!host.radio().isRunning()) return "Connectez d'abord le poste.";
                    if (s.rf.isEmpty()) return "Ce satellite n'a pas de configuration radio.";
                    if (s.rf.get(Math.min(rfIndex, s.rf.size() - 1)).downHz <= 0) return "Pas de fréquence pour cette configuration : réglez-la avec MODIFIER.";
                }
                toggleTracking();
                return null;
            }
            case "sstv":
                if (selected != null) { catalog.setSstv(selected, "1".equals(p.get("on"))); sstvSw.setOn(catalog.isSstv(selected)); refilter(); updateDetail(); }
                return null;
            case "refresh":
                new Thread(() -> {
                    String m = catalog.refresh();
                    host.log("Satellites : " + m);
                    SwingUtilities.invokeLater(() -> { status.setText(m); refilter(); });
                    computePasses();
                }, "htmod-satellites").start();
                return null;
            case "fetchTle":
                try {
                    String[] t = catalog.fetchTleByNorad(Integer.parseInt(p.get("norad").trim()));
                    return Json.obj("name", t[0], "l1", t[1], "l2", t[2]);
                } catch (Exception e) {
                    return "TLE introuvable : " + e.getMessage();
                }
            case "add":
                try {
                    SatCatalog.Rf rf = null;
                    String down = p.getOrDefault("down", "").trim(), up = p.getOrDefault("up", "").trim(), tone = p.getOrDefault("tone", "").trim();
                    if (!down.isEmpty()) {
                        rf = new SatCatalog.Rf();
                        rf.name = p.getOrDefault("rfName", "FM").trim();
                        rf.downHz = Math.round(Double.parseDouble(down.replace(',', '.')) * 1e6);
                        if (!up.isEmpty()) rf.upHz = Math.round(Double.parseDouble(up.replace(',', '.')) * 1e6);
                        if (!tone.isEmpty()) rf.upTone = (int) Math.round(Double.parseDouble(tone.replace(',', '.')) * 100);
                    }
                    String n = p.getOrDefault("name", "").trim();
                    catalog.addUser(n.isEmpty() ? "Satellite perso" : n, p.getOrDefault("l1", "").trim(), p.getOrDefault("l2", "").trim(), rf,
                            "1".equals(p.get("sstv")));
                    host.log("Satellite ajouté : " + n);
                    refilter();
                    new Thread(this::computePasses, "htmod-passages").start();
                    return null;
                } catch (Exception e) {
                    return "Impossible d'ajouter : " + e.getMessage();
                }
            case "remove": {
                SatCatalog.Sat s = selected;
                if (s == null) return null;
                if (!SatCatalog.SRC_USER.equals(s.source)) return "Seuls les satellites ajoutés par vous peuvent être supprimés.";
                try {
                    if (tracking) stopTracking();
                    catalog.removeUser(s.norad);
                    selected = null;
                    refilter();
                    return null;
                } catch (Exception e) {
                    return "Suppression impossible : " + e.getMessage();
                }
            }
            default: return null;
        }
    }

    // ------------------------------------------------------------------ Calque de carte

    @Override
    public void paint(Graphics2D g, MapPanel m) {
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        int w = m.getWidth();
        SatCatalog.Sat sel = selected;
        // trace au sol du satellite choisi (passe en pointilles, futur plein)
        if (sel != null && !track.isEmpty()) {
            long now = System.currentTimeMillis();
            int nowIdx = (int) Math.min(track.size() - 1, (now - (trackAt - 50 * 60_000L)) / 30_000L);
            drawPolyline(g, m, track.subList(0, Math.max(1, nowIdx + 1)), w,
                    new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 5f}, 0f), Shack.alpha(Shack.AMBER, 150));
            drawPolyline(g, m, track.subList(Math.max(0, nowIdx), track.size()), w, new BasicStroke(2f), Shack.alpha(Shack.AMBER, 220));
        }
        // empreinte
        SatTrack.Look sl = sel == null ? null : looks.get(sel.norad);
        if (sl != null) {
            List<double[]> circle = new ArrayList<>();
            double ang = sl.footprintKm / 6371.0, la = Math.toRadians(sl.latDeg), lo = Math.toRadians(sl.lonDeg);
            for (int i = 0; i <= 96; i++) {
                double b = 2 * Math.PI * i / 96;
                double lat2 = Math.asin(Math.sin(la) * Math.cos(ang) + Math.cos(la) * Math.sin(ang) * Math.cos(b));
                double lon2 = lo + Math.atan2(Math.sin(b) * Math.sin(ang) * Math.cos(la), Math.cos(ang) - Math.sin(la) * Math.sin(lat2));
                circle.add(new double[]{Math.toDegrees(lat2), Math.toDegrees(lon2)});
            }
            drawPolyline(g, m, circle, w, new BasicStroke(1.6f), Shack.alpha(Shack.CYAN, 200));
        }
        // observateur
        SatTrack.Observer o = observer;
        if (o != null) {
            Point2D.Double p = m.toScreen(o.latDeg, o.lonDeg);
            g.setColor(Shack.AMBER);
            g.setStroke(new BasicStroke(2f));
            g.drawOval((int) p.x - 7, (int) p.y - 7, 14, 14);
            g.drawLine((int) p.x - 11, (int) p.y, (int) p.x - 4, (int) p.y);
            g.drawLine((int) p.x + 4, (int) p.y, (int) p.x + 11, (int) p.y);
        }
        // satellites filtres
        g.setFont(Shack.monoBold(11f));
        FontMetrics fm = g.getFontMetrics();
        for (SatCatalog.Sat s : shown) {
            SatTrack.Look l = looks.get(s.norad);
            if (l == null) continue;
            Point2D.Double p = m.toScreen(l.latDeg, l.lonDeg);
            if (p.x < -20 || p.y < -20 || p.x > w + 20 || p.y > m.getHeight() + 20) continue;
            boolean isSel = s == sel, vis = l.elDeg > 0, sstv = catalog.isSstv(s);
            Color c = isSel ? Shack.AMBER : sstv ? new Color(0xC24DD6) : vis ? Shack.CYAN : Shack.TEXT2;
            int r = isSel ? 7 : 5;
            g.setColor(new Color(0, 0, 0, 120));
            g.fillOval((int) p.x - r - 1, (int) p.y - r + 1, 2 * r + 2, 2 * r + 2);
            g.setColor(c);
            g.fillOval((int) p.x - r, (int) p.y - r, 2 * r, 2 * r);
            g.setColor(Shack.WELL);
            g.fillOval((int) p.x - 2, (int) p.y - 2, 4, 4);
            if (isSel || vis || m.zoomLevel() >= 4 || shown.size() <= 25) {
                String lab = s.name.length() > 18 ? s.name.substring(0, 18) + "…" : s.name;
                int lw = fm.stringWidth(lab) + 8;
                g.setColor(Shack.alpha(Shack.BG, 200));
                g.fillRoundRect((int) p.x + 8, (int) p.y - 9, lw, 16, 7, 7);
                g.setColor(c);
                g.drawString(lab, (int) p.x + 12, (int) p.y + 3);
            }
        }
        // legende
        g.setFont(Shack.mono(10.5f));
        String leg = "● visible   ● SSTV   ● sous l'horizon   — trace   ○ empreinte";
        g.setColor(Shack.alpha(Shack.BG, 210));
        g.fillRoundRect(8, 8, g.getFontMetrics().stringWidth(leg) + 16, 20, 8, 8);
        int x = 16;
        Color[] cs = {Shack.CYAN, new Color(0xC24DD6), Shack.TEXT2};
        String[] parts = {"● visible   ", "● SSTV   ", "● sous l'horizon   ", "— trace   ○ empreinte"};
        for (int i = 0; i < parts.length; i++) {
            g.setColor(i < 3 ? cs[i] : Shack.AMBER);
            g.drawString(parts[i], x, 22);
            x += g.getFontMetrics().stringWidth(parts[i]);
        }
    }

    /** Polyligne lat/lon coupee aux sauts de la ligne de changement de date. */
    private static void drawPolyline(Graphics2D g, MapPanel m, List<double[]> pts, int w, BasicStroke stroke, Color c) {
        if (pts.size() < 2) return;
        Path2D path = new Path2D.Double();
        Point2D.Double prev = null;
        for (double[] p : pts) {
            Point2D.Double s = m.toScreen(p[0], p[1]);
            if (prev == null || Math.abs(s.x - prev.x) > w / 2.0 + 200) path.moveTo(s.x, s.y);
            else path.lineTo(s.x, s.y);
            prev = s;
        }
        g.setStroke(stroke);
        g.setColor(c);
        g.draw(path);
    }

    @Override
    public boolean click(MapPanel m, int x, int y) {
        SatCatalog.Sat best = null;
        double bd = 14;
        for (SatCatalog.Sat s : shown) {
            SatTrack.Look l = looks.get(s.norad);
            if (l == null) continue;
            Point2D.Double p = m.toScreen(l.latDeg, l.lonDeg);
            double d = Math.hypot(p.x - x, p.y - y);
            if (d < bd) { bd = d; best = s; }
        }
        if (best == null) return false;
        select(best, true);
        return true;
    }

    // ------------------------------------------------------------------ Liste

    private final class ListModel extends AbstractTableModel {
        private final String[] cols = {"Satellite", "Élév.", "Prochain", "SSTV"};

        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            if (r >= shown.size()) return "";
            SatCatalog.Sat s = shown.get(r);
            SatTrack.Look l = looks.get(s.norad);
            switch (c) {
                case 0: return s.name;
                case 1: return l == null ? "" : String.format(Locale.ROOT, "%.0f°", l.elDeg);
                case 2: {
                    if (l != null && l.elDeg > 0) return "visible";
                    SatTrack.Pass p = nextPass.get(s.norad);
                    return p == null ? "" : hm.format(new Date(p.aosMs)) + String.format(Locale.ROOT, " %2.0f°", p.maxElDeg);
                }
                default: return catalog.isSstv(s) ? "●" : "";
            }
        }
    }
}
