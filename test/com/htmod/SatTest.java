package com.htmod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Verification de SGP4/SDP4 contre les vecteurs officiels SGP4-VER (Vallado, tcppver.out). */
final class SatTest {
    private SatTest() {}

    static void run(File data) throws Exception {
        System.out.println("Satellites (SGP4-VER) :");
        List<String> tle = Files.readAllLines(new File(data, "SGP4-VER.TLE").toPath(), StandardCharsets.ISO_8859_1);
        Map<Integer, String[]> sats = new HashMap<>();
        for (int i = 0; i + 1 < tle.size(); i++) {
            String a = tle.get(i), b = tle.get(i + 1);
            if (a.startsWith("1 ") && b.startsWith("2 ")) {
                sats.put(Integer.parseInt(a.substring(2, 7).trim()), new String[]{a, b});
                i++;
            }
        }
        List<String> out = Files.readAllLines(new File(data, "tcppver.out").toPath(), StandardCharsets.ISO_8859_1);
        Sgp4 cur = null;
        int cases = 0, points = 0, bad = 0, nearOk = 0, deepOk = 0;
        double worst = 0;
        String worstAt = "";
        for (String line : out) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.endsWith("xx")) {
                int n = Integer.parseInt(s.split("\\s+")[0]);
                String[] l = sats.get(n);
                cur = null;
                if (l != null) {
                    try { cur = Sgp4.fromTle(l[0], l[1]); cases++; } catch (IllegalArgumentException e) { /* cas d'erreur volontaire */ }
                }
                continue;
            }
            if (cur == null) continue;
            String[] f = s.split("\\s+");
            if (f.length < 7) continue;
            double t;
            try { t = Double.parseDouble(f[0]); } catch (NumberFormatException e) { continue; }
            double[] want = new double[6];
            for (int k = 0; k < 6; k++) want[k] = Double.parseDouble(f[k + 1]);
            double[] got = cur.propagate(t);
            points++;
            // 33334 : cas d'erreur volontaire de SGP4-VER (mouvement moyen 0,00001 tour/jour) : rejet attendu
            if (got == null && cur.satnum == 33334 && cur.error > 0) { points--; continue; }
            if (got == null) { bad++; System.out.println("     sans position : " + cur.satnum + " t=" + t + " erreur " + cur.error); continue; }
            double dr = Math.sqrt(sq(got[0] - want[0]) + sq(got[1] - want[1]) + sq(got[2] - want[2]));
            if (dr > worst) { worst = dr; worstAt = cur.satnum + " t=" + t; }
            if (dr > 1e-3) bad++; // 1 m
            else if (2 * Math.PI / (Double.parseDouble(cur.line2.substring(52, 63).trim()) * 2 * Math.PI / 1440) >= 225) deepOk++;
            else nearOk++;
        }
        WinTest.check(cases + " satellites de référence, " + points + " points (" + nearOk + " proches, " + deepOk
                + " espace lointain) : " + bad + " écart(s) > 1 m, pire " + String.format(java.util.Locale.ROOT, "%.6f", worst)
                + " km (" + worstAt + ")", bad == 0 && points > 400 && deepOk > 100);
        runTracking(data);
    }

    /** Geometrie de poursuite et catalogue (reponse reelle du serveur Benshi enregistree). */
    static void runTracking(File data) throws Exception {
        Sgp4 iss = Sgp4.fromTle("1 25544U 98067A   26295.53177894  .00010215  00000-0  19326-3 0  9991",
                "2 25544  51.6333  29.9219 0006847 296.1942  63.8342 15.48996468591497");
        SatTrack.Observer obs = new SatTrack.Observer(48.8566, 2.3522, 0.05);
        long t0 = Math.round((iss.jdEpoch - 2440587.5) * 86400000.0) + 3600_000L;
        SatTrack.Look l = SatTrack.look(iss, t0, obs);
        WinTest.check(String.format(java.util.Locale.ROOT, "ISS : altitude %.0f km, empreinte %.0f km", l.altKm, l.footprintKm),
                l.altKm > 380 && l.altKm < 450 && l.footprintKm > 2000 && l.footprintKm < 2400);
        double worst = 0;
        for (long t = t0; t < t0 + 6 * 3600_000L; t += 137_000) {
            SatTrack.Look a = SatTrack.look(iss, t - 500, obs), b = SatTrack.look(iss, t + 500, obs), m = SatTrack.look(iss, t, obs);
            worst = Math.max(worst, Math.abs((b.rangeKm - a.rangeKm) / 1.0 - m.rangeRateKms));
        }
        WinTest.check(String.format(java.util.Locale.ROOT, "vitesse radiale = dérivée de la distance (écart max %.4f km/s)", worst), worst < 0.002);
        List<SatTrack.Pass> ps = SatTrack.passes(iss, obs, t0, 24, 0);
        boolean sane = !ps.isEmpty();
        for (SatTrack.Pass p : ps) sane &= p.aosMs < p.tcaMs && p.tcaMs < p.losMs && p.maxElDeg > 0 && p.maxElDeg <= 90
                && (p.losMs - p.aosMs) < 15 * 60_000L;
        WinTest.check("ISS : " + ps.size() + " passage(s) sur 24 h au-dessus de Paris, cohérents", sane && ps.size() >= 3);
        // Doppler : ~ +/- 3,5 kHz a 145,8 MHz pour l'ISS
        double maxShift = 0;
        for (long t = t0; t < t0 + 24 * 3600_000L; t += 20_000) {
            SatTrack.Look k = SatTrack.look(iss, t, obs);
            if (k.elDeg > 0) maxShift = Math.max(maxShift, Math.abs(k.downlink(145_800_000) - 145_800_000));
        }
        WinTest.check(String.format(java.util.Locale.ROOT, "Doppler ISS sur 145,800 MHz : jusqu'à %.1f kHz", maxShift / 1000), maxShift > 2500 && maxShift < 3700);

        // catalogue : reponse reelle du serveur Benshi + base integree
        File tmp = new File(System.getProperty("java.io.tmpdir"), "htmod-sat-test");
        SatCatalog cat = new SatCatalog(tmp);
        int n = cat.mergeBenshi(Files.readAllBytes(new File(data, "benshi-getgp.bin").toPath()));
        SatCatalog.Sat s = cat.get(25544);
        boolean issOk = s != null && s.rf.size() >= 2 && cat.isSstv(s);
        WinTest.check("serveur Benshi : " + n + " satellite(s), ISS avec " + (s == null ? 0 : s.rf.size()) + " configuration(s) radio et SSTV",
                n >= 20 && issOk);
        cat.loadLocal();
        int sstv = 0;
        for (SatCatalog.Sat x : cat.all()) if (cat.isSstv(x)) sstv++;
        WinTest.check("base FM/SSTV intégrée : " + cat.all().size() + " satellite(s), dont " + sstv + " SSTV", cat.all().size() >= 100 && sstv >= 10);
    }

    private static double sq(double v) { return v * v; }
}
