/* HTMOD Windows : lecture / ecriture de fichiers WAV PCM 16 bits mono. */
package com.htmod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

final class Wav {
    short[] pcm;
    int rate;

    private Wav() {}

    /** Lit un WAV PCM 16 bits ; en stereo, ne garde que le canal gauche. */
    static Wav read(File file) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(file.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.limit() < 12 || bb.getInt(0) != 0x46464952 || bb.getInt(8) != 0x45564157) throw new IOException("pas un fichier WAV");
        int pos = 12, rate = 32000, channels = 1, bits = 16, dataOff = -1, dataLen = 0;
        while (pos + 8 <= bb.limit()) {
            String id = new String(bb.array(), pos, 4, "US-ASCII");
            int size = bb.getInt(pos + 4);
            if (id.equals("fmt ")) {
                channels = bb.getShort(pos + 10);
                rate = bb.getInt(pos + 12);
                bits = bb.getShort(pos + 22);
            } else if (id.equals("data")) {
                dataOff = pos + 8;
                dataLen = Math.min(size, bb.limit() - dataOff);
                break;
            }
            pos += 8 + size + (size & 1);
        }
        if (dataOff < 0) throw new IOException("pas de donnees audio");
        if (bits != 16) throw new IOException("seul le PCM 16 bits est pris en charge (fichier : " + bits + " bits)");
        int step = 2 * Math.max(1, channels);
        Wav w = new Wav();
        w.rate = rate;
        w.pcm = new short[dataLen / step];
        for (int i = 0; i < w.pcm.length; i++) w.pcm[i] = bb.getShort(dataOff + step * i);
        return w;
    }

    static void write(File f, short[] pcm, int n, int rate) throws IOException {
        int dataLen = n * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + dataLen).putInt(0x45564157).putInt(0x20746d66).putInt(16);
        b.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(dataLen);
        for (int i = 0; i < n; i++) b.putShort(pcm[i]);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(b.array());
        }
    }
}
