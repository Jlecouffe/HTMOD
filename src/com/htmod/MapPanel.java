/*
 * HTMOD Windows : carte OpenStreetMap (tuiles Mapnik ou OpenTopoMap, cache disque), avec les
 * stations APRS, les radiosondes (et leur trace) et les balises SARSAT. Mode nuit = couleurs
 * inversees comme dans Radtel Tools (TilesOverlay.INVERT_COLORS). Glisser pour deplacer, molette
 * pour zoomer, clic sur une epingle pour sa fiche.
 */
package com.htmod;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

final class MapPanel extends JComponent {
    static final int KIND_APRS = 0, KIND_SONDE = 1, KIND_SARSAT = 2;
    private static final int TILE = 256;
    private static final String USER_AGENT = "HTMod/1.1 (Windows; decodeur radioamateur SARSAT/radiosondes/APRS)";

    static final class Marker {
        final String key;
        int kind;
        String title = "", subtitle = "", details = "";
        char symTable = '/', symCode = ' ';
        double lat, lon;
        long time;
        final List<double[]> track = new ArrayList<>();

        Marker(String key) { this.key = key; }
    }

    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final File cacheDir;
    private final Map<String, Marker> markers = new LinkedHashMap<>();
    private final Map<String, BufferedImage> tiles = Collections.synchronizedMap(new LinkedHashMap<String, BufferedImage>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 400; }
    });
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "htmod-tuiles");
        t.setDaemon(true);
        return t;
    });

    private int zoom;
    private double cx, cy; // centre en pixels monde au zoom courant
    private boolean night, topo;
    private Marker selected;
    private Point dragFrom;
    private double dragCx, dragCy;
    private Consumer<Marker> onSelect = m -> {};
    private boolean fittedOnce;
    private volatile boolean tileErrorLogged;
    /** Journal de l'appli (erreurs de tuiles). */
    Consumer<String> logSink = s -> {};

    /** Calque dessine par-dessus la carte (satellites...). */
    interface Overlay {
        void paint(Graphics2D g, MapPanel map);
        /** Clic sur la carte : true si le calque l'a traite. */
        default boolean click(MapPanel map, int x, int y) { return false; }
    }

    private final String key;
    private Overlay overlay;

    void setOverlay(Overlay o) { overlay = o; repaint(); }

    /** Calque dessine sur les tuiles, sous les traces et les epingles (vent...). */
    private Overlay underlay;

    void setUnderlay(Overlay o) { underlay = o; repaint(); }

    /** {lat, lon} du point ecran (x, y). */
    double[] fromScreen(double x, double y) {
        return new double[]{yToLat(cy - getHeight() / 2.0 + y), xToLon(cx - getWidth() / 2.0 + x)};
    }

    /** Coordonnees ecran d'un point (copie du monde la plus proche du centre). */
    java.awt.geom.Point2D.Double toScreen(double lat, double lon) {
        double ws = worldSize(), x = lonToX(lon), y = latToY(lat);
        while (x - cx > ws / 2) x -= ws;
        while (x - cx < -ws / 2) x += ws;
        return new java.awt.geom.Point2D.Double(x - cx + getWidth() / 2.0, y - cy + getHeight() / 2.0);
    }

    /** Pixels par kilometre a cette latitude (pour les cercles d'empreinte). */
    double pixelsPerKm(double lat) {
        return worldSize() / (40075.016686 * Math.cos(Math.toRadians(Math.max(-85, Math.min(85, lat)))));
    }

    void centerOn(double lat, double lon) { setCenter(lat, lon); saveView(); repaint(); }

    int zoomLevel() { return zoom; }

    MapPanel(File cacheDir) { this(cacheDir, "map"); }

    MapPanel(File cacheDir, String key) {
        this.key = key;
        this.cacheDir = cacheDir;
        night = prefs.getBoolean(key + "Night", true);
        topo = prefs.getBoolean(key + "Topo", false);
        zoom = prefs.getInt(key + "Zoom", 6);
        setCenter(prefs.getDouble(key + "Lat", 46.6), prefs.getDouble(key + "Lon", 2.4));
        fittedOnce = prefs.getBoolean(key + "Fitted", false);
        setOpaque(true);
        setPreferredSize(new Dimension(800, 600));
        setFocusable(true);

        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragFrom = e.getPoint();
                dragCx = cx;
                dragCy = cy;
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (dragFrom == null) return;
                cx = dragCx - (e.getX() - dragFrom.x);
                cy = dragCy - (e.getY() - dragFrom.y);
                clampY();
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) {
                if (dragFrom != null && dragFrom.distance(e.getPoint()) < 4) click(e);
                dragFrom = null;
                saveView();
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(e.getWheelRotation() < 0 ? 1 : -1, e.getX(), e.getY());
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    void setOnSelect(Consumer<Marker> c) { onSelect = c; }
    boolean isNight() { return night; }
    boolean isTopo() { return topo; }

    void setNight(boolean v) { night = v; prefs.putBoolean(key + "Night", v); tiles.clear(); repaint(); }

    void setTopo(boolean v) {
        topo = v;
        prefs.putBoolean(key + "Topo", v);
        if (v && zoom > 17) zoom = 17;
        tiles.clear();
        failed.clear();
        repaint();
    }

    // ------------------------------------------------------------------ Projection

    private double worldSize() { return TILE * Math.pow(2, zoom); }

    private double lonToX(double lon) { return (lon + 180) / 360 * worldSize(); }

    private double latToY(double lat) {
        double l = Math.toRadians(Math.max(-85.05, Math.min(85.05, lat)));
        return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2 * worldSize();
    }

    private double xToLon(double x) { return x / worldSize() * 360 - 180; }

    private double yToLat(double y) {
        double n = Math.PI - 2 * Math.PI * y / worldSize();
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private void setCenter(double lat, double lon) {
        cx = lonToX(lon);
        cy = latToY(lat);
    }

    private void clampY() { cy = Math.max(0, Math.min(worldSize(), cy)); }

    private int maxZoom() { return topo ? 17 : 19; }

    void zoomAt(int dz, int sx, int sy) {
        int nz = Math.max(2, Math.min(maxZoom(), zoom + dz));
        if (nz == zoom) return;
        double lat = yToLat(cy + sy - getHeight() / 2.0), lon = xToLon(cx + sx - getWidth() / 2.0);
        zoom = nz;
        double nx = lonToX(lon), ny = latToY(lat);
        cx = nx - (sx - getWidth() / 2.0);
        cy = ny - (sy - getHeight() / 2.0);
        clampY();
        saveView();
        repaint();
    }

    void zoomIn() { zoomAt(1, getWidth() / 2, getHeight() / 2); }
    void zoomOut() { zoomAt(-1, getWidth() / 2, getHeight() / 2); }

    void focus(String key) {
        Marker mk;
        synchronized (markers) { mk = markers.get(key); }
        if (mk == null) return;
        if (zoom < 11) zoom = 11;
        setCenter(mk.lat, mk.lon);
        selected = mk;
        onSelect.accept(mk);
        saveView();
        repaint();
    }

    /** Cadre toutes les epingles. */
    void fitAll() {
        List<Marker> all = snapshot();
        if (all.isEmpty() || getWidth() == 0) return;
        double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
        for (Marker m : all) {
            minLat = Math.min(minLat, m.lat); maxLat = Math.max(maxLat, m.lat);
            minLon = Math.min(minLon, m.lon); maxLon = Math.max(maxLon, m.lon);
        }
        for (zoom = 13; zoom > 2; zoom--) {
            double w = lonToX(maxLon) - lonToX(minLon), h = latToY(minLat) - latToY(maxLat);
            if (w < getWidth() * 0.8 && h < getHeight() * 0.75) break;
        }
        cx = (lonToX(minLon) + lonToX(maxLon)) / 2;
        cy = (latToY(minLat) + latToY(maxLat)) / 2;
        saveView();
        repaint();
    }

    private void saveView() {
        prefs.putInt(key + "Zoom", zoom);
        prefs.putDouble(key + "Lat", yToLat(cy));
        prefs.putDouble(key + "Lon", xToLon(cx));
    }

    // ------------------------------------------------------------------ Epingles

    /** Ajoute ou met a jour une epingle (sur le fil Swing). */
    void put(String key, int kind, String title, String subtitle, String details, double lat, double lon, char symTable, char symCode) {
        put(key, kind, title, subtitle, details, lat, lon, symTable, symCode, System.currentTimeMillis());
    }

    /** Idem avec l'heure de reception (stations relues au demarrage). */
    void put(String key, int kind, String title, String subtitle, String details, double lat, double lon, char symTable, char symCode, long time) {
        Marker m;
        synchronized (markers) {
            m = markers.get(key);
            if (m == null) {
                m = new Marker(key);
                markers.put(key, m);
            }
            boolean moved = m.track.isEmpty() || Math.abs(m.lat - lat) > 1e-6 || Math.abs(m.lon - lon) > 1e-6;
            m.kind = kind;
            m.title = title;
            m.subtitle = subtitle;
            m.details = details;
            m.lat = lat;
            m.lon = lon;
            m.symTable = symTable;
            m.symCode = symCode;
            m.time = time;
            if (moved) {
                m.track.add(new double[]{lat, lon});
                if (m.track.size() > 800) m.track.remove(0);
            }
        }
        if (!fittedOnce && getWidth() > 0) {
            fittedOnce = true;
            prefs.putBoolean(key + "Fitted", true);
            zoom = Math.max(zoom, 9);
            setCenter(lat, lon);
        }
        if (selected == m) onSelect.accept(m);
        repaint();
    }

    void clear() {
        synchronized (markers) { markers.clear(); }
        selected = null;
        onSelect.accept(null);
        repaint();
    }

    int count(int kind) {
        int n = 0;
        synchronized (markers) { for (Marker m : markers.values()) if (m.kind == kind) n++; }
        return n;
    }

    private List<Marker> snapshot() {
        synchronized (markers) { return new ArrayList<>(markers.values()); }
    }

    private void click(MouseEvent e) {
        Overlay ov = overlay;
        if (ov != null && ov.click(this, e.getX(), e.getY())) { repaint(); return; }
        Marker best = null;
        double bd = 26;
        double ox = cx - getWidth() / 2.0, oy = cy - getHeight() / 2.0;
        for (Marker m : snapshot()) {
            double sx = lonToX(m.lon) - ox, sy = latToY(m.lat) - oy - 22; // centre de la tete de l'epingle
            double d = Math.hypot(sx - e.getX(), sy - e.getY());
            if (d < bd) { bd = d; best = m; }
        }
        if (best == null && e.getClickCount() == 2) { zoomAt(1, e.getX(), e.getY()); return; }
        selected = best;
        onSelect.accept(best);
        repaint();
    }

    static Color kindColor(int kind) {
        return kind == KIND_SARSAT ? Shack.RED : kind == KIND_SONDE ? Shack.GREEN : Shack.CYAN;
    }

    // ------------------------------------------------------------------ Tuiles

    private static String tileUrl(boolean topo, int z, int x, int y) {
        return topo ? "https://" + "abc".charAt((x + y) % 3) + ".tile.opentopomap.org/" + z + "/" + x + "/" + y + ".png"
                : "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
    }

    private BufferedImage tile(int z, int x, int y) {
        String key = (topo ? "t" : "o") + (night ? "n" : "j") + z + "/" + x + "/" + y;
        BufferedImage img = tiles.get(key);
        if (img != null || failed.contains(key) || !loading.add(key)) return img;
        pool.submit(() -> {
            try {
                BufferedImage raw = loadTile(topo, z, x, y);
                if (raw != null) {
                    tiles.put(key, night ? invert(raw) : raw);
                    SwingUtilities.invokeLater(this::repaint);
                } else {
                    failed.add(key);
                }
            } finally {
                loading.remove(key);
            }
        });
        return null;
    }

    private BufferedImage loadTile(boolean topo, int z, int x, int y) {
        File f = new File(cacheDir, (topo ? "topo/" : "osm/") + z + "/" + x + "/" + y + ".png");
        try {
            boolean fresh = f.isFile() && System.currentTimeMillis() - f.lastModified() < 30L * 86400_000L;
            if (!fresh) {
                HttpURLConnection c = (HttpURLConnection) new URL(tileUrl(topo, z, x, y)).openConnection();
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code != 200 && !tileErrorLogged) {
                    tileErrorLogged = true;
                    logSink.accept("Carte : le serveur de tuiles répond " + code + " (" + tileUrl(topo, z, x, y) + ")");
                }
                if (code == 200) {
                    f.getParentFile().mkdirs();
                    File tmp = new File(f.getPath() + ".part");
                    try (InputStream in = c.getInputStream()) {
                        Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                c.disconnect();
            }
            return f.isFile() ? ImageIO.read(f) : null;
        } catch (Exception e) {
            if (!tileErrorLogged) {
                tileErrorLogged = true;
                logSink.accept("Carte : téléchargement des tuiles impossible (" + e + ")");
            }
            try { return f.isFile() ? ImageIO.read(f) : null; } catch (Exception ignore) { return null; } // hors ligne : cache
        }
    }

    /**
     * Mode nuit : luminance inversee (comme INVERT_COLORS de Radtel Tools), mais desaturee et
     * teintee graphite-bleu pour coller au fond « shack » ; un peu de la teinte d'origine est
     * conservee pour distinguer l'eau, les forets et les routes.
     */
    private static BufferedImage invert(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            int r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
            double v = 255 - (0.299 * r + 0.587 * g + 0.114 * b);
            v = v * v / 255.0;                         // contraste : le fond reste sombre
            double cr = 10 + v * 0.66, cg = 14 + v * 0.74, cb = 19 + v * 0.84;
            // 10 % de teinte d'origine (inversee), pour l'eau et la vegetation
            cr += ((255 - r) - v) * 0.10; cg += ((255 - g) - v) * 0.10; cb += ((255 - b) - v) * 0.10;
            if (b > r + 25 && b > g) { cb += 18; cg += 8; } // eau : reflet cyan
            px[i] = (clamp(cr) << 16) | (clamp(cg) << 8) | clamp(cb);
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, w, h, px, 0, w);
        return out;
    }

    private static int clamp(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }

    // ------------------------------------------------------------------ Interface web

    private final Map<String, byte[]> webTiles = Collections.synchronizedMap(new LinkedHashMap<String, byte[]>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, byte[]> e) { return size() > 600; }
    });

    /** Tuile PNG pour la carte web (meme cache disque et meme mode nuit que la carte du PC). */
    byte[] webTile(boolean topo, boolean night, int z, int x, int y) {
        if (z < 0 || z > 19 || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) return null;
        String k = (topo ? "t" : "o") + (night ? "n" : "j") + z + "/" + x + "/" + y;
        byte[] b = webTiles.get(k);
        if (b != null) return b;
        BufferedImage raw = loadTile(topo, z, x, y);
        if (raw == null) return null;
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            ImageIO.write(night ? invert(raw) : raw, "png", bo);
            b = bo.toByteArray();
            webTiles.put(k, b);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Epingles pour la carte web. */
    List<Map<String, Object>> webMarkers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Marker m : snapshot()) {
            List<double[]> tr;
            synchronized (markers) { tr = new ArrayList<>(m.track); }
            String sym = m.kind == KIND_SARSAT ? "!" : m.kind == KIND_SONDE ? "O" : String.valueOf(m.symCode == ' ' ? '•' : m.symCode);
            out.add(Json.obj("key", m.key, "kind", m.kind, "title", m.title, "sub", m.subtitle, "details", m.details,
                    "lat", m.lat, "lon", m.lon, "sym", sym, "time", m.time, "track", tr));
        }
        return out;
    }

    boolean webNight() { return night; }
    boolean webTopo() { return topo; }

    // ------------------------------------------------------------------ Dessin

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = Shack.smooth(g0);
        int w = getWidth(), h = getHeight();
        g.setColor(night ? Shack.WELL : new Color(0xE6E2DA));
        g.fillRect(0, 0, w, h);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        double ox = cx - w / 2.0, oy = cy - h / 2.0;
        int n = 1 << zoom;
        int tx0 = (int) Math.floor(ox / TILE), ty0 = (int) Math.floor(oy / TILE);
        int tx1 = (int) Math.floor((ox + w) / TILE), ty1 = (int) Math.floor((oy + h) / TILE);
        for (int ty = ty0; ty <= ty1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = tx0; tx <= tx1; tx++) {
                int wx = ((tx % n) + n) % n;
                BufferedImage img = tile(zoom, wx, ty);
                int px = (int) Math.round(tx * TILE - ox), py = (int) Math.round(ty * TILE - oy);
                if (img != null) {
                    g.drawImage(img, px, py, TILE, TILE, null);
                } else {
                    // tuile du niveau inferieur agrandie en attendant
                    BufferedImage parent = zoom > 0 ? tiles.get((topo ? "t" : "o") + (night ? "n" : "j") + (zoom - 1) + "/" + (wx / 2) + "/" + (ty / 2)) : null;
                    if (parent != null) {
                        int sx = (wx % 2) * 128, sy = (ty % 2) * 128;
                        g.drawImage(parent, px, py, px + TILE, py + TILE, sx, sy, sx + 128, sy + 128, null);
                    } else {
                        g.setColor(night ? Shack.EDGE : new Color(0xD0CBC0));
                        g.drawRect(px, py, TILE, TILE);
                    }
                }
            }
        }

        Overlay ul = underlay;
        if (ul != null) {
            Graphics2D gu = (Graphics2D) g.create();
            ul.paint(gu, this);
            gu.dispose();
        }

        List<Marker> all = snapshot();
        // traces
        for (Marker m : all) {
            if (m.track.size() < 2) continue;
            Path2D p = new Path2D.Double();
            for (int i = 0; i < m.track.size(); i++) {
                double[] pt = m.track.get(i);
                double x = lonToX(pt[1]) - ox, y = latToY(pt[0]) - oy;
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            g.setColor(Shack.alpha(kindColor(m.kind), m == selected ? 230 : 150));
            g.setStroke(new BasicStroke(m == selected ? 3f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
        }
        // epingles (les plus recentes au-dessus, la selection tout en haut)
        all.sort((a, b) -> a == selected ? 1 : b == selected ? -1 : Long.compare(a.time, b.time));
        for (Marker m : all) {
            double x = lonToX(m.lon) - ox, y = latToY(m.lat) - oy;
            if (x < -60 || y < -80 || x > w + 60 || y > h + 60) continue;
            drawPin(g, (int) Math.round(x), (int) Math.round(y), m, m == selected);
        }

        Overlay ov = overlay;
        if (ov != null) {
            Graphics2D go = (Graphics2D) g.create();
            ov.paint(go, this);
            go.dispose();
        }

        // attribution (obligatoire)
        String attr = topo ? "© OpenStreetMap, SRTM · © OpenTopoMap (CC-BY-SA)" : "© contributeurs OpenStreetMap";
        g.setFont(Shack.body(11f));
        FontMetrics fm = g.getFontMetrics();
        int aw = fm.stringWidth(attr) + 12;
        g.setColor(Shack.alpha(Shack.BG, 200));
        g.fillRoundRect(w - aw - 6, h - 22, aw, 18, 8, 8);
        g.setColor(Shack.TEXT2);
        g.drawString(attr, w - aw, h - 9);
        // echelle de zoom
        g.setFont(Shack.mono(11f));
        String z = "Z" + zoom + "  " + Aprs.fmtPos(yToLat(cy), xToLon(cx));
        int zw = g.getFontMetrics().stringWidth(z) + 14;
        g.setColor(Shack.alpha(Shack.BG, 200));
        g.fillRoundRect(8, h - 24, zw, 18, 8, 8);
        g.setColor(Shack.MUTED);
        g.drawString(z, 15, h - 11);
        g.dispose();
    }

    private void drawPin(Graphics2D g, int x, int y, Marker m, boolean sel) {
        float size = sel ? 40 : 32;
        Color c = kindColor(m.kind);
        AffineTransform at = g.getTransform();
        g.translate(x - size / 2, y - size);
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(Shack.pinPath(size, 3));
        g.setColor(c);
        g.fill(Shack.pinPath(size, 0));
        if (sel) {
            g.setColor(Shack.AMBER);
            g.setStroke(new BasicStroke(2f));
            g.draw(Shack.pinPath(size, 0));
        }
        float r = size * 0.24f, ccx = size / 2, ccy = size * 0.42f;
        g.setColor(Shack.WELL);
        g.fill(new java.awt.geom.Ellipse2D.Float(ccx - r, ccy - r, 2 * r, 2 * r));
        String sym = m.kind == KIND_SARSAT ? "!" : m.kind == KIND_SONDE ? "O" : String.valueOf(m.symCode == ' ' ? '•' : m.symCode);
        g.setFont(Shack.monoBold(size * 0.32f));
        FontMetrics fm = g.getFontMetrics();
        g.setColor(c);
        g.drawString(sym, ccx - fm.stringWidth(sym) / 2f, ccy + fm.getAscent() / 2f - 2);
        g.setTransform(at);

        // etiquette
        g.setFont(Shack.monoBold(11.5f));
        fm = g.getFontMetrics();
        String label = m.title;
        int lw = fm.stringWidth(label) + 10;
        int lx = x - lw / 2, ly = y + 3;
        g.setComposite(AlphaComposite.SrcOver.derive(0.85f));
        g.setColor(Shack.BG);
        g.fillRoundRect(lx, ly, lw, 17, 8, 8);
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(sel ? Shack.AMBER : Shack.TEXT);
        g.drawString(label, lx + 5, ly + 13);
    }
}
