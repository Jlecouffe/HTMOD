/*
 * HTMOD Android : bandscope par balayage (portage de Bandscope.java). Le poste est accorde pas a pas en
 * mode frequence (rien n'est ecrit en memoire) et son S-metre releve a chaque pas.
 */
package com.htmod

import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BandscopeMod(private val host: Engine) {
    private val status = LinkedBlockingQueue<IntArray>()
    @Volatile var halfSpanHz = Prefs.getInt("bsHalfSpan", 500_000)
        private set
    @Volatile var stepHz = Prefs.getInt("bsStep", 12_500)
        private set
    @Volatile var running = false
        private set
    @Volatile var centerHz = 0L
        private set
    @Volatile var parkedHz = 0L
        private set
    @Volatile var levels = IntArray(0)
        private set
    @Volatile var peaks = IntArray(0)
        private set
    @Volatile var squelch = BooleanArray(0)
        private set
    @Volatile var cursor = -1
        private set
    @Volatile var sweeps = 0
        private set
    @Volatile private var sweepMs = 0L
    private val history = arrayOfNulls<IntArray>(HISTORY)
    private var histHead = 0
    private var worker: Thread? = null

    fun onHtStatus(rssi: Int, sq: Boolean) { status.offer(intArrayOf(rssi, if (sq) 1 else 0)) }

    fun refreshCenter() {
        if (running || parkedHz != 0L) return
        val c = host.radio.currentRxHz()
        if (c > 0) centerHz = c
    }

    /** Balayages precedents, le plus recent en premier. */
    fun history(): List<IntArray> = synchronized(history) {
        val out = ArrayList<IntArray>()
        for (k in 0 until HISTORY) {
            val hl = history[(histHead - 1 - k + HISTORY * 2) % HISTORY] ?: break
            out.add(hl)
        }
        out
    }

    fun info(): String {
        val n = 2 * halfSpanHz / stepHz + 1
        if (running) return if (sweeps == 0) tr("Balayage en cours…") else tr("Balayage n° ") + sweeps + " · " + n + tr(" pas en ") +
            String.format(Locale.ROOT, "%.1f", sweepMs / 1000.0) + tr(" s · décodages SARSAT/sondes en pause")
        return if (parkedHz != 0L) tr("Calé sur ") + Engine.mhz(parkedHz) + tr(" MHz (mode fréquence) · ") + n + tr(" pas par balayage")
        else tr("Arrêté · ") + n + tr(" pas, ~") + Math.round(n * 0.19) + tr(" s par balayage")
    }

    @Synchronized fun start(): String? {
        val r = host.radio
        if (!r.isRunning) return tr("Bandscope : connectez d'abord le poste.")
        val c = if (parkedHz != 0L) centerHz else r.currentRxHz()
        if (c <= 0) return tr("Bandscope : fréquence du canal encore inconnue (lecture des canaux en cours).")
        centerHz = c
        parkedHz = 0
        val n = 2 * halfSpanHz / stepHz + 1
        levels = IntArray(n) { -1 }
        peaks = IntArray(n)
        squelch = BooleanArray(n)
        synchronized(history) { history.fill(null); histHead = 0 }
        sweeps = 0
        running = true
        host.sweeping = true
        host.log(tr("Bandscope : balayage de ") + Engine.mhz(c - halfSpanHz) + tr(" à ") + Engine.mhz(c + halfSpanHz) + tr(" MHz, pas ") + stepHz + " Hz")
        val half = halfSpanHz
        val step = stepHz
        worker = Thread({ loop(c, half, step, n) }, "htmod-bandscope").apply { isDaemon = true; start() }
        return null
    }

    private fun loop(center: Long, half: Int, step: Int, n: Int) {
        val r = host.radio
        try {
            while (running && r.isRunning) {
                val t0 = System.currentTimeMillis()
                val lv = levels
                var i = 0
                while (i < n && running && r.isRunning) {
                    val f = center - half + i.toLong() * step
                    status.clear()
                    r.tuneVfo(f, center, false)
                    Thread.sleep(SETTLE_MS)
                    status.clear()
                    r.requestHtStatus()
                    val s = status.poll(STATUS_WAIT_MS, TimeUnit.MILLISECONDS)
                    lv[i] = s?.get(0) ?: 0
                    squelch[i] = s != null && s[1] == 1
                    val pk = peaks
                    pk[i] = Math.max(lv[i], pk[i])
                    cursor = i
                    i++
                }
                if (!running) break
                sweepMs = System.currentTimeMillis() - t0
                sweeps++
                synchronized(history) { history[histHead] = lv.clone(); histHead = (histHead + 1) % HISTORY }
                val pk = peaks
                if (sweeps % 3 == 0) for (k in pk.indices) pk[k] = Math.max(lv[k], pk[k] - 1)
            }
        } catch (e: InterruptedException) {
        } finally {
            cursor = -1
        }
    }

    @Synchronized fun stop(backToChannel: Boolean) {
        if (!running) return
        running = false
        worker?.interrupt()
        if (backToChannel && host.radio.isRunning) host.radio.restoreAfterSweep()
        host.sweeping = false
        host.log(tr("Bandscope : arrêté") + if (backToChannel) tr(", retour au canal") else "")
    }

    fun onDisconnected() { running = false; parkedHz = 0; host.sweeping = false }

    /** Ecoute d'une frequence du balayage (le poste reste en mode frequence). */
    fun park(f: Long) {
        val r = host.radio
        if (!r.isRunning || centerHz <= 0) return
        val was = running
        running = false
        val w = worker
        w?.interrupt()
        try { w?.join(800) } catch (e: InterruptedException) {}
        if (was) host.sweeping = false
        r.tuneVfo(f, centerHz, false)
        parkedHz = f
        host.log(tr("Bandscope : écoute de ") + Engine.mhz(f) + tr(" MHz (RETOUR AU CANAL pour revenir)"))
    }

    fun cancelForManualTune() { if (running) stop(false); parkedHz = 0 }

    fun backToChannel() {
        parkedHz = 0
        if (host.radio.isRunning) host.radio.restoreAfterSweep()
        host.log(tr("Bandscope : retour au canal"))
        refreshCenter()
    }

    fun setSpan(v: Int) { halfSpanHz = v; Prefs.putInt("bsHalfSpan", v); restartIfRunning() }
    fun setStep(v: Int) { stepHz = v; Prefs.putInt("bsStep", v); restartIfRunning() }
    private fun restartIfRunning() { if (running) { stop(false); start() } }

    fun freqAt(i: Int): Long = centerHz - halfSpanHz + i.toLong() * stepHz

    companion object {
        private const val SETTLE_MS = 140L
        private const val STATUS_WAIT_MS = 500L
        private const val HISTORY = 120
        val SPANS = intArrayOf(100_000, 250_000, 500_000, 1_000_000)
        val STEPS = intArrayOf(6_250, 8_333, 12_500, 25_000)

        fun sUnits(level: Int) = if (level <= 0) "S0" else if (level <= 9) "S$level" else "S9+" + (level - 9) * 10

        /** Palette du S-metre (Radtel Tools) : bleu nuit -> cyan -> vert -> jaune -> rouge. */
        fun levelColor(level: Int): Int {
            val p = level.coerceIn(0, 15) / 15f
            return when {
                p <= 0.01f -> 0xFF0A0A2A.toInt()
                p < 0.33f -> lerp(0x0A0A6E, 0x00C8C8, p / 0.33f)
                p < 0.66f -> lerp(0x00C8C8, 0x3DDC84, (p - 0.33f) / 0.33f)
                p < 0.9f -> lerp(0x3DDC84, 0xFFC107, (p - 0.66f) / 0.24f)
                else -> lerp(0xFFC107, 0xFF3B30, (p - 0.9f) / 0.1f)
            }
        }

        private fun lerp(a: Int, b: Int, t0: Float): Int {
            val t = t0.coerceIn(0f, 1f)
            val r = (((a shr 16) and 255) + (((b shr 16) and 255) - ((a shr 16) and 255)) * t).toInt()
            val g = (((a shr 8) and 255) + (((b shr 8) and 255) - ((a shr 8) and 255)) * t).toInt()
            val bl = ((a and 255) + ((b and 255) - (a and 255)) * t).toInt()
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}
