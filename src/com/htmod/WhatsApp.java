/*
 * HTMOD Windows : envoi des messages POCSAG recus vers un groupe WhatsApp, gratuit et sans service
 * tiers : wabridge.exe (bridge\wa, bibliotheque libre whatsmeow) relie le PC au compte WhatsApp de
 * l'utilisateur comme WhatsApp Web (QR code scanne une fois dans WhatsApp > Appareils connectes).
 * Session dans %LOCALAPPDATA%\HTMod\whatsapp (hors de Documents : pas de synchronisation des cles).
 * Reglages (Options > MESSAGES POCSAG -> WHATSAPP) : groupe destinataire, filtre des RIC, details.
 * Envoi en file d'attente sur un fil a part (3 essais, 1 message par seconde au plus), doublons
 * ignores pendant 2 minutes.
 */
package com.htmod;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

final class WhatsApp {
    /** Groupe WhatsApp du compte relie. */
    static final class Group {
        final String id, name;
        Group(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name.isEmpty() ? id : name; }
    }

    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final File exe, dir;
    private final Consumer<String> log;
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(500);
    private final Map<String, Long> recent = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger tags = new AtomicInteger();
    private volatile String lastError = "";
    private volatile int sent;

    // pont
    private Process proc;
    private OutputStream toBridge;
    private volatile boolean linked, connected;
    private volatile String jid = "";
    private volatile Consumer<String> onQr, onLogin;
    private volatile List<Group> groupAcc;
    private volatile CompletableFuture<List<Group>> groupsDone;

    WhatsApp(File exe, File dir, Consumer<String> log) {
        this.exe = exe;
        this.dir = dir;
        this.log = log;
        Thread t = new Thread(this::loop, "htmod-whatsapp");
        t.setDaemon(true);
        t.start();
        if (enabled()) new Thread(() -> { try { ensureBridge(); } catch (IOException e) { lastError = e.getMessage(); } }, "htmod-whatsapp-demarrage").start();
    }

    // ------------------------------------------------------------------ Reglages

    boolean enabled() { return prefs.getBoolean("waOn", false); }
    String chat() { return prefs.get("waChat", ""); }
    String chatName() { return prefs.get("waChatName", ""); }
    String rics() { return prefs.get("waRics", ""); }
    boolean details() { return prefs.getBoolean("waDetails", true); }
    boolean linked() { return linked; }
    boolean connected() { return connected; }
    String phone() { return jid; }
    boolean complete() { return !chat().isEmpty() && linked; }
    boolean hasError() { return !lastError.isEmpty(); }

    void save(boolean on, String chat, String chatName, String rics, boolean details) {
        prefs.putBoolean("waOn", on);
        prefs.put("waChat", chat.trim());
        prefs.put("waChatName", chatName.trim());
        prefs.put("waRics", rics.trim());
        prefs.putBoolean("waDetails", details);
        if (on) new Thread(() -> { try { ensureBridge(); } catch (IOException e) { lastError = e.getMessage(); } }, "htmod-whatsapp-demarrage").start();
    }

    /** Etat affiche dans les options. */
    String status() {
        if (!enabled()) return linked ? Tr.t("WhatsApp : PC relié, envoi désactivé (MESSAGES POCSAG → WHATSAPP)") : Tr.t("WhatsApp : désactivé");
        if (!linked) return Tr.t("WhatsApp : PC non relié (QR code à scanner)");
        if (chat().isEmpty()) return Tr.t("WhatsApp : aucun groupe choisi");
        String g = chatName().isEmpty() ? chat() : chatName();
        return Tr.t("WhatsApp → ") + g + (connected ? "" : Tr.t(" (hors ligne)")) + (sent > 0 ? " · " + sent + Tr.t(" envoyé(s)") : "")
                + (lastError.isEmpty() ? "" : Tr.t(" · erreur : ") + lastError);
    }

    /** RIC acceptes (vide = tous) : liste separee par des virgules ou des espaces, plages « 25800-25899 ». */
    static boolean ricAccepted(String filter, int ric) {
        if (filter == null || filter.trim().isEmpty()) return true;
        for (String p : filter.split("[,;\\s]+")) {
            if (p.isEmpty()) continue;
            try {
                int dash = p.indexOf('-');
                if (dash > 0) {
                    int a = Integer.parseInt(p.substring(0, dash)), b = Integer.parseInt(p.substring(dash + 1));
                    if (ric >= Math.min(a, b) && ric <= Math.max(a, b)) return true;
                } else if (Integer.parseInt(p) == ric) return true;
            } catch (NumberFormatException ignore) {
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Pont wabridge.exe

    private synchronized void ensureBridge() throws IOException {
        if (proc != null && proc.isAlive()) return;
        if (!exe.isFile()) throw new IOException(Tr.t("wabridge.exe introuvable (") + exe.getAbsolutePath() + ")");
        dir.mkdirs();
        proc = new ProcessBuilder(exe.getAbsolutePath(), dir.getAbsolutePath()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        toBridge = proc.getOutputStream();
        final Process p = proc;
        Thread rd = new Thread(() -> readLoop(p), "htmod-whatsapp-pont");
        rd.setDaemon(true);
        rd.start();
    }

    private synchronized void command(String json) throws IOException {
        ensureBridge();
        toBridge.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        toBridge.flush();
    }

    private void readLoop(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                List<Map<String, String>> os = SatCatalog.Json.objects(line);
                if (!os.isEmpty()) onEvent(os.get(0));
            }
        } catch (IOException ignore) {
        }
        linked = false;
        connected = false;
        for (CompletableFuture<String> f : pending.values()) f.complete(Tr.t("pont WhatsApp arrêté"));
        pending.clear();
    }

    private void onEvent(Map<String, String> e) {
        String ev = e.getOrDefault("ev", "");
        switch (ev) {
            case "state":
                boolean wasLinked = linked;
                linked = "1".equals(e.get("linked"));
                connected = "1".equals(e.get("connected"));
                jid = e.getOrDefault("jid", "");
                if (linked && !wasLinked) log.accept(Tr.t("WhatsApp : PC relié au compte +") + jid);
                break;
            case "qr": { Consumer<String> c = onQr; if (c != null) c.accept(e.getOrDefault("png", "")); break; }
            case "login": { Consumer<String> c = onLogin; if (c != null) c.accept(e.getOrDefault("result", "")); break; }
            case "loggedOut": linked = false; connected = false; log.accept(Tr.t("WhatsApp : PC délié du compte")); break;
            case "group": { List<Group> acc = groupAcc; if (acc != null) acc.add(new Group(e.getOrDefault("id", ""), e.getOrDefault("name", ""))); break; }
            case "groupsEnd": { CompletableFuture<List<Group>> f = groupsDone; if (f != null) f.complete(groupAcc); break; }
            case "sent": { CompletableFuture<String> f = pending.remove(e.getOrDefault("tag", "")); if (f != null) f.complete(null); break; }
            case "error": {
                String tag = e.getOrDefault("tag", ""), msg = e.getOrDefault("msg", "?");
                CompletableFuture<String> f = pending.remove(tag);
                if (f != null) f.complete(msg);
                else if ("groups".equals(tag) && groupsDone != null) groupsDone.completeExceptionally(new Exception(msg));
                else log.accept(Tr.t("WhatsApp : ") + msg);
                break;
            }
            default: break;
        }
    }

    /** Liaison du PC : onQr recoit le chemin de chaque QR code (PNG), onResult « success », « timeout »... */
    void login(Consumer<String> onQr, Consumer<String> onResult) throws IOException {
        this.onQr = onQr;
        this.onLogin = onResult;
        command("{\"cmd\":\"login\"}");
    }

    void logout() throws IOException { command("{\"cmd\":\"logout\"}"); }

    /** Groupes du compte relie (a appeler hors du fil Swing). */
    List<Group> groups() throws Exception {
        groupAcc = new ArrayList<>();
        groupsDone = new CompletableFuture<>();
        command("{\"cmd\":\"groups\"}");
        List<Group> out = groupsDone.get(40, TimeUnit.SECONDS);
        out.sort((x, y) -> x.toString().compareToIgnoreCase(y.toString()));
        return out;
    }

    /** Envoi immediat (a appeler hors du fil Swing) ; renvoie un message d'erreur ou null. */
    String sendNow(String chatId, String text) {
        if (chatId == null || chatId.isEmpty()) return Tr.t("aucun groupe choisi");
        String tag = "m" + tags.incrementAndGet();
        CompletableFuture<String> f = new CompletableFuture<>();
        pending.put(tag, f);
        try {
            command("{\"cmd\":\"send\",\"to\":" + quote(chatId) + ",\"text\":" + quote(text) + ",\"tag\":" + quote(tag) + "}");
            return f.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            pending.remove(tag);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    // ------------------------------------------------------------------ Messages POCSAG

    /** Message POCSAG recu : mis en file d'attente si l'envoi est actif et le RIC accepte. */
    void pocsag(Pocsag.Message m, String type, String body, String time) {
        if (!enabled() || chat().isEmpty() || !ricAccepted(rics(), m.address)) return;
        String text = details()
                ? "📟 POCSAG " + time + "\nRIC " + m.address + " · F" + m.function + " · " + m.baud + " bd" + (m.afsk ? " AFSK" : "") + " · " + type + "\n" + body
                : body;
        long now = System.currentTimeMillis();
        String key = m.address + "|" + body;
        synchronized (recent) {
            Iterator<Map.Entry<String, Long>> it = recent.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue() > 120_000) it.remove();
            if (recent.containsKey(key)) return; // meme message repete par un autre emetteur
            recent.put(key, now);
        }
        if (!queue.offer(text)) log.accept(Tr.t("WhatsApp : file d'attente pleine, message ignoré"));
    }

    private void loop() {
        while (true) {
            String text;
            try { text = queue.take(); } catch (InterruptedException e) { return; }
            String err = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                err = sendNow(chat(), text);
                if (err == null) break;
                try { Thread.sleep(5000L * (attempt + 1)); } catch (InterruptedException e) { return; }
            }
            if (err == null) { sent++; lastError = ""; }
            else { lastError = err; log.accept(Tr.t("WhatsApp : envoi impossible (") + err + ")"); }
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; } // pas plus d'un message par seconde
        }
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"').toString();
    }
}
