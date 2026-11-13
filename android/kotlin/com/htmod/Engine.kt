/*
 * HTMOD Android : moteur de l'appli (equivalent de App.java sans interface) : liaison Bluetooth avec le
 * poste et reconnexion automatique, decodeurs (SARSAT, radiosondes, APRS, POCSAG, SSTV), carte,
 * journal, haut-parleur du telephone, VFO / bandscope / emission / satellites.
 * Vit dans le service de premier plan (HtService) : continue en arriere-plan, ecran eteint.
 */
package com.htmod

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission")
class Engine(ctx: Context, val ui: Ui) : Radio.Listener, Decoders.Listener {
    interface Ui {
        fun notify(title: String, text: String, alert: Boolean)
        fun status(text: String)
        fun pocsag(m: PocsagMsg)
        fun askMic()
    }

    class AprsRow(val time: Long, val name: String, val via: String, val type: String, val pos: String, val info: String,
                  val tnc2: String, val origin: String, val mapKey: String?)

    private val ctx: Context = ctx.applicationContext
    val outDir: File
    val radio: Radio
    private val decoders: Decoders
    private val afsk: Afsk1200
    val transmitter: Transmitter
    val map: MapStore
    val spectrum = Spectrum()
    private val aprsMute = AprsMute(Decoders.SAMPLE_RATE)
    private val pocsagMute = PocsagMute(Decoders.SAMPLE_RATE)
    val pocsag: PocsagMod
    val vfo: VfoMod
    val bandscope: BandscopeMod
    val aprsTx: AprsTxMod
    val sstv: SstvMod
    val sats: SatMod
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "htmod-minuterie").apply { isDaemon = true } }

    @Volatile var devices: List<Radio.Device> = emptyList()
        private set
    @Volatile var device: Radio.Device? = null
        private set
    @Volatile var channelName = ""
        private set
    @Volatile var receiving = false
        private set
    @Volatile var sweeping = false
    @Volatile var status = ""
        private set
    @Volatile var connected = false
        private set
    @Volatile private var userDisconnect = true
    @Volatile private var reconnect: ScheduledFuture<*>? = null
    private val aprsSeen = HashMap<String, Long>()

    private val btState = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> { log(tr("Bluetooth du téléphone activé")); refreshDevices(true); autoConnect() }
                BluetoothAdapter.STATE_OFF -> log(tr("Bluetooth du téléphone désactivé"))
            }
        }
    }


    // ------------------------------------------------------------------ Outils des onglets

    fun stopSweep() = bandscope.cancelForManualTune()

    fun hasMic() = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun csv(type: String, id: String, details: String) = decoders.logExternal(type, id, details)

    fun saveWav(prefix: String, pcm: ShortArray, n: Int) = decoders.saveWavExternal(prefix, pcm, n)

    var forcedMode: Int
        get() = decoders.forcedMode
        set(m) { decoders.forcedMode = m; Prefs.putInt("forcedMode", m) }

    var saveWavOn: Boolean
        get() = decoders.saveWav
        set(v) { decoders.saveWav = v; Prefs.putBoolean("saveWav", v) }

    /** Mode de decodage affiche : texte et couleur. */
    fun mode(): Pair<String, Int> {
        val f = decoders.forcedMode
        val m = decoders.effectiveMode(channelName)
        return when {
            sweeping -> Pair(tr("BALAYAGE BANDSCOPE · DÉCODAGES EN PAUSE"), Col.AMBER)
            m == Decoders.MODE_NONE -> Pair(tr("APRS ET POCSAG · SARSAT/SONDES EN ATTENTE"), Col.CYAN)
            m == Decoders.MODE_SARSAT -> Pair(tr("DÉCODAGE SARSAT") + if (f != 0) tr(" (FORCÉ)") else "", Col.RED)
            else -> Pair(tr("DÉCODAGE RADIOSONDE") + if (f != 0) tr(" (FORCÉ)") else "", Col.GREEN)
        }
    }

    // ------------------------------------------------------------------ Journal

    private val logLines = ArrayDeque<String>()
    private val logTime = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    @Volatile var logVersion = 0L
        private set

    override fun onLog(text: String) = log(text)

    fun log(s: String) {
        val line = synchronized(logTime) { logTime.format(Date()) + "  " + s }
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 500) logLines.removeFirst()
        }
        logVersion++
        android.util.Log.i("HTMod", s)
    }

    fun logLines(): List<String> = synchronized(logLines) { ArrayList(logLines) }

    // ------------------------------------------------------------------ Appareils et liaison

    fun refreshDevices(verbose: Boolean) {
        val list = ArrayList<Radio.Device>()
        try {
            val ad = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
            if (ad == null) { log(tr("Pas de Bluetooth sur ce téléphone.")); return }
            if (!ad.isEnabled) log(tr("Bluetooth du téléphone désactivé : activez-le puis actualisez la liste."))
            for (d in ad.bondedDevices) list.add(Radio.Device(d.address, d.name ?: d.address))
        } catch (e: SecurityException) {
            log(tr("Autorisation Bluetooth refusée : autorisez « Appareils à proximité » pour HTMod."))
            return
        }
        list.sortBy { if (looksLikeRadio(it.name)) 0 else 1 }
        devices = list
        val last = Prefs.get("mac", "")
        var sel: Radio.Device? = null
        for (d in list) {
            if (d.mac == last) sel = d else if (sel == null && looksLikeRadio(d.name)) sel = d
        }
        if (sel != null) selectDevice(sel)
        if (list.isEmpty()) log(tr("Aucun appareil Bluetooth appairé : appairez le poste dans les réglages Bluetooth du téléphone."))
        else if (verbose) log(list.size.toString() + tr(" appareil(s) Bluetooth appairé(s)"))
    }

    fun selectDevice(d: Radio.Device) {
        device = d
        Prefs.put("mac", d.mac)
        updateBar()
    }

    val reconnecting get() = reconnect != null

    /** Connexion / deconnexion (touche du poste) ; renvoie un message d'erreur ou null. */
    fun toggleConnect(): String? {
        if (radio.isRunning || reconnect != null) {
            userDisconnect = true
            Prefs.putBoolean("wasConnected", false)
            stopReconnect()
            radio.disconnect()
            connected = false
            status = tr("Déconnecté")
            updateBar()
            return null
        }
        val d = device ?: return tr("Choisissez d'abord le poste.")
        userDisconnect = false
        Prefs.putBoolean("wasConnected", true)
        doConnect(d.mac)
        return null
    }

    /** Reprise apres redemarrage du service ou retour du Bluetooth : reconnexion si le poste etait connecte. */
    fun autoConnect() {
        if (Prefs.getBoolean("wasConnected", false) && device != null && !radio.isRunning && reconnect == null) {
            userDisconnect = false
            device?.let { doConnect(it.mac) }
        }
    }

    private fun doConnect(mac: String) {
        try { radio.connect(mac) } catch (e: Exception) { log(tr("Connexion impossible : ") + e.message) }
    }

    private fun stopReconnect() {
        val f = reconnect
        reconnect = null
        f?.cancel(false)
    }

    fun shutdown() {
        userDisconnect = true
        stopReconnect()
        if (transmitter.isBusy) {
            transmitter.cancel()
            try { Thread.sleep(300) } catch (e: InterruptedException) {}
        }
        radio.disconnect()
        closeSpeaker()
        timer.shutdownNow()
        sats.stop()
        try { ctx.unregisterReceiver(btState) } catch (e: Exception) {}
    }

    /** Notification permanente : poste, etat de la liaison et canal en reception. */
    private fun updateBar() {
        val t = if (radio.isRunning && connected) {
            tr("Connecté") + (device?.let { " · " + it.name } ?: "") + (if (radio.audioOpen()) "" else tr(" (sans audio)")) +
                (if (channelName.isEmpty()) "" else tr(" · canal ") + channelName) + tr(" · décodage actif")
        } else if (reconnect != null) {
            tr("Liaison perdue, nouvel essai…") + if (status.isEmpty()) "" else "\n" + status
        } else status
        ui.status(t)
    }

    // ------------------------------------------------------------------ Rappels Radio / Decoders

    override fun onStatus(text: String) {
        status = text
        if (text.startsWith(tr("Connecté")) || text.startsWith(tr("Commandes ouvertes"))) {
            stopReconnect()
            connected = true
        }
        updateBar()
    }

    override fun onChannel(name: String?, receiving: Boolean) {
        channelName = name ?: ""
        this.receiving = receiving
        bandscope.refreshCenter()
        updateBar()
    }

    override fun onAudio(pcm: ShortArray, len: Int) {
        spectrum.feed(pcm, 0, len)
        if (!sweeping) {
            decoders.feed(channelName, pcm, 0, len)
            pocsag.feed(pcm, len)
            sstv.feed(pcm, len)
        }
        if (Prefs.getBoolean("swAprs", true)) afsk.process(pcm, 0, len)
        if (Prefs.getBoolean("listen", false)) {
            // le decodage ci-dessus a deja recu l'audio intact : on ne coupe que le haut-parleur
            var out = pcm
            if (Prefs.getBoolean("muteAprs", false)) out = aprsMute.filter(out, len, AprsMute.aprsChannel(channelName, radio.currentRxHz()))
            if (Prefs.getBoolean("mutePocsag", false)) out = pocsagMute.filter(out, len, PocsagMute.pocsagChannel(channelName), pocsag.synced)
            play(out, len)
        }
    }

    override fun onAudioEnd() {}

    override fun onTncFrame(frame: ByteArray) = onAx25(frame, "TNC")

    override fun onHtStatus(rssi: Int, squelchOpen: Boolean) = bandscope.onHtStatus(rssi, squelchOpen)

    override fun onTncSent(ok: Boolean) = aprsTx.onSent(ok)

    override fun onDisconnected() {
        bandscope.onDisconnected()
        aprsTx.onDisconnected()
        sats.onDisconnected()
        channelName = ""
        receiving = false
        connected = false
        if (!userDisconnect && reconnect == null) {
            log(tr("Liaison perdue, nouvel essai toutes les 10 s (DÉCONNECTER pour arrêter)."))
            reconnect = timer.scheduleWithFixedDelay({
                if (!radio.isRunning && !userDisconnect) {
                    val mac = Prefs.get("mac", "")
                    if (mac.isNotEmpty() && bluetoothOn(ctx)) doConnect(mac)
                }
            }, 3, 10, TimeUnit.SECONDS)
        }
        updateBar()
    }

    // ------------------------------------------------------------------ Detections (SARSAT, sondes)

    private val detections = ArrayList<Decoders.Detection>()

    fun detections(): List<Decoders.Detection> = synchronized(detections) { ArrayList(detections) }

    fun mapKey(d: Decoders.Detection): String =
        if (d.kind == "SARSAT") "S:" + d.id else "R:" + if (!d.id.isNullOrEmpty()) d.id else d.kind

    override fun onDetection(d: Decoders.Detection) {
        synchronized(detections) { detections.add(0, d) }
        if (d.hasPos) {
            val sarsat = d.kind == "SARSAT"
            val title = if (sarsat) d.id else if (!d.id.isNullOrEmpty()) d.id.replace("IDa:", "") else d.kind
            map.put(mapKey(d), if (sarsat) MapStore.KIND_SARSAT else MapStore.KIND_SONDE, title ?: "", d.title ?: "", d.text ?: "", d.lat, d.lon, '/', 'O', System.currentTimeMillis())
        }
        ui.notify(d.title ?: "", d.text ?: "", d.alert)
    }

    // ------------------------------------------------------------------ APRS

    private val aprsRows = ArrayList<AprsRow>()
    private val utcFmt = utcFormat()

    fun aprsRows(): List<AprsRow> = synchronized(aprsRows) { ArrayList(aprsRows) }

    /** Trame emise par le poste (onglet APRS) : ajoutee au trafic. */
    fun sentAprs(frame: ByteArray) = onAx25(frame, tr("émis"))

    private fun onAx25(frame: ByteArray, origin: String) {
        val p = Ax25.parse(frame) ?: return
        if (!p.isUi) return
        val now = System.currentTimeMillis()
        val key = p.src + ">" + p.dst + ":" + p.info
        synchronized(aprsSeen) {
            val t = aprsSeen[key]
            if (t != null && now - t < 30_000 && origin != "fichier") return
            aprsSeen[key] = now
            if (aprsSeen.size > 2000) aprsSeen.entries.removeAll { now - it.value > 60_000 }
        }
        val r = Aprs.parse(p)
        logAprs(origin, r.tnc2)
        showReport(r, origin, now)
        val s = r.summary()
        log("APRS [" + origin + "] " + r.name + (if (r.name == r.source) "" else " (" + r.source + ")") + " · " + r.type +
            (if (r.hasPos) " · " + Aprs.fmtPos(r.lat, r.lon) else "") + if (s.isEmpty()) "" else " · $s")
        if (r.message != null && tr("Accusé") != r.type) ui.notify("APRS " + r.source + " → " + r.addressee, r.message, false)
    }

    private fun showReport(r: Aprs.Report, origin: String, time: Long) {
        val via = if (r.name == r.source) "" else r.source
        val row = AprsRow(time, r.name, via, r.type, if (r.hasPos) Aprs.fmtPos(r.lat, r.lon) else "", r.summary(), r.tnc2, origin,
            if (r.hasPos) "A:" + r.name else null)
        synchronized(aprsRows) {
            aprsRows.add(0, row)
            if (aprsRows.size > 3000) aprsRows.removeAt(aprsRows.size - 1)
        }
        if (r.hasPos && !r.killed) {
            val sub = r.symbolName() + (if (via.isEmpty()) "" else tr(" · par ") + via) + " · " + r.type
            val det = StringBuilder(Aprs.fmtPos(r.lat, r.lon))
            val s = r.summary()
            if (s.isNotEmpty()) det.append("\n").append(s)
            det.append("\n").append(r.tnc2)
            map.put("A:" + r.name, MapStore.KIND_APRS, r.name, sub, det.toString(), r.lat, r.lon, r.symTable, r.symCode, time)
        }
    }

    @Synchronized private fun logAprs(origin: String, tnc2: String) {
        try {
            OutputStreamWriter(FileOutputStream(File(outDir, "aprs.txt"), true), Charsets.UTF_8).use {
                it.write(synchronized(utcFmt) { utcFmt.format(Date()) } + " [" + origin + "] " + tnc2 + "\r\n")
            }
        } catch (e: IOException) {
            log(tr("Journal APRS : ") + e.message)
        }
    }

    /** Stations des dernieres 24 h relues dans aprs.txt au demarrage. */
    private fun restoreAprs() {
        val f = File(outDir, "aprs.txt")
        if (!f.isFile) return
        Thread({
            try {
                RandomAccessFile(f, "r").use { raf ->
                    val len = raf.length()
                    val from = Math.max(0, len - 3_000_000)
                    raf.seek(from)
                    val b = ByteArray((len - from).toInt())
                    raf.readFully(b)
                    val lines = String(b, Charsets.UTF_8).split(Regex("\r?\n"))
                    val limit = System.currentTimeMillis() - 24 * 3600_000L
                    val utc = utcFormat()
                    var n = 0
                    for (i in (if (from > 0) 1 else 0) until lines.size) {
                        val l = lines[i]
                        val lb = l.indexOf(" [")
                        val rb = l.indexOf("] ")
                        if (l.length < 22 || lb != 19 || rb < 0) continue
                        val t = try { utc.parse(l.substring(0, 19))!!.time } catch (e: Exception) { continue }
                        if (t < limit) continue
                        val origin = l.substring(lb + 2, rb)
                        val tnc2 = l.substring(rb + 2)
                        val gt = tnc2.indexOf('>')
                        val colon = tnc2.indexOf(':')
                        if (gt <= 0 || colon <= gt) continue
                        val path = tnc2.substring(gt + 1, colon)
                        val comma = path.indexOf(',')
                        val r = Aprs.parse(tnc2.substring(0, gt), if (comma < 0) path else path.substring(0, comma), tnc2.substring(colon + 1), 0)
                        r.tnc2 = tnc2
                        showReport(r, origin, t)
                        n++
                    }
                    if (n > 0) log("APRS : " + n + tr(" trame(s) des dernières 24 h rechargée(s) depuis aprs.txt"))
                }
            } catch (e: Exception) {
                log(tr("APRS : relecture de aprs.txt impossible (") + e.message + ")")
            }
        }, "htmod-aprs-restauration").start()
    }

    fun clearAll() {
        map.clear()
        synchronized(aprsRows) { aprsRows.clear() }
        synchronized(detections) { detections.clear() }
    }

    // ------------------------------------------------------------------ Haut-parleur du telephone

    private var speaker: AudioTrack? = null

    @Synchronized private fun play(pcm: ShortArray, len: Int) {
        try {
            var s = speaker
            if (s == null) {
                val min = AudioTrack.getMinBufferSize(Decoders.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                s = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(Decoders.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, Decoders.SAMPLE_RATE))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                s.play()
                speaker = s
            }
            s!!.write(pcm, 0, len, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: Exception) {
            log(tr("Sortie audio impossible : ") + e.message)
            Prefs.putBoolean("listen", false)
        }
    }

    @Synchronized fun closeSpeaker() {
        speaker?.let { try { it.stop() } catch (e: Exception) {}; it.release() }
        speaker = null
    }

    var listen: Boolean
        get() = Prefs.getBoolean("listen", false)
        set(v) { Prefs.putBoolean("listen", v); if (!v) closeSpeaker() }

    // ------------------------------------------------------------------ Fichiers WAV

    /** Decode un fichier WAV (SARSAT 1, sonde 2, APRS -1, POCSAG -2) ; renvoie un message d'erreur ou null. */
    fun decodeWav(f: File, mode: Int): String? {
        return try {
            val w = Wav.read(f)
            log(tr("Fichier ") + f.name + " : " + w.pcm.size + tr(" échantillons à ") + w.rate + tr(" Hz, décodage ") +
                (if (mode == -2) "POCSAG" else if (mode < 0) "APRS" else Decoders.modeName(mode)) + "…")
            when {
                mode == -2 -> pocsag.decodeFile(w.pcm, w.rate)
                mode < 0 -> Thread({
                    var n = 0
                    val dec = Afsk1200(w.rate) { fr -> n++; onAx25(fr, "fichier") }
                    dec.process(w.pcm, 0, w.pcm.size)
                    log(tr("Fichier : ") + n + tr(" trame(s) AX.25"))
                }, "htmod-aprs-file").start()
                else -> decoders.decodeFile(mode, w.pcm, w.rate)
            }
            null
        } catch (e: Exception) {
            tr("Lecture impossible : ") + e.message
        }
    }

    // demarrage en dernier : toutes les proprietes ci-dessus sont initialisees
    init {
        val base = ctx.getExternalFilesDir(null)
        outDir = File(base ?: ctx.filesDir, "HTMod")
        outDir.mkdirs()
        Radio.links = java.util.function.Function { mac -> BtLink(this.ctx, mac) }
        decoders = Decoders(outDir, this)
        decoders.forcedMode = Prefs.getInt("forcedMode", Decoders.MODE_NONE)
        decoders.saveWav = Prefs.getBoolean("saveWav", false)
        radio = Radio(null, this)
        transmitter = Transmitter(radio)
        afsk = Afsk1200(Decoders.SAMPLE_RATE) { f -> onAx25(f, "audio") }
        map = MapStore(File(outDir, "tiles")) { log(it) }
        pocsag = PocsagMod(this)
        vfo = VfoMod(this)
        bandscope = BandscopeMod(this)
        aprsTx = AprsTxMod(this)
        sstv = SstvMod(this, outDir)
        sats = SatMod(this, outDir)
        status = tr("Déconnecté")
        log("HTMod $VERSION Android" + tr(" – résultats dans ") + outDir.absolutePath)
        refreshDevices(false)
        restoreAprs()
        // test de vie : etat HT demande toutes les 20 s (une ecriture en echec ferme la liaison -> reconnexion)
        timer.scheduleWithFixedDelay({ if (radio.isRunning && !sweeping) radio.requestHtStatus() }, 20, 20, TimeUnit.SECONDS)
        this.ctx.registerReceiver(btState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    companion object {
        const val VERSION = "2.0"

        fun mhz(hz: Long): String = String.format(Locale.ROOT, "%.4f", hz / 1e6)

        fun bluetoothOn(c: Context): Boolean {
            val ad = (c.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
            return ad != null && ad.isEnabled
        }

        private fun looksLikeRadio(n: String?): Boolean {
            val s = (n ?: "").uppercase(Locale.ROOT)
            return listOf("UV-PRO", "UV-50", "VR-N", "GA-5", "RT-660", "DB50").any { s.contains(it) }
        }

        private fun utcFormat() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }
}
