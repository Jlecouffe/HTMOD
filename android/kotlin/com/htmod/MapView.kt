/*
 * HTMOD Android : carte tactile (OpenStreetMap / OpenTopoMap, mode nuit) : glisser pour deplacer,
 * pincer ou double-appui pour zoomer, appui sur une epingle pour sa fiche. Epingles APRS / sondes /
 * SARSAT avec leur trace ; calque optionnel (satellites). Position et zoom memorises.
 */
package com.htmod

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale

class MapView(c: Context, private val key: String, private val store: MapStore) : View(c) {
    var night = Prefs.getBoolean("$key.night", true)
        set(v) { field = v; Prefs.putBoolean("$key.night", v); invalidate() }
    var topo = Prefs.getBoolean("$key.topo", false)
        set(v) { field = v; Prefs.putBoolean("$key.topo", v); if (v && zoom > 17) zoom = 17.0; invalidate() }
    private var zoom = Prefs.get("$key.zoom", "6").toDoubleOrNull() ?: 6.0
    private var cx = lonToX(Prefs.get("$key.lon", "2.4").toDoubleOrNull() ?: 2.4)
    private var cy = latToY(Prefs.get("$key.lat", "46.6").toDoubleOrNull() ?: 46.6)
    var showMarkers = true
    var onTap: ((MapStore.Marker?) -> Unit)? = null
    /** Calque dessine au-dessus des tuiles (satellites). */
    var overlay: ((Canvas, MapView) -> Unit)? = null
    /** Appui hors epingle : coordonnees (calque satellites). */
    var onTapPoint: ((Float, Float) -> Boolean)? = null
    private val ts = 256f * Math.min(2.2f, resources.displayMetrics.density) / 1.4f
    private val tileP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyP = Paint().apply { color = 0xFF10161D.toInt() }
    private val markP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = c.dp(2).toFloat() }
    private val txtP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.dp(11).toFloat(); typeface = BOLD }
    private val infoP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = c.dp(10.5f).toFloat(); typeface = MONO; color = Col.TEXT2 }
    private val boxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.alpha(Col.BG, 0xD0) }
    private val redraw = { postInvalidate() }

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val ws = worldSize()
            cx = wrap(cx + dx / ws)
            cy = (cy + dy / ws).coerceIn(0.0, 1.0)
            invalidate()
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean { zoomAt(1.0, e.x, e.y); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val m = hit(e.x, e.y)
            if (m == null && onTapPoint?.invoke(e.x, e.y) == true) return true
            onTap?.invoke(m)
            return true
        }
    })

    private val scaler = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(Math.log(d.scaleFactor.toDouble()) / Math.log(2.0), d.focusX, d.focusY)
            return true
        }
    })

    init { isClickable = true }

    private fun maxZoom() = if (topo) 17.0 else 19.0
    private fun worldSize() = ts * Math.pow(2.0, zoom)

    private fun zoomAt(dz: Double, sx: Float, sy: Float) {
        val nz = (zoom + dz).coerceIn(2.0, maxZoom())
        val ws0 = worldSize()
        val px = cx + (sx - width / 2.0) / ws0
        val py = cy + (sy - height / 2.0) / ws0
        zoom = nz
        val ws1 = worldSize()
        cx = wrap(px - (sx - width / 2.0) / ws1)
        cy = (py - (sy - height / 2.0) / ws1).coerceIn(0.0, 1.0)
        save()
        invalidate()
    }

    fun zoomIn() = zoomAt(1.0, width / 2f, height / 2f)
    fun zoomOut() = zoomAt(-1.0, width / 2f, height / 2f)

    fun center(lat: Double, lon: Double, minZoom: Double = 0.0) {
        cx = lonToX(lon); cy = latToY(lat)
        if (zoom < minZoom) zoom = minZoom
        save(); invalidate()
    }

    /** Cadre toutes les epingles. */
    fun fitAll() {
        val ms = store.all()
        if (ms.isEmpty()) return
        var x0 = 1.0; var x1 = 0.0; var y0 = 1.0; var y1 = 0.0
        for (m in ms) { val x = lonToX(m.lon); val y = latToY(m.lat); x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y) }
        cx = (x0 + x1) / 2; cy = (y0 + y1) / 2
        val span = Math.max(Math.max(x1 - x0, y1 - y0), 1e-5)
        zoom = (Math.log(Math.min(width, height) * 0.8 / (ts * span)) / Math.log(2.0)).coerceIn(2.0, 14.0)
        save(); invalidate()
    }

    private fun save() {
        Prefs.put("$key.zoom", zoom.toString())
        Prefs.put("$key.lat", yToLat(cy).toString())
        Prefs.put("$key.lon", xToLon(cx).toString())
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        if (e.action == MotionEvent.ACTION_UP) save()
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    /** Point ecran d'une position (le plus proche du centre, la carte se repete en longitude). */
    fun toScreen(lat: Double, lon: Double): PointF {
        val ws = worldSize()
        var dx = lonToX(lon) - cx
        if (dx > 0.5) dx -= 1.0
        if (dx < -0.5) dx += 1.0
        return PointF((width / 2.0 + dx * ws).toFloat(), (height / 2.0 + (latToY(lat) - cy) * ws).toFloat())
    }

    /** Distance au sol (km) representee par un pixel au centre. */
    fun kmPerPixel(): Double = 40075.0 * Math.cos(Math.toRadians(yToLat(cy))) / worldSize()

    private fun hit(x: Float, y: Float): MapStore.Marker? {
        if (!showMarkers) return null
        var best: MapStore.Marker? = null
        var bd = context.dp(26).toDouble()
        for (m in store.all()) {
            val p = toScreen(m.lat, m.lon)
            val d = Math.hypot((p.x - x).toDouble(), (p.y - y).toDouble())
            if (d < bd) { bd = d; best = m }
        }
        return best
    }

    override fun onDraw(g: Canvas) {
        val w = width
        val h = height
        g.drawColor(Col.WELL)
        val tz = Math.floor(zoom + 0.5).toInt().coerceIn(2, maxZoom().toInt())
        val n = 1 shl tz
        val tsz = (ts * Math.pow(2.0, zoom - tz)).toFloat()
        val ws = worldSize()
        val left = cx * ws - w / 2.0
        val top = cy * ws - h / 2.0
        val tx0 = Math.floor(left / tsz).toInt()
        val ty0 = Math.max(0, Math.floor(top / tsz).toInt())
        val tx1 = Math.floor((left + w) / tsz).toInt()
        val ty1 = Math.min(n - 1, Math.floor((top + h) / tsz).toInt())
        val dst = RectF()
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val x = ((tx * tsz) - left).toFloat()
            val y = ((ty * tsz) - top).toFloat()
            dst.set(x, y, x + tsz + 0.5f, y + tsz + 0.5f)
            val b: Bitmap? = store.tile(topo, night, tz, ((tx % n) + n) % n, ty, redraw)
            if (b != null) g.drawBitmap(b, null, dst, tileP) else g.drawRect(dst, emptyP)
        }
        overlay?.invoke(g, this)
        if (showMarkers) drawMarkers(g)
        val info = String.format(Locale.ROOT, "Z%d  %.4f, %.4f", tz, yToLat(cy), xToLon(cx))
        val iw = infoP.measureText(info)
        g.drawRoundRect(RectF(6f, h - infoP.textSize * 2.1f, iw + 22, h - 6f), 10f, 10f, boxP)
        g.drawText(info, 14f, h - infoP.textSize * 0.9f, infoP)
        val attr = "© OpenStreetMap"
        g.drawText(attr, w - infoP.measureText(attr) - 12, h - infoP.textSize * 0.9f, infoP)
    }

    private fun drawMarkers(g: Canvas) {
        val ms = store.all()
        val r = context.dp(8).toFloat()
        for (m in ms) {
            if (m.track.size < 2) continue
            trackP.color = Col.alpha(kindColor(m.kind), 0x90)
            trackP.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            val p = Path()
            var first = true
            var prev: PointF? = null
            for (pt in m.track) {
                val s = toScreen(pt[0], pt[1])
                if (first || (prev != null && Math.abs(s.x - prev.x) > width)) p.moveTo(s.x, s.y) else p.lineTo(s.x, s.y)
                first = false
                prev = s
            }
            g.drawPath(p, trackP)
        }
        val bounds = Rect()
        for (m in ms) {
            val s = toScreen(m.lat, m.lon)
            if (s.x < -50 || s.y < -50 || s.x > width + 50 || s.y > height + 50) continue
            val col = kindColor(m.kind)
            markP.color = Col.alpha(Col.BG, 0xE0)
            g.drawCircle(s.x, s.y, r + 3, markP)
            markP.color = col
            g.drawCircle(s.x, s.y, r, markP)
            val sym = if (m.kind == MapStore.KIND_SARSAT) "!" else if (m.kind == MapStore.KIND_SONDE) "O" else if (m.symCode == ' ') "•" else m.symCode.toString()
            txtP.color = Col.BG
            txtP.getTextBounds(sym, 0, sym.length, bounds)
            g.drawText(sym, s.x - bounds.exactCenterX(), s.y - bounds.exactCenterY(), txtP)
            txtP.color = col
            val tw = txtP.measureText(m.title)
            g.drawRoundRect(RectF(s.x + r + 4, s.y - txtP.textSize, s.x + r + 12 + tw, s.y + txtP.textSize * 0.45f), 8f, 8f, boxP)
            g.drawText(m.title, s.x + r + 8, s.y + txtP.textSize * 0.15f, txtP)
        }
    }

    companion object {
        fun kindColor(kind: Int) = if (kind == MapStore.KIND_SARSAT) Col.RED else if (kind == MapStore.KIND_SONDE) Col.GREEN else Col.CYAN
        fun lonToX(lon: Double) = (lon + 180.0) / 360.0
        fun latToY(lat: Double): Double {
            val s = Math.sin(Math.toRadians(lat.coerceIn(-85.0, 85.0)))
            return 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)
        }
        fun xToLon(x: Double) = x * 360.0 - 180.0
        fun yToLat(y: Double) = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * y))))
        private fun wrap(x: Double) = x - Math.floor(x)
    }
}
