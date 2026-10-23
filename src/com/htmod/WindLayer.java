/*
 * HTMOD Windows : calque « vent » anime de la carte (facon Windy).
 *  - Donnees : vent a 10 m actuel d'Open-Meteo (gratuit, sans cle) sur une grille 12 x 8 couvrant la
 *    vue (+ 20 %), rechargee quand la vue change nettement ou toutes les 30 min.
 *  - Rendu : particules transportees par le champ (interpolation bilineaire), trainees sur un tampon
 *    transparent qui s'estompe a chaque image (~30 images/s), couleur selon la vitesse.
 */
package com.htmod;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.Timer;

final class WindLayer implements MapPanel.Overlay {
    private static final int COLS = 12, ROWS = 8;
    private static final double[] SPEED_STEPS = {5, 10, 20, 30, 45, 65}; // km/h : bornes des couleurs
    private static final Pattern SPEED = Pattern.compile("\"wind_speed_10m\"\\s*:\\s*(-?[0-9.]+)");
    private static final Pattern DIR = Pattern.compile("\"wind_direction_10m\"\\s*:\\s*(-?[0-9.]+)");

    private final MapPanel map;
    private final Consumer<String> log;
    private final Timer anim;
    private final Random rnd = new Random();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile boolean on;
    // grille : coins (deg) et composantes (km/h, x = est, y = nord)
    private volatile Grid grid;
    private volatile boolean fetching;
    private long lastFetch;
    private String status = "";
    private int failures;

    // particules (ecran)
    private float[] px = new float[0], py = new float[0];
    private int[] age = new int[0];
    private BufferedImage trail;
    private double refX = Double.NaN, refY;
    private int refZoom = -1;

    private static final class Grid {
        double lat0, lat1, lon0, lon1;
        float[] u, v;
        int zoom;
        long time;
        double max;
    }

    WindLayer(MapPanel map, Consumer<String> log) {
        this.map = map;
        this.log = log;
        anim = new Timer(33, e -> map.repaint());
        anim.setCoalesce(true);
    }

    boolean isOn() { return on; }

    void setOn(boolean v) {
        on = v;
        trail = null;
        if (v) anim.start(); else anim.stop();
        map.repaint();
    }

    // ------------------------------------------------------------------ Donnees

    private void maybeFetch(double latN, double latS, double lonW, double lonE) {
        Grid g = grid;
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetch < (failures > 0 ? 15_000L * Math.min(failures, 8) : 1_500)) return;
        boolean need = g == null || now - g.time > 30 * 60_000L || g.zoom != map.zoomLevel()
                || latN > g.lat1 || latS < g.lat0 || lonW < g.lon0 || lonE > g.lon1;
        if (!need) return;
        double dLat = (latN - latS) * 0.2, dLon = (lonE - lonW) * 0.2;
        double la1 = Math.min(85, latN + dLat), la0 = Math.max(-85, latS - dLat);
        double lo0 = lonW - dLon, lo1 = lonE + dLon;
        if (lo1 - lo0 > 360) { double c = (lo0 + lo1) / 2; lo0 = c - 180; lo1 = c + 180; }
        fetching = true;
        lastFetch = now;
        final double fla0 = la0, fla1 = la1, flo0 = lo0, flo1 = lo1;
        final int z = map.zoomLevel();
        Thread t = new Thread(() -> fetch(fla0, fla1, flo0, flo1, z), "htmod-vent");
        t.setDaemon(true);
        t.start();
    }

    private void fetch(double la0, double la1, double lo0, double lo1, int zoom) {
        try {
            StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
            for (int r = 0; r < ROWS; r++)
                for (int c = 0; c < COLS; c++) {
                    double la = la0 + (la1 - la0) * r / (ROWS - 1), lo = lo0 + (lo1 - lo0) * c / (COLS - 1);
                    double ln = ((lo + 180) % 360 + 360) % 360 - 180;
                    if (lats.length() > 0) { lats.append(','); lons.append(','); }
                    lats.append(String.format(Locale.ROOT, "%.3f", la));
                    lons.append(String.format(Locale.ROOT, "%.3f", ln));
                }
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lats + "&longitude=" + lons
                    + "&current=wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh&timezone=UTC";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "HTMod (carte du vent)").GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new Exception("HTTP " + resp.statusCode());
            String body = resp.body();
            Matcher ms = SPEED.matcher(body), md = DIR.matcher(body);
            int n = COLS * ROWS;
            float[] u = new float[n], v = new float[n];
            double max = 0;
            for (int i = 0; i < n; i++) {
                if (!ms.find() || !md.find()) throw new Exception("réponse incomplète (" + i + "/" + n + " points)");
                double s = Double.parseDouble(ms.group(1)), d = Math.toRadians(Double.parseDouble(md.group(1)));
                // direction meteo = d'ou vient le vent : il souffle vers d + 180
                u[i] = (float) (-s * Math.sin(d));
                v[i] = (float) (-s * Math.cos(d));
                max = Math.max(max, s);
            }
            Grid g = new Grid();
            g.lat0 = la0; g.lat1 = la1; g.lon0 = lo0; g.lon1 = lo1;
            g.u = u; g.v = v; g.zoom = zoom; g.time = System.currentTimeMillis(); g.max = max;
            grid = g;
            failures = 0;
            status = String.format(Locale.ROOT, "max %.0f km/h", max);
        } catch (Exception e) {
            failures++;
            status = "indisponible";
            if (failures == 1) log.accept("Vent (Open-Meteo) : " + e.getMessage());
        } finally {
            fetching = false;
        }
    }

    /** Vent (km/h) au point : {est, nord}, ou null hors grille. */
    private static float[] sample(Grid g, double lat, double lon) {
        while (lon < g.lon0) lon += 360;
        while (lon > g.lon1 && lon - 360 >= g.lon0) lon -= 360;
        double fx = (lon - g.lon0) / (g.lon1 - g.lon0) * (COLS - 1), fy = (lat - g.lat0) / (g.lat1 - g.lat0) * (ROWS - 1);
        if (fx < 0 || fy < 0 || fx > COLS - 1 || fy > ROWS - 1) return null;
        int x0 = Math.min((int) fx, COLS - 2), y0 = Math.min((int) fy, ROWS - 2);
        double ax = fx - x0, ay = fy - y0;
        int i00 = y0 * COLS + x0, i10 = i00 + 1, i01 = i00 + COLS, i11 = i01 + 1;
        float u = (float) ((g.u[i00] * (1 - ax) + g.u[i10] * ax) * (1 - ay) + (g.u[i01] * (1 - ax) + g.u[i11] * ax) * ay);
        float v = (float) ((g.v[i00] * (1 - ax) + g.v[i10] * ax) * (1 - ay) + (g.v[i01] * (1 - ax) + g.v[i11] * ax) * ay);
        return new float[]{u, v};
    }

    // ------------------------------------------------------------------ Rendu

    @Override
    public void paint(Graphics2D g, MapPanel m) {
        if (!on) return;
        int w = m.getWidth(), h = m.getHeight();
        if (w <= 0 || h <= 0) return;
        double[] nw = m.fromScreen(0, 0), se = m.fromScreen(w, h);
        double lonW = nw[1], lonE = se[1];
        if (lonE < lonW) lonE += 360;
        maybeFetch(nw[0], se[0], lonW, lonE);
        Grid gr = grid;
        if (gr != null) {
            // vue deplacee ou zoomee : on efface les trainees
            Point2D.Double ref = m.toScreen(0, 0);
            if (trail == null || trail.getWidth() != w || trail.getHeight() != h || m.zoomLevel() != refZoom
                    || Math.abs(ref.x - refX) > 0.5 || Math.abs(ref.y - refY) > 0.5) {
                trail = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                refX = ref.x;
                refY = ref.y;
                refZoom = m.zoomLevel();
                seed(w, h, true);
            }
            if (!m.isPaintingForPrint()) step(gr, m, w, h); // image du serveur web : sans faire avancer l'animation
            g.drawImage(trail, 0, 0, null);
        }
        legend(g, m, gr);
    }

    private void seed(int w, int h, boolean all) {
        int n = Math.max(300, Math.min(4000, w * h / 1100));
        if (px.length != n) {
            px = new float[n];
            py = new float[n];
            age = new int[n];
            all = true;
        }
        if (all) for (int i = 0; i < n; i++) respawn(i, w, h, true);
    }

    private void respawn(int i, int w, int h, boolean randomAge) {
        px[i] = rnd.nextFloat() * w;
        py[i] = rnd.nextFloat() * h;
        age[i] = randomAge ? rnd.nextInt(90) : 0;
    }

    private void step(Grid gr, MapPanel m, int w, int h) {
        seed(w, h, false);
        Graphics2D t = trail.createGraphics();
        // estompage des trainees
        t.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_IN, 0.88f));
        t.setColor(Color.BLACK);
        t.fillRect(0, 0, w, h);
        t.setComposite(AlphaComposite.SrcOver);
        t.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        t.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D.Float[] paths = new Path2D.Float[SPEED_STEPS.length + 1];
        for (int k = 0; k < paths.length; k++) paths[k] = new Path2D.Float();
        double scale = 0.075; // pixels par image pour 1 km/h
        for (int i = 0; i < px.length; i++) {
            if (++age[i] > 70 + (i % 40)) { respawn(i, w, h, false); continue; }
            double[] ll = m.fromScreen(px[i], py[i]);
            float[] uv = sample(gr, ll[0], ll[1]);
            if (uv == null) { respawn(i, w, h, false); continue; }
            double s = Math.hypot(uv[0], uv[1]);
            float nx = (float) (px[i] + uv[0] * scale), ny = (float) (py[i] - uv[1] * scale);
            if (nx < 0 || ny < 0 || nx >= w || ny >= h) { respawn(i, w, h, false); continue; }
            int k = 0;
            while (k < SPEED_STEPS.length && s > SPEED_STEPS[k]) k++;
            paths[k].moveTo(px[i], py[i]);
            paths[k].lineTo(nx, ny);
            px[i] = nx;
            py[i] = ny;
        }
        Color[] pal = palette(m.isNight());
        for (int k = 0; k < paths.length; k++) {
            t.setColor(pal[k]);
            t.draw(paths[k]);
        }
        t.dispose();
    }

    private static Color[] palette(boolean night) {
        if (night) return new Color[]{new Color(180, 200, 220, 170), new Color(120, 220, 255, 200), new Color(90, 240, 170, 210),
            new Color(250, 230, 90, 220), new Color(255, 170, 60, 230), new Color(255, 100, 70, 240), new Color(255, 70, 200, 250)};
        return new Color[]{new Color(40, 70, 110, 150), new Color(20, 110, 190, 190), new Color(10, 140, 110, 210),
            new Color(200, 140, 0, 220), new Color(220, 90, 0, 230), new Color(200, 30, 30, 240), new Color(160, 0, 160, 250)};
    }

    private void legend(Graphics2D g, MapPanel m, Grid gr) {
        String s = "VENT 10 m · " + (gr == null ? (fetching ? "chargement…" : status.isEmpty() ? "…" : status) : status)
                + " · Open-Meteo";
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(Shack.mono(11f));
        int tw = g.getFontMetrics().stringWidth(s);
        Color[] pal = palette(true);
        int bw = 12 * pal.length;
        int x = 12, y = 12, w = tw + bw + 30;
        g.setColor(Shack.alpha(Shack.BG, 210));
        g.fillRoundRect(x, y, w, 24, 10, 10);
        g.setColor(Shack.TEXT2);
        g.drawString(s, x + 10, y + 16);
        for (int k = 0; k < pal.length; k++) {
            g.setColor(new Color(pal[k].getRGB() | 0xFF000000));
            g.fillRect(x + tw + 18 + k * 12, y + 9, 12, 6);
        }
    }
}
