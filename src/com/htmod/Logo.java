/*
 * HTMOD Windows : logo « HT » (monogramme ambre et ondes cyan sur fond nuit), dessine en vectoriel
 * sur une grille de 64 avec une variante par taille : complet (40 px et plus), une seule onde (24-39 px),
 * lettres seules et plus epaisses (20 px et moins, barre des taches en petites icones).
 * main() ecrit le fichier .ico multi-tailles de HTMod.exe et de l'installateur (appele par build.ps1).
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

final class Logo {
    private Logo() {}

    static final int[] ICO_SIZES = {16, 20, 24, 32, 40, 48, 64, 96, 128, 256};

    /** Dessine le logo dans le carre (x, y, size). */
    static void paint(Graphics2D g0, double x, double y, double size) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.translate(x, y);
        g.scale(size / 64.0, size / 64.0);
        boolean tiny = size <= 20, mid = size < 40;
        g.setColor(Shack.BG);
        RoundRectangle2D tile = new RoundRectangle2D.Double(1, 1, 62, 62, 28, 28);
        g.fill(tile);
        if (!tiny) {
            g.setColor(Shack.EDGE);
            g.setStroke(new BasicStroke(2f));
            g.draw(tile);
        }
        Path2D ht = new Path2D.Double();
        if (tiny) {
            ht.moveTo(12, 16); ht.lineTo(12, 48);
            ht.moveTo(12, 32); ht.lineTo(26, 32);
            ht.moveTo(26, 16); ht.lineTo(26, 48);
            ht.moveTo(34, 16); ht.lineTo(52, 16);
            ht.moveTo(43, 16); ht.lineTo(43, 48);
        } else {
            ht.moveTo(10, 18); ht.lineTo(10, 46);
            ht.moveTo(10, 32); ht.lineTo(24, 32);
            ht.moveTo(24, 18); ht.lineTo(24, 46);
            ht.moveTo(30, 18); ht.lineTo(44, 18);
            ht.moveTo(37, 18); ht.lineTo(37, 46);
        }
        g.setColor(Shack.AMBER);
        g.setStroke(new BasicStroke(tiny ? 8f : size < 28 ? 6f : mid ? 5.5f : 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(ht);
        if (!tiny) {
            g.setColor(Shack.CYAN);
            if (mid) {
                g.setStroke(new BasicStroke(size < 28 ? 5f : 4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(wave(45, 10, 53.13));
            } else {
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(wave(45.13, 8, 61.04));
                g.draw(wave(45, 15, 53.13));
            }
        }
        g.dispose();
    }

    /** Onde : arc de cercle de centre (cx, 32), ouvert vers la droite, de -a a +a degres. */
    private static Arc2D wave(double cx, double r, double a) {
        return new Arc2D.Double(cx - r, 32 - r, 2 * r, 2 * r, -a, 2 * a, Arc2D.OPEN);
    }

    static BufferedImage image(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        paint(g, 0, 0, size);
        g.dispose();
        return img;
    }

    /** Images de la fenetre (Windows choisit la taille selon l'echelle d'affichage). */
    static List<java.awt.Image> windowImages() {
        List<java.awt.Image> l = new ArrayList<>();
        for (int s : new int[]{16, 20, 24, 32, 40, 48, 64, 128, 256}) l.add(image(s));
        return l;
    }

    /** Fichier .ico : une image PNG 32 bits par taille (format accepte par Windows Vista et suivants). */
    static void writeIco(File out) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int s : ICO_SIZES) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            ImageIO.write(image(s), "png", b);
            pngs.add(b.toByteArray());
        }
        try (OutputStream o = Files.newOutputStream(out.toPath())) {
            le16(o, 0);
            le16(o, 1); // icone
            le16(o, ICO_SIZES.length);
            int offset = 6 + 16 * ICO_SIZES.length;
            for (int i = 0; i < ICO_SIZES.length; i++) {
                int s = ICO_SIZES[i];
                o.write(s >= 256 ? 0 : s);
                o.write(s >= 256 ? 0 : s);
                o.write(0); // palette
                o.write(0);
                le16(o, 1);  // plans
                le16(o, 32); // bits par pixel
                le32(o, pngs.get(i).length);
                le32(o, offset);
                offset += pngs.get(i).length;
            }
            for (byte[] p : pngs) o.write(p);
        }
    }

    private static void le16(OutputStream o, int v) throws IOException { o.write(v); o.write(v >> 8); }

    private static void le32(OutputStream o, int v) throws IOException { le16(o, v); le16(o, v >> 16); }

    /** build.ps1 : java -cp out\classes com.htmod.Logo <fichier.ico> [<apercu.png>] */
    public static void main(String[] a) throws IOException {
        System.setProperty("java.awt.headless", "true");
        writeIco(new File(a[0]));
        if (a.length > 1) {
            int w = 0;
            for (int s : ICO_SIZES) w += s + 12;
            BufferedImage sheet = new BufferedImage(w + 12, 256 + 24, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(new Color(0x202020));
            g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            int x = 12;
            for (int s : ICO_SIZES) { g.drawImage(image(s), x, 12, null); x += s + 12; }
            g.dispose();
            ImageIO.write(sheet, "png", new File(a[1]));
        }
    }
}
