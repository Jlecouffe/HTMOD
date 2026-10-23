/*
 * HTMOD : POCSAG (512 / 1200 / 2400 bauds), reception et emission, Java pur (teste sur PC).
 *
 * Decodage : portage du decodeur de Radtel Tools (PocsagDecoder.kt), a partir de l'audio demodule
 * FM du poste (PCM 32 kHz) : anti-continu -> moyenne glissante (~1/4 de bit) -> bascule a
 * hysteresis (35 % de la crete suivie) -> DPLL calee sur les transitions -> integration sur le bit
 * -> registre 32 bits -> synchro 0x7CD215D8 (ou son inverse, polarite inconnue) -> lots de 16 mots
 * -> correction BCH(31,21) jusqu'a 2 bits -> messages. Les trois debits sont decodes en parallele.
 *
 * Emission : preambule de 576 bits 1010..., lots (synchro + 16 mots), NRZ a bords adoucis, rendu
 * en PCM 32 kHz, emis par le poste (Transmitter). Option : pre-compensation de la pre-accentuation
 * du poste (filtre de desaccentuation 750 us) pour garder des paliers plats en FM.
 */
package com.htmod;

import java.util.ArrayList;
import java.util.List;

final class Pocsag {
    static final int[] BAUDS = {512, 1200, 2400};
    static final int SYNC = 0x7CD215D8;
    static final int IDLE = 0x7A89C197;
    /** Polynome generateur BCH(31,21) : x^10+x^9+x^8+x^6+x^5+x^3+1. */
    private static final int GEN = 0x769;
    private static final String NUMERIC_CHARS = "0123456789*U -)(";

    /** Un message recu. */
    static final class Message {
        int address, function, baud;
        String numeric, alpha;
        boolean isNumeric, toneOnly;
        int corrected, badCodewords;

        String text() { return isNumeric ? numeric : alpha; }
    }

    interface Listener { void onMessage(Message m); }

    // ------------------------------------------------------------------ Decodeur

    private final Listener listener;
    private final BaudDemod[] demods;
    private float dcPrevX, dcPrevY;

    Pocsag(int sampleRate, Listener l) {
        listener = l;
        demods = new BaudDemod[BAUDS.length];
        for (int i = 0; i < BAUDS.length; i++) demods[i] = new BaudDemod(sampleRate, BAUDS[i]);
    }

    void process(short[] buf, int off, int n) {
        for (int i = off; i < off + n; i++) {
            float x = buf[i];
            float y = x - dcPrevX + 0.999f * dcPrevY; // passe-haut ~7 Hz
            dcPrevX = x;
            dcPrevY = y;
            for (BaudDemod d : demods) d.sample(y);
        }
    }

    /** Debits actuellement synchronises (affichage d'etat). */
    List<Integer> syncedBauds() {
        List<Integer> r = new ArrayList<>();
        for (BaudDemod d : demods) if (d.synced) r.add(d.baud);
        return r;
    }

    /** Termine les messages en cours (fin de reception). */
    void flush() { for (BaudDemod d : demods) d.loseSync(); }

    private final class BaudDemod {
        final int baud;
        private final double step;
        private final int maLen;
        private final float[] ring;
        private int ringIdx;
        private float maSum;
        private final float peakDecay;
        private float peak;
        private boolean state;
        private double phase;
        private int integ;

        private int shift;
        volatile boolean synced;
        private boolean inverted;
        private int bitsInCw;
        private int cwIndex; // 0..15, 16 = synchro attendue
        private int badInBatch;
        private Building msg;

        BaudDemod(int sampleRate, int baud) {
            this.baud = baud;
            double spb = sampleRate / (double) baud;
            step = 1.0 / spb;
            maLen = Math.max(1, (int) Math.round(spb / 4));
            ring = new float[maLen];
            peakDecay = (float) Math.exp(-1.0 / (spb * 32));
        }

        void sample(float x) {
            maSum += x - ring[ringIdx];
            ring[ringIdx] = x;
            ringIdx = (ringIdx + 1) % maLen;
            float v = maSum / maLen;
            float a = Math.abs(v);
            peak = a > peak ? a : peak * peakDecay;
            float thr = peak * 0.35f;
            boolean ns = state ? v > -thr : v > thr;
            if (ns != state) {
                state = ns;
                double k = synced ? 0.06 : 0.2; // transition attendue en limite de bit
                phase += phase < 0.5 ? -phase * k : (1.0 - phase) * k;
            }
            integ += state ? 1 : -1;
            phase += step;
            if (phase >= 1.0) {
                phase -= 1.0;
                bit(integ > 0);
                integ = 0;
            }
        }

        private void bit(boolean b) {
            shift = (shift << 1) | (b ? 1 : 0);
            if (!synced) {
                if (Integer.bitCount(shift ^ SYNC) <= 1) startSync(false);
                else if (Integer.bitCount(shift ^ ~SYNC) <= 1) startSync(true);
                return;
            }
            if (++bitsInCw < 32) return;
            bitsInCw = 0;
            int cw = inverted ? ~shift : shift;
            if (cwIndex == 16) { // entre deux lots : synchro attendue, sinon fin de transmission
                if (Integer.bitCount(cw ^ SYNC) <= 2) { cwIndex = 0; badInBatch = 0; }
                else loseSync();
                return;
            }
            codeword(cw, cwIndex);
            cwIndex++;
        }

        private void startSync(boolean inv) {
            synced = true;
            inverted = inv;
            bitsInCw = 0;
            cwIndex = 0;
            badInBatch = 0;
        }

        void loseSync() {
            finishMessage();
            synced = false;
            cwIndex = 0;
            bitsInCw = 0;
        }

        private void codeword(int raw, int index) {
            int[] fix = correct(raw);
            if (fix == null) {
                if (++badInBatch >= 6) { loseSync(); return; } // trop d'erreurs : bruit
                if (msg != null) { msg.bad++; msg.payload.add((raw >>> 11) & 0xFFFFF); }
                return;
            }
            int cw = fix[0];
            if (cw == IDLE) { finishMessage(); return; }
            if ((cw & (1 << 31)) == 0) { // mot d'adresse
                finishMessage();
                int addr = (((cw >>> 13) & 0x3FFFF) << 3) | (index / 2);
                msg = new Building(addr, (cw >>> 11) & 3);
                msg.corrected += fix[1];
            } else if (msg != null) {
                msg.corrected += fix[1];
                msg.payload.add((cw >>> 11) & 0xFFFFF);
            }
        }

        private void finishMessage() {
            Building m = msg;
            if (m == null) return;
            msg = null;
            listener.onMessage(buildMessage(m.address, m.function, baud, m.payload, m.corrected, m.bad));
        }
    }

    private static final class Building {
        final int address, function;
        final List<Integer> payload = new ArrayList<>();
        int corrected, bad;

        Building(int a, int f) { address = a; function = f; }
    }

    // ------------------------------------------------------------------ BCH et texte

    /** Syndrome (10 bits) -> masque d'erreur (1 ou 2 bits) sur les 31 bits hauts, -1 si inconnu. */
    private static final int[] SYNDROMES = new int[1024];

    static {
        java.util.Arrays.fill(SYNDROMES, -1);
        for (int i = 0; i < 31; i++) {
            SYNDROMES[bchRem(1 << i)] = 1 << i;
            for (int j = i + 1; j < 31; j++) {
                int m = (1 << i) | (1 << j);
                int s = bchRem(m);
                if (SYNDROMES[s] == -1) SYNDROMES[s] = m;
            }
        }
    }

    private static int bchRem(int v) {
        int r = v;
        for (int i = 30; i >= 10; i--) if (((r >>> i) & 1) != 0) r ^= GEN << (i - 10);
        return r;
    }

    /** Mot de code complet (32 bits) a partir des 21 bits de donnees. */
    static int encodeCodeword(int data21) {
        int d = (data21 & 0x1FFFFF) << 10;
        int c31 = d | bchRem(d);
        return (c31 << 1) | (Integer.bitCount(c31) & 1);
    }

    /** {mot corrige, bits corriges}, ou null si plus de 2 erreurs. */
    static int[] correct(int cw) {
        int c31 = cw >>> 1;
        int s = bchRem(c31);
        int mask = s == 0 ? 0 : SYNDROMES[s];
        if (mask == -1) return null;
        int fixed31 = c31 ^ mask;
        int parity = Integer.bitCount(fixed31) & 1;
        int errors = Integer.bitCount(mask) + (parity != (cw & 1) ? 1 : 0);
        if (errors > 2) return null;
        return new int[]{(fixed31 << 1) | parity, errors};
    }

    private static int reverseBits(int v, int n) {
        int r = 0;
        for (int i = 0; i < n; i++) if (((v >>> i) & 1) != 0) r |= 1 << (n - 1 - i);
        return r;
    }

    static String decodeNumeric(List<Integer> payload) {
        StringBuilder sb = new StringBuilder();
        for (int w : payload) for (int k = 4; k >= 0; k--) sb.append(NUMERIC_CHARS.charAt(reverseBits((w >>> (k * 4)) & 0xF, 4)));
        return sb.toString().trim();
    }

    static String decodeAlpha(List<Integer> payload) {
        StringBuilder sb = new StringBuilder();
        int acc = 0, nbits = 0;
        for (int w : payload) {
            for (int b = 19; b >= 0; b--) {
                acc = (acc << 1) | ((w >>> b) & 1);
                if (++nbits == 7) {
                    int c = reverseBits(acc, 7);
                    if (c == 10) sb.append('\n');
                    else if (c >= 32 && c <= 126) sb.append((char) c);
                    else if (c != 0 && c != 3 && c != 4 && c != 0x17 && c != 13) sb.append('·');
                    acc = 0;
                    nbits = 0;
                }
            }
        }
        int e = sb.length();
        while (e > 0 && Character.isWhitespace(sb.charAt(e - 1))) e--;
        return sb.substring(0, e);
    }

    /** Choix numerique / alpha au contenu (comme le mode « auto » de multimon-ng). */
    private static boolean guessNumeric(int function, String numeric, String alpha) {
        if (alpha.isEmpty()) return true;
        int odd = 0;
        for (char c : numeric.toCharArray()) if ("0123456789 -".indexOf(c) < 0) odd++;
        double numOdd = odd / (double) Math.max(1, numeric.length());
        int bad = 0;
        for (char c : alpha.toCharArray()) if (c == '·') bad++;
        double alphaOdd = bad / (double) alpha.length();
        if (numOdd < alphaOdd) return true;
        if (numOdd > alphaOdd) return false;
        return function == 0 && numeric.length() <= 16;
    }

    static Message buildMessage(int address, int function, int baud, List<Integer> payload, int corrected, int bad) {
        Message m = new Message();
        m.address = address;
        m.function = function;
        m.baud = baud;
        m.numeric = decodeNumeric(payload);
        m.alpha = decodeAlpha(payload);
        m.isNumeric = guessNumeric(function, m.numeric, m.alpha);
        m.toneOnly = payload.isEmpty();
        m.corrected = corrected;
        m.badCodewords = bad;
        return m;
    }

    // ------------------------------------------------------------------ Encodeur (emission)

    /** Mots de donnees 20 bits d'un message alphanumerique (ASCII 7 bits, poids faible d'abord, EOT final). */
    static List<Integer> alphaPayload(String text) {
        List<Integer> bits = new ArrayList<>();
        String t = text + '\u0004';
        for (int k = 0; k < t.length(); k++) {
            int ch = t.charAt(k) & 0x7F;
            for (int i = 0; i < 7; i++) bits.add((ch >>> i) & 1);
        }
        while (bits.size() % 20 != 0) bits.add(0);
        return chunk(bits, 20);
    }

    /** Mots de donnees d'un message numerique (chiffres, espace, -, U, *, parentheses). */
    static List<Integer> numericPayload(String digits) {
        List<Integer> nibbles = new ArrayList<>();
        for (char c : digits.toCharArray()) {
            int n = NUMERIC_CHARS.indexOf(Character.toUpperCase(c));
            if (n < 0) throw new IllegalArgumentException("caractère non numérique : " + c);
            nibbles.add(n);
        }
        while (nibbles.size() % 5 != 0) nibbles.add(NUMERIC_CHARS.indexOf(' '));
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < nibbles.size(); i += 5) {
            int w = 0;
            for (int k = 0; k < 5; k++) w = (w << 4) | reverseBits(nibbles.get(i + k), 4);
            out.add(w);
        }
        return out;
    }

    private static List<Integer> chunk(List<Integer> bits, int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < bits.size(); i += n) {
            int w = 0;
            for (int k = 0; k < n; k++) w = (w << 1) | bits.get(i + k);
            out.add(w);
        }
        return out;
    }

    /** Suite de mots (synchros comprises) pour un message : l'adresse va dans la trame (RIC & 7). */
    static List<Integer> codewords(int address, int function, List<Integer> payload) {
        List<Integer> words = new ArrayList<>();
        int frame = address & 7;
        for (int i = 0; i < frame * 2; i++) words.add(IDLE);
        words.add(encodeCodeword(((address >>> 3) << 2) | (function & 3)));
        for (int p : payload) words.add(encodeCodeword((1 << 20) | p));
        words.add(IDLE);
        while (words.size() % 16 != 0) words.add(IDLE);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < words.size(); i += 16) {
            out.add(SYNC);
            out.addAll(words.subList(i, i + 16));
        }
        return out;
    }

    /** Train de bits complet : preambule 576 bits 1010..., mots, petite queue. */
    static boolean[] bitstream(List<Integer> words) {
        int n = 576 + words.size() * 32 + 32;
        boolean[] bits = new boolean[n];
        int k = 0;
        for (int i = 0; i < 576; i++) bits[k++] = i % 2 == 0;
        for (int w : words) for (int i = 31; i >= 0; i--) bits[k++] = ((w >>> i) & 1) != 0;
        for (int i = 0; i < 32; i++) bits[k++] = i % 2 == 0;
        return bits;
    }

    /**
     * Forme d'onde PCM : NRZ (1 = niveau haut, sauf si invert), bords adoucis sur ~1/8 de bit,
     * amplitude level (0..1 de la pleine echelle), pre-compensation optionnelle de la
     * pre-accentuation 750 us, 150 ms de silence avant et apres.
     */
    static short[] waveform(boolean[] bits, int baud, int rate, double level, boolean invert, boolean deemphasis) {
        double spb = rate / (double) baud;
        int lead = rate * 150 / 1000;
        int n = lead * 2 + (int) Math.ceil(bits.length * spb);
        double[] y = new double[n];
        int edge = Math.max(1, (int) Math.round(spb / 8));
        for (int i = 0; i < n - 2 * lead; i++) {
            int bi = (int) (i / spb);
            if (bi >= bits.length) break;
            double v = bits[bi] != invert ? 1 : -1;
            // bord adouci : rampe en cosinus sur les edge premiers echantillons du bit
            double pos = i - bi * spb;
            if (bi > 0 && pos < edge) {
                double prev = bits[bi - 1] != invert ? 1 : -1;
                double t = 0.5 - 0.5 * Math.cos(Math.PI * pos / edge);
                v = prev + (v - prev) * t;
            }
            y[lead + i] = v;
        }
        if (deemphasis) {
            // passe-bas 1 pole a 212 Hz (750 us), puis normalisation : la pre-accentuation du poste
            // (+6 dB/octave) redonne des paliers plats en sortie de modulateur FM
            double a = Math.exp(-2 * Math.PI * 212.0 / rate), s = 0, peak = 1e-9;
            for (int i = 0; i < n; i++) { s = s * a + y[i] * (1 - a); y[i] = s; peak = Math.max(peak, Math.abs(s)); }
            for (int i = 0; i < n; i++) y[i] /= peak;
        }
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) Math.round(Math.max(-1, Math.min(1, y[i] * level)) * 32767);
        return out;
    }
}
