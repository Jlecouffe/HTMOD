/*
 * HTMOD Windows : mises a jour par les Releases GitHub (github.com/Jlecouffe/HTMOD/releases).
 *  - derniere version : API GitHub releases/latest (tag « v1.2 », notes, fichiers joints) ;
 *  - telechargement de HTMod-Setup.exe dans le dossier temporaire, controle par l'empreinte SHA-256
 *    publiee avec la Release (HTMod-Setup.exe.sha256) ;
 *  - installation : l'installateur Inno Setup est lance en silencieux dans le dossier actuel
 *    (/DIR, /CURRENTUSER ou /ALLUSERS), ferme HTMod, remplace les fichiers et relance HTMod (/UPDATE=1).
 */
package com.htmod;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Updater {
    static final String REPO = "Jlecouffe/HTMOD";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    static final String SETUP = "HTMod-Setup.exe";

    static final class Release {
        String version = "", notes = "", page = RELEASES_PAGE, setupUrl, shaUrl;
        long size;
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    /** Derniere Release publiee (hors brouillons et pre-versions). */
    Release latest() throws IOException, InterruptedException {
        HttpRequest r = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "HTMod/" + App.VERSION)
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) throw new IOException("aucune version publiée");
        if (resp.statusCode() != 200) throw new IOException("GitHub HTTP " + resp.statusCode());
        return parse(resp.body());
    }

    static Release parse(String json) {
        Release rel = new Release();
        String tag = str(json, "tag_name");
        rel.version = tag == null ? "" : tag.replaceFirst("^[vV]", "").trim();
        String body = str(json, "body");
        rel.notes = body == null ? "" : body.trim();
        String page = str(json, "html_url");
        if (page != null && page.contains("/releases/")) rel.page = page;
        Matcher m = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        while (m.find()) {
            String u = m.group(1);
            if (u.endsWith("/" + SETUP)) rel.setupUrl = u;
            else if (u.endsWith("/" + SETUP + ".sha256")) rel.shaUrl = u;
        }
        Matcher sz = Pattern.compile("\"name\"\\s*:\\s*\"" + Pattern.quote(SETUP) + "\"[^}]*?\"size\"\\s*:\\s*(\\d+)").matcher(json);
        if (sz.find()) rel.size = Long.parseLong(sz.group(1));
        return rel;
    }

    /** Valeur texte d'une cle JSON (premiere occurrence), echappements decodes ; null si absente. */
    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) return null;
        String s = m.group(1);
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) { b.append(c); continue; }
            char e = s.charAt(++i);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 'r': break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 < s.length()) { b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; }
                    break;
                default: b.append(e);
            }
        }
        return b.toString();
    }

    /** Compare deux versions « 1.10.2 » numeriquement (> 0 si a est plus recente). */
    static int compare(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        String d = s.replaceAll("\\D.*$", "");
        try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    static boolean newer(Release r) { return !r.version.isEmpty() && compare(r.version, App.VERSION) > 0; }

    /** Lance depuis l'application installee (HTMod.exe de jpackage), et pas depuis les sources. */
    static File installedExe() {
        String p = System.getProperty("jpackage.app-path");
        if (p != null && new File(p).isFile()) return new File(p);
        // repli : runtime embarque dans <dossier>\runtime, a cote de HTMod.exe
        File rt = new File(System.getProperty("java.home", ""));
        File exe = new File(rt.getParentFile() == null ? rt : rt.getParentFile(), "HTMod.exe");
        return rt.getName().equalsIgnoreCase("runtime") && exe.isFile() ? exe : null;
    }

    /** Telecharge l'installateur et verifie son empreinte ; progress recoit 0..100. */
    File download(Release r, IntConsumer progress) throws IOException, InterruptedException {
        if (r.setupUrl == null) throw new IOException(SETUP + " absent de la version " + r.version);
        if (r.shaUrl == null) throw new IOException("empreinte " + SETUP + ".sha256 absente de la version " + r.version);
        String expected = text(r.shaUrl).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) throw new IOException("empreinte SHA-256 illisible");
        File dir = new File(System.getProperty("java.io.tmpdir"), "htmod-maj");
        dir.mkdirs();
        File out = new File(dir, "HTMod-Setup-" + r.version.replaceAll("[^0-9A-Za-z.]", "") + ".exe");
        HttpResponse<InputStream> resp = http.send(HttpRequest.newBuilder(URI.create(r.setupUrl))
                .header("User-Agent", "HTMod/" + App.VERSION).timeout(Duration.ofMinutes(10)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new IOException("téléchargement HTTP " + resp.statusCode());
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(r.size);
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
        try (InputStream in = resp.body(); OutputStream o = Files.newOutputStream(out.toPath())) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                int pct = total > 0 ? (int) (done * 100 / total) : 0;
                if (pct != last) { progress.accept(Math.min(100, pct)); last = pct; }
            }
        }
        StringBuilder got = new StringBuilder();
        for (byte b : sha.digest()) got.append(String.format("%02x", b));
        if (!got.toString().equals(expected)) {
            out.delete();
            throw new IOException("empreinte SHA-256 différente : fichier corrompu ou modifié, installation annulée");
        }
        return out;
    }

    private String text(String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "HTMod/" + App.VERSION)
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    /**
     * Lance l'installateur dans le dossier de l'installation actuelle ; l'appelant quitte HTMod juste apres.
     * Installation « pour tous » (Program Files, demande de droits administrateur) ou « pour moi ».
     */
    static void install(File setup, File exe) throws IOException {
        File appDir = exe.getParentFile();
        String pf = System.getenv("ProgramFiles"), pf86 = System.getenv("ProgramFiles(x86)");
        String dir = appDir.getAbsolutePath();
        boolean allUsers = (pf != null && dir.toLowerCase(Locale.ROOT).startsWith(pf.toLowerCase(Locale.ROOT)))
                || (pf86 != null && dir.toLowerCase(Locale.ROOT).startsWith(pf86.toLowerCase(Locale.ROOT)));
        new ProcessBuilder(setup.getAbsolutePath(), "/SILENT", "/SUPPRESSMSGBOXES", "/CLOSEAPPLICATIONS", "/NORESTART",
                allUsers ? "/ALLUSERS" : "/CURRENTUSER", "/DIR=" + dir, "/UPDATE=1").start();
    }
}
