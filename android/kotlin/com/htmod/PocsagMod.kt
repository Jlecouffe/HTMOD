/*
 * HTMOD Android : POCSAG (portage de PocsagPanel.java).
 *  - Reception : decodage permanent de l'audio du poste (512 / 1200 / 2400 bauds, detecteur du poste
 *    PosteDetector + NRZ + AFSK du coeur partage), alerte a chaque message, decodages.csv ; messages
 *    des dernieres 24 h relus au demarrage, suppression memorisee.
 *  - Emission : RIC, fonction, debit, alpha / numerique, niveau, pre-compensation, polarite, AFSK ;
 *    bandes radioamateur seulement.
 */
package com.htmod

import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class PocsagMsg(val time: String, val ric: String, val func: String, val rate: String, val type: String, val text: String, val sec: Long) {
    val id get() = "$sec|$ric"
}

class PocsagMod(private val host: Engine) {
    private val rx = ArrayBlockingQueue<ShortArray>(256)
    @Volatile private var decoder: Pocsag? = null
    private val rows = ArrayList<PocsagMsg>()
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val deleted = LinkedHashSet<String>()

    // reglages d'emission (memes cles que Windows)
    var ric = Prefs.get("pocsagRic", "")
    var msg = Prefs.get("pocsagMsg", "")
    var func = Prefs.getInt("pocsagFunc", 3)
    var baudIdx = Prefs.getInt("pocsagBaudIdx", 1)
    var numeric = Prefs.getBoolean("pocsagNumeric", false)
    var levelIdx = Prefs.getInt("pocsagLevelIdx", 2)
    var precomp = Prefs.getBoolean("pocsagPre", false)
    var invert = Prefs.getBoolean("pocsagInv", false)
    var afsk = Prefs.getBoolean("pocsagAfsk", false)

    @Volatile var sending = false
        private set
    @Volatile private var txText = " "
    @Volatile private var txColor = Col.MUTED
    @Volatile private var txUntil = 0L
    @Volatile var version = 0L
        private set


    fun messages(): List<PocsagMsg> = synchronized(rows) { ArrayList(rows) }

    // ------------------------------------------------------------------ Suppression

    private fun isDeleted(sec: Long, ric: String) =
        deleted.contains("$sec|$ric") || deleted.contains("${sec - 1}|$ric") || deleted.contains("${sec + 1}|$ric")

    @Synchronized fun delete(id: String) {
        synchronized(rows) { rows.removeAll { it.id == id } }
        deleted.add(id)
        while (deleted.size > 1000) deleted.remove(deleted.first())
        Prefs.put("pocsagDeleted", deleted.joinToString(","))
        version++
    }

    /** Messages des dernieres 24 h relus dans decodages.csv : la liste ne repart pas de zero au redemarrage. */
    private fun restore(f: File) {
        if (!f.isFile) return
        try {
            RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val from = Math.max(0, len - 2_000_000)
                raf.seek(from)
                val b = ByteArray((len - from).toInt())
                raf.readFully(b)
                val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val limit = System.currentTimeMillis() - 24 * 3600_000L
                val got = ArrayList<PocsagMsg>()
                for (l in String(b, Charsets.UTF_8).split(Regex("\r?\n"))) {
                    val c = l.split(";", limit = 12)
                    if (c.size < 12 || c[1] != "POCSAG") continue
                    val t = try { utc.parse(c[0])!!.time } catch (e: Exception) { continue }
                    if (t < limit) continue
                    val d = c[11].split(" / ", limit = 4) // debit / Fn / type / texte
                    if (d.size < 4 || isDeleted(t / 1000, c[2])) continue
                    got.add(0, PocsagMsg(tf.format(Date(t)), c[2], d[1], d[0], d[2], d[3], t / 1000))
                }
                synchronized(rows) { rows.addAll(got.take(2000)) }
                if (got.isNotEmpty()) host.log("POCSAG : " + got.size + tr(" message(s) des dernières 24 h rechargé(s) depuis decodages.csv"))
            }
        } catch (e: Exception) {
            host.log(tr("POCSAG : relecture de decodages.csv impossible (") + e.message + ")")
        }
    }

    // ------------------------------------------------------------------ Reception

    private var burst = ShortArray(Decoders.SAMPLE_RATE * 4)
    private var burstN = 0
    private var burstTotal = 0L
    private var burstSq = 0.0
    private var burstPeak = 0
    private var burstHz = 0L

    private fun burstAdd(b: ShortArray) {
        if (burstTotal == 0L) {
            val r = host.radio
            burstHz = if (r.isRunning) r.currentRxHz() else 0
            burstChannel = host.channelName
        }
        burstTotal += b.size
        for (s in b) { burstSq += s * s.toDouble(); burstPeak = Math.max(burstPeak, Math.abs(s.toInt())) }
        if (burstN + b.size <= BURST_MAX) {
            if (burstN + b.size > burst.size) burst = burst.copyOf(Math.min(BURST_MAX, Math.max(burst.size * 2, burstN + b.size)))
            System.arraycopy(b, 0, burst, burstN, b.size)
            burstN += b.size
        }
    }

    private var burstChannel = ""

    /**
     * Fin de reception : ligne de diagnostic seulement sur un canal POCSAG ou si une synchro POCSAG a ete vue
     * (sinon chaque ouverture du squelch sur le canal APRS ou ailleurs remplirait le journal).
     */
    private fun burstEnd(sync: String) {
        val secs = burstTotal / Decoders.SAMPLE_RATE.toDouble()
        if (secs >= 0.3 && (sync.isNotEmpty() || burstChannel.lowercase(Locale.ROOT).contains("pocsag"))) {
            val rms = Math.sqrt(burstSq / burstTotal)
            host.log(String.format(Locale.ROOT, tr("POCSAG : réception %.1f s"), secs) +
                (if (burstHz > 0) " · " + Engine.mhz(burstHz) + " MHz" else "") +
                String.format(Locale.ROOT, tr(" · niveau %.0f dBFS (crête %.0f dBFS)"), 20 * Math.log10(Math.max(1.0, rms) / 32768.0),
                    20 * Math.log10(Math.max(1, burstPeak) / 32768.0)) +
                tr(" · synchro : ") + (if (sync.isEmpty()) tr("aucune") else sync))
            if (secs >= 0.5) host.saveWav("poste", burst, burstN)
        }
        burstN = 0; burstTotal = 0; burstSq = 0.0; burstPeak = 0
    }

    private fun startDecoder() {
        val d = Pocsag(Decoders.SAMPLE_RATE) { onMessage(it) }
        decoder = d
        Thread({
            while (true) {
                try {
                    val b = rx.poll(600, TimeUnit.MILLISECONDS)
                    if (b == null) {
                        d.flush()
                        if (burstTotal > 0) burstEnd(d.takeSyncSummary())
                        continue
                    }
                    burstAdd(b)
                    d.process(b, 0, b.size)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    host.log(tr("POCSAG : ") + t)
                }
            }
        }, "htmod-pocsag").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
    }

    fun feed(pcm: ShortArray, len: Int) {
        if (!rx.offer(pcm.copyOf(len))) rx.poll()
    }

    fun decodeFile(pcm: ShortArray, rate: Int) {
        Thread({
            var n = 0
            val d = Pocsag(rate) { n++; onMessage(it) }
            d.process(pcm, 0, pcm.size)
            d.flush()
            host.log(tr("Fichier : ") + n + tr(" message(s) POCSAG"))
        }, "htmod-pocsag-fichier").start()
    }

    private fun onMessage(m: Pocsag.Message) {
        val type = if (m.toneOnly) "bip" else if (m.isNumeric) "num" else "alpha"
        val body = if (m.toneOnly) tr("(bip seul)") else m.text()
        val rate = m.baud.toString() + " bd" + if (m.afsk) " AFSK" else ""
        host.log("POCSAG RIC " + m.address + " F" + m.function + " " + rate + " " + type + " [" + m.demod + (if (m.votes > 1) " ×" + m.votes else "") + "] : " + body.replace('\n', ' '))
        host.csv("POCSAG", m.address.toString(), rate + " / F" + m.function + " / " + type + " / " + body.replace('\n', ' '))
        val now = System.currentTimeMillis()
        val p: PocsagMsg
        synchronized(rows) {
            p = PocsagMsg(tf.format(Date(now)), m.address.toString(), "F" + m.function, rate, type + if (m.badCodewords > 0) " ?" else "",
                body.replace('\n', ' '), now / 1000)
            rows.add(0, p)
            if (rows.size > 2000) rows.removeAt(rows.size - 1)
        }
        version++
        host.ui.pocsag(p)
    }

    /** Le decodeur suit une trame (synchro en cours) : sert a couper le son des trames. */
    val synced: Boolean get() = decoder?.syncedBauds()?.isNotEmpty() == true

    fun syncText(): String {
        val d = decoder
        val s = d?.syncedBauds() ?: emptyList<String>()
        return if (host.sweeping) tr("Réception en pause (balayage bandscope)")
        else if (s.isEmpty()) tr("En écoute… (aucune synchro)") else tr("Synchronisé : ") + s + tr(" bauds")
    }

    // ------------------------------------------------------------------ Emission

    /** Etat d'emission : texte et couleur. */
    fun txState(): Pair<String, Int> {
        if (sending || System.currentTimeMillis() < txUntil) return Pair(txText, txColor)
        val r = host.radio
        val hz = if (r.isRunning) r.currentTxHz() else 0
        val t = if (!r.isRunning) tr("Poste non connecté")
        else tr("Fréquence d'émission : ") + if (hz > 0) Engine.mhz(hz) + " MHz" + (if (amateur(hz)) "" else tr("  (hors bande radioamateur)")) else tr("inconnue")
        return Pair(t, if (hz > 0 && !amateur(hz)) Col.RED else Col.MUTED)
    }

    private fun status(s: String, c: Int, ms: Long) { txText = s; txColor = c; txUntil = System.currentTimeMillis() + ms }

    fun stop() = host.transmitter.cancel()

    /** Verifie et prepare l'emission ; renvoie {erreur, null} ou {null, description pour la confirmation}. */
    fun check(): Pair<String?, String?> {
        val r = host.radio
        if (!r.isRunning || !r.audioOpen()) return Pair(tr("Connectez d'abord le poste (canal audio ouvert)."), null)
        if (host.sweeping) return Pair(tr("Arrêtez d'abord le balayage du bandscope."), null)
        val address = ric.trim().toIntOrNull()
        if (address == null || address < 0 || address > 2097151) return Pair(tr("RIC invalide (0 à 2097151)."), null)
        try { if (msg.isNotEmpty()) { if (numeric) Pocsag.numericPayload(msg) else Pocsag.alphaPayload(msg) } } catch (e: IllegalArgumentException) { return Pair(e.message, null) }
        if (afsk && Pocsag.BAUDS[baudIdx] == 2400) return Pair(tr("AFSK : choisissez 512 ou 1200 bauds."), null)
        val hz = r.currentTxHz()
        if (hz <= 0) return Pair(tr("Fréquence d'émission inconnue (lecture des canaux en cours, ou émission interdite sur ce canal)."), null)
        if (!amateur(hz)) return Pair(tr("Émission refusée : ") + Engine.mhz(hz) + tr(" MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz)."), null)
        return Pair(null, "RIC " + address + tr(" · fonction ") + func + " · " + Pocsag.BAUDS[baudIdx] + tr(" bauds") + (if (afsk) " · AFSK" else "") +
            "\n" + tr("Fréquence : ") + Engine.mhz(hz) + " MHz")
    }

    /** Emission (apres confirmation) ; renvoie un message d'erreur ou null. */
    fun send(): String? {
        val (err, _) = check()
        if (err != null) return err
        val r = host.radio
        val t = host.transmitter
        val address = ric.trim().toInt()
        val payload = if (msg.isEmpty()) ArrayList<Int>() else if (numeric) Pocsag.numericPayload(msg) else Pocsag.alphaPayload(msg)
        Prefs.put("pocsagRic", ric.trim()); Prefs.put("pocsagMsg", msg); Prefs.putInt("pocsagFunc", func)
        Prefs.putInt("pocsagBaudIdx", baudIdx); Prefs.putBoolean("pocsagNumeric", numeric); Prefs.putInt("pocsagLevelIdx", levelIdx)
        Prefs.putBoolean("pocsagPre", precomp); Prefs.putBoolean("pocsagInv", invert); Prefs.putBoolean("pocsagAfsk", afsk)
        val hz = r.currentTxHz()
        val baud = Pocsag.BAUDS[baudIdx]
        val bits = Pocsag.bitstream(Pocsag.codewords(address, func, payload))
        val pcm = if (afsk) Pocsag.waveformAfsk(bits, baud, Decoders.SAMPLE_RATE, LEVELS[levelIdx] / 100.0, invert)
        else Pocsag.waveform(bits, baud, Decoders.SAMPLE_RATE, LEVELS[levelIdx] / 100.0, invert, precomp)
        val ok = t.transmit(pcm, true, object : Transmitter.Listener {
            override fun onTxStart(seconds: Double) {
                sending = true
                status(tr("Émission en cours sur ") + Engine.mhz(hz) + " MHz (" + String.format(Locale.ROOT, "%.1f", seconds) + " s)…", Col.AMBER, 3_600_000)
                host.log(tr("POCSAG : émission RIC ") + address + tr(" sur ") + Engine.mhz(hz) + " MHz, " + baud + " bd" + if (afsk) " AFSK" else "")
            }

            override fun onTxEnd(cancelled: Boolean) {
                sending = false
                status(if (cancelled) tr("Émission interrompue.") else tr("Message émis."), if (cancelled) Col.RED else Col.GREEN, 8000)
                host.log(tr("POCSAG : ") + if (cancelled) tr("émission interrompue") else tr("message émis"))
            }
        })
        return if (ok) null else tr("Émission impossible : une émission est déjà en cours ou le canal audio est fermé.")
    }

    // demarrage en dernier : toutes les proprietes ci-dessus sont initialisees
    init {
        for (k in Prefs.get("pocsagDeleted", "").split(",")) if (k.isNotEmpty()) deleted.add(k)
        restore(File(host.outDir, "decodages.csv"))
        startDecoder()
    }

    companion object {
        val LEVELS = intArrayOf(40, 60, 80, 100)
        private const val BURST_MAX = Decoders.SAMPLE_RATE * 60

        fun amateur(hz: Long) = (hz in 144_000_000..146_000_000) || (hz in 430_000_000..440_000_000)
    }
}
