package com.htmod;

/** Lancement isole de SatTest (arg : dossier test\data). */
public class SatTestMain {
    public static void main(String[] a) throws Exception {
        SatTest.run(new java.io.File(a[0]));
        System.exit(WinTest.ok ? 0 : 1);
    }
}
