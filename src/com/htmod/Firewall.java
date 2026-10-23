/*
 * HTMOD Windows : regle du pare-feu Windows pour le serveur web (connexions entrantes, reseaux prives).
 *  - verification : « netsh advfirewall firewall show rule » (lecture seule, sans droits) ;
 *  - creation : uniquement sur demande de l'utilisateur (touche AUTORISER DANS LE PARE-FEU), par
 *    btbridge.exe qui lance netsh.exe avec la demande d'elevation habituelle de Windows (UAC).
 * Aucun script PowerShell, aucune commande encodee, aucune fenetre cachee.
 */
package com.htmod;

import java.io.File;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class Firewall {
    static final String RULE = "HTMod serveur web";

    private Firewall() {}

    /** Chemin de l'executable en cours (HTMod.exe une fois installe). */
    static String exePath() {
        return ProcessHandle.current().info().command().orElse("");
    }

    static boolean windows() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    /** Une regle « HTMod serveur web » autorise deja cet executable (lecture seule). */
    static boolean exists() {
        if (!windows()) return true;
        String exe = exePath();
        try {
            Process p = new ProcessBuilder("netsh", "advfirewall", "firewall", "show", "rule", "name=" + RULE, "verbose")
                    .redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            String s = new String(out, Charset.defaultCharset()).toLowerCase(Locale.ROOT);
            return p.exitValue() == 0 && (exe.isEmpty() || s.contains(exe.toLowerCase(Locale.ROOT)));
        } catch (Exception e) {
            return false;
        }
    }

    /** Cree la regle (une demande d'autorisation Windows s'affiche) ; resultat dans le journal. */
    static void add(File bridgeExe, Consumer<String> log, Runnable done) {
        String exe = exePath();
        if (exe.isEmpty() || !bridgeExe.isFile()) { log.accept("Pare-feu : btbridge.exe ou HTMod.exe introuvable"); return; }
        Thread t = new Thread(() -> {
            try {
                log.accept("Pare-feu : autorisation de " + exe + " sur les réseaux privés (confirmez la demande de Windows)…");
                Process p = new ProcessBuilder(bridgeExe.getAbsolutePath(), "firewall", exe).redirectErrorStream(true).start();
                p.getOutputStream().close();
                p.getInputStream().readAllBytes();
                p.waitFor(180, TimeUnit.SECONDS);
                log.accept(exists() ? "Pare-feu : règle « " + RULE + " » ajoutée"
                        : "Pare-feu : règle non ajoutée (autorisation refusée ?)");
            } catch (Exception e) {
                log.accept("Pare-feu : " + e.getMessage());
            } finally {
                if (done != null) done.run();
            }
        }, "htmod-parefeu");
        t.setDaemon(true);
        t.start();
    }
}
