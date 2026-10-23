package com.htmod;

import java.io.File;

/** Essai reel (lecture seule) : connexion au poste, lecture des canaux, comptage de l'audio recu. */
public class LiveProbe {
    public static void main(String[] a) throws Exception {
        long[] samples = {0};
        Radio r = new Radio(new File(a[0]), new Radio.Listener() {
            public void onStatus(String t) { System.out.println("[etat] " + t); }
            public void onLog(String t) { System.out.println("[log]  " + t); }
            public void onChannel(String n, boolean rx) { System.out.println("[canal] '" + n + "' rx=" + rx); }
            public void onAudio(short[] p, int len) { samples[0] += len; }
            public void onAudioEnd() { System.out.println("[audio] fin de sequence"); }
            public void onDisconnected() { System.out.println("[deconnecte]"); }
        });
        r.connect(a[1]);
        Thread.sleep(Integer.parseInt(a[2]) * 1000L);
        System.out.println("Audio recu : " + samples[0] + " echantillons (" + samples[0] / 32 + " ms)");
        r.disconnect();
        System.exit(0);
    }
}
