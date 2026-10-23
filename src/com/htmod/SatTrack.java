/*
 * HTMOD Windows : geometrie de poursuite a partir de SGP4 : repere TEME -> terrestre (rotation par le
 * temps sideral de Greenwich), position geodesique WGS-84, azimut / elevation / distance / vitesse
 * radiale depuis l'observateur (Doppler), empreinte au sol, trace et passages.
 */
package com.htmod;

import java.util.ArrayList;
import java.util.List;

final class SatTrack {
    private static final double WE = 7.292115e-5;            // rotation terrestre (rad/s)
    private static final double A = 6378.137, F = 1 / 298.257223563, E2 = F * (2 - F); // WGS-84
    static final double C_KMS = 299792.458;

    private SatTrack() {}

    /** Position d'observation. */
    static final class Observer {
        final double latDeg, lonDeg, altKm;
        final double[] ecef;

        Observer(double latDeg, double lonDeg, double altKm) {
            this.latDeg = latDeg;
            this.lonDeg = lonDeg;
            this.altKm = altKm;
            double lat = Math.toRadians(latDeg), lon = Math.toRadians(lonDeg);
            double n = A / Math.sqrt(1 - E2 * Math.sin(lat) * Math.sin(lat));
            ecef = new double[]{(n + altKm) * Math.cos(lat) * Math.cos(lon), (n + altKm) * Math.cos(lat) * Math.sin(lon),
                (n * (1 - E2) + altKm) * Math.sin(lat)};
        }
    }

    /** Etat d'un satellite a un instant donne. */
    static final class Look {
        double latDeg, lonDeg, altKm;   // point sous le satellite
        double azDeg, elDeg, rangeKm;   // vu de l'observateur
        double rangeRateKms;            // > 0 : s'eloigne
        double footprintKm;             // rayon de l'empreinte au sol (visibilite a 0 deg)

        /** Frequence recue pour une emission a f (Hz) du satellite. */
        long downlink(long f) { return Math.round(f * (1 - rangeRateKms / C_KMS)); }

        /** Frequence a emettre pour que le satellite recoive f (Hz). */
        long uplink(long f) { return Math.round(f * (1 + rangeRateKms / C_KMS)); }
    }

    static final class Pass {
        long aosMs, tcaMs, losMs;
        double maxElDeg, aosAzDeg, losAzDeg;
    }

    /** Etat a l'instant ms (UTC) ; null si la propagation echoue. */
    static Look look(Sgp4 p, long ms, Observer obs) {
        double jd = Sgp4.jdOf(ms);
        double tsince = (jd - p.jdEpoch) * 1440.0;
        double[] rv = p.propagate(tsince);
        if (rv == null) return null;
        double g = Sgp4.gstime(jd), cg = Math.cos(g), sg = Math.sin(g);
        double x = cg * rv[0] + sg * rv[1], y = -sg * rv[0] + cg * rv[1], z = rv[2];
        double vx = cg * rv[3] + sg * rv[4] + WE * y, vy = -sg * rv[3] + cg * rv[4] - WE * x, vz = rv[5];
        Look l = new Look();
        // geodesique (iteration de Bowring simplifiee)
        double lon = Math.atan2(y, x), pxy = Math.hypot(x, y), lat = Math.atan2(z, pxy * (1 - E2)), h = 0;
        for (int i = 0; i < 6; i++) {
            double sl = Math.sin(lat), n = A / Math.sqrt(1 - E2 * sl * sl);
            h = pxy / Math.cos(lat) - n;
            lat = Math.atan2(z, pxy * (1 - E2 * n / (n + h)));
        }
        l.latDeg = Math.toDegrees(lat);
        l.lonDeg = Math.toDegrees(lon);
        l.altKm = h;
        l.footprintKm = A * Math.acos(A / (A + Math.max(h, 1)));
        if (obs != null) {
            double rx = x - obs.ecef[0], ry = y - obs.ecef[1], rz = z - obs.ecef[2];
            double la = Math.toRadians(obs.latDeg), lo = Math.toRadians(obs.lonDeg);
            double sla = Math.sin(la), cla = Math.cos(la), slo = Math.sin(lo), clo = Math.cos(lo);
            double s = sla * clo * rx + sla * slo * ry - cla * rz;
            double e = -slo * rx + clo * ry;
            double zz = cla * clo * rx + cla * slo * ry + sla * rz;
            double range = Math.sqrt(rx * rx + ry * ry + rz * rz);
            l.rangeKm = range;
            l.elDeg = Math.toDegrees(Math.asin(zz / range));
            double az = Math.toDegrees(Math.atan2(e, -s));
            l.azDeg = az < 0 ? az + 360 : az;
            l.rangeRateKms = (rx * vx + ry * vy + rz * vz) / range;
        }
        return l;
    }

    private static double el(Sgp4 p, long ms, Observer o) {
        Look l = look(p, ms, o);
        return l == null ? -90 : l.elDeg;
    }

    /** Passages (elevation > 0) entre fromMs et fromMs + hours ; maxEl filtre les passages trop bas. */
    static List<Pass> passes(Sgp4 p, Observer o, long fromMs, double hours, double minMaxEl) {
        List<Pass> out = new ArrayList<>();
        long end = fromMs + (long) (hours * 3600_000L), step = 30_000;
        double prev = el(p, fromMs, o);
        long t = fromMs;
        Pass cur = null;
        if (prev > 0) { cur = new Pass(); cur.aosMs = fromMs; cur.maxElDeg = prev; cur.tcaMs = fromMs; }
        while (t < end) {
            long nt = t + step;
            double e = el(p, nt, o);
            if (prev <= 0 && e > 0) {
                cur = new Pass();
                cur.aosMs = refine(p, o, t, nt);
                cur.maxElDeg = e;
                cur.tcaMs = nt;
            } else if (prev > 0 && e <= 0 && cur != null) {
                cur.losMs = refine(p, o, t, nt);
                finish(p, o, cur);
                if (cur.maxElDeg >= minMaxEl) out.add(cur);
                cur = null;
            }
            if (cur != null && e > cur.maxElDeg) { cur.maxElDeg = e; cur.tcaMs = nt; }
            prev = e;
            t = nt;
            if (prev < -40 && cur == null) t += 60_000; // loin sous l'horizon : on avance plus vite
        }
        if (cur != null) { cur.losMs = end; finish(p, o, cur); if (cur.maxElDeg >= minMaxEl) out.add(cur); }
        return out;
    }

    private static void finish(Sgp4 p, Observer o, Pass ps) {
        // affinage du maximum autour de l'echantillon le plus haut
        long a = Math.max(ps.aosMs, ps.tcaMs - 30_000), b = Math.min(ps.losMs, ps.tcaMs + 30_000);
        for (long t = a; t <= b; t += 2_000) {
            double e = el(p, t, o);
            if (e > ps.maxElDeg) { ps.maxElDeg = e; ps.tcaMs = t; }
        }
        Look la = look(p, ps.aosMs, o), ll = look(p, ps.losMs, o);
        if (la != null) ps.aosAzDeg = la.azDeg;
        if (ll != null) ps.losAzDeg = ll.azDeg;
    }

    /** Instant (ms) du passage de l'elevation par 0 entre a (dessous/dessus) et b, a la seconde. */
    private static long refine(Sgp4 p, Observer o, long a, long b) {
        boolean upA = el(p, a, o) > 0;
        while (b - a > 1000) {
            long m = (a + b) / 2;
            if ((el(p, m, o) > 0) == upA) a = m; else b = m;
        }
        return b;
    }

    /** Trace au sol : {lat, lon} tous les stepMs entre from et to. */
    static List<double[]> groundTrack(Sgp4 p, long from, long to, long stepMs) {
        List<double[]> out = new ArrayList<>();
        for (long t = from; t <= to; t += stepMs) {
            Look l = look(p, t, null);
            if (l != null) out.add(new double[]{l.latDeg, l.lonDeg});
        }
        return out;
    }
}
