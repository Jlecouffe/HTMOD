/*
 * HTMOD Windows : ecran de demarrage, portage Java2D de BootScreenView (Radtel Tools) :
 * globe filaire en rotation, satellite en orbite, ondes, sol en perspective qui defile, spectre
 * d'horizon et S-metre de chargement. Tout est dessine, aucune ressource externe.
 * Un clic le ferme immediatement.
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.Timer;

final class BootSplash extends JComponent {
    private final long t0 = System.nanoTime();
    private final String version;
    private final String[] modules;
    private final long durationMs;
    private float progress;
    private String moduleLabel = "";

    private BootSplash(String version, String[] modules, long durationMs) {
        this.version = version;
        this.modules = modules;
        this.durationMs = durationMs;
    }

    /** Affiche l'ecran puis appelle done (sur le fil Swing). */
    static void show(String version, String[] modules, long durationMs, Runnable done) {
        JWindow win = new JWindow();
        BootSplash v = new BootSplash(version, modules, durationMs);
        win.setContentPane(v);
        win.setSize(390, 700);
        win.setLocationRelativeTo(null);
        win.setBackground(Shack.BG);
        final boolean[] finished = {false};
        Runnable finish = () -> {
            if (finished[0]) return;
            finished[0] = true;
            win.dispose();
            done.run();
        };
        Timer anim = new Timer(16, null);
        anim.addActionListener(e -> {
            long el = (System.nanoTime() - v.t0) / 1_000_000;
            v.progress = Math.min(1f, el / (float) durationMs);
            int i = Math.min(modules.length - 1, (int) (v.progress * modules.length));
            v.moduleLabel = modules[i];
            v.repaint();
            if (el > durationMs + 250) { anim.stop(); finish.run(); }
        });
        v.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { anim.stop(); finish.run(); }
        });
        win.setVisible(true);
        anim.start();
    }

    private float t() { return (System.nanoTime() - t0) / 1e9f; }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D c = Shack.smooth(g0);
        float t = t();
        float w = getWidth(), h = getHeight();
        c.setColor(Shack.BG);
        c.fillRect(0, 0, (int) w, (int) h);
        float s = Math.min(w / 390f, h / 844f) * 1.0f;
        float cx = w / 2, horizon = h * 0.555f;

        drawFloor(c, w, h, horizon, t);
        drawHorizon(c, w, horizon, s, t);
        float gy = h * 0.25f;
        drawPulses(c, cx, gy, s, t);
        drawGlobe(c, cx, gy, 100 * s, t);
        drawOrbit(c, cx, gy, 150 * s, t);
        drawBrand(c, cx, h * 0.6f, s);
        drawMeter(c, w, h * 0.785f, s, t);

        c.setFont(Shack.mono(11 * s * 1.1f));
        c.setColor(Shack.TEXT2);
        center(c, "Décodeurs portés de Radtel Tools / HTMOD Android", cx, h - 33 * s);
        center(c, "Protocole et SBC d'après HTCommander", cx, h - 18 * s);
        c.dispose();
    }

    private static void center(Graphics2D c, String s, float cx, float y) {
        FontMetrics fm = c.getFontMetrics();
        c.drawString(s, cx - fm.stringWidth(s) / 2f, y);
    }

    private static Color a(Color col, float alpha) {
        return Shack.alpha(col, Math.max(0, Math.min(255, Math.round(alpha))));
    }

    private void drawFloor(Graphics2D c, float w, float h, float horizon, float t) {
        float depth = h - horizon;
        c.setStroke(new BasicStroke(1f));
        float scroll = (t * 0.6f) % 1f;
        // lignes horizontales : espacement en 1/z, defilement vers l'ecran
        for (int i = 0; i < 14; i++) {
            float z = 1f + (i + 1 - scroll) * 0.9f;
            float y = horizon + depth * (1.1f / z);
            if (y > h || y < horizon) continue;
            float k = Math.max(0, Math.min(1, (y - horizon) / depth));
            c.setColor(a(Shack.AMBER, k * k * 140));
            c.draw(new Line2D.Float(0, y, w, y));
        }
        // lignes fuyantes vers le point de fuite
        c.setPaint(new GradientPaint(0, horizon, a(Shack.AMBER, 0), 0, h, a(Shack.AMBER, 70)));
        for (int i = -12; i <= 12; i++) {
            float xb = w / 2 + i * w * 0.16f;
            c.draw(new Line2D.Float(w / 2 + i * w * 0.012f, horizon, xb, h));
        }
    }

    private void drawHorizon(Graphics2D c, float w, float horizon, float s, float t) {
        float sh = 52 * s;
        float base = horizon - sh * 0.12f;
        float[][] peaks = {{.22f, .75f, 1.7f}, {.49f, .35f, 2.3f}, {.78f, .5f, 3.1f}};
        Path2D line = new Path2D.Float(), area = new Path2D.Float();
        float step = 4f;
        for (float x = 0; x <= w + step; x += step) {
            float u = x / w;
            float y = base - sh * 0.04f * Math.abs((float) Math.sin(x * 0.11f + t * 9f));
            for (float[] p : peaks) {
                float amp = p[1] * sh * (0.65f + 0.35f * (float) Math.sin(t * p[2] + p[0] * 10));
                y -= amp * (float) Math.exp(-Math.pow((u - p[0]) / 0.025f, 2));
            }
            if (x == 0) { line.moveTo(x, y); area.moveTo(x, horizon); area.lineTo(x, y); }
            else { line.lineTo(x, y); area.lineTo(x, y); }
        }
        area.lineTo(w + step, horizon);
        area.closePath();
        c.setColor(a(Shack.CYAN, 36));
        c.fill(area);
        c.setColor(Shack.CYAN);
        c.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        c.draw(line);
        c.setColor(Shack.EDGE);
        c.setStroke(new BasicStroke(1f));
        c.draw(new Line2D.Float(0, horizon, w, horizon));
    }

    private void drawPulses(Graphics2D c, float cx, float cy, float s, float t) {
        c.setStroke(new BasicStroke(1f));
        for (float ph : new float[]{0f, 0.5f}) {
            float p = ((t / 3.2f) + ph) % 1f;
            c.setColor(a(Shack.CYAN, 0.55f * (1 - p) * 255));
            float r = 150 * s * (0.55f + 1.2f * p);
            c.draw(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
        }
    }

    private void drawGlobe(Graphics2D c, float cx, float cy, float r, float t) {
        double spin = Math.toRadians(t * 22.5f), tiltX = Math.toRadians(-16), tiltZ = Math.toRadians(14);
        float f = 4.5f * r;
        float[][] pts = new float[49][3];
        // meridiens puis paralleles ; face cachee plus discrete (profondeur)
        for (int m = 0; m < 6 + 5; m++) {
            boolean merid = m < 6;
            double lon = merid ? m * Math.PI / 6 : 0;
            double la = merid ? 0 : Math.toRadians(new float[]{0f, 30f, -30f, 58f, -58f}[m - 6]);
            boolean main = merid ? m % 3 == 0 : m == 6;
            int alphaFront = merid ? (main ? 255 : 200) : (main ? 255 : 170);
            for (int i = 0; i <= 48; i++) {
                double th = i / 48.0 * 2 * Math.PI;
                double x0, y0, z0;
                if (merid) { x0 = r * Math.sin(th) * Math.cos(lon); y0 = -r * Math.cos(th); z0 = r * Math.sin(th) * Math.sin(lon); }
                else { x0 = r * Math.cos(la) * Math.cos(th); y0 = -r * Math.sin(la); z0 = r * Math.cos(la) * Math.sin(th); }
                double x1 = x0 * Math.cos(spin) + z0 * Math.sin(spin), z1 = -x0 * Math.sin(spin) + z0 * Math.cos(spin);
                double y2 = y0 * Math.cos(tiltX) - z1 * Math.sin(tiltX), z2 = y0 * Math.sin(tiltX) + z1 * Math.cos(tiltX);
                double x3 = x1 * Math.cos(tiltZ) - y2 * Math.sin(tiltZ), y3 = x1 * Math.sin(tiltZ) + y2 * Math.cos(tiltZ);
                float k = (float) (f / (f + z2));
                pts[i][0] = (float) x3 * k; pts[i][1] = (float) y3 * k; pts[i][2] = (float) z2;
            }
            c.setStroke(new BasicStroke(main ? 1.5f : 1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 48; i++) {
                boolean back = (pts[i][2] + pts[i + 1][2]) / 2 > 0;
                c.setColor(a(Shack.AMBER, back ? alphaFront / 3f : alphaFront));
                c.draw(new Line2D.Float(cx + pts[i][0], cy + pts[i][1], cx + pts[i + 1][0], cy + pts[i + 1][1]));
            }
        }
        c.setPaint(new RadialGradientPaint(cx, cy, r * 1.3f, new float[]{0f, 1f},
                new Color[]{a(Shack.AMBER, 40), a(Shack.AMBER, 0)}));
        c.fill(new Ellipse2D.Float(cx - r * 1.3f, cy - r * 1.3f, r * 2.6f, r * 2.6f));
    }

    private void drawOrbit(Graphics2D c, float cx, float cy, float r, float t) {
        double inc = Math.toRadians(74), ang = Math.toRadians(t * 51.4f);
        float ry = (float) (r * Math.cos(inc));
        Graphics2D g = (Graphics2D) c.create();
        g.rotate(Math.toRadians(-10), cx, cy);
        g.setColor(a(Shack.CYAN, 200));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 5f}, 0f));
        g.draw(new Ellipse2D.Float(cx - r, cy - ry, 2 * r, 2 * ry));
        float sx = cx + r * (float) Math.sin(ang), sy = cy - ry * (float) Math.cos(ang);
        boolean front = Math.cos(ang) < 0;
        float rad = front ? 6f : 4.5f;
        for (int i = 5; i >= 1; i--) { // halo (ombre lumineuse)
            g.setColor(a(Shack.CYAN, (front ? 26 : 14)));
            g.fill(new Ellipse2D.Float(sx - rad - i * 2.4f, sy - rad - i * 2.4f, 2 * (rad + i * 2.4f), 2 * (rad + i * 2.4f)));
        }
        g.setColor(a(Shack.CYAN, front ? 255 : 150));
        g.fill(new Ellipse2D.Float(sx - rad, sy - rad, 2 * rad, 2 * rad));
        g.dispose();
    }

    private void drawBrand(Graphics2D c, float cx, float y, float s) {
        c.setFont(Shack.tracked(Shack.display(46 * s), 0.16f));
        c.setColor(Shack.TEXT);
        center(c, "HTMOD", cx, y);
        c.setFont(Shack.tracked(Shack.display(17 * s), 0.62f));
        c.setColor(Shack.AMBER);
        center(c, "WINDOWS", cx, y + 28 * s);
        c.setFont(Shack.tracked(Shack.mono(11 * s), 0.12f));
        c.setColor(Shack.MUTED);
        center(c, "SARSAT · RADIOSONDES · APRS · v" + version, cx, y + 54 * s);
    }

    private void drawMeter(Graphics2D c, float w, float y, float s, float t) {
        float left = 32 * s, right = w - 32 * s;
        int n = 13;
        float gap = 4 * s, segW = (right - left - gap * (n - 1)) / n, segH = 14 * s;
        int lit = Math.max(0, Math.min(n, (int) (progress * n)));
        c.setFont(Shack.mono(10 * s * 1.1f));
        String[] labels = {"S1", "3", "5", "7", "9", "+20", "+40"};
        int[] idx = {0, 2, 4, 6, 8, 10, 12};
        for (int k = 0; k < labels.length; k++) {
            c.setColor(idx[k] >= 10 ? Shack.RED : Shack.MUTED);
            center(c, labels[k], left + idx[k] * (segW + gap) + segW / 2, y - 8 * s);
        }
        for (int i = 0; i < n; i++) {
            float x = left + i * (segW + gap);
            RoundRectangle2D rect = new RoundRectangle2D.Float(x, y, segW, segH, 4, 4);
            if (i < lit) {
                Color col = i >= 11 ? Shack.RED : i >= 9 ? Shack.CYAN : Shack.AMBER;
                for (int k = 3; k >= 1; k--) {
                    c.setColor(a(col, 30));
                    c.fill(new RoundRectangle2D.Float(x - k * 1.5f, y - k * 1.5f, segW + k * 3, segH + k * 3, 6, 6));
                }
                c.setColor(col);
                c.fill(rect);
            } else {
                c.setColor(Shack.KEY_TOP);
                c.fill(rect);
                c.setColor(Shack.EDGE);
                c.setStroke(new BasicStroke(1f));
                c.draw(rect);
            }
        }
        float ty = y + segH + 22 * s;
        c.setFont(Shack.mono(12 * s * 1.05f));
        c.setColor(Shack.TEXT2);
        String label = "▸ " + moduleLabel;
        c.drawString(label, left, ty);
        if (((int) (t * 2)) % 2 == 0) {
            c.setColor(Shack.AMBER);
            c.drawString("_", left + c.getFontMetrics().stringWidth(label), ty);
        }
        c.setColor(Shack.CYAN);
        String pct = Math.round(progress * 100) + " %";
        c.drawString(pct, right - c.getFontMetrics().stringWidth(pct), ty);
    }
}
