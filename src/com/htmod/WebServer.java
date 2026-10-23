/*
 * HTMOD Windows : serveur web du reseau local. Toute l'application dans un navigateur (PC, tablette,
 * telephone) :
 *  - image : la fenetre est dessinee hors ecran (printAll, avec les dialogues et menus ouverts) et
 *    envoyee en flux MJPEG, seulement quand elle change (~8 images/s au plus) ;
 *  - commandes : clics, glisser, molette, clavier du navigateur rejoues comme evenements Swing sur le
 *    composant vise (sans bouger la souris du PC) ;
 *  - son : audio recu du poste diffuse en PCM 16 kHz (lecture Web Audio dans la page) ;
 *  - acces protege par un code (cookie de session), le serveur pouvant faire emettre le poste.
 */
package com.htmod;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.JFrame;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class WebServer {
    private static final String COOKIE = "htmod";

    /** Ce que l'application fournit a la page web. */
    interface Backend {
        Object NOT_HANDLED = new Object();
        /** Etat par sections (fil Swing) ; slow = inclure les sections couteuses (satellites). */
        Map<String, Object> snapshot(boolean slow);
        /** Commande (fil Swing) : null, message d'erreur (String) ou donnees. */
        Object command(String action, Map<String, String> params);
        /** Commande longue hors du fil Swing (reseau...), ou NOT_HANDLED. */
        Object commandOffEdt(String action, Map<String, String> params);
        byte[] tile(boolean topo, boolean night, int z, int x, int y);
        BufferedImage image(String which, String name);
        String upload(String what, Map<String, String> params, byte[] data);
    }

    private final JFrame frame;
    private final Backend backend;
    private final Consumer<String> log;
    private final String pin;
    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final SecureRandom rnd = new SecureRandom();
    private HttpServer server;
    private int port;

    // image partagee
    private final Object frameLock = new Object();
    private byte[] jpeg;
    private long version;
    private long lastHash;
    private volatile int viewers;
    private Thread renderer;

    // son
    private final List<ArrayBlockingQueue<byte[]>> listeners = new CopyOnWriteArrayList<>();

    WebServer(JFrame frame, Backend backend, String pin, Consumer<String> log) {
        this.frame = frame;
        this.backend = backend;
        this.pin = pin;
        this.log = log;
    }

    boolean isRunning() { return server != null; }

    int port() { return port; }

    synchronized void start(int port) throws IOException {
        if (server != null) return;
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 16);
        s.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "htmod-web");
            t.setDaemon(true);
            return t;
        }));
        s.createContext("/", this::handle);
        s.start();
        server = s;
        this.port = port;
        log.accept("Serveur web : " + String.join("  ", urls()) + "  (code " + pin + ")");
    }

    synchronized void stop() {
        if (server == null) return;
        server.stop(0);
        server = null;
        tokens.clear();
        log.accept("Serveur web arrêté");
    }

    /** Adresses http du reseau local. */
    List<String> urls() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String n = ni.getDisplayName().toLowerCase(java.util.Locale.ROOT);
                if (n.contains("virtual") || n.contains("vmware") || n.contains("hyper-v") || n.contains("vethernet")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) out.add("http://" + a.getHostAddress() + ":" + port);
            }
        } catch (Exception ignore) {}
        if (out.isEmpty()) out.add("http://localhost:" + port);
        return out;
    }

    // ------------------------------------------------------------------ Son

    /** Audio recu du poste (32 kHz) : diffuse a 16 kHz aux pages qui ecoutent. */
    void audio(short[] pcm, int len) {
        if (listeners.isEmpty()) return;
        int n = len / 2;
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = (pcm[2 * i] + pcm[2 * i + 1]) / 2;
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        for (ArrayBlockingQueue<byte[]> q : listeners) if (!q.offer(b)) { q.poll(); q.offer(b); }
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/login") && "POST".equals(ex.getRequestMethod())) { login(ex); return; }
            if (!authorized(ex)) {
                if (path.equals("/") || path.equals("/index.html")) send(ex, 200, "text/html; charset=utf-8", page("web-login.html"));
                else send(ex, 401, "text/plain", "code requis".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/tiles/")) { tileReq(ex, path); return; }
            if (path.startsWith("/api/")) { api(ex, path); return; }
            switch (path) {
                case "/": case "/index.html": send(ex, 200, "text/html; charset=utf-8", page("web/index.html")); break;
                case "/app.css": send(ex, 200, "text/css; charset=utf-8", page("web/app.css")); break;
                case "/app.js": send(ex, 200, "text/javascript; charset=utf-8", page("web/app.js")); break;
                case "/map.js": send(ex, 200, "text/javascript; charset=utf-8", page("web/map.js")); break;
                case "/views.js": send(ex, 200, "text/javascript; charset=utf-8", page("web/views.js")); break;
                case "/ecran": send(ex, 200, "text/html; charset=utf-8", page("web-ecran.html")); break;
                case "/stream": stream(ex); break;
                case "/frame.jpg": {
                    byte[] j = renderNow();
                    send(ex, j == null ? 503 : 200, "image/jpeg", j == null ? new byte[0] : j);
                    break;
                }
                case "/input": {
                    Map<String, String> q = form(ex);
                    SwingUtilities.invokeLater(() -> {
                        try { input(q); } catch (RuntimeException e) { /* evenement perdu */ }
                    });
                    send(ex, 204, "text/plain", null);
                    break;
                }
                case "/audio": audioStream(ex); break;
                default: send(ex, 404, "text/plain", "introuvable".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // client parti
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        List<String> cs = ex.getRequestHeaders().get("Cookie");
        if (cs == null) return false;
        for (String c : cs)
            for (String part : c.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=") && tokens.contains(p.substring(COOKIE.length() + 1))) return true;
            }
        return false;
    }

    private void login(HttpExchange ex) throws IOException {
        Map<String, String> f = form(ex);
        if (pin.equals(f.getOrDefault("pin", "").trim())) {
            byte[] b = new byte[16];
            rnd.nextBytes(b);
            StringBuilder t = new StringBuilder();
            for (byte x : b) t.append(String.format("%02x", x));
            tokens.add(t.toString());
            ex.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + t + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=2592000");
            ex.getResponseHeaders().add("Location", "/");
            send(ex, 303, "text/plain", null);
            log.accept("Serveur web : connexion depuis " + ex.getRemoteAddress().getAddress().getHostAddress());
        } else {
            try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
            ex.getResponseHeaders().add("Location", "/?erreur");
            send(ex, 303, "text/plain", null);
        }
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
        String q = ex.getRequestURI().getRawQuery();
        if (q != null && !q.isEmpty()) body = body.isEmpty() ? q : body + "&" + q;
        Map<String, String> m = new HashMap<>();
        for (String kv : body.split("&")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
        }
        return m;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0 && o.size() < 65536) o.write(b, 0, n);
        return o.toByteArray();
    }

    private static void send(HttpExchange ex, int code, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (body == null || body.length == 0) { ex.sendResponseHeaders(code, -1); return; }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(body); }
    }

    private static byte[] page(String name) throws IOException {
        // developpement : HTMOD_WEBDIR = dossier res\com\htmod (pages relues a chaque requete)
        String dev = System.getenv("HTMOD_WEBDIR");
        if (dev != null && !dev.isEmpty()) {
            java.io.File f = new java.io.File(dev, name);
            if (f.isFile()) return java.nio.file.Files.readAllBytes(f.toPath());
        }
        try (InputStream in = WebServer.class.getResourceAsStream(name)) {
            if (in == null) return ("page " + name + " absente").getBytes(StandardCharsets.UTF_8);
            return readAll2(in);
        }
    }

    private static byte[] readAll2(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ Image

    private void stream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=htmodframe");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        viewers++;
        ensureRenderer();
        long seen = -1, sentAt = 0;
        try (OutputStream o = ex.getResponseBody()) {
            while (server != null) {
                byte[] j;
                synchronized (frameLock) {
                    long until = System.currentTimeMillis() + 2000;
                    while (version == seen && System.currentTimeMillis() < until) frameLock.wait(500);
                    j = jpeg;
                    if (version == seen && System.currentTimeMillis() - sentAt < 2000) continue;
                    seen = version;
                }
                if (j == null) continue;
                o.write(("--htmodframe\r\nContent-Type: image/jpeg\r\nContent-Length: " + j.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.write(j);
                o.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                o.flush();
                sentAt = System.currentTimeMillis();
            }
        } catch (InterruptedException ignore) {
        } finally {
            viewers--;
        }
    }

    private synchronized void ensureRenderer() {
        if (renderer != null && renderer.isAlive()) return;
        renderer = new Thread(() -> {
            while (server != null && viewers > 0) {
                long t0 = System.currentTimeMillis();
                renderNow();
                long dt = System.currentTimeMillis() - t0;
                try { Thread.sleep(Math.max(40, 125 - dt)); } catch (InterruptedException e) { return; }
            }
        }, "htmod-web-image");
        renderer.setDaemon(true);
        renderer.start();
    }

    /** Dessine la fenetre (et ses dialogues) ; met a jour le JPEG partage s'il a change. */
    private byte[] renderNow() {
        final BufferedImage[] out = {null};
        try {
            SwingUtilities.invokeAndWait(() -> out[0] = compose());
        } catch (Exception e) {
            return jpeg;
        }
        BufferedImage img = out[0];
        if (img == null) return jpeg;
        long h = hash(img);
        synchronized (frameLock) {
            if (h == lastHash && jpeg != null) return jpeg;
        }
        byte[] j = encode(img);
        synchronized (frameLock) {
            lastHash = h;
            jpeg = j;
            version++;
            frameLock.notifyAll();
        }
        return j;
    }

    private static long hash(BufferedImage img) {
        long h = 1125899906842597L;
        int w = img.getWidth(), ht = img.getHeight();
        for (int y = 0; y < ht; y += 3)
            for (int x = (y / 3) % 4; x < w; x += 4) h = 31 * h + img.getRGB(x, y);
        return h;
    }

    private static byte[] encode(BufferedImage img) {
        try {
            ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream mo = new MemoryCacheImageOutputStream(bo)) {
                w.setOutput(mo);
                ImageWriteParam p = w.getDefaultWriteParam();
                p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                p.setCompressionQuality(0.8f);
                w.write(null, new IIOImage(img, null, null), p);
            }
            w.dispose();
            return bo.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private Component mainRoot() { return frame.getRootPane(); }

    private static Component rootOf(Window w) {
        return w instanceof RootPaneContainer ? ((RootPaneContainer) w).getRootPane() : w;
    }

    private static Point screenOf(Component c) {
        try { return c.isShowing() ? c.getLocationOnScreen() : null; } catch (IllegalComponentStateException e) { return null; }
    }

    /** Fenetres affichees au-dessus de la fenetre principale (dialogues, menus), dans l'ordre. */
    private List<Window> overlays() {
        List<Window> l = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w == frame || !w.isShowing() || w.getWidth() <= 0) continue;
            Window o = w;
            boolean mine = false;
            while (o != null) { if (o == frame) { mine = true; break; } o = o.getOwner(); }
            if (mine || w.getOwner() == null && w instanceof javax.swing.JDialog) l.add(w);
        }
        return l;
    }

    private BufferedImage compose() {
        Component root = mainRoot();
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return null;
        // meme echelle que l'ecran (ex. 125 %) : mise en page et texte identiques, image plus nette
        double sc = 1;
        try { sc = Math.max(1, frame.getGraphicsConfiguration().getDefaultTransform().getScaleX()); } catch (Exception ignore) {}
        scale = sc;
        BufferedImage img = new BufferedImage((int) Math.ceil(w * sc), (int) Math.ceil(h * sc), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.scale(sc, sc);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        root.printAll(g);
        Point o = screenOf(root);
        if (o != null) {
            for (Window win : overlays()) {
                Component r = rootOf(win);
                Point p = screenOf(r);
                if (p == null) continue;
                int x = p.x - o.x, y = p.y - o.y;
                Graphics2D g2 = (Graphics2D) g.create(x, y, r.getWidth(), r.getHeight());
                r.printAll(g2);
                g2.dispose();
                if (win instanceof java.awt.Dialog) {
                    g.setColor(new Color(0xF5A623));
                    g.drawRect(x - 1, y - 1, r.getWidth() + 1, r.getHeight() + 1);
                }
            }
        }
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ Commandes

    private Component pressed, hover, focus;
    private Point pressAt;
    private int pressButton, clickCount;
    private long lastClick;
    private Point lastClickAt;
    private boolean dragged;

    private static final class Hit {
        Component c;
        Point p; // coordonnees dans c
    }

    /** Composant sous le point (coordonnees de l'image), dialogues et menus d'abord. */
    private Hit hit(int x, int y) {
        Component root = mainRoot();
        Point o = screenOf(root);
        if (o != null) {
            List<Window> ws = overlays();
            for (int i = ws.size() - 1; i >= 0; i--) {
                Component r = rootOf(ws.get(i));
                Point p = screenOf(r);
                if (p == null) continue;
                int rx = x + o.x - p.x, ry = y + o.y - p.y;
                if (rx >= 0 && ry >= 0 && rx < r.getWidth() && ry < r.getHeight()) return deepest(r, rx, ry);
            }
        }
        if (x < 0 || y < 0 || x >= root.getWidth() || y >= root.getHeight()) return null;
        return deepest(root, x, y);
    }

    private static Hit deepest(Component root, int x, int y) {
        Component c = root instanceof Container ? SwingUtilities.getDeepestComponentAt(root, x, y) : root;
        if (c == null) c = root;
        Hit h = new Hit();
        h.c = c;
        h.p = SwingUtilities.convertPoint(root, x, y, c);
        return h;
    }

    /** Point de l'image converti dans le repere de c (pour glisser hors du composant). */
    private Point toComponent(int x, int y, Component c) {
        Component root = mainRoot();
        Point o = screenOf(root), pc = screenOf(c);
        if (o != null && pc != null) return new Point(x + o.x - pc.x, y + o.y - pc.y);
        return SwingUtilities.convertPoint(root, x, y, c);
    }

    private static int mods(int button, boolean down) {
        int m = 0;
        if (down) m |= button == 3 ? MouseEvent.BUTTON3_DOWN_MASK : button == 2 ? MouseEvent.BUTTON2_DOWN_MASK : MouseEvent.BUTTON1_DOWN_MASK;
        return m;
    }

    private static int awtButton(int b) { return b == 3 ? MouseEvent.BUTTON3 : b == 2 ? MouseEvent.BUTTON2 : MouseEvent.BUTTON1; }

    private void mouse(Component c, int id, Point p, int mods, int clicks, int button) {
        c.dispatchEvent(new MouseEvent(c, id, System.currentTimeMillis(), mods, p.x, p.y, clicks, button == 3 && id == MouseEvent.MOUSE_PRESSED, button));
    }

    /** Echelle de l'image envoyee (coordonnees image = coordonnees Swing x echelle). */
    private volatile double scale = 1;

    private void input(Map<String, String> q) {
        String t = q.getOrDefault("t", "");
        int x = (int) Math.round(parse(q.get("x")) / scale), y = (int) Math.round(parse(q.get("y")) / scale);
        long now = System.currentTimeMillis();
        switch (t) {
            case "down": {
                Hit h = hit(x, y);
                if (h == null) return;
                pressButton = Math.max(1, parse(q.get("b")));
                boolean again = lastClickAt != null && now - lastClick < 450 && lastClickAt.distance(x, y) < 8;
                clickCount = again ? clickCount + 1 : 1;
                pressed = h.c;
                pressAt = new Point(x, y);
                dragged = false;
                setHover(h);
                mouse(h.c, MouseEvent.MOUSE_PRESSED, h.p, mods(pressButton, true), clickCount, awtButton(pressButton));
                if (h.c.isFocusable()) { focus = h.c; h.c.requestFocusInWindow(); }
                break;
            }
            case "move": {
                if (pressed != null) {
                    if (pressAt.distance(x, y) > 4) dragged = true;
                    mouse(pressed, MouseEvent.MOUSE_DRAGGED, toComponent(x, y, pressed), mods(pressButton, true), 0, MouseEvent.NOBUTTON);
                } else {
                    Hit h = hit(x, y);
                    if (h == null) return;
                    setHover(h);
                    mouse(h.c, MouseEvent.MOUSE_MOVED, h.p, 0, 0, MouseEvent.NOBUTTON);
                }
                break;
            }
            case "up": {
                if (pressed == null) return;
                Component c = pressed;
                Point p = toComponent(x, y, c);
                pressed = null;
                mouse(c, MouseEvent.MOUSE_RELEASED, p, 0, clickCount, awtButton(pressButton));
                if (!dragged && c.contains(p)) {
                    mouse(c, MouseEvent.MOUSE_CLICKED, p, 0, clickCount, awtButton(pressButton));
                    lastClick = now;
                    lastClickAt = new Point(x, y);
                }
                break;
            }
            case "wheel": {
                Hit h = hit(x, y);
                if (h == null) return;
                int r = parse(q.get("d"));
                h.c.dispatchEvent(new MouseWheelEvent(h.c, MouseEvent.MOUSE_WHEEL, now, 0, h.p.x, h.p.y, 0, false,
                        MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, r > 0 ? 1 : -1));
                break;
            }
            case "type": {
                Component f = focusTarget();
                String s = q.getOrDefault("c", "");
                if (f == null) return;
                for (char ch : s.toCharArray())
                    f.dispatchEvent(new KeyEvent(f, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, ch));
                break;
            }
            case "key": {
                Component f = focusTarget();
                if (f == null) return;
                int code = parse(q.get("k")), m = parse(q.get("m"));
                int mods = ((m & 1) != 0 ? KeyEvent.SHIFT_DOWN_MASK : 0) | ((m & 2) != 0 ? KeyEvent.CTRL_DOWN_MASK : 0)
                        | ((m & 4) != 0 ? KeyEvent.ALT_DOWN_MASK : 0);
                char ch = code == KeyEvent.VK_ENTER ? '\n' : code == KeyEvent.VK_BACK_SPACE ? '\b' : code == KeyEvent.VK_TAB ? '\t'
                        : code == KeyEvent.VK_ESCAPE ? (char) 27 : KeyEvent.CHAR_UNDEFINED;
                String c = q.get("c");
                if (c != null && c.length() == 1) ch = c.charAt(0);
                f.dispatchEvent(new KeyEvent(f, KeyEvent.KEY_PRESSED, now, mods, code, ch));
                if (ch != KeyEvent.CHAR_UNDEFINED && (mods & KeyEvent.CTRL_DOWN_MASK) != 0)
                    f.dispatchEvent(new KeyEvent(f, KeyEvent.KEY_TYPED, now, mods, KeyEvent.VK_UNDEFINED, ch));
                f.dispatchEvent(new KeyEvent(f, KeyEvent.KEY_RELEASED, now, mods, code, ch));
                break;
            }
            default: break;
        }
    }

    private Component focusTarget() {
        // le dernier composant clique depuis la page (la fenetre du PC n'a pas forcement le focus Windows)
        if (focus != null && focus.isShowing()) return focus;
        return java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    }

    private void setHover(Hit h) {
        if (h.c == hover) return;
        if (hover != null) {
            Component old = hover;
            old.dispatchEvent(new MouseEvent(old, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false));
        }
        hover = h.c;
        hover.dispatchEvent(new MouseEvent(hover, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, h.p.x, h.p.y, 0, false));
    }

    private static int parse(String s) {
        if (s == null) return 0;
        try { return (int) Math.round(Double.parseDouble(s)); } catch (NumberFormatException e) { return 0; }
    }

    // ------------------------------------------------------------------ API de l'interface web

    private void api(HttpExchange ex, String path) throws IOException {
        switch (path) {
            case "/api/events": events(ex); return;
            case "/api/state": {
                Map<String, Object>[] s = snapshotNow(true);
                send(ex, 200, "application/json; charset=utf-8", s == null ? "{}".getBytes(StandardCharsets.UTF_8)
                        : Json.of(s[0]).getBytes(StandardCharsets.UTF_8));
                return;
            }
            case "/api/cmd": {
                Map<String, String> q = form(ex);
                String a = q.getOrDefault("a", "");
                Object r = backend.commandOffEdt(a, q);
                if (r == Backend.NOT_HANDLED) {
                    Object[] out = {null};
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            try { out[0] = backend.command(a, q); } catch (RuntimeException e) { out[0] = "Erreur : " + e; }
                        });
                    } catch (Exception e) {
                        out[0] = "Erreur : " + e.getMessage();
                    }
                    r = out[0];
                }
                Map<String, Object> res = r instanceof String ? Json.obj("ok", false, "error", r) : Json.obj("ok", true, "data", r);
                send(ex, 200, "application/json; charset=utf-8", Json.of(res).getBytes(StandardCharsets.UTF_8));
                bump();
                return;
            }
            case "/api/img": {
                Map<String, String> q = form(ex);
                BufferedImage img = backend.image(q.getOrDefault("w", "rx"), q.get("name"));
                if (img == null) { send(ex, 404, "text/plain", "aucune image".getBytes(StandardCharsets.UTF_8)); return; }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bo);
                send(ex, 200, "image/png", bo.toByteArray());
                return;
            }
            case "/api/upload": {
                Map<String, String> q = new HashMap<>();
                String raw = ex.getRequestURI().getRawQuery();
                if (raw != null) for (String kv : raw.split("&")) {
                    int i = kv.indexOf('=');
                    if (i > 0) q.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                }
                byte[] data = readLimited(ex.getRequestBody(), 40 << 20);
                String err = backend.upload(q.getOrDefault("what", ""), q, data);
                send(ex, 200, "application/json; charset=utf-8", Json.of(err == null ? Json.obj("ok", true) : Json.obj("ok", false, "error", err))
                        .getBytes(StandardCharsets.UTF_8));
                bump();
                return;
            }
            default: send(ex, 404, "text/plain", "introuvable".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readLimited(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) {
            o.write(b, 0, n);
            if (o.size() > max) throw new IOException("fichier trop gros");
        }
        return o.toByteArray();
    }

    /** /tiles/{o|t}{j|n}/z/x/y.png */
    private void tileReq(HttpExchange ex, String path) throws IOException {
        String[] p = path.substring(7).replace(".png", "").split("/");
        byte[] b = null;
        try {
            if (p.length == 4 && p[0].length() == 2)
                b = backend.tile(p[0].charAt(0) == 't', p[0].charAt(1) == 'n', Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException ignore) {}
        if (b == null) { send(ex, 404, "text/plain", null); return; }
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // --- etat pousse en direct (Server-Sent Events) : seules les sections modifiees sont envoyees

    private final Object stateLock = new Object();
    private final Map<String, String> stateJson = new HashMap<>();
    private final Map<String, Long> stateVer = new HashMap<>();
    private long stateSeq;
    private volatile int sseClients;
    private Thread producer;
    private volatile boolean bumped;

    private void bump() { bumped = true; }

    @SuppressWarnings("unchecked")
    private Map<String, Object>[] snapshotNow(boolean slow) {
        Map<String, Object>[] out = new Map[1];
        try { SwingUtilities.invokeAndWait(() -> out[0] = backend.snapshot(slow)); } catch (Exception e) { return null; }
        return out;
    }

    private synchronized void ensureProducer() {
        if (producer != null && producer.isAlive()) return;
        producer = new Thread(() -> {
            long lastSlow = 0;
            while (server != null && sseClients > 0) {
                long now = System.currentTimeMillis();
                boolean slow = now - lastSlow > 1000;
                if (slow) lastSlow = now;
                Map<String, Object>[] s = snapshotNow(slow);
                if (s != null) {
                    synchronized (stateLock) {
                        boolean changed = false;
                        for (Map.Entry<String, Object> e : s[0].entrySet()) {
                            String j = Json.of(e.getValue());
                            if (!j.equals(stateJson.get(e.getKey()))) {
                                stateJson.put(e.getKey(), j);
                                stateVer.put(e.getKey(), ++stateSeq);
                                changed = true;
                            }
                        }
                        if (changed) stateLock.notifyAll();
                    }
                }
                for (int i = 0; i < 6 && !bumped; i++) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                }
                bumped = false;
            }
        }, "htmod-web-etat");
        producer.setDaemon(true);
        producer.start();
    }

    private void events(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        sseClients++;
        ensureProducer();
        Map<String, Long> seen = new HashMap<>();
        try (OutputStream o = ex.getResponseBody()) {
            long lastWrite = System.currentTimeMillis();
            while (server != null) {
                List<String[]> send = new ArrayList<>();
                synchronized (stateLock) {
                    for (Map.Entry<String, Long> e : stateVer.entrySet()) {
                        Long s = seen.get(e.getKey());
                        if (s == null || s < e.getValue()) { send.add(new String[]{e.getKey(), stateJson.get(e.getKey())}); seen.put(e.getKey(), e.getValue()); }
                    }
                    if (send.isEmpty()) {
                        try { stateLock.wait(5000); } catch (InterruptedException e) { return; }
                    }
                }
                if (!send.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String[] kv : send) sb.append("event: ").append(kv[0]).append("\ndata: ").append(kv[1]).append("\n\n");
                    o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    lastWrite = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - lastWrite > 15000) {
                    o.write(": ok\n\n".getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    lastWrite = System.currentTimeMillis();
                }
            }
        } finally {
            sseClients--;
        }
    }

    // ------------------------------------------------------------------ Son (diffusion)

    private void audioStream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, 0);
        ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<>(64);
        listeners.add(q);
        try (OutputStream o = ex.getResponseBody()) {
            while (server != null) {
                byte[] b = q.poll(1, TimeUnit.SECONDS);
                if (b == null) continue;
                o.write(b);
                o.flush();
            }
        } catch (InterruptedException ignore) {
        } finally {
            listeners.remove(q);
        }
    }
}
