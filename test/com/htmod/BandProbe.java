package com.htmod;

import java.io.File;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Essai reel du balayage VFO (reception seule, rien n'est ecrit dans le poste). */
public class BandProbe {
    public static void main(String[] a) throws Exception {
        LinkedBlockingQueue<int[]> st = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Long> vfo = new LinkedBlockingQueue<>();
        String[] chan = {""};
        Radio r = new Radio(new File(a[0]), new Radio.Listener() {
            public void onStatus(String t) {}
            public void onLog(String t) { System.out.println("[log] " + t); }
            public void onChannel(String n, boolean rx) { chan[0] = n; }
            public void onAudio(short[] p, int len) {}
            public void onAudioEnd() {}
            public void onDisconnected() { System.out.println("[deconnecte]"); }
            @Override public void onHtStatus(int rssi, boolean sq) { st.offer(new int[]{rssi, sq ? 1 : 0}); }
            @Override public void onVfoFreq(long hz) { vfo.offer(hz); }
        });
        r.connect(a[1]);
        Thread.sleep(9000);
        long center = r.currentRxHz();
        System.out.println("Canal '" + chan[0] + "' a " + center + " Hz");
        if (center == 0) { r.disconnect(); System.exit(1); }
        long t0 = System.currentTimeMillis();
        for (long f = center - 100_000; f <= center + 100_000; f += 25_000) {
            st.clear();
            r.tuneVfo(f, center, false);
            Thread.sleep(150);
            r.requestHtStatus();
            int[] s = st.poll(600, TimeUnit.MILLISECONDS);
            vfo.clear();
            r.requestVfoFreq();
            Long v = vfo.poll(600, TimeUnit.MILLISECONDS);
            System.out.printf("  %.4f MHz : %s, VFO lu %s%n", f / 1e6, s == null ? "pas de reponse" : "S=" + s[0] + (s[1] == 1 ? " squelch ouvert" : ""),
                    v == null ? "?" : String.format("%.4f", v / 1e6));
        }
        System.out.println("Duree : " + (System.currentTimeMillis() - t0) + " ms pour 9 pas");
        r.stopVfo();
        Thread.sleep(1500);
        System.out.println("Apres arret : canal '" + chan[0] + "' a " + r.currentRxHz() + " Hz");
        r.disconnect();
        System.exit(0);
    }
}
