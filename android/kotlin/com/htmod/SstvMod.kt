/*
 * HTMOD Android : SSTV (portage de SstvPanel.java) : reception permanente (VIS automatique), images PNG
 * dans <resultats>/sstv, galerie ; emission d'une image du telephone ou de la mire.
 */
package com.htmod

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class SstvMod(private val host: Engine, outDir: File) {
    val dir = File(outDir, "sstv")
    private val rx = ArrayBlockingQueue<ShortArray>(512)
    @Volatile private var decoder: Sstv.Decoder? = null
    @Volatile var rxImg: Img? = null
        private set
    @Volatile var txImg: Img? = null
        private set
    @Volatile private var source: Img? = null
    @Volatile var rxVersion = 0L
        private set
    @Volatile var txVersion = 0L
        private set
    @Volatile var rxName = " "
        private set
    var txMode: Sstv.Mode = Sstv.byName(Prefs.get("sstvMode", "Robot 36")) ?: Sstv.MODES[0]
        private set
    var levelIdx = Prefs.getInt("sstvLevelIdx", 2)
        set(v) { field = v; Prefs.putInt("sstvLevelIdx", v) }
    @Volatile var rxOn = Prefs.getBoolean("sstvRx", true)
        private set
    @Volatile var txInfo = " "
        private set
    @Volatile var txInfoColor = Col.MUTED
        private set
    @Volatile var sending = false
        private set
    val text get() = Prefs.get("sstvText", "")
    val custom get() = source != null

    private val sourceFile = File(outDir, "sstv-image-emission.png")

    init {
        if (Prefs.getBoolean("sstvCustom", false) && sourceFile.isFile) {
            try { source = Img.decode(sourceFile.readBytes()) } catch (e: Exception) {}
        }
        rebuildPreview()
        startDecoder()
    }

    private fun startDecoder() {
        val d = Sstv.Decoder(Decoders.SAMPLE_RATE, object : Sstv.Decoder.Listener {
            override fun onStart(m: Sstv.Mode) {
                host.log(tr("SSTV : réception ") + m.name + " (VIS " + m.vis + ")")
                rxImg = null
                rxVersion++
            }

            override fun onProgress(m: Sstv.Mode, img: Img, rowsDone: Int) { rxImg = img; rxVersion++ }

            override fun onDone(m: Sstv.Mode, img: Img, complete: Boolean) { received(m, img, complete) }
        })
        decoder = d
        Thread({
            while (true) {
                try {
                    val b = rx.poll(600, TimeUnit.MILLISECONDS)
                    if (b == null) { d.idle(); continue }
                    d.process(b, 0, b.size)
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    host.log(tr("SSTV : ") + t)
                }
            }
        }, "htmod-sstv").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
    }

    fun feed(pcm: ShortArray, len: Int) {
        if (!rxOn) return
        if (!rx.offer(pcm.copyOf(len))) rx.poll()
    }

    private fun received(m: Sstv.Mode, img: Img, complete: Boolean) {
        var rows = 0
        if (!complete) {
            rows = lastRows(img)
            if (rows < 16) { host.log(tr("SSTV : ") + m.name + tr(" interrompue trop tôt, image ignorée")); return }
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val f = File(dir, stamp + "_" + m.name.replace(" ", "") + (if (complete) "" else "_partiel") + ".png")
        try { dir.mkdirs(); f.writeBytes(img.png()) } catch (e: Exception) { host.log(tr("SSTV : enregistrement impossible : ") + e.message) }
        val what = m.name + if (complete) "" else tr(" (partielle, ") + rows + tr(" lignes)")
        host.log(tr("SSTV : image reçue ") + what + " → " + f.name)
        host.csv("SSTV", m.name, f.name + if (complete) "" else tr(" / partielle"))
        host.ui.notify(tr("Image SSTV reçue"), what, false)
        rxImg = img
        rxVersion++
        rxName = f.name
    }

    private fun lastRows(img: Img): Int {
        for (y in img.getHeight() - 1 downTo 0) for (x in 0 until img.getWidth()) if ((img.getRGB(x, y) and 0xFFFFFF) != 0) return y + 1
        return 0
    }

    fun state(): Pair<String, Int> {
        val d = decoder
        val m = d?.mode()
        return when {
            !rxOn -> Pair(tr("Réception coupée"), Col.MUTED)
            host.sweeping -> Pair(tr("Réception en pause (balayage bandscope)"), Col.MUTED)
            d != null && d.busy() && m != null -> Pair(tr("Réception ") + m.name + " · " + d.rowsDone() + " / " + m.height + tr(" lignes"), Col.AMBER)
            else -> Pair(tr("En écoute · détection VIS automatique"), Col.CYAN)
        }
    }

    fun gallery(): List<File> = (dir.listFiles { _, n -> n.lowercase(Locale.ROOT).endsWith(".png") } ?: arrayOf())
        .sortedByDescending { it.name }.take(80)

    fun setRx(on: Boolean) {
        rxOn = on
        Prefs.putBoolean("sstvRx", on)
        if (!on) { rx.clear(); decoder?.stop() }
    }

    fun finish() { decoder?.stop() }

    fun show(f: File) {
        try { Img.decode(f.readBytes())?.let { rxImg = it; rxVersion++; rxName = f.name } } catch (e: Exception) {}
    }

    fun setMode(m: Sstv.Mode) { txMode = m; Prefs.put("sstvMode", m.name); rebuildPreview() }
    fun setText(t: String) { Prefs.put("sstvText", t.trim()); rebuildPreview() }
    fun testCard() { source = null; Prefs.putBoolean("sstvCustom", false); rebuildPreview() }

    fun setImage(data: ByteArray): String? {
        val im = Img.decode(data) ?: return tr("Format d'image non reconnu (PNG, JPEG, GIF, BMP).")
        source = im
        try { sourceFile.parentFile?.mkdirs(); sourceFile.writeBytes(im.png()); Prefs.putBoolean("sstvCustom", true) } catch (e: Exception) {}
        rebuildPreview()
        return null
    }

    private fun txImage(): Img = source?.let { Sstv.prepare(it, txMode, text) } ?: Sstv.testCard(txMode, text)

    private fun rebuildPreview() {
        txImg = txImage()
        txVersion++
        if (!sending) {
            txInfo = txMode.name + " · " + txMode.width + "×" + txMode.height + " · " + String.format(Locale.ROOT, "%.0f s", txMode.seconds())
            txInfoColor = Col.MUTED
        }
    }

    fun txFreq(): Pair<String, Int> {
        val r = host.radio
        val hz = if (r.isRunning) r.currentTxHz() else 0
        val t = if (!r.isRunning) tr("Poste non connecté")
        else tr("Fréquence d'émission : ") + if (hz > 0) Engine.mhz(hz) + " MHz" + (if (PocsagMod.amateur(hz)) "" else tr("  (hors bande radioamateur)")) else tr("inconnue")
        return Pair(t, if (hz > 0 && !PocsagMod.amateur(hz)) Col.RED else Col.MUTED)
    }

    /** Verification avant emission : {erreur, null} ou {null, description pour la confirmation}. */
    fun check(): Pair<String?, String?> {
        val r = host.radio
        if (!r.isRunning || !r.audioOpen()) return Pair(tr("Connectez d'abord le poste (canal audio ouvert)."), null)
        if (host.sweeping) return Pair(tr("Arrêtez d'abord le balayage du bandscope."), null)
        val hz = r.currentTxHz()
        if (hz <= 0) return Pair(tr("Fréquence d'émission inconnue (lecture des canaux en cours, ou émission interdite sur ce canal)."), null)
        if (!PocsagMod.amateur(hz)) return Pair(tr("Émission refusée : ") + Engine.mhz(hz) + tr(" MHz n'est pas dans une bande radioamateur (144–146 ou 430–440 MHz)."), null)
        return Pair(null, txMode.name + " · " + String.format(Locale.ROOT, "%.0f", txMode.seconds()) + tr(" s d'émission continue") + "\n" + tr("Fréquence : ") + Engine.mhz(hz) + " MHz")
    }

    fun stop() = host.transmitter.cancel()

    fun send(): String? {
        val (err, _) = check()
        if (err != null) return err
        val t = host.transmitter
        val m = txMode
        val hz = host.radio.currentTxHz()
        val pcm = Sstv.encode(txImage(), m, Decoders.SAMPLE_RATE, PocsagMod.LEVELS[levelIdx] / 100.0)
        val secs = pcm.size / Decoders.SAMPLE_RATE.toDouble()
        val t0 = System.currentTimeMillis()
        val ok = t.transmit(pcm, true, object : Transmitter.Listener {
            override fun onTxStart(seconds: Double) {
                sending = true
                Thread({
                    while (sending) {
                        txInfo = tr("Émission ") + m.name + " : " + (System.currentTimeMillis() - t0) / 1000 + " / " + Math.round(secs) + " s"
                        txInfoColor = Col.AMBER
                        try { Thread.sleep(1000) } catch (e: InterruptedException) { return@Thread }
                    }
                }, "htmod-sstv-progression").apply { isDaemon = true; start() }
                host.log(tr("SSTV : émission ") + m.name + tr(" sur ") + Engine.mhz(hz) + " MHz (" + String.format(Locale.ROOT, "%.0f", seconds) + " s)")
            }

            override fun onTxEnd(cancelled: Boolean) {
                sending = false
                txInfo = if (cancelled) tr("Émission interrompue.") else tr("Image émise (") + m.name + ")."
                txInfoColor = if (cancelled) Col.RED else Col.GREEN
                host.log(tr("SSTV : ") + if (cancelled) tr("émission interrompue") else tr("image émise"))
            }
        })
        return if (ok) null else tr("Émission impossible : une émission est déjà en cours ou le canal audio est fermé.")
    }
}
