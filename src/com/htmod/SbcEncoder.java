/*
 * HTMOD Windows : encodeur SBC mono (portage de lib/sbc/sbc_encoder.dart de HTCommander, Apache 2.0),
 * pour l'emission vers le poste (32 kHz, 16 blocs, 8 sous-bandes). Aller-retour verifie par WinTest.
 */
package com.htmod;

final class SbcEncoder {
    static final int[][] W4 = {
        {0, 358, 4443, -4443, -358, 0, 358, 4443, -4443, -358},
        {49, 946, 8082, -944, 61, 49, 946, 8082, -944, 61},
        {18, 670, 6389, -2544, -100, 18, 670, 6389, -2544, -100},
        {90, 1055, 9235, 201, 128, 90, 1055, 9235, 201, 128},
    };
    static final int[][] W4B = {
        {126, 848, 9644, 848, 126, 126, 848, 9644, 848, 126},
        {61, -944, 8082, 946, 49, 61, -944, 8082, 946, 49},
        {128, 201, 9235, 1055, 90, 128, 201, 9235, 1055, 90},
        {-100, -2544, 6389, 670, 18, -100, -2544, 6389, 670, 18},
    };
    static final int[][] W8 = {
        {0, 185, 2228, -2228, -185, 0, 185, 2228, -2228, -185},
        {27, 480, 4039, -480, 30, 27, 480, 4039, -480, 30},
        {5, 263, 2719, -1743, -115, 5, 263, 2719, -1743, -115},
        {58, 502, 4764, 290, 69, 58, 502, 4764, 290, 69},
        {11, 343, 3197, -1280, -54, 11, 343, 3197, -1280, -54},
        {48, 532, 4612, 96, 65, 48, 532, 4612, 96, 65},
        {18, 418, 3644, -856, -6, 18, 418, 3644, -856, -6},
        {37, 521, 4367, -161, 53, 37, 521, 4367, -161, 53},
    };
    static final int[][] W8B = {
        {66, 424, 4815, 424, 66, 66, 424, 4815, 424, 66},
        {30, -480, 4039, 480, 27, 30, -480, 4039, 480, 27},
        {69, 290, 4764, 502, 58, 69, 290, 4764, 502, 58},
        {-115, -1743, 2719, 263, 5, -115, -1743, 2719, 263, 5},
        {65, 96, 4612, 532, 48, 65, 96, 4612, 532, 48},
        {-54, -1280, 3197, 343, 11, -54, -1280, 3197, 343, 11},
        {53, -161, 4367, 521, 37, 53, -161, 4367, 521, 37},
        {-6, -856, 3644, 418, 18, -6, -856, 3644, 418, 18},
    };
    static final int[][] COSMAT8 = {
        {5793, 6811, 7568, 8035, 4551, 3135, 1598, 8192},
        {-5793, -1598, 3135, 6811, -8035, -7568, -4551, 8192},
        {-5793, -8035, -3135, 4551, 1598, 7568, 6811, 8192},
        {5793, -4551, -7568, 1598, 6811, -3135, -8035, 8192},
        {5793, 4551, -7568, -1598, -6811, -3135, 8035, 8192},
        {-5793, 8035, -3135, -4551, -1598, 7568, -6811, 8192},
        {-5793, 1598, 3135, -6811, 8035, -7568, 4551, 8192},
        {5793, -6811, 7568, -8035, -4551, 3135, -1598, 8192},
    };

    int index;
    final short[][][] x = new short[2][8][5];
    final long[] y = new long[4];
    final int blocks, subbands, bitpool, frequency, allocation;

    SbcEncoder(int frequency, int blocks, int subbands, int bitpool, int allocation) {
        this.frequency = frequency; this.blocks = blocks; this.subbands = subbands; this.bitpool = bitpool; this.allocation = allocation;
    }

    static final class BitWriter {
        final byte[] d; int pos; long acc; int nacc;
        BitWriter(byte[] d) { this.d = d; }
        void put(int v, int n) {
            if (n == 0) return;
            v &= (int) ((1L << n) - 1);
            acc = (acc << n) | v; nacc += n;
            while (nacc >= 8) { nacc -= 8; d[pos++] = (byte) (acc >> nacc); acc &= (1L << nacc) - 1; }
        }
        int bitPos() { return pos * 8 + nacc; }
        void flush() { if (nacc > 0) { d[pos++] = (byte) (acc << (8 - nacc)); nacc = 0; acc = 0; } }
    }

    static int clz(int v) { return Integer.numberOfLeadingZeros(v); }

    byte[] encode(short[] pcm, int off) {
        Sbc.Frame f = new Sbc.Frame();
        f.frequency = frequency; f.blocks = blocks; f.subbands = subbands; f.bitpool = bitpool;
        f.mode = Sbc.MODE_MONO; f.allocation = allocation;
        int size = f.frameSize();
        short[] sb = new short[Sbc.MAX_SAMPLES];
        for (int blk = 0; blk < blocks; blk++) {
            if (subbands == 4) analyze4(pcm, off + blk * 4, sb, blk * 4);
            else analyze8(pcm, off + blk * 8, sb, blk * 8);
        }
        byte[] out = new byte[size];
        BitWriter w = new BitWriter(out);
        w.put(0, 32);
        int[] scf = new int[8];
        for (int s = 0; s < subbands; s++) {
            int m = 0;
            for (int blk = 0; blk < blocks; blk++) m |= Math.abs(sb[blk * subbands + s]);
            scf[s] = m != 0 ? 31 - clz(m) : 0;
        }
        for (int s = 0; s < subbands; s++) w.put(scf[s], 4);
        int[] nbits = alloc(f, scf);
        for (int blk = 0; blk < blocks; blk++)
            for (int s = 0; s < subbands; s++) {
                int nb = nbits[s];
                if (nb == 0) continue;
                int range = (1 << nb) - 1;
                long q = ((((long) sb[blk * subbands + s] * range) >> (scf[s] + 1)) + range) >> 1;
                w.put((int) q, nb);
            }
        int pad = 8 - (w.bitPos() % 8);
        if (pad < 8) w.put(0, pad);
        w.flush();
        BitWriter h = new BitWriter(out);
        h.put(0x9c, 8); h.put(frequency, 2); h.put((blocks >> 2) - 1, 2); h.put(Sbc.MODE_MONO, 2);
        h.put(allocation, 1); h.put((subbands >> 2) - 1, 1); h.put(bitpool, 8);
        out[3] = (byte) Sbc.crc(f, out, 0, size);
        return out;
    }

    private int[] alloc(Sbc.Frame f, int[] scf) {
        int[] loud = subbands == 4 ? Sbc.LOUDNESS4[frequency] : Sbc.LOUDNESS8[frequency];
        int[] need = new int[8], nb = new int[8];
        int maxNeed = 0;
        for (int s = 0; s < subbands; s++) {
            int n;
            if (allocation == Sbc.ALLOC_LOUDNESS) { n = scf[s] != 0 ? scf[s] - loud[s] : -5; n >>= (n > 0) ? 1 : 0; }
            else n = scf[s];
            if (n > maxNeed) maxNeed = n;
            need[s] = n;
        }
        int bitcount = 0, bitslice = maxNeed + 1;
        for (int bc = 0; bc < bitpool; ) {
            int bs = bitslice--;
            bitcount = bc;
            if (bitcount == bitpool) break;
            for (int s = 0; s < subbands; s++) bc += (need[s] >= bs && need[s] < bs + 15 ? 1 : 0) + (need[s] == bs ? 1 : 0);
        }
        for (int s = 0; s < subbands; s++) { int n = need[s] - bitslice; nb[s] = n < 2 ? 0 : (n > 16 ? 16 : n); }
        for (int s = 0; s < subbands && bitcount < bitpool; s++) {
            int n = (nb[s] > 0 && nb[s] < 16) ? 1 : (need[s] == bitslice + 1 && bitpool > bitcount + 1) ? 2 : 0;
            nb[s] += n; bitcount += n;
        }
        for (int s = 0; s < subbands && bitcount < bitpool; s++) { int n = nb[s] < 16 ? 1 : 0; nb[s] += n; bitcount += n; }
        return nb;
    }

    private static short sat(long v) { return (short) Math.max(-32768, Math.min(32767, v)); }

    private void analyze4(short[] in, int o, short[] out, int oo) {
        int idx = index >> 1, odd = index & 1, inIdx = idx != 0 ? 5 - idx : 0;
        x[odd][0][inIdx] = in[o + 3]; x[odd][1][inIdx] = in[o + 1]; x[odd][2][inIdx] = in[o + 2]; x[odd][3][inIdx] = in[o];
        long y0 = 0, y1 = 0, y2, y3 = 0;
        for (int j = 0; j < 5; j++) {
            y0 += x[odd][0][j] * W4[0][idx + j];
            y1 += x[odd][2][j] * W4[2][idx + j] + x[odd][3][j] * W4[3][idx + j];
            y3 += x[odd][1][j] * W4[1][idx + j];
        }
        y0 += y[0]; y[0] = 0;
        for (int j = 0; j < 5; j++) y[0] += x[odd][0][j] * W4B[0][idx + j];
        y2 = y[1]; y[1] = 0;
        for (int j = 0; j < 5; j++) y[1] += x[odd][2][j] * W4B[2][idx + j] - x[odd][3][j] * W4B[3][idx + j];
        short[] yy = {sat((y0 + (1 << 14)) >> 15), sat((y1 + (1 << 14)) >> 15), sat((y2 + (1 << 14)) >> 15), sat((y3 + (1 << 14)) >> 15)};
        index = index < 9 ? index + 1 : 0;
        int[] c = Sbc.COS8;
        long s0 = yy[0] * c[2] + yy[1] * c[1] + yy[2] * c[3] + (yy[3] << 13);
        long s1 = -yy[0] * c[2] + yy[1] * c[3] - yy[2] * c[1] + (yy[3] << 13);
        long s2 = -yy[0] * c[2] - yy[1] * c[3] + yy[2] * c[1] + (yy[3] << 13);
        long s3 = yy[0] * c[2] - yy[1] * c[1] - yy[2] * c[3] + (yy[3] << 13);
        out[oo] = sat((s0 + (1 << 12)) >> 13); out[oo + 1] = sat((s1 + (1 << 12)) >> 13);
        out[oo + 2] = sat((s2 + (1 << 12)) >> 13); out[oo + 3] = sat((s3 + (1 << 12)) >> 13);
    }

    private void analyze8(short[] in, int o, short[] out, int oo) {
        int idx = index >> 1, odd = index & 1, inIdx = idx != 0 ? 5 - idx : 0;
        int[] map = {7, 3, 6, 0, 5, 1, 4, 2};
        for (int i = 0; i < 8; i++) x[odd][i][inIdx] = in[o + map[i]];
        long[] t = new long[8];
        for (int i = 0; i < 8; i++) for (int j = 0; j < 5; j++) t[i] += x[odd][i][j] * W8[i][idx + j];
        long y0 = t[0] + y[0], y1 = t[2] + t[3], y2 = t[4] + t[5], y3 = t[6] + t[7];
        long y4 = y[1], y5 = y[2], y6 = y[3], y7 = t[1];
        y[0] = y[1] = y[2] = y[3] = 0;
        for (int j = 0; j < 5; j++) {
            y[0] += x[odd][0][j] * W8B[0][idx + j];
            y[1] += x[odd][2][j] * W8B[2][idx + j] - x[odd][3][j] * W8B[3][idx + j];
            y[2] += x[odd][4][j] * W8B[4][idx + j] - x[odd][5][j] * W8B[5][idx + j];
            y[3] += x[odd][6][j] * W8B[6][idx + j] - x[odd][7][j] * W8B[7][idx + j];
        }
        long[] ys = {y0, y1, y2, y3, y4, y5, y6, y7};
        short[] yy = new short[8];
        for (int i = 0; i < 8; i++) yy[i] = sat((ys[i] + (1 << 14)) >> 15);
        index = index < 9 ? index + 1 : 0;
        for (int i = 0; i < 8; i++) {
            long s = 0;
            for (int j = 0; j < 8; j++) s += yy[j] * COSMAT8[i][j];
            out[oo + i] = sat((s + (1 << 12)) >> 13);
        }
    }
}
