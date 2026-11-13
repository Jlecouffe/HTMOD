/*
 * HTMOD Android : outil de construction (PC) : icones de l'appli dessinees par Logo.java (meme logo
 * que HTMod Windows), une par densite d'ecran : <res>/mipmap-<densite>/ic_launcher.png.
 */
package com.htmod;

import java.io.File;
import javax.imageio.ImageIO;

public final class IconGen {
    public static void main(String[] a) throws Exception {
        File res = new File(a[0]);
        String[] dens = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] size = {48, 72, 96, 144, 192};
        for (int i = 0; i < dens.length; i++) {
            File d = new File(res, "mipmap-" + dens[i]);
            d.mkdirs();
            ImageIO.write(Logo.image(size[i]), "png", new File(d, "ic_launcher.png"));
        }
    }
}
