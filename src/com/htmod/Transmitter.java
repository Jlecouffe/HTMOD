/*
 * HTMOD Windows : emission d'un signal audio (PCM 32 kHz) par le poste, comme HTCommander
 * (audio_engine.dart) : encodage SBC 32 kHz mono, 16 blocs, 8 sous-bandes, bitpool 40 (allocation
 * SNR pour les donnees), trames « 7E 00 <SBC echappe> 7E » sur le canal audio, cadencees en temps
 * reel avec au plus 1 s d'avance, puis trame de fin d'audio. Le poste passe en emission tout seul
 * a la reception de l'audio et la relache a la trame de fin.
 */
package com.htmod;

import java.io.ByteArrayOutputStream;

final class Transmitter {
    private static final int RATE = 32000;
    private static final int LEAD_MS = 1000;
    private static final byte[] END_FRAME = {0x7e, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7e};

    interface Listener {
        void onTxStart(double seconds);
        void onTxEnd(boolean cancelled);
    }

    private final Radio radio;
    private volatile boolean busy, cancel;

    Transmitter(Radio radio) { this.radio = radio; }

    boolean isBusy() { return busy; }

    void cancel() { cancel = true; }

    /** Emet le PCM (32 kHz) ; renvoie false si une emission est deja en cours ou le canal audio ferme. */
    synchronized boolean transmit(short[] pcm, boolean data, Listener l) {
        if (busy || !radio.audioOpen()) return false;
        busy = true;
        cancel = false;
        Thread t = new Thread(() -> run(pcm, data, l), "htmod-emission");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run(short[] pcm, boolean data, Listener l) {
        l.onTxStart(pcm.length / (double) RATE);
        SbcEncoder enc = new SbcEncoder(1, 16, 8, 40, data ? Sbc.ALLOC_SNR : Sbc.ALLOC_LOUDNESS);
        int per = 16 * 8; // echantillons par trame SBC
        long t0 = System.currentTimeMillis();
        int sent = 0;
        try {
            int pos = 0;
            while (pos + per <= pcm.length && !cancel) {
                // plusieurs trames SBC par message (< 300 octets, comme HTCommander)
                ByteArrayOutputStream sbc = new ByteArrayOutputStream();
                int start = pos;
                while (pos + per <= pcm.length && sbc.size() < 300 - 90) {
                    byte[] f = enc.encode(pcm, pos);
                    sbc.write(f, 0, f.length);
                    pos += per;
                }
                radio.sendAudio(frame(sbc.toByteArray()));
                sent += pos - start;
                long ahead = sent * 1000L / RATE - LEAD_MS - (System.currentTimeMillis() - t0);
                if (ahead > 0) Thread.sleep(Math.min(ahead, 100));
            }
            // laisse le poste jouer le tampon avant la trame de fin
            long left = sent * 1000L / RATE - (System.currentTimeMillis() - t0);
            while (left > 0 && !cancel) {
                Thread.sleep(Math.min(left, 100));
                left = sent * 1000L / RATE - (System.currentTimeMillis() - t0);
            }
        } catch (InterruptedException ignore) {
        } finally {
            radio.sendAudio(END_FRAME);
            busy = false;
            l.onTxEnd(cancel);
        }
    }

    // ------------------------------------------------------------------ PTT : micro du PC en direct

    private volatile boolean liveStop;
    private volatile int micPeak;

    /** Niveau crete du micro pendant le PTT (0..100). */
    int micLevel() { return micPeak; }

    /**
     * PTT : capture le micro (32 kHz mono) et l'emet en continu (SBC « loudness », comme la voix dans
     * HTCommander) jusqu'a stopLive() ou maxSeconds ; le poste emet tant qu'il recoit de l'audio.
     */
    synchronized boolean startLive(double gain, int maxSeconds, Listener l) {
        if (busy || !radio.audioOpen()) return false;
        javax.sound.sampled.AudioFormat fmt = new javax.sound.sampled.AudioFormat(RATE, 16, 1, true, false);
        javax.sound.sampled.TargetDataLine mic;
        try {
            mic = javax.sound.sampled.AudioSystem.getTargetDataLine(fmt);
            mic.open(fmt, RATE / 5);
        } catch (Exception e) {
            throw new IllegalStateException("micro indisponible : " + e.getMessage());
        }
        busy = true;
        cancel = false;
        liveStop = false;
        Thread t = new Thread(() -> runLive(mic, gain, maxSeconds, l), "htmod-ptt");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        return true;
    }

    void stopLive() { liveStop = true; }

    private void runLive(javax.sound.sampled.TargetDataLine mic, double gain, int maxSeconds, Listener l) {
        l.onTxStart(maxSeconds);
        SbcEncoder enc = new SbcEncoder(1, 16, 8, 40, Sbc.ALLOC_LOUDNESS);
        int per = 16 * 8;
        int chunk = per * 3; // 12 ms : 3 trames SBC par message (< 300 octets)
        byte[] raw = new byte[chunk * 2];
        short[] pcm = new short[chunk];
        long t0 = System.currentTimeMillis();
        boolean timeout = false;
        try {
            mic.start();
            while (!liveStop && !cancel) {
                if (System.currentTimeMillis() - t0 > maxSeconds * 1000L) { timeout = true; break; }
                int got = 0;
                while (got < raw.length) {
                    int r = mic.read(raw, got, raw.length - got);
                    if (r <= 0) break;
                    got += r;
                }
                int peak = 0;
                for (int i = 0; i < chunk; i++) {
                    int v = (int) Math.round((short) ((raw[2 * i] & 0xFF) | (raw[2 * i + 1] << 8)) * gain);
                    v = Math.max(-32767, Math.min(32767, v));
                    pcm[i] = (short) v;
                    peak = Math.max(peak, Math.abs(v));
                }
                micPeak = peak * 100 / 32767;
                ByteArrayOutputStream sbc = new ByteArrayOutputStream();
                for (int pos = 0; pos + per <= chunk; pos += per) {
                    byte[] f = enc.encode(pcm, pos);
                    sbc.write(f, 0, f.length);
                }
                radio.sendAudio(frame(sbc.toByteArray()));
            }
        } catch (Exception ignore) {
        } finally {
            try { mic.stop(); mic.close(); } catch (Exception ignore) {}
            micPeak = 0;
            radio.sendAudio(END_FRAME);
            busy = false;
            l.onTxEnd(timeout || cancel);
        }
    }

    /** Trame audio : 0x7E, commande 0 (audio), donnees avec echappement 0x7D, 0x7E. */
    static byte[] frame(byte[] sbc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(sbc.length + 8);
        out.write(0x7e);
        out.write(0x00);
        for (byte b : sbc) {
            int v = b & 0xFF;
            if (v == 0x7d || v == 0x7e) { out.write(0x7d); out.write(v ^ 0x20); }
            else out.write(v);
        }
        out.write(0x7e);
        return out.toByteArray();
    }
}
