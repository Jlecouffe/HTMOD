/*
 * HTMOD Android : coupure du son des trames POCSAG sur le haut-parleur du telephone (le decodage n'est
 * pas touche), comme AprsMute pour l'APRS. Une trame est reperee de trois facons :
 *  - le poste recoit sur un canal POCSAG (nom contenant « POCSAG » ou « DAPNET ») ;
 *  - le preambule (bits alternes 1010...) donne une tonalite pure a debit / 2 : 256, 600 ou 1200 Hz
 *    (part de l'energie dans trois passe-bande, par blocs de 8 ms) ;
 *  - le decodeur POCSAG est synchronise sur une trame.
 * Le son est coupe pendant la trame et 600 ms apres, remplace par du silence.
 */
package com.htmod

import java.util.Locale

class PocsagMute(rate: Int) {
    // 512 bd : la tonalite de 256 Hz est coupee par le poste (passe-haut 290 Hz), on voit son harmonique 3 (768 Hz)
    private val filters = doubleArrayOf(256.0, 600.0, 768.0, 1200.0).map { bandpass(it, 5.0, rate) }
    private val state = Array(filters.size) { DoubleArray(2) }
    private val acc = DoubleArray(filters.size)
    private var x1 = 0.0
    private var x2 = 0.0
    private var energy = 0.0
    private var n = 0
    private var hits = 0
    private var holdUntil = 0L

    /** Bloc a jouer : le meme, ou du silence s'il fait partie d'une trame POCSAG. */
    fun filter(pcm: ShortArray, len: Int, pocsagChannel: Boolean, synced: Boolean): ShortArray {
        val now = System.currentTimeMillis()
        for (i in 0 until len) {
            val x = pcm[i] / 32768.0
            for (k in filters.indices) {
                val f = filters[k]
                val s = state[k]
                val y = f[0] * x + f[1] * x2 - f[2] * s[0] - f[3] * s[1]
                s[1] = s[0]; s[0] = y
                acc[k] += y * y
            }
            x2 = x1; x1 = x
            energy += x * x
            if (++n == BLOCK) {
                val tone = (acc.maxOrNull() ?: 0.0) / BLOCK
                val mean = energy / BLOCK
                // trois blocs d'affilee (~24 ms) domines par une seule tonalite de preambule
                if (mean > 1e-4 && tone > 0.55 * mean) { if (++hits >= 3) holdUntil = now + HOLD_MS } else hits = 0
                acc.fill(0.0); energy = 0.0; n = 0
            }
        }
        if (pocsagChannel || synced) holdUntil = now + HOLD_MS
        return if (now < holdUntil) ShortArray(len) else pcm
    }

    companion object {
        private const val BLOCK = 256
        private const val HOLD_MS = 600

        fun pocsagChannel(name: String?): Boolean {
            val n = (name ?: "").uppercase(Locale.ROOT)
            return n.contains("POCSAG") || n.contains("DAPNET")
        }

        /** Passe-bande RBJ (gain 1 au centre) : b0, b2, a1, a2 (b1 = 0). */
        private fun bandpass(f: Double, q: Double, rate: Int): DoubleArray {
            val w = 2 * Math.PI * f / rate
            val al = Math.sin(w) / (2 * q)
            val a0 = 1 + al
            return doubleArrayOf(al / a0, -al / a0, -2 * Math.cos(w) / a0, (1 - al) / a0)
        }
    }
}
