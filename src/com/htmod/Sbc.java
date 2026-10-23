/*
 * HTMOD Windows : decodeur SBC (Bluetooth sub-band codec), portage Java de celui de HTCommander
 * (lib/sbc/sbc_decoder.dart, Apache 2.0). Le poste envoie son audio en SBC mono 32 kHz.
 */
package com.htmod;

final class Sbc {
    static final int HEADER_SIZE = 4;
    static final int MAX_SUBBANDS = 8;
    static final int MAX_BLOCKS = 16;
    static final int MAX_SAMPLES = MAX_BLOCKS * MAX_SUBBANDS;

    static final int MODE_MONO = 0, MODE_DUAL = 1, MODE_STEREO = 2, MODE_JOINT = 3;
    static final int ALLOC_LOUDNESS = 0, ALLOC_SNR = 1;

    /** Parametres d'une trame SBC. */
    static final class Frame {
        boolean msbc;
        int frequency;   // 0 = 16 kHz, 1 = 32 kHz, 2 = 44,1 kHz, 3 = 48 kHz
        int mode = MODE_MONO;
        int allocation = ALLOC_LOUDNESS;
        int blocks, subbands, bitpool;

        int frequencyHz() {
            switch (frequency) {
                case 0: return 16000;
                case 1: return 32000;
                case 2: return 44100;
                default: return 48000;
            }
        }

        boolean isValid() {
            if (blocks < 4 || blocks > 16 || (!msbc && blocks % 4 != 0)) return false;
            if (subbands != 4 && subbands != 8) return false;
            boolean two = mode != MODE_MONO, dual = mode == MODE_DUAL, joint = mode == MODE_JOINT;
            boolean stereo = joint || mode == MODE_STEREO;
            int maxBits = ((16 * subbands * blocks) << (two ? 1 : 0)) - (HEADER_SIZE * 8)
                    - ((4 * subbands) << (two ? 1 : 0)) - (joint ? subbands : 0);
            int maxBitpool = Math.min(maxBits / (blocks << (dual ? 1 : 0)), (16 << (stereo ? 1 : 0)) * subbands);
            return bitpool <= maxBitpool;
        }

        int frameSize() {
            if (!isValid()) return 0;
            boolean two = mode != MODE_MONO, dual = mode == MODE_DUAL, joint = mode == MODE_JOINT;
            int nbits = ((4 * subbands) << (two ? 1 : 0)) + ((blocks * bitpool) << (dual ? 1 : 0)) + (joint ? subbands : 0);
            return HEADER_SIZE + ((nbits + 7) >> 3);
        }
    }

    // ------------------------------------------------------------------ Lecture de bits

    static final class Bits {
        private final byte[] data;
        private final int off, max;
        private int pos;
        private long acc;
        private int nacc;
        boolean error;

        Bits(byte[] data, int off, int size) { this.data = data; this.off = off; this.max = size; }

        int bitPosition() { return pos * 8 - nacc; }

        int get(int n) {
            if (n == 0) return 0;
            if (n < 0 || n > 32) { error = true; return 0; }
            while (nacc < n && pos < max) {
                acc = ((acc << 8) | (data[off + pos++] & 0xFF)) & 0xFFFFFFFFL;
                nacc += 8;
            }
            if (nacc < n) {
                long r = acc << (n - nacc);
                nacc = 0;
                acc = 0;
                error = true;
                return (int) (r & ((1L << n) - 1));
            }
            nacc -= n;
            int v = (int) ((acc >> nacc) & ((1L << n) - 1));
            acc &= (1L << nacc) - 1;
            return v;
        }
    }

    // ------------------------------------------------------------------ Tables

    static final int[] CRC8 = {
        0x00, 0x1d, 0x3a, 0x27, 0x74, 0x69, 0x4e, 0x53, 0xe8, 0xf5, 0xd2, 0xcf, 0x9c, 0x81, 0xa6, 0xbb,
        0xcd, 0xd0, 0xf7, 0xea, 0xb9, 0xa4, 0x83, 0x9e, 0x25, 0x38, 0x1f, 0x02, 0x51, 0x4c, 0x6b, 0x76,
        0x87, 0x9a, 0xbd, 0xa0, 0xf3, 0xee, 0xc9, 0xd4, 0x6f, 0x72, 0x55, 0x48, 0x1b, 0x06, 0x21, 0x3c,
        0x4a, 0x57, 0x70, 0x6d, 0x3e, 0x23, 0x04, 0x19, 0xa2, 0xbf, 0x98, 0x85, 0xd6, 0xcb, 0xec, 0xf1,
        0x13, 0x0e, 0x29, 0x34, 0x67, 0x7a, 0x5d, 0x40, 0xfb, 0xe6, 0xc1, 0xdc, 0x8f, 0x92, 0xb5, 0xa8,
        0xde, 0xc3, 0xe4, 0xf9, 0xaa, 0xb7, 0x90, 0x8d, 0x36, 0x2b, 0x0c, 0x11, 0x42, 0x5f, 0x78, 0x65,
        0x94, 0x89, 0xae, 0xb3, 0xe0, 0xfd, 0xda, 0xc7, 0x7c, 0x61, 0x46, 0x5b, 0x08, 0x15, 0x32, 0x2f,
        0x59, 0x44, 0x63, 0x7e, 0x2d, 0x30, 0x17, 0x0a, 0xb1, 0xac, 0x8b, 0x96, 0xc5, 0xd8, 0xff, 0xe2,
        0x26, 0x3b, 0x1c, 0x01, 0x52, 0x4f, 0x68, 0x75, 0xce, 0xd3, 0xf4, 0xe9, 0xba, 0xa7, 0x80, 0x9d,
        0xeb, 0xf6, 0xd1, 0xcc, 0x9f, 0x82, 0xa5, 0xb8, 0x03, 0x1e, 0x39, 0x24, 0x77, 0x6a, 0x4d, 0x50,
        0xa1, 0xbc, 0x9b, 0x86, 0xd5, 0xc8, 0xef, 0xf2, 0x49, 0x54, 0x73, 0x6e, 0x3d, 0x20, 0x07, 0x1a,
        0x6c, 0x71, 0x56, 0x4b, 0x18, 0x05, 0x22, 0x3f, 0x84, 0x99, 0xbe, 0xa3, 0xf0, 0xed, 0xca, 0xd7,
        0x35, 0x28, 0x0f, 0x12, 0x41, 0x5c, 0x7b, 0x66, 0xdd, 0xc0, 0xe7, 0xfa, 0xa9, 0xb4, 0x93, 0x8e,
        0xf8, 0xe5, 0xc2, 0xdf, 0x8c, 0x91, 0xb6, 0xab, 0x10, 0x0d, 0x2a, 0x37, 0x64, 0x79, 0x5e, 0x43,
        0xb2, 0xaf, 0x88, 0x95, 0xc6, 0xdb, 0xfc, 0xe1, 0x5a, 0x47, 0x60, 0x7d, 0x2e, 0x33, 0x14, 0x09,
        0x7f, 0x62, 0x45, 0x58, 0x0b, 0x16, 0x31, 0x2c, 0x97, 0x8a, 0xad, 0xb0, 0xe3, 0xfe, 0xd9, 0xc4,
    };

    static final int[] RANGE_SCALE = {
        0x0FFFFFFF, 0x05555556, 0x02492492, 0x01111111, 0x00842108, 0x00410410, 0x00204081, 0x00101010,
        0x00080402, 0x00040100, 0x00020040, 0x00010010, 0x00008004, 0x00004001, 0x00002000, 0x00001000,
    };

    static final int[] COS8 = {8192, 7568, 5793, 3135};
    static final int[] COS16 = {8192, 8035, 7568, 6811, 5793, 4551, 3135, 1598};

    static final int[][] LOUDNESS4 = {{-1, 0, 0, 0}, {-2, 0, 0, 1}, {-2, 0, 0, 1}, {-2, 0, 0, 1}};
    static final int[][] LOUDNESS8 = {
        {-2, 0, 0, 0, 0, 0, 0, 1}, {-3, 0, 0, 0, 0, 0, 1, 2}, {-4, 0, 0, 0, 0, 0, 1, 2}, {-4, 0, 0, 0, 0, 0, 1, 2},
    };

    private static int[] twice(int... v) {
        int[] r = new int[v.length * 2];
        System.arraycopy(v, 0, r, 0, v.length);
        System.arraycopy(v, 0, r, v.length, v.length);
        return r;
    }

    // Coefficients de fenetrage (virgule fixe 2.13), dupliques pour le tampon circulaire.
    static final int[][] WINDOW4 = {
        twice(0, -126, -358, -848, -4443, -9644, 4443, -848, 358, -126),
        twice(-18, -128, -670, -201, -6389, -9235, 2544, -1055, 100, -90),
        twice(-49, -61, -946, 944, -8082, -8082, 944, -946, -61, -49),
        twice(-90, 100, -1055, 2544, -9235, -6389, -201, -670, -128, -18),
    };

    static final int[][] WINDOW8 = {
        twice(0, -132, -371, -848, -4456, -9631, 4456, -848, 371, -132),
        twice(-10, -138, -526, -580, -5438, -9528, 3486, -1004, 229, -117),
        twice(-22, -131, -685, -192, -6395, -9224, 2561, -1063, 108, -97),
        twice(-36, -106, -835, 322, -7287, -8734, 1711, -1042, 12, -75),
        twice(-54, -59, -960, 959, -8078, -8078, 959, -960, -59, -54),
        twice(-75, 12, -1042, 1711, -8734, -7287, 322, -835, -106, -36),
        twice(-97, 108, -1063, 2561, -9224, -6395, -192, -685, -131, -22),
        twice(-117, 229, -1004, 3486, -9528, -5438, -580, -526, -138, -10),
    };

    static short sat16(int v) { return (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v)); }

    static int crc(Frame f, byte[] d, int off, int size) {
        int nch = f.mode != MODE_MONO ? 2 : 1;
        int nsb = f.subbands;
        int nbit = nch * nsb * 4 + (f.mode == MODE_JOINT ? nsb : 0);
        if (size < ((HEADER_SIZE * 8 + nbit + 7) >> 3)) return -1;
        int c = 0x0f;
        c = CRC8[c ^ (d[off + 1] & 0xFF)];
        c = CRC8[c ^ (d[off + 2] & 0xFF)];
        for (int i = 4; i < 4 + nbit / 8; i++) c = CRC8[c ^ (d[off + i] & 0xFF)];
        if (nbit % 8 != 0) c = ((c << 4) ^ CRC8[(c >> 4) ^ ((d[off + 4 + nbit / 8] & 0xFF) >> 4)]) & 0xFF;
        return c;
    }

    // ------------------------------------------------------------------ Etat de synthese

    private static final class State {
        int index;
        final short[][][] v = new short[2][MAX_SUBBANDS][10];

        void reset() {
            index = 0;
            for (short[][] a : v) for (short[] b : a) java.util.Arrays.fill(b, (short) 0);
        }
    }

    private final State[] states = {new State(), new State()};
    private final short[][] sbSamples = new short[2][MAX_SAMPLES];
    private final int[] sbScale = new int[2];
    private final int[][] scf = new int[2][MAX_SUBBANDS];
    private final int[][] nbits = new int[2][MAX_SUBBANDS];
    private final int[][] bitneeds = new int[2][MAX_SUBBANDS];

    void reset() {
        states[0].reset();
        states[1].reset();
    }

    /** Lit l'en-tete ; renvoie le CRC annonce, ou -1 si l'en-tete est invalide. */
    private static int header(Bits b, Frame f) {
        int sync = b.get(8);
        f.msbc = sync == 0xad;
        if (f.msbc) {
            b.get(16);
            f.frequency = 0;
            f.mode = MODE_MONO;
            f.allocation = ALLOC_LOUDNESS;
            f.subbands = 8;
            f.blocks = 15;
            f.bitpool = 26;
        } else if (sync == 0x9c) {
            f.frequency = b.get(2);
            f.blocks = (1 + b.get(2)) << 2;
            f.mode = b.get(2);
            f.allocation = b.get(1);
            f.subbands = (1 + b.get(1)) << 2;
            f.bitpool = b.get(8);
        } else {
            return -1;
        }
        int c = b.get(8);
        return f.isValid() ? c : -1;
    }

    /** Lit l'en-tete d'une trame (sans decoder) ; null si invalide. */
    static Frame probe(byte[] d, int off, int len) {
        if (len < HEADER_SIZE) return null;
        Bits b = new Bits(d, off, HEADER_SIZE);
        Frame f = new Frame();
        if (header(b, f) < 0 || b.error) return null;
        return f;
    }

    /**
     * Decode une trame situee en d[off..off+len) ; ecrit les echantillons du canal gauche dans out
     * a partir de outOff. Renvoie le nombre d'echantillons, ou -1 en cas d'erreur.
     */
    int decode(byte[] d, int off, int len, short[] out, int outOff) {
        Frame f = new Frame();
        if (len < HEADER_SIZE) return -1;
        Bits hb = new Bits(d, off, HEADER_SIZE);
        int c = header(hb, f);
        if (c < 0 || hb.error) return -1;
        int size = f.frameSize();
        if (len < size) return -1;
        if (crc(f, d, off, len) != c) return -1;

        Bits b = new Bits(d, off, size);
        b.get(HEADER_SIZE * 8);
        frameData(b, f);
        if (b.error) return -1;

        int n = f.blocks * f.subbands;
        if (out.length - outOff < n) return -1;
        synthesize(states[0], f.blocks, f.subbands, sbSamples[0], sbScale[0], out, outOff);
        if (f.mode != MODE_MONO) {
            // canal droit decode pour garder l'etat coherent, mais ignore (le poste est mono)
            short[] right = new short[n];
            synthesize(states[1], f.blocks, f.subbands, sbSamples[1], sbScale[1], right, 0);
        }
        return n;
    }

    private void frameData(Bits b, Frame f) {
        int nch = f.mode != MODE_MONO ? 2 : 1;
        int nsb = f.subbands;

        int mjoint = 0;
        if (f.mode == MODE_JOINT) {
            int v = b.get(nsb);
            if (nsb == 4) {
                mjoint = ((v & 0x02) << 1) | ((v & 0x04) >> 1) | ((v & 0x08) >> 3);
            } else {
                mjoint = ((v & 0x02) << 5) | ((v & 0x04) << 3) | ((v & 0x08) << 1) | ((v & 0x10) >> 1)
                        | ((v & 0x20) >> 3) | ((v & 0x40) >> 5) | ((v & 0x80) >> 7);
            }
        }

        for (int ch = 0; ch < 2; ch++) java.util.Arrays.fill(scf[ch], 0);
        for (int ch = 0; ch < nch; ch++)
            for (int sb = 0; sb < nsb; sb++) scf[ch][sb] = b.get(4);

        for (int ch = 0; ch < 2; ch++) java.util.Arrays.fill(nbits[ch], 0);
        bitAllocation(f, scf, nbits, 0);
        if (f.mode == MODE_DUAL) bitAllocation(f, scf, nbits, 1);

        for (int ch = 0; ch < nch; ch++) {
            int maxScf = 0;
            for (int sb = 0; sb < nsb; sb++) {
                int s = scf[ch][sb] + ((mjoint >> sb) & 1);
                if (s > maxScf) maxScf = s;
            }
            sbScale[ch] = (15 - maxScf) - (17 - 16);
        }
        if (f.mode == MODE_JOINT) sbScale[0] = sbScale[1] = Math.min(sbScale[0], sbScale[1]);

        for (int blk = 0; blk < f.blocks; blk++) {
            for (int ch = 0; ch < nch; ch++) {
                for (int sb = 0; sb < nsb; sb++) {
                    int nbit = nbits[ch][sb];
                    int idx = blk * nsb + sb;
                    if (nbit == 0) {
                        sbSamples[ch][idx] = 0;
                        continue;
                    }
                    long s = b.get(nbit);
                    s = ((s << 1) | 1) * RANGE_SCALE[nbit - 1];
                    sbSamples[ch][idx] = (short) ((s - (1L << 28)) >> (28 - ((scf[ch][sb] + 1) + sbScale[ch])));
                }
            }
        }

        for (int sb = 0; sb < nsb; sb++) {
            if (((mjoint >> sb) & 1) == 0) continue;
            for (int blk = 0; blk < f.blocks; blk++) {
                int idx = blk * nsb + sb;
                int s0 = sbSamples[0][idx], s1 = sbSamples[1][idx];
                sbSamples[0][idx] = (short) (s0 + s1);
                sbSamples[1][idx] = (short) (s0 - s1);
            }
        }

        int pad = 8 - (b.bitPosition() % 8);
        if (pad < 8) b.get(pad);
    }

    /**
     * Allocation des bits. first = 0 : canaux 0 (et 1 en stereo) ; first = 1 : second canal du
     * mode "dual channel", traite seul (comme l'appel sur la sous-liste dans le code d'origine).
     */
    private void bitAllocation(Frame f, int[][] scf, int[][] nbits, int first) {
        int[] loud = f.subbands == 4 ? LOUDNESS4[f.frequency] : LOUDNESS8[f.frequency];
        boolean stereo = f.mode == MODE_STEREO || f.mode == MODE_JOINT;
        int nsb = f.subbands;
        int nch = (stereo && first == 0) ? 2 : 1;

        int maxBitneed = 0;
        for (int c = 0; c < nch; c++) {
            int ch = first + c;
            for (int sb = 0; sb < nsb; sb++) {
                int s = scf[ch][sb];
                int need;
                if (f.allocation == ALLOC_LOUDNESS) {
                    need = s != 0 ? s - loud[sb] : -5;
                    need >>= (need > 0) ? 1 : 0;
                } else {
                    need = s;
                }
                if (need > maxBitneed) maxBitneed = need;
                bitneeds[ch][sb] = need;
            }
        }

        int bitpool = f.bitpool;
        int bitcount = 0;
        int bitslice = maxBitneed + 1;
        for (int bc = 0; bc < bitpool; ) {
            int bs = bitslice--;
            bitcount = bc;
            if (bitcount == bitpool) break;
            for (int c = 0; c < nch; c++) {
                int ch = first + c;
                for (int sb = 0; sb < nsb; sb++) {
                    int bn = bitneeds[ch][sb];
                    bc += (bn >= bs && bn < bs + 15 ? 1 : 0) + (bn == bs ? 1 : 0);
                }
            }
        }

        for (int c = 0; c < nch; c++) {
            int ch = first + c;
            for (int sb = 0; sb < nsb; sb++) {
                int nb = bitneeds[ch][sb] - bitslice;
                nbits[ch][sb] = nb < 2 ? 0 : (nb > 16 ? 16 : nb);
            }
        }

        for (int sb = 0; sb < nsb && bitcount < bitpool; sb++) {
            for (int c = 0; c < nch && bitcount < bitpool; c++) {
                int ch = first + c;
                int n = (nbits[ch][sb] > 0 && nbits[ch][sb] < 16) ? 1
                        : (bitneeds[ch][sb] == bitslice + 1 && bitpool > bitcount + 1) ? 2 : 0;
                nbits[ch][sb] += n;
                bitcount += n;
            }
        }
        for (int sb = 0; sb < nsb && bitcount < bitpool; sb++) {
            for (int c = 0; c < nch && bitcount < bitpool; c++) {
                int ch = first + c;
                int n = nbits[ch][sb] < 16 ? 1 : 0;
                nbits[ch][sb] += n;
                bitcount += n;
            }
        }
    }

    private static void synthesize(State st, int nblocks, int nsb, short[] in, int scale, short[] out, int outOff) {
        for (int blk = 0; blk < nblocks; blk++) {
            int inOff = blk * nsb;
            int o = outOff + blk * nsb;
            int dctIdx = st.index != 0 ? 10 - st.index : 0;
            int odd = dctIdx & 1;
            if (nsb == 4) {
                dct4(in, inOff, scale, st.v[odd], st.v[1 - odd], dctIdx);
                window(st.v[odd], st.index, WINDOW4, 4, out, o);
            } else {
                dct8(in, inOff, scale, st.v[odd], st.v[1 - odd], dctIdx);
                window(st.v[odd], st.index, WINDOW8, 8, out, o);
            }
            st.index = st.index < 9 ? st.index + 1 : 0;
        }
    }

    private static int rnd(int v, int shr) { return (v + (1 << (shr - 1))) >> shr; }

    private static void dct4(short[] in, int o, int scale, short[][] out0, short[][] out1, int idx) {
        int s03 = (in[o] + in[o + 3]) >> 1, d03 = (in[o] - in[o + 3]) >> 1;
        int s12 = (in[o + 1] + in[o + 2]) >> 1, d12 = (in[o + 1] - in[o + 2]) >> 1;
        int a0 = (s03 - s12) * COS8[2];
        int b1 = -(s03 + s12) << 13;
        int a1 = d03 * COS8[3] - d12 * COS8[1];
        int b0 = -d03 * COS8[1] - d12 * COS8[3];
        int shr = 12 + scale;
        a0 = rnd(a0, shr); b0 = rnd(b0, shr); a1 = rnd(a1, shr); b1 = rnd(b1, shr);
        out0[0][idx] = sat16(a0);
        out0[3][idx] = sat16(-a1);
        out0[1][idx] = sat16(a1);
        out0[2][idx] = 0;
        out1[0][idx] = sat16(-a0);
        out1[3][idx] = sat16(b0);
        out1[1][idx] = sat16(b0);
        out1[2][idx] = sat16(b1);
    }

    private static void dct8(short[] in, int o, int scale, short[][] out0, short[][] out1, int idx) {
        int s07 = (in[o] + in[o + 7]) >> 1, d07 = (in[o] - in[o + 7]) >> 1;
        int s16 = (in[o + 1] + in[o + 6]) >> 1, d16 = (in[o + 1] - in[o + 6]) >> 1;
        int s25 = (in[o + 2] + in[o + 5]) >> 1, d25 = (in[o + 2] - in[o + 5]) >> 1;
        int s34 = (in[o + 3] + in[o + 4]) >> 1, d34 = (in[o + 3] - in[o + 4]) >> 1;
        int a0 = ((s07 + s34) - (s25 + s16)) * COS16[4];
        int b3 = (-(s07 + s34) - (s25 + s16)) << 13;
        int a2 = (s07 - s34) * COS16[6] + (s25 - s16) * COS16[2];
        int b1 = (s34 - s07) * COS16[2] + (s25 - s16) * COS16[6];
        int a1 = d07 * COS16[5] - d16 * COS16[1] + d25 * COS16[7] + d34 * COS16[3];
        int b2 = -d07 * COS16[1] - d16 * COS16[3] - d25 * COS16[5] - d34 * COS16[7];
        int a3 = d07 * COS16[7] - d16 * COS16[5] + d25 * COS16[3] - d34 * COS16[1];
        int b0 = -d07 * COS16[3] + d16 * COS16[7] + d25 * COS16[1] + d34 * COS16[5];
        int shr = 12 + scale;
        a0 = rnd(a0, shr); b0 = rnd(b0, shr); a1 = rnd(a1, shr); b1 = rnd(b1, shr);
        a2 = rnd(a2, shr); b2 = rnd(b2, shr); a3 = rnd(a3, shr); b3 = rnd(b3, shr);
        out0[0][idx] = sat16(a0);
        out0[7][idx] = sat16(-a1);
        out0[1][idx] = sat16(a1);
        out0[6][idx] = sat16(-a2);
        out0[2][idx] = sat16(a2);
        out0[5][idx] = sat16(-a3);
        out0[3][idx] = sat16(a3);
        out0[4][idx] = 0;
        out1[0][idx] = sat16(-a0);
        out1[7][idx] = sat16(b0);
        out1[1][idx] = sat16(b0);
        out1[6][idx] = sat16(b1);
        out1[2][idx] = sat16(b1);
        out1[5][idx] = sat16(b2);
        out1[3][idx] = sat16(b2);
        out1[4][idx] = sat16(b3);
    }

    private static void window(short[][] in, int index, int[][] win, int n, short[] out, int o) {
        for (int i = 0; i < n; i++) {
            long s = 0; // 10 produits 16 x 16 bits : peut depasser 32 bits
            for (int j = 0; j < 10; j++) s += in[i][j] * win[i][index + j];
            long r = (s + (1 << 12)) >> 13;
            out[o + i] = sat16((int) Math.max(-32768, Math.min(32767, r)));
        }
    }
}
