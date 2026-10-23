/*
 * HTMOD Windows : decodage du champ d'information APRS (spec APRS 1.0.1 + 1.2) :
 * positions non compressees, compressees et Mic-E, objets, items, messages, statut, NMEA brut,
 * trafic tiers (}). Pas de meteo detaillee : le commentaire est conserve tel quel.
 */
package com.htmod;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Aprs {
    static final class Report {
        String source;          // indicatif emetteur (ou origine du trafic tiers)
        String name;            // nom affiche : objet/item, sinon l'indicatif
        String type = "?";
        boolean hasPos;
        double lat, lon;
        char symTable = '/', symCode = ' ';
        int course = -1;        // degres
        double speedKmh = -1;
        double altM = Double.NaN;
        String comment = "";
        String addressee, message; // messages
        boolean killed;         // objet / item supprime
        String tnc2;

        String summary() {
            StringBuilder sb = new StringBuilder();
            if (message != null) sb.append("→ ").append(addressee).append(" : ").append(message);
            if (hasPos) {
                if (speedKmh >= 0) sb.append(sb.length() > 0 ? "  " : "").append(Math.round(speedKmh)).append(" km/h");
                if (course >= 0) sb.append(" cap ").append(course).append("°");
                if (!Double.isNaN(altM)) sb.append(sb.length() > 0 ? "  " : "").append("alt ").append(Math.round(altM)).append(" m");
            }
            if (!comment.isEmpty()) sb.append(sb.length() > 0 ? "  " : "").append(comment);
            return sb.toString().trim();
        }

        String symbolName() { return Aprs.symbolName(symTable, symCode); }
    }

    private Aprs() {}

    static Report parse(Ax25 p) {
        Report r = parse(p.src, p.dst, p.info, 0);
        r.tnc2 = p.tnc2();
        return r;
    }

    static Report parse(String src, String dst, String info, int depth) {
        Report r = new Report();
        r.source = src;
        r.name = src;
        if (info == null || info.isEmpty()) { r.type = "Vide"; return r; }
        try {
            char t = info.charAt(0);
            switch (t) {
                case '!': case '=':
                    r.type = "Position";
                    position(r, info, 1);
                    break;
                case '/': case '@':
                    r.type = "Position";
                    position(r, info, 8);
                    break;
                case ';':
                    r.type = "Objet";
                    if (info.length() >= 18) {
                        r.name = info.substring(1, 10).trim();
                        r.killed = info.charAt(10) == '_';
                        position(r, info, 18);
                    }
                    break;
                case ')': {
                    r.type = "Item";
                    int end = -1;
                    for (int i = 4; i < Math.min(info.length(), 11); i++) {
                        char c = info.charAt(i);
                        if (c == '!' || c == '_') { end = i; break; }
                    }
                    if (end > 0) {
                        r.name = info.substring(1, end).trim();
                        r.killed = info.charAt(end) == '_';
                        position(r, info, end + 1);
                    }
                    break;
                }
                case '`': case '\'': case 0x1c: case 0x1d:
                    r.type = "Mic-E";
                    micE(r, dst, info);
                    break;
                case ':':
                    r.type = "Message";
                    if (info.length() >= 11 && info.charAt(10) == ':') {
                        r.addressee = info.substring(1, 10).trim();
                        r.message = info.substring(11);
                        if (r.message.matches("(ack|rej)[A-Za-z0-9]{1,5}")) r.type = "Accusé";
                        else if (r.addressee.startsWith("BLN")) r.type = "Bulletin";
                    }
                    break;
                case '>':
                    r.type = "Statut";
                    r.comment = info.substring(1).trim();
                    break;
                case '$':
                    r.type = "NMEA";
                    nmea(r, info);
                    break;
                case '}': {
                    // trafic tiers : "SRC>DST,PATH:info"
                    String inner = info.substring(1);
                    int gt = inner.indexOf('>'), colon = inner.indexOf(':');
                    if (depth < 3 && gt > 0 && colon > gt) {
                        String isrc = inner.substring(0, gt);
                        String head = inner.substring(gt + 1, colon);
                        String idst = head.split(",")[0];
                        Report in = parse(isrc, idst, inner.substring(colon + 1), depth + 1);
                        in.comment = (in.comment + " (via " + src + ")").trim();
                        return in;
                    }
                    r.type = "Tiers";
                    break;
                }
                case 'T':
                    r.type = "Télémétrie";
                    r.comment = info;
                    break;
                case '_':
                    r.type = "Météo";
                    r.comment = info.substring(1);
                    break;
                default:
                    // Certaines stations mettent la position plus loin (ex. "!" apres un texte)
                    int bang = info.indexOf('!');
                    if (bang > 0 && bang < 40 && info.length() - bang > 19) {
                        r.type = "Position";
                        position(r, info, bang + 1);
                    } else {
                        r.type = "Autre";
                        r.comment = info;
                    }
            }
        } catch (RuntimeException e) {
            r.comment = info; // paquet tronque ou non conforme
        }
        if (r.symTable == '/' && r.symCode == '_' && "Position".equals(r.type)) r.type = "Météo";
        if (r.hasPos && (Math.abs(r.lat) > 90 || Math.abs(r.lon) > 180 || (r.lat == 0 && r.lon == 0))) r.hasPos = false;
        return r;
    }

    // ------------------------------------------------------------------ Positions

    private static void position(Report r, String s, int i) {
        if (i >= s.length()) return;
        char c = s.charAt(i);
        // non compressee : commence par les degres de latitude (chiffres) ; compressee : par la table de symboles
        if ((c == '/' || c == '\\' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'j')) && i + 13 <= s.length()) {
            compressed(r, s, i);
            return;
        }
        uncompressed(r, s, i);
    }

    private static double ddmm(String v, int degDigits) {
        v = v.replace(' ', '0'); // ambiguite de position
        double deg = Integer.parseInt(v.substring(0, degDigits));
        double min = Double.parseDouble(v.substring(degDigits));
        return deg + min / 60.0;
    }

    private static final Pattern CSE_SPD = Pattern.compile("^(\\d{3})/(\\d{3})");
    private static final Pattern ALT = Pattern.compile("/A=(-?\\d{5,6})");

    private static void uncompressed(Report r, String s, int i) {
        if (i + 19 > s.length()) return;
        String lat = s.substring(i, i + 7);
        char ns = s.charAt(i + 7);
        r.symTable = s.charAt(i + 8);
        String lon = s.substring(i + 9, i + 17);
        char ew = s.charAt(i + 17);
        r.symCode = s.charAt(i + 18);
        if (lat.charAt(4) != '.' || lon.charAt(5) != '.') return;
        r.lat = ddmm(lat, 2) * (ns == 'S' || ns == 's' ? -1 : 1);
        r.lon = ddmm(lon, 3) * (ew == 'W' || ew == 'w' ? -1 : 1);
        r.hasPos = (ns == 'N' || ns == 'S' || ns == 'n' || ns == 's') && (ew == 'E' || ew == 'W' || ew == 'e' || ew == 'w');
        String rest = s.substring(i + 19);
        Matcher m = CSE_SPD.matcher(rest);
        if (m.find() && r.symCode != '_') {
            int cse = Integer.parseInt(m.group(1));
            int spd = Integer.parseInt(m.group(2));
            if (cse > 0 && cse <= 360) r.course = cse % 360;
            if (cse > 0 || spd > 0) r.speedKmh = spd * 1.852;
            rest = rest.substring(7);
        }
        comment(r, rest);
    }

    private static int b91(String s, int from, int n) {
        int v = 0;
        for (int k = 0; k < n; k++) v = v * 91 + (s.charAt(from + k) - 33);
        return v;
    }

    private static void compressed(Report r, String s, int i) {
        r.symTable = s.charAt(i);
        r.lat = 90.0 - b91(s, i + 1, 4) / 380926.0;
        r.lon = -180.0 + b91(s, i + 5, 4) / 190463.0;
        r.symCode = s.charAt(i + 9);
        r.hasPos = true;
        char c = s.charAt(i + 10), sp = s.charAt(i + 11), t = s.charAt(i + 12);
        if (c != ' ') {
            int ct = t - 33;
            if ((ct & 0x18) == 0x10) {
                r.altM = Math.pow(1.002, (c - 33) * 91 + (sp - 33)) * 0.3048;
            } else if (c >= '!' && c <= 'z') {
                r.course = (c - 33) * 4;
                if (r.course == 0) r.course = -1;
                r.speedKmh = (Math.pow(1.08, sp - 33) - 1) * 1.852;
            }
        }
        comment(r, s.substring(i + 13));
    }

    private static void comment(Report r, String rest) {
        Matcher m = ALT.matcher(rest);
        if (m.find()) {
            r.altM = Integer.parseInt(m.group(1)) * 0.3048;
            rest = rest.substring(0, m.start()) + rest.substring(m.end());
        }
        r.comment = clean(rest);
    }

    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) if (ch >= 0x20 && ch != 0x7F) sb.append(ch);
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ Mic-E

    private static int micDigit(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'A' && c <= 'J') return c - 'A';
        if (c >= 'P' && c <= 'Y') return c - 'P';
        return 0; // K, L, Z : ambiguite
    }

    private static void micE(Report r, String dst, String s) {
        String d = dst.contains("-") ? dst.substring(0, dst.indexOf('-')) : dst;
        if (d.length() < 6 || s.length() < 9) return;
        int latDeg = micDigit(d.charAt(0)) * 10 + micDigit(d.charAt(1));
        int latMin = micDigit(d.charAt(2)) * 10 + micDigit(d.charAt(3));
        int latHun = micDigit(d.charAt(4)) * 10 + micDigit(d.charAt(5));
        boolean north = d.charAt(3) >= 'P' && d.charAt(3) <= 'Z';
        boolean lonOff = d.charAt(4) >= 'P' && d.charAt(4) <= 'Z';
        boolean west = d.charAt(5) >= 'P' && d.charAt(5) <= 'Z';
        double lat = latDeg + (latMin + latHun / 100.0) / 60.0;

        int lonDeg = s.charAt(1) - 28;
        if (lonOff) lonDeg += 100;
        if (lonDeg >= 180 && lonDeg <= 189) lonDeg -= 80;
        else if (lonDeg >= 190 && lonDeg <= 199) lonDeg -= 190;
        int lonMin = s.charAt(2) - 28;
        if (lonMin >= 60) lonMin -= 60;
        int lonHun = s.charAt(3) - 28;
        double lon = lonDeg + (lonMin + lonHun / 100.0) / 60.0;

        r.lat = north ? lat : -lat;
        r.lon = west ? -lon : lon;
        r.hasPos = true;

        int sp = s.charAt(4) - 28, dc = s.charAt(5) - 28, se = s.charAt(6) - 28;
        int speed = sp * 10 + dc / 10;
        int course = (dc % 10) * 100 + se;
        if (speed >= 800) speed -= 800;
        if (course >= 400) course -= 400;
        r.speedKmh = speed * 1.852;
        r.course = course > 0 && course <= 360 ? course % 360 : -1;
        r.symCode = s.charAt(7);
        r.symTable = s.charAt(8);

        String rest = s.length() > 9 ? s.substring(9) : "";
        // altitude : 3 caracteres base 91 suivis de '}' (metres + 10000)
        int brace = rest.indexOf('}');
        if (brace >= 3) {
            r.altM = b91(rest, brace - 3, 3) - 10000;
            rest = rest.substring(0, brace - 3) + rest.substring(brace + 1);
        }
        // indicateur de type de poste en tete de commentaire (">", "]", "`"...) et suffixe ("=", "^"...)
        if (!rest.isEmpty() && (rest.charAt(0) == '>' || rest.charAt(0) == ']' || rest.charAt(0) == '`' || rest.charAt(0) == '\''))
            rest = rest.substring(1);
        comment(r, rest);
    }

    // ------------------------------------------------------------------ NMEA

    private static void nmea(Report r, String s) {
        String[] f = s.split(",");
        try {
            if (f[0].endsWith("RMC") && f.length > 8 && "A".equals(f[2])) {
                r.lat = ddmm(f[3], 2) * ("S".equals(f[4]) ? -1 : 1);
                r.lon = ddmm(f[5], 3) * ("W".equals(f[6]) ? -1 : 1);
                r.hasPos = true;
                if (!f[7].isEmpty()) r.speedKmh = Double.parseDouble(f[7]) * 1.852;
                if (!f[8].isEmpty()) r.course = (int) Math.round(Double.parseDouble(f[8]));
            } else if (f[0].endsWith("GGA") && f.length > 9 && !f[6].equals("0")) {
                r.lat = ddmm(f[2], 2) * ("S".equals(f[3]) ? -1 : 1);
                r.lon = ddmm(f[4], 3) * ("W".equals(f[5]) ? -1 : 1);
                r.hasPos = true;
                if (!f[9].isEmpty()) r.altM = Double.parseDouble(f[9]);
            }
        } catch (RuntimeException ignore) {
        }
        r.symCode = '>';
    }

    // ------------------------------------------------------------------ Symboles

    static String symbolName(char table, char code) {
        boolean alt = table != '/';
        switch (code) {
            case '>': return "Voiture";
            case '-': return "Maison";
            case '#': return "Relais numérique";
            case '&': return "Passerelle";
            case '_': return "Météo";
            case 'k': return alt ? "Véhicule" : "Camion";
            case 'u': return "Poids lourd";
            case 'v': return "Fourgon";
            case 'j': return "Jeep";
            case '[': return "Piéton";
            case 'b': return "Vélo";
            case '<': return "Moto";
            case 'O': return alt ? "Fusée" : "Ballon";
            case '\'': return "Avion";
            case '^': return alt ? "Aéronef" : "Gros avion";
            case 'X': return "Hélicoptère";
            case 's': return "Bateau";
            case 'Y': return "Voilier";
            case 'r': return "Antenne";
            case 'a': return "Ambulance";
            case 'f': return "Pompiers";
            case 'P': return "Police";
            case 'y': return "Maison (yagi)";
            case 'l': return "Ordinateur";
            case 'n': return "Nœud";
            case '`': return "Antenne parabolique";
            case 'R': return "Camping-car";
            case 'U': return "Bus";
            case 'S': return alt ? "Satellite" : "Navette";
            case 'E': return "Événement";
            default: return "Symbole " + table + code;
        }
    }

    static String fmtPos(double lat, double lon) {
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lon);
    }
}
