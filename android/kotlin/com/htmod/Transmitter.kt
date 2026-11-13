/*
 * HTMOD Android : emission d'un signal audio (PCM 32 kHz) par le poste, comme la version Windows :
 * encodage SBC 32 kHz mono, trames « 7E 00 <SBC echappe> 7E » sur le canal audio cadencees en temps
 * reel, puis trame de fin. PTT : micro du telephone (AudioRecord 32 kHz).
 */
package com.htmod

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class Transmitter(private val radio: Radio) {
    interface Listener {
        fun onTxStart(seconds: Double)
        fun onTxEnd(cancelled: Boolean)
    }

    @Volatile var isBusy = false
        private set
    @Volatile private var cancel = false
    @Volatile private var liveStop = false
    @Volatile var micLevel = 0
        private set

    fun cancel() { cancel = true }

    @Synchronized fun transmit(pcm: ShortArray, data: Boolean, l: Listener): Boolean {
        if (isBusy || !radio.audioOpen()) return false
        isBusy = true
        cancel = false
        Thread({ run(pcm, data, l) }, "htmod-emission").apply { isDaemon = true; start() }
        return true
    }

    private fun run(pcm: ShortArray, data: Boolean, l: Listener) {
        l.onTxStart(pcm.size / RATE.toDouble())
        val enc = SbcEncoder(1, 16, 8, 40, if (data) Sbc.ALLOC_SNR else Sbc.ALLOC_LOUDNESS)
        val per = 16 * 8
        val t0 = System.currentTimeMillis()
        var sent = 0
        try {
            var pos = 0
            while (pos + per <= pcm.size && !cancel) {
                val sbc = ByteArrayOutputStream()
                val start = pos
                while (pos + per <= pcm.size && sbc.size() < 300 - 90) {
                    val f = enc.encode(pcm, pos)
                    sbc.write(f, 0, f.size)
                    pos += per
                }
                radio.sendAudio(frame(sbc.toByteArray()))
                sent += pos - start
                val ahead = sent * 1000L / RATE - LEAD_MS - (System.currentTimeMillis() - t0)
                if (ahead > 0) Thread.sleep(Math.min(ahead, 100))
            }
            var left = sent * 1000L / RATE - (System.currentTimeMillis() - t0)
            while (left > 0 && !cancel) {
                Thread.sleep(Math.min(left, 100))
                left = sent * 1000L / RATE - (System.currentTimeMillis() - t0)
            }
        } catch (e: InterruptedException) {
        } finally {
            radio.sendAudio(END_FRAME)
            isBusy = false
            l.onTxEnd(cancel)
        }
    }

    /** PTT : micro du telephone emis en continu jusqu'a stopLive() ou maxSeconds. */
    @SuppressLint("MissingPermission")
    @Synchronized fun startLive(gain: Double, maxSeconds: Int, l: Listener): Boolean {
        if (isBusy || !radio.audioOpen()) return false
        val mic: AudioRecord
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            mic = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(min, RATE / 5 * 2))
            if (mic.state != AudioRecord.STATE_INITIALIZED) { mic.release(); throw IllegalStateException(tr("micro indisponible (autorisation ?)")) }
        } catch (e: SecurityException) {
            throw IllegalStateException(tr("micro non autorisé"))
        }
        isBusy = true
        cancel = false
        liveStop = false
        Thread({ runLive(mic, gain, maxSeconds, l) }, "htmod-ptt").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        return true
    }

    fun stopLive() { liveStop = true }

    private fun runLive(mic: AudioRecord, gain: Double, maxSeconds: Int, l: Listener) {
        l.onTxStart(maxSeconds.toDouble())
        val enc = SbcEncoder(1, 16, 8, 40, Sbc.ALLOC_LOUDNESS)
        val per = 16 * 8
        val chunk = per * 3
        val raw = ShortArray(chunk)
        val pcm = ShortArray(chunk)
        val t0 = System.currentTimeMillis()
        var timeout = false
        try {
            mic.startRecording()
            while (!liveStop && !cancel) {
                if (System.currentTimeMillis() - t0 > maxSeconds * 1000L) { timeout = true; break }
                var got = 0
                while (got < chunk) {
                    val r = mic.read(raw, got, chunk - got)
                    if (r <= 0) break
                    got += r
                }
                var peak = 0
                for (i in 0 until chunk) {
                    val v = Math.max(-32767, Math.min(32767, Math.round(raw[i] * gain).toInt()))
                    pcm[i] = v.toShort()
                    peak = Math.max(peak, Math.abs(v))
                }
                micLevel = peak * 100 / 32767
                val sbc = ByteArrayOutputStream()
                var pos = 0
                while (pos + per <= chunk) {
                    val f = enc.encode(pcm, pos)
                    sbc.write(f, 0, f.size)
                    pos += per
                }
                radio.sendAudio(frame(sbc.toByteArray()))
            }
        } catch (e: Exception) {
        } finally {
            try { mic.stop() } catch (e: Exception) {}
            mic.release()
            micLevel = 0
            radio.sendAudio(END_FRAME)
            isBusy = false
            l.onTxEnd(timeout || cancel)
        }
    }

    companion object {
        private const val RATE = 32000
        private const val LEAD_MS = 1000
        private val END_FRAME = byteArrayOf(0x7e, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7e)

        fun frame(sbc: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(sbc.size + 8)
            out.write(0x7e)
            out.write(0x00)
            for (b in sbc) {
                val v = b.toInt() and 0xFF
                if (v == 0x7d || v == 0x7e) { out.write(0x7d); out.write(v xor 0x20) } else out.write(v)
            }
            out.write(0x7e)
            return out.toByteArray()
        }
    }
}
