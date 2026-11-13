/*
 * HTMOD Windows : tests de l'envoi vers WhatsApp sans reseau : filtre des RIC, echappement JSON.
 */
package com.htmod;

final class WhatsAppTest {
    private WhatsAppTest() {}

    static void run() {
        System.out.println("WhatsApp :");
        WinTest.check("filtre vide : tous les RIC", WhatsApp.ricAccepted("", 25819));
        WinTest.check("liste « 25814, 25819 »", WhatsApp.ricAccepted("25814, 25819", 25819) && !WhatsApp.ricAccepted("25814, 25819", 25820));
        WinTest.check("plage « 25800-25899 »", WhatsApp.ricAccepted("25800-25899", 25846) && !WhatsApp.ricAccepted("25800-25899", 1234567));
        WinTest.check("saisie mal formee ignoree", WhatsApp.ricAccepted("abc 1234567", 1234567));
        WinTest.check("JSON : guillemets, retours a la ligne", "\"a\\\"b\\nc\"".equals(WhatsApp.quote("a\"b\nc")));
    }
}
