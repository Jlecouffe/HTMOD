/*
 * HTMOD Windows : propagateur SGP4 / SDP4 (portage Java de l'algorithme de reference de Vallado,
 * « Revisiting Spacetrack Report #3 », AIAA 2006-6753 : sgp4init, sgp4, dscom, dpper, dsinit,
 * dspace), constantes WGS-72, mode « improved ». Positions et vitesses dans le repere TEME, en km
 * et km/s. Verifie contre les vecteurs officiels SGP4-VER (voir SatTest).
 */
package com.htmod;

final class Sgp4 {
    // constantes WGS-72
    static final double MU = 398600.8, RE = 6378.135;
    static final double XKE = 60.0 / Math.sqrt(RE * RE * RE / MU);
    static final double J2 = 0.001082616, J3 = -0.00000253881, J4 = -0.00000165597, J3OJ2 = J3 / J2;
    private static final double TWOPI = 2 * Math.PI, X2O3 = 2.0 / 3.0, DEG2RAD = Math.PI / 180;

    final String line1, line2;
    final int satnum;
    /** Epoque en jours juliens. */
    final double jdEpoch;
    int error;

    // elements
    private double ecco, argpo, inclo, mo, nodeo, bstar, no; // no = mouvement moyen « unkozai » (rad/min)
    // near earth
    private int isimp;
    private boolean deep;
    private double aycof, con41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof, sinmao, t2cof, t3cof, t4cof, t5cof,
            x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf, gsto;
    // deep space
    private int irez;
    private double d2201, d2211, d3210, d3222, d4410, d4422, d5220, d5232, d5421, d5433, dedt, del1, del2, del3, didt, dmdt,
            dnodt, domdt, e3, ee2, peo, pgho, pho, pinco, plo, se2, se3, sgh2, sgh3, sgh4, sh2, sh3, si2, si3, sl2, sl3, sl4,
            xfact, xgh2, xgh3, xgh4, xh2, xh3, xi2, xi3, xl2, xl3, xl4, xlamo, zmol, zmos, atime, xli, xni;

    private Sgp4(String l1, String l2) {
        line1 = l1;
        line2 = l2;
        int sn;
        try { sn = Integer.parseInt(sub(l1, 2, 7)); } catch (NumberFormatException e) { sn = 0; } // numeros « alpha-5 »
        satnum = sn;
        int epochyr = Integer.parseInt(sub(l1, 18, 20));
        double epochdays = Double.parseDouble(sub(l1, 20, 32));
        bstar = exp(sub(l1, 53, 61));
        inclo = Double.parseDouble(sub(l2, 8, 16)) * DEG2RAD;
        nodeo = Double.parseDouble(sub(l2, 17, 25)) * DEG2RAD;
        ecco = Double.parseDouble("0." + sub(l2, 26, 33));
        argpo = Double.parseDouble(sub(l2, 34, 42)) * DEG2RAD;
        mo = Double.parseDouble(sub(l2, 43, 51)) * DEG2RAD;
        double noKozai = Double.parseDouble(sub(l2, 52, 63)) / (1440.0 / TWOPI);
        int year = epochyr < 57 ? epochyr + 2000 : epochyr + 1900;
        jdEpoch = jday(year, 1, 1, 0, 0, 0) + epochdays - 1;
        init(jdEpoch - 2433281.5, noKozai);
    }

    private static String sub(String s, int a, int b) {
        if (a >= s.length()) return "";
        return s.substring(a, Math.min(b, s.length())).trim();
    }

    /** Parse « +12345-3 » (mantisse implicite 0.12345, exposant). */
    private static double exp(String s) {
        s = s.trim();
        if (s.length() < 3) return 0;
        String m = s.substring(0, s.length() - 2).trim(), e = s.substring(s.length() - 2).trim();
        char sign = '+';
        if (m.startsWith("-") || m.startsWith("+")) { sign = m.charAt(0); m = m.substring(1); }
        double v = Double.parseDouble("0." + m.trim()) * Math.pow(10, Integer.parseInt(e.replace("+", "")));
        return sign == '-' ? -v : v;
    }

    /** Elements a deux lignes ; IllegalArgumentException si les lignes sont mal formees. */
    static Sgp4 fromTle(String l1, String l2) {
        if (l1 == null || l2 == null || !l1.startsWith("1 ") || !l2.startsWith("2 ") || l1.length() < 61 || l2.length() < 63)
            throw new IllegalArgumentException("lignes TLE invalides");
        try {
            return new Sgp4(l1, l2);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("lignes TLE invalides : " + e.getMessage());
        }
    }

    static double jday(int y, int mo, int d, int h, int mi, double s) {
        return 367.0 * y - Math.floor(7 * (y + Math.floor((mo + 9) / 12.0)) * 0.25) + Math.floor(275 * mo / 9.0) + d + 1721013.5
                + ((s / 60.0 + mi) / 60.0 + h) / 24.0;
    }

    /** Temps sideral moyen de Greenwich (rad) pour une date julienne UT1. */
    static double gstime(double jdut1) {
        double tut1 = (jdut1 - 2451545.0) / 36525.0;
        double temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841;
        temp = (temp * DEG2RAD / 240.0) % TWOPI;
        if (temp < 0) temp += TWOPI;
        return temp;
    }

    /** Date julienne d'un instant Java (ms depuis 1970, UTC). */
    static double jdOf(long ms) { return ms / 86400000.0 + 2440587.5; }

    // ------------------------------------------------------------------ Initialisation

    private void init(double epoch, double noKozai) {
        double ss = 78.0 / RE + 1.0, qzms2ttemp = (120.0 - 78.0) / RE, qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp;

        // --- initl
        double eccsq = ecco * ecco, omeosq = 1.0 - eccsq, rteosq = Math.sqrt(omeosq), cosio = Math.cos(inclo), cosio2 = cosio * cosio;
        double ak = Math.pow(XKE / noKozai, X2O3);
        double d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
        double delPrime = d1 / (ak * ak);
        double adel = ak * (1.0 - delPrime * delPrime - delPrime * (1.0 / 3.0 + 134.0 * delPrime * delPrime / 81.0));
        delPrime = d1 / (adel * adel);
        no = noKozai / (1.0 + delPrime);
        double ao = Math.pow(XKE / no, X2O3), sinio = Math.sin(inclo), po = ao * omeosq, con42 = 1.0 - 5.0 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po, rp = ao * (1.0 - ecco);
        gsto = gstime(epoch + 2433281.5);

        if (omeosq >= 0.0 || no >= 0.0) {
            isimp = rp < 220.0 / RE + 1.0 ? 1 : 0;
            double sfour = ss, qzms24 = qzms2t, perige = (rp - 1.0) * RE;
            if (perige < 156.0) {
                sfour = perige - 78.0;
                if (perige < 98.0) sfour = 20.0;
                double qzms24temp = (120.0 - sfour) / RE;
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp;
                sfour = sfour / RE + 1.0;
            }
            double pinvsq = 1.0 / posq, tsi = 1.0 / (ao - sfour);
            eta = ao * ecco * tsi;
            double etasq = eta * eta, eeta = ecco * eta, psisq = Math.abs(1.0 - etasq);
            double coef = qzms24 * Math.pow(tsi, 4.0), coef1 = coef / Math.pow(psisq, 3.5);
            double cc2 = coef1 * no * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq))
                    + 0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)));
            cc1 = bstar * cc2;
            double cc3 = 0.0;
            if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * J3OJ2 * no * sinio / ecco;
            x1mth2 = 1.0 - cosio2;
            cc4 = 2.0 * no * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq)
                    - J2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta))
                    + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * Math.cos(2.0 * argpo)));
            cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq);
            double cosio4 = cosio2 * cosio2, temp1 = 1.5 * J2 * pinvsq * no, temp2 = 0.5 * temp1 * J2 * pinvsq,
                    temp3 = -0.46875 * J4 * pinvsq * pinvsq * no;
            mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
            argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4)
                    + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4);
            double xhdot1 = -temp1 * cosio;
            nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;
            double xpidot = argpdot + nodedot;
            omgcof = bstar * cc3 * Math.cos(argpo);
            xmcof = 0.0;
            if (ecco > 1.0e-4) xmcof = -X2O3 * coef * bstar / eeta;
            nodecf = 3.5 * omeosq * xhdot1 * cc1;
            t2cof = 1.5 * cc1;
            if (Math.abs(cosio + 1.0) > 1.5e-12) xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio);
            else xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / 1.5e-12;
            aycof = -0.5 * J3OJ2 * sinio;
            double delmotemp = 1.0 + eta * Math.cos(mo);
            delmo = delmotemp * delmotemp * delmotemp;
            sinmao = Math.sin(mo);
            x7thm1 = 7.0 * cosio2 - 1.0;

            if (TWOPI / no >= 225.0) { // espace lointain
                deep = true;
                isimp = 1;
                double tc = 0.0;
                Ds ds = dscom(epoch, ecco, argpo, tc, inclo, nodeo, no);
                dsinit(ds, ds.cosim, argpo, 0.0, tc, mo, mdot, no, nodeo, nodedot, xpidot, ecco, eccsq);
            }

            if (isimp != 1) {
                double cc1sq = cc1 * cc1;
                d2 = 4.0 * ao * tsi * cc1sq;
                double temp = d2 * tsi * cc1 / 3.0;
                d3 = (17.0 * ao + sfour) * temp;
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1;
                t3cof = d2 + 2.0 * cc1sq;
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq));
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq));
            }
        }
        propagate(0.0);
    }

    // ------------------------------------------------------------------ Propagation

    /**
     * Position et vitesse TEME (km, km/s) a tsince minutes de l'epoque : {x, y, z, vx, vy, vz},
     * ou null si le calcul echoue (error 1-4 : elements incorrects) ; error 6 = satellite retombe.
     */
    double[] propagate(double tsince) {
        double vkmpersec = RE * XKE / 60.0;
        double t = tsince;
        error = 0;
        double xmdf = mo + mdot * t, argpdf = argpo + argpdot * t, nodedf = nodeo + nodedot * t;
        double argpm = argpdf, mm = xmdf, t2 = t * t, nodem = nodedf + nodecf * t2;
        double tempa = 1.0 - cc1 * t, tempe = bstar * cc4 * t, templ = t2cof * t2;
        if (isimp != 1) {
            double delomg = omgcof * t, delmtemp = 1.0 + eta * Math.cos(xmdf);
            double delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * t, t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }
        double nm = no, em = ecco, inclm = inclo;
        if (deep) {
            double[] o = dspace(t, t, em, argpm, inclm, mm, nodem, nm);
            em = o[0]; argpm = o[1]; inclm = o[2]; mm = o[3]; nodem = o[4]; nm = o[5];
        }
        if (nm <= 0.0) { error = 2; return null; }
        double am = Math.pow(XKE / nm, X2O3) * tempa * tempa;
        nm = XKE / Math.pow(am, 1.5);
        em = em - tempe;
        if (em >= 1.0 || em < -0.001) { error = 1; return null; }
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + no * templ;
        double xlm = mm + argpm + nodem;
        nodem = nodem % TWOPI;
        argpm = argpm % TWOPI;
        xlm = xlm % TWOPI;
        mm = (xlm - argpm - nodem) % TWOPI;
        double sinim = Math.sin(inclm), cosim = Math.cos(inclm);

        double ep = em, xincp = inclm, argpp = argpm, nodep = nodem, mp = mm, sinip = sinim, cosip = cosim;
        double lcof = xlcof, acof = aycof, c41 = con41, x1m = x1mth2, x7 = x7thm1;
        if (deep) {
            double[] p = dpper(t, false, ep, xincp, nodep, argpp, mp);
            ep = p[0]; xincp = p[1]; nodep = p[2]; argpp = p[3]; mp = p[4];
            if (xincp < 0.0) { xincp = -xincp; nodep = nodep + Math.PI; argpp = argpp - Math.PI; }
            if (ep < 0.0 || ep > 1.0) { error = 3; return null; }
            sinip = Math.sin(xincp);
            cosip = Math.cos(xincp);
            acof = -0.5 * J3OJ2 * sinip;
            if (Math.abs(cosip + 1.0) > 1.5e-12) lcof = -0.25 * J3OJ2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip);
            else lcof = -0.25 * J3OJ2 * sinip * (3.0 + 5.0 * cosip) / 1.5e-12;
        }
        double axnl = ep * Math.cos(argpp);
        double temp = 1.0 / (am * (1.0 - ep * ep));
        double aynl = ep * Math.sin(argpp) + temp * acof;
        double xl = mp + argpp + nodep + temp * lcof * axnl;

        // equation de Kepler
        double u = (xl - nodep) % TWOPI, eo1 = u, tem5 = 9999.9, sineo1 = 0, coseo1 = 0;
        int ktr = 1;
        while (Math.abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0.0 ? 0.95 : -0.95;
            eo1 = eo1 + tem5;
            ktr++;
        }
        double ecose = axnl * coseo1 + aynl * sineo1, esine = axnl * sineo1 - aynl * coseo1, el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1.0 - el2);
        if (pl < 0.0) { error = 4; return null; }
        double rl = am * (1.0 - ecose), rdotl = Math.sqrt(am) * esine / rl, rvdotl = Math.sqrt(pl) / rl, betal = Math.sqrt(1.0 - el2);
        temp = esine / (1.0 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp), cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu), sin2u = (cosu + cosu) * sinu, cos2u = 1.0 - 2.0 * sinu * sinu;
        temp = 1.0 / pl;
        double temp1 = 0.5 * J2 * temp, temp2 = temp1 * temp;
        if (deep) {
            double cosisq = cosip * cosip;
            c41 = 3.0 * cosisq - 1.0;
            x1m = 1.0 - cosisq;
            x7 = 7.0 * cosisq - 1.0;
        }
        double mrt = rl * (1.0 - 1.5 * temp2 * betal * c41) + 0.5 * temp1 * x1m * cos2u;
        su = su - 0.25 * temp2 * x7 * sin2u;
        double xnode = nodep + 1.5 * temp2 * cosip * sin2u, xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u;
        double mvt = rdotl - nm * temp1 * x1m * sin2u / XKE, rvdot = rvdotl + nm * temp1 * (x1m * cos2u + 1.5 * c41) / XKE;
        double sinsu = Math.sin(su), cossu = Math.cos(su), snod = Math.sin(xnode), cnod = Math.cos(xnode);
        double sini = Math.sin(xinc), cosi = Math.cos(xinc), xmx = -snod * cosi, xmy = cnod * cosi;
        double ux = xmx * sinsu + cnod * cossu, uy = xmy * sinsu + snod * cossu, uz = sini * sinsu;
        double vx = xmx * cossu - cnod * sinsu, vy = xmy * cossu - snod * sinsu, vz = sini * cossu;
        double[] r = {mrt * ux * RE, mrt * uy * RE, mrt * uz * RE,
            (mvt * ux + rvdot * vx) * vkmpersec, (mvt * uy + rvdot * vy) * vkmpersec, (mvt * uz + rvdot * vz) * vkmpersec};
        if (mrt < 1.0) error = 6; // sous la surface : signale, position renvoyee comme la reference
        return r;
    }

    // ------------------------------------------------------------------ Espace lointain

    /** Resultats de dscom utiles a dsinit. */
    private static final class Ds {
        double sinim, cosim, emsq, em, nm, s1, s2, s3, s4, s5, ss1, ss2, ss3, ss4, ss5, sz1, sz3, sz11, sz13, sz21, sz23, sz31, sz33,
                z1, z3, z11, z13, z21, z23, z31, z33;
    }

    private Ds dscom(double epoch, double ep, double argpp, double tc, double inclp, double nodep, double np) {
        final double zes = 0.01675, zel = 0.05490, c1ss = 2.9864797e-6, c1l = 4.7968065e-7, zsinis = 0.39785416, zcosis = 0.91744867,
                zcosgs = 0.1945905, zsings = -0.98088458;
        Ds o = new Ds();
        double nm = np, em = ep, snodm = Math.sin(nodep), cnodm = Math.cos(nodep), sinomm = Math.sin(argpp), cosomm = Math.cos(argpp);
        double sinim = Math.sin(inclp), cosim = Math.cos(inclp), emsq = em * em, betasq = 1.0 - emsq, rtemsq = Math.sqrt(betasq);
        peo = 0; pinco = 0; plo = 0; pgho = 0; pho = 0;
        double day = epoch + 18261.5 + tc / 1440.0;
        double xnodce = (4.5236020 - 9.2422029e-4 * day) % TWOPI;
        double stem = Math.sin(xnodce), ctem = Math.cos(xnodce);
        double zcosil = 0.91375164 - 0.03568096 * ctem, zsinil = Math.sqrt(1.0 - zcosil * zcosil);
        double zsinhl = 0.089683511 * stem / zsinil, zcoshl = Math.sqrt(1.0 - zsinhl * zsinhl);
        double gam = 5.8351514 + 0.0019443680 * day;
        double zx = 0.39785416 * stem / zsinil, zy = zcoshl * ctem + 0.91744867 * zsinhl * stem;
        zx = Math.atan2(zx, zy);
        zx = gam + zx - xnodce;
        double zcosgl = Math.cos(zx), zsingl = Math.sin(zx);
        double zcosg = zcosgs, zsing = zsings, zcosi = zcosis, zsini = zsinis, zcosh = cnodm, zsinh = snodm, cc = c1ss, xnoi = 1.0 / nm;
        double s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0, z1 = 0, z2 = 0, z3 = 0, z11 = 0, z12 = 0, z13 = 0, z21 = 0, z22 = 0,
                z23 = 0, z31 = 0, z32 = 0, z33 = 0;
        double ss1 = 0, ss2 = 0, ss3 = 0, ss4 = 0, ss5 = 0, ss6 = 0, ss7 = 0, sz1 = 0, sz2 = 0, sz3 = 0, sz11 = 0, sz12 = 0, sz13 = 0,
                sz21 = 0, sz22 = 0, sz23 = 0, sz31 = 0, sz32 = 0, sz33 = 0;
        for (int lsflg = 1; lsflg <= 2; lsflg++) {
            double a1 = zcosg * zcosh + zsing * zcosi * zsinh, a3 = -zsing * zcosh + zcosg * zcosi * zsinh;
            double a7 = -zcosg * zsinh + zsing * zcosi * zcosh, a8 = zsing * zsini, a9 = zsing * zsinh + zcosg * zcosi * zcosh;
            double a10 = zcosg * zsini, a2 = cosim * a7 + sinim * a8, a4 = cosim * a9 + sinim * a10;
            double a5 = -sinim * a7 + cosim * a8, a6 = -sinim * a9 + cosim * a10;
            double x1 = a1 * cosomm + a2 * sinomm, x2 = a3 * cosomm + a4 * sinomm, x3 = -a1 * sinomm + a2 * cosomm;
            double x4 = -a3 * sinomm + a4 * cosomm, x5 = a5 * sinomm, x6 = a6 * sinomm, x7 = a5 * cosomm, x8 = a6 * cosomm;
            z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3;
            z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4;
            z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4;
            z1 = 3.0 * (a1 * a1 + a2 * a2) + z31 * emsq;
            z2 = 6.0 * (a1 * a3 + a2 * a4) + z32 * emsq;
            z3 = 3.0 * (a3 * a3 + a4 * a4) + z33 * emsq;
            z11 = -6.0 * a1 * a5 + emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5);
            z12 = -6.0 * (a1 * a6 + a3 * a5) + emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5));
            z13 = -6.0 * a3 * a6 + emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6);
            z21 = 6.0 * a2 * a5 + emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7);
            z22 = 6.0 * (a4 * a5 + a2 * a6) + emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8));
            z23 = 6.0 * a4 * a6 + emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8);
            z1 = z1 + z1 + betasq * z31;
            z2 = z2 + z2 + betasq * z32;
            z3 = z3 + z3 + betasq * z33;
            s3 = cc * xnoi;
            s2 = -0.5 * s3 / rtemsq;
            s4 = s3 * rtemsq;
            s1 = -15.0 * em * s4;
            s5 = x1 * x3 + x2 * x4;
            s6 = x2 * x3 + x1 * x4;
            s7 = x2 * x4 - x1 * x3;
            if (lsflg == 1) {
                ss1 = s1; ss2 = s2; ss3 = s3; ss4 = s4; ss5 = s5; ss6 = s6; ss7 = s7;
                sz1 = z1; sz2 = z2; sz3 = z3; sz11 = z11; sz12 = z12; sz13 = z13; sz21 = z21; sz22 = z22; sz23 = z23;
                sz31 = z31; sz32 = z32; sz33 = z33;
                zcosg = zcosgl; zsing = zsingl; zcosi = zcosil; zsini = zsinil;
                zcosh = zcoshl * cnodm + zsinhl * snodm;
                zsinh = snodm * zcoshl - cnodm * zsinhl;
                cc = c1l;
            }
        }
        zmol = (4.7199672 + 0.22997150 * day - gam) % TWOPI;
        zmos = (6.2565837 + 0.017201977 * day) % TWOPI;
        se2 = 2.0 * ss1 * ss6; se3 = 2.0 * ss1 * ss7; si2 = 2.0 * ss2 * sz12; si3 = 2.0 * ss2 * (sz13 - sz11);
        sl2 = -2.0 * ss3 * sz2; sl3 = -2.0 * ss3 * (sz3 - sz1); sl4 = -2.0 * ss3 * (-21.0 - 9.0 * emsq) * zes;
        sgh2 = 2.0 * ss4 * sz32; sgh3 = 2.0 * ss4 * (sz33 - sz31); sgh4 = -18.0 * ss4 * zes;
        sh2 = -2.0 * ss2 * sz22; sh3 = -2.0 * ss2 * (sz23 - sz21);
        ee2 = 2.0 * s1 * s6; e3 = 2.0 * s1 * s7; xi2 = 2.0 * s2 * z12; xi3 = 2.0 * s2 * (z13 - z11);
        xl2 = -2.0 * s3 * z2; xl3 = -2.0 * s3 * (z3 - z1); xl4 = -2.0 * s3 * (-21.0 - 9.0 * emsq) * zel;
        xgh2 = 2.0 * s4 * z32; xgh3 = 2.0 * s4 * (z33 - z31); xgh4 = -18.0 * s4 * zel;
        xh2 = -2.0 * s2 * z22; xh3 = -2.0 * s2 * (z23 - z21);

        o.sinim = sinim; o.cosim = cosim; o.emsq = emsq; o.em = em; o.nm = nm;
        o.s1 = s1; o.s2 = s2; o.s3 = s3; o.s4 = s4; o.s5 = s5;
        o.ss1 = ss1; o.ss2 = ss2; o.ss3 = ss3; o.ss4 = ss4; o.ss5 = ss5;
        o.sz1 = sz1; o.sz3 = sz3; o.sz11 = sz11; o.sz13 = sz13; o.sz21 = sz21; o.sz23 = sz23; o.sz31 = sz31; o.sz33 = sz33;
        o.z1 = z1; o.z3 = z3; o.z11 = z11; o.z13 = z13; o.z21 = z21; o.z23 = z23; o.z31 = z31; o.z33 = z33;
        return o;
    }

    /** Perturbations luni-solaires periodiques ; renvoie {ep, inclp, nodep, argpp, mp}. */
    private double[] dpper(double t, boolean init, double ep, double inclp, double nodep, double argpp, double mp) {
        final double zns = 1.19459e-5, zes = 0.01675, znl = 1.5835218e-4, zel = 0.05490;
        double zm = init ? zmos : zmos + zns * t;
        double zf = zm + 2.0 * zes * Math.sin(zm), sinzf = Math.sin(zf);
        double f2 = 0.5 * sinzf * sinzf - 0.25, f3 = -0.5 * sinzf * Math.cos(zf);
        double ses = se2 * f2 + se3 * f3, sis = si2 * f2 + si3 * f3, sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf;
        double sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf, shs = sh2 * f2 + sh3 * f3;
        zm = init ? zmol : zmol + znl * t;
        zf = zm + 2.0 * zel * Math.sin(zm);
        sinzf = Math.sin(zf);
        f2 = 0.5 * sinzf * sinzf - 0.25;
        f3 = -0.5 * sinzf * Math.cos(zf);
        double sel = ee2 * f2 + e3 * f3, sil = xi2 * f2 + xi3 * f3, sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf;
        double sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf, shll = xh2 * f2 + xh3 * f3;
        double pe = ses + sel, pinc = sis + sil, pl = sls + sll, pgh = sghs + sghl, ph = shs + shll;
        if (!init) {
            pe = pe - peo; pinc = pinc - pinco; pl = pl - plo; pgh = pgh - pgho; ph = ph - pho;
            inclp = inclp + pinc;
            ep = ep + pe;
            double sinip = Math.sin(inclp), cosip = Math.cos(inclp);
            if (inclp >= 0.2) {
                ph = ph / sinip;
                pgh = pgh - cosip * ph;
                argpp = argpp + pgh;
                nodep = nodep + ph;
                mp = mp + pl;
            } else { // methode de Lyddane
                double sinop = Math.sin(nodep), cosop = Math.cos(nodep);
                double alfdp = sinip * sinop, betdp = sinip * cosop;
                double dalf = ph * cosop + pinc * cosip * sinop, dbet = -ph * sinop + pinc * cosip * cosop;
                alfdp = alfdp + dalf;
                betdp = betdp + dbet;
                nodep = nodep % TWOPI;
                double xls = mp + argpp + cosip * nodep, dls = pl + pgh - pinc * nodep * sinip;
                xls = xls + dls;
                double xnoh = nodep;
                nodep = Math.atan2(alfdp, betdp);
                if (Math.abs(xnoh - nodep) > Math.PI) nodep = nodep < xnoh ? nodep + TWOPI : nodep - TWOPI;
                mp = mp + pl;
                argpp = xls - mp - cosip * nodep;
            }
        }
        return new double[]{ep, inclp, nodep, argpp, mp};
    }

    private void dsinit(Ds d, double cosim, double argpo0, double t, double tc, double mo0, double mdot0, double no0, double nodeo0,
                        double nodedot0, double xpidot, double ecco0, double eccsq) {
        final double q22 = 1.7891679e-6, q31 = 2.1460748e-6, q33 = 2.2123015e-7, root22 = 1.7891679e-6, root44 = 7.3636953e-9,
                root54 = 2.1765803e-9, rptim = 4.37526908801129966e-3, root32 = 3.7393792e-7, root52 = 1.1428639e-7,
                znl = 1.5835218e-4, zns = 1.19459e-5;
        double nm = d.nm, em = d.em, emsq = d.emsq, sinim = d.sinim, inclm = inclo;
        irez = 0;
        if (nm < 0.0052359877 && nm > 0.0034906585) irez = 1;
        if (nm >= 8.26e-3 && nm <= 9.24e-3 && em >= 0.5) irez = 2;
        double ses = d.ss1 * zns * d.ss5, sis = d.ss2 * zns * (d.sz11 + d.sz13), sls = -zns * d.ss3 * (d.sz1 + d.sz3 - 14.0 - 6.0 * emsq);
        double sghs = d.ss4 * zns * (d.sz31 + d.sz33 - 6.0), shs = -zns * d.ss2 * (d.sz21 + d.sz23);
        if (inclm < 5.2359877e-2 || inclm > Math.PI - 5.2359877e-2) shs = 0.0;
        if (sinim != 0.0) shs = shs / sinim;
        double sgs = sghs - cosim * shs;
        dedt = ses + d.s1 * znl * d.s5;
        didt = sis + d.s2 * znl * (d.z11 + d.z13);
        dmdt = sls - znl * d.s3 * (d.z1 + d.z3 - 14.0 - 6.0 * emsq);
        double sghl = d.s4 * znl * (d.z31 + d.z33 - 6.0), shll = -znl * d.s2 * (d.z21 + d.z23);
        if (inclm < 5.2359877e-2 || inclm > Math.PI - 5.2359877e-2) shll = 0.0;
        domdt = sgs + sghl;
        dnodt = shs;
        if (sinim != 0.0) {
            domdt = domdt - cosim / sinim * shll;
            dnodt = dnodt + shll / sinim;
        }
        double theta = (gsto + tc * rptim) % TWOPI;
        em = em + dedt * t;
        if (irez != 0) {
            double aonv = Math.pow(nm / XKE, X2O3);
            if (irez == 2) {
                double cosisq = cosim * cosim, emo = em;
                em = ecco0;
                double emsqo = emsq;
                emsq = eccsq;
                double eoc = em * emsq, g201 = -0.306 - (em - 0.64) * 0.440, g211, g310, g322, g410, g422, g520, g533, g521, g532;
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq;
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc;
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc;
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc;
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc;
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc;
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc;
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc;
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc;
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc;
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc;
                    if (em > 0.715) g520 = -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc;
                    else g520 = 1464.74 - 4664.75 * em + 3763.64 * emsq;
                }
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc;
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc;
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc;
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc;
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc;
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc;
                }
                double sini2 = sinim * sinim;
                double f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq), f221 = 1.5 * sini2;
                double f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq), f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq);
                double f441 = 35.0 * sini2 * f220, f442 = 39.3750 * sini2 * sini2;
                double f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq) + 0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq));
                double f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq) + 6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq));
                double f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq));
                double f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq));
                double xno2 = nm * nm, ainv2 = aonv * aonv, temp1 = 3.0 * xno2 * ainv2, temp = temp1 * root22;
                d2201 = temp * f220 * g201;
                d2211 = temp * f221 * g211;
                temp1 = temp1 * aonv;
                temp = temp1 * root32;
                d3210 = temp * f321 * g310;
                d3222 = temp * f322 * g322;
                temp1 = temp1 * aonv;
                temp = 2.0 * temp1 * root44;
                d4410 = temp * f441 * g410;
                d4422 = temp * f442 * g422;
                temp1 = temp1 * aonv;
                temp = temp1 * root52;
                d5220 = temp * f522 * g520;
                d5232 = temp * f523 * g532;
                temp = 2.0 * temp1 * root54;
                d5421 = temp * f542 * g521;
                d5433 = temp * f543 * g533;
                xlamo = (mo0 + nodeo0 + nodeo0 - theta - theta) % TWOPI;
                xfact = mdot0 + dmdt + 2.0 * (nodedot0 + dnodt - rptim) - no0;
                em = emo;
                emsq = emsqo;
            }
            if (irez == 1) {
                double g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq), g310 = 1.0 + 2.0 * emsq, g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq);
                double f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim);
                double f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim);
                double f330 = 1.0 + cosim;
                f330 = 1.875 * f330 * f330 * f330;
                del1 = 3.0 * nm * nm * aonv * aonv;
                del2 = 2.0 * del1 * f220 * g200 * q22;
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv;
                del1 = del1 * f311 * g310 * q31 * aonv;
                xlamo = (mo0 + nodeo0 + argpo0 - theta) % TWOPI;
                xfact = mdot0 + xpidot - rptim + dmdt + domdt + dnodt - no0;
            }
            xli = xlamo;
            xni = no0;
            atime = 0.0;
        }
    }

    /** Integration des resonances ; renvoie {em, argpm, inclm, mm, nodem, nm}. */
    private double[] dspace(double t, double tc, double em, double argpm, double inclm, double mm, double nodem, double nm) {
        final double fasx2 = 0.13130908, fasx4 = 2.8843198, fasx6 = 0.37448087, g22 = 5.7686396, g32 = 0.95240898, g44 = 1.8014998,
                g52 = 1.0508330, g54 = 4.4108898, rptim = 4.37526908801129966e-3, stepp = 720.0, stepn = -720.0, step2 = 259200.0;
        double dndt = 0.0, theta = (gsto + tc * rptim) % TWOPI;
        em = em + dedt * t;
        inclm = inclm + didt * t;
        argpm = argpm + domdt * t;
        nodem = nodem + dnodt * t;
        mm = mm + dmdt * t;
        double ft = 0.0;
        if (irez != 0) {
            if (atime == 0.0 || t * atime <= 0.0 || Math.abs(t) < Math.abs(atime)) {
                atime = 0.0;
                xni = no;
                xli = xlamo;
            }
            double delt = t > 0.0 ? stepp : stepn, xndt = 0, xldot = 0, xnddt = 0;
            boolean again = true;
            while (again) {
                if (irez != 2) {
                    xndt = del1 * Math.sin(xli - fasx2) + del2 * Math.sin(2.0 * (xli - fasx4)) + del3 * Math.sin(3.0 * (xli - fasx6));
                    xldot = xni + xfact;
                    xnddt = del1 * Math.cos(xli - fasx2) + 2.0 * del2 * Math.cos(2.0 * (xli - fasx4))
                            + 3.0 * del3 * Math.cos(3.0 * (xli - fasx6));
                    xnddt = xnddt * xldot;
                } else {
                    double xomi = argpo + argpdot * atime, x2omi = xomi + xomi, x2li = xli + xli;
                    xndt = d2201 * Math.sin(x2omi + xli - g22) + d2211 * Math.sin(xli - g22) + d3210 * Math.sin(xomi + xli - g32)
                            + d3222 * Math.sin(-xomi + xli - g32) + d4410 * Math.sin(x2omi + x2li - g44) + d4422 * Math.sin(x2li - g44)
                            + d5220 * Math.sin(xomi + xli - g52) + d5232 * Math.sin(-xomi + xli - g52)
                            + d5421 * Math.sin(xomi + x2li - g54) + d5433 * Math.sin(-xomi + x2li - g54);
                    xldot = xni + xfact;
                    xnddt = d2201 * Math.cos(x2omi + xli - g22) + d2211 * Math.cos(xli - g22) + d3210 * Math.cos(xomi + xli - g32)
                            + d3222 * Math.cos(-xomi + xli - g32) + d5220 * Math.cos(xomi + xli - g52) + d5232 * Math.cos(-xomi + xli - g52)
                            + 2.0 * (d4410 * Math.cos(x2omi + x2li - g44) + d4422 * Math.cos(x2li - g44)
                            + d5421 * Math.cos(xomi + x2li - g54) + d5433 * Math.cos(-xomi + x2li - g54));
                    xnddt = xnddt * xldot;
                }
                if (Math.abs(t - atime) >= stepp) {
                    xli = xli + xldot * delt + xndt * step2;
                    xni = xni + xndt * delt + xnddt * step2;
                    atime = atime + delt;
                } else {
                    ft = t - atime;
                    again = false;
                }
            }
            nm = xni + xndt * ft + xnddt * ft * ft * 0.5;
            double xl = xli + xldot * ft + xndt * ft * ft * 0.5;
            if (irez != 1) mm = xl - 2.0 * nodem + 2.0 * theta;
            else mm = xl - nodem - argpm + theta;
            dndt = nm - no;
            nm = no + dndt;
        }
        return new double[]{em, argpm, inclm, mm, nodem, nm};
    }
}
