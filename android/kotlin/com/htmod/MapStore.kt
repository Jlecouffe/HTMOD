/*
 * HTMOD Android : epingles de la carte (APRS, radiosondes, SARSAT) et tuiles OpenStreetMap / OpenTopoMap
 * (cache disque 30 jours, mode nuit identique a MapPanel.java de la version Windows).
 */
package com.htmod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MapStore(private val cacheDir: File, private val log: (String) -> Unit) {
    class Marker(val key: String) {
        var kind = 0
        var title = ""
        var subtitle = ""
        var details = ""
        var symTable = '/'
        var symCode = ' '
        var lat = 0.0
        var lon = 0.0
        var time = 0L
        val track = ArrayList<DoubleArray>()
    }

    private val markers = LinkedHashMap<String, Marker>()
    @Volatile var version = 0L
        private set

    fun put(key: String, kind: Int, title: String, subtitle: String, details: String, lat: Double, lon: Double, symTable: Char, symCode: Char, time: Long) {
        synchronized(markers) {
            val m = markers.getOrPut(key) { Marker(key) }
            val moved = m.track.isEmpty() || Math.abs(m.lat - lat) > 1e-6 || Math.abs(m.lon - lon) > 1e-6
            m.kind = kind; m.title = title; m.subtitle = subtitle; m.details = details
            m.lat = lat; m.lon = lon; m.symTable = symTable; m.symCode = symCode; m.time = time
            if (moved) {
                m.track.add(doubleArrayOf(lat, lon))
                if (m.track.size > 800) m.track.removeAt(0)
            }
            version++
        }
    }

    fun clear() = synchronized(markers) { markers.clear(); version++ }

    fun count(kind: Int): Int = synchronized(markers) { markers.values.count { it.kind == kind } }

    fun all(): List<Marker> = synchronized(markers) { ArrayList(markers.values) }

    fun get(key: String): Marker? = synchronized(markers) { markers[key] }

    // ------------------------------------------------------------------ Tuiles

    private val mem = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "htmod-tuiles").apply { isDaemon = true } }
    @Volatile private var errorLogged = false

    /** Tuile en memoire, sinon null (chargement lance, onReady appele a l'arrivee). */
    fun tile(topo: Boolean, night: Boolean, z: Int, x: Int, y: Int, onReady: () -> Unit): Bitmap? {
        if (z < 0 || z > 19 || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        val k = (if (topo) "t" else "o") + (if (night) "n" else "j") + "$z/$x/$y"
        mem.get(k)?.let { return it }
        val f = failed[k]
        if (f != null && System.currentTimeMillis() - f < 60_000) return null
        if (loading.add(k)) pool.execute {
            try {
                val raw = load(topo, z, x, y)
                var b = if (raw == null) null else BitmapFactory.decodeByteArray(raw, 0, raw.size)
                if (b != null && night) b = invert(b)
                if (b != null) { mem.put(k, b); onReady() } else failed[k] = System.currentTimeMillis()
            } finally {
                loading.remove(k)
            }
        }
        return null
    }

    private fun url(topo: Boolean, z: Int, x: Int, y: Int) =
        if (topo) "https://" + "abc"[(x + y) % 3] + ".tile.opentopomap.org/$z/$x/$y.png" else "https://tile.openstreetmap.org/$z/$x/$y.png"

    private fun load(topo: Boolean, z: Int, x: Int, y: Int): ByteArray? {
        val f = File(cacheDir, (if (topo) "topo/" else "osm/") + "$z/$x/$y.png")
        try {
            val fresh = f.isFile && System.currentTimeMillis() - f.lastModified() < 30L * 86400_000L
            if (!fresh) {
                val c = URL(url(topo, z, x, y)).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "HTMod/2.0 (Android; decodeur radioamateur SARSAT/radiosondes/APRS)")
                c.connectTimeout = 8000
                c.readTimeout = 15000
                val code = c.responseCode
                if (code == 200) {
                    f.parentFile?.mkdirs()
                    val tmp = File(f.path + ".part")
                    c.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o) } }
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                } else if (!errorLogged) {
                    errorLogged = true
                    log(tr("Carte : le serveur de tuiles répond ") + code)
                }
                c.disconnect()
            }
            return if (f.isFile) f.readBytes() else null
        } catch (e: Exception) {
            if (!errorLogged) { errorLogged = true; log(tr("Carte : téléchargement des tuiles impossible (") + e + ")") }
            return try { if (f.isFile) f.readBytes() else null } catch (e2: Exception) { null }
        }
    }

    /** Mode nuit : luminance inversee, desaturee et teintee graphite-bleu (comme la version Windows). */
    private fun invert(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            var v = 255 - (0.299 * r + 0.587 * g + 0.114 * b)
            v = v * v / 255.0
            var cr = 10 + v * 0.66
            var cg = 14 + v * 0.74
            var cb = 19 + v * 0.84
            cr += ((255 - r) - v) * 0.10; cg += ((255 - g) - v) * 0.10; cb += ((255 - b) - v) * 0.10
            if (b > r + 25 && b > g) { cb += 18; cg += 8 }
            px[i] = (0xFF shl 24) or (clamp(cr) shl 16) or (clamp(cg) shl 8) or clamp(cb)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun clamp(v: Double) = if (v < 0) 0 else if (v > 255) 255 else v.toInt()

    companion object {
        const val KIND_APRS = 0
        const val KIND_SONDE = 1
        const val KIND_SARSAT = 2
    }
}
