/*
 * HTMOD Windows : equivalent de Hook.java (version Android). Recoit l'audio PCM 16 bits / 32 kHz
 * du poste. Si le canal en reception s'appelle "SARSAT" ou "Radiosonde" (casse ignoree), ou si
 * un mode est force dans l'interface, l'audio est mis en tampon et decode sur un thread separe :
 *  - SARSAT     : decodage a la fin de chaque rafale (300 ms sans audio) ou toutes les 5 s ;
 *  - Radiosonde : decodage toutes les 6 s de flux continu, ou en fin de reception.
 * Resultats : rappel vers l'interface (tableau + notification Windows), journal CSV et
 * enregistrements WAV dans Documents\HTMod\.
 */
package com.htmod;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class Decoders {
    static final int SAMPLE_RATE = 32000;
    static final int MODE_NONE = 0, MODE_SARSAT = 1, MODE_SONDE = 2;
    private static final long FLUSH_GAP_MS = 300;

    /** Un decodage pret a afficher. */
    static final class Detection {
        final long receivedMs = System.currentTimeMillis();
        String kind;        // "SARSAT" ou type de radiosonde (RS41, DFM09...)
        String id;
        String title;
        String text;
        boolean hasPos;
        double lat, lon;
        int count;
        boolean alert;      // SARSAT : notification plus visible
    }

    interface Listener {
        void onDetection(Detection d);
        void onLog(String text);
    }

    private static final class Chunk {
        final int mode;
        final short[] pcm;

        Chunk(int mode, short[] pcm) {
            this.mode = mode;
            this.pcm = pcm;
        }
    }

    private final Listener listener;
    private final File outDir;
    private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(512);
    private volatile Thread worker;
    private volatile int lastMode = MODE_NONE;
    /** Mode force dans l'interface (MODE_NONE = automatique selon le nom du canal). */
    volatile int forcedMode = MODE_NONE;
    volatile boolean saveWav = true;

    Decoders(File outDir, Listener listener) {
        this.outDir = outDir;
        this.listener = listener;
    }

    File outDir() { return outDir; }

    static int modeFor(String name) {
        if (name == null) return MODE_NONE;
        String n = name.trim().toLowerCase(Locale.ROOT);
        if (n.equals("sarsat")) return MODE_SARSAT;
        if (n.equals("radiosonde")) return MODE_SONDE;
        return MODE_NONE;
    }

    int effectiveMode(String channelName) {
        int f = forcedMode;
        return f != MODE_NONE ? f : modeFor(channelName);
    }

    static String modeName(int mode) {
        return mode == MODE_SARSAT ? "SARSAT" : mode == MODE_SONDE ? "Radiosonde" : "aucun";
    }

    /** Appele pour chaque bloc PCM recu (le tableau peut etre reutilise par l'appelant). */
    void feed(String channelName, short[] pcm, int off, int len) {
        if (pcm == null || len <= 0) return;
        int mode = effectiveMode(channelName);
        if (mode == MODE_NONE) {
            lastMode = MODE_NONE;
            return;
        }
        if (lastMode != mode) {
            lastMode = mode;
            listener.onLog("Canal " + modeName(mode) + " détecté, décodage actif");
        }
        ensureWorker();
        queue.offer(new Chunk(mode, Arrays.copyOfRange(pcm, off, off + len)));
    }

    /** Decode un fichier entier (WAV deja lu), hors ligne, dans le mode demande. */
    void decodeFile(int mode, short[] pcm, int rate) {
        Thread t = new Thread(() -> {
            try {
                if (mode == MODE_SARSAT) {
                    List<Sarsat.Frame> frames = new Sarsat(rate, 1400).decode(pcm, pcm.length);
                    listener.onLog("Fichier : " + frames.size() + " trame(s) SARSAT");
                    for (Sarsat.Frame f : frames) onSarsat(f);
                } else {
                    List<Sondes.Fix> fixes = new Sondes(rate).decode(pcm, pcm.length);
                    listener.onLog("Fichier : " + fixes.size() + " position(s) de radiosonde");
                    for (Sondes.Fix f : fixes) onSonde(f);
                }
            } catch (Throwable e) {
                listener.onLog("Décodage du fichier impossible : " + e);
            }
        }, "htmod-file");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(this::workerLoop, "htmod-decoder");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY + 1);
        worker.start();
    }

    // ------------------------------------------------------------------ Thread de decodage

    private short[] buf = new short[SAMPLE_RATE * 8];
    private int bufLen = 0;
    private int bufMode = MODE_NONE;
    private Sarsat sarsat;
    private Sondes sondes;
    private boolean sondeWavSaved;

    private void workerLoop() {
        while (true) {
            Chunk c;
            try {
                c = queue.poll(FLUSH_GAP_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (c == null) {
                    flush();
                    continue;
                }
                if (c.mode != bufMode) {
                    flush();
                    bufMode = c.mode;
                    if (c.mode == MODE_SONDE) sondeWavSaved = false;
                }
                append(c.pcm);
                int max = SAMPLE_RATE * (bufMode == MODE_SARSAT ? 5 : 6);
                if (bufLen >= max) flush();
            } catch (Throwable t) {
                listener.onLog("Erreur de décodage : " + t);
                bufLen = 0;
            }
        }
    }

    private void append(short[] pcm) {
        if (bufLen + pcm.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, bufLen + pcm.length));
        System.arraycopy(pcm, 0, buf, bufLen, pcm.length);
        bufLen += pcm.length;
    }

    private void flush() {
        int n = bufLen;
        bufLen = 0;
        if (n == 0) return;
        if (bufMode == MODE_SARSAT) {
            if (n < SAMPLE_RATE * 35 / 100) return;
            if (sarsat == null) sarsat = new Sarsat(SAMPLE_RATE, 1400);
            long t0 = System.currentTimeMillis();
            List<Sarsat.Frame> frames = sarsat.decode(buf, n);
            listener.onLog("SARSAT : rafale de " + (n * 1000 / SAMPLE_RATE) + " ms analysée en "
                    + (System.currentTimeMillis() - t0) + " ms, " + frames.size() + " trame(s)");
            saveWav("sarsat", buf, n);
            for (Sarsat.Frame f : frames) onSarsat(f);
        } else if (bufMode == MODE_SONDE) {
            if (n < SAMPLE_RATE / 2) return;
            if (sondes == null) sondes = new Sondes(SAMPLE_RATE);
            long t0 = System.currentTimeMillis();
            List<Sondes.Fix> fixes = sondes.decode(buf, n);
            listener.onLog("Radiosonde : " + (n * 1000 / SAMPLE_RATE) + " ms analysés en "
                    + (System.currentTimeMillis() - t0) + " ms, " + fixes.size() + " position(s)");
            if (!sondeWavSaved) {
                saveWav("radiosonde", buf, n);
                sondeWavSaved = true;
            }
            for (Sondes.Fix f : fixes) onSonde(f);
        }
    }

    // ------------------------------------------------------------------ Sorties

    private final Map<String, Integer> counts = new HashMap<>();

    private synchronized int bump(String key) {
        Integer c = counts.get(key);
        int v = c == null ? 1 : c + 1;
        counts.put(key, v);
        return v;
    }

    private void onSarsat(Sarsat.Frame f) {
        String country = Sarsat.countryName(f.countryCode);
        String countryTxt = (country != null ? country : "?") + " (" + f.countryCode + ")";
        int count = bump("S" + f.hexId);
        Detection d = new Detection();
        d.kind = "SARSAT";
        d.id = f.hexId;
        d.count = count;
        d.alert = !f.isTest;
        d.title = (f.isTest ? "SARSAT (auto-test) " : "SARSAT 406 ") + f.hexId + (count > 1 ? " ×" + count : "");
        StringBuilder text = new StringBuilder();
        text.append(countryTxt).append(" – ").append(f.protocolName());
        if (f.identification != null && !f.identification.isEmpty()) text.append("\n").append(f.identification);
        if (f.hasPosition) text.append("\nPosition : ").append(fmt(f.latitude, 5)).append(", ").append(fmt(f.longitude, 5));
        else text.append("\nPas de position");
        text.append("\nBCH1 ").append(f.crc1Ok ? "OK" : "KO");
        if (f.length == 144) text.append(", BCH2 ").append(f.crc2Ok ? "OK" : "KO");
        text.append(" – trame ").append(f.rawHex());
        d.text = text.toString();
        d.hasPos = f.hasPosition;
        d.lat = f.latitude;
        d.lon = f.longitude;
        logCsv("SARSAT", f.hexId, f.hasPosition, f.latitude, f.longitude, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1,
                null, countryTxt + " / " + f.protocolName() + " / " + f.identification + (f.isTest ? " / auto-test" : "")
                        + " / " + f.rawHex());
        listener.onLog(d.title + " | " + d.text.replace('\n', ' '));
        listener.onDetection(d);
    }

    private void onSonde(Sondes.Fix f) {
        int count = bump("R" + f.key());
        String name = f.type + (f.id != null && !f.id.isEmpty() ? " " + f.id : "");
        Detection d = new Detection();
        d.kind = f.type;
        d.id = f.id;
        d.count = count;
        d.title = name + (count > 1 ? " ×" + count : "");
        StringBuilder text = new StringBuilder();
        text.append(fmt(f.lat, 5)).append(", ").append(fmt(f.lon, 5)).append("  alt ").append(Math.round(f.alt)).append(" m");
        text.append("\nVz ").append(fmt(f.vSpeed, 1)).append(" m/s  –  ").append(fmt(f.hSpeed * 3.6, 0)).append(" km/h cap ")
                .append(Math.round(f.heading)).append("°");
        if (f.sats > 0) text.append("  sats ").append(f.sats);
        if (f.tempC != null) text.append("\nT ").append(fmt(f.tempC, 1)).append(" °C");
        text.append("\n").append(utc(f.timeUtcMs)).append(" UTC");
        d.text = text.toString();
        d.hasPos = true;
        d.lat = f.lat;
        d.lon = f.lon;
        logCsv(f.type, f.id, true, f.lat, f.lon, f.alt, f.hSpeed, f.vSpeed, f.heading, f.sats, f.tempC, utc(f.timeUtcMs));
        listener.onLog(d.title + " | " + d.text.replace('\n', ' '));
        listener.onDetection(d);
    }

    static String fmt(double v, int dec) { return String.format(Locale.ROOT, "%." + dec + "f", v); }

    static String utc(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    private File dir(String sub) {
        File d = sub == null ? outDir : new File(outDir, sub);
        if (!d.isDirectory() && !d.mkdirs()) return null;
        return d;
    }

    private synchronized void logCsv(String type, String id, boolean hasPos, double lat, double lon, double alt, double vh,
                                     double vz, double cap, int sats, Double temp, String extra) {
        File d = dir(null);
        if (d == null) return;
        File f = new File(d, "decodages.csv");
        boolean header = !f.exists();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            if (header) w.write("recu_utc;type;id;lat;lon;alt_m;vh_ms;vz_ms;cap;sats;temp_c;details\r\n");
            w.write(utc(System.currentTimeMillis()) + ";" + type + ";" + (id == null ? "" : id) + ";"
                    + (hasPos ? fmt(lat, 6) : "") + ";" + (hasPos ? fmt(lon, 6) : "") + ";" + num(alt, 1) + ";"
                    + num(vh, 1) + ";" + num(vz, 1) + ";" + num(cap, 0) + ";" + (sats >= 0 ? sats : "") + ";"
                    + (temp == null ? "" : fmt(temp, 1)) + ";" + (extra == null ? "" : extra.replace(';', ',')) + "\r\n");
        } catch (IOException e) {
            listener.onLog("CSV : " + e.getMessage());
        }
    }

    /** Ligne du journal CSV pour un decodage d'un autre module (POCSAG...). */
    void logExternal(String type, String id, String details) {
        logCsv(type, id, false, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, null, details);
    }

    private static String num(double v, int dec) { return Double.isNaN(v) ? "" : fmt(v, dec); }

    /** Enregistre un WAV mono 16 bits / 32 kHz (diagnostic) ; garde les 60 plus recents. */
    private void saveWav(String prefix, short[] pcm, int n) {
        if (!saveWav) return;
        File d = dir("wav");
        if (d == null) return;
        SimpleDateFormat sf = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT);
        sf.setTimeZone(TimeZone.getTimeZone("UTC"));
        File f = new File(d, prefix + "_" + sf.format(new Date()) + ".wav");
        try {
            Wav.write(f, pcm, n, SAMPLE_RATE);
        } catch (IOException e) {
            listener.onLog("WAV : " + e.getMessage());
        }
        File[] all = d.listFiles();
        if (all != null && all.length > 60) {
            Arrays.sort(all);
            for (int i = 0; i < all.length - 60; i++) all[i].delete();
        }
    }
}
