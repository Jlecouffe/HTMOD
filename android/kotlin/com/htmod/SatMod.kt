/*
 * HTMOD Android : satellites (portage de SatPanel.java) : catalogue (Benshi, SatNOGS, CelesTrak,
 * satellites perso), filtres, positions, passages, configurations radio corrigees du Doppler,
 * poursuite Doppler par le poste.
 */
package com.htmod

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SatMod(private val host: Engine, outDir: File) {
    val catalog = SatCatalog(File(outDir, "satellites"))
    @Volatile var filter = F_ALL
        set(v) { field = v; refilter() }
    @Volatile var minEl = Prefs.getInt("satMinEl", 0)
        private set
    @Volatile var search = ""
        set(v) { field = v; refilter() }
    @Volatile var status = " "
        private set
    @Volatile var obsLat = Prefs.get("obsLat", "48.8566")
        private set
    @Volatile var obsLon = Prefs.get("obsLon", "2.3522")
        private set
    @Volatile var observer: SatTrack.Observer? = null
        private set
    @Volatile var shown: List<SatCatalog.Sat> = emptyList()
        private set
    val looks = ConcurrentHashMap<Int, SatTrack.Look>()
    private val nextPass = ConcurrentHashMap<Int, SatTrack.Pass>()
    @Volatile var selected: SatCatalog.Sat? = null
        private set
    @Volatile var rfIndex = 0
        private set
    @Volatile var track: List<DoubleArray> = emptyList()
        private set
    @Volatile var trackAt = 0L
        private set
    @Volatile var tracking = false
        private set
    @Volatile var passes = ""
        private set
    private val hm = SimpleDateFormat("HH:mm", Locale.ROOT)
    private val dhm = SimpleDateFormat("dd/MM HH:mm", Locale.ROOT)
    @Volatile private var stopped = false

    init {
        setObserver(obsLat, obsLon)
        Thread({
            catalog.loadLocal()
            tick()
            val m = catalog.refresh()
            host.log(tr("Satellites : ") + m)
            status = m
            computePasses()
        }, "htmod-satellites").start()
        Thread({
            var lastPasses = System.currentTimeMillis()
            while (!stopped) {
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@Thread }
                try {
                    tick()
                    if (System.currentTimeMillis() - lastPasses > 300_000) { lastPasses = System.currentTimeMillis(); computePasses() }
                } catch (e: RuntimeException) {}
            }
        }, "htmod-satellites-suivi").apply { isDaemon = true; start() }
    }

    fun stop() { stopped = true }

    fun setObserver(la: String, lo: String): String? {
        val a = la.trim().replace(',', '.').toDoubleOrNull()
        val o = lo.trim().replace(',', '.').toDoubleOrNull()
        if (a == null || o == null || Math.abs(a) > 90 || Math.abs(o) > 180) return tr("Position invalide (latitude -90..90, longitude -180..180).")
        observer = SatTrack.Observer(a, o, 0.05)
        obsLat = la.trim(); obsLon = lo.trim()
        Prefs.put("obsLat", obsLat); Prefs.put("obsLon", obsLon)
        Thread({ computePasses() }, "htmod-passages").start()
        selected?.let { computePassesFor(it) }
        return null
    }

    fun setMinEl(v: Int) {
        minEl = v
        Prefs.putInt("satMinEl", v)
        refilter()
        Thread({ computePasses() }, "htmod-passages").start()
        selected?.let { computePassesFor(it) }
    }

    @Synchronized private fun refilter() {
        val q = search.trim().lowercase(Locale.ROOT)
        val l = ArrayList<SatCatalog.Sat>()
        for (s in catalog.all()) {
            if (filter == F_SSTV && !catalog.isSstv(s)) continue
            val k = looks[s.norad]
            if (filter == F_VISIBLE) {
                if (k == null || k.elDeg <= 0 || k.elDeg < minEl) continue
            } else if (minEl > 0) {
                if (!(k != null && k.elDeg >= minEl) && !nextPass.containsKey(s.norad)) continue
            }
            if (q.isNotEmpty() && !s.name.lowercase(Locale.ROOT).contains(q) && !s.norad.toString().contains(q)) continue
            l.add(s)
        }
        shown = l
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val o = observer
        for (s in catalog.all()) {
            try { SatTrack.look(s.prop(), now, o)?.let { looks[s.norad] = it } } catch (e: RuntimeException) {}
        }
        refilter()
        if (selected != null) {
            if (now - trackAt > 30_000) updateTrack()
            if (tracking) doTrack()
        }
    }

    private fun updateTrack() {
        val s = selected ?: return
        val now = System.currentTimeMillis()
        track = try { SatTrack.groundTrack(s.prop(), now - 50 * 60_000L, now + 100 * 60_000L, 30_000) } catch (e: RuntimeException) { emptyList() }
        trackAt = now
    }

    private fun computePassesFor(s: SatCatalog.Sat) {
        val o = observer ?: return
        passes = tr("Calcul des passages…")
        Thread({
            val ps = try { SatTrack.passes(s.prop(), o, System.currentTimeMillis(), 24.0, minEl.toDouble()) } catch (e: RuntimeException) { emptyList<SatTrack.Pass>() }
            val sb = StringBuilder()
            for (p in ps) {
                synchronized(hm) {
                    sb.append(dhm.format(Date(p.aosMs))).append(" → ").append(hm.format(Date(p.losMs)))
                        .append(String.format(Locale.ROOT, "  max %2.0f°  az %3.0f→%3.0f", p.maxElDeg, p.aosAzDeg, p.losAzDeg)).append('\n')
                }
                if (sb.length > 1400) break
            }
            val txt = if (ps.isEmpty()) tr("Aucun passage au-dessus de l'horizon dans les 24 h.") else sb.toString().trim()
            if (selected === s) passes = txt
        }, "htmod-passages-sat").start()
    }

    private fun computePasses() {
        val o = observer ?: return
        val now = System.currentTimeMillis()
        val m = HashMap<Int, SatTrack.Pass>()
        for (s in catalog.all()) {
            try {
                val ps = SatTrack.passes(s.prop(), o, now, 24.0, minEl.toDouble())
                if (ps.isNotEmpty()) m[s.norad] = ps[0]
            } catch (e: RuntimeException) {}
        }
        nextPass.clear()
        nextPass.putAll(m)
        refilter()
    }

    fun next(s: SatCatalog.Sat): String {
        val l = looks[s.norad]
        if (l != null && l.elDeg > 0) return "visible"
        val p = nextPass[s.norad] ?: return ""
        return synchronized(hm) { hm.format(Date(p.aosMs)) + String.format(Locale.ROOT, " %2.0f°", p.maxElDeg) }
    }

    fun select(s: SatCatalog.Sat) {
        if (s === selected) return
        if (tracking) stopTracking()
        selected = s
        rfIndex = 0
        trackAt = 0
        updateTrack()
        computePassesFor(s)
    }

    fun setRf(i: Int) { val s = selected ?: return; rfIndex = i.coerceIn(0, Math.max(0, s.rf.size - 1)) }

    fun rf(): SatCatalog.Rf? { val s = selected ?: return null; return if (s.rf.isEmpty()) null else s.rf[Math.min(rfIndex, s.rf.size - 1)] }

    fun rfText(): String {
        val s = selected ?: return ""
        val l = looks[s.norad] ?: return tr("Position incalculable (TLE trop ancien ?)")
        val r = rf() ?: return tr("Aucune configuration radio connue (ajoutez-en une avec AJOUTER…).")
        val sb = StringBuilder(r.label()).append('\n')
        if (r.downHz <= 0) return sb.toString().trim()
        sb.append(tr("Réception : ")).append(SatCatalog.mhz(l.downlink(r.downHz))).append(" MHz")
            .append(String.format(Locale.ROOT, " (%+.1f kHz)", (l.downlink(r.downHz) - r.downHz) / 1000.0))
        if (r.upHz > 0) sb.append(tr("\nÉmission : ")).append(SatCatalog.mhz(l.uplink(r.upHz))).append(" MHz")
            .append(String.format(Locale.ROOT, " (%+.1f kHz)", (l.uplink(r.upHz) - r.upHz) / 1000.0))
        return sb.toString()
    }

    // ------------------------------------------------------------------ Poursuite Doppler

    fun toggleTracking(): String? {
        if (tracking) { stopTracking(); return null }
        val s = selected ?: return tr("Choisissez un satellite.")
        if (!host.radio.isRunning) return tr("Connectez d'abord le poste.")
        val rf = rf() ?: return tr("Ce satellite n'a pas de configuration radio.")
        if (rf.downHz <= 0) return tr("Pas de fréquence pour cette configuration : réglez-la avec MODIFIER.")
        host.stopSweep()
        tracking = true
        host.log(tr("Suivi Doppler de ") + s.name + " (" + rf.label() + ")")
        doTrack()
        return null
    }

    private fun doTrack() {
        val s = selected
        val r = host.radio
        if (s == null || !r.isRunning) { stopTracking(); return }
        val l = SatTrack.look(s.prop(), System.currentTimeMillis(), observer) ?: return
        val rf = rf()
        if (rf == null || rf.downHz <= 0) { stopTracking(); return }
        val rx = l.downlink(rf.downHz)
        val tx = if (rf.upHz > 0) l.uplink(rf.upHz) else rx
        val p = nextPass[s.norad]
        val secs = if (p == null) 0 else Math.max(0, (p.aosMs - System.currentTimeMillis()) / 1000).toInt()
        r.setSatelliteInfo(if (s.name.length > 20) s.name.substring(0, 20) else s.name, l.azDeg, l.elDeg, l.rangeKm, l.altKm, if (l.elDeg > 0) 0 else secs)
        r.tuneVfo(rx, tx, rf.downAm, rf.downTone, rf.upTone)
    }

    private fun stopTracking() {
        if (!tracking) return
        tracking = false
        if (host.radio.isRunning) host.radio.restoreAfterSweep()
        host.log(tr("Suivi Doppler arrêté"))
    }

    fun onDisconnected() { tracking = false }

    // ------------------------------------------------------------------ Frequences, ajout, suppression

    fun editRf(down: String, up: String, tone: String): String? {
        val s = selected ?: return tr("Choisissez un satellite.")
        val r = rf() ?: return tr("Choisissez un satellite avec une configuration radio.")
        val d = down.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: return tr("Valeur invalide.")
        val u = if (up.isBlank()) 0L else up.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: return tr("Valeur invalide.")
        val t = if (tone.isBlank()) 0 else tone.trim().replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 100).toInt() } ?: return tr("Valeur invalide.")
        if (d < 1_000_000L || d > 6_000_000_000L || u < 0 || u > 6_000_000_000L) return tr("Fréquence hors plage.")
        if (t != 0 && (t < 6000 || t > 26000)) return tr("CTCSS hors plage (60–260 Hz).")
        try { catalog.editRf(s, r, d, u, t) } catch (e: IOException) { return tr("Enregistrement impossible : ") + e.message }
        host.log(tr("Satellites : ") + s.name + " " + r.label())
        if (tracking) doTrack()
        return null
    }

    fun resetRf(): String? {
        val s = selected ?: return null
        val r = rf() ?: return null
        try { catalog.resetRf(s, r) } catch (e: IOException) { return tr("Enregistrement impossible : ") + e.message }
        host.log(tr("Satellites : ") + s.name + tr(" fréquences d'origine ") + r.label())
        if (tracking) doTrack()
        return null
    }

    fun setSstv(on: Boolean) { selected?.let { catalog.setSstv(it, on); refilter() } }

    fun refresh() {
        Thread({
            val m = catalog.refresh()
            host.log(tr("Satellites : ") + m)
            status = m
            computePasses()
        }, "htmod-satellites").start()
    }

    /** TLE par n° NORAD (CelesTrak) : {nom, ligne 1, ligne 2} ; a appeler hors du fil de l'interface. */
    fun fetchTle(norad: String): Array<String> = catalog.fetchTleByNorad(norad.trim().toInt())

    fun add(name: String, l1: String, l2: String, rfName: String, down: String, up: String, tone: String, sstv: Boolean): String? {
        return try {
            var rf: SatCatalog.Rf? = null
            if (down.isNotBlank()) {
                rf = SatCatalog.Rf()
                rf.name = rfName.trim().ifEmpty { "FM" }
                rf.downHz = Math.round(down.trim().replace(',', '.').toDouble() * 1e6)
                if (up.isNotBlank()) rf.upHz = Math.round(up.trim().replace(',', '.').toDouble() * 1e6)
                if (tone.isNotBlank()) rf.upTone = Math.round(tone.trim().replace(',', '.').toDouble() * 100).toInt()
            }
            val n = name.trim()
            catalog.addUser(n.ifEmpty { tr("Satellite perso") }, l1.trim(), l2.trim(), rf, sstv)
            host.log(tr("Satellite ajouté : ") + n)
            refilter()
            Thread({ computePasses() }, "htmod-passages").start()
            null
        } catch (e: Exception) {
            tr("Impossible d'ajouter : ") + e.message
        }
    }

    fun remove(): String? {
        val s = selected ?: return null
        if (s.source != SatCatalog.SRC_USER) return tr("Seuls les satellites ajoutés par vous peuvent être supprimés.")
        return try {
            if (tracking) stopTracking()
            catalog.removeUser(s.norad)
            selected = null
            refilter()
            null
        } catch (e: Exception) {
            tr("Suppression impossible : ") + e.message
        }
    }

    companion object {
        const val F_ALL = 0
        const val F_SSTV = 1
        const val F_VISIBLE = 2
        val MIN_ELS = intArrayOf(0, 10, 20, 30, 45)
    }
}
