/*
 * HTMOD Windows : fenetre principale, habillage « shack de nuit » de Radtel Tools.
 * Se connecte au poste (RT-660 en BLE + RFCOMM, UV-PRO / VR-N76... en RFCOMM) via btbridge.exe,
 * recoit l'audio et :
 *  - decode les balises SARSAT 406 et les radiosondes quand le canal en reception s'appelle
 *    "SARSAT" ou "Radiosonde" (ou si un mode est force) ;
 *  - decode l'APRS en permanence : paquets du TNC du poste + demodulateur AFSK 1200 logiciel ;
 *  - place tout sur une carte OpenStreetMap, affiche le waterfall, notifie dans Windows.
 */
package com.htmod;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;

public final class App implements Radio.Listener, Decoders.Listener {
    static final String VERSION = "1.1";

    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final File bridgeExe;
    private final File outDir;
    private final Radio radio;
    private final Decoders decoders;
    private final Afsk1200 afsk;

    private JFrame frame;
    private Shack.Chip linkChip, modeChip;
    private JLabel modelLbl, statusLbl, channelLbl, deviceLbl;
    private Shack.Key connectKey, autoKey, sarsatKey, sondeKey;
    private Shack.Switch listenSw, wavSw, muteAprsSw, webSw;
    private final JLabel webInfo = new JLabel();
    private final Shack.Key fwKey = new Shack.Key("AUTORISER DANS LE PARE-FEU").font(Shack.bold(11.5f));
    private WebServer web;
    private final AprsMute aprsMute = new AprsMute(Decoders.SAMPLE_RATE), webMute = new AprsMute(Decoders.SAMPLE_RATE);
    private Bandscope bandscope;
    private PocsagPanel pocsag;
    private SstvPanel sstv;
    private VfoPanel vfo;
    private SatPanel sats;
    private Transmitter transmitter;
    private volatile boolean sweeping;
    private Counter aprsCount, sondeCount, sarsatCount;
    private SpectrumStrip spectrum;
    private WaterfallPanel waterfall;
    private MapPanel map;
    private InfoCard info;
    private JTextArea logArea;
    private final DetectionModel detections = new DetectionModel();
    private final AprsModel aprsModel = new AprsModel();
    private JLabel aprsHeader;
    private CardLayout views;
    private JPanel viewHost;
    private final Map<String, Shack.Key> tabs = new HashMap<>();
    private TrayIcon tray;
    private Decoders.Detection lastNotified;

    private List<Radio.Device> devices = new ArrayList<>();
    private Radio.Device device;
    private volatile String channelName = "";
    private volatile boolean userDisconnect = true;
    private Timer reconnectTimer;
    private SourceDataLine speaker;
    private volatile long lastPlayMs;
    private final Map<String, Long> aprsSeen = new HashMap<>();

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        SwingUtilities.invokeLater(() -> {
            Shack.install();
            App app = new App();
            BootSplash.show(VERSION, new String[]{"Pont Bluetooth", "Décodeur SBC", "Balises SARSAT 406", "Radiosondes",
                    "APRS · AFSK 1200", "Carte OpenStreetMap", "Prêt"}, 1800, app::show);
        });
    }

    private App() {
        bridgeExe = findBridge();
        outDir = new File(new File(System.getProperty("user.home"), "Documents"), "HTMod");
        decoders = new Decoders(outDir, this);
        radio = new Radio(bridgeExe, this);
        afsk = new Afsk1200(Decoders.SAMPLE_RATE, f -> onAx25(f, "audio"));
    }

    /** btbridge.exe est livre a cote de l'application (dossier app\ de jpackage, ou dossier courant). */
    private static File findBridge() {
        List<File> candidates = new ArrayList<>();
        try {
            File jar = new File(App.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = jar.isFile() ? jar.getParentFile() : jar;
            candidates.add(new File(dir, "btbridge.exe"));
            candidates.add(new File(dir.getParentFile(), "btbridge.exe"));
        } catch (Exception ignore) {
        }
        candidates.add(new File("btbridge.exe"));
        for (File f : candidates) if (f.isFile()) return f;
        return candidates.get(0);
    }

    // ------------------------------------------------------------------ Construction de l'interface

    private void show() {
        frame = new JFrame("HTMod – SARSAT, radiosondes & APRS");
        frame.setIconImages(java.util.Arrays.asList(icon(16), icon(32), icon(64)));
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Shack.BG);
        root.add(topBar(), BorderLayout.NORTH);
        root.add(sideColumn(), BorderLayout.WEST);
        root.add(mainArea(), BorderLayout.CENTER);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(1000, 680));
        frame.setSize(1360, 860);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        setupTray();
        applyWeb();
        updateLink(false, "HORS LIGNE");
        updateMode();
        log("HTMod " + VERSION + " – résultats dans " + outDir.getAbsolutePath());
        if (!bridgeExe.isFile()) log("ATTENTION : btbridge.exe introuvable (" + bridgeExe.getAbsolutePath() + ")");
        refreshDevices(false);
        restoreAprs();
        new Timer(1000, e -> refreshCounters()).start();
    }

    private JComponent topBar() {
        JPanel bar = new JPanel();
        bar.setOpaque(false);
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(new EmptyBorder(12, 20, 6, 18));
        bar.add(new IconView(Shack.GLOBE, Shack.AMBER, 28));
        bar.add(Box.createHorizontalStrut(12));
        bar.add(new Brand());
        bar.add(Box.createHorizontalStrut(16));
        JLabel sub = Shack.label("SARSAT · RADIOSONDES · APRS", Shack.tracked(Shack.mono(11f), 0.12f), Shack.MUTED);
        bar.add(sub);
        bar.add(Box.createHorizontalGlue());
        Shack.Key settings = new Shack.Key("").icon(Shack.SLIDERS, Shack.TEXT2);
        settings.setToolTipText("Réglages et outils");
        fix(settings, 44, 44);
        settings.setMaximumSize(new Dimension(44, 44));
        settings.addActionListener(e -> settingsMenu().show(settings, 0, settings.getHeight()));
        bar.add(settings);
        return bar;
    }

    private JComponent sideColumn() {
        JPanel col = new JPanel();
        col.setOpaque(false);
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(6, 18, 18, 12));

        // Carte RADIO : modele, etat de liaison, spectre, poste + connexion
        Shack.Card radioCard = new Shack.Card(18);
        radioCard.setLayout(new BoxLayout(radioCard, BoxLayout.Y_AXIS));
        JPanel top = row();
        JPanel title = new JPanel();
        title.setOpaque(false);
        title.setLayout(new BoxLayout(title, BoxLayout.Y_AXIS));
        title.add(Shack.label("RADIO", Shack.tracked(Shack.mono(10f), 0.16f), Shack.MUTED));
        modelLbl = Shack.label("Aucun poste", Shack.display(22f), Shack.TEXT);
        title.add(modelLbl);
        top.add(title, BorderLayout.CENTER);
        linkChip = new Shack.Chip();
        JPanel chipWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
        chipWrap.setOpaque(false);
        chipWrap.add(linkChip);
        top.add(chipWrap, BorderLayout.EAST);
        radioCard.add(top);
        radioCard.add(Box.createVerticalStrut(12));
        Shack.Well specWell = new Shack.Well(10);
        specWell.setLayout(new BorderLayout());
        specWell.setBorder(new EmptyBorder(1, 1, 1, 1));
        spectrum = new SpectrumStrip();
        specWell.add(spectrum);
        fix(specWell, 296, 52);
        specWell.setAlignmentX(Component.LEFT_ALIGNMENT);
        radioCard.add(specWell);
        radioCard.add(Box.createVerticalStrut(10));
        JPanel bottom = row();
        deviceLbl = Shack.label("Poste ›", Shack.mono(12f), Shack.AMBER);
        deviceLbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        deviceLbl.setToolTipText("Choisir le poste parmi les appareils Bluetooth appairés");
        deviceLbl.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { devicesMenu().show(deviceLbl, 0, deviceLbl.getHeight()); }
        });
        bottom.add(deviceLbl, BorderLayout.CENTER);
        connectKey = new Shack.Key("SE CONNECTER").primary().radius(12).font(Shack.bold(12.5f));
        fix(connectKey, 150, 42);
        connectKey.addActionListener(e -> toggleConnect());
        bottom.add(connectKey, BorderLayout.EAST);
        radioCard.add(bottom);
        statusLbl = Shack.label("Déconnecté", Shack.mono(11f), Shack.MUTED);
        statusLbl.setBorder(new EmptyBorder(8, 2, 0, 0));
        JPanel st = row();
        st.add(statusLbl);
        radioCard.add(st);
        add(col, radioCard);

        // Reception
        col.add(left(Shack.section("Réception")));
        Shack.Card rx = new Shack.Card(16);
        rx.setLayout(new BoxLayout(rx, BoxLayout.Y_AXIS));
        JPanel r1 = row();
        r1.add(Shack.label("CANAL", Shack.tracked(Shack.mono(10f), 0.16f), Shack.MUTED), BorderLayout.WEST);
        rx.add(r1);
        JPanel r2 = row();
        channelLbl = Shack.label("–", Shack.display(26f), Shack.TEXT);
        r2.add(channelLbl, BorderLayout.CENTER);
        rx.add(r2);
        rx.add(Box.createVerticalStrut(8));
        JPanel r3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        r3.setOpaque(false);
        r3.setAlignmentX(Component.LEFT_ALIGNMENT);
        modeChip = new Shack.Chip();
        r3.add(modeChip);
        rx.add(r3);
        add(col, rx);

        // Mode de decodage
        col.add(left(Shack.section("Décodage SARSAT / sondes")));
        JPanel modes = new JPanel(new GridLayout(1, 3, 8, 0));
        modes.setOpaque(false);
        autoKey = new Shack.Key("AUTO").font(Shack.bold(12f));
        sarsatKey = new Shack.Key("SARSAT").font(Shack.bold(12f)).selectedColor(Shack.RED);
        sondeKey = new Shack.Key("SONDE").font(Shack.bold(12f)).selectedColor(Shack.GREEN);
        autoKey.setToolTipText("Décode quand le canal en réception s'appelle SARSAT ou Radiosonde");
        sarsatKey.setToolTipText("Décoder l'audio en SARSAT quel que soit le canal");
        sondeKey.setToolTipText("Décoder l'audio en radiosonde quel que soit le canal");
        autoKey.addActionListener(e -> setForced(Decoders.MODE_NONE));
        sarsatKey.addActionListener(e -> setForced(Decoders.MODE_SARSAT));
        sondeKey.addActionListener(e -> setForced(Decoders.MODE_SONDE));
        modes.add(autoKey); modes.add(sarsatKey); modes.add(sondeKey);
        fix(modes, 328, 44);
        add(col, modes);
        decoders.forcedMode = prefs.getInt("forcedMode", Decoders.MODE_NONE);

        // Options
        col.add(left(Shack.section("Options")));
        Shack.Card opts = new Shack.Card(16);
        opts.setLayout(new BoxLayout(opts, BoxLayout.Y_AXIS));
        opts.setBorder(new EmptyBorder(8, 16, 8, 14));
        listenSw = new Shack.Switch("Écouter sur le PC", prefs.getBoolean("listen", false));
        listenSw.addActionListener(e -> { prefs.putBoolean("listen", listenSw.isOn()); if (!listenSw.isOn()) closeSpeaker(); });
        // une sortie audio Windows privee de donnees rejoue son tampon en boucle : on la ferme des que le flux s'arrete
        new Timer(200, e -> { if (System.currentTimeMillis() - lastPlayMs > 400) closeSpeaker(); }).start();
        wavSw = new Shack.Switch("Enregistrer les WAV", prefs.getBoolean("saveWav", true));
        decoders.saveWav = wavSw.isOn();
        wavSw.addActionListener(e -> { decoders.saveWav = wavSw.isOn(); prefs.putBoolean("saveWav", wavSw.isOn()); });
        muteAprsSw = new Shack.Switch("Couper le son des trames APRS", prefs.getBoolean("muteAprs", true));
        muteAprsSw.addActionListener(e -> prefs.putBoolean("muteAprs", muteAprsSw.isOn()));
        muteAprsSw.setToolTipText("Les trames restent reçues et décodées ; seul le haut-parleur du PC est coupé pendant la trame");
        webSw = new Shack.Switch("Serveur web (réseau local)", prefs.getBoolean("webOn", false));
        webSw.setToolTipText("Toute l'application dans un navigateur du réseau local (PC, tablette, téléphone)");
        webSw.addActionListener(e -> { prefs.putBoolean("webOn", webSw.isOn()); applyWeb(); });
        for (Shack.Switch s : new Shack.Switch[]{listenSw, muteAprsSw, wavSw, webSw}) {
            s.setAlignmentX(Component.LEFT_ALIGNMENT);
            s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            opts.add(s);
        }
        webInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        webInfo.setFont(Shack.mono(11.5f));
        webInfo.setForeground(Shack.CYAN);
        webInfo.setBorder(new EmptyBorder(2, 2, 6, 0));
        opts.add(webInfo);
        fwKey.setToolTipText("Autoriser les connexions du réseau local vers HTMod (Windows demande confirmation)");
        fwKey.setAlignmentX(Component.LEFT_ALIGNMENT);
        fwKey.setPreferredSize(new Dimension(260, 36));
        fwKey.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        fwKey.setVisible(false);
        fwKey.addActionListener(e -> { fwKey.setEnabled(false); Firewall.add(bridgeExe, this::log, () -> SwingUtilities.invokeLater(() -> { fwKey.setEnabled(true); checkFirewall(); })); });
        opts.add(fwKey);
        add(col, opts);

        // Compteurs
        col.add(left(Shack.section("Station")));
        JPanel counts = new JPanel(new GridLayout(1, 3, 8, 0));
        counts.setOpaque(false);
        aprsCount = new Counter("APRS", Shack.CYAN);
        sondeCount = new Counter("SONDES", Shack.GREEN);
        sarsatCount = new Counter("SARSAT", Shack.RED);
        counts.add(aprsCount); counts.add(sondeCount); counts.add(sarsatCount);
        fix(counts, 328, 78);
        add(col, counts);
        col.add(Box.createVerticalGlue());

        JScrollPane sp = Shack.scroll(col);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setPreferredSize(new Dimension(372, 100));
        sp.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return sp;
    }

    private JComponent mainArea() {
        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false);
        main.setBorder(new EmptyBorder(6, 6, 18, 20));

        JPanel tabBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        tabBar.setOpaque(false);
        tabBar.setBorder(new EmptyBorder(0, 0, 10, 0));
        views = new CardLayout();
        viewHost = new JPanel(views);
        viewHost.setOpaque(false);

        // Carte
        map = new MapPanel(new File(outDir, "tiles"));
        map.logSink = this::log;
        info = new InfoCard();
        map.setOnSelect(m -> SwingUtilities.invokeLater(() -> info.show(m)));
        addView(tabBar, "CARTE", Shack.PIN, mapView());

        // APRS
        JTable aprsTable = new JTable(aprsModel);
        Shack.styleTable(aprsTable, 0, 1, 3);
        aprsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] aw = {96, 130, 110, 180, 560};
        for (int i = 0; i < aw.length; i++) aprsTable.getColumnModel().getColumn(i).setPreferredWidth(aw[i]);
        aprsTable.getColumnModel().getColumn(0).setMinWidth(90);
        aprsTable.setToolTipText("Double-clic : voir la station sur la carte");
        aprsTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = aprsTable.getSelectedRow();
                if (r < 0) return;
                AprsRow row = aprsModel.get(aprsTable.convertRowIndexToModel(r));
                aprsTable.setToolTipText(row.tnc2);
                if (e.getClickCount() == 2 && row.hasPos) { selectTab("CARTE"); map.focus(row.mapKey); }
            }
        });
        aprsHeader = Shack.label("0 paquet", Shack.mono(11f), Shack.MUTED);
        addView(tabBar, "APRS", Shack.LIST, tableView("TRAFIC APRS", aprsHeader, aprsTable));

        // Decodages SARSAT / sondes
        JTable detTable = new JTable(detections);
        Shack.styleTable(detTable, 0, 2, 3, 4);
        detTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        int[] dw = {150, 90, 170, 90, 90, 520};
        for (int i = 0; i < dw.length; i++) detTable.getColumnModel().getColumn(i).setPreferredWidth(dw[i]);
        detTable.setToolTipText("Double-clic : voir la position sur la carte");
        detTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int r = detTable.getSelectedRow();
                if (e.getClickCount() == 2 && r >= 0) {
                    Decoders.Detection d = detections.get(detTable.convertRowIndexToModel(r));
                    if (d.hasPos) { selectTab("CARTE"); map.focus(mapKey(d)); }
                }
            }
        });
        addView(tabBar, "DÉCODAGES", Shack.TARGET, tableView("SARSAT & RADIOSONDES", Shack.label("", Shack.mono(11f), Shack.MUTED), detTable));

        // Spectre audio : calcule en coulisse pour le mini-spectre de la carte RADIO
        waterfall = new WaterfallPanel();
        waterfall.onColumn = spectrum::push;

        // Bandscope : balayage du poste autour de la frequence du canal
        bandscope = new Bandscope(new Bandscope.Host() {
            @Override public Radio radio() { return radio; }
            @Override public void log(String s) { App.this.log(s); }
            @Override public void sweeping(boolean on) {
                sweeping = on;
                SwingUtilities.invokeLater(App.this::updateMode);
            }
        });
        addView(tabBar, "BANDSCOPE", Shack.BARS, bandscope);

        // POCSAG (reception permanente + emission) et VFO (double veille, changement de frequence)
        transmitter = new Transmitter(radio);
        pocsag = new PocsagPanel(new PocsagPanel.Host() {
            @Override public Radio radio() { return radio; }
            @Override public Transmitter transmitter() { return transmitter; }
            @Override public boolean sweeping() { return sweeping; }
            @Override public void log(String s) { App.this.log(s); }
            @Override public void notify(String title, String text) {
                if (tray != null) SwingUtilities.invokeLater(() -> tray.displayMessage(title, text, TrayIcon.MessageType.INFO));
            }
            @Override public void csv(String type, String id, String details) { decoders.logExternal(type, id, details); }
        });
        addView(tabBar, "POCSAG", Shack.PAGER, pocsag);
        sstv = new SstvPanel(new SstvPanel.Host() {
            @Override public Radio radio() { return radio; }
            @Override public Transmitter transmitter() { return transmitter; }
            @Override public boolean sweeping() { return sweeping; }
            @Override public void log(String s) { App.this.log(s); }
            @Override public void notify(String title, String text) {
                if (tray != null) SwingUtilities.invokeLater(() -> tray.displayMessage(title, text, TrayIcon.MessageType.INFO));
            }
            @Override public void csv(String type, String id, String details) { decoders.logExternal(type, id, details); }
        }, outDir);
        addView(tabBar, "SSTV", Shack.PICTURE, sstv);
        vfo = new VfoPanel(new VfoPanel.Host() {
            @Override public Radio radio() { return radio; }
            @Override public Transmitter transmitter() { return transmitter; }
            @Override public void log(String s) { App.this.log(s); }
            @Override public void stopSweep() {
                bandscope.cancelForManualTune();
                SwingUtilities.invokeLater(bandscope::refreshCenter);
            }
        });
        addView(tabBar, "VFO", Shack.AB, vfo);

        // Satellites : carte de suivi (serveur Benshi + SatNOGS + CelesTrak + perso), filtre SSTV, Doppler
        sats = new SatPanel(new SatPanel.Host() {
            @Override public Radio radio() { return radio; }
            @Override public void log(String s) { App.this.log(s); }
            @Override public void stopSweep() { bandscope.cancelForManualTune(); }
        }, outDir);
        addView(tabBar, "SATELLITES", Shack.GLOBE, sats);

        // Journal
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(Shack.mono(12.5f));
        logArea.setBackground(Shack.WELL);
        logArea.setForeground(Shack.TEXT2);
        logArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        Shack.Well logWell = new Shack.Well(14);
        logWell.setLayout(new BorderLayout());
        logWell.setBorder(new EmptyBorder(2, 2, 2, 2));
        logWell.add(Shack.scroll(logArea));
        addView(tabBar, "JOURNAL", Shack.WAVE, logWell);

        main.add(tabBar, BorderLayout.NORTH);
        main.add(viewHost, BorderLayout.CENTER);
        selectTab(prefs.get("tab", "CARTE"));
        return main;
    }

    private void addView(JPanel tabBar, String name, Shack.Icon icon, JComponent view) {
        Shack.Key k = new Shack.Key(name).icon(icon, Shack.TEXT2).font(Shack.tracked(Shack.bold(11f), 0.04f));
        k.setPreferredSize(new Dimension(k.getPreferredSize().width - 4, 40));
        k.addActionListener(e -> selectTab(name));
        tabs.put(name, k);
        tabBar.add(k);
        tabBar.add(Box.createHorizontalStrut(5));
        viewHost.add(view, name);
    }

    private void selectTab(String name) {
        if (!tabs.containsKey(name)) name = "CARTE";
        for (Map.Entry<String, Shack.Key> e : tabs.entrySet()) e.getValue().setSelected(e.getKey().equals(name));
        views.show(viewHost, name);
        prefs.put("tab", name);
    }

    private JComponent mapView() {
        Shack.Well well = new Shack.Well(14);
        well.setLayout(new BorderLayout());
        well.setBorder(new EmptyBorder(1, 1, 1, 1));
        JLayeredPane layers = new JLayeredPane() {
            @Override public void doLayout() {
                int w = getWidth(), h = getHeight();
                map.setBounds(0, 0, w, h);
                int x = w - 12;
                for (Component c : getComponentsInLayer(JLayeredPane.PALETTE_LAYER)) {
                    Dimension d = c.getPreferredSize();
                    x -= d.width;
                    c.setBounds(x, 12, d.width, d.height);
                    x -= 8;
                }
                Dimension d = info.getPreferredSize();
                info.setBounds(12, h - d.height - 34, Math.min(d.width, w - 24), d.height);
            }
        };
        layers.add(map, JLayeredPane.DEFAULT_LAYER);
        Shack.Key plus = smallKey("+", "Zoom avant");
        plus.addActionListener(e -> map.zoomIn());
        Shack.Key minus = smallKey("−", "Zoom arrière");
        minus.addActionListener(e -> map.zoomOut());
        Shack.Key fit = smallKey("", "Cadrer toutes les positions").icon(Shack.TARGET, Shack.TEXT2);
        fit.addActionListener(e -> map.fitAll());
        Shack.Key night = smallKey("NUIT", "Carte en couleurs inversées (mode nuit)");
        night.setPreferredSize(new Dimension(70, 42));
        night.setSelected(map.isNight());
        night.addActionListener(e -> { map.setNight(!map.isNight()); night.setSelected(map.isNight()); });
        Shack.Key topo = smallKey("TOPO", "Fond OpenTopoMap (relief)");
        topo.setPreferredSize(new Dimension(70, 42));
        topo.setSelected(map.isTopo());
        topo.addActionListener(e -> { map.setTopo(!map.isTopo()); topo.setSelected(map.isTopo()); });
        WindLayer wind = new WindLayer(map, this::log);
        map.setUnderlay(wind);
        Shack.Key windKey = smallKey("VENT", "Vent animé (Open-Meteo, 10 m)");
        windKey.setPreferredSize(new Dimension(70, 42));
        windKey.addActionListener(e -> {
            wind.setOn(!wind.isOn());
            windKey.setSelected(wind.isOn());
            prefs.putBoolean("mapWind", wind.isOn());
        });
        if (prefs.getBoolean("mapWind", false)) { wind.setOn(true); windKey.setSelected(true); }
        // de droite a gauche
        for (Shack.Key k : new Shack.Key[]{plus, minus, fit, night, topo, windKey}) layers.add(k, JLayeredPane.PALETTE_LAYER);
        layers.add(info, JLayeredPane.MODAL_LAYER);
        well.add(layers);
        return well;
    }

    private Shack.Key smallKey(String t, String tip) {
        Shack.Key k = new Shack.Key(t).font(Shack.bold(15f));
        k.setToolTipText(tip);
        k.setPreferredSize(new Dimension(44, 42));
        return k;
    }

    private JComponent tableView(String title, JLabel right, JTable table) {
        Shack.Card card = new Shack.Card(16);
        card.setLayout(new BorderLayout());
        card.setBorder(new EmptyBorder(12, 2, 6, 2));
        JPanel head = row();
        head.setBorder(new EmptyBorder(0, 14, 10, 16));
        head.add(Shack.label(title, Shack.tracked(Shack.mono(11f), 0.16f), Shack.MUTED), BorderLayout.WEST);
        head.add(right, BorderLayout.EAST);
        card.add(head, BorderLayout.NORTH);
        card.add(Shack.scroll(table), BorderLayout.CENTER);
        return card;
    }

    private static JPanel row() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    private static void add(JPanel col, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        col.add(c);
    }

    private static void fix(JComponent c, int w, int h) {
        Dimension d = new Dimension(w, h);
        c.setPreferredSize(d);
        c.setMinimumSize(d);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
    }

    // ------------------------------------------------------------------ Menus

    private JPopupMenu settingsMenu() {
        JPopupMenu m = new JPopupMenu();
        m.add(item("Décoder un WAV SARSAT…", () -> decodeWav(Decoders.MODE_SARSAT)));
        m.add(item("Décoder un WAV radiosonde…", () -> decodeWav(Decoders.MODE_SONDE)));
        m.add(item("Décoder un WAV APRS…", () -> decodeWav(-1)));
        m.add(item("Décoder un WAV POCSAG…", () -> decodeWav(-2)));
        m.addSeparator();
        m.add(item("Ouvrir le dossier des résultats", () -> {
            try {
                outDir.mkdirs();
                Desktop.getDesktop().open(outDir);
            } catch (Exception ex) {
                log("Ouverture du dossier impossible : " + ex.getMessage());
            }
        }));
        m.add(item("Effacer la carte et les tableaux", () -> {
            map.clear();
            aprsModel.clear();
            detections.clear();
            refreshCounters();
        }));
        JCheckBoxMenuItem tnc = new JCheckBoxMenuItem("APRS : démodulateur logiciel en plus du TNC", prefs.getBoolean("swAprs", true));
        tnc.addActionListener(e -> prefs.putBoolean("swAprs", tnc.isSelected()));
        m.add(tnc);
        m.addSeparator();
        m.add(item("Mode d'emploi", this::help));
        m.add(item("Quitter", this::quit));
        return m;
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem i = new JMenuItem(text);
        i.setBorder(new EmptyBorder(7, 10, 7, 18));
        i.addActionListener(e -> r.run());
        return i;
    }

    private JPopupMenu devicesMenu() {
        JPopupMenu m = new JPopupMenu();
        if (devices.isEmpty()) {
            JMenuItem none = new JMenuItem("Aucun appareil appairé dans Windows");
            none.setEnabled(false);
            m.add(none);
        }
        for (Radio.Device d : devices) {
            JMenuItem i = item(d.name + "   " + d.mac, () -> selectDevice(d));
            if (device != null && d.mac.equals(device.mac)) i.setForeground(Shack.AMBER);
            m.add(i);
        }
        m.addSeparator();
        m.add(item("Actualiser la liste", () -> refreshDevices(true)));
        m.add(item("Ouvrir les paramètres Bluetooth de Windows", () -> {
            try { Desktop.getDesktop().browse(new URI("ms-settings:bluetooth")); } catch (Exception ignore) {}
        }));
        return m;
    }

    private void help() {
        JOptionPane.showMessageDialog(frame,
                "1. Appairez le poste dans Paramètres Windows > Bluetooth (une seule fois).\n"
                        + "2. Coupez le Bluetooth du téléphone : le poste n'accepte qu'une connexion.\n"
                        + "3. Choisissez le poste (Poste ›) et cliquez sur SE CONNECTER.\n"
                        + "   RT-660 : commandes en BLE et audio en Bluetooth classique, comme Radtel Tools.\n"
                        + "4. SARSAT / radiosondes : canal nommé SARSAT ou Radiosonde, ou mode forcé.\n"
                        + "   APRS : toujours actif (TNC du poste + démodulateur logiciel).\n"
                        + "5. BANDSCOPE : le poste balaie ±100 kHz à ±1 MHz autour du canal (S-mètre à chaque pas,\n"
                        + "   rien n'est écrit en mémoire). Double-clic pour écouter une fréquence. Pendant le\n"
                        + "   balayage, le poste n'écoute plus le canal : SARSAT et sondes sont en pause.\n"
                        + "6. POCSAG : décodage permanent ; émission (radioamateurs, bandes 2 m / 70 cm) depuis l'onglet.\n"
                        + "7. VFO : double veille (écoute des 2 VFO) et accord sur une fréquence libre.\n\n"
                        + "Carte : glisser pour déplacer, molette pour zoomer, clic sur une épingle.\n"
                        + "Résultats (CSV, WAV, journal APRS, tuiles) : " + outDir.getAbsolutePath(),
                "HTMod " + VERSION, JOptionPane.INFORMATION_MESSAGE);
    }

    // ------------------------------------------------------------------ Actions

    private void setForced(int mode) {
        decoders.forcedMode = mode;
        prefs.putInt("forcedMode", mode);
        updateMode();
    }

    private void updateMode() {
        int f = decoders.forcedMode;
        autoKey.setSelected(f == Decoders.MODE_NONE);
        sarsatKey.setSelected(f == Decoders.MODE_SARSAT);
        sondeKey.setSelected(f == Decoders.MODE_SONDE);
        int m = decoders.effectiveMode(channelName);
        if (sweeping) modeChip.set("BALAYAGE BANDSCOPE · DÉCODAGES EN PAUSE", Shack.AMBER, Shack.TEXT);
        else if (m == Decoders.MODE_NONE) modeChip.set("APRS SEUL · SARSAT/SONDES EN ATTENTE", Shack.CYAN, Shack.TEXT2);
        else if (m == Decoders.MODE_SARSAT) modeChip.set("DÉCODAGE SARSAT" + (f != 0 ? " (FORCÉ)" : ""), Shack.RED, Shack.TEXT);
        else modeChip.set("DÉCODAGE RADIOSONDE" + (f != 0 ? " (FORCÉ)" : ""), Shack.GREEN, Shack.TEXT);
    }

    private void updateLink(boolean on, String text) {
        linkChip.set(text, on ? Shack.CYAN : Shack.MUTED, on ? Shack.TEXT2 : Shack.MUTED);
        connectKey.setText(on || reconnectTimer != null || radio.isRunning() ? "DÉCONNECTER" : "SE CONNECTER");
    }

    private void selectDevice(Radio.Device d) {
        device = d;
        prefs.put("mac", d.mac);
        modelLbl.setText(d.name);
        deviceLbl.setText(d.mac + "  ›");
    }

    private void refreshDevices(boolean verbose) {
        new Thread(() -> {
            try {
                List<Radio.Device> list = Radio.pairedDevices(bridgeExe);
                SwingUtilities.invokeLater(() -> {
                    devices = list;
                    String last = prefs.get("mac", "");
                    Radio.Device sel = null;
                    for (Radio.Device d : list) {
                        if (d.mac.equals(last)) sel = d;
                        else if (sel == null && looksLikeRadio(d.name)) sel = d;
                    }
                    if (sel != null) selectDevice(sel);
                    if (list.isEmpty()) log("Aucun appareil Bluetooth appairé : appairez le poste dans les paramètres Windows.");
                    else if (verbose) log(list.size() + " appareil(s) Bluetooth appairé(s)");
                });
            } catch (Exception e) {
                log("Liste Bluetooth impossible : " + e.getMessage());
            }
        }, "htmod-list").start();
    }

    private static boolean looksLikeRadio(String n) {
        String s = n.toUpperCase(Locale.ROOT);
        return s.contains("UV-PRO") || s.contains("UV-50") || s.contains("VR-N") || s.contains("GA-5") || s.contains("RT-660")
                || s.contains("DB50");
    }

    private void toggleConnect() {
        if (radio.isRunning() || reconnectTimer != null) {
            userDisconnect = true;
            stopReconnect();
            radio.disconnect();
            onStatus("Déconnecté");
            updateLink(false, "HORS LIGNE");
            return;
        }
        if (device == null) {
            devicesMenu().show(deviceLbl, 0, deviceLbl.getHeight());
            return;
        }
        userDisconnect = false;
        doConnect(device.mac);
    }

    private void doConnect(String mac) {
        try {
            radio.connect(mac);
            updateLink(false, "CONNEXION…");
        } catch (Exception e) {
            log("Connexion impossible : " + e.getMessage());
        }
    }

    private void stopReconnect() {
        if (reconnectTimer != null) { reconnectTimer.stop(); reconnectTimer = null; }
    }

    private void decodeWav(int mode) {
        FileDialog fd = new FileDialog(frame, "Choisir un fichier WAV", FileDialog.LOAD);
        fd.setDirectory(prefs.get("wavDir", outDir.getAbsolutePath()));
        fd.setFile("*.wav");
        fd.setVisible(true);
        if (fd.getFile() == null) return;
        File f = new File(fd.getDirectory(), fd.getFile());
        prefs.put("wavDir", f.getParent());
        String err = decodeWavFile(f, mode);
        if (err != null) JOptionPane.showMessageDialog(frame, err);
    }

    /** Decode un fichier WAV (SARSAT, sonde, APRS = -1, POCSAG = -2) ; renvoie un message d'erreur ou null. */
    private String decodeWavFile(File f, int mode) {
        try {
            Wav w = Wav.read(f);
            log("Fichier " + f.getName() + " : " + w.pcm.length + " échantillons à " + w.rate + " Hz, décodage "
                    + (mode == -2 ? "POCSAG" : mode < 0 ? "APRS" : Decoders.modeName(mode)) + "…");
            if (w.rate == Decoders.SAMPLE_RATE) waterfall.feed(w.pcm, 0, Math.min(w.pcm.length, Decoders.SAMPLE_RATE * 8));
            if (mode == -2) {
                pocsag.decodeFile(w.pcm, w.rate);
            } else if (mode < 0) {
                new Thread(() -> {
                    int[] n = {0};
                    Afsk1200 dec = new Afsk1200(w.rate, fr -> { n[0]++; onAx25(fr, "fichier"); });
                    dec.process(w.pcm, 0, w.pcm.length);
                    log("Fichier : " + n[0] + " trame(s) AX.25");
                }, "htmod-aprs-file").start();
            } else {
                decoders.decodeFile(mode, w.pcm, w.rate);
            }
            return null;
        } catch (Exception e) {
            return "Lecture impossible : " + e.getMessage();
        }
    }

    private void quit() {
        userDisconnect = true;
        if (transmitter != null && transmitter.isBusy()) {
            transmitter.cancel();
            try { Thread.sleep(300); } catch (InterruptedException ignore) {}
        }
        radio.disconnect();
        if (tray != null) SystemTray.getSystemTray().remove(tray);
        System.exit(0);
    }

    private void refreshCounters() {
        aprsCount.set(map.count(MapPanel.KIND_APRS));
        sondeCount.set(map.count(MapPanel.KIND_SONDE));
        sarsatCount.set(map.count(MapPanel.KIND_SARSAT));
    }

    // ------------------------------------------------------------------ APRS

    /** Trame AX.25 venue du TNC du poste, du demodulateur logiciel ou d'un fichier. */
    private void onAx25(byte[] frame, String origin) {
        Ax25 p = Ax25.parse(frame);
        if (p == null || !p.isUi()) return;
        long now = System.currentTimeMillis();
        String key = p.src + ">" + p.dst + ":" + p.info;
        synchronized (aprsSeen) {
            Long t = aprsSeen.get(key);
            if (t != null && now - t < 30_000 && !"fichier".equals(origin)) return; // doublon (relais, TNC + logiciel)
            aprsSeen.put(key, now);
            if (aprsSeen.size() > 2000) {
                Iterator<Map.Entry<String, Long>> it = aprsSeen.entrySet().iterator();
                while (it.hasNext()) if (now - it.next().getValue() > 60_000) it.remove();
            }
        }
        Aprs.Report r = Aprs.parse(p);
        logAprs(origin, r.tnc2);
        showReport(r, origin, now);
        if (r.message != null && !"Accusé".equals(r.type) && tray != null) {
            String title = "APRS " + r.source + " → " + r.addressee;
            SwingUtilities.invokeLater(() -> tray.displayMessage(title, r.message, TrayIcon.MessageType.INFO));
        }
    }

    /**
     * Stations des dernieres 24 h relues dans aprs.txt au demarrage : la carte et le tableau APRS (et la
     * page web) ne repartent pas de zero a chaque lancement.
     */
    private void restoreAprs() {
        File f = new File(outDir, "aprs.txt");
        if (!f.isFile()) return;
        new Thread(() -> {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                long len = raf.length(), from = Math.max(0, len - 3_000_000);
                raf.seek(from);
                byte[] b = new byte[(int) (len - from)];
                raf.readFully(b);
                String[] lines = new String(b, StandardCharsets.UTF_8).split("\r?\n");
                long limit = System.currentTimeMillis() - 24 * 3600_000L;
                SimpleDateFormat utc = utcFormat();
                List<Object[]> ok = new ArrayList<>();
                for (int i = from > 0 ? 1 : 0; i < lines.length; i++) { // premiere ligne peut etre coupee
                    String l = lines[i];
                    int lb = l.indexOf(" ["), rb = l.indexOf("] ");
                    if (l.length() < 22 || lb != 19 || rb < 0) continue;
                    long t;
                    try { t = utc.parse(l.substring(0, 19)).getTime(); } catch (Exception e) { continue; }
                    if (t < limit) continue;
                    String origin = l.substring(lb + 2, rb), tnc2 = l.substring(rb + 2);
                    int gt = tnc2.indexOf('>'), colon = tnc2.indexOf(':');
                    if (gt <= 0 || colon <= gt) continue;
                    String src = tnc2.substring(0, gt), path = tnc2.substring(gt + 1, colon);
                    int comma = path.indexOf(',');
                    Aprs.Report r = Aprs.parse(src, comma < 0 ? path : path.substring(0, comma), tnc2.substring(colon + 1), 0);
                    r.tnc2 = tnc2;
                    ok.add(new Object[]{r, origin, t});
                }
                for (Object[] o : ok) showReport((Aprs.Report) o[0], (String) o[1], (Long) o[2]);
                if (!ok.isEmpty()) log("APRS : " + ok.size() + " trame(s) des dernières 24 h rechargée(s) depuis aprs.txt");
            } catch (Exception e) {
                log("APRS : relecture de aprs.txt impossible (" + e.getMessage() + ")");
            }
        }, "htmod-aprs-restauration").start();
    }

    /** Ajoute une trame decodee au tableau APRS et, si elle a une position, a la carte. */
    private void showReport(Aprs.Report r, String origin, long now) {
        AprsRow row = new AprsRow();
        row.time = now;
        row.name = r.name;
        row.via = r.name.equals(r.source) ? "" : r.source;
        row.type = r.type;
        row.hasPos = r.hasPos;
        row.pos = r.hasPos ? Aprs.fmtPos(r.lat, r.lon) : "";
        row.info = r.summary();
        row.tnc2 = r.tnc2;
        row.origin = origin;
        row.mapKey = "A:" + r.name;
        SwingUtilities.invokeLater(() -> {
            aprsModel.add(row);
            if (r.hasPos && !r.killed) {
                String sub = r.symbolName() + (row.via.isEmpty() ? "" : " · par " + row.via) + " · " + r.type;
                StringBuilder det = new StringBuilder(Aprs.fmtPos(r.lat, r.lon));
                String s = r.summary();
                if (!s.isEmpty()) det.append("\n").append(s);
                det.append("\n").append(r.tnc2);
                map.put(row.mapKey, MapPanel.KIND_APRS, r.name, sub, det.toString(), r.lat, r.lon, r.symTable, r.symCode, now);
            }
            aprsHeader.setText(aprsModel.getRowCount() + " paquet(s) · " + map.count(MapPanel.KIND_APRS) + " station(s) sur la carte");
            refreshCounters();
        });
    }

    private final SimpleDateFormat utcFmt = utcFormat();

    private static SimpleDateFormat utcFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f;
    }

    private synchronized void logAprs(String origin, String tnc2) {
        outDir.mkdirs();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(outDir, "aprs.txt"), true), StandardCharsets.UTF_8)) {
            w.write(utcFmt.format(new Date()) + " [" + origin + "] " + tnc2 + "\r\n");
        } catch (IOException e) {
            log("Journal APRS : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Notifications Windows

    private void setupTray() {
        if (!SystemTray.isSupported()) return;
        tray = new TrayIcon(icon(16), "HTMod");
        tray.setImageAutoSize(true);
        tray.addActionListener(e -> {
            Decoders.Detection d = lastNotified;
            frame.setVisible(true);
            frame.setState(JFrame.NORMAL);
            frame.toFront();
            if (d != null && d.hasPos) { selectTab("CARTE"); map.focus(mapKey(d)); }
        });
        try {
            SystemTray.getSystemTray().add(tray);
        } catch (AWTException e) {
            tray = null;
        }
    }

    static Image icon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Shack.smooth(img.getGraphics());
        g.setColor(Shack.BG);
        g.fillRoundRect(0, 0, size, size, size / 3, size / 3);
        Shack.GLOBE.paint(g, Math.round(size * 0.14f), Math.round(size * 0.14f), Math.round(size * 0.72f), Shack.AMBER);
        g.dispose();
        return img;
    }

    private static String mapKey(Decoders.Detection d) {
        if ("SARSAT".equals(d.kind)) return "S:" + d.id;
        return "R:" + (d.id != null && !d.id.isEmpty() ? d.id : d.kind);
    }

    private void openMapWeb(double lat, double lon) {
        String url = "https://www.openstreetmap.org/?mlat=" + Decoders.fmt(lat, 6) + "&mlon=" + Decoders.fmt(lon, 6)
                + "#map=13/" + Decoders.fmt(lat, 6) + "/" + Decoders.fmt(lon, 6);
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            log("Ouverture de la carte impossible : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Rappels Radio / Decoders

    @Override
    public void onStatus(String text) {
        SwingUtilities.invokeLater(() -> {
            statusLbl.setText(text);
            if (text.startsWith("Connecté") || text.startsWith("Commandes ouvertes")) {
                stopReconnect();
                updateLink(true, "CONNECTÉ");
            }
        });
    }

    @Override
    public void onLog(String text) { log(text); }

    @Override
    public void onChannel(String name, boolean receiving) {
        channelName = name == null ? "" : name;
        SwingUtilities.invokeLater(() -> {
            channelLbl.setText(channelName.isEmpty() ? "?" : channelName);
            channelLbl.setForeground(receiving ? Shack.CYAN : Shack.TEXT);
            updateMode();
        });
        if (bandscope != null) bandscope.refreshCenter();
        if (vfo != null) vfo.refreshSoon();
    }

    @Override
    public void onAudio(short[] pcm, int len) {
        waterfall.feed(pcm, 0, len);
        if (!sweeping) { // en balayage, l'audio vient de frequences voisines
            decoders.feed(channelName, pcm, 0, len);
            pocsag.feed(pcm, len);
            sstv.feed(pcm, len);
        }
        if (prefs.getBoolean("swAprs", true)) afsk.process(pcm, 0, len);
        WebServer ws = web;
        if (ws != null && ws.isRunning()) // meme coupure des trames APRS que pour le haut-parleur du PC
            ws.audio(muteAprsSw.isOn() ? webMute.filter(pcm, len, AprsMute.aprsChannel(channelName, radio.currentRxHz())) : pcm, len);
        if (listenSw.isOn()) {
            // le decodage ci-dessus a deja recu l'audio intact : on ne coupe que le haut-parleur
            if (muteAprsSw.isOn()) play(aprsMute.filter(pcm, len, AprsMute.aprsChannel(channelName, radio.currentRxHz())), len);
            else play(pcm, len);
        }
    }

    @Override
    public void onAudioEnd() { closeSpeaker(); }

    @Override
    public void onTncFrame(byte[] f) { onAx25(f, "TNC"); }

    @Override
    public void onHtStatus(int rssi, boolean squelchOpen) {
        if (bandscope != null) bandscope.onHtStatus(rssi, squelchOpen);
        if (vfo != null) vfo.refreshSoon();
    }

    @Override
    public void onSettings(boolean dualWatch, int channelA, int channelB) { if (vfo != null) vfo.refreshSoon(); }

    @Override
    public void onChannelsChanged() { if (vfo != null) vfo.channelsChanged(); }

    @Override
    public void onDisconnected() {
        if (bandscope != null) bandscope.onDisconnected();
        if (sats != null) sats.onDisconnected();
        SwingUtilities.invokeLater(() -> {
            channelLbl.setText("–");
            channelLbl.setForeground(Shack.TEXT);
            channelName = "";
            updateMode();
            if (!userDisconnect && reconnectTimer == null) {
                log("Liaison perdue, nouvel essai toutes les 15 s (DÉCONNECTER pour arrêter).");
                reconnectTimer = new Timer(15000, e -> {
                    if (radio.isRunning() || userDisconnect) return;
                    String mac = prefs.get("mac", "");
                    if (!mac.isEmpty()) doConnect(mac);
                });
                reconnectTimer.start();
            }
            updateLink(false, reconnectTimer != null ? "NOUVEL ESSAI…" : "HORS LIGNE");
        });
        closeSpeaker();
    }

    @Override
    public void onDetection(Decoders.Detection d) {
        SwingUtilities.invokeLater(() -> {
            detections.add(d);
            if (d.hasPos) {
                int kind = "SARSAT".equals(d.kind) ? MapPanel.KIND_SARSAT : MapPanel.KIND_SONDE;
                String title = "SARSAT".equals(d.kind) ? d.id : (d.id != null && !d.id.isEmpty() ? d.id.replace("IDa:", "") : d.kind);
                map.put(mapKey(d), kind, title, d.title, d.text, d.lat, d.lon, '/', 'O');
            }
            refreshCounters();
            if (tray != null) {
                lastNotified = d;
                tray.displayMessage(d.title, d.text, d.alert ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
            }
        });
    }

    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    /** Dernieres lignes du journal, pour l'interface web. */
    private final java.util.ArrayDeque<String> webLog = new java.util.ArrayDeque<>();

    private void log(String s) {
        String line = logTime.format(new Date()) + "  " + s + "\n";
        synchronized (webLog) {
            webLog.addLast(line.substring(0, line.length() - 1));
            while (webLog.size() > 400) webLog.removeFirst();
        }
        SwingUtilities.invokeLater(() -> {
            if (logArea == null) return;
            logArea.append(line);
            if (logArea.getDocument().getLength() > 400_000) logArea.replaceRange("", 0, 100_000);
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    // ------------------------------------------------------------------ Ecoute

    private synchronized void play(short[] pcm, int len) {
        try {
            if (speaker == null) {
                AudioFormat fmt = new AudioFormat(Decoders.SAMPLE_RATE, 16, 1, true, false);
                speaker = AudioSystem.getSourceDataLine(fmt);
                speaker.open(fmt, Decoders.SAMPLE_RATE); // ~0,5 s de tampon
                speaker.start();
            }
            byte[] b = new byte[len * 2];
            for (int i = 0; i < len; i++) { b[2 * i] = (byte) pcm[i]; b[2 * i + 1] = (byte) (pcm[i] >> 8); }
            if (speaker.available() >= b.length) speaker.write(b, 0, b.length); // en retard : on saute
            lastPlayMs = System.currentTimeMillis();
        } catch (Exception e) {
            log("Sortie audio impossible : " + e.getMessage());
            SwingUtilities.invokeLater(() -> listenSw.setOn(false));
            prefs.putBoolean("listen", false);
        }
    }

    // ------------------------------------------------------------------ Interface web : etat et commandes

    /** Pont entre l'application et la page web (toujours appele sur le fil Swing, sauf mention). */
    private final class WebBackend implements WebServer.Backend {
        private final SimpleDateFormat dt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

        @Override
        public Map<String, Object> snapshot(boolean slow) {
            Map<String, Object> s = new java.util.LinkedHashMap<>();
            List<Object> devs = new ArrayList<>();
            for (Radio.Device d : devices) devs.add(Json.obj("name", d.name, "mac", d.mac));
            s.put("radio", Json.obj("model", modelLbl.getText(), "device", deviceLbl.getText(), "devices", devs,
                    "mac", device == null ? "" : device.mac, "link", linkChip.text(), "linkDot", linkChip.dot(), "linkFg", linkChip.fg(),
                    "connectKey", connectKey.getText(), "status", statusLbl.getText(), "channel", channelLbl.getText(),
                    "channelColor", channelLbl.getForeground(), "mode", modeChip.text(), "modeDot", modeChip.dot(), "modeFg", modeChip.fg(),
                    "forced", decoders.forcedMode, "running", radio.isRunning()));
            s.put("options", Json.obj("listen", listenSw.isOn(), "muteAprs", muteAprsSw.isOn(), "wav", wavSw.isOn(), "web", webSw.isOn(),
                    "swAprs", prefs.getBoolean("swAprs", true), "night", map.webNight(), "topo", map.webTopo(),
                    "wind", prefs.getBoolean("mapWind", false), "outDir", outDir.getAbsolutePath(), "version", VERSION));
            s.put("counters", new int[]{map.count(MapPanel.KIND_APRS), map.count(MapPanel.KIND_SONDE), map.count(MapPanel.KIND_SARSAT)});
            float[] sp;
            synchronized (spectrum.v) { sp = spectrum.v.clone(); }
            s.put("spec", sp);
            List<Object> ar = new ArrayList<>();
            for (int i = 0; i < Math.min(300, aprsModel.rows.size()); i++) {
                AprsRow a = aprsModel.rows.get(i);
                ar.add(new Object[]{aprsModel.getValueAt(i, 0), aprsModel.getValueAt(i, 1), aprsModel.getValueAt(i, 2), a.pos,
                    aprsModel.getValueAt(i, 4), a.tnc2, a.hasPos ? a.mapKey : null});
            }
            s.put("aprs", Json.obj("rows", ar, "header", aprsHeader.getText()));
            List<Object> dr = new ArrayList<>();
            for (int i = 0; i < Math.min(300, detections.rows.size()); i++) {
                Decoders.Detection d = detections.rows.get(i);
                Object[] row = new Object[7];
                for (int c = 0; c < 6; c++) row[c] = detections.getValueAt(i, c);
                row[6] = d.hasPos ? mapKey(d) : null;
                dr.add(row);
            }
            s.put("det", dr);
            s.put("markers", map.webMarkers());
            synchronized (webLog) { s.put("log", new ArrayList<>(webLog)); }
            s.put("bs", bandscope.web());
            s.put("pocsag", pocsag.web());
            s.put("sstv", sstv.web());
            s.put("vfo", vfo.web());
            if (slow) s.put("sats", sats.web());
            return s;
        }

        @Override
        public Object command(String a, Map<String, String> p) {
            int dot = a.indexOf('.');
            if (dot > 0) {
                String panel = a.substring(0, dot), act = a.substring(dot + 1);
                switch (panel) {
                    case "bs": bandscope.webCmd(act, p); return null;
                    case "pocsag": return pocsag.webCmd(act, p);
                    case "sstv": return sstv.webCmd(act, p);
                    case "vfo": return vfo.webCmd(act, p);
                    case "sats": return sats.webCmd(act, p);
                    default: return "Commande inconnue : " + a;
                }
            }
            switch (a) {
                case "connect":
                    if (!radio.isRunning() && reconnectTimer == null && device == null) return "Choisissez d'abord le poste (liste des appareils).";
                    toggleConnect();
                    return null;
                case "device":
                    for (Radio.Device d : devices) if (d.mac.equals(p.get("mac"))) selectDevice(d);
                    return null;
                case "devices": refreshDevices(true); return null;
                case "forced": setForced(Integer.parseInt(p.get("v"))); return null;
                case "option": {
                    boolean on = "1".equals(p.get("on"));
                    switch (p.getOrDefault("name", "")) {
                        case "listen": listenSw.setOn(on); prefs.putBoolean("listen", on); if (!on) closeSpeaker(); break;
                        case "muteAprs": muteAprsSw.setOn(on); prefs.putBoolean("muteAprs", on); break;
                        case "wav": wavSw.setOn(on); decoders.saveWav = on; prefs.putBoolean("saveWav", on); break;
                        case "swAprs": prefs.putBoolean("swAprs", on); break;
                        case "web": webSw.setOn(on); prefs.putBoolean("webOn", on); SwingUtilities.invokeLater(App.this::applyWeb); break;
                        default: break;
                    }
                    return null;
                }
                case "clear":
                    map.clear();
                    aprsModel.clear();
                    detections.clear();
                    refreshCounters();
                    aprsHeader.setText("0 paquet");
                    return null;
                default: return "Commande inconnue : " + a;
            }
        }

        @Override
        public Object commandOffEdt(String a, Map<String, String> p) {
            if ("sats.fetchTle".equals(a)) return sats.webCmd("fetchTle", p); // reseau : hors du fil Swing
            return WebServer.Backend.NOT_HANDLED;
        }

        @Override
        public byte[] tile(boolean topo, boolean night, int z, int x, int y) { return map.webTile(topo, night, z, x, y); }

        @Override
        public BufferedImage image(String which, String name) {
            switch (which) {
                case "rx": return sstv.webRxImage();
                case "tx": return sstv.webTxImage();
                default: {
                    File f = sstv.webFile(name);
                    try { return f == null ? null : javax.imageio.ImageIO.read(f); } catch (IOException e) { return null; }
                }
            }
        }

        @Override
        public String upload(String what, Map<String, String> p, byte[] data) {
            if ("sstv".equals(what)) {
                String[] r = {null};
                try { SwingUtilities.invokeAndWait(() -> r[0] = sstv.webUpload(data)); } catch (Exception e) { return e.getMessage(); }
                return r[0];
            }
            if ("wav".equals(what)) {
                try {
                    File tmp = File.createTempFile("htmod-web-", ".wav");
                    tmp.deleteOnExit();
                    java.nio.file.Files.write(tmp.toPath(), data);
                    return decodeWavFile(tmp, Integer.parseInt(p.getOrDefault("mode", "-1")));
                } catch (Exception e) {
                    return "Fichier illisible : " + e.getMessage();
                }
            }
            return "Envoi inconnu";
        }
    }

    // ------------------------------------------------------------------ Serveur web

    /** Demarre ou arrete le serveur web selon l'interrupteur ; affiche adresses et code d'acces. */
    private void applyWeb() {
        if (webSw == null) return;
        if (webSw.isOn()) {
            String pin = prefs.get("webPin", "");
            if (pin.length() != 6) {
                pin = String.format(Locale.ROOT, "%06d", new java.security.SecureRandom().nextInt(1_000_000));
                prefs.put("webPin", pin);
            }
            if (web == null) web = new WebServer(frame, new WebBackend(), pin, this::log);
            try {
                web.start(prefs.getInt("webPort", 8080));
                webInfo.setText("<html>" + String.join("<br>", web.urls()) + "<br>code d'accès : <b>" + pin + "</b></html>");
                webInfo.setForeground(Shack.CYAN);
                checkFirewall(); // touche AUTORISER DANS LE PARE-FEU si la regle manque (rien d'automatique)
            } catch (Exception e) {
                log("Serveur web impossible sur le port " + prefs.getInt("webPort", 8080) + " : " + e.getMessage());
                webInfo.setText("Port " + prefs.getInt("webPort", 8080) + " indisponible");
                webInfo.setForeground(Shack.RED);
            }
        } else {
            if (web != null) web.stop();
            webInfo.setText("");
            fwKey.setVisible(false);
        }
        webInfo.revalidate();
    }

    /** Verifie (lecture seule) la regle du pare-feu et propose la touche si elle manque. */
    private void checkFirewall() {
        new Thread(() -> {
            boolean ok = Firewall.exists();
            SwingUtilities.invokeLater(() -> {
                fwKey.setVisible(!ok && webSw.isOn());
                fwKey.getParent().revalidate();
            });
        }, "htmod-parefeu-verif").start();
    }

    private synchronized void closeSpeaker() {
        if (speaker != null) { speaker.stop(); speaker.flush(); speaker.close(); speaker = null; }
    }

    // ------------------------------------------------------------------ Petits composants

    /** Marque « HT » + « MOD » ambre (comme RADTEL + TOOLS). */
    private static final class Brand extends JComponent {
        Brand() { setPreferredSize(new Dimension(100, 32)); setMaximumSize(new Dimension(100, 32)); }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Shack.smooth(g0);
            g.setFont(Shack.tracked(Shack.display(25f), 0.06f));
            FontMetrics fm = g.getFontMetrics();
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(Shack.TEXT);
            g.drawString("HT", 0, y);
            g.setColor(Shack.AMBER);
            g.drawString("MOD", fm.stringWidth("HT") + 1, y);
            g.dispose();
        }
    }

    private static final class IconView extends JComponent {
        private final Shack.Icon icon;
        private final Color color;
        private final int size;

        IconView(Shack.Icon icon, Color color, int size) {
            this.icon = icon;
            this.color = color;
            this.size = size;
            Dimension d = new Dimension(size, size);
            setPreferredSize(d);
            setMaximumSize(d);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Shack.smooth(g0);
            icon.paint(g, 0, 0, size, color);
            g.dispose();
        }
    }

    /** Petite touche compteur : nombre en grand, libelle mono. */
    private static final class Counter extends JComponent {
        private final String label;
        private final Color color;
        private int value;

        Counter(String label, Color color) { this.label = label; this.color = color; }

        void set(int v) { if (v != value) { value = v; repaint(); } }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Shack.smooth(g0);
            int w = getWidth(), h = getHeight() - 4;
            Shack.paintShadow(g, 0, 0, w, h, 14, 4);
            Shack.paintKey(g, 0, 0, w, h, 14, Shack.KEY_TOP, Shack.KEY_BOT);
            g.setFont(Shack.display(26f));
            FontMetrics fm = g.getFontMetrics();
            String v = String.valueOf(value);
            g.setColor(value > 0 ? color : Shack.MUTED);
            g.drawString(v, (w - fm.stringWidth(v)) / 2, 36);
            g.setFont(Shack.tracked(Shack.mono(10f), 0.14f));
            fm = g.getFontMetrics();
            g.setColor(Shack.MUTED);
            g.drawString(label, (w - fm.stringWidth(label)) / 2, h - 12);
            g.dispose();
        }
    }

    /** Spectre audio en direct (cyan, rempli) avec repere ambre sur le pic, comme SpectrumView. */
    private static final class SpectrumStrip extends JComponent {
        private final float[] v = new float[100];
        private long last;

        SpectrumStrip() {
            new Timer(60, e -> {
                if (System.currentTimeMillis() - last > 400) {
                    for (int i = 0; i < v.length; i++) v[i] *= 0.85f;
                }
                repaint();
            }).start();
        }

        void push(float[] col) {
            synchronized (v) {
                for (int i = 0; i < v.length && i < col.length; i++) v[i] = v[i] * 0.55f + col[i] * 0.45f;
            }
            last = System.currentTimeMillis();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = Shack.smooth(g0);
            int w = getWidth(), h = getHeight();
            float base = h * 0.88f;
            float[] s;
            synchronized (v) { s = v.clone(); }
            long t = System.currentTimeMillis();
            Path2D line = new Path2D.Float(), area = new Path2D.Float();
            int peak = 0;
            for (int i = 0; i < s.length; i++) if (s[i] > s[peak]) peak = i;
            for (int i = 0; i < s.length; i++) {
                float x = i / (float) (s.length - 1) * w;
                float noise = h * 0.03f * (float) Math.abs(Math.sin(x * 0.11f + t / 110.0));
                float y = base - noise - s[i] * h * 0.78f;
                if (i == 0) { line.moveTo(x, y); area.moveTo(x, h); area.lineTo(x, y); }
                else { line.lineTo(x, y); area.lineTo(x, y); }
            }
            area.lineTo(w, h);
            area.closePath();
            g.setColor(Shack.alpha(Shack.CYAN, 36));
            g.fill(area);
            g.setColor(Shack.CYAN);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(line);
            if (s[peak] > 0.15f) {
                float px = peak / (float) (s.length - 1) * w;
                g.setColor(Shack.AMBER);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 6f}, 0f));
                g.drawLine(Math.round(px), 0, Math.round(px), h);
                g.setFont(Shack.mono(10f));
                String f = String.format(Locale.ROOT, "%.1f kHz", peak * 16.0 / (s.length - 1));
                int tx = Math.min(Math.round(px) + 5, w - g.getFontMetrics().stringWidth(f) - 4);
                g.drawString(f, tx, 12);
            }
            g.dispose();
        }
    }

    /** Fiche de l'epingle selectionnee, en surimpression sur la carte. */
    private final class InfoCard extends Shack.Card {
        private final JLabel title = Shack.label("", Shack.display(20f), Shack.TEXT);
        private final JLabel sub = Shack.label("", Shack.mono(11f), Shack.MUTED);
        private final JTextArea details = new JTextArea();
        private MapPanel.Marker marker;

        InfoCard() {
            super(16);
            setLayout(new BorderLayout(0, 6));
            setBorder(new EmptyBorder(12, 16, 10, 14));
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(false);
            head.add(title, BorderLayout.NORTH);
            head.add(sub, BorderLayout.SOUTH);
            add(head, BorderLayout.NORTH);
            details.setEditable(false);
            details.setOpaque(false);
            details.setLineWrap(true);
            details.setWrapStyleWord(true);
            details.setFont(Shack.mono(11.5f));
            details.setForeground(Shack.TEXT2);
            add(details, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            actions.setOpaque(false);
            JLabel web = Shack.label("Ouvrir sur OpenStreetMap ›", Shack.mono(11.5f), Shack.AMBER);
            web.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            web.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (marker != null) openMapWeb(marker.lat, marker.lon); }
            });
            actions.add(web);
            JLabel close = Shack.label("     Fermer ×", Shack.mono(11.5f), Shack.MUTED);
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            close.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { InfoCard.this.setVisible(false); }
            });
            actions.add(close);
            add(actions, BorderLayout.SOUTH);
            setVisible(false);
        }

        void show(MapPanel.Marker m) {
            marker = m;
            if (m == null) { setVisible(false); return; }
            title.setText(m.title);
            title.setForeground(MapPanel.kindColor(m.kind));
            sub.setText(m.subtitle + "  ·  " + logTime.format(new Date(m.time)));
            details.setText(m.details);
            setVisible(true);
            if (getParent() != null) getParent().doLayout();
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            details.setSize(380, Short.MAX_VALUE);
            int dh = details.getPreferredSize().height;
            return new Dimension(420, 92 + Math.min(dh, 160));
        }
    }

    // ------------------------------------------------------------------ Tableaux

    private static final class AprsRow {
        long time;
        String name, via, type, pos, info, tnc2, origin, mapKey;
        boolean hasPos;
    }

    private static final class AprsModel extends AbstractTableModel {
        private static final String[] COLS = {"Heure", "Indicatif", "Type", "Position", "Informations"};
        private final List<AprsRow> rows = new ArrayList<>();
        private final SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

        void add(AprsRow r) {
            rows.add(0, r);
            if (rows.size() > 3000) rows.remove(rows.size() - 1);
            fireTableDataChanged();
        }

        void clear() { rows.clear(); fireTableDataChanged(); }
        AprsRow get(int i) { return rows.get(i); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            AprsRow a = rows.get(r);
            switch (c) {
                case 0: return tf.format(new Date(a.time));
                case 1: return a.name + (a.via.isEmpty() ? "" : "  (" + a.via + ")");
                case 2: return a.type + ("TNC".equals(a.origin) ? "" : " ·");
                case 3: return a.pos;
                default: return a.info.isEmpty() ? a.tnc2 : a.info;
            }
        }
    }

    private static final class DetectionModel extends AbstractTableModel {
        private static final String[] COLS = {"Reçu (local)", "Type", "Identifiant", "Latitude", "Longitude", "Détails"};
        private final List<Decoders.Detection> rows = new ArrayList<>();
        private final SimpleDateFormat tf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

        void add(Decoders.Detection d) {
            rows.add(0, d);
            fireTableRowsInserted(0, 0);
        }

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        Decoders.Detection get(int i) { return rows.get(i); }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override
        public Object getValueAt(int r, int c) {
            Decoders.Detection d = rows.get(r);
            switch (c) {
                case 0: return tf.format(new Date(d.receivedMs));
                case 1: return d.kind;
                case 2: return (d.id == null ? "" : d.id) + (d.count > 1 ? " ×" + d.count : "");
                case 3: return d.hasPos ? Decoders.fmt(d.lat, 5) : "";
                case 4: return d.hasPos ? Decoders.fmt(d.lon, 5) : "";
                default: return d.text.replace('\n', ' ').replace("  ", " ");
            }
        }
    }
}
