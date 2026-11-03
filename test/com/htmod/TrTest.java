package com.htmod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fichier de traductions : colonnes, formats et balises conserves, prefixes testes par le code. */
class TrTest {
    private static final Pattern SPEC = Pattern.compile("%[-+0-9.]*[a-zA-Z]"), TAG = Pattern.compile("</?[a-zA-Z][^>]*>");

    static void run() throws Exception {
        System.out.println("Traductions :");
        List<String[]> rows = Tr.table();
        WinTest.check(rows.size() + " entrees", rows.size() > 500);
        int badCols = 0, badSpec = 0, badTag = 0;
        Map<String, String[]> byFr = new HashMap<>();
        for (String[] r : rows) {
            if (r.length != 3) { badCols++; System.out.println("    colonnes : " + r[0]); continue; }
            byFr.put(r[0], r);
            for (int i = 1; i < 3; i++) {
                if (r[i].isEmpty()) continue;
                if (!all(SPEC, r[0]).equals(all(SPEC, r[i]))) { badSpec++; System.out.println("    format : " + r[i]); }
                if (count(TAG, r[0]) != count(TAG, r[i])) { badTag++; System.out.println("    balises : " + r[i]); }
            }
        }
        WinTest.check("3 colonnes partout", badCols == 0);
        WinTest.check("memes %d, %s", badSpec == 0);
        WinTest.check("memes balises HTML", badTag == 0);

        // App.onStatus et KissPanel reconnaissent un etat au debut du texte
        String[][] prefixes = {{"Connecté", "Connecté (commandes + audio)"}, {"Commandes ouvertes", "Commandes ouvertes ("}, {"Ouverture", "Ouverture de"}};
        for (String[] p : prefixes)
            for (int i = 1; i < 3; i++)
                WinTest.check("prefixe " + p[0] + " (" + Tr.LANGS[i][0] + ")", byFr.get(p[1])[i].startsWith(byFr.get(p[0])[i]));

        WinTest.check("texte sans traduction inchange", "abc def".equals(Tr.t("abc def")));
        WinTest.check("colonne en police fixe", Tr.pad("xy", 5).equals("xy   "));
    }

    private static List<String> all(Pattern p, String s) {
        List<String> l = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) l.add(m.group());
        Collections.sort(l);
        return l;
    }

    private static int count(Pattern p, String s) { return all(p, s).size(); }
}
