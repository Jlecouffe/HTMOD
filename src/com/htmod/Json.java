/*
 * HTMOD Windows : ecriture JSON minimale pour l'interface web (Map, List, tableaux, texte, nombres).
 */
package com.htmod;

import java.awt.Color;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class Json {
    private Json() {}

    /** Petite fabrique d'objet ordonne : Json.obj("a", 1, "b", "x"). */
    static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String css(Color c) { return String.format("#%06X", c.getRGB() & 0xFFFFFF); }

    static String of(Object o) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String || o instanceof Character) { str(sb, o.toString()); return; }
        if (o instanceof Boolean) { sb.append(o); return; }
        if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(String.format(Locale.ROOT, "%.6g", d));
            return;
        }
        if (o instanceof Number) { sb.append(o); return; }
        if (o instanceof Color) { str(sb, css((Color) o)); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Collection<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
            return;
        }
        if (o instanceof int[]) {
            int[] a = (int[]) o;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
            return;
        }
        if (o instanceof long[]) {
            long[] a = (long[]) o;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
            return;
        }
        if (o instanceof boolean[]) {
            boolean[] a = (boolean[]) o;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i] ? 1 : 0); }
            sb.append(']');
            return;
        }
        if (o instanceof float[]) {
            float[] a = (float[]) o;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(String.format(Locale.ROOT, "%.3f", a[i])); }
            sb.append(']');
            return;
        }
        if (o instanceof double[]) {
            double[] a = (double[]) o;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); write(sb, a[i]); }
            sb.append(']');
            return;
        }
        if (o instanceof Object[]) {
            Object[] a = (Object[]) o;
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); write(sb, a[i]); }
            sb.append(']');
            return;
        }
        str(sb, o.toString());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
