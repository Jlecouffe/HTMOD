/*
 * HTMOD Windows : liaison avec le poste via btbridge.exe (RFCOMM).
 *  - canal de commandes : protocole GAIA (trames FF 01 ...), comme HTCommander (gaia_protocol.dart,
 *    radio.dart) : infos appareil, etat HT (canal courant), lecture des noms de canaux ;
 *  - canal audio : trames delimitees par 0x7E (echappement 0x7D), contenant de l'audio SBC 32 kHz.
 * Le PCM decode et le nom du canal en reception sont transmis au Listener.
 */
package com.htmod;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

final class Radio {
    interface Listener {
        void onStatus(String text);
        void onLog(String text);
        /** Nom du canal en reception (peut etre vide), et etat de la reception. */
        void onChannel(String name, boolean receiving);
        /** PCM 16 bits mono 32 kHz recu du poste. */
        void onAudio(short[] pcm, int len);
        /** Fin d'une sequence audio (le poste a ferme le squelch). */
        void onAudioEnd();
        void onDisconnected();
        /** Canaux ou noms de groupes relus. */
        default void onChannelsChanged() {}
        /** Trame AX.25 (sans FCS) decodee par le TNC materiel du poste (APRS, BSS...). */
        default void onTncFrame(byte[] frame) {}
        /** Etat HT recu (reponse a GET_HT_STATUS ou notification) : S-metre 0..15, squelch ouvert. */
        default void onHtStatus(int rssi, boolean squelchOpen) {}
        /** Frequence reellement accordee en mode frequence (reponse FREQ_MODE_GET_STATUS). */
        default void onVfoFreq(long hz) {}
        /** Reglages lus : double veille active, canaux des VFO A et B. */
        default void onSettings(boolean dualWatch, int channelA, int channelB) {}
        /** Reglages APRS du poste relus (indicatif, symbole, chemin, position...). */
        default void onAprsSettings() {}
        /** Trame AX.25 remise au TNC du poste : true = emise, false = abandonnee. */
        default void onTncSent(boolean ok) {}
    }

    static final class Device {
        final String mac, name;
        Device(String mac, String name) { this.mac = mac; this.name = name; }
        @Override public String toString() { return name + "  (" + mac + ")"; }
    }

    private static final int GROUP_BASIC = 2;
    private static final int CMD_READ_SETTINGS = 10, CMD_WRITE_SETTINGS = 11;
    private static final int NOTIF_HT_SETTINGS_CHANGED = 6;
    private static final int CMD_GET_DEV_INFO = 4, CMD_REGISTER_NOTIFICATION = 6, CMD_EVENT_NOTIFICATION = 9,
            CMD_READ_RF_CH = 13, CMD_GET_HT_STATUS = 20, CMD_FREQ_MODE_SET_PAR = 35, CMD_FREQ_MODE_GET_STATUS = 36,
            CMD_RX_DATA = 57, CMD_WRITE_RF_CH = 14, CMD_SET_REGION = 60, CMD_READ_REGION_NAME = 73, CMD_GET_VOLUME = 22, CMD_SET_VOLUME = 23;
    private static final int NOTIF_HT_STATUS_CHANGED = 1, NOTIF_DATA_RXD = 2, NOTIF_HT_CH_CHANGED = 5;
    private static final int CMD_HT_SEND_DATA = 31, CMD_READ_BSS_SETTINGS = 33, CMD_GET_APRS_PATH = 72, CMD_GET_POSITION = 76;
    private static final int NOTIF_BSS_SETTINGS_CHANGED = 11;

    /** Commandes en BLE (RT-660) : messages bruts ; sinon SPP : messages enveloppes en GAIA. */
    private volatile boolean bleMode;
    private ByteArrayOutputStream tncAcc;
    private int tncNextFrag = -1;

    private final Listener listener;
    private final File bridgeExe;
    private Process proc;
    private OutputStream toBridge;
    private volatile boolean running;

    // Etat du poste
    private String[] channels = new String[0];
    private long[] channelRx = new long[0]; // frequence de reception de chaque canal (Hz)
    private long[] channelTx = new long[0]; // frequence d'emission de chaque canal (Hz, 0 = emission interdite)
    private volatile byte[][] channelRaw = new byte[0][]; // enregistrement complet (25 octets) de chaque canal
    // reglages (READ_SETTINGS) : double veille et canaux des deux VFO
    private volatile byte[] settingsRaw;
    private volatile int dualWatch = -1, channelA = -1, channelB = -1;
    private int lastLoggedA = -2, lastLoggedB = -2;
    /** VFO en reception selon l'etat HT : 0 = simple, 1 = A, 2 = B. */
    private volatile int activeVfo;
    /** Frequence accordee par l'utilisateur (onglet VFO), 0 = sur les canaux memoire. */
    private volatile long userVfoHz;
    private volatile boolean audioOpen;
    private volatile int currCh = -1;
    private volatile int rssi = -1;
    private volatile int currRegion = -1;
    // groupes de canaux (« regions » du protocole) : nombre (GET_DEV_INFO) et noms (READ_REGION_NAME)
    private volatile String[] regionNames = new String[0];
    private int nextRegionRead = -1;
    private volatile boolean inRx, inTx;
    private volatile int volume = -1;
    private final ArrayDeque<Integer> readQueue = new ArrayDeque<>();
    private int pendingRead = -1;
    private long pendingSince;

    // Audio
    private final Sbc sbc = new Sbc();
    private final ByteArrayOutputStream audioAcc = new ByteArrayOutputStream();
    private boolean inAudioRun;
    private final short[] pcmBuf = new short[Sbc.MAX_SAMPLES * 64];

    // Commandes
    private byte[] ctrlAcc = new byte[0];

    Radio(File bridgeExe, Listener listener) {
        this.bridgeExe = bridgeExe;
        this.listener = listener;
    }

    /** Liste des appareils Bluetooth appaires (les postes s'appellent souvent UV-PRO, VR-N76, RT-660...). */
    static List<Device> pairedDevices(File bridgeExe) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(bridgeExe.getAbsolutePath(), "list").redirectErrorStream(true).start();
        List<Device> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t == 17) out.add(new Device(line.substring(0, t), line.substring(t + 1).trim()));
            }
        }
        p.waitFor();
        return out;
    }

    boolean isRunning() { return running; }

    String currentChannelName() {
        int c = currCh;
        if (c >= 254) return "NOAA";
        String[] ch = channels;
        return c >= 0 && c < ch.length && ch[c] != null ? ch[c] : "";
    }

    synchronized void connect(String mac) throws IOException {
        disconnect();
        listener.onStatus("Connexion à " + mac + "...");
        proc = new ProcessBuilder(bridgeExe.getAbsolutePath(), "connect", mac).start();
        toBridge = proc.getOutputStream();
        running = true;
        final Process p = proc;
        Thread rd = new Thread(() -> readLoop(p), "htmod-bridge");
        rd.setDaemon(true);
        rd.start();
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) listener.onLog("btbridge : " + l);
            } catch (IOException ignore) {
            }
        }, "htmod-bridge-err");
        err.setDaemon(true);
        err.start();
        Thread tick = new Thread(this::tickLoop, "htmod-radio-tick");
        tick.setDaemon(true);
        tick.start();
    }

    synchronized void disconnect() {
        if (freqMode && running) { // ne jamais laisser le poste en mode frequence
            stopVfo();
            try { Thread.sleep(300); } catch (InterruptedException ignore) {}
        }
        running = false;
        if (proc != null) {
            try { toBridge.close(); } catch (IOException ignore) {}
            proc.destroy();
            proc = null;
        }
        channels = new String[0];
        currCh = -1;
        userVfoHz = 0;
        audioOpen = false;
        settingsRaw = null;
        dualWatch = -1;
        bss = null;
        aprsPath = null;
        synchronized (txQueue) { txQueue.clear(); txInFlight = false; }
        synchronized (readQueue) { readQueue.clear(); pendingRead = -1; }
    }

    // ------------------------------------------------------------------ Pont

    private void readLoop(Process p) {
        try (DataInputStream in = new DataInputStream(p.getInputStream())) {
            while (true) {
                int ch = in.read();
                if (ch < 0) break;
                int len = in.readInt();
                if (len < 0 || len > 1 << 20) break;
                byte[] data = new byte[len];
                in.readFully(data);
                if (ch == 0) onCtrlData(data);
                else if (ch == 1) onAudioData(data);
                else onBridgeEvent(new String(data, StandardCharsets.UTF_8));
            }
        } catch (IOException ignore) {
        } catch (Throwable t) {
            listener.onLog("Erreur liaison : " + t);
        }
        boolean was = running;
        running = false;
        if (was) {
            listener.onStatus("Déconnecté");
            listener.onDisconnected();
        }
    }

    void onBridgeEvent(String ev) {
        listener.onLog("Bluetooth : " + ev);
        if (ev.startsWith("connected 0")) {
            bleMode = ev.endsWith("ble");
            listener.onStatus("Commandes ouvertes (" + (bleMode ? "BLE" : "Bluetooth classique") + "), lecture du poste…");
            send(GROUP_BASIC, CMD_GET_DEV_INFO, new byte[]{3});
        } else if (ev.equals("closed 1")) {
            audioOpen = false;
        } else if (ev.equals("connected 1")) {
            audioOpen = true;
            listener.onStatus("Connecté (commandes + audio)");
        } else if (ev.startsWith("error canal commandes")) {
            listener.onStatus("Échec de connexion : poste éteint, hors de portée, ou déjà connecté à l'appli du téléphone ?");
        }
    }

    private synchronized void write(int ch, byte[] data) {
        if (toBridge == null) return;
        try {
            byte[] h = {(byte) ch, (byte) (data.length >> 24), (byte) (data.length >> 16), (byte) (data.length >> 8), (byte) data.length};
            toBridge.write(h);
            toBridge.write(data);
            toBridge.flush();
        } catch (IOException e) {
            listener.onLog("Écriture vers le pont impossible : " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ GAIA

    private void send(int group, int cmd, byte[] payload) {
        int n = payload == null ? 0 : payload.length;
        if (bleMode) {
            byte[] m = new byte[4 + n];
            m[0] = (byte) (group >> 8);
            m[1] = (byte) group;
            m[2] = (byte) (cmd >> 8);
            m[3] = (byte) cmd;
            if (n > 0) System.arraycopy(payload, 0, m, 4, n);
            write(0, m);
            return;
        }
        byte[] f = new byte[8 + n];
        f[0] = (byte) 0xFF;
        f[1] = 0x01;
        f[2] = 0x00; // pas de somme de controle
        f[3] = (byte) n;
        f[4] = (byte) (group >> 8);
        f[5] = (byte) group;
        f[6] = (byte) (cmd >> 8);
        f[7] = (byte) cmd;
        if (n > 0) System.arraycopy(payload, 0, f, 8, n);
        write(0, f);
    }

    void onCtrlData(byte[] data) {
        if (bleMode) { // une indication BLE = un message complet
            try {
                handleCommand(data);
            } catch (Throwable t) {
                listener.onLog("Commande mal formée : " + t);
            }
            return;
        }
        byte[] b = new byte[ctrlAcc.length + data.length];
        System.arraycopy(ctrlAcc, 0, b, 0, ctrlAcc.length);
        System.arraycopy(data, 0, b, ctrlAcc.length, data.length);
        int off = 0;
        while (b.length - off >= 8) {
            if ((b[off] & 0xFF) != 0xFF || (b[off + 1] & 0xFF) != 0x01) { off++; continue; }
            int payloadLen = b[off + 3] & 0xFF;
            int total = payloadLen + 8 + (b[off + 2] & 1);
            if (total > b.length - off) break;
            byte[] cmd = new byte[4 + payloadLen];
            System.arraycopy(b, off + 4, cmd, 0, cmd.length);
            off += total;
            try {
                handleCommand(cmd);
            } catch (Throwable t) {
                listener.onLog("Commande mal formée : " + t);
            }
        }
        ctrlAcc = new byte[b.length - off];
        System.arraycopy(b, off, ctrlAcc, 0, ctrlAcc.length);
    }

    private static int u8(byte[] d, int p) { return p < d.length ? d[p] & 0xFF : 0; }

    private void handleCommand(byte[] cmd) {
        if (cmd.length < 4) return;
        int group = (u8(cmd, 0) << 8) | u8(cmd, 1);
        int code = ((u8(cmd, 2) << 8) | u8(cmd, 3)) & 0x7FFF;
        if (group != GROUP_BASIC) return;
        switch (code) {
            case CMD_GET_DEV_INFO: onDevInfo(cmd); break;
            case CMD_READ_SETTINGS: if (u8(cmd, 4) == 0) onSettings(cmd); break; // octet 4 = etat de la reponse
            case CMD_WRITE_SETTINGS:
                if (u8(cmd, 4) == 0) send(GROUP_BASIC, CMD_READ_SETTINGS, null); // relecture apres ecriture
                else listener.onLog("Écriture des réglages refusée (état " + u8(cmd, 4) + ")");
                break;
            case CMD_GET_HT_STATUS: onHtStatus(cmd); break;
            case CMD_READ_RF_CH: onReadChannel(cmd); break;
            case CMD_READ_REGION_NAME: onRegionName(cmd); break;
            case CMD_WRITE_RF_CH:
                // [etat, n° de canal] : relecture du canal pour afficher ce que le poste a vraiment enregistre
                if (u8(cmd, 4) == 0) {
                    int id = cmd.length > 5 ? u8(cmd, 5) : pendingWrite;
                    listener.onLog("Canal " + (id + 1) + " enregistré dans le poste");
                    readChannel(id);
                } else {
                    listener.onLog("Écriture du canal refusée par le poste (état " + u8(cmd, 4) + ")");
                    if (pendingWrite >= 0) readChannel(pendingWrite);
                }
                break;
            case CMD_GET_VOLUME:
                if (u8(cmd, 4) == 0 && cmd.length >= 6) { volume = u8(cmd, 5); listener.onSettings(dualWatch > 0, channelA, channelB); }
                break;
            case CMD_SET_VOLUME: send(GROUP_BASIC, CMD_GET_VOLUME, null); break;
            case CMD_RX_DATA: onTncFragment(cmd); break;
            case CMD_READ_BSS_SETTINGS: onBssSettings(cmd); break;
            case CMD_GET_APRS_PATH:
                if (u8(cmd, 4) == 0) { aprsPath = ascii(cmd, 5, cmd.length - 5); listener.onAprsSettings(); }
                break;
            case CMD_GET_POSITION: onPosition(cmd); break;
            case CMD_HT_SEND_DATA: onTncSendAck(u8(cmd, 4) == 0); break;
            case CMD_FREQ_MODE_GET_STATUS:
                if (cmd.length >= 9 && u8(cmd, 4) == 0)
                    listener.onVfoFreq((((long) u8(cmd, 5) << 24) | (u8(cmd, 6) << 16) | (u8(cmd, 7) << 8) | u8(cmd, 8)) & 0x3FFFFFFFL);
                break;
            case CMD_EVENT_NOTIFICATION:
                int n = u8(cmd, 4);
                if (n == NOTIF_HT_STATUS_CHANGED) onHtStatus(cmd);
                else if (n == NOTIF_HT_CH_CHANGED) readAllChannels();
                else if (n == NOTIF_DATA_RXD) onTncFragment(cmd);
                else if (n == NOTIF_HT_SETTINGS_CHANGED) onSettings(cmd);
                else if (n == NOTIF_BSS_SETTINGS_CHANGED) send(GROUP_BASIC, CMD_READ_BSS_SETTINGS, null);
                break;
            default: break;
        }
    }

    private void onDevInfo(byte[] m) {
        int productId = (u8(m, 6) << 8) | u8(m, 7);
        int count = u8(m, 13);
        int regions = ((u8(m, 11) & 0x03) << 4) | (u8(m, 12) >> 4);
        listener.onLog("Poste : produit " + productId + ", logiciel " + ((u8(m, 9) << 8) | u8(m, 10)) + ", " + count + " canaux, "
                + regions + " groupe(s)");
        regionNames = new String[regions];
        nextRegionRead = regions > 0 ? 0 : -1;
        channels = new String[count];
        channelRx = new long[count];
        channelTx = new long[count];
        channelRaw = new byte[count][];
        send(GROUP_BASIC, CMD_REGISTER_NOTIFICATION, new byte[]{NOTIF_HT_STATUS_CHANGED});
        send(GROUP_BASIC, CMD_REGISTER_NOTIFICATION, new byte[]{NOTIF_DATA_RXD}); // paquets du TNC du poste
        send(GROUP_BASIC, CMD_GET_HT_STATUS, null);
        send(GROUP_BASIC, CMD_READ_SETTINGS, null);
        send(GROUP_BASIC, CMD_GET_VOLUME, null);
        readAprsSettings();
        readAllChannels();
    }

    /** Reglages (reponse READ_SETTINGS ou notification) : octets 5.. = structure Settings (HTCommander). */
    private void onSettings(byte[] m) {
        if (m.length < 15) return;
        settingsRaw = m.clone();
        channelA = ((u8(m, 5) & 0xF0) >> 4) + (u8(m, 14) & 0xF0);
        channelB = (u8(m, 5) & 0x0F) + ((u8(m, 14) & 0x0F) << 4);
        int dw = (u8(m, 6) & 0x30) >> 4;
        if (dw != dualWatch || channelA != lastLoggedA || channelB != lastLoggedB)
            listener.onLog("Réglages du poste : double veille " + (dw == 0 ? "inactive" : "active (" + dw + ")") + ", VFO A canal "
                    + (channelA + 1) + ", VFO B canal " + (channelB + 1));
        lastLoggedA = channelA;
        lastLoggedB = channelB;
        dualWatch = dw;
        listener.onSettings(dualWatch != 0, channelA, channelB);
    }

    /** Active ou coupe la double veille (ecoute des 2 VFO), comme HTCommander : reecriture des reglages. */
    boolean setDualWatch(boolean on) {
        byte[] raw = settingsRaw;
        if (raw == null || raw.length < 15) return false;
        byte[] data = java.util.Arrays.copyOfRange(raw, 5, raw.length);
        data[1] = (byte) ((data[1] & ~0x30) | ((on ? 1 : 0) << 4));
        listener.onLog("Écriture des réglages : double veille " + (on ? "active" : "inactive") + " (" + data.length + " octets)");
        send(GROUP_BASIC, CMD_WRITE_SETTINGS, data);
        return true;
    }

    /** Volume du poste (0..15), -1 = inconnu. */
    int volume() { return volume; }

    void setVolume(int v) {
        v = Math.max(0, Math.min(15, v));
        volume = v;
        send(GROUP_BASIC, CMD_SET_VOLUME, new byte[]{(byte) v});
    }

    /** Niveau de squelch (reglages, octet 6 bits 3-0), -1 = inconnu. */
    int squelch() {
        byte[] raw = settingsRaw;
        return raw == null || raw.length < 7 ? -1 : raw[6] & 0x0F;
    }

    boolean setSquelch(int level) {
        byte[] raw = settingsRaw;
        if (raw == null || raw.length < 15) return false;
        level = Math.max(0, Math.min(9, level));
        byte[] data = java.util.Arrays.copyOfRange(raw, 5, raw.length);
        data[1] = (byte) ((data[1] & 0xF0) | level);
        listener.onLog("Squelch du poste → " + level);
        send(GROUP_BASIC, CMD_WRITE_SETTINGS, data);
        return true;
    }

    /** Le poste est en emission (etat HT). */
    boolean isInTx() { return inTx; }

    int dualWatch() { return dualWatch; }
    int channelA() { return channelA; }
    int channelB() { return channelB; }
    int activeVfo() { return activeVfo; }

    String channelName(int i) {
        String[] ch = channels;
        return i >= 0 && i < ch.length && ch[i] != null ? ch[i] : "";
    }

    long channelRxHz(int i) { long[] f = channelRx; return i >= 0 && i < f.length ? f[i] : 0; }

    private void onHtStatus(byte[] m) {
        boolean powerOn = (u8(m, 5) & 0x80) != 0;
        inRx = (u8(m, 5) & 0x10) != 0;
        inTx = (u8(m, 5) & 0x40) != 0;
        int ch = (((u8(m, 8) & 0x3C) >> 2) << 4) + (u8(m, 6) >> 4);
        int region = ((u8(m, 7) & 0x0F) << 2) + (u8(m, 8) >> 6);
        rssi = u8(m, 7) >> 4;
        boolean sq = (u8(m, 5) & 0x20) != 0;
        // bits 3-2 = mode double veille (pas le VFO actif) ; le VFO qui recoit se deduit du canal courant
        int dwStatus = (u8(m, 5) & 0x0C) >> 2;
        int av = freqMode ? 0 : ch == channelA ? 1 : ch == channelB ? 2 : 0;
        if (!freqMode && (ch != lastStatusCh || dwStatus != lastDwStatus))
            listener.onLog("État du poste : canal " + (ch + 1) + " " + channelName(ch)
                    + (av == 1 ? " (VFO A)" : av == 2 ? " (VFO B)" : " (ni A ni B)")
                    + ", double veille " + (dwStatus != 0 ? "active (" + dwStatus + ")" : "inactive")
                    + (inRx ? ", réception" : ""));
        lastStatusCh = ch;
        lastDwStatus = dwStatus;
        activeVfo = av;
        listener.onHtStatus(rssi, sq);
        pumpTnc();
        if (freqMode) return; // en balayage VFO, le canal affiche n'a pas change
        if (currRegion != -1 && region != currRegion) readAllChannels(); // autre zone = autres canaux
        currRegion = region;
        currCh = ch;
        if (!powerOn) listener.onStatus("Poste éteint");
        fireChannel();
    }

    // ------------------------------------------------------------------ Mode frequence (VFO) : bandscope

    private volatile boolean freqMode;
    private int lastStatusCh = -1, lastDwStatus = -1;
    private int freqModeUpdates;

    /** Frequence de reception actuelle en Hz : celle accordee dans l'onglet VFO, sinon celle du canal. */
    long currentRxHz() {
        if (userVfoHz > 0) return userVfoHz;
        int c = currCh;
        long[] f = channelRx;
        long hz = c >= 0 && c < f.length ? f[c] : 0;
        if (hz > 0) return hz;
        // canal annonce non programme (ex. « canal 27 » en double veille) : canal du VFO selectionne
        int s = selectedChannel();
        return s >= 0 && s < f.length ? f[s] : 0;
    }

    boolean isFreqMode() { return freqMode; }

    /** Canal du VFO selectionne : B si la double veille indique le VFO B, sinon A ; -1 si inconnu. */
    int selectedChannel() {
        int a = channelA, b = channelB;
        if (dualWatch == 2 && b >= 0) return b;
        return a;
    }

    /**
     * Frequence d'emission actuelle (0 si inconnue ou emission interdite) : le poste emet sur le VFO
     * selectionne, pas forcement sur le canal en reception annonce par l'etat HT.
     */
    long currentTxHz() {
        if (userVfoHz > 0) return userVfoHz;
        long[] f = channelTx;
        int c = selectedChannel();
        if (c < 0 || c >= f.length || f[c] <= 0) c = currCh;
        if (c < 0 || c >= f.length) return 0;
        Channel ch = channel(c);
        if (ch != null && ch.txDisable) return 0;
        return f[c];
    }

    long userVfoHz() { return userVfoHz; }

    /** Accorde le poste sur une frequence choisie par l'utilisateur (reception et emission). */
    void tuneUser(long hz) {
        userVfoHz = hz;
        tuneVfo(hz, hz, false);
    }

    /** Retour aux canaux memoire (fin de l'accord utilisateur). */
    void backToChannels() {
        userVfoHz = 0;
        stopVfo();
    }

    /** Fin d'un balayage : retour sur la frequence accordee par l'utilisateur, sinon sur le canal. */
    void restoreAfterSweep() {
        long u = userVfoHz;
        if (u > 0) tuneVfo(u, u, false);
        else stopVfo();
    }

    boolean audioOpen() { return audioOpen && running; }

    /** Envoie des trames audio deja formees (0x7E ...) sur le canal audio (emission). */
    void sendAudio(byte[] frames) { write(1, frames); }

    /**
     * Accorde le poste sur rxHz en mode frequence (FREQ_MODE_SET_PAR, comme le suivi satellite de
     * HTCommander : drapeaux 0x0A03 aux deux premiers envois puis 0x0A00). La frequence d'emission
     * reste txHz (la frequence du canal de l'utilisateur) : un appui sur PTT n'emettra jamais
     * sur une frequence balayee. Rien n'est ecrit dans la memoire du poste.
     */
    void tuneVfo(long rxHz, long txHz, boolean am) { tuneVfo(rxHz, txHz, am, 0, 0); }

    /** Idem avec sous-tons (en 0,01 Hz, 0 = aucun) en reception et en emission (relais satellite FM). */
    void tuneVfo(long rxHz, long txHz, boolean am, int rxTone, int txTone) {
        freqMode = true;
        int flags = freqModeUpdates++ < 2 ? 0x0A03 : 0x0A00;
        byte[] p = new byte[16];
        putInt(p, 0, (int) (rxHz & 0x3FFFFFFF) | (am ? 1 << 30 : 0));
        putInt(p, 4, (int) (txHz & 0x3FFFFFFF));
        p[8] = (byte) (rxTone >> 8);
        p[9] = (byte) rxTone;
        p[10] = (byte) (txTone >> 8);
        p[11] = (byte) txTone;
        p[12] = (byte) (flags >> 8);
        p[13] = (byte) flags;
        p[14] = (byte) 0x61; // pas de canal 25 kHz (constante envoyee par l'appli constructeur)
        p[15] = (byte) 0xA8;
        send(GROUP_BASIC, CMD_FREQ_MODE_SET_PAR, p);
    }

    /** Quitte le mode frequence : le poste revient sur son canal memoire. */
    void stopVfo() {
        if (!freqMode) return;
        send(GROUP_BASIC, CMD_FREQ_MODE_SET_PAR, new byte[16]);
        freqMode = false;
        freqModeUpdates = 0;
        send(GROUP_BASIC, CMD_GET_HT_STATUS, null);
    }

    /**
     * Nom et position du satellite poursuivi pour l'ecran du poste (SET_SATELLITE_INFO, 77, comme
     * HTCommander) : nom 20 octets, azimut x128, elevation x256, distance km, altitude km, secondes
     * avant le prochain passage.
     */
    void setSatelliteInfo(String name, double azDeg, double elDeg, double rangeKm, double altKm, int secsToNext) {
        byte[] p = new byte[30];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, p, 0, Math.min(20, n.length));
        int az = (int) Math.round(((azDeg % 360) + 360) % 360 * 128), el = (int) Math.round(elDeg * 256);
        int[] v = {az, el, (int) Math.min(65535, Math.max(0, rangeKm)), (int) Math.min(65535, Math.max(0, altKm)),
            Math.min(65535, Math.max(0, secsToNext))};
        for (int i = 0; i < v.length; i++) {
            p[20 + 2 * i] = (byte) (v[i] >> 8);
            p[21 + 2 * i] = (byte) v[i];
        }
        send(GROUP_BASIC, 77, p);
    }

    /** Demande l'etat HT (S-metre) ; la reponse arrive par Listener.onHtStatus. */
    void requestHtStatus() { send(GROUP_BASIC, CMD_GET_HT_STATUS, null); }

    /** Demande la frequence VFO reellement accordee ; reponse par Listener.onVfoFreq. */
    void requestVfoFreq() { send(GROUP_BASIC, CMD_FREQ_MODE_GET_STATUS, null); }

    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 24);
        b[off + 1] = (byte) (v >> 16);
        b[off + 2] = (byte) (v >> 8);
        b[off + 3] = (byte) v;
    }

    private void fireChannel() { listener.onChannel(currentChannelName(), inRx); }

    /**
     * Fragment de trame du TNC materiel (comme TncDataFragment de HTCommander) :
     * octet 5 = [dernier:1][avec n° de canal:1][n° de fragment:6], donnees, puis n° de canal eventuel.
     */
    private void onTncFragment(byte[] m) {
        if (m.length < 7) return;
        int flags = u8(m, 5);
        boolean last = (flags & 0x80) != 0;
        boolean withCh = (flags & 0x40) != 0;
        int frag = flags & 0x3F;
        int len = m.length - 6 - (withCh ? 1 : 0);
        if (len <= 0) return;
        if (frag == 0) {
            tncAcc = new ByteArrayOutputStream();
        } else if (tncAcc == null || frag != tncNextFrag) {
            tncAcc = null; // fragment manquant : trame abandonnee
            return;
        }
        tncAcc.write(m, 6, len);
        tncNextFrag = frag + 1;
        if (last) {
            byte[] f = tncAcc.toByteArray();
            tncAcc = null;
            listener.onTncFrame(f);
        }
    }

    private void readAllChannels() {
        synchronized (readQueue) {
            readQueue.clear();
            for (int i = 0; i < channels.length; i++) readQueue.add(i);
            pendingRead = -1;
        }
        pumpReads();
    }

    /** Les lectures de canaux sont serialisees : une requete a la fois, comme dans HTCommander. */
    private void pumpReads() {
        Integer next;
        synchronized (readQueue) {
            if (pendingRead >= 0) return;
            next = readQueue.poll();
            if (next == null) return;
            pendingRead = next;
            pendingSince = System.currentTimeMillis();
        }
        send(GROUP_BASIC, CMD_READ_RF_CH, new byte[]{(byte) (int) next});
    }

    private void onReadChannel(byte[] m) {
        int id = u8(m, 5);
        if (m.length >= 30 && u8(m, 4) == 0) {
            String name = new String(m, 20, 10, Charset.forName("GBK"));
            int z = name.indexOf('\0');
            if (z >= 0) name = name.substring(0, z);
            name = name.trim();
            String[] ch = channels;
            if (id < ch.length) ch[id] = name;
            long[] fr = channelRx;
            if (id < fr.length) fr[id] = (((long) u8(m, 10) << 24) | (u8(m, 11) << 16) | (u8(m, 12) << 8) | u8(m, 13)) & 0x3FFFFFFFL;
            long[] ft = channelTx;
            if (id < ft.length) ft[id] = (((long) u8(m, 6) << 24) | (u8(m, 7) << 16) | (u8(m, 8) << 8) | u8(m, 9)) & 0x3FFFFFFFL;
            byte[][] raw = channelRaw;
            if (id < raw.length) {
                raw[id] = java.util.Arrays.copyOfRange(m, 5, 30);
                // controle : relire puis reencoder doit redonner le meme enregistrement (sinon modifier ce canal le changerait)
                if (!java.util.Arrays.equals(raw[id], Channel.parse(raw[id]).toBytes()))
                    listener.onLog("Canal " + (id + 1) + " : réencodage différent de l'original (" + hexDiff(raw[id], Channel.parse(raw[id]).toBytes()) + ")");
            }
        }
        synchronized (readQueue) {
            if (pendingRead == id) pendingRead = -1;
            if (readQueue.isEmpty() && pendingRead < 0) {
                listener.onLog("Canaux lus : " + describeChannels());
                if (nextRegionRead == 0) send(GROUP_BASIC, CMD_READ_REGION_NAME, new byte[]{0}); // puis les noms des groupes
            }
        }
        listener.onChannelsChanged();
        if (id == currCh) fireChannel();
        pumpReads();
    }

    /** Reponse READ_REGION_NAME : [etat, index, nom UTF-8] ; les noms sont lus un par un. */
    private void onRegionName(byte[] m) {
        String[] names = regionNames;
        int i = u8(m, 5);
        if (u8(m, 4) == 0 && i < names.length) {
            String n = new String(m, 6, Math.max(0, m.length - 6), Charset.forName("GBK")); // comme les noms de canaux
            int z = n.indexOf('\0');
            names[i] = (z >= 0 ? n.substring(0, z) : n).trim();
        }
        int next = Math.max(i, nextRegionRead) + 1;
        if (next < names.length) {
            nextRegionRead = next;
            send(GROUP_BASIC, CMD_READ_REGION_NAME, new byte[]{(byte) next});
        } else {
            nextRegionRead = -1;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < names.length; k++) sb.append(k == 0 ? "" : ", ").append(k + 1).append('=').append(names[k] == null ? "" : names[k]);
            listener.onLog("Groupes : " + sb);
        }
        listener.onChannelsChanged();
    }

    int regionCount() { return regionNames.length; }
    int currentRegion() { return currRegion; }
    int channelCount() { return channels.length; }
    long channelTxHz(int i) { long[] f = channelTx; return i >= 0 && i < f.length ? f[i] : 0; }

    String regionName(int i) {
        String[] n = regionNames;
        return i >= 0 && i < n.length && n[i] != null ? n[i] : "";
    }

    /** Change de groupe de canaux (SET_REGION, sans reponse) ; le poste renvoie son etat et on relit les canaux. */
    void setRegion(int region) {
        if (region < 0 || region >= regionNames.length) return;
        listener.onLog("Groupe " + (region + 1) + (regionName(region).isEmpty() ? "" : " (" + regionName(region) + ")"));
        send(GROUP_BASIC, CMD_SET_REGION, new byte[]{(byte) region});
        send(GROUP_BASIC, CMD_GET_HT_STATUS, null);
    }

    /**
     * Met le canal ch sur le VFO A (vfo 1) ou B (vfo 2) en reecrivant les reglages : canal A = octet 5
     * (poids forts) + octet 14 (poids forts), canal B = octet 5 (poids faibles) + octet 14 (poids faibles).
     */
    boolean setVfoChannel(int vfo, int ch) {
        byte[] raw = settingsRaw;
        if (raw == null || raw.length < 15 || ch < 0 || ch > 255) return false;
        byte[] data = java.util.Arrays.copyOfRange(raw, 5, raw.length);
        if (vfo == 1) {
            data[0] = (byte) ((data[0] & 0x0F) | ((ch & 0x0F) << 4));
            data[9] = (byte) ((data[9] & 0x0F) | (ch & 0xF0));
        } else {
            data[0] = (byte) ((data[0] & 0xF0) | (ch & 0x0F));
            data[9] = (byte) ((data[9] & 0xF0) | ((ch >> 4) & 0x0F));
        }
        listener.onLog("VFO " + (vfo == 1 ? "A" : "B") + " → canal " + (ch + 1) + (channelName(ch).isEmpty() ? "" : " " + channelName(ch)));
        send(GROUP_BASIC, CMD_WRITE_SETTINGS, data);
        return true;
    }

    // ------------------------------------------------------------------ APRS : reglages du poste et emission par son TNC

    /**
     * Reglages APRS / BSS du poste (READ_BSS_SETTINGS, structure RadioBssSettings de HTCommander) :
     * indicatif et SSID, symbole, commentaire de balise, intervalle, format (BSS ou APRS)...
     */
    static final class AprsSettings {
        int maxFwdTimes, timeToLive, packetFormat, ssid, intervalS;
        boolean sendLocationOnPtt, sendIdOnPtt, shareLocation, sendVoltage, allowPositionCheck, smartBeacon;
        String callsign = "", symbol = "", beacon = "", pttIdInfo = "";

        /** Indicatif complet (« F4ABC-9 »), vide si le poste n'en a pas. */
        String source() { return callsign.isEmpty() ? "" : ssid > 0 ? callsign + "-" + ssid : callsign; }
    }

    private volatile AprsSettings bss;
    private volatile String aprsPath;
    private volatile double gpsLat = Double.NaN, gpsLon = Double.NaN;
    private volatile boolean gpsLocked;

    AprsSettings aprsSettings() { return bss; }
    /** Chemin APRS du poste (« WIDE1-1,WIDE2-1 »), null si pas encore lu. */
    String aprsPath() { return aprsPath; }
    boolean gpsLocked() { return gpsLocked; }
    double gpsLat() { return gpsLat; }
    double gpsLon() { return gpsLon; }

    /** Relit les reglages APRS, le chemin et la position GPS du poste. */
    void readAprsSettings() {
        send(GROUP_BASIC, CMD_READ_BSS_SETTINGS, null);
        send(GROUP_BASIC, CMD_GET_APRS_PATH, null);
        send(GROUP_BASIC, CMD_GET_POSITION, null);
    }

    private void onBssSettings(byte[] m) {
        if (u8(m, 4) != 0 || m.length < 51) return;
        AprsSettings a = new AprsSettings();
        a.maxFwdTimes = u8(m, 5) >> 4;
        a.timeToLive = u8(m, 5) & 0x0F;
        int f = u8(m, 6);
        a.sendLocationOnPtt = (f & 0x80) != 0;
        a.sendIdOnPtt = (f & 0x40) != 0;
        a.shareLocation = (f & 0x10) != 0;
        a.sendVoltage = (f & 0x08) != 0;
        a.packetFormat = (f & 0x04) >> 2;
        a.allowPositionCheck = (f & 0x02) != 0;
        a.ssid = u8(m, 7) >> 4;
        a.smartBeacon = (u8(m, 7) & 0x08) != 0;
        a.intervalS = u8(m, 8) * 10;
        a.pttIdInfo = ascii(m, 13, 12);
        a.beacon = ascii(m, 25, 18);
        a.symbol = ascii(m, 43, 2);
        a.callsign = ascii(m, 45, 6).toUpperCase(java.util.Locale.ROOT);
        bss = a;
        listener.onLog("Réglages APRS du poste : " + (a.source().isEmpty() ? "pas d'indicatif" : a.source()) + ", symbole « " + a.symbol
                + " », intervalle " + a.intervalS + " s, format " + (a.packetFormat == 1 ? "APRS" : "BSS")
                + (a.beacon.isEmpty() ? "" : ", commentaire « " + a.beacon + " »"));
        listener.onAprsSettings();
    }

    /** GET_POSITION : latitude / longitude sur 24 bits signes en 1/30000 de degre, etat 0 = GPS fixe. */
    private void onPosition(byte[] m) {
        if (m.length < 11) return;
        gpsLocked = u8(m, 4) == 0;
        if (gpsLocked) {
            gpsLat = s24(m, 5) / 30000.0;
            gpsLon = s24(m, 8) / 30000.0;
        }
        listener.onAprsSettings();
    }

    private static int s24(byte[] m, int p) {
        int v = (u8(m, p) << 16) | (u8(m, p + 1) << 8) | u8(m, p + 2);
        return (v & 0x800000) != 0 ? v - 0x1000000 : v;
    }

    private static String ascii(byte[] m, int off, int len) {
        len = Math.max(0, Math.min(len, m.length - off));
        String s = new String(m, off, len, StandardCharsets.UTF_8);
        int z = s.indexOf('\0');
        return (z >= 0 ? s.substring(0, z) : s).trim();
    }

    /** Fragments HT_SEND_DATA en attente (un a la fois, comme HTCommander). */
    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private boolean txInFlight;
    private long txSince, txQueuedAt;
    private static final int TNC_MTU = 50;

    /**
     * Confie une trame AX.25 (sans FCS) au TNC du poste, qui la module en AFSK 1200 et l'emet sur le
     * canal ch (-1 = canal du VFO A). L'envoi attend que le canal soit libre (ni reception ni
     * emission en cours) ; resultat par Listener.onTncSent. false si une trame est deja en attente.
     */
    boolean sendTncFrame(byte[] ax25, int ch) {
        if (!running || ax25.length == 0) return false;
        synchronized (txQueue) {
            if (!txQueue.isEmpty() || txInFlight) return false;
            for (int i = 0, frag = 0; i < ax25.length; i += TNC_MTU, frag++) {
                int n = Math.min(TNC_MTU, ax25.length - i);
                boolean last = i + n == ax25.length;
                byte[] p = new byte[1 + n + (ch >= 0 ? 1 : 0)];
                p[0] = (byte) ((last ? 0x80 : 0) | (ch >= 0 ? 0x40 : 0) | (frag & 0x3F));
                System.arraycopy(ax25, i, p, 1, n);
                if (ch >= 0) p[p.length - 1] = (byte) ch;
                txQueue.add(p);
            }
            txQueuedAt = System.currentTimeMillis();
        }
        pumpTnc();
        return true;
    }

    boolean tncBusy() { synchronized (txQueue) { return txInFlight || !txQueue.isEmpty(); } }

    private void pumpTnc() {
        byte[] next;
        synchronized (txQueue) {
            long now = System.currentTimeMillis();
            if (txInFlight && now - txSince > 5000) txInFlight = false; // pas d'accuse : on renvoie le fragment
            if (txInFlight || txQueue.isEmpty()) return;
            if (now - txQueuedAt > 30_000) {
                txQueue.clear();
                next = null;
            } else if (rssi > 0 || inTx || inRx) {
                return; // canal occupe : on attend
            } else {
                next = txQueue.peek();
                txInFlight = true;
                txSince = now;
            }
        }
        if (next == null) {
            listener.onLog("APRS : canal occupé depuis 30 s, trame abandonnée");
            listener.onTncSent(false);
            return;
        }
        send(GROUP_BASIC, CMD_HT_SEND_DATA, next);
    }

    private void onTncSendAck(boolean ok) {
        boolean done;
        synchronized (txQueue) {
            if (!txInFlight) return;
            txInFlight = false;
            if (ok) txQueue.poll();
            else txQueue.clear();
            done = txQueue.isEmpty();
        }
        if (!ok) {
            listener.onLog("APRS : le poste a refusé la trame");
            listener.onTncSent(false);
        } else if (done) {
            listener.onTncSent(true);
        } else {
            pumpTnc();
        }
    }

    // ------------------------------------------------------------------ Canaux : detail, ecriture

    /**
     * Canal memoire (25 octets, format HTCommander RadioChannelInfo) : n°, frequences et modulations
     * (2 bits de poids fort), sous-tons (CTCSS en 0,01 Hz si >= 1000, sinon code DCS), options, nom GBK.
     * Les options non modifiees sont recopiees telles quelles depuis l'enregistrement lu.
     */
    static final class Channel {
        int id, rxMod, txMod, txSub, rxSub;
        long rxHz, txHz;
        boolean scan, maxPower, talkAround, wide, preDeEmphBypass, sign, medPower, txDisable;
        int flags2; // fixedFreq, fixedBandwidth, fixedTxPower, mute (non edites)
        String name = "";
        private byte[] raw;       // enregistrement d'origine : tout ce qui n'est pas modifie est recopie a l'octet pres
        private String rawName;

        static Channel parse(byte[] r) {
            Channel c = new Channel();
            c.id = r[0] & 0xFF;
            c.txMod = (r[1] & 0xFF) >> 6;
            c.txHz = (((long) (r[1] & 0xFF) << 24) | ((r[2] & 0xFF) << 16) | ((r[3] & 0xFF) << 8) | (r[4] & 0xFF)) & 0x3FFFFFFFL;
            c.rxMod = (r[5] & 0xFF) >> 6;
            c.rxHz = (((long) (r[5] & 0xFF) << 24) | ((r[6] & 0xFF) << 16) | ((r[7] & 0xFF) << 8) | (r[8] & 0xFF)) & 0x3FFFFFFFL;
            c.txSub = ((r[9] & 0xFF) << 8) | (r[10] & 0xFF);
            c.rxSub = ((r[11] & 0xFF) << 8) | (r[12] & 0xFF);
            int f = r[13] & 0xFF;
            c.scan = (f & 0x80) != 0; c.maxPower = (f & 0x40) != 0; c.talkAround = (f & 0x20) != 0; c.wide = (f & 0x10) != 0;
            c.preDeEmphBypass = (f & 0x08) != 0; c.sign = (f & 0x04) != 0; c.medPower = (f & 0x02) != 0; c.txDisable = (f & 0x01) != 0;
            c.flags2 = r[14] & 0xFF;
            String n = new String(r, 15, 10, Charset.forName("GBK"));
            int z = n.indexOf('\0');
            c.name = (z >= 0 ? n.substring(0, z) : n).trim();
            c.raw = r.clone();
            c.rawName = c.name;
            return c;
        }

        byte[] toBytes() {
            byte[] r = raw != null && raw.length >= 25 ? java.util.Arrays.copyOf(raw, 25) : new byte[25];
            r[0] = (byte) id;
            putInt(r, 1, (int) (txHz & 0x3FFFFFFF) | ((txMod & 3) << 30));
            putInt(r, 5, (int) (rxHz & 0x3FFFFFFF) | ((rxMod & 3) << 30));
            r[9] = (byte) (txSub >> 8); r[10] = (byte) txSub;
            r[11] = (byte) (rxSub >> 8); r[12] = (byte) rxSub;
            r[13] = (byte) ((scan ? 0x80 : 0) | (maxPower ? 0x40 : 0) | (talkAround ? 0x20 : 0) | (wide ? 0x10 : 0)
                    | (preDeEmphBypass ? 0x08 : 0) | (sign ? 0x04 : 0) | (medPower ? 0x02 : 0) | (txDisable ? 0x01 : 0));
            r[14] = (byte) flags2;
            if (raw == null || !name.equals(rawName)) { // nom modifie : reecrit (complete par des zeros)
                java.util.Arrays.fill(r, 15, 25, (byte) 0);
                byte[] nb = name.getBytes(Charset.forName("GBK"));
                System.arraycopy(nb, 0, r, 15, Math.min(10, nb.length));
            }
            return r;
        }

        boolean mute() { return (flags2 & 0x10) != 0; }

        /** Sous-ton lisible : "", "88.5" (CTCSS) ou "D023" (DCS). */
        static String subText(int v) {
            if (v == 0) return "";
            if (v >= 1000) return String.format(java.util.Locale.ROOT, "%.1f", v / 100.0);
            return String.format(java.util.Locale.ROOT, "D%03d", v);
        }

        /** Inverse de subText ; IllegalArgumentException si illisible. */
        static int parseSub(String s) {
            s = s == null ? "" : s.trim().toUpperCase(java.util.Locale.ROOT).replace(',', '.');
            if (s.isEmpty() || s.equals("AUCUN") || s.equals("OFF") || s.equals("0")) return 0;
            if (s.startsWith("D")) {
                String d = s.replaceAll("[^0-9]", "");
                if (d.isEmpty() || d.length() > 3) throw new IllegalArgumentException("Code DCS invalide : " + s);
                return Integer.parseInt(d);
            }
            double hz = Double.parseDouble(s.replace("HZ", "").trim());
            if (hz < 60 || hz > 260) throw new IllegalArgumentException("CTCSS hors plage (60–260 Hz) : " + s);
            return (int) Math.round(hz * 100);
        }
    }

    private static String hexDiff(byte[] a, byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(a.length, b.length); i++)
            if (a[i] != b[i]) sb.append(sb.length() > 0 ? ", " : "octet ").append(i).append(String.format(" %02X→%02X", a[i] & 0xFF, b[i] & 0xFF));
        return sb.toString();
    }

    /** Detail du canal i (enregistrement lu), ou null s'il n'a pas encore ete lu. */
    Channel channel(int i) {
        byte[][] raw = channelRaw;
        return i >= 0 && i < raw.length && raw[i] != null && raw[i].length >= 25 ? Channel.parse(raw[i]) : null;
    }

    private volatile int pendingWrite = -1;

    /** Ecrit un canal memoire dans le poste (WRITE_RF_CH), puis le relit. */
    void writeChannel(Channel c) {
        pendingWrite = c.id;
        listener.onLog("Écriture du canal " + (c.id + 1) + " (" + c.name + ", " + String.format(java.util.Locale.ROOT, "%.4f", c.rxHz / 1e6) + " MHz)");
        send(GROUP_BASIC, CMD_WRITE_RF_CH, c.toBytes());
    }

    private void readChannel(int id) {
        synchronized (readQueue) { readQueue.add(id); }
        pumpReads();
    }

    private String describeChannels() {
        StringBuilder sb = new StringBuilder();
        String[] ch = channels;
        for (int i = 0; i < ch.length; i++) {
            if (ch[i] == null || ch[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(i + 1).append('=').append(ch[i]);
        }
        return sb.length() == 0 ? "(aucun nom)" : sb.toString();
    }

    private void tickLoop() {
        while (running) {
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            boolean retry = false;
            synchronized (readQueue) {
                if (pendingRead >= 0 && System.currentTimeMillis() - pendingSince > 1500) {
                    pendingRead = -1; // pas de reponse : on passe au suivant
                    retry = true;
                }
            }
            if (retry) pumpReads();
            pumpTnc();
        }
    }

    // ------------------------------------------------------------------ Audio

    void onAudioData(byte[] data) {
        audioAcc.write(data, 0, data.length);
        byte[] b = audioAcc.toByteArray();
        if (b.length > 1 << 16) { audioAcc.reset(); return; }
        int off = 0;
        while (true) {
            int start = -1, end = -1;
            for (int i = off; i < b.length; i++) {
                if ((b[i] & 0xFF) == 0x7E) {
                    if (start < 0) start = i;
                    else if (i == start + 1) start = i; // deux 0x7E consecutifs : le second ouvre la trame
                    else { end = i; break; }
                }
            }
            if (start < 0) { off = b.length; break; }
            if (end < 0) { off = start; break; }
            handleAudioFrame(unescape(b, start + 1, end));
            off = end; // le marqueur de fin peut servir de debut a la trame suivante
        }
        audioAcc.reset();
        audioAcc.write(b, off, b.length - off);
    }

    private static byte[] unescape(byte[] b, int from, int to) {
        byte[] out = new byte[to - from];
        int n = 0;
        for (int i = from; i < to; i++) {
            if ((b[i] & 0xFF) == 0x7D) {
                if (++i < to) out[n++] = (byte) (b[i] ^ 0x20);
            } else {
                out[n++] = b[i];
            }
        }
        byte[] r = new byte[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    private void handleAudioFrame(byte[] f) {
        if (f.length == 0) return;
        switch (f[0]) {
            case 0x00:
            case 0x03: // audio recu
                if (!inAudioRun) {
                    inAudioRun = true;
                    sbc.reset();
                }
                decodeSbc(f, 1);
                break;
            case 0x01: // fin d'audio
                if (inAudioRun) {
                    inAudioRun = false;
                    listener.onAudioEnd();
                }
                break;
            default: // 0x02 ACK, 0x09 echo d'emission : ignores
                break;
        }
    }

    private void decodeSbc(byte[] f, int off) {
        int n = 0;
        while (off < f.length) {
            int sync = f[off] & 0xFF;
            if (sync != 0x9C && sync != 0xAD) break;
            Sbc.Frame h = Sbc.probe(f, off, f.length - off);
            if (h == null) break;
            int size = h.frameSize();
            if (size <= 0 || size > f.length - off) break;
            if (pcmBuf.length - n < Sbc.MAX_SAMPLES) break;
            int got = sbc.decode(f, off, size, pcmBuf, n);
            if (got < 0) break;
            n += got;
            off += size;
        }
        if (n > 0) listener.onAudio(pcmBuf, n);
    }
}
