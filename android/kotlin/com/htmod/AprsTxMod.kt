/*
 * HTMOD Android : emission APRS (portage de AprsTxPanel.java). Parametres lus dans le poste ; la trame
 * est confiee au TNC du poste (HT_SEND_DATA) sur le canal « APRS » sinon celui du VFO A ; emission
 * manuelle ou balise automatique, bandes radioamateur seulement.
 */
package com.htmod

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AprsTxMod(private val host: Engine) {
    @Volatile private var pending: ByteArray? = null
    @Volatile private var lastSent = 0L
    @Volatile var auto = false
        private set
    @Volatile var status = " "
        private set
    @Volatile var statusColor = Col.MUTED
        private set
    var info = Prefs.get("aprsTxInfo", "")

    val waiting get() = pending != null

    init {
        Thread({
            while (true) {
                try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
                try { tickAuto() } catch (e: RuntimeException) {}
            }
        }, "htmod-aprs-balise").apply { isDaemon = true; start() }
    }

    private fun status(s: String, c: Int) { status = s; statusColor = c }

    private fun aprsChannel(r: Radio): Int {
        for (i in 0 until r.channelCount()) if (r.channelName(i).uppercase(Locale.ROOT).contains("APRS")) return i
        return -1
    }

    private fun txHz(r: Radio, ch0: Int): Long {
        val ch = if (ch0 < 0) r.channelA() else ch0
        val c = r.channel(ch)
        if (c != null && c.txDisable) return 0
        return r.channelTxHz(ch)
    }

    private fun path(r: Radio): List<String> {
        var p = r.aprsPath()
        if (p == null || p.isBlank()) p = DEFAULT_PATH
        return p.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }.map { it.uppercase(Locale.ROOT) }
    }

    private fun position(r: Radio): DoubleArray? {
        if (r.gpsLocked() && !r.gpsLat().isNaN()) return doubleArrayOf(r.gpsLat(), r.gpsLon(), 1.0)
        val lat = Prefs.get("obsLat", "").toDoubleOrNull()
        val lon = Prefs.get("obsLon", "").toDoubleOrNull()
        if (lat != null && lon != null && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return doubleArrayOf(lat, lon, 0.0)
        return null
    }

    private fun symTable(a: Radio.AprsSettings) = if (a.symbol.length >= 2) a.symbol[0] else '/'
    private fun symCode(a: Radio.AprsSettings) = if (a.symbol.length >= 2) a.symbol[1] else if (a.symbol.length == 1) a.symbol[0] else '['

    /** Reglages APRS lus dans le poste, en lignes « titre : valeur ». */
    fun params(): List<Pair<String, String>> {
        val r = host.radio
        val a = r.aprsSettings()
        if (!r.isRunning) return listOf(Pair("", tr("Poste non connecté.")))
        if (a == null) return listOf(Pair("", tr("Réglages APRS pas encore lus…")))
        val ch = aprsChannel(r)
        val hz = txHz(r, ch)
        val pos = position(r)
        return listOf(
            Pair(tr("Indicatif"), if (a.source().isEmpty()) tr("(non réglé dans le poste)") else a.source()),
            Pair(tr("Symbole"), if (a.symbol.isEmpty()) tr("(aucun, /[ par défaut)") else a.symbol + "  " + Aprs.symbolName(symTable(a), symCode(a))),
            Pair(tr("Chemin"), path(r).joinToString(",") + if (r.aprsPath().isNullOrEmpty()) tr(" (défaut)") else ""),
            Pair(tr("Commentaire"), if (a.beacon.isEmpty()) tr("(aucun)") else a.beacon),
            Pair(tr("Intervalle"), (if (a.intervalS > 0) "${a.intervalS} s" else tr("(non réglé)")) + (if (a.shareLocation) tr(" · partage de position actif") else "") + if (a.smartBeacon) " · SmartBeacon" else ""),
            Pair(tr("Format"), if (a.packetFormat == 1) "APRS" else tr("BSS (le poste émet en BSS ; HTMod émet en APRS)")),
            Pair(tr("Position"), if (pos == null) tr("aucune (GPS du poste sans fix, pas de position d'observation)")
                else Aprs.fmtPos(pos[0], pos[1]) + if (pos[2] > 0) tr(" (GPS du poste)") else tr(" (position d'observation)")),
            Pair(tr("Canal"), (if (ch >= 0) "${ch + 1} " + r.channelName(ch) else tr("VFO A (pas de canal « APRS »)")) +
                if (hz > 0) " · " + Engine.mhz(hz) + " MHz" + (if (PocsagMod.amateur(hz)) "" else tr(" (hors bande radioamateur)")) else tr(" · émission interdite")))
    }

    /** Emet une trame ; info vide = balise de position. Renvoie un message d'erreur ou null. */
    fun send(info0: String): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Connectez d'abord le poste.")
        val a = r.aprsSettings() ?: run { r.readAprsSettings(); return tr("Réglages APRS du poste pas encore lus, réessayez dans un instant.") }
        if (a.source().isEmpty()) return tr("Aucun indicatif APRS réglé dans le poste (réglages APRS / BSS du poste ou de son appli).")
        if (pending != null || r.tncBusy()) return tr("Une trame attend déjà que le canal se libère.")
        val ch = aprsChannel(r)
        val hz = txHz(r, ch)
        if (hz <= 0) return tr("Émission interdite sur le canal d'émission (ou canaux pas encore lus).")
        if (!PocsagMod.amateur(hz)) return tr("Émission refusée : ") + Engine.mhz(hz) + tr(" MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz).")
        var info = info0.trim()
        if (info.isEmpty()) {
            val pos = position(r)
            info = if (pos == null) ">" + a.beacon else positionInfo(pos[0], pos[1], symTable(a), symCode(a), a.beacon)
        }
        val frame = try { Ax25.encodeUi(a.source(), TOCALL, path(r), info) } catch (e: IllegalArgumentException) { return tr("Trame impossible : ") + e.message }
        if (!r.sendTncFrame(frame, ch)) return tr("Le TNC du poste est occupé, réessayez.")
        pending = frame
        lastSent = System.currentTimeMillis()
        val p = Ax25.parse(frame)
        host.log(tr("APRS : émission ") + (p?.tnc2() ?: "") + tr(" (canal ") + (if (ch >= 0) (ch + 1).toString() else "VFO A") + ", " + Engine.mhz(hz) + " MHz)")
        status(tr("En attente du canal libre…"), Col.AMBER)
        return null
    }

    fun onSent(ok: Boolean) {
        val f = pending
        pending = null
        if (ok && f != null) host.sentAprs(f)
        status(if (ok) tr("Trame émise à ") + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) else tr("Trame non émise (canal occupé ou refus du poste)."),
            if (ok) Col.GREEN else Col.RED)
    }

    fun onDisconnected() { pending = null }

    private fun interval(a: Radio.AprsSettings) = if (a.intervalS > 0) Math.max(60, a.intervalS) else 600

    fun setAuto(on: Boolean): String? {
        val r = host.radio
        val a = r.aprsSettings()
        if (on && (!r.isRunning || a == null || a.source().isEmpty())) {
            auto = false
            return if (!r.isRunning) tr("Connectez d'abord le poste.") else tr("Réglages APRS du poste non lus ou sans indicatif.")
        }
        auto = on
        if (on) lastSent = 0
        host.log(tr("APRS : balise automatique ") + if (on) tr("activée") else tr("arrêtée"))
        return null
    }

    fun intervalText(): String = host.radio.aprsSettings()?.let { interval(it).toString() + " s" } ?: "?"

    fun reload(): String? {
        if (!host.radio.isRunning) return tr("Connectez d'abord le poste.")
        host.radio.readAprsSettings()
        status(tr("Lecture des réglages APRS du poste…"), Col.MUTED)
        return null
    }

    private fun tickAuto() {
        if (!auto) return
        val r = host.radio
        val a = r.aprsSettings() ?: return
        if (!r.isRunning || pending != null) return
        if (System.currentTimeMillis() - lastSent < interval(a) * 1000L) return
        val err = send("")
        if (err != null) { lastSent = System.currentTimeMillis(); status(tr("Balise automatique : ") + err, Col.RED) }
    }

    companion object {
        const val TOCALL = "APZHTM"
        const val DEFAULT_PATH = "WIDE1-1,WIDE2-1"

        fun positionInfo(lat: Double, lon: Double, table: Char, code: Char, comment: String?): String {
            val la = Math.abs(lat)
            val lo = Math.abs(lon)
            var ld = la.toInt()
            var od = lo.toInt()
            var lm = (la - ld) * 60
            var om = (lo - od) * 60
            if (lm >= 59.995) { ld++; lm = 0.0 }
            if (om >= 59.995) { od++; om = 0.0 }
            return String.format(Locale.ROOT, "!%02d%05.2f%c%c%03d%05.2f%c%c%s", ld, lm, if (lat < 0) 'S' else 'N', table,
                od, om, if (lon < 0) 'W' else 'E', code, comment ?: "")
        }
    }
}
