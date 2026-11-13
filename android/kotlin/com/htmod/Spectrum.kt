/*
 * HTMOD Android : mini-spectre de la carte RADIO (comme WaterfallPanel + SpectrumStrip de Windows) :
 * FFT de 1024 points (32 ms), 100 colonnes de 0 a 16 kHz, -90..-20 dBFS -> 0..1, lissage.
 */
package com.htmod

class Spectrum {
    private val ring = ShortArray(FFT * 8)
    private var head = 0
    private var tail = 0
    private var count = 0
    private val lock = Object()
    private val window = DoubleArray(FFT) { 0.5 - 0.5 * Math.cos(2 * Math.PI * it / (FFT - 1)) }
    private val re = DoubleArray(FFT)
    private val im = DoubleArray(FFT)
    private val v = FloatArray(COLS)
    private val col = FloatArray(COLS)
    @Volatile private var last = 0L

    init {
        Thread({ loop() }, "htmod-spectre").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1; start() }
    }

    fun feed(pcm: ShortArray, off: Int, len: Int) {
        synchronized(lock) {
            for (i in 0 until len) {
                if (count == ring.size) { head = (head + 1) % ring.size; count-- }
                ring[tail] = pcm[off + i]
                tail = (tail + 1) % ring.size
                count++
            }
            lock.notifyAll()
        }
    }

    /** Valeurs actuelles 0..1 (decroissance quand l'audio s'arrete). */
    fun values(): FloatArray = synchronized(v) {
        if (System.currentTimeMillis() - last > 400) for (i in 0 until COLS) v[i] *= 0.6f
        v.clone()
    }

    private fun loop() {
        val frame = ShortArray(FFT)
        while (true) {
            try {
                synchronized(lock) {
                    while (count < FFT) lock.wait(500)
                    for (i in 0 until FFT) { frame[i] = ring[head]; head = (head + 1) % ring.size }
                    count -= FFT
                }
                for (i in 0 until FFT) { re[i] = frame[i] * window[i]; im[i] = 0.0 }
                fft(re, im)
                col.fill(0f)
                for (k in 0 until FFT / 2) {
                    val mag = Math.sqrt(re[k] * re[k] + im[k] * im[k]) / FFT / 32768.0
                    val n = ((20.0 * Math.log10(mag + 1e-9) + 90.0) / 70.0).coerceIn(0.0, 1.0)
                    val c = k * COLS / (FFT / 2)
                    if (n > col[c]) col[c] = n.toFloat()
                }
                synchronized(v) { for (i in 0 until COLS) v[i] = v[i] * 0.55f + col[i] * 0.45f }
                last = System.currentTimeMillis()
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
            }
        }
    }

    companion object {
        private const val FFT = 1024
        const val COLS = 100

        fun fft(xr: DoubleArray, xi: DoubleArray) {
            val n = xr.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = xr[i]; xr[i] = xr[j]; xr[j] = t
                    t = xi[i]; xi[i] = xi[j]; xi[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2 * Math.PI / len
                val wr = Math.cos(ang)
                val wi = Math.sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k
                        val b = i + k + len / 2
                        val tr = cr * xr[b] - ci * xi[b]
                        val ti = cr * xi[b] + ci * xr[b]
                        xr[b] = xr[a] - tr; xi[b] = xi[a] - ti
                        xr[a] += tr; xi[a] += ti
                        val ncr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr; cr = ncr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
