/*
 * HTMOD Windows : drapeaux des langues, dessines (Windows n'affiche pas les emoji de drapeaux).
 * Memes dessins que FLAGS dans res/com/htmod/web/views.js (repere 60 x 40).
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import javax.swing.Icon;

final class Flags {
    private Flags() {}

    static void paint(Graphics2D g0, String lang, int x, int y, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.translate(x, y);
        g.clip(new RoundRectangle2D.Float(0, 0, w, h, 4, 4));
        g.scale(w / 60.0, h / 40.0);
        switch (lang) {
            case "en":
                g.setColor(new Color(0x012169));
                g.fillRect(0, 0, 60, 40);
                cross(g, Color.WHITE, 8f, true);
                cross(g, new Color(0xC8102E), 3f, true);
                cross(g, Color.WHITE, 13f, false);
                cross(g, new Color(0xC8102E), 8f, false);
                break;
            case "it":
                bands(g, new Color(0x009246), Color.WHITE, new Color(0xCE2B37));
                break;
            default:
                bands(g, new Color(0x002395), Color.WHITE, new Color(0xED2939));
        }
        g.dispose();
    }

    private static void bands(Graphics2D g, Color a, Color b, Color c) {
        g.setColor(a);
        g.fillRect(0, 0, 20, 40);
        g.setColor(b);
        g.fillRect(20, 0, 20, 40);
        g.setColor(c);
        g.fillRect(40, 0, 20, 40);
    }

    private static void cross(Graphics2D g, Color c, float width, boolean diagonal) {
        g.setColor(c);
        g.setStroke(new BasicStroke(width));
        if (diagonal) {
            g.draw(new Line2D.Float(0, 0, 60, 40));
            g.draw(new Line2D.Float(60, 0, 0, 40));
        } else {
            g.draw(new Line2D.Float(30, 0, 30, 40));
            g.draw(new Line2D.Float(0, 20, 60, 20));
        }
    }

    /** Pour Shack.Key : drapeau 22 x 15 centre sur la case d'icone de la touche. */
    static Shack.Icon keyIcon(String lang) {
        return (g, x, y, s, c) -> paint(g, lang, x + (s - 22) / 2, y + (s - 15) / 2, 22, 15);
    }

    /** Pour les lignes de menu. */
    static Icon menuIcon(String lang) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g, int x, int y) { paint((Graphics2D) g, lang, x, y, 24, 16); }
            @Override public int getIconWidth() { return 24; }
            @Override public int getIconHeight() { return 16; }
        };
    }
}
