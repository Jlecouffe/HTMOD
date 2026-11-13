package com.htmod;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Tests POCSAG, emission (SBC + trames) et reglages, sans poste. */
final class PocsagTest {
    private PocsagTest() {}

    /** Canal FM simule : pre-accentuation optionnelle, passe-haut (CTCSS), passe-bas (bande audio), bruit. */
    static short[] channel(short[] in, int fs, boolean preemph, double hpHz, double lpHz, double noise, long seed) {
        Random rnd = new Random(seed);
        double aHp = Math.exp(-2 * Math.PI * hpHz / fs), aLp = Math.exp(-2 * Math.PI * lpHz / fs);
        double hpX = 0, hpY = 0, lp = 0, prev = 0;
        short[] out = new short[in.length];
        for (int i = 0; i < in.length; i++) {
            double x = in[i] / 32768.0;
            if (preemph) { // pre-accentuation 750 us
                double tau = 750e-6 * fs;
                double y = (x + tau * (x - prev)) / (1 + tau);
                prev = x;
                x = y * 3;
            }
            double hp = aHp * (hpY + x - hpX);
            hpX = x;
            hpY = hp;
            lp = lp * aLp + hp * (1 - aLp);
            double y = lp + noise * (rnd.nextDouble() * 2 - 1);
            out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(y * 20000)));
        }
        return out;
    }

    static List<Pocsag.Message> decode(short[] s, int fs) {
        List<Pocsag.Message> got = new ArrayList<>();
        Pocsag d = new Pocsag(fs, got::add);
        for (int i = 0; i < s.length; i += 640) d.process(s, i, Math.min(640, s.length - i));
        d.flush();
        return got;
    }

    /** PCM -> SBC bitpool 40 (SNR) -> trames 7E 00 -> Radio -> decodeur. */
    static List<Pocsag.Message> sbcChain(short[] pcm, int fs) {
        List<Pocsag.Message> got = new ArrayList<>();
        Pocsag dec = new Pocsag(fs, got::add);
        Radio radio = new Radio(new java.io.File("x"), new WinTest.NullListener() {
            @Override public void onAudio(short[] p, int len) { dec.process(p, 0, len); }
        });
        SbcEncoder enc = new SbcEncoder(1, 16, 8, 40, Sbc.ALLOC_SNR);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int p = 0; p + 128 * 3 <= pcm.length; p += 128 * 3) {
            ByteArrayOutputStream sbc = new ByteArrayOutputStream();
            for (int k = 0; k < 3; k++) { byte[] f = enc.encode(pcm, p + k * 128); sbc.write(f, 0, f.length); }
            byte[] fr = Transmitter.frame(sbc.toByteArray());
            stream.write(fr, 0, fr.length);
        }
        byte[] all = stream.toByteArray();
        for (int i = 0; i < all.length; i += 500) radio.onAudioData(java.util.Arrays.copyOfRange(all, i, Math.min(all.length, i + 500)));
        dec.flush();
        return got;
    }

    static Wav readWav(java.io.File dir, String name) {
        try { return Wav.read(new java.io.File(dir, name)); } catch (java.io.IOException e) { return null; }
    }

    static void run(java.io.File data) {
        System.out.println("POCSAG :");
        int fs = 32000;
        WinTest.check("SYNC et IDLE sont des mots BCH valides",
                Pocsag.encodeCodeword(Pocsag.SYNC >>> 11) == Pocsag.SYNC && Pocsag.encodeCodeword(Pocsag.IDLE >>> 11) == Pocsag.IDLE);
        int cw = Pocsag.encodeCodeword(0x12345);
        boolean bch = true;
        for (int i = 0; i < 32; i++) for (int j = i + 1; j < 32; j++) {
            int[] r = Pocsag.correct(cw ^ (1 << i) ^ (1 << j));
            bch &= r != null && r[0] == cw && r[1] == 2;
        }
        WinTest.check("correction BCH de toutes les erreurs de 2 bits", bch);

        String text = "HTMOD POCSAG TEST 1234 abc";
        for (int baud : Pocsag.BAUDS) {
            for (boolean inv : new boolean[]{false, true}) {
                short[] pcm = Pocsag.waveform(Pocsag.bitstream(Pocsag.codewords(1234567, 3, Pocsag.alphaPayload(text))), baud, fs, 0.8, inv, false);
                List<Pocsag.Message> m = decode(channel(pcm, fs, false, 300, 3000, 0.05, 1), fs);
                WinTest.check(baud + " bd" + (inv ? " inversé" : "") + " par canal filtré bruité",
                        m.size() == 1 && m.get(0).address == 1234567 && m.get(0).function == 3 && text.equals(m.get(0).text()));
            }
        }
        for (int baud : new int[]{512, 1200}) {
            short[] pcm = Pocsag.waveform(Pocsag.bitstream(Pocsag.codewords(800008, 3, Pocsag.alphaPayload(text))), baud, fs, 0.8, false, true);
            List<Pocsag.Message> m = decode(channel(pcm, fs, true, 300, 3000, 0.05, 2), fs);
            WinTest.check(baud + " bd, pré-compensation face à une pré-accentuation", m.size() == 1 && text.equals(m.get(0).text()));
        }
        List<Pocsag.Message> nm = decode(channel(Pocsag.waveform(Pocsag.bitstream(Pocsag.codewords(2001, 0, Pocsag.numericPayload("0612345678"))),
                1200, fs, 0.8, false, false), fs, false, 300, 3000, 0.05, 4), fs);
        WinTest.check("message numérique", nm.size() == 1 && nm.get(0).isNumeric && "0612345678".equals(nm.get(0).text()));

        // Certains reseaux encodent leur texte alpha dans une charge affichee « numerique » par le pager.
        // Ce sont deux captures reellement lues sur le meme ecran : la premiere a un en-tete de 5 bits.
        String rawProtec = "849146527*028*4(94" + "-2)014-20507 3)439" + "()U*384)() *3-3-56";
        Pocsag.AlphaDecode protec = Pocsag.decodeNumericSymbolsAsAlpha(rawProtec);
        WinTest.check("trame numérique réinterprétée avec en-tête", protec.bitOffset == 5
                && protec.text.contains("PROTEC - Accid") && protec.corrupt == 3);
        String rawIntervention = "3-76214(2094U83 22" + "49*9 7*31494725U82" + "5U61-94-4)3-80";
        Pocsag.AlphaDecode intervention = Pocsag.decodeNumericSymbolsAsAlpha("3-76214(2094U83 22\n49*9 7*31494725U82\n5U61-94-4)3-80");
        WinTest.check("trame numérique réinterprétée en alpha", intervention.bitOffset == 0
                && "SOI / RECEPTION INTERVENTIOF".equals(intervention.text) && intervention.corrupt == 0);
        Pocsag.Message autoAlpha = Pocsag.buildMessage(21307, 0, 1200, Pocsag.numericPayload(rawIntervention), 0, 0);
        WinTest.check("détection automatique d'une charge alpha affichée numérique", !autoAlpha.isNumeric
                && "SOI / RECEPTION INTERVENTIOF".equals(autoAlpha.text()));

        // --- POCSAG reel recu par un Radtel RT-660 (audio Bluetooth enregistre, canal en audio plat),
        //     emis par un PlutoSDR a 4,5 kHz d'excursion (3,5 kHz pour la derniere trame)
        String[][] reel = {
            {"poste_rt660_1200_hello.wav", "1200", "1234567", "HELLO POCSAG HTMOD"},
            {"poste_rt660_1200_numerique.wav", "1200", "1234567", "0612345678"},
            {"poste_rt660_1200_dev3500.wav", "1200", "1234567", "DEVIATION 3500"},
            {"poste_rt660_512.wav", "512", "1234560", "TEST 512 BAUDS"},
            {"poste_rt660_2400.wav", "2400", "1234560", "COURT 2400"}};
        for (String[] c : reel) {
            Wav w = readWav(data, c[0]);
            if (w == null) { WinTest.check("enregistrement " + c[0] + " present", false); continue; }
            List<Pocsag.Message> m = decode(w.pcm, w.rate);
            boolean good = false;
            for (Pocsag.Message x : m) good |= x.baud == Integer.parseInt(c[1]) && x.address == Integer.parseInt(c[2]) && c[3].equals(x.text());
            WinTest.check("RT-660 réel " + c[1] + " bd : « " + c[3] + " » (" + (m.isEmpty() ? "rien" : m.get(0).demod + ", " + m.size() + " message(s)") + ")",
                    good && m.size() == 1);
        }
        for (String f : new String[]{"poste_rt660_bruit.wav", "poste_rt660_aleatoire.wav"}) {
            Wav w = readWav(data, f);
            WinTest.check("RT-660 réel : aucun message sur " + f, w != null && decode(w.pcm, w.rate).isEmpty());
        }
        // texte alpha correct qu'un decalage de bits faisait afficher illisible
        String tricky = "GG   4L-/L6E3THO637P4";
        Pocsag.Message tm0 = Pocsag.buildMessage(889344, 3, 512, Pocsag.alphaPayload(tricky), 0, 0);
        WinTest.check("alignement alpha standard conservé", tricky.equals(tm0.text()));

        // --- AFSK 1200/2200 Hz : POCSAG porte dans la bande 300-3100 Hz des postes Benshi
        for (int baud : Pocsag.AFSK_BAUDS) {
            for (boolean inv : new boolean[]{false, true}) {
                short[] pcm = Pocsag.waveformAfsk(Pocsag.bitstream(Pocsag.codewords(1234567, 3, Pocsag.alphaPayload(text))), baud, fs, 0.8, inv);
                List<Pocsag.Message> m = decode(channel(pcm, fs, false, 300, 3000, 0.05, 11), fs);
                WinTest.check("AFSK " + baud + " bd" + (inv ? " inversé" : "") + " par canal filtré bruité",
                        m.size() == 1 && m.get(0).afsk && m.get(0).address == 1234567 && text.equals(m.get(0).text()));
            }
            // passe-haut 300 Hz double (pente du RT-660 : 256 Hz a -40 dB), bruit plus fort
            short[] pcm = Pocsag.waveformAfsk(Pocsag.bitstream(Pocsag.codewords(800008, 3, Pocsag.alphaPayload(text))), baud, fs, 0.8, false);
            List<Pocsag.Message> m = decode(channel(channel(pcm, fs, false, 300, 3100, 0, 12), fs, false, 300, 3100, 0.15, 13), fs);
            WinTest.check("AFSK " + baud + " bd, canal Benshi raide et bruité", m.size() == 1 && m.get(0).afsk && text.equals(m.get(0).text()));
            m = decode(channel(pcm, fs, true, 300, 3000, 0.05, 14), fs);
            WinTest.check("AFSK " + baud + " bd, pré-accentuation non compensée", m.size() == 1 && m.get(0).afsk && text.equals(m.get(0).text()));
        }
        // fonction 0 portant du texte 7 bits (comme po_tx_f0txt.py du RT-1000Pro), en AFSK
        String f0 = "HELLO POGSAT NUM";
        List<Pocsag.Message> f0m = decode(channel(Pocsag.waveformAfsk(Pocsag.bitstream(Pocsag.codewords(1234567, 0, Pocsag.alphaPayload(f0))),
                512, fs, 0.8, false), fs, false, 300, 3000, 0.05, 15), fs);
        WinTest.check("AFSK 512 bd, texte en fonction 0 reconnu", f0m.size() == 1 && f0m.get(0).function == 0
                && !f0m.get(0).isNumeric && f0.equals(f0m.get(0).text()));

        List<Pocsag.Message> tm = decode(channel(Pocsag.waveform(Pocsag.bitstream(Pocsag.codewords(2002, 1, new ArrayList<>())),
                512, fs, 0.8, false, false), fs, false, 300, 3000, 0.05, 5), fs);
        WinTest.check("bip seul", tm.size() == 1 && tm.get(0).toneOnly && tm.get(0).address == 2002);
        short[] noise = new short[fs * 20];
        Random r = new Random(7);
        for (int i = 0; i < noise.length; i++) noise[i] = (short) ((r.nextDouble() * 2 - 1) * 8000);
        WinTest.check("aucune fausse détection sur 20 s de bruit", decode(noise, fs).isEmpty());

        // --- chaine d'emission : POCSAG -> SBC bitpool 40 (SNR) -> trames 7E 00 -> Radio -> decodeur
        short[] pcm = Pocsag.waveform(Pocsag.bitstream(Pocsag.codewords(424242, 3, Pocsag.alphaPayload(text))), 1200, fs, 0.8, false, false);
        List<Pocsag.Message> got = sbcChain(pcm, fs);
        WinTest.check("émission : trames SBC (bitpool 40) relues et décodées → RIC 424242",
                got.size() == 1 && got.get(0).address == 424242 && text.equals(got.get(0).text()));
        short[] apcm = Pocsag.waveformAfsk(Pocsag.bitstream(Pocsag.codewords(434343, 0, Pocsag.alphaPayload(f0))), 512, fs, 0.8, false);
        got = sbcChain(apcm, fs);
        WinTest.check("émission AFSK 512 bd F0 : trames SBC relues et décodées → RIC 434343",
                got.size() == 1 && got.get(0).afsk && got.get(0).address == 434343 && f0.equals(got.get(0).text()));

        // --- reglages : canaux A/B et double veille
        int[] seen = {-1, -1, -1};
        Radio rs = new Radio(new java.io.File("x"), new WinTest.NullListener() {
            @Override public void onSettings(boolean dual, int a, int b) { seen[0] = dual ? 1 : 0; seen[1] = a; seen[2] = b; }
        });
        byte[] payload = new byte[22];
        payload[0] = 0;                 // etat
        payload[1] = (byte) 0x2B;       // canal A = 2 (+ poids fort), canal B = 11
        payload[2] = (byte) 0x15;       // double veille = 1, squelch = 5
        payload[10] = (byte) 0x10;      // poids forts : A += 16 -> 18, B += 0
        rs.onCtrlData(WinTest.gaia(0x800A, payload));
        WinTest.check("réglages : double veille " + seen[0] + ", VFO A canal " + seen[1] + ", VFO B canal " + seen[2],
                seen[0] == 1 && seen[1] == 18 && seen[2] == 11);
    }
}
