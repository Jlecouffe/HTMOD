package com.htmod;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Tests APRS : AX.25, demodulateur AFSK 1200 (signal synthetique), decodage APRS, fragments TNC, BLE. */
final class AprsTest {
    private AprsTest() {}

    static void run() {
        System.out.println("APRS :");

        // --- AX.25 aller-retour
        byte[] f = ax25("F4ABC-9", "APRS", new String[]{"WIDE1-1*", "WIDE2-1"}, "!4903.50N/00201.75E>088/036/A=001234 Test");
        Ax25 p = Ax25.parse(java.util.Arrays.copyOf(f, f.length - 2)); // sans FCS, comme le TNC et le demodulateur
        WinTest.check("AX.25 : " + (p == null ? "null" : p.tnc2()),
                p != null && p.tnc2().equals("F4ABC-9>APRS,WIDE1-1*,WIDE2-1:!4903.50N/00201.75E>088/036/A=001234 Test"));

        // --- Positions
        Aprs.Report r = Aprs.parse(p);
        WinTest.check("position non compressée 49.0583, 2.0292, 088°, 67 km/h, 376 m",
                r.hasPos && near(r.lat, 49.058333) && near(r.lon, 2.029167) && r.course == 88
                        && Math.round(r.speedKmh) == 67 && Math.round(r.altM) == 376 && r.symCode == '>' && r.comment.equals("Test"));
        Aprs.Report c = Aprs.parse("F4ABC", "APRS", "=/5L!!<*e7>7P[", 0);
        WinTest.check("position compressée (exemple de la spec) 49.5, -72.75 (" + Aprs.fmtPos(c.lat, c.lon) + ")",
                c.hasPos && Math.abs(c.lat - 49.5) < 1e-3 && Math.abs(c.lon + 72.75) < 1e-3 && c.symCode == '>');
        Aprs.Report o = Aprs.parse("F4ABC", "APRS", ";LEADER   *092345z4903.50N/07201.75W>088/036", 0);
        WinTest.check("objet LEADER", "Objet".equals(o.type) && "LEADER".equals(o.name) && o.hasPos && near(o.lon, -72.029167));
        Aprs.Report m = Aprs.parse("F4ABC", "APRS", ":F4XYZ-7  :Bonjour{12", 0);
        WinTest.check("message à F4XYZ-7", "Message".equals(m.type) && "F4XYZ-7".equals(m.addressee) && m.message.equals("Bonjour{12"));
        Aprs.Report t = Aprs.parse("F5ZZZ-10", "APRS", "}F4ABC>APRS,TCPIP,F5ZZZ-10*:!4903.50N/00201.75E-Maison", 0);
        WinTest.check("trafic tiers : origine F4ABC", "F4ABC".equals(t.source) && t.hasPos && t.symCode == '-');

        // --- Mic-E : encode de facon independante (formules de la spec) puis decode
        String dst = micEDest(45, 12.34, true, 4, true);   // 45°12.34' N, longitude >= 100 ? non (4)
        String info = micEInfo(4, 51.67, 52, 123, '>', '/', "Mobile");
        Aprs.Report me = Aprs.parse("F4MIC-9", dst, info, 0);
        WinTest.check("Mic-E 45.20567, -4.86117, 96 km/h, cap 123 (" + Aprs.fmtPos(me.lat, me.lon) + ", "
                        + Math.round(me.speedKmh) + " km/h, " + me.course + "°)",
                me.hasPos && near(me.lat, 45 + 12.34 / 60) && near(me.lon, -(4 + 51.67 / 60)) && me.course == 123
                        && Math.round(me.speedKmh) == Math.round(52 * 1.852) && me.symCode == '>');

        // --- Demodulateur : 6 trames, tonalites desequilibrees (desaccentuation) + bruit
        List<byte[]> frames = new ArrayList<>();
        for (int i = 0; i < 6; i++)
            frames.add(ax25("F4T" + (char) ('A' + i), "APRS", new String[]{"WIDE2-2"}, "!4903.50N/00201.75E>Paquet de test numero " + i));
        short[] audio = modulate(frames, 32000, 0.45, 0.22, 7);
        List<String> got = new ArrayList<>();
        Afsk1200 d = new Afsk1200(32000, fr -> { Ax25 a = Ax25.parse(fr); if (a != null) got.add(a.src); });
        for (int i = 0; i < audio.length; i += 640) d.process(audio, i, Math.min(640, audio.length - i)); // blocs de 20 ms
        WinTest.check("AFSK 1200 : " + got.size() + "/6 trames décodées (bruit, space atténué de 6 dB) " + got, got.size() == 6);
        // a 44,1 kHz (fichier WAV)
        List<String> got44 = new ArrayList<>();
        Afsk1200 d44 = new Afsk1200(44100, fr -> got44.add("x"));
        short[] a44 = modulate(frames, 44100, 0.5, 0.15, 3);
        d44.process(a44, 0, a44.length);
        WinTest.check("AFSK 1200 à 44,1 kHz : " + got44.size() + "/6", got44.size() == 6);
        // bruit seul : aucune fausse trame
        short[] noise = new short[32000 * 5];
        Random rnd = new Random(3);
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (rnd.nextGaussian() * 6000);
        int[] fp = {0};
        new Afsk1200(32000, fr -> fp[0]++).process(noise, 0, noise.length);
        WinTest.check("AFSK 1200 : aucune fausse trame sur 5 s de bruit", fp[0] == 0);

        // --- Fragments du TNC materiel (notification DATA_RXD), en mode BLE (messages bruts)
        List<byte[]> tnc = new ArrayList<>();
        Radio radio = new Radio(new java.io.File("x"), new WinTest.NullListener() {
            @Override public void onTncFrame(byte[] fr) { tnc.add(fr); }
        });
        radio.onBridgeEvent("connected 0 ble");
        byte[] whole = frames.get(0);
        byte[] fcsless = java.util.Arrays.copyOf(whole, whole.length - 2);
        int half = fcsless.length / 2;
        radio.onCtrlData(notif(0x00, java.util.Arrays.copyOfRange(fcsless, 0, half), -1));
        radio.onCtrlData(notif(0x80 | 0x40 | 1, java.util.Arrays.copyOfRange(fcsless, half, fcsless.length), 3));
        WinTest.check("TNC du poste : trame réassemblée depuis 2 fragments BLE",
                tnc.size() == 1 && java.util.Arrays.equals(tnc.get(0), fcsless));
    }

    static boolean near(double a, double b) { return Math.abs(a - b) < 2e-4; }

    /** Notification EVENT_NOTIFICATION / DATA_RXD brute (format BLE). */
    static byte[] notif(int flags, byte[] data, int channel) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0); b.write(2); b.write(0); b.write(9); // groupe 2, commande 9
        b.write(2); // DATA_RXD
        b.write(flags);
        b.write(data, 0, data.length);
        if (channel >= 0) b.write(channel);
        return b.toByteArray();
    }

    // ------------------------------------------------------------------ Construction de trames

    static byte[] ax25(String src, String dst, String[] path, String info) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        List<String> addrs = new ArrayList<>();
        addrs.add(dst);
        addrs.add(src);
        for (String s : path) addrs.add(s);
        for (int k = 0; k < addrs.size(); k++) {
            String a = addrs.get(k);
            boolean h = a.endsWith("*");
            if (h) a = a.substring(0, a.length() - 1);
            String call = a, ssid = "0";
            int dash = a.indexOf('-');
            if (dash > 0) { call = a.substring(0, dash); ssid = a.substring(dash + 1); }
            for (int i = 0; i < 6; i++) b.write((i < call.length() ? call.charAt(i) : ' ') << 1);
            int sb = 0x60 | (Integer.parseInt(ssid) << 1) | (k == addrs.size() - 1 ? 1 : 0) | (h ? 0x80 : 0);
            b.write(sb);
        }
        b.write(0x03);
        b.write(0xF0);
        byte[] i = info.getBytes(StandardCharsets.ISO_8859_1);
        b.write(i, 0, i.length);
        byte[] body = b.toByteArray();
        int fcs = Ax25.fcs(body, 0, body.length);
        byte[] out = java.util.Arrays.copyOf(body, body.length + 2);
        out[body.length] = (byte) fcs;
        out[body.length + 1] = (byte) (fcs >> 8);
        return out;
    }

    /** Modulation AFSK Bell 202 (phase continue), avec niveaux mark/space distincts et bruit gaussien. */
    static short[] modulate(List<byte[]> frames, int rate, double markAmp, double spaceAmp, long seed) {
        List<Integer> bits = new ArrayList<>();
        for (byte[] fr : frames) {
            for (int k = 0; k < 40; k++) flag(bits);
            int ones = 0;
            for (byte by : fr) {
                for (int i = 0; i < 8; i++) {
                    int bit = (by >> i) & 1;
                    bits.add(bit);
                    if (bit == 1) { if (++ones == 5) { bits.add(0); ones = 0; } } else ones = 0;
                }
            }
            for (int k = 0; k < 3; k++) flag(bits);
            for (int k = 0; k < 120; k++) bits.add(-1); // silence entre trames
        }
        Random rnd = new Random(seed);
        double spb = rate / 1200.0, phase = 0, t = 0;
        boolean mark = true;
        List<Short> out = new ArrayList<>();
        int n = 0;
        for (int bit : bits) {
            if (bit == 0) mark = !mark; // NRZI : 0 = changement
            double end = (n + 1) * spb;
            while (t < end) {
                double s;
                if (bit < 0) s = 0;
                else {
                    phase += 2 * Math.PI * (mark ? 1200 : 2200) / rate;
                    s = Math.sin(phase) * (mark ? markAmp : spaceAmp);
                }
                s += rnd.nextGaussian() * 0.04;
                out.add((short) Math.max(-32768, Math.min(32767, Math.round(s * 32767))));
                t += 1;
            }
            n++;
        }
        short[] r = new short[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static void flag(List<Integer> bits) { for (int i = 0; i < 8; i++) bits.add((0x7E >> i) & 1); }

    // ------------------------------------------------------------------ Encodeur Mic-E (spec APRS 1.0.1, chapitre 10)

    static String micEDest(int latDeg, double latMin, boolean north, int lonDeg, boolean west) {
        int mm = (int) latMin, hh = (int) Math.round((latMin - mm) * 100);
        int[] dg = {latDeg / 10, latDeg % 10, mm / 10, mm % 10, hh / 10, hh % 10};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            boolean flagBit = (i == 0 || i == 1) // bits de message A/B : "En route" = 1,1,1 -> P..Y
                    || (i == 2)
                    || (i == 3 && north) || (i == 4 && lonOffset(lonDeg)) || (i == 5 && west);
            sb.append((char) ((flagBit ? 'P' : '0') + dg[i]));
        }
        return sb.toString();
    }

    /** Bit de decalage de longitude : 0-9 degres et 100-179 degres. */
    static boolean lonOffset(int lonDeg) { return lonDeg < 10 || lonDeg >= 100; }

    static String micEInfo(int lonDeg, double lonMin, int speedKn, int course, char sym, char table, String comment) {
        int d;
        if (lonDeg < 10) d = lonDeg + 118;              // decode : +100 puis -190
        else if (lonDeg < 100) d = lonDeg + 28;
        else if (lonDeg < 110) d = lonDeg + 8;          // decode : +100 puis -80
        else d = lonDeg - 72;
        int mm = (int) lonMin, hh = (int) Math.round((lonMin - mm) * 100);
        int mc = mm < 10 ? mm + 88 : mm + 28;
        int sp = speedKn / 10 + 28;
        int dc = (speedKn % 10) * 10 + course / 100 + 28;
        int se = course % 100 + 28;
        return "`" + (char) d + (char) mc + (char) (hh + 28) + (char) sp + (char) dc + (char) se + sym + table + comment;
    }
}
