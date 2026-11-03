package com.htmod;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** TNC KISS : encodage, decodage (echappements, trames coupees, commandes non « donnees » ignorees). */
final class KissTest {
    private KissTest() {}

    static void run() {
        System.out.println("TNC KISS :");
        // trame dont le champ d'information contient FEND et FESC (a echapper)
        byte[] ax = Ax25.encodeUi("F4ABC-9", "APZHTM", Arrays.asList("WIDE1-1"), ">Test ÀÛ fin");
        byte[] k = KissTnc.encode(ax);
        int raw = 0;
        for (int i = 1; i < k.length - 1; i++) if ((k[i] & 0xFF) == KissTnc.FEND) raw++;
        WinTest.check("encodage : " + k.length + " octets, pas de FEND dans la trame", raw == 0 && (k[0] & 0xFF) == 0xC0 && k[1] == 0);

        List<byte[]> got = new ArrayList<>();
        KissTnc t = new KissTnc(new File("x"), new KissTnc.Listener() {
            @Override public void onKissFrame(byte[] f) { got.add(f); }
            @Override public void onKissStatus(String text, boolean connected) {}
            @Override public void onKissLog(String text) {}
        });
        // bruit, commande TXDELAY (ignoree), puis la trame coupee en 3 morceaux (dont un au milieu d'un echappement)
        byte[] stream = concat(new byte[]{0x41, 0x42, (byte) 0xC0, 0x01, 0x32, (byte) 0xC0}, k, k);
        int esc = -1;
        for (int i = 6; i < stream.length; i++) if ((stream[i] & 0xFF) == KissTnc.FESC) { esc = i; break; }
        int cut1 = esc + 1, cut2 = cut1 + 7;
        t.feed(Arrays.copyOfRange(stream, 0, cut1), cut1);
        t.feed(Arrays.copyOfRange(stream, cut1, cut2), cut2 - cut1);
        t.feed(Arrays.copyOfRange(stream, cut2, stream.length), stream.length - cut2);
        Ax25 p = got.isEmpty() ? null : Ax25.parse(got.get(0));
        WinTest.check("décodage : " + got.size() + " trame(s), " + (p == null ? "illisible" : p.tnc2()),
                got.size() == 2 && Arrays.equals(got.get(0), ax) && Arrays.equals(got.get(1), ax));
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] b : parts) n += b.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] b : parts) { System.arraycopy(b, 0, r, o, b.length); o += b.length; }
        return r;
    }
}
