/*
 * HTMOD Windows : habillage « shack de nuit », repris de Radtel Tools (ShackStyle.kt) :
 * fond graphite, touches en relief (degrade + liseré), fenetres creuses, ambre « cadran » pour
 * l'accent, cyan pour ce qui est signal, titres de section en mono majuscules espacees.
 */
package com.htmod;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

final class Shack {
    static final Color BG = new Color(0x0A0E13);
    static final Color KEY_TOP = new Color(0x1A222D);
    static final Color KEY_BOT = new Color(0x121821);
    static final Color CARD_TOP = new Color(0x18202A);
    static final Color CARD_BOT = new Color(0x10161E);
    static final Color EDGE = new Color(0x263241);
    static final Color WELL = new Color(0x070A0E);
    static final Color TEXT = new Color(0xE8EDF2);
    static final Color TEXT2 = new Color(0xC3CCD6);
    static final Color MUTED = new Color(0x8B97A6);
    static final Color AMBER = new Color(0xF5A524);
    static final Color CYAN = new Color(0x5FC8E8);
    static final Color RED = new Color(0xE7625F);
    static final Color GREEN = new Color(0x6DD69A);

    private Shack() {}

    // ------------------------------------------------------------------ Polices

    private static final Set<String> FAMILIES = new HashSet<>(Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));

    private static String pick(String... names) {
        for (String n : names) if (FAMILIES.contains(n)) return n;
        return names[names.length - 1];
    }

    // equivalents Windows de sans-serif-condensed / monospace / sans-serif (Android)
    private static final String DISPLAY_FAMILY = pick("Bahnschrift SemiBold Condensed", "Bahnschrift Condensed", "Bahnschrift",
            "Segoe UI Semibold", Font.SANS_SERIF);
    private static final String MONO_FAMILY = pick("Cascadia Mono", "Consolas", Font.MONOSPACED);
    private static final String BODY_FAMILY = pick("Segoe UI", Font.SANS_SERIF);

    static Font display(float size) { return new Font(DISPLAY_FAMILY, DISPLAY_FAMILY.startsWith("Bahnschrift S") ? Font.PLAIN : Font.BOLD, 1).deriveFont(size); }
    static Font mono(float size) { return new Font(MONO_FAMILY, Font.PLAIN, 1).deriveFont(size); }
    static Font monoBold(float size) { return new Font(MONO_FAMILY, Font.BOLD, 1).deriveFont(size); }
    static Font body(float size) { return new Font(BODY_FAMILY, Font.PLAIN, 1).deriveFont(size); }
    static Font bold(float size) { return new Font(BODY_FAMILY, Font.BOLD, 1).deriveFont(size); }

    static Font tracked(Font f, float tracking) {
        Map<TextAttribute, Object> a = new HashMap<>();
        a.put(TextAttribute.TRACKING, tracking);
        return f.deriveFont(a);
    }

    static Graphics2D smooth(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    // ------------------------------------------------------------------ Fonds

    /** Fond de touche en relief : degrade haut -> bas + liseré. */
    static void paintKey(Graphics2D g, int x, int y, int w, int h, int r, Color top, Color bot) {
        RoundRectangle2D rr = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, r * 2, r * 2);
        g.setPaint(new GradientPaint(0, y, top, 0, y + h, bot));
        g.fill(rr);
        g.setPaint(EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(rr);
    }

    /** Fenetre creuse (icone, spectre). */
    static void paintWell(Graphics2D g, int x, int y, int w, int h, int r) {
        RoundRectangle2D rr = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, r * 2, r * 2);
        g.setPaint(WELL);
        g.fill(rr);
        g.setPaint(EDGE);
        g.draw(rr);
    }

    /** Ombre portee douce sous une touche. */
    static void paintShadow(Graphics2D g, int x, int y, int w, int h, int r, int depth) {
        for (int i = depth; i > 0; i--) {
            g.setColor(new Color(0, 0, 0, 22));
            g.fillRoundRect(x + 1, y + i + 1, w - 2, h - 2, r * 2, r * 2);
        }
    }

    // ------------------------------------------------------------------ Composants

    /** Panneau « carte » (touche large en relief). */
    static class Card extends JPanel {
        private final int radius;

        Card(int radius) {
            this.radius = radius;
            setOpaque(false);
            setBorder(new EmptyBorder(14, 16, 12, 16));
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            paintShadow(g, 0, 0, getWidth(), getHeight() - 4, radius, 4);
            paintKey(g, 0, 0, getWidth(), getHeight() - 4, radius, CARD_TOP, CARD_BOT);
            g.dispose();
        }

        @Override
        public Insets getInsets() {
            Insets i = super.getInsets();
            return new Insets(i.top, i.left, i.bottom + 4, i.right);
        }
    }

    /** Panneau creux. */
    static class Well extends JPanel {
        private final int radius;

        Well(int radius) {
            this.radius = radius;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            paintWell(g, 0, 0, getWidth(), getHeight(), radius);
            g.dispose();
        }
    }

    /** Dessine une petite icone dans un rectangle. */
    interface Icon { void paint(Graphics2D g, int x, int y, int size, Color c); }

    /** Touche en relief cliquable (enfoncement de 2 px a l'appui), eventuellement « active ». */
    static class Key extends JComponent {
        private String text;
        private Icon icon;
        private Color iconColor = TEXT2;
        private boolean selected, primary, hover, pressed;
        private final List<ActionListener> listeners = new ArrayList<>();
        private int radius = 12;
        private Font font = bold(13f);
        private Color selectedColor = AMBER;

        Key(String text) {
            this.text = text;
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }
                @Override public void mousePressed(MouseEvent e) { if (isEnabled()) { pressed = true; repaint(); } }
                @Override public void mouseReleased(MouseEvent e) {
                    boolean fire = pressed && contains(e.getPoint());
                    pressed = false;
                    repaint();
                    if (fire) fire();
                }
            };
            addMouseListener(m);
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER) fire();
                }
            });
        }

        Key icon(Icon i, Color c) { icon = i; iconColor = c; repaint(); return this; }
        Key primary() { primary = true; return this; }
        Key radius(int r) { radius = r; return this; }
        Key font(Font f) { font = f; return this; }
        Key selectedColor(Color c) { selectedColor = c; return this; }
        void setText(String t) { text = t; revalidate(); repaint(); }
        String getText() { return text; }
        void setSelected(boolean s) { selected = s; repaint(); }
        boolean isSelected() { return selected; }
        void addActionListener(ActionListener l) { listeners.add(l); }

        private void fire() {
            if (!isEnabled()) return;
            ActionEvent e = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, text);
            for (ActionListener l : new ArrayList<>(listeners)) l.actionPerformed(e);
        }

        @Override
        public Dimension getPreferredSize() {
            if (isPreferredSizeSet()) return super.getPreferredSize();
            FontMetrics fm = getFontMetrics(font);
            int w = (text == null || text.isEmpty() ? 0 : fm.stringWidth(text)) + 28 + (icon != null ? 22 : 0);
            return new Dimension(Math.max(w, 40), 40);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int w = getWidth(), h = getHeight() - 3;
            int dy = pressed ? 2 : 0;
            if (!pressed) paintShadow(g, 0, 0, w, h, radius, 3);
            if (primary) {
                g.setColor(isEnabled() ? (hover ? AMBER.brighter() : AMBER) : alpha(AMBER, 90));
                g.fillRoundRect(0, dy, w, h, radius * 2, radius * 2);
            } else {
                paintKey(g, 0, dy, w, h, radius, hover ? KEY_TOP.brighter() : KEY_TOP, KEY_BOT);
                if (selected) {
                    g.setColor(selectedColor);
                    g.setStroke(new BasicStroke(1.4f));
                    g.drawRoundRect(1, dy + 1, w - 3, h - 3, radius * 2 - 2, radius * 2 - 2);
                }
            }
            Color fg = primary ? new Color(0x16110A) : selected ? selectedColor : (isEnabled() ? TEXT : MUTED);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int tw = text == null || text.isEmpty() ? 0 : fm.stringWidth(text);
            int iw = icon != null ? 18 : 0;
            int gap = icon != null && tw > 0 ? 8 : 0;
            int x = (w - tw - iw - gap) / 2;
            if (icon != null) icon.paint(g, x, dy + (h - 18) / 2, 18, primary ? fg : selected ? selectedColor : iconColor);
            if (tw > 0) {
                g.setColor(fg);
                g.drawString(text, x + iw + gap, dy + (h - fm.getHeight()) / 2 + fm.getAscent());
            }
            if (isFocusOwner()) {
                g.setColor(alpha(AMBER, 120));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2f, 3f}, 0f));
                g.drawRoundRect(3, dy + 3, w - 7, h - 7, radius * 2 - 6, radius * 2 - 6);
            }
            g.dispose();
        }
    }

    /** Interrupteur (pilule creuse, curseur ambre quand actif) avec son libelle. */
    static class Switch extends JComponent {
        private boolean on;
        private final String label;
        private final List<ActionListener> listeners = new ArrayList<>();

        Switch(String label, boolean on) {
            this.label = label;
            this.on = on;
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { toggle(); }
            });
            addKeyListener(new KeyAdapter() {
                @Override public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_SPACE) toggle(); }
            });
        }

        private void toggle() {
            on = !on;
            repaint();
            for (ActionListener l : listeners) l.actionPerformed(new ActionEvent(this, 0, label));
        }

        boolean isOn() { return on; }
        void setOn(boolean v) { on = v; repaint(); }
        void addActionListener(ActionListener l) { listeners.add(l); }

        @Override public Dimension getPreferredSize() { return isPreferredSizeSet() ? super.getPreferredSize() : new Dimension(260, 34); }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int h = getHeight();
            g.setFont(body(13f));
            g.setColor(TEXT2);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            int sw = 40, sh = 22, x = getWidth() - sw - 2, y = (h - sh) / 2;
            paintWell(g, x, y, sw, sh, sh / 2);
            int kx = on ? x + sw - sh + 3 : x + 3;
            g.setColor(on ? AMBER : KEY_TOP);
            g.fillOval(kx, y + 3, sh - 6, sh - 6);
            if (!on) { g.setColor(EDGE); g.drawOval(kx, y + 3, sh - 6, sh - 6); }
            if (isFocusOwner()) { g.setColor(alpha(AMBER, 120)); g.drawRoundRect(x - 2, y - 2, sw + 3, sh + 3, sh, sh); }
            g.dispose();
        }
    }

    /** Pastille d'etat : point colore + texte mono, dans une fenetre creuse arrondie. */
    static class Chip extends JComponent {
        private String text = "";
        private Color dot = MUTED, fg = MUTED;

        void set(String text, Color dot, Color fg) {
            this.text = text;
            this.dot = dot;
            this.fg = fg;
            revalidate();
            repaint();
        }

        String text() { return text; }
        Color dot() { return dot; }
        Color fg() { return fg; }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(mono(11f)).stringWidth(text) + 40, 28);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = smooth(g0);
            int h = getHeight();
            paintWell(g, 0, 0, getWidth(), h, h / 2);
            g.setColor(dot);
            g.fillOval(11, h / 2 - 4, 8, 8);
            if (dot != MUTED) { g.setColor(alpha(dot, 60)); g.fillOval(8, h / 2 - 7, 14, 14); }
            g.setFont(mono(11f));
            g.setColor(fg);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 27, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Titre de section (mono, majuscules, espace). */
    static JLabel section(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(tracked(mono(11f), 0.18f));
        l.setForeground(MUTED);
        l.setBorder(new EmptyBorder(14, 9, 5, 0));
        return l;
    }

    static JLabel label(String text, Font f, Color c) {
        JLabel l = new JLabel(text);
        l.setFont(f);
        l.setForeground(c);
        return l;
    }

    // ------------------------------------------------------------------ Icones (traits, comme les vecteurs Android)

    static final Icon GLOBE = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(Math.max(1.4f, s / 14f)));
        g.drawOval(x + 1, y + 1, s - 2, s - 2);
        g.drawOval(x + s / 4, y + 1, s / 2, s - 2);
        g.drawLine(x + 1, y + s / 2, x + s - 1, y + s / 2);
        g.drawArc(x + 2, y - s / 3, s - 4, s, 200, 140);
        g.drawArc(x + 2, y + s / 3, s - 4, s, 20, 140);
    };

    static final Icon SLIDERS = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int[] ys = {y + s / 5, y + s / 2, y + s * 4 / 5};
        int[] ks = {x + s * 2 / 3, x + s / 3, x + s * 3 / 5};
        for (int i = 0; i < 3; i++) {
            g.drawLine(x + 1, ys[i], x + s - 1, ys[i]);
            g.setColor(BG);
            g.fillOval(ks[i] - 3, ys[i] - 3, 6, 6);
            g.setColor(c);
            g.drawOval(ks[i] - 3, ys[i] - 3, 6, 6);
        }
    };

    static final Icon BARS = (g, x, y, s, c) -> {
        float[] hts = {0.45f, 0.75f, 1.0f, 0.6f, 0.85f};
        g.setColor(c);
        float bw = s / 7f;
        for (int i = 0; i < 5; i++) {
            float bh = (s - 2) * hts[i];
            g.fillRect(Math.round(x + 1 + i * bw * 1.3f), Math.round(y + s - 1 - bh), Math.max(1, Math.round(bw)), Math.round(bh));
        }
    };

    static final Icon PIN = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f));
        Path2D p = pinPath(s, 0);
        p.transform(java.awt.geom.AffineTransform.getTranslateInstance(x, y));
        g.draw(p);
        g.fillOval(x + s / 2 - 2, y + Math.round(s * 0.42f) - 2, 5, 5);
    };

    static final Icon LIST = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 3; i++) {
            int yy = y + s / 4 + i * s / 4;
            g.fillOval(x + 1, yy - 1, 3, 3);
            g.drawLine(x + 7, yy, x + s - 1, yy);
        }
    };

    static final Icon WAVE = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Float();
        for (int i = 0; i <= s; i++) {
            double v = y + s / 2.0 - Math.sin(i / (double) s * Math.PI * 3) * (s / 3.0) * Math.exp(-Math.pow(i / (double) s - 0.5, 2) * 6);
            if (i == 0) p.moveTo(x + i, v); else p.lineTo(x + i, v);
        }
        g.draw(p);
    };

    static final Icon TARGET = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f));
        g.drawOval(x + s / 4, y + s / 4, s / 2, s / 2);
        g.drawLine(x + s / 2, y, x + s / 2, y + s / 5);
        g.drawLine(x + s / 2, y + s, x + s / 2, y + s * 4 / 5);
        g.drawLine(x, y + s / 2, x + s / 5, y + s / 2);
        g.drawLine(x + s, y + s / 2, x + s * 4 / 5, y + s / 2);
    };

    static final Icon PICTURE = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawRoundRect(x + 1, y + s / 6, s - 2, s * 2 / 3, s / 6, s / 6);
        Path2D p = new Path2D.Float();
        p.moveTo(x + s * 0.18f, y + s * 0.72f);
        p.lineTo(x + s * 0.42f, y + s * 0.45f);
        p.lineTo(x + s * 0.6f, y + s * 0.62f);
        p.lineTo(x + s * 0.7f, y + s * 0.52f);
        p.lineTo(x + s * 0.84f, y + s * 0.72f);
        g.draw(p);
        g.fillOval(x + s * 2 / 3 - 2, y + s * 3 / 10 - 2, Math.max(3, s / 7), Math.max(3, s / 7));
    };

    static final Icon PAGER = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setStroke(new BasicStroke(1.6f));
        g.drawRoundRect(x + 1, y + s / 5, s - 2, s * 3 / 5, s / 4, s / 4);
        g.fillRect(x + s / 5, y + s / 3, s * 3 / 5, Math.max(2, s / 9));
        g.fillRect(x + s / 5, y + s / 2, s * 2 / 5, Math.max(2, s / 9));
    };

    static final Icon AB = (g, x, y, s, c) -> {
        g.setColor(c);
        g.setFont(monoBold(s * 0.62f));
        FontMetrics fm = g.getFontMetrics();
        int by = y + (s + fm.getAscent()) / 2 - 2;
        g.drawString("A", x, by);
        g.drawString("B", x + s - fm.stringWidth("B"), by);
        g.setStroke(new BasicStroke(1.6f));
        g.drawLine(x + s / 2, y + 2, x + s / 2, y + s - 2);
    };

    /** Champ de saisie creux (mono). */
    static javax.swing.JTextField field(String text, int columns) {
        javax.swing.JTextField f = new javax.swing.JTextField(text, columns);
        f.setFont(mono(14f));
        f.setForeground(TEXT);
        f.setBackground(WELL);
        f.setCaretColor(AMBER);
        f.setSelectionColor(EDGE);
        f.setSelectedTextColor(TEXT);
        f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), new EmptyBorder(8, 10, 8, 10)));
        return f;
    }

    /** Groupe de touches exclusives (une seule selectionnee). */
    static Key[] choice(JPanel into, String[] labels, int selected, java.util.function.IntConsumer onSelect) {
        Key[] keys = new Key[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            keys[i] = new Key(labels[i]).font(bold(12f));
            keys[i].setPreferredSize(new Dimension(Math.max(64, keys[i].getPreferredSize().width), 40));
            keys[i].addActionListener(e -> {
                for (int k = 0; k < keys.length; k++) keys[k].setSelected(k == idx);
                onSelect.accept(idx);
            });
            keys[i].setSelected(i == selected);
            into.add(keys[i]);
        }
        return keys;
    }

    /** Contour d'epingle de carte (repris de MarkerHelper.buildPinPath). */
    static Path2D pinPath(float size, float offsetY) {
        float cx = size / 2f, top = size * 0.1f + offsetY, circleY = size * 0.42f + offsetY, tipY = size + offsetY;
        Path2D p = new Path2D.Float();
        p.moveTo(cx, tipY);
        p.curveTo(cx - size * 0.05f, circleY + size * 0.28f, cx - size * 0.38f, circleY + size * 0.18f, cx - size * 0.38f, circleY);
        p.append(new java.awt.geom.Arc2D.Float(cx - size * 0.38f, top, size * 0.76f, size * 0.64f, 180f, -180f, java.awt.geom.Arc2D.OPEN), true);
        p.curveTo(cx + size * 0.38f, circleY + size * 0.18f, cx + size * 0.05f, circleY + size * 0.28f, cx, tipY);
        p.closePath();
        return p;
    }

    // ------------------------------------------------------------------ Swing : valeurs par defaut et tableaux

    static void install() {
        try { UIManager.setLookAndFeel("javax.swing.plaf.metal.MetalLookAndFeel"); } catch (Exception ignore) {}
        Object[] kv = {
            "Panel.background", BG, "Label.foreground", TEXT, "Label.font", body(13f),
            "OptionPane.background", BG, "OptionPane.messageForeground", TEXT, "OptionPane.messageFont", body(13f),
            "OptionPane.buttonFont", bold(12f),
            "Button.background", KEY_TOP, "Button.foreground", TEXT, "Button.font", bold(12f), "Button.select", KEY_BOT,
            // Metal peint un degrade clair par-dessus le fond (bouton blanc, texte clair invisible jusqu'au clic)
            "Button.gradient", Arrays.asList(0.3f, 0f, KEY_TOP, KEY_TOP, KEY_BOT),
            "ToggleButton.gradient", Arrays.asList(0.3f, 0f, KEY_TOP, KEY_TOP, KEY_BOT),
            "Button.disabledText", MUTED, "Button.shadow", EDGE, "Button.darkShadow", BG, "Button.highlight", KEY_TOP,
            "Button.focus", AMBER, "Button.border", BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE),
                    BorderFactory.createEmptyBorder(6, 16, 6, 16)),
            "PopupMenu.background", KEY_BOT, "PopupMenu.border", BorderFactory.createLineBorder(EDGE),
            "MenuItem.background", KEY_BOT, "MenuItem.foreground", TEXT, "MenuItem.font", body(13f),
            "MenuItem.selectionBackground", KEY_TOP, "MenuItem.selectionForeground", AMBER,
            "MenuItem.acceleratorForeground", MUTED, "Menu.background", KEY_BOT, "Menu.foreground", TEXT, "Menu.font", body(13f),
            "Menu.selectionBackground", KEY_TOP, "Menu.selectionForeground", AMBER,
            "CheckBoxMenuItem.background", KEY_BOT, "CheckBoxMenuItem.foreground", TEXT, "CheckBoxMenuItem.font", body(13f),
            "CheckBoxMenuItem.selectionBackground", KEY_TOP, "CheckBoxMenuItem.selectionForeground", AMBER,
            "RadioButtonMenuItem.background", KEY_BOT, "RadioButtonMenuItem.foreground", TEXT, "RadioButtonMenuItem.font", body(13f),
            "RadioButtonMenuItem.selectionBackground", KEY_TOP, "RadioButtonMenuItem.selectionForeground", AMBER,
            "Separator.foreground", EDGE, "Separator.background", KEY_BOT,
            "ToolTip.background", KEY_TOP, "ToolTip.foreground", TEXT, "ToolTip.font", body(12f),
            "ToolTip.border", BorderFactory.createLineBorder(EDGE),
            "ScrollPane.background", BG, "Viewport.background", BG, "ScrollPane.border", BorderFactory.createEmptyBorder(),
            "TextArea.background", WELL, "TextArea.foreground", TEXT2, "TextArea.caretForeground", AMBER,
            "TextArea.selectionBackground", EDGE, "TextArea.selectionForeground", TEXT,
            "TextField.background", WELL, "TextField.foreground", TEXT, "TextField.caretForeground", AMBER,
            "Table.background", KEY_BOT, "Table.foreground", TEXT2, "Table.gridColor", EDGE,
            "Table.selectionBackground", new Color(0x2A2416), "Table.selectionForeground", TEXT,
            "TableHeader.background", BG, "TableHeader.foreground", MUTED,
            "List.background", KEY_BOT, "List.foreground", TEXT, "List.selectionBackground", KEY_TOP, "List.selectionForeground", AMBER,
            "ComboBox.background", KEY_BOT, "ComboBox.foreground", TEXT,
            "control", BG, "controlText", TEXT, "window", BG, "text", TEXT,
        };
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            UIManager.put(kv[i], v instanceof Color ? new ColorUIResource((Color) v) : v);
        }
    }

    static JScrollPane scroll(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(c.getBackground());
        sp.setBackground(BG);
        sp.getVerticalScrollBar().setUI(new Bar());
        sp.getHorizontalScrollBar().setUI(new Bar());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        sp.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 10));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        JPanel corner = new JPanel();
        corner.setBackground(BG);
        sp.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        return sp;
    }

    /** Barre de defilement fine, sans fleches. */
    static final class Bar extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { trackColor = BG; thumbColor = EDGE; }
        @Override protected JButton createDecreaseButton(int o) { return zero(); }
        @Override protected JButton createIncreaseButton(int o) { return zero(); }

        private static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(BG);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override
        protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = smooth(g0);
            g.setColor(isDragging ? MUTED : EDGE);
            boolean v = ((JScrollBar) c).getOrientation() == JScrollBar.VERTICAL;
            if (v) g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
            else g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
            g.dispose();
        }
    }

    /** Tableau sombre : lignes alternees, en-tete mono majuscules, colonnes mono au choix. */
    static void styleTable(JTable t, int... monoCols) {
        t.setBackground(KEY_BOT);
        t.setForeground(TEXT2);
        t.setGridColor(EDGE);
        t.setShowVerticalLines(false);
        t.setRowHeight(30);
        t.setFont(body(13f));
        t.setFillsViewportHeight(true);
        t.setIntercellSpacing(new Dimension(0, 1));
        Set<Integer> mono = new HashSet<>();
        for (int c : monoCols) mono.add(c);
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(table, value, sel, false, row, col);
                setBorder(new EmptyBorder(0, 12, 0, 8));
                setFont(mono.contains(col) ? mono(12.5f) : body(13f));
                if (sel) {
                    setBackground(new Color(0x2A2416));
                    setForeground(col == 1 ? AMBER : TEXT);
                } else {
                    setBackground(row % 2 == 0 ? KEY_BOT : CARD_BOT);
                    setForeground(col == 1 ? TEXT : TEXT2);
                }
                return this;
            }
        };
        t.setDefaultRenderer(Object.class, r);
        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(0, 32));
        h.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(table, value == null ? "" : value.toString().toUpperCase(), false, false, row, col);
                setFont(tracked(mono(10.5f), 0.14f));
                setForeground(MUTED);
                setBackground(BG);
                setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, EDGE),
                        new EmptyBorder(0, 12, 0, 8)));
                return this;
            }
        });
    }
}
