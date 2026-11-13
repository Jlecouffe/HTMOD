/*
 * HTMOD Windows : fenetre de reglage de l'envoi des messages POCSAG vers un groupe WhatsApp (gratuit,
 * pont wabridge.exe) : liaison du PC par QR code, choix du groupe dans la liste du compte, filtre des
 * RIC, message de test.
 */
package com.htmod;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Image;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

final class WhatsAppDialog {
    private WhatsAppDialog() {}

    static void show(Window owner, WhatsApp wa, Runnable onSaved) {
        JDialog d = new JDialog(owner, Tr.t("Messages POCSAG vers WhatsApp"));
        d.setModal(true);
        JPanel p = new JPanel();
        p.setBackground(Shack.BG);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(16, 18, 14, 18));

        Shack.Switch on = new Shack.Switch(Tr.t("Envoyer les messages POCSAG reçus vers un groupe WhatsApp"), wa.enabled());
        JTextField chat = Shack.field(wa.chat(), 30), rics = Shack.field(wa.rics(), 30);
        JComboBox<WhatsApp.Group> groups = new JComboBox<>();
        groups.setFont(Shack.body(13f));
        if (!wa.chat().isEmpty()) groups.addItem(new WhatsApp.Group(wa.chat(), wa.chatName()));
        Shack.Switch details = new Shack.Switch(Tr.t("Ajouter l'heure, le RIC, la fonction et le débit au message"), wa.details());
        JLabel link = Shack.label(" ", Shack.mono(12f), Shack.MUTED);
        JLabel qr = new JLabel();
        qr.setVisible(false);
        JLabel state = Shack.label(" ", Shack.mono(11.5f), Shack.MUTED);

        // chaque changement est enregistre tout de suite (pas besoin de ENREGISTRER)
        boolean[] filling = {false};
        Runnable store = () -> {
            WhatsApp.Group g = (WhatsApp.Group) groups.getSelectedItem();
            String name = g != null && g.id.equals(chat.getText().trim()) ? g.name : "";
            wa.save(on.isOn(), chat.getText(), name, rics.getText(), details.isOn());
            onSaved.run();
        };
        groups.addActionListener(e -> {
            if (filling[0]) return;
            WhatsApp.Group g = (WhatsApp.Group) groups.getSelectedItem();
            if (g == null) return;
            chat.setText(g.id);
            if (wa.linked()) on.setOn(true); // groupe choisi sur un PC relie : l'envoi demarre
            store.run();
        });
        on.addActionListener(e -> store.run());
        details.addActionListener(e -> store.run());
        java.awt.event.FocusAdapter saveOnLeave = new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) { store.run(); }
        };
        for (JTextField f : new JTextField[]{chat, rics}) {
            f.addActionListener(e -> store.run());
            f.addFocusListener(saveOnLeave);
        }

        p.add(left(Shack.label(Tr.t("ENVOI DES MESSAGES POCSAG VERS WHATSAPP"), Shack.tracked(Shack.mono(10f), 0.14f), Shack.MUTED)));
        p.add(left(fill(on)));
        JLabel help = new JLabel(Tr.t("<html><body style='width:440px'>Gratuit, sans compte supplémentaire : le PC est relié à votre WhatsApp ")
                + Tr.t("comme WhatsApp Web. Touchez RELIER, puis dans WhatsApp sur le téléphone : Réglages › Appareils connectés › ")
                + Tr.t("Connecter un appareil, et scannez le QR code. Client WhatsApp non officiel (bibliothèque libre whatsmeow) : ")
                + Tr.t("usage personnel et modéré conseillé.</body></html>"));
        help.setFont(Shack.body(12f));
        help.setForeground(Shack.MUTED);
        help.setBorder(new EmptyBorder(6, 2, 6, 0));
        p.add(left(help));
        p.add(left(link));
        Shack.Key bind = new Shack.Key(Tr.t("RELIER MON WHATSAPP (QR CODE)")).primary().font(Shack.bold(11.5f));
        Shack.Key unbind = new Shack.Key(Tr.t("DÉLIER")).font(Shack.bold(11.5f));
        p.add(left(row(bind, unbind)));
        p.add(left(qr));
        Shack.Key list = new Shack.Key(Tr.t("LISTER MES GROUPES")).font(Shack.bold(11.5f));
        p.add(left(cap(Tr.t("Groupe destinataire"))));
        p.add(left(groups));
        p.add(left(row(list)));
        p.add(left(cap(Tr.t("Identifiant du groupe (rempli par la liste, ex. 120363…@g.us)"))));
        p.add(left(chat));
        p.add(left(cap(Tr.t("RIC à transmettre (vide = tous ; ex. 25814, 25819, 25800-25899)"))));
        p.add(left(rics));
        p.add(left(fill(details)));
        p.add(left(state));

        Boolean[] shown = {null};
        Runnable refreshLink = () -> {
            boolean ok = wa.linked();
            link.setText(ok ? Tr.t("PC relié au compte WhatsApp +") + wa.phone() + (wa.connected() ? "" : Tr.t(" (connexion…)")) : Tr.t("PC non relié à WhatsApp"));
            link.setForeground(ok ? Shack.GREEN : Shack.AMBER);
            if (shown[0] != null && shown[0] == ok) return;
            shown[0] = ok;
            bind.setVisible(!ok);
            unbind.setVisible(ok);
            if (ok) qr.setVisible(false);
            d.pack();
        };
        Timer t = new Timer(1000, e -> refreshLink.run());
        t.start();

        bind.addActionListener(e -> {
            state.setText(Tr.t("Connexion à WhatsApp, QR code en préparation…"));
            state.setForeground(Shack.MUTED);
            try {
                wa.login(png -> SwingUtilities.invokeLater(() -> {
                    try {
                        BufferedImage im = ImageIO.read(new File(png));
                        if (im == null) return;
                        qr.setIcon(new ImageIcon(im.getScaledInstance(260, 260, Image.SCALE_FAST)));
                        qr.setVisible(true);
                        state.setText(Tr.t("Scannez ce QR code avec WhatsApp (Réglages › Appareils connectés › Connecter un appareil)"));
                        state.setForeground(Shack.CYAN);
                        d.pack();
                    } catch (Exception ignore) {
                    }
                }), res -> SwingUtilities.invokeLater(() -> {
                    boolean ok = "success".equals(res);
                    if (ok && !chat.getText().trim().isEmpty()) { on.setOn(true); store.run(); }
                    state.setText(ok ? Tr.t("PC relié : choisissez maintenant le groupe (LISTER MES GROUPES)")
                            : Tr.t("Liaison non aboutie (") + res + Tr.t(") : touchez RELIER pour un nouveau QR code"));
                    state.setForeground(ok ? Shack.GREEN : Shack.RED);
                    qr.setVisible(false);
                    refreshLink.run();
                }));
            } catch (Exception ex) {
                state.setText(Tr.t("Liaison impossible : ") + ex.getMessage());
                state.setForeground(Shack.RED);
            }
        });
        unbind.addActionListener(e -> {
            try { wa.logout(); } catch (Exception ex) { state.setText(Tr.t("Liaison impossible : ") + ex.getMessage()); }
        });
        list.addActionListener(e -> {
            state.setText(Tr.t("Lecture des groupes…"));
            state.setForeground(Shack.MUTED);
            new Thread(() -> {
                try {
                    List<WhatsApp.Group> gs = wa.groups();
                    SwingUtilities.invokeLater(() -> {
                        String cur = chat.getText().trim();
                        filling[0] = true; // remplir la liste ne doit pas choisir un groupe a la place de l'utilisateur
                        groups.removeAllItems();
                        WhatsApp.Group sel = null;
                        for (WhatsApp.Group g : gs) { groups.addItem(g); if (g.id.equals(cur)) sel = g; }
                        if (sel != null) groups.setSelectedItem(sel); else groups.setSelectedIndex(-1);
                        filling[0] = false;
                        state.setText(gs.size() + Tr.t(" groupe(s) trouvé(s)") + (sel == null && !gs.isEmpty() ? Tr.t(" : choisissez le groupe dans la liste") : ""));
                        state.setForeground(gs.isEmpty() ? Shack.AMBER : Shack.GREEN);
                    });
                } catch (Exception ex) {
                    String m = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    SwingUtilities.invokeLater(() -> { state.setText(Tr.t("Liste impossible : ") + m); state.setForeground(Shack.RED); });
                }
            }, "htmod-whatsapp-groupes").start();
        });

        JPanel br = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        br.setOpaque(false);
        Shack.Key test = new Shack.Key(Tr.t("ENVOYER UN TEST")).font(Shack.bold(12f));
        test.addActionListener(e -> {
            store.run();
            state.setText(Tr.t("Envoi du message de test…"));
            state.setForeground(Shack.MUTED);
            new Thread(() -> {
                String err = wa.sendNow(wa.chat(), Tr.t("📟 HTMod : test de l'envoi des messages POCSAG vers ce groupe."));
                SwingUtilities.invokeLater(() -> {
                    state.setText(err == null ? Tr.t("Message de test envoyé au groupe") : Tr.t("Échec : ") + err);
                    state.setForeground(err == null ? Shack.GREEN : Shack.RED);
                });
            }, "htmod-whatsapp-test").start();
        });
        Shack.Key cancel = new Shack.Key(Tr.t("FERMER")).font(Shack.bold(12f));
        cancel.addActionListener(e -> d.dispose());
        Shack.Key ok = new Shack.Key(Tr.t("ENREGISTRER")).primary().font(Shack.bold(12f));
        ok.addActionListener(e -> {
            // ENREGISTRER avec un PC relie et un groupe choisi : l'envoi est forcement voulu
            if (wa.linked() && !chat.getText().trim().isEmpty()) on.setOn(true);
            store.run();
            d.dispose();
        });
        br.add(test);
        br.add(cancel);
        br.add(ok);
        p.add(left(br));
        d.setContentPane(p);
        refreshLink.run();
        d.setLocationRelativeTo(owner);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { t.stop(); store.run(); }
        });
        d.setVisible(true);
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (c instanceof JTextField || c instanceof JComboBox) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        return c;
    }

    private static JComponent fill(JComponent c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return c;
    }

    private static JLabel cap(String s) {
        JLabel l = Shack.label(s, Shack.body(12f), Shack.MUTED);
        l.setBorder(new EmptyBorder(10, 2, 4, 0));
        return l;
    }

    private static JPanel row(JComponent... cs) {
        JPanel r = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        r.setOpaque(false);
        for (JComponent c : cs) r.add(c);
        return r;
    }
}
