/*
 * HTMOD Android : VFO du poste (portage de VfoPanel.java) : double veille, volume, squelch, PTT avec le
 * micro du telephone, groupes et canaux memoire (choix, ajout, modification, renommage), accord libre.
 */
package com.htmod

import java.nio.charset.Charset
import java.util.Locale

class VfoMod(private val host: Engine) {
    var gainIdx = Prefs.getInt("pttGainIdx", 1).coerceIn(0, GAINS.size - 1)
        set(v) { field = v; Prefs.putInt("pttGainIdx", v) }
    @Volatile var pttHeld = false
        private set
    private var pttStart = 0L
    @Volatile private var pttErr: String? = null
    @Volatile private var errUntil = 0L

    class Ch(val ch: Int, val name: String, val rx: String, val tx: String, val vfo: String)

    fun channels(): List<Ch> {
        val r = host.radio
        val out = ArrayList<Ch>()
        if (!r.isRunning) return out
        for (i in 0 until r.channelCount()) {
            if (r.channelRxHz(i) <= 0 && r.channelName(i).isEmpty()) continue
            val rx = r.channelRxHz(i)
            val tx = r.channelTxHz(i)
            val txs = if (tx <= 0) tr("RX seul") else if (tx == rx) mhz(tx) else mhz(tx) + String.format(Locale.ROOT, " (%+.1f)", (tx - rx) / 1e6)
            val a = i == r.channelA()
            val b = i == r.channelB() && r.dualWatch() > 0
            val v = if (a && b) "A+B" else if (a) "A" else if (b) "B" else if (i == r.channelB()) "(B)" else ""
            out.add(Ch(i, r.channelName(i), mhz(rx), txs, v))
        }
        return out
    }

    fun groups(): List<String> {
        val r = host.radio
        if (!r.isRunning) return emptyList()
        return (0 until r.regionCount()).map { tr("Groupe ") + (it + 1) + if (r.regionName(it).isEmpty()) "" else " · " + r.regionName(it) }
    }

    fun label(ch: Int): String {
        val r = host.radio
        if (!r.isRunning || ch < 0) return "–"
        val n = r.channelName(ch)
        return tr("Canal ") + (ch + 1) + if (n.isEmpty()) "" else " · $n"
    }

    fun freq(ch: Int): String {
        val r = host.radio
        return if (r.isRunning && ch >= 0 && r.channelRxHz(ch) > 0) mhz(r.channelRxHz(ch)) + " MHz" else " "
    }

    /** Etat de la double veille : texte et couleur. */
    fun dualState(): Pair<String, Int> {
        val r = host.radio
        val dual = r.dualWatch()
        val act = r.activeVfo()
        return when {
            !r.isRunning -> Pair(tr("POSTE NON CONNECTÉ"), Col.MUTED)
            dual < 0 -> Pair(tr("LECTURE DES RÉGLAGES…"), Col.MUTED)
            dual > 0 -> Pair(tr("DOUBLE VEILLE ACTIVE") + when (act) { 1 -> tr(" · RÉCEPTION SUR A"); 2 -> tr(" · RÉCEPTION SUR B"); else -> "" }, Col.CYAN)
            else -> Pair(tr("UN SEUL VFO (A)"), Col.AMBER)
        }
    }

    fun pttState(): Pair<String, Int> {
        val r = host.radio
        val on = r.isRunning
        if (pttHeld) {
            return Pair((if (r.isInTx) tr("Le poste émet") else tr("Envoi de l'audio au poste…")) + tr(" sur ") + Engine.mhz(r.currentTxHz()) +
                " MHz · " + (System.currentTimeMillis() - pttStart) / 1000 + " s", Col.RED)
        }
        val e = pttErr
        if (e != null && System.currentTimeMillis() < errUntil) return Pair(e, Col.RED)
        val tx = if (on) r.currentTxHz() else 0
        return Pair(if (!on) tr("Poste non connecté") else if (tx > 0) tr("Émission sur ") + Engine.mhz(tx) + " MHz" else tr("Fréquence d'émission inconnue"), Col.MUTED)
    }

    fun tuneState(): String {
        val r = host.radio
        val u = r.userVfoHz()
        return if (!r.isRunning) " " else if (u > 0) tr("Accordé sur ") + Engine.mhz(u) + tr(" MHz (mode fréquence)") else tr("Sur les canaux mémoire")
    }

    // ------------------------------------------------------------------ Actions (renvoient un message d'erreur ou null)

    fun setDual(on: Boolean): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Connectez d'abord le poste.")
        if (on) { host.stopSweep(); r.backToChannels() }
        if (!r.setDualWatch(on)) return tr("Réglages du poste pas encore lus.")
        host.log(tr("Double veille ") + if (on) tr("activée") else tr("désactivée"))
        return null
    }

    fun volume(d: Int) { val r = host.radio; if (r.isRunning && r.volume() >= 0) r.setVolume(r.volume() + d) }

    fun squelch(d: Int) { val r = host.radio; if (r.isRunning && r.squelch() >= 0) r.setSquelch(r.squelch() + d) }

    fun pttDown() {
        if (pttHeld) return
        val r = host.radio
        val t = host.transmitter
        if (!r.isRunning || !r.audioOpen()) { pttError(tr("Poste non connecté (canal audio fermé)")); return }
        if (t.isBusy) { pttError(tr("Une émission est déjà en cours (POCSAG / SSTV)")); return }
        val hz = r.currentTxHz()
        if (hz <= 0) { pttError(tr("Émission impossible : fréquence d'émission inconnue ou canal en réception seule")); return }
        if (!host.hasMic()) { pttError(tr("Autorisez le micro pour HTMod")); host.ui.askMic(); return }
        host.stopSweep()
        pttHeld = true
        pttStart = System.currentTimeMillis()
        try {
            val ok = t.startLive(GAINS[gainIdx] / 100.0, 180, object : Transmitter.Listener {
                override fun onTxStart(seconds: Double) { host.log(tr("PTT : émission sur ") + Engine.mhz(hz) + " MHz") }
                override fun onTxEnd(cancelled: Boolean) {
                    pttHeld = false
                    host.log(tr("PTT : fin d'émission (") + (System.currentTimeMillis() - pttStart) / 1000 + " s" + (if (cancelled) tr(", coupure de sécurité") else "") + ")")
                }
            })
            if (!ok) { pttHeld = false; pttError(tr("Émission impossible (canal audio fermé)")) }
        } catch (e: IllegalStateException) {
            pttHeld = false
            pttError(tr("Émission impossible : ") + e.message)
        }
    }

    fun pttUp() { if (pttHeld) host.transmitter.stopLive() }

    private fun pttError(s: String) { pttErr = s; errUntil = System.currentTimeMillis() + 5000 }

    fun group(g: Int) {
        val r = host.radio
        if (g >= 0 && r.isRunning && g < r.regionCount() && g != r.currentRegion()) {
            host.stopSweep()
            r.backToChannels()
            r.setRegion(g)
        }
    }

    fun assign(vfo: Int, ch: Int): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Connectez d'abord le poste.")
        if (r.userVfoHz() > 0 || r.isFreqMode) { host.stopSweep(); r.backToChannels() }
        return if (r.setVfoChannel(vfo, ch)) null else tr("Réglages du poste pas encore lus.")
    }

    fun tune(f: String): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Connectez d'abord le poste.")
        val v = f.trim().replace(',', '.').toDoubleOrNull() ?: return tr("Fréquence invalide.")
        val hz = Math.round(v * 1e6)
        if (!((hz in 136_000_000..174_000_000) || (hz in 400_000_000..520_000_000))) return tr("Hors des bandes du poste (136–174 / 400–520 MHz).")
        Prefs.put("vfoFreq", f.trim())
        host.stopSweep()
        r.tuneUser(hz)
        host.log(tr("Accord sur ") + Engine.mhz(hz) + tr(" MHz (mode fréquence)"))
        return null
    }

    fun back() {
        val r = host.radio
        if (!r.isRunning) return
        host.stopSweep()
        r.backToChannels()
        host.log(tr("Retour aux canaux mémoire"))
    }

    fun renameGroup(name: String): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Connectez d'abord le poste.")
        val nm = name.trim()
        if (nm.isEmpty()) return tr("Nom vide.")
        if (nm.toByteArray(GBK).size > 10) return tr("Nom trop long (10 caractères au plus).")
        return if (r.setRegionName(r.currentRegion(), nm)) null else tr("Groupe inconnu ou poste déconnecté.")
    }

    /** Canal lu (null s'il ne l'est pas encore). */
    fun channel(ch: Int): Radio.Channel? = host.radio.channel(ch)

    fun firstFree(): Int = host.radio.firstFreeChannel()

    /** Ecrit un canal (nouveau ou modifie) dans le poste ; renvoie un message d'erreur ou null. */
    fun write(c: Radio.Channel, name: String, rx: String, tx: String, rxTone: String, txTone: String, power: Int, wide: Boolean,
              am: Boolean, scan: Boolean, txOff: Boolean, flat: Boolean): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Poste déconnecté.")
        return try {
            val n = Radio.Channel.parse(c.toBytes())
            val nm = name.trim()
            if (nm.toByteArray(GBK).size > 10) throw IllegalArgumentException(tr("Nom trop long (10 caractères au plus)."))
            n.name = nm
            n.rxHz = parseMhz(rx, tr("réception"))
            n.txHz = if (tx.isBlank()) n.rxHz else parseMhz(tx, tr("émission"))
            n.rxSub = Radio.Channel.parseSub(rxTone)
            n.txSub = Radio.Channel.parseSub(txTone)
            n.maxPower = power == 0
            n.medPower = power == 1
            n.wide = wide
            n.rxMod = if (am) 1 else 0
            n.txMod = n.rxMod
            n.scan = scan
            n.txDisable = txOff
            n.preDeEmphBypass = flat
            r.writeChannel(n)
            null
        } catch (e: NumberFormatException) {
            tr("Valeur invalide : ") + e.message
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    companion object {
        val GAINS = intArrayOf(50, 100, 150, 200)
        private val GBK: Charset = Charset.forName("GBK")

        fun mhz(hz: Long) = if (hz > 0) String.format(Locale.ROOT, "%.4f", hz / 1e6) else "—"

        private fun parseMhz(s: String, what: String): Long {
            val v = s.trim().replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException(tr("Fréquence de ") + what + tr(" invalide."))
            val hz = Math.round(v * 1e6)
            if (hz < 18_000_000L || hz > 1_000_000_000L) throw IllegalArgumentException(tr("Fréquence de ") + what + tr(" hors plage : ") + s.trim() + " MHz.")
            return hz
        }
    }
}
