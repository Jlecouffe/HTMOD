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
 *
 * Reception du POCSAG reel par un poste Benshi (RT-660 mesure, canal en audio plat) : la voie audio
 * coupe tout sous 290 Hz, resonne et ecrete ; le decodeur ci-dessus n'y decode rien. Un detecteur de
 * Viterbi par debit (PosteDetector) simule le poste a partir de sa reponse mesuree.
 * Un meme message decode par plusieurs demodulateurs n'est publie qu'une fois (le plus propre), et un
 * lot n'est publie que si la synchro suivante confirme sa polarite.
 *
 * Variante AFSK (entre postes Benshi) : les memes trames sur une sous-porteuse a phase continue
 * (bit 1 = 1200 Hz, bit 0 = 2200 Hz), dans la bande du poste ; correlateurs quadratiques sur un
 * bit. Un pager du commerce n'entend pas l'AFSK : il faut HTMod (ou equivalent) des deux cotes.
 */
package com.htmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Pocsag {
    static final int[] BAUDS = {512, 1200, 2400};
    /** Debits AFSK : a 2400 bauds, la fenetre d'un bit est trop courte pour separer 1200 et 2200 Hz. */
    static final int[] AFSK_BAUDS = {512, 1200};
    static final double AFSK_MARK = 1200, AFSK_SPACE = 2200;
    static final int SYNC = 0x7CD215D8;
    static final int IDLE = 0x7A89C197;
    /** Polynome generateur BCH(31,21) : x^10+x^9+x^8+x^6+x^5+x^3+1. */
    private static final int GEN = 0x769;
    private static final String NUMERIC_CHARS = "0123456789*U -)(";

    /** Un message recu. */
    static final class Message {
        int address, function, baud;
        String numeric, alpha;
        boolean isNumeric, toneOnly, afsk;
        int corrected, badCodewords;
        /** Demodulateur qui a decode le message (journal, mise au point). */
        String demod = "NRZ";
        /** Nombre de demodulateurs ayant decode exactement ce message. */
        int votes = 1;
        /** Bits corriges dans le mot d'adresse. */
        int addrFix;
        /** Mots de 20 bits du champ message. */
        List<Integer> payload = new ArrayList<>();

        String text() { return isNumeric ? numeric : alpha; }
    }

    interface Listener { void onMessage(Message m); }

    // ------------------------------------------------------------------ Decodeur

    /** Familles de demodulateurs : NRZ (audio plat, fichiers WAV), poste Benshi (Viterbi), AFSK. */
    private static final int NRZ = 0, POSTE = 1, AFSK = 2;

    private final Listener listener;
    private final BaudDemod[] demods;
    private float dcPrevX, dcPrevY;

    /** Un meme message est souvent decode par plusieurs demodulateurs : on garde le meilleur. */
    private final List<Message> pending = new ArrayList<>();
    private final List<Long> pendingAt = new ArrayList<>();
    private final long hold;
    private long now;

    Pocsag(int sampleRate, Listener l) {
        listener = l;
        hold = sampleRate; // 1 s
        List<BaudDemod> ds = new ArrayList<>();
        for (int b : BAUDS) ds.add(new BaudDemod(sampleRate, b, NRZ, null, null));
        for (int b : BAUDS) ds.add(new BaudDemod(sampleRate, b, POSTE, null, new PosteDetector(sampleRate, b)));
        for (int b : AFSK_BAUDS) ds.add(new BaudDemod(sampleRate, b, AFSK, new AfskFront(sampleRate, b), null));
        demods = ds.toArray(new BaudDemod[0]);
    }

    void process(short[] buf, int off, int n) {
        for (int i = off; i < off + n; i++) {
            float x = buf[i];
            float y = x - dcPrevX + 0.999f * dcPrevY; // passe-haut ~7 Hz
            dcPrevX = x;
            dcPrevY = y;
            for (BaudDemod d : demods) {
                switch (d.kind) {
                    case AFSK: d.sample(d.afsk.next(y)); break;
                    default: d.sample(y);
                }
            }
            now++;
        }
        release(false);
    }

    /** Debits actuellement synchronises (affichage d'etat), « 1200 AFSK » pour la sous-porteuse. */
    List<String> syncedBauds() {
        List<String> r = new ArrayList<>();
        for (BaudDemod d : demods) {
            String s = d.baud + (d.kind == AFSK ? " AFSK" : "");
            if (d.synced && !r.contains(s)) r.add(s);
        }
        return r;
    }

    /** Demodulateurs ayant trouve la synchro POCSAG depuis le dernier appel (diagnostic de reception). */
    private final java.util.Set<String> syncSeen = new java.util.LinkedHashSet<>();

    /** « 1200 bd EQ ch40, 1200 bd NRZ » ou « » ; remet la liste a zero. */
    String takeSyncSummary() {
        StringBuilder sb = new StringBuilder(); // sans String.join : aussi compile pour Android 5
        for (String x : syncSeen) sb.append(sb.length() > 0 ? ", " : "").append(x);
        String s = sb.toString();
        syncSeen.clear();
        return s;
    }

    /** Nom court du demodulateur (journal) : « NRZ », « POSTE », « AFSK ». */
    private static String demodName(int kind) {
        return kind == POSTE ? "POSTE" : kind == AFSK ? "AFSK" : "NRZ";
    }

    /** Propose un message decode ; les doublons (meme debit, RIC, fonction) sont fusionnes. */
    private void offer(Message m) {
        for (int i = 0; i < pending.size(); i++) {
            Message p = pending.get(i);
            if (p.baud == m.baud && p.address == m.address && p.function == m.function && p.afsk == m.afsk) {
                boolean same = p.text().equals(m.text()) && p.toneOnly == m.toneOnly;
                int dl = m.payload.size() - p.payload.size(); // version tronquee : un demodulateur a decroche
                if (m.badCodewords < p.badCodewords || (m.badCodewords == p.badCodewords && (dl > 0 || (dl == 0 && m.corrected < p.corrected)))) {
                    m.votes = same ? p.votes + 1 : 1;
                    pending.set(i, m);
                } else if (same) p.votes++;
                return;
            }
        }
        // meme texte sous un autre RIC : l'adresse a ete mal corrigee par l'un des demodulateurs ;
        // on garde celle qui a demande le moins de corrections
        String t = m.text();
        if (!m.toneOnly && t.length() >= 5) {
            for (int i = 0; i < pending.size(); i++) {
                Message p = pending.get(i);
                if (p.baud == m.baud && p.afsk == m.afsk && !p.toneOnly && t.equals(p.text())) {
                    if (m.corrected < p.corrected) { m.votes = p.votes + 1; pending.set(i, m); }
                    else p.votes++;
                    return;
                }
            }
        }
        pending.add(m);
        pendingAt.add(now);
    }

    private void release(boolean all) {
        while (!pending.isEmpty() && (all || now - pendingAt.get(0) >= hold)) {
            Message m = pending.remove(0);
            pendingAt.remove(0);
            // vu par un seul demodulateur et garde des mots illisibles : refuse ; bip seul obtenu par
            // correction : refuse aussi (bruit ou mots inverses corriges en fausse adresse)
            if ((m.votes == 1 && (m.badCodewords > 0 || m.addrFix >= 2)) || (m.toneOnly && m.corrected > 0)) continue;
            listener.onMessage(m);
        }
    }

    /**
     * Discriminateur AFSK : amplitude a 1200 Hz et a 2200 Hz sur une fenetre glissante d'un bit
     * (correlateurs complexes), chacune normalisee par sa propre AGC (comme Afsk1200) : la
     * pre-accentuation ou la desaccentuation du poste desequilibrent les deux tons de plusieurs dB.
     * Sortie souple positive pour un bit 1 (mark).
     */
    private static final class AfskFront {
        private final int len;
        private final double[] reM, imM, reS, imS;
        private double sReM, sImM, sReS, sImS;
        private double mPeak, mValley, sPeak, sValley;
        private double cM = 1, sM = 0, cS = 1, sS = 0;
        private final double rcM, rsM, rcS, rsS;
        private int idx, renorm;

        AfskFront(int sampleRate, int baud) {
            len = Math.max(4, (int) Math.round(sampleRate / (double) baud));
            reM = new double[len]; imM = new double[len]; reS = new double[len]; imS = new double[len];
            double wM = 2 * Math.PI * AFSK_MARK / sampleRate, wS = 2 * Math.PI * AFSK_SPACE / sampleRate;
            rcM = Math.cos(wM); rsM = Math.sin(wM); rcS = Math.cos(wS); rsS = Math.sin(wS);
        }

        float next(float x) {
            double a = x * cM, b = -x * sM, c = x * cS, d = -x * sS;
            sReM += a - reM[idx]; sImM += b - imM[idx]; sReS += c - reS[idx]; sImS += d - imS[idx];
            reM[idx] = a; imM[idx] = b; reS[idx] = c; imS[idx] = d;
            idx = (idx + 1) % len;
            double t = cM * rcM - sM * rsM; sM = sM * rcM + cM * rsM; cM = t;
            t = cS * rcS - sS * rsS; sS = sS * rcS + cS * rsS; cS = t;
            if (++renorm >= 4096) { // les oscillateurs derivent lentement en amplitude
                renorm = 0;
                double m = Math.hypot(cM, sM), s = Math.hypot(cS, sS);
                cM /= m; sM /= m; cS /= s; sS /= s;
                double r1 = 0, r2 = 0, r3 = 0, r4 = 0; // et les sommes glissantes en arrondi
                for (int k = 0; k < len; k++) { r1 += reM[k]; r2 += imM[k]; r3 += reS[k]; r4 += imS[k]; }
                sReM = r1; sImM = r2; sReS = r3; sImS = r4;
            }
            double m = Math.sqrt(sReM * sReM + sImM * sImM), s = Math.sqrt(sReS * sReS + sImS * sImS);
            // AGC : attaque rapide, relachement lent
            mPeak = m > mPeak ? m * 0.25 + mPeak * 0.75 : mPeak * 0.9995 + m * 0.0005;
            mValley = m < mValley ? m * 0.25 + mValley * 0.75 : mValley * 0.9995 + m * 0.0005;
            sPeak = s > sPeak ? s * 0.25 + sPeak * 0.75 : sPeak * 0.9995 + s * 0.0005;
            sValley = s < sValley ? s * 0.25 + sValley * 0.75 : sValley * 0.9995 + s * 0.0005;
            double mn = mPeak - mValley > 1e-6 ? (m - 0.5 * (mPeak + mValley)) / (mPeak - mValley) : 0;
            double sn = sPeak - sValley > 1e-6 ? (s - 0.5 * (sPeak + sValley)) / (sPeak - sValley) : 0;
            return (float) (mn - sn);
        }
    }

    /** Termine les messages en cours (fin de reception). */
    void flush() {
        for (BaudDemod d : demods) d.loseSync();
        release(true);
    }

    private final class BaudDemod {
        final int baud, kind;
        /** Discriminateur AFSK en amont (famille AFSK seulement). */
        final AfskFront afsk;
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

        /** Detecteur de Viterbi de la voie audio d'un poste Benshi (famille POSTE), null sinon. */
        final PosteDetector poste;

        BaudDemod(int sampleRate, int baud, int kind, AfskFront afsk, PosteDetector poste) {
            this.baud = baud;
            this.kind = kind;
            this.afsk = afsk;
            this.poste = poste;
            double spb = sampleRate / (double) baud;
            step = 1.0 / spb;
            maLen = Math.max(1, (int) Math.round(spb / 4));
            ring = new float[maLen];
            peakDecay = (float) Math.exp(-1.0 / (spb * 32));
        }

        void sample(float x) {
            if (poste != null) {
                int b = poste.next(x / 32768.0, synced);
                if (b >= 0) bit(b == 1);
                return;
            }
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
                if (Integer.bitCount(cw ^ SYNC) <= 2) { commit(); cwIndex = 0; badInBatch = 0; }
                else if (Integer.bitCount(cw ^ ~SYNC) <= 2) {
                    // polarite basculee pendant le lot : un mot BCH complemente reste valide, le lot est faux
                    discard();
                    inverted = !inverted;
                    cwIndex = 0;
                    badInBatch = 0;
                } else loseSync();
                return;
            }
            codeword(cw, cwIndex);
            cwIndex++;
        }

        private void startSync(boolean inv) {
            syncSeen.add(baud + " bd " + demodName(kind));
            synced = true;
            inverted = inv;
            bitsInCw = 0;
            cwIndex = 0;
            badInBatch = 0;
        }

        /** Messages termines dans le lot en cours : publies quand la synchro suivante confirme le lot. */
        private final List<Message> held = new ArrayList<>();

        private void commit() {
            for (Message m : held) offer(m);
            held.clear();
        }

        private void discard() {
            held.clear();
            msg = null;
        }

        void loseSync() {
            finishMessage();
            commit(); // fin de transmission : le dernier lot est valide par ses mots BCH
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
            if (cw == 0 || cw == -1) { // mots valides mais typiques d'un demodulateur bloque
                if (++badInBatch >= 6) { loseSync(); return; }
                if (msg != null) msg.bad++;
                return;
            }
            if (cw == IDLE) { finishMessage(); return; }
            if ((cw & (1 << 31)) == 0) { // mot d'adresse
                finishMessage();
                int addr = (((cw >>> 13) & 0x3FFFF) << 3) | (index / 2);
                msg = new Building(addr, (cw >>> 11) & 3);
                msg.corrected += fix[1];
                msg.addrFix = fix[1];
            } else if (msg != null) {
                msg.corrected += fix[1];
                msg.payload.add((cw >>> 11) & 0xFFFFF);
            }
        }

        private void finishMessage() {
            Building m = msg;
            if (m == null) return;
            msg = null;
            Message out = buildMessage(m.address, m.function, baud, m.payload, m.corrected, m.bad);
            out.addrFix = m.addrFix;
            out.afsk = kind == AFSK;
            out.demod = demodName(kind);
            held.add(out);
        }
    }

    private static final class Building {
        final int address, function;
        final List<Integer> payload = new ArrayList<>();
        int corrected, bad, addrFix;

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

    /** Symboles du jeu POCSAG numerique, sans supprimer les espaces de bourrage. */
    private static String numericSymbols(List<Integer> payload) {
        StringBuilder sb = new StringBuilder();
        for (int w : payload) for (int k = 4; k >= 0; k--) sb.append(NUMERIC_CHARS.charAt(reverseBits((w >>> (k * 4)) & 0xF, 4)));
        return sb.toString();
    }

    static String decodeNumeric(List<Integer> payload) {
        return numericSymbols(payload).trim();
    }

    /**
     * Resultat d'une reinterpretation du jeu numerique comme texte alpha.
     *
     * Certains reseaux transportent de l'ASCII 7 bits dans une charge que leur pager affiche
     * en mode « numerique ». Le decalage retenu est indique pour rendre le resultat verifiable ;
     * le caractere · represente un octet non imprimable inattendu.
     */
    static final class AlphaDecode {
        final String text;
        final int bitOffset, printable, corrupt;
        /** Proposition issue d'une seule correction de symbole, jamais appliquee silencieusement. */
        final String suggestedText, correctedSymbols;
        final int correctedSymbol;

        AlphaDecode(String text, int bitOffset, int printable, int corrupt) {
            this(text, bitOffset, printable, corrupt, null, null, -1);
        }

        AlphaDecode(String text, int bitOffset, int printable, int corrupt, String suggestedText, String correctedSymbols, int correctedSymbol) {
            this.text = text;
            this.bitOffset = bitOffset;
            this.printable = printable;
            this.corrupt = corrupt;
            this.suggestedText = suggestedText;
            this.correctedSymbols = correctedSymbols;
            this.correctedSymbol = correctedSymbol;
        }

        boolean hasSuggestion() { return suggestedText != null; }
    }

    private static final class AlphaCandidate {
        final String text;
        final int printable, corrupt, score;

        AlphaCandidate(String text, int printable, int corrupt, int score) {
            this.text = text;
            this.printable = printable;
            this.corrupt = corrupt;
            this.score = score;
        }
    }

    /** Flux de bits d'une suite de mots POCSAG, dans l'ordre du champ message. */
    private static String payloadBits(List<Integer> payload) {
        StringBuilder bits = new StringBuilder(payload.size() * 20);
        for (int w : payload) for (int b = 19; b >= 0; b--) bits.append(((w >>> b) & 1) == 0 ? '0' : '1');
        return bits.toString();
    }

    /** Decode une position donnee d'un flux alpha 7 bits (bits de caractere LSB d'abord). */
    private static AlphaCandidate alphaCandidate(String bits, int offset) {
        StringBuilder sb = new StringBuilder();
        int printable = 0, corrupt = 0, word = 0, words = 0;
        for (int p = offset; p + 7 <= bits.length(); p += 7) {
            int v = 0;
            for (int b = 0; b < 7; b++) v = (v << 1) | (bits.charAt(p + b) - '0');
            int c = reverseBits(v, 7);
            if (c == 10) {
                sb.append('\n');
                printable++;
                if (word >= 2) words += word * word;
                word = 0;
            } else if (c >= 32 && c <= 126) {
                sb.append((char) c);
                printable++;
                if (Character.isLetter(c)) word++;
                else {
                    if (word >= 2) words += word * word;
                    word = 0;
                }
            } else if (c != 0 && c != 3 && c != 4 && c != 0x17 && c != 13) {
                sb.append('·');
                corrupt++;
                if (word >= 2) words += word * word;
                word = 0;
            } else {
                if (word >= 2) words += word * word;
                word = 0;
            }
        }
        if (word >= 2) words += word * word;
        int end = sb.length();
        while (end > 0 && Character.isWhitespace(sb.charAt(end - 1))) end--;
        String text = sb.substring(0, end);
        // Les longues sequences de lettres et les caracteres imprimables distinguent tres bien
        // un texte d'un mauvais alignement de bits, tout en pénalisant les octets abimés.
        int score = printable * 3 + words - corrupt * 10 - (text.isEmpty() ? 30 : 0);
        return new AlphaCandidate(text, printable, corrupt, score);
    }

    /** Essaie les sept alignements possibles d'un flux ASCII 7 bits. */
    private static AlphaDecode decodeAlphaAutoBits(String bits) {
        AlphaCandidate best = null;
        int bestOffset = 0;
        for (int offset = 0; offset < 7; offset++) {
            AlphaCandidate c = alphaCandidate(bits, offset);
            if (best == null || c.score > best.score) {
                best = c;
                bestOffset = offset;
            }
        }
        return new AlphaDecode(best.text, bestOffset, best.printable, best.corrupt);
    }

    /** Flux de bits issu des symboles affiches en mode numerique. */
    private static String numericSymbolsBits(String symbols) {
        StringBuilder bits = new StringBuilder(symbols.length() * 4);
        for (int i = 0; i < symbols.length(); i++) {
            int transmitted = reverseBits(NUMERIC_CHARS.indexOf(symbols.charAt(i)), 4);
            for (int b = 3; b >= 0; b--) bits.append(((transmitted >>> b) & 1) == 0 ? '0' : '1');
        }
        return bits.toString();
    }

    /**
     * Petit score de vraisemblance pour les libelles francais courants de radiomessagerie.
     * Il ne sert qu'a proposer une correction d'un seul symbole recopie depuis un ecran, jamais
     * a changer le message recu automatiquement.
     */
    private static int operationalTextScore(String text) {
        String s = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String word : new String[]{"mission", "intervention", "relev", "accid", "reception", "protec"}) {
            if (s.contains(word)) score += 50;
        }
        return score;
    }

    /**
     * Cherche une substitution unique de symbole qui rend un texte nettement plus plausible.
     * Une photo peut confondre U et 1, ou une parenthese et un chiffre ; sans les mots BCH
     * originaux, une proposition explicite est plus sure qu'une correction cachee.
     */
    private static AlphaDecode suggestOneSymbolFix(String symbols, AlphaDecode original) {
        int originalScore = operationalTextScore(original.text);
        AlphaDecode best = null;
        String bestSymbols = null;
        int bestIndex = -1, bestScore = originalScore;
        char[] chars = symbols.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char was = chars[i];
            for (int n = 0; n < NUMERIC_CHARS.length(); n++) {
                char replacement = NUMERIC_CHARS.charAt(n);
                if (replacement == was) continue;
                chars[i] = replacement;
                AlphaDecode candidate = decodeAlphaAutoBits(numericSymbolsBits(new String(chars)));
                int score = operationalTextScore(candidate.text);
                // Une proposition ne doit pas masquer une degradation marquee de la trame.
                if (score > bestScore && candidate.printable >= original.printable - 1 && candidate.corrupt <= original.corrupt + 1) {
                    best = candidate;
                    bestSymbols = new String(chars);
                    bestIndex = i;
                    bestScore = score;
                }
            }
            chars[i] = was;
        }
        if (best == null || bestScore < originalScore + 50) return original;
        return new AlphaDecode(original.text, original.bitOffset, original.printable, original.corrupt,
                best.text, bestSymbols, bestIndex);
    }

    /**
     * Reinterprete une chaine affichee par un pager en « numerique » comme un flux alpha.
     * Les retours ligne servent seulement a mettre en forme une copie depuis l'ecran et sont ignores ;
     * les espaces ordinaires restent des symboles POCSAG (valeur 0xC).
     */
    static AlphaDecode decodeNumericSymbolsAsAlpha(String symbols) {
        if (symbols == null) throw new IllegalArgumentException("trame absente");
        StringBuilder normalized = new StringBuilder(symbols.length());
        int used = 0;
        for (int i = 0; i < symbols.length(); i++) {
            char c = symbols.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') continue;
            if (c == '\u00a0') c = ' ';
            c = Character.toUpperCase(c);
            int n = NUMERIC_CHARS.indexOf(c);
            if (n < 0) throw new IllegalArgumentException("symbole POCSAG numerique invalide : " + symbols.charAt(i));
            normalized.append(c);
            used++;
        }
        if (used == 0) throw new IllegalArgumentException("trame vide");
        AlphaDecode direct = decodeAlphaAutoBits(numericSymbolsBits(normalized.toString()));
        return suggestOneSymbolFix(normalized.toString(), direct);
    }

    static String decodeAlpha(List<Integer> payload) {
        return alphaCandidate(payloadBits(payload), 0).text;
    }

    /**
     * Version automatique, utile lorsque le reseau place un petit en-tete avant le texte. L'alignement
     * standard est garde s'il ne contient aucun octet non imprimable : sinon un decalage peut sortir
     * un meilleur score sur un texte correct (chiffres et espaces) et l'afficher illisible.
     */
    private static AlphaDecode decodeAlphaAuto(List<Integer> payload) {
        String bits = payloadBits(payload);
        AlphaCandidate std = alphaCandidate(bits, 0);
        if (std.corrupt == 0 && !std.text.isEmpty()) return new AlphaDecode(std.text, 0, std.printable, 0);
        return decodeAlphaAutoBits(bits);
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
        m.alpha = decodeAlphaAuto(payload).text;
        m.isNumeric = guessNumeric(function, m.numeric, m.alpha);
        m.toneOnly = payload.isEmpty();
        m.payload = new ArrayList<>(payload);
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
            if (n < 0) throw new IllegalArgumentException(Tr.t("caractère non numérique : ") + c);
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

    /**
     * Forme d'onde AFSK : sous-porteuse a phase continue (bit 1 = 1200 Hz, bit 0 = 2200 Hz, echange si
     * invert), dans la bande 300-3100 Hz de la voie audio des postes Benshi. Rampes de 2 ms aux bords,
     * 150 ms de silence avant et apres.
     */
    static short[] waveformAfsk(boolean[] bits, int baud, int rate, double level, boolean invert) {
        double spb = rate / (double) baud;
        int lead = rate * 150 / 1000;
        int body = (int) Math.ceil(bits.length * spb);
        int ramp = rate * 2 / 1000;
        short[] out = new short[lead * 2 + body];
        double phase = 0;
        for (int i = 0; i < body; i++) {
            int bi = Math.min(bits.length - 1, (int) (i / spb));
            double f = bits[bi] != invert ? AFSK_MARK : AFSK_SPACE;
            phase += 2 * Math.PI * f / rate;
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
            double env = Math.min(1, Math.min(i, body - 1 - i) / (double) ramp);
            out[lead + i] = (short) Math.round(Math.sin(phase) * env * level * 32767);
        }
        return out;
    }
}
