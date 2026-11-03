/*
 * HTMOD Windows : langue de l'interface (francais, anglais, italien).
 *  - le texte francais des sources sert de cle : Tr.t("Quitter") ; les traductions sont dans res/com/htmod/i18n.txt ;
 *  - les espaces et retours a la ligne autour d'un texte sont ignores pour la recherche, puis conserves ;
 *  - la langue est lue une seule fois au demarrage : en changer demande de relancer HTMod (App.applyLanguage) ;
 *  - l'interface web recoit la meme table par /i18n.js (fonction _t).
 */
package com.htmod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

final class Tr {
    /** Code et nom (dans sa propre langue) des langues proposees. */
    static final String[][] LANGS = {{"fr", "Français"}, {"en", "English"}, {"it", "Italiano"}};

    private static final String PREF = "lang";
    private static final String LANG = pick();
    private static final Map<String, String> DICT = load(LANG);

    static {
        // boites de dialogue et selecteur de fichiers de Swing dans la meme langue
        if (!Locale.getDefault().getLanguage().equals(LANG)) Locale.setDefault(new Locale(LANG));
    }

    private Tr() {}

    /** Langue enregistree, sinon celle de Windows, sinon l'anglais (-Dhtmod.lang=xx pour les tests). */
    private static String pick() {
        String forced = System.getProperty("htmod.lang");
        if (known(forced)) return forced;
        String saved = prefs().get(PREF, "");
        if (known(saved)) return saved;
        String sys = Locale.getDefault().getLanguage();
        return known(sys) ? sys : "en";
    }

    private static Preferences prefs() { return Preferences.userRoot().node("htmod"); }

    static boolean known(String code) {
        for (String[] l : LANGS) if (l[0].equals(code)) return true;
        return false;
    }

    /** Langue de cette execution. */
    static String lang() { return LANG; }

    /** Enregistre la langue du prochain demarrage. */
    static void save(String code) {
        Preferences p = prefs();
        p.put(PREF, code);
        try { p.flush(); } catch (BackingStoreException ignore) {} // relance immediate : ne pas attendre l'ecriture differee
    }

    /** Traduction d'un texte francais (le texte lui-meme s'il n'a pas de traduction). */
    static String t(String s) {
        if (s == null || DICT.isEmpty()) return s;
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) <= ' ') a++;
        while (b > a && s.charAt(b - 1) <= ' ') b--;
        String v = DICT.get(s.substring(a, b));
        if (v == null) return s;
        return a == 0 && b == s.length() ? v : s.substring(0, a) + v + s.substring(b);
    }

    /** Traduction completee par des espaces jusqu'a width caracteres (colonnes en police fixe). */
    static String pad(String s, int width) {
        StringBuilder b = new StringBuilder(t(s));
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    /** Script /i18n.js de l'interface web : table de la langue courante et fonctions _t, i18nPage. */
    static String webScript() {
        return "const I18N_LANG=" + Json.of(LANG) + ",I18N=" + Json.of(DICT) + ";\n"
                + "function _t(s){const m=/^([ \\t\\r\\n]*)([\\s\\S]*?)([ \\t\\r\\n]*)$/.exec(s),v=I18N[m[2]];return typeof v==='string'?m[1]+v+m[3]:s;}\n"
                + "function i18nPage(){document.documentElement.lang=I18N_LANG;"
                + "document.querySelectorAll('[data-t]').forEach(e=>{e.textContent=_t(e.textContent);});"
                + "document.querySelectorAll('[title]').forEach(e=>{e.title=_t(e.title);});}\n"
                + "document.addEventListener('DOMContentLoaded',i18nPage);\n";
    }

    private static Map<String, String> load(String lang) {
        Map<String, String> m = new HashMap<>();
        int col = -1;
        for (int i = 1; i < LANGS.length; i++) if (LANGS[i][0].equals(lang)) col = i;
        if (col < 0) return m; // francais : le texte des sources
        try {
            for (String[] row : table()) if (row.length > col && !row[col].isEmpty()) m.put(row[0], row[col]);
        } catch (IOException e) {
            System.err.println("i18n.txt : " + e);
        }
        return m;
    }

    /** Lignes du fichier de traductions : {francais, anglais, italien}. */
    static List<String[]> table() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = Tr.class.getResourceAsStream("i18n.txt")) {
            if (in == null) throw new IOException("i18n.txt absent");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] c = line.split(" \\|( |$)", -1);
                for (int i = 0; i < c.length; i++) c[i] = c[i].trim().replace("\\n", "\n");
                rows.add(c);
            }
        }
        return rows;
    }
}
