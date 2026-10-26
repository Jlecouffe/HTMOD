package com.htmod;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/** Tests de la version Windows, sans poste : SBC, trames audio, protocole GAIA, chaine de decodage. */
public class WinTest {
    static boolean ok = true;

    static void check(String what, boolean cond) {
        System.out.println((cond ? "  OK     " : "  ECHEC  ") + what);
        ok &= cond;
    }

    public static void main(String[] a) throws Exception {
        String samples = a[0];

        System.out.println("SBC aller-retour (tonalite 1 kHz) :");
        for (int sb : new int[]{8, 4}) {
            double snr = sbcRoundTrip(sb, 16, 32, Sbc.ALLOC_LOUDNESS);
            check(sb + " sous-bandes, 16 blocs, bitpool 32 : SNR " + Math.round(snr) + " dB", snr > 40);
        }
        double snrSnr = sbcRoundTrip(8, 16, 18, Sbc.ALLOC_SNR);
        check("8 sous-bandes, allocation SNR, bitpool 18 : SNR " + Math.round(snrSnr) + " dB", snrSnr > 25);

        System.out.println("Protocole GAIA simule :");
        List<String> names = new ArrayList<>();
        Radio radio = new Radio(new File("x"), new NullListener() {
            @Override public void onChannel(String name, boolean rx) { names.add(name); }
        });
        // GET_DEV_INFO (reponse) : 3 canaux
        radio.onCtrlData(gaia(0x8004, new byte[]{0, 0, 0, 1, 0, 0, 1, 0, 0, 3, 0, 0, 0, 0, 0, 0}));
        // GET_HT_STATUS : allume, en reception, canal 2 (octet 6 = 0x20)
        radio.onCtrlData(gaia(0x8014, new byte[]{0, (byte) 0x90, 0x20, 0, 0}));
        // READ_RF_CH canal 2 "SARSAT", coupee en deux paquets pour tester le reassemblage
        byte[] ch = gaia(0x800D, channelPayload(2, "SARSAT"));
        byte[] p1 = java.util.Arrays.copyOfRange(ch, 0, 9), p2 = java.util.Arrays.copyOfRange(ch, 9, ch.length);
        radio.onCtrlData(p1);
        radio.onCtrlData(p2);
        check("nom du canal courant = SARSAT (" + radio.currentChannelName() + ")", radio.currentChannelName().equals("SARSAT"));
        check("mode deduit : SARSAT", Decoders.modeFor(radio.currentChannelName()) == Decoders.MODE_SARSAT);
        // Notification HT_STATUS_CHANGED : passage au canal 0 (nom inconnu)
        radio.onCtrlData(gaia(0x0009, new byte[]{1, (byte) 0x80, 0x00, 0, 0}));
        check("changement de canal par notification", radio.currentChannelName().isEmpty());

        System.out.println("Chaine complete WAV -> SBC -> trames 0x7E -> Radio -> Decoders :");
        List<Decoders.Detection> got = new ArrayList<>();
        File tmp = new File(System.getProperty("java.io.tmpdir"), "htmod-test");
        Decoders dec = new Decoders(tmp, new Decoders.Listener() {
            @Override public void onDetection(Decoders.Detection d) { synchronized (got) { got.add(d); } }
            @Override public void onLog(String text) { System.out.println("     " + text); }
        });
        dec.saveWav = false;

        Wav s = Wav.read(new File(samples, "sarsat_samples/sarsat_selftest_beacon.wav"));
        if (s.rate != 32000) throw new IllegalStateException("echantillon SARSAT attendu a 32 kHz, lu " + s.rate);
        runThroughRadio(s.pcm, "SARSAT", dec);
        Thread.sleep(1500);
        boolean sarsatOk = false;
        synchronized (got) {
            for (Decoders.Detection d : got)
                if ("SARSAT".equals(d.kind) && "1C72091A2B3FDFF".equals(d.id) && Math.abs(d.lat - 42.954) < 5e-4) sarsatOk = true;
        }
        check("balise SARSAT 1C72091A2B3FDFF decodee apres passage par SBC", sarsatOk);

        got.clear();
        Wav d = Wav.read(new File(samples, "radiosonde_samples/DFM09_32k.wav"));
        runThroughRadio(d.pcm, "Radiosonde", dec);
        Thread.sleep(3000);
        boolean dfmOk = false;
        synchronized (got) {
            for (Decoders.Detection x : got) if ("DFM09".equals(x.kind) && x.id != null && x.id.contains("523135")) dfmOk = true;
            check("radiosonde DFM09 523135 decodee apres passage par SBC (" + got.size() + " position(s))", dfmOk);
        }

        got.clear();
        runThroughRadio(s.pcm, "APRS", dec);
        Thread.sleep(800);
        check("aucun decodage sur un canal ordinaire", got.isEmpty());

        AprsTest.run();
        AprsTxTest.run();
        PocsagTest.run();
        SstvTest.run();
        ChannelTest.run();
        SatTest.run(new File(a.length > 1 ? a[1] : "test/data"));

        System.out.println(ok ? "TOUS LES TESTS OK" : "DES TESTS ONT ECHOUE");
        System.exit(ok ? 0 : 1);
    }

    static double sbcRoundTrip(int subbands, int blocks, int bitpool, int alloc) {
        int n = 32000;
        short[] in = new short[n];
        for (int i = 0; i < n; i++) in[i] = (short) (10000 * Math.sin(2 * Math.PI * 1000 * i / 32000.0));
        SbcEncoder enc = new SbcEncoder(1, blocks, subbands, bitpool, alloc);
        Sbc decd = new Sbc();
        int per = blocks * subbands;
        short[] out = new short[n];
        int o = 0;
        for (int p = 0; p + per <= n; p += per) {
            byte[] f = enc.encode(in, p);
            int got = decd.decode(f, 0, f.length, out, o);
            if (got != per) return -1;
            o += got;
        }
        double best = -1;
        for (int lag = 0; lag < 300; lag++) {
            double sig = 0, err = 0;
            for (int i = 2000; i < o - 300; i++) {
                sig += (double) in[i] * in[i];
                double e = out[i + lag] - in[i];
                err += e * e;
            }
            best = Math.max(best, 10 * Math.log10(sig / Math.max(err, 1e-9)));
        }
        return best;
    }

    /** Encode le PCM en SBC, l'emballe comme le poste (0x7E, commande 0x03, echappement 0x7D) et le passe a Radio. */
    static void runThroughRadio(short[] pcm, String channel, Decoders dec) {
        Radio radio = new Radio(new File("x"), new NullListener() {
            @Override public void onAudio(short[] p, int len) { dec.feed(channel, p, 0, len); }
        });
        SbcEncoder enc = new SbcEncoder(1, 16, 8, 32, Sbc.ALLOC_LOUDNESS);
        int per = 128;
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int framesPerPacket = 4;
        for (int p = 0; p + per * framesPerPacket <= pcm.length; p += per * framesPerPacket) {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            payload.write(0x03);
            for (int k = 0; k < framesPerPacket; k++) {
                byte[] f = enc.encode(pcm, p + k * per);
                payload.write(f, 0, f.length);
            }
            stream.write(0x7E);
            for (byte b : payload.toByteArray()) {
                if ((b & 0xFF) == 0x7E || (b & 0xFF) == 0x7D) { stream.write(0x7D); stream.write(b ^ 0x20); }
                else stream.write(b);
            }
            stream.write(0x7E);
        }
        stream.write(new byte[]{0x7E, 0x01, 0x7E}, 0, 3); // fin d'audio
        byte[] all = stream.toByteArray();
        // decoupage arbitraire en paquets RFCOMM de 333 octets
        for (int i = 0; i < all.length; i += 333) radio.onAudioData(java.util.Arrays.copyOfRange(all, i, Math.min(all.length, i + 333)));
    }

    static byte[] gaia(int cmd, byte[] payload) {
        byte[] f = new byte[8 + payload.length];
        f[0] = (byte) 0xFF; f[1] = 0x01; f[2] = 0; f[3] = (byte) payload.length;
        f[4] = 0; f[5] = 2; f[6] = (byte) (cmd >> 8); f[7] = (byte) cmd;
        System.arraycopy(payload, 0, f, 8, payload.length);
        return f;
    }

    static byte[] channelPayload(int id, String name) {
        byte[] p = new byte[26]; // octets 4..29 de la commande
        p[0] = 0;          // statut
        p[1] = (byte) id;  // canal
        byte[] n = name.getBytes(Charset.forName("GBK"));
        System.arraycopy(n, 0, p, 16, Math.min(10, n.length)); // nom a l'offset 20 de la commande
        return p;
    }

    static class NullListener implements Radio.Listener {
        public void onStatus(String text) {}
        public void onLog(String text) {}
        public void onChannel(String name, boolean receiving) {}
        public void onAudio(short[] pcm, int len) {}
        public void onAudioEnd() {}
        public void onDisconnected() {}
    }
}
