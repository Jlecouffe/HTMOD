package com.htmod;

/** Canaux memoire : encodage 25 octets (format HTCommander) et sous-tons. */
final class ChannelTest {
    private ChannelTest() {}

    static void run() {
        System.out.println("Canaux :");
        // canal aviation AM 118,150 MHz, sans emission, nom « CDG app1 »
        Radio.Channel a = new Radio.Channel();
        a.id = 25; a.rxHz = 118_150_000; a.txHz = 118_150_000; a.rxMod = a.txMod = 1; a.wide = true; a.txDisable = true;
        a.name = "CDG app1"; a.flags2 = 0x10;
        byte[] b = a.toBytes();
        Radio.Channel a2 = Radio.Channel.parse(b);
        boolean same = java.util.Arrays.equals(b, a2.toBytes());
        WinTest.check("aller-retour 25 octets (AM, émission interdite, nom GBK)", same && b.length == 25 && a2.rxHz == 118_150_000
                && a2.rxMod == 1 && a2.txDisable && "CDG app1".equals(a2.name) && a2.mute());
        // relais 2 m : -600 kHz, CTCSS 88,5 en emission, DCS 023 en reception, puissance moyenne
        Radio.Channel r = new Radio.Channel();
        r.id = 3; r.rxHz = 145_600_000; r.txHz = 145_000_000; r.txSub = Radio.Channel.parseSub("88,5"); r.rxSub = Radio.Channel.parseSub("D023");
        r.medPower = true; r.scan = true; r.name = "F1ZXX";
        Radio.Channel r2 = Radio.Channel.parse(r.toBytes());
        WinTest.check("relais : décalage, CTCSS 88.5 = " + r2.txSub + ", DCS D023 = " + r2.rxSub,
                r2.txHz == 145_000_000 && r2.txSub == 8850 && r2.rxSub == 23 && r2.medPower && !r2.maxPower && r2.scan
                        && "88.5".equals(Radio.Channel.subText(r2.txSub)) && "D023".equals(Radio.Channel.subText(r2.rxSub)));
        boolean refused = false;
        try { Radio.Channel.parseSub("300"); } catch (IllegalArgumentException e) { refused = true; }
        WinTest.check("sous-ton hors plage refusé", refused && Radio.Channel.parseSub("") == 0);
    }
}
