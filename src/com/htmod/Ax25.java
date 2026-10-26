/* HTMOD Windows : trames AX.25 (adresses, controle, PID, champ d'information) et FCS. */
package com.htmod;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Ax25 {
    String src, dst;
    final List<String> path = new ArrayList<>(); // "WIDE1-1*" si deja repete
    int control, pid;
    String info = "";
    byte[] raw;

    boolean isUi() { return (control & 0xEF) == 0x03; }

    /** Format TNC2 : SRC>DST,PATH:info */
    String tnc2() {
        StringBuilder sb = new StringBuilder(src).append('>').append(dst);
        for (String p : path) sb.append(',').append(p);
        return sb.append(':').append(info).toString();
    }

    /** Decode une trame sans FCS ni fanions ; null si mal formee. */
    static Ax25 parse(byte[] d) {
        if (d == null || d.length < 15) return null;
        List<String> addrs = new ArrayList<>();
        int i = 0;
        boolean last = false;
        while (!last) {
            if (i + 7 > d.length || addrs.size() >= 10) return null;
            StringBuilder cs = new StringBuilder();
            for (int k = 0; k < 6; k++) {
                int c = (d[i + k] & 0xFF);
                if ((c & 1) != 0) return null; // bit d'extension au milieu de l'indicatif
                c >>= 1;
                if (c == ' ') continue;
                if (c < 0x30 || c > 0x5A) return null;
                cs.append((char) c);
            }
            if (cs.length() == 0) return null;
            int ssidByte = d[i + 6] & 0xFF;
            int ssid = (ssidByte >> 1) & 0x0F;
            if (ssid != 0) cs.append('-').append(ssid);
            if (addrs.size() >= 2 && (ssidByte & 0x80) != 0) cs.append('*'); // bit H : deja repete
            addrs.add(cs.toString());
            last = (ssidByte & 1) != 0;
            i += 7;
        }
        if (addrs.size() < 2 || i >= d.length) return null;
        Ax25 p = new Ax25();
        p.raw = d;
        p.dst = addrs.get(0);
        p.src = addrs.get(1);
        p.path.addAll(addrs.subList(2, addrs.size()));
        // N'afficher l'asterisque que sur le dernier relais ayant repete (convention TNC2).
        int lastRep = -1;
        for (int k = 0; k < p.path.size(); k++) if (p.path.get(k).endsWith("*")) lastRep = k;
        for (int k = 0; k < p.path.size(); k++) {
            String s = p.path.get(k);
            if (s.endsWith("*") && k != lastRep) p.path.set(k, s.substring(0, s.length() - 1));
        }
        p.control = d[i++] & 0xFF;
        if (p.isUi()) {
            if (i >= d.length) return null;
            p.pid = d[i++] & 0xFF;
        }
        p.info = new String(d, i, d.length - i, StandardCharsets.ISO_8859_1);
        return p;
    }

    /**
     * Trame UI (controle 0x03, PID 0xF0) sans FCS, pour le TNC du poste : destination, source puis
     * relais (« WIDE1-1 »). IllegalArgumentException si un indicatif est invalide.
     */
    static byte[] encodeUi(String src, String dst, List<String> path, String info) {
        List<String> addrs = new ArrayList<>();
        addrs.add(dst);
        addrs.add(src);
        for (String p : path) if (!p.trim().isEmpty()) addrs.add(p.trim());
        if (addrs.size() > 10) throw new IllegalArgumentException("chemin trop long (8 relais au plus)");
        byte[] body = info.getBytes(StandardCharsets.ISO_8859_1);
        byte[] d = new byte[addrs.size() * 7 + 2 + body.length];
        for (int a = 0; a < addrs.size(); a++) {
            String cs = addrs.get(a).toUpperCase(java.util.Locale.ROOT);
            int ssid = 0, dash = cs.indexOf('-');
            if (dash >= 0) {
                try { ssid = Integer.parseInt(cs.substring(dash + 1)); } catch (NumberFormatException e) { ssid = -1; }
                cs = cs.substring(0, dash);
            }
            if (cs.isEmpty() || cs.length() > 6 || !cs.matches("[A-Z0-9]+") || ssid < 0 || ssid > 15)
                throw new IllegalArgumentException("indicatif invalide : " + addrs.get(a));
            for (int k = 0; k < 6; k++) d[a * 7 + k] = (byte) ((k < cs.length() ? cs.charAt(k) : ' ') << 1);
            // bits reserves a 1 ; bit C de la destination a 1 (commande, AX.25 v2) ; bit d'extension sur la derniere
            d[a * 7 + 6] = (byte) ((a == 0 ? 0x80 : 0) | 0x60 | (ssid << 1) | (a == addrs.size() - 1 ? 1 : 0));
        }
        int i = addrs.size() * 7;
        d[i++] = 0x03;
        d[i++] = (byte) 0xF0;
        System.arraycopy(body, 0, d, i, body.length);
        return d;
    }

    /** FCS AX.25 (CRC-16 CCITT reflechi, init 0xFFFF, complemente). */
    static int fcs(byte[] d, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= d[i] & 0xFF;
            for (int b = 0; b < 8; b++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0x8408 : crc >>> 1;
        }
        return ~crc & 0xFFFF;
    }
}
