/*
 * HTMOD Android : onglet CARTE : stations APRS, radiosondes et balises SARSAT sur OpenStreetMap
 * (nuit / topo), compteurs, fiche de l'epingle touchee (ouverture dans une appli de cartes).
 */
package com.htmod

import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MapScreen(act: MainActivity) : Screen(act) {
    override val title = tr("CARTE")
    private var map: MapView? = null
    private val frame = FrameLayout(act)
    private val counters = k.text(" ", 12f, Col.TEXT2, mono = true)
    private val info = k.card()
    private val infoTitle = k.text("", 20f, Col.TEXT, bold = true)
    private val infoSub = k.text("", 12f, Col.MUTED, mono = true)
    private val infoText = k.text("", 13f, Col.TEXT2, mono = true)
    private var marker: MapStore.Marker? = null
    private var mapVersion = -1L
    private val night = k.key(tr("NUIT"), small = true) { map?.let { it.night = !it.night } }
    private val topo = k.key("TOPO", small = true) { map?.let { it.topo = !it.topo } }

    override val view: View = frame

    override fun onShow() {
        val e = engine ?: return
        if (map == null) {
            val m = MapView(act, "map", e.map)
            m.onTap = { show(it) }
            map = m
            frame.addView(m, FrameLayout.LayoutParams(MATCH, MATCH))
            val keys = k.hbox().apply { setPadding(k.dp(10), k.dp(10), k.dp(10), 0) }
            keys.addView(night)
            keys.addView(topo, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            keys.addView(k.key(tr("TOUT"), small = true) { m.fitAll() }, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            keys.addView(View(act), lp(0, 1, 1f))
            keys.addView(k.key("−", small = true) { m.zoomOut() }, lp(k.dp(46), WRAP))
            keys.addView(k.key("+", small = true) { m.zoomIn() }, lp(k.dp(46), WRAP).apply { leftMargin = k.dp(6) })
            frame.addView(keys, FrameLayout.LayoutParams(MATCH, WRAP).gravity(Gravity.TOP))
            counters.background = round(Col.alpha(Col.BG, 0xD0), k.dp(10).toFloat())
            counters.setPadding(k.dp(10), k.dp(5), k.dp(10), k.dp(5))
            frame.addView(counters, FrameLayout.LayoutParams(WRAP, WRAP).gravity(Gravity.TOP or Gravity.START).apply {
                topMargin = k.dp(64); leftMargin = k.dp(10)
            })
            info.addView(infoTitle)
            info.addView(infoSub)
            info.addView(infoText, lp().apply { topMargin = k.dp(6) })
            val ik = k.hbox()
            ik.addView(k.key(tr("OUVRIR DANS UNE APPLI DE CARTES"), small = true) { openMaps() }, lp(0, WRAP, 1f))
            ik.addView(k.key(tr("FERMER"), small = true) { show(null) }, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            info.addView(ik, lp().apply { topMargin = k.dp(10) })
            info.visibility = View.GONE
            frame.addView(info, FrameLayout.LayoutParams(MATCH, WRAP).gravity(Gravity.BOTTOM).apply { setMargins(k.dp(10), 0, k.dp(10), k.dp(26)) })
        }
    }

    /** Centre la carte sur une epingle (depuis APRS ou les decodages) et ouvre sa fiche. */
    fun focus(key: String) {
        onShow()
        val m = engine?.map?.get(key) ?: return
        map?.center(m.lat, m.lon, 11.0)
        show(m)
    }

    private fun show(m: MapStore.Marker?) {
        marker = m
        if (m == null) { info.visibility = View.GONE; return }
        infoTitle.text = m.title
        infoTitle.setTextColor(MapView.kindColor(m.kind))
        infoSub.text = m.subtitle + "  ·  " + SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(m.time))
        infoText.text = m.details
        info.visibility = View.VISIBLE
    }

    private fun openMaps() {
        val m = marker ?: return
        val geo = String.format(Locale.ROOT, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", m.lat, m.lon, m.lat, m.lon, Uri.encode(m.title))
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geo))) }
        catch (e: Exception) {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=13/%.6f/%.6f", m.lat, m.lon, m.lat, m.lon))))
        }
    }

    override fun refresh() {
        val e = engine ?: return
        val m = map ?: return
        night.setSel(m.night)
        topo.setSel(m.topo)
        counters.text = "APRS " + e.map.count(MapStore.KIND_APRS) + "  ·  " + tr("SONDES ") + e.map.count(MapStore.KIND_SONDE) +
            "  ·  SARSAT " + e.map.count(MapStore.KIND_SARSAT)
        if (e.map.version != mapVersion) {
            mapVersion = e.map.version
            m.invalidate()
            marker?.let { mk -> e.map.get(mk.key)?.let { if (info.visibility == View.VISIBLE) show(it) } }
        }
    }
}
