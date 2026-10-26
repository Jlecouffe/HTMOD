package com.htmod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Emission APRS : reglages BSS du poste, chemin, position, trame AX.25 relue par le decodeur. */
final class AprsTxTest {
    private AprsTxTest() {}

    static void run() {
        System.out.println("Émission APRS :");
        Radio radio = new Radio(new File("x"), new WinTest.NullListener());
        // READ_BSS_SETTINGS (reponse) : statut + 46 octets (structure RadioBssSettings de HTCommander)
        byte[] p = new byte[47];
        p[1] = 0x23;                    // relais max 2, TTL 3
        p[2] = (byte) (0x10 | 0x04);    // partage de position, format APRS
        p[3] = (byte) (9 << 4);         // SSID 9
        p[4] = 18;                      // 180 s
        put(p, 21, "HTMod test", 18);   // commentaire
        put(p, 39, "/>", 2);            // symbole voiture
        put(p, 41, "F4ABC", 6);         // indicatif
        radio.onCtrlData(WinTest.gaia(0x8021, p));
        Radio.AprsSettings a = radio.aprsSettings();
        WinTest.check("réglages APRS lus : " + (a == null ? "rien" : a.source() + " " + a.symbol + " " + a.intervalS + " s « " + a.beacon + " »"),
                a != null && a.source().equals("F4ABC-9") && a.symbol.equals("/>") && a.intervalS == 180 && a.beacon.equals("HTMod test")
                        && a.packetFormat == 1 && a.shareLocation);
        // GET_APRS_PATH (reponse)
        byte[] path = "WIDE1-1,WIDE2-1".getBytes(StandardCharsets.US_ASCII);
        byte[] pp = new byte[1 + path.length];
        System.arraycopy(path, 0, pp, 1, path.length);
        radio.onCtrlData(WinTest.gaia(0x8048, pp));
        WinTest.check("chemin APRS lu : " + radio.aprsPath(), "WIDE1-1,WIDE2-1".equals(radio.aprsPath()));
        // GET_POSITION (reponse) : 48,8566 N / -2,3522 (ouest) en 1/30000 de degre, 24 bits signes
        byte[] pos = new byte[18];
        int lat = (int) Math.round(48.8566 * 30000), lon = (int) Math.round(-2.3522 * 30000);
        pos[1] = (byte) (lat >> 16); pos[2] = (byte) (lat >> 8); pos[3] = (byte) lat;
        pos[4] = (byte) (lon >> 16); pos[5] = (byte) (lon >> 8); pos[6] = (byte) lon;
        radio.onCtrlData(WinTest.gaia(0x804C, pos));
        WinTest.check(String.format(java.util.Locale.ROOT, "position GPS du poste : %.4f, %.4f", radio.gpsLat(), radio.gpsLon()),
                radio.gpsLocked() && Math.abs(radio.gpsLat() - 48.8566) < 1e-4 && Math.abs(radio.gpsLon() + 2.3522) < 1e-4);

        String info = AprsTxPanel.positionInfo(radio.gpsLat(), radio.gpsLon(), '/', '>', a == null ? "" : a.beacon);
        byte[] f = Ax25.encodeUi("F4ABC-9", AprsTxPanel.TOCALL, Arrays.asList("WIDE1-1", "WIDE2-1"), info);
        Ax25 back = Ax25.parse(f);
        WinTest.check("trame AX.25 relue : " + (back == null ? "illisible" : back.tnc2()),
                back != null && back.isUi() && back.pid == 0xF0 && back.tnc2().equals("F4ABC-9>APZHTM,WIDE1-1,WIDE2-1:" + info));
        Aprs.Report r = back == null ? null : Aprs.parse(back);
        WinTest.check("position décodée : " + (r == null ? "?" : Aprs.fmtPos(r.lat, r.lon) + " " + r.symbolName() + " « " + r.comment + " »"),
                r != null && r.hasPos && Math.abs(r.lat - 48.8566) < 2e-4 && Math.abs(r.lon + 2.3522) < 2e-4 && r.symCode == '>'
                        && r.comment.equals("HTMod test"));
        // bits d'adresse : destination commande (C=1), reserves a 1, extension seulement sur le dernier relais
        WinTest.check("octets SSID : " + String.format("%02X %02X %02X %02X", f[6], f[13], f[20], f[27]),
                (f[6] & 0xFF) == 0xE0 && (f[13] & 0xFF) == 0x72 && (f[20] & 0xFF) == 0x62 && (f[27] & 0xFF) == 0x63);
        boolean refused;
        try { Ax25.encodeUi("TROPLONG1", "APZHTM", Arrays.asList(), "x"); refused = false; } catch (IllegalArgumentException e) { refused = true; }
        WinTest.check("indicatif invalide refusé", refused);
        WinTest.check("pas d'émission sans liaison", !radio.sendTncFrame(f, -1));
    }

    private static void put(byte[] p, int off, String s, int len) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, p, off, Math.min(len, b.length));
    }
}
