/*
 * HTMOD Windows : TNC KISS externe sur port COM (TNC materiel, Mobilinkd en serie, Direwolf via
 * com0com...). Java n'a pas d'acces serie natif : btbridge.exe ouvre le port (« btbridge serial COM3
 * 9600 ») et relaie les octets sur le canal 3, avec le meme format de trames que le Bluetooth.
 * Protocole KISS : trames delimitees par FEND (C0), echappements FESC (DB) + TFEND (DC) / TFESC (DD),
 * premier octet = commande (4 bits de poids fort : port du TNC, 4 bits de poids faible : 0 = donnees).
 * Les trames de donnees sont des trames AX.25 sans FCS, comme celles du TNC du poste.
 */
package com.htmod;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class KissTnc {
    interface Listener {
        /** Trame AX.25 (sans FCS) recue du TNC. */
        void onKissFrame(byte[] ax25);
        void onKissStatus(String text, boolean connected);
        void onKissLog(String text);
    }

    static final int FEND = 0xC0, FESC = 0xDB, TFEND = 0xDC, TFESC = 0xDD;
    static final int[] BAUDS = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};

    private final File bridgeExe;
    private final Listener listener;
    private Process proc;
    private OutputStream out;
    private volatile boolean connected;
    private volatile String port = "";
    private volatile int frames;
    // decodeur KISS
    private final ByteArrayOutputStream acc = new ByteArrayOutputStream();
    private boolean inFrame, escape;

    KissTnc(File bridgeExe, Listener listener) {
        this.bridgeExe = bridgeExe;
        this.listener = listener;
    }

    /** Ports COM presents (« COM3 »...). */
    static List<String> ports(File bridgeExe) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(bridgeExe.getAbsolutePath(), "ports").redirectErrorStream(true).start();
        List<String> l = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String s;
            while ((s = r.readLine()) != null) if (s.trim().matches("COM\\d+")) l.add(s.trim());
        }
        p.waitFor();
        return l;
    }

    boolean isConnected() { return connected; }
    String port() { return port; }
    int frameCount() { return frames; }

    synchronized void connect(String comPort, int baud) throws IOException {
        disconnect();
        port = comPort;
        frames = 0;
        listener.onKissStatus(Tr.t("Ouverture de ") + comPort + " · " + baud + Tr.t(" bauds…"), false);
        proc = new ProcessBuilder(bridgeExe.getAbsolutePath(), "serial", comPort, String.valueOf(baud)).start();
        out = proc.getOutputStream();
        final Process p = proc;
        Thread rd = new Thread(() -> readLoop(p), "htmod-kiss");
        rd.setDaemon(true);
        rd.start();
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) listener.onKissLog(Tr.t("TNC KISS : ") + l);
            } catch (IOException ignore) {
            }
        }, "htmod-kiss-err");
        err.setDaemon(true);
        err.start();
    }

    synchronized void disconnect() {
        connected = false;
        if (proc != null) {
            try { out.close(); } catch (IOException ignore) {}
            proc.destroy();
            proc = null;
        }
    }

    private void readLoop(Process p) {
        try (DataInputStream in = new DataInputStream(p.getInputStream())) {
            while (true) {
                int ch = in.read();
                if (ch < 0) break;
                int len = in.readInt();
                if (len < 0 || len > 1 << 20) break;
                byte[] data = new byte[len];
                in.readFully(data);
                if (ch == 3) feed(data, data.length);
                else if (ch == 2) onEvent(new String(data, StandardCharsets.UTF_8));
            }
        } catch (IOException ignore) {
        }
        boolean was = connected;
        connected = false;
        synchronized (this) { if (proc == p) proc = null; }
        if (was) listener.onKissStatus(Tr.t("Port ") + port + Tr.t(" fermé"), false);
    }

    private void onEvent(String ev) {
        if (ev.startsWith("connected serial")) {
            connected = true;
            listener.onKissLog(Tr.t("TNC KISS : ") + ev.substring(10));
            listener.onKissStatus(Tr.t("Connecté sur ") + port + Tr.t(" · en écoute"), true);
        } else if (ev.startsWith("error serial")) {
            listener.onKissLog(Tr.t("TNC KISS : ") + ev.substring(6));
            if (!connected) listener.onKissStatus(Tr.t("Échec : ") + ev.substring(13), false);
        } else {
            listener.onKissLog(Tr.t("TNC KISS : ") + ev);
        }
    }

    /** Octets du port serie -> trames KISS (public pour les tests). */
    void feed(byte[] b, int n) {
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c == FEND) {
                if (inFrame && acc.size() > 1) frame(acc.toByteArray());
                acc.reset();
                inFrame = true;
                escape = false;
                continue;
            }
            if (!inFrame) continue;
            if (escape) {
                acc.write(c == TFEND ? FEND : c == TFESC ? FESC : c);
                escape = false;
            } else if (c == FESC) {
                escape = true;
            } else {
                acc.write(c);
            }
            if (acc.size() > 2048) { acc.reset(); inFrame = false; } // flux incoherent
        }
    }

    private void frame(byte[] k) {
        if ((k[0] & 0x0F) != 0) return; // commande autre que « donnees » (TXDELAY, retour de parametres...)
        byte[] ax25 = new byte[k.length - 1];
        System.arraycopy(k, 1, ax25, 0, ax25.length);
        frames++;
        listener.onKissFrame(ax25);
    }

    /** Trame KISS de donnees (port 0) pour une trame AX.25 sans FCS. */
    static byte[] encode(byte[] ax25) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(ax25.length + 8);
        o.write(FEND);
        o.write(0x00);
        for (byte x : ax25) {
            int c = x & 0xFF;
            if (c == FEND) { o.write(FESC); o.write(TFEND); }
            else if (c == FESC) { o.write(FESC); o.write(TFESC); }
            else o.write(c);
        }
        o.write(FEND);
        return o.toByteArray();
    }

    /** Envoie une trame AX.25 au TNC pour emission ; false si le port n'est pas ouvert. */
    synchronized boolean send(byte[] ax25) {
        if (!connected || out == null) return false;
        byte[] k = encode(ax25);
        try {
            out.write(new byte[]{3, (byte) (k.length >> 24), (byte) (k.length >> 16), (byte) (k.length >> 8), (byte) k.length});
            out.write(k);
            out.flush();
            return true;
        } catch (IOException e) {
            listener.onKissLog(Tr.t("TNC KISS : envoi impossible (") + e.getMessage() + ")");
            return false;
        }
    }
}
