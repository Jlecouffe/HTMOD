/*
 * HTMOD Windows : catalogue des satellites radioamateur.
 *  1. Serveur Benshi (celui de l'appli HT) : gRPC /satellite.Satellite/GetGP sur rpc.benshikj.com:800
 *     (sans compte) -> TLE + configurations radio (montee / descente, sous-tons, modulation, nom) ;
 *  2. base FM/SSTV issue de SatNOGS (htmod_sats.json, reprise du mod Android) : satellites
 *     supplementaires et indicateur SSTV ;
 *  3. CelesTrak (groupe « amateur ») pour rafraichir les TLE des satellites hors serveur Benshi
 *     (jamais plus d'une fois toutes les 2 h, regle d'usage de CelesTrak) ;
 *  4. satellites ajoutes par l'utilisateur (TLE colles ou recuperes par n° NORAD).
 * Filtre SSTV : configuration radio nommee « SSTV », indicateur SatNOGS, ou choix de l'utilisateur.
 * Cache dans Documents\HTMod\satellites\.
 */
package com.htmod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

final class SatCatalog {
    static final String SRC_BENSHI = "Benshi", SRC_SATNOGS = "SatNOGS", SRC_USER = "Perso";

    /** Configuration radio d'un satellite (frequences en Hz, sous-tons en 0,01 Hz, 0 = aucun). */
    static final class Rf {
        String name = "";
        long upHz, downHz;
        int upTone, downTone;
        boolean upAm, downAm;
        /** Frequences d'origine {descente, montee, CTCSS} si l'utilisateur les a modifiees, sinon null. */
        long[] orig;

        String label() {
            if (downHz <= 0) return (name.isEmpty() ? "FM" : name) + " · fréquence à régler (MODIFIER)";
            return (name.isEmpty() ? "FM" : name) + " · ↓ " + mhz(downHz) + (upHz > 0 ? "  ↑ " + mhz(upHz) : "")
                    + (upTone > 0 ? String.format(Locale.ROOT, "  CTCSS %.1f", upTone / 100.0) : "") + (orig != null ? "  (modifié)" : "");
        }
    }

    static final class Sat {
        String name;
        int norad;
        String tle1, tle2;
        final List<Rf> rf = new ArrayList<>();
        String source;
        boolean sstvAuto;
        private Sgp4 prop;

        Sgp4 prop() {
            if (prop == null) prop = Sgp4.fromTle(tle1, tle2);
            return prop;
        }

        void setTle(String l1, String l2) {
            tle1 = l1;
            tle2 = l2;
            prop = null;
        }
    }

    interface Listener { void onCatalog(String message); }

    private final File dir;
    private final Preferences prefs = Preferences.userRoot().node("htmod");
    private final Map<Integer, Sat> sats = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Set<Integer> sstvOn = new HashSet<>(), sstvOff = new HashSet<>();
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    SatCatalog(File dir) {
        this.dir = dir;
        dir.mkdirs();
        for (String s : prefs.get("sstvOn", "").split(",")) if (!s.isEmpty()) sstvOn.add(Integer.parseInt(s));
        for (String s : prefs.get("sstvOff", "").split(",")) if (!s.isEmpty()) sstvOff.add(Integer.parseInt(s));
    }

    static String mhz(long hz) { return String.format(Locale.ROOT, "%.4f", hz / 1e6); }

    List<Sat> all() {
        synchronized (sats) {
            List<Sat> l = new ArrayList<>(sats.values());
            l.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
            return l;
        }
    }

    Sat get(int norad) { return sats.get(norad); }

    boolean isSstv(Sat s) {
        if (sstvOff.contains(s.norad)) return false;
        return sstvOn.contains(s.norad) || s.sstvAuto;
    }

    void setSstv(Sat s, boolean on) {
        sstvOn.remove(s.norad);
        sstvOff.remove(s.norad);
        if (on != s.sstvAuto) (on ? sstvOn : sstvOff).add(s.norad);
        prefs.put("sstvOn", join(sstvOn));
        prefs.put("sstvOff", join(sstvOff));
    }

    private static String join(Set<Integer> s) {
        StringBuilder b = new StringBuilder();
        for (int v : s) b.append(b.length() == 0 ? "" : ",").append(v);
        return b.toString();
    }

    // ------------------------------------------------------------------ Chargement

    /** Charge les caches et la base integree (rapide, sans reseau). */
    void loadLocal() {
        sats.clear();
        loadSatnogs();
        byte[] benshi = read(new File(dir, "benshi.grpc"));
        if (benshi != null) mergeBenshi(benshi);
        String celes = readText(new File(dir, "celestrak-amateur.tle"));
        if (celes != null) mergeTles(celes, false);
        loadUser();
        fixIss();
    }

    /**
     * ISS (NORAD 25544) : frequences fixees ici, quoi que donne le serveur Benshi :
     * FM (relais) montee 145,990 MHz CTCSS 67 Hz / descente 437,800 MHz ; APRS 437,825 MHz (emission et
     * reception, quand le relais n'emet pas) ; CW sans frequence (a regler avec MODIFIER) ; SSTV 437,550 MHz
     * (reception, Robot 36, evenements ARISS). Puis les frequences modifiees par l'utilisateur.
     */
    private void fixIss() {
        Sat s = sats.get(25544);
        if (s != null && !SRC_USER.equals(s.source)) {
            String fm = "ISS-FM", aprs = "ISS APRS", sstv = "ISS SSTV";
            List<Rf> others = new ArrayList<>();
            for (Rf r : s.rf) {
                String n = r.name.toUpperCase(Locale.ROOT);
                if (n.contains("APRS")) aprs = r.name;
                else if (n.contains("SSTV")) sstv = r.name;
                else if (n.contains("FM")) fm = r.name;
                else if (!n.contains("CW")) others.add(r);
            }
            s.rf.clear();
            s.rf.add(issRf(fm, 437_800_000L, 145_990_000L, 6700));
            s.rf.add(issRf(aprs, 437_825_000L, 437_825_000L, 0));
            s.rf.add(issRf("ISS CW", 0, 0, 0));
            s.rf.add(issRf(sstv, 437_550_000L, 0, 0));
            s.rf.addAll(others);
            s.sstvAuto = true;
        }
        applyEdits();
    }

    private static Rf issRf(String name, long downHz, long upHz, int upTone) {
        Rf r = new Rf();
        r.name = name;
        r.downHz = downHz;
        r.upHz = upHz;
        r.upTone = upTone;
        return r;
    }

    // ------------------------------------------------------------------ Frequences modifiees par l'utilisateur

    /** « norad|nom de la configuration » -> {descente Hz, montee Hz, CTCSS en 0,01 Hz} (frequences-perso.txt). */
    private final Map<String, long[]> edits = Collections.synchronizedMap(new LinkedHashMap<>());
    private boolean editsLoaded;

    private File editsFile() { return new File(dir, "frequences-perso.txt"); }

    private static String editKey(Sat s, Rf r) { return s.norad + "|" + r.name; }

    private void applyEdits() {
        if (!editsLoaded) {
            editsLoaded = true;
            String t = readText(editsFile());
            if (t != null) for (String line : t.replace("\r", "").split("\n")) {
                String[] f = line.split(";", -1);
                if (f.length < 5) continue;
                try {
                    edits.put(f[0] + "|" + f[1], new long[]{Math.round(Double.parseDouble(f[2]) * 1e6),
                        f[3].isEmpty() ? 0 : Math.round(Double.parseDouble(f[3]) * 1e6), f[4].isEmpty() ? 0 : Math.round(Double.parseDouble(f[4]) * 100)});
                } catch (NumberFormatException ignore) {
                }
            }
        }
        synchronized (sats) {
            for (Sat s : sats.values()) for (Rf r : s.rf) {
                long[] e = edits.get(editKey(s, r));
                if (e != null) apply(r, e);
            }
        }
    }

    private static void apply(Rf r, long[] e) {
        if (r.orig == null) r.orig = new long[]{r.downHz, r.upHz, r.upTone};
        r.downHz = e[0];
        r.upHz = e[1];
        r.upTone = (int) e[2];
    }

    /** Modifie les frequences d'une configuration (descente, montee 0 = aucune, CTCSS en 0,01 Hz) et l'enregistre. */
    void editRf(Sat s, Rf r, long downHz, long upHz, int upTone) throws IOException {
        long[] e = {downHz, upHz, upTone};
        edits.put(editKey(s, r), e);
        apply(r, e);
        saveEdits();
    }

    /** Revient aux frequences d'origine (serveur, base integree) d'une configuration. */
    void resetRf(Sat s, Rf r) throws IOException {
        edits.remove(editKey(s, r));
        if (r.orig != null) {
            r.downHz = r.orig[0];
            r.upHz = r.orig[1];
            r.upTone = (int) r.orig[2];
            r.orig = null;
        }
        saveEdits();
    }

    private void saveEdits() throws IOException {
        StringBuilder sb = new StringBuilder();
        synchronized (edits) {
            for (Map.Entry<String, long[]> e : edits.entrySet()) {
                int bar = e.getKey().indexOf('|');
                long[] v = e.getValue();
                sb.append(e.getKey(), 0, bar).append(';').append(e.getKey().substring(bar + 1)).append(';').append(mhz(v[0])).append(';')
                        .append(v[1] > 0 ? mhz(v[1]) : "").append(';').append(v[2] > 0 ? String.format(Locale.ROOT, "%.1f", v[2] / 100.0) : "").append('\n');
            }
        }
        Files.write(editsFile().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Met a jour depuis le reseau (Benshi toujours, CelesTrak si le cache a plus de 2 h). */
    String refresh() {
        StringBuilder msg = new StringBuilder();
        try {
            byte[] b = fetchBenshi();
            Files.write(new File(dir, "benshi.grpc").toPath(), b);
            int n = mergeBenshi(b);
            msg.append("Serveur Benshi : ").append(n).append(" satellite(s)");
        } catch (Exception e) {
            msg.append("Serveur Benshi injoignable (").append(e.getMessage()).append(")");
        }
        File cf = new File(dir, "celestrak-amateur.tle");
        if (!cf.isFile() || System.currentTimeMillis() - cf.lastModified() > 2 * 3600_000L) {
            try {
                String t = get("https://celestrak.org/NORAD/elements/gp.php?GROUP=amateur&FORMAT=TLE");
                if (t.contains("\n1 ")) {
                    Files.write(cf.toPath(), t.getBytes(StandardCharsets.UTF_8));
                    msg.append(" · CelesTrak : ").append(mergeTles(t, false)).append(" TLE");
                }
            } catch (Exception e) {
                msg.append(" · CelesTrak injoignable");
            }
        }
        loadUser(); // les satellites perso gardent leurs propres TLE
        fixIss();
        return msg.toString();
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "HTMod/1.1 (radioamateur)")
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    // ------------------------------------------------------------------ Serveur Benshi (gRPC)

    /** Appel gRPC unaire GetGP (syncAt = 0 : liste complete) ; renvoie le message protobuf de reponse. */
    byte[] fetchBenshi() throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create("https://rpc.benshikj.com:800/satellite.Satellite/GetGP"))
                .header("content-type", "application/grpc").header("te", "trailers")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{0, 0, 0, 0, 0})) // message vide
                .timeout(Duration.ofSeconds(30)).build();
        HttpResponse<byte[]> resp = http.send(r, HttpResponse.BodyHandlers.ofByteArray());
        byte[] b = resp.body();
        if (resp.statusCode() != 200 || b.length < 5) throw new IOException("HTTP " + resp.statusCode());
        if (b[0] != 0) throw new IOException("reponse compressee non geree");
        int len = ((b[1] & 0xFF) << 24) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 8) | (b[4] & 0xFF);
        if (len < 0 || 5 + len > b.length) throw new IOException("reponse tronquee");
        byte[] msg = new byte[len];
        System.arraycopy(b, 5, msg, 0, len);
        return msg;
    }

    /** GetGPResult { repeated GP gp = 1; int64 time = 2; } */
    int mergeBenshi(byte[] msg) {
        int n = 0;
        Proto p = new Proto(msg, 0, msg.length);
        while (p.more()) {
            int tag = p.tag();
            if ((tag >>> 3) == 1 && (tag & 7) == 2) {
                Proto gp = p.sub();
                if (mergeGp(gp)) n++;
            } else p.skip(tag);
        }
        return n;
    }

    /** GP { Name 1; NoradCatID 2; TLELine1 4; TLELine2 5; repeated RfInfo rf 6; UpdatedAt 8 } */
    private boolean mergeGp(Proto p) {
        String name = "", l1 = null, l2 = null;
        int norad = 0;
        List<Rf> rfs = new ArrayList<>();
        while (p.more()) {
            int tag = p.tag(), f = tag >>> 3;
            if (f == 1 && (tag & 7) == 2) name = p.string();
            else if (f == 2 && (tag & 7) == 0) norad = (int) p.varint();
            else if (f == 4 && (tag & 7) == 2) l1 = p.string();
            else if (f == 5 && (tag & 7) == 2) l2 = p.string();
            else if (f == 6 && (tag & 7) == 2) rfs.add(rf(p.sub()));
            else p.skip(tag);
        }
        if (norad == 0 || l1 == null || l2 == null) return false;
        try { Sgp4.fromTle(l1, l2); } catch (IllegalArgumentException e) { return false; }
        Sat s = sats.get(norad);
        if (s == null) { s = new Sat(); s.norad = norad; sats.put(norad, s); }
        if (!SRC_USER.equals(s.source)) {
            s.name = name.isEmpty() ? "NORAD " + norad : name;
            s.source = SRC_BENSHI;
            s.setTle(l1, l2);
        }
        if (!rfs.isEmpty()) {
            s.rf.clear();
            s.rf.addAll(rfs);
        }
        for (Rf r : rfs) if (r.name.toUpperCase(Locale.ROOT).contains("SSTV")) s.sstvAuto = true;
        return true;
    }

    /** RfInfo { UplinkFreq 1; DownlinkFreq 2; UplinkSubAudio 3; DownlinkSubAudio 4; UplinkMod 5; DownlinkMod 6; Bandwidth 7; Name 9 } */
    private static Rf rf(Proto p) {
        Rf r = new Rf();
        while (p.more()) {
            int tag = p.tag(), f = tag >>> 3;
            if ((tag & 7) == 0) {
                long v = p.varint();
                if (f == 1) r.upHz = v;
                else if (f == 2) r.downHz = v;
                else if (f == 3) r.upTone = (int) v;
                else if (f == 4) r.downTone = (int) v;
                else if (f == 5) r.upAm = v == 1;
                else if (f == 6) r.downAm = v == 1;
            } else if (f == 9 && (tag & 7) == 2) r.name = p.string();
            else p.skip(tag);
        }
        return r;
    }

    /** Lecteur protobuf minimal (varint, chaines, sous-messages). */
    private static final class Proto {
        private final byte[] b;
        private int pos;
        private final int end;

        Proto(byte[] b, int off, int end) { this.b = b; this.pos = off; this.end = end; }

        boolean more() { return pos < end; }

        long varint() {
            long v = 0;
            int shift = 0;
            while (pos < end) {
                int x = b[pos++] & 0xFF;
                v |= (long) (x & 0x7F) << shift;
                if ((x & 0x80) == 0) break;
                shift += 7;
            }
            return v;
        }

        int tag() { return (int) varint(); }

        Proto sub() {
            int len = (int) varint();
            Proto s = new Proto(b, pos, Math.min(end, pos + len));
            pos += len;
            return s;
        }

        String string() {
            int len = (int) varint();
            String s = new String(b, pos, Math.min(len, end - pos), StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        void skip(int tag) {
            switch (tag & 7) {
                case 0: varint(); break;
                case 1: pos += 8; break;
                case 2: pos += (int) varint(); break;
                case 5: pos += 4; break;
                default: pos = end;
            }
        }
    }

    // ------------------------------------------------------------------ SatNOGS (integre) et TLE

    private void loadSatnogs() {
        try (InputStream in = SatCatalog.class.getResourceAsStream("htmod_sats.json")) {
            if (in == null) return;
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (Map<String, String> o : Json.objects(json)) {
                try {
                    int norad = Integer.parseInt(o.get("norad"));
                    Sat s = sats.get(norad);
                    if (s == null) {
                        s = new Sat();
                        s.norad = norad;
                        s.name = o.getOrDefault("name", "NORAD " + norad);
                        s.source = SRC_SATNOGS;
                        s.setTle(o.get("tle1"), o.get("tle2"));
                        Sgp4.fromTle(s.tle1, s.tle2);
                        sats.put(norad, s);
                    }
                    long rx = Long.parseLong(o.getOrDefault("rx", "0")), tx = Long.parseLong(o.getOrDefault("tx", "0"));
                    if (rx > 0 && s.rf.isEmpty()) {
                        Rf r = new Rf();
                        r.name = "true".equals(o.get("sstv")) ? "SSTV" : "FM";
                        r.downHz = rx;
                        r.upHz = tx;
                        int ct = (int) Math.round(Double.parseDouble(o.getOrDefault("ctcss", "0")) * 100);
                        r.upTone = ct;
                        s.rf.add(r);
                    }
                    if ("true".equals(o.get("sstv"))) s.sstvAuto = true;
                } catch (RuntimeException ignore) {
                }
            }
        } catch (IOException ignore) {
        }
    }

    /** Remplace les TLE des satellites connus (et ajoute les autres si addUnknown) ; renvoie le nombre de TLE lus. */
    int mergeTles(String text, boolean addUnknown) {
        String[] lines = text.replace("\r", "").split("\n");
        int n = 0;
        for (int i = 0; i + 1 < lines.length; i++) {
            String a = lines[i].trim(), b = lines[i + 1].trim();
            if (!a.startsWith("1 ") || !b.startsWith("2 ")) continue;
            String name = i > 0 && !lines[i - 1].trim().startsWith("2 ") ? lines[i - 1].trim() : "";
            i++;
            int norad;
            try {
                norad = Integer.parseInt(a.substring(2, 7).trim());
                Sgp4.fromTle(a, b);
            } catch (RuntimeException e) {
                continue;
            }
            n++;
            Sat s = sats.get(norad);
            if (s == null) {
                if (!addUnknown) continue;
                s = new Sat();
                s.norad = norad;
                s.name = name.isEmpty() ? "NORAD " + norad : name;
                s.source = SRC_USER;
                sats.put(norad, s);
                s.setTle(a, b);
            } else if (!SRC_BENSHI.equals(s.source) && !SRC_USER.equals(s.source)) {
                s.setTle(a, b); // TLE plus frais que la base integree
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ Satellites de l'utilisateur

    private File userFile() { return new File(dir, "satellites-perso.txt"); }

    private void loadUser() {
        String t = readText(userFile());
        if (t == null) return;
        for (String block : t.replace("\r", "").split("\n\\s*\n")) {
            Map<String, String> kv = new HashMap<>();
            List<Rf> rfs = new ArrayList<>();
            for (String line : block.split("\n")) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim(), v = line.substring(eq + 1).trim();
                if (k.equals("RF")) {
                    String[] f = v.split(";", -1);
                    Rf r = new Rf();
                    r.name = f[0];
                    r.downHz = f.length > 1 && !f[1].isEmpty() ? Math.round(Double.parseDouble(f[1]) * 1e6) : 0;
                    r.upHz = f.length > 2 && !f[2].isEmpty() ? Math.round(Double.parseDouble(f[2]) * 1e6) : 0;
                    r.upTone = f.length > 3 && !f[3].isEmpty() ? (int) Math.round(Double.parseDouble(f[3]) * 100) : 0;
                    rfs.add(r);
                } else kv.put(k, v);
            }
            try {
                int norad = Integer.parseInt(kv.get("NORAD"));
                Sgp4.fromTle(kv.get("TLE1"), kv.get("TLE2"));
                Sat s = new Sat();
                s.norad = norad;
                s.name = kv.getOrDefault("NAME", "NORAD " + norad);
                s.source = SRC_USER;
                s.setTle(kv.get("TLE1"), kv.get("TLE2"));
                s.rf.addAll(rfs);
                Sat old = sats.get(norad);
                if (rfs.isEmpty() && old != null) s.rf.addAll(old.rf);
                s.sstvAuto = old != null && old.sstvAuto;
                sats.put(norad, s);
            } catch (RuntimeException ignore) {
            }
        }
    }

    /** Ajoute (ou remplace) un satellite perso et l'enregistre. */
    void addUser(String name, String l1, String l2, Rf rf, boolean sstv) throws IOException {
        Sgp4 p = Sgp4.fromTle(l1, l2);
        StringBuilder sb = new StringBuilder();
        String old = readText(userFile());
        if (old != null) {
            for (String block : old.replace("\r", "").split("\n\\s*\n")) {
                if (block.trim().isEmpty() || block.contains("NORAD=" + p.satnum + "\n") || block.trim().endsWith("NORAD=" + p.satnum)) continue;
                sb.append(block.trim()).append("\n\n");
            }
        }
        sb.append("NAME=").append(name).append("\nNORAD=").append(p.satnum).append("\nTLE1=").append(l1).append("\nTLE2=").append(l2).append('\n');
        if (rf != null && rf.downHz > 0)
            sb.append("RF=").append(rf.name).append(';').append(mhz(rf.downHz)).append(';').append(rf.upHz > 0 ? mhz(rf.upHz) : "")
                    .append(';').append(rf.upTone > 0 ? String.format(Locale.ROOT, "%.1f", rf.upTone / 100.0) : "").append('\n');
        Files.write(userFile().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        loadUser();
        Sat s = sats.get(p.satnum);
        if (s != null) setSstv(s, sstv);
    }

    /** Supprime un satellite perso (les satellites Benshi / SatNOGS reviennent au prochain chargement). */
    void removeUser(int norad) throws IOException {
        String old = readText(userFile());
        if (old == null) return;
        StringBuilder sb = new StringBuilder();
        for (String block : old.replace("\r", "").split("\n\\s*\n")) {
            if (block.trim().isEmpty()) continue;
            boolean match = false;
            for (String line : block.split("\n")) if (line.trim().equals("NORAD=" + norad)) match = true;
            if (!match) sb.append(block.trim()).append("\n\n");
        }
        Files.write(userFile().toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        loadLocal();
    }

    /** TLE d'un satellite par son n° NORAD (CelesTrak) : {nom, ligne 1, ligne 2}. */
    String[] fetchTleByNorad(int norad) throws IOException, InterruptedException {
        String t = get("https://celestrak.org/NORAD/elements/gp.php?CATNR=" + norad + "&FORMAT=TLE");
        String[] l = t.replace("\r", "").trim().split("\n");
        for (int i = 0; i + 1 < l.length; i++)
            if (l[i].startsWith("1 ") && l[i + 1].startsWith("2 "))
                return new String[]{i > 0 ? l[i - 1].trim() : "NORAD " + norad, l[i].trim(), l[i + 1].trim()};
        throw new IOException("aucun TLE pour NORAD " + norad);
    }

    private static byte[] read(File f) {
        try { return f.isFile() ? Files.readAllBytes(f.toPath()) : null; } catch (IOException e) { return null; }
    }

    private static String readText(File f) {
        byte[] b = read(f);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** Lecteur JSON minimal : tableau d'objets plats (valeurs texte, nombres, booleens). */
    static final class Json {
        static List<Map<String, String>> objects(String s) {
            List<Map<String, String>> out = new ArrayList<>();
            int i = 0, n = s.length();
            while (i < n) {
                int o = s.indexOf('{', i);
                if (o < 0) break;
                Map<String, String> m = new HashMap<>();
                i = o + 1;
                while (i < n && s.charAt(i) != '}') {
                    int k1 = s.indexOf('"', i);
                    if (k1 < 0) break;
                    int k2 = s.indexOf('"', k1 + 1);
                    String key = s.substring(k1 + 1, k2);
                    i = s.indexOf(':', k2) + 1;
                    while (i < n && Character.isWhitespace(s.charAt(i))) i++;
                    String val;
                    if (s.charAt(i) == '"') {
                        StringBuilder v = new StringBuilder();
                        i++;
                        while (i < n && s.charAt(i) != '"') {
                            char c = s.charAt(i++);
                            if (c == '\\' && i < n) { char e = s.charAt(i++); v.append(e == 'n' ? '\n' : e == 't' ? '\t' : e); }
                            else v.append(c);
                        }
                        i++;
                        val = v.toString();
                    } else {
                        int e = i;
                        while (e < n && s.charAt(e) != ',' && s.charAt(e) != '}') e++;
                        val = s.substring(i, e).trim();
                        i = e;
                    }
                    m.put(key, val);
                    while (i < n && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) i++;
                }
                out.add(m);
                i++;
            }
            return out;
        }
    }
}
