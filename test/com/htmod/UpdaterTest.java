package com.htmod;

/** Mises a jour : lecture de la reponse GitHub releases/latest et comparaison des versions. */
final class UpdaterTest {
    private UpdaterTest() {}

    static void run() {
        System.out.println("Mises à jour :");
        String json = "{\"url\":\"https://api.github.com/repos/Jlecouffe/HTMOD/releases/1\",\"html_url\":\"https://github.com/Jlecouffe/HTMOD/releases/tag/v1.2\","
                + "\"tag_name\":\"v1.2\",\"name\":\"HTMod 1.2\",\"assets\":[{\"name\":\"HTMod-Setup.exe\",\"size\":22018583,"
                + "\"browser_download_url\":\"https://github.com/Jlecouffe/HTMOD/releases/download/v1.2/HTMod-Setup.exe\"},"
                + "{\"name\":\"HTMod-Setup.exe.sha256\",\"size\":82,"
                + "\"browser_download_url\":\"https://github.com/Jlecouffe/HTMOD/releases/download/v1.2/HTMod-Setup.exe.sha256\"}],"
                + "\"body\":\"- Émission APRS\\r\\n- Fréquences \\\"ISS\\\" \\u00e9ditables\"}";
        Updater.Release r = Updater.parse(json);
        WinTest.check("version " + r.version + ", " + r.size + " octets, page " + r.page,
                r.version.equals("1.2") && r.size == 22018583 && r.page.endsWith("/tag/v1.2"));
        WinTest.check("fichiers joints : installateur et empreinte", r.setupUrl != null && r.setupUrl.endsWith("/v1.2/HTMod-Setup.exe")
                && r.shaUrl != null && r.shaUrl.endsWith(".sha256"));
        WinTest.check("notes décodées : " + r.notes.replace('\n', '|'), r.notes.equals("- Émission APRS\n- Fréquences \"ISS\" éditables"));
        WinTest.check("comparaison des versions", Updater.compare("1.2", "1.1") > 0 && Updater.compare("1.10", "1.9") > 0
                && Updater.compare("1.1", "1.1.0") == 0 && Updater.compare("1.0.9", "1.1") < 0);
    }
}
