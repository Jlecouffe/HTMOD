/*
 * HTMOD Android : satellites : position d'observation, filtres (tous / SSTV / visibles, elevation
 * minimale), recherche, liste ; fiche du satellite (carte avec trace au sol et empreinte, position,
 * configurations radio corrigees du Doppler, MODIFIER, suivi avec le poste, passages), ajout / suppression.
 */
package com.htmod

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

class SatScreen(act: MainActivity) : Screen(act) {
    override val title = tr("Satellites")
    private var items: List<SatCatalog.Sat> = emptyList()
    private val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    private val lat = k.field(Prefs.get("obsLat", "48.8566"), "lat", num, true)
    private val lon = k.field(Prefs.get("obsLon", "2.3522"), "lon", num, true)
    private val status = k.text(" ", 12f, Col.MUTED, mono = true)
    private val search = k.field("", tr("Rechercher (nom ou n° NORAD)"))
    private val filter = k.choice(listOf(tr("TOUS"), "SSTV", tr("VISIBLES")), 0) { engine?.sats?.filter = it }
    private val minEl = k.choice(SatMod.MIN_ELS.map { "$it°" }, SatMod.MIN_ELS.indexOf(Prefs.getInt("satMinEl", 0))) { engine?.sats?.setMinEl(SatMod.MIN_ELS[it]) }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: k.hbox().apply {
                setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(10))
                addView(k.text("", 15f, Col.TEXT, bold = true), lp(0, WRAP, 1f))
                addView(k.text("", 12.5f, Col.TEXT2, mono = true), lp(k.dp(52), WRAP))
                addView(k.text("", 12.5f, Col.MUTED, mono = true), lp(k.dp(92), WRAP))
            }
            val s = items[p]
            val sats = engine?.sats
            val l = sats?.looks?.get(s.norad)
            (v.getChildAt(0) as TextView).apply {
                text = s.name + if (sats?.catalog?.isSstv(s) == true) "  ●" else ""
                setTextColor(if (l != null && l.elDeg > 0) Col.CYAN else if (sats?.catalog?.isSstv(s) == true) Col.PURPLE else Col.TEXT)
            }
            (v.getChildAt(1) as TextView).text = if (l == null) "" else String.format(Locale.ROOT, "%.0f°", l.elDeg)
            (v.getChildAt(2) as TextView).text = sats?.next(s) ?: ""
            v.setBackgroundColor(if (p % 2 == 0) Col.CARD else Col.alpha(Col.CARD, 0x80))
            return v
        }
    }

    private val listPart = k.vbox(k.dp(12)).apply {
        addView(k.card().apply {
            addView(k.label(tr("Position d'observation (lat / lon en degrés)")), lp().apply { bottomMargin = k.dp(6) })
            val row = k.hbox()
            row.addView(lat, lp(0, WRAP, 1f))
            row.addView(lon, lp(0, WRAP, 1f).apply { leftMargin = k.dp(6) })
            row.addView(k.key("OK", small = true) { k.err(engine?.sats?.setObserver(lat.text.toString(), lon.text.toString())) }, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            addView(row)
            addView(filter, lp().apply { topMargin = k.dp(10) })
            addView(k.label(tr("Élévation minimale")), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            addView(minEl)
            addView(search, lp().apply { topMargin = k.dp(10) })
        })
        val box = k.card().apply { setPadding(0, k.dp(6), 0, k.dp(6)) }
        box.addView(ListView(act).apply {
            adapter = this@SatScreen.adapter
            divider = null
            setSelector(android.R.color.transparent)
            setOnItemClickListener { _, _, p, _ -> engine?.sats?.select(items[p]); showDetail(true) }
        }, lp(MATCH, 0, 1f))
        addView(box, lp(MATCH, 0, 1f))
        val keys = k.hbox()
        keys.addView(k.key(tr("ACTUALISER"), small = true) { engine?.sats?.refresh(); k.toast(tr("Mise à jour du catalogue…")) }, lp(0, WRAP, 1f))
        keys.addView(k.key(tr("AJOUTER…"), small = true) { addDialog() }, lp(0, WRAP, 1f).apply { leftMargin = k.dp(6) })
        addView(keys)
        addView(status, lp().apply { topMargin = k.dp(6) })
    }

    // fiche
    private val dName = k.text("", 22f, Col.TEXT, bold = true)
    private val dSub = k.text("", 12f, Col.MUTED, mono = true)
    private val dPos = k.text("", 13.5f, Col.TEXT2, mono = true)
    private val dRf = k.text("", 13.5f, Col.TEXT2, mono = true)
    private val rfKey = k.key(" ", small = true) { pickRf() }
    private val trackKey = k.key(tr("SUIVRE AVEC LE POSTE"), primary = true) { k.err(engine?.sats?.toggleTracking()) }
    private val dPasses = k.text("", 13f, Col.TEXT2, mono = true)
    private val sstvSw = k.switchRow(tr("Satellite SSTV"), false) { engine?.sats?.setSstv(it) }
    private val removeKey = k.key(tr("SUPPRIMER CE SATELLITE"), small = true) {
        k.confirm(tr("Satellites"), tr("Supprimer ce satellite ajouté par vous ?"), tr("SUPPRIMER")) {
            val err = engine?.sats?.remove()
            if (err != null) k.alert(err) else showDetail(false)
        }
    }
    private var satMap: MapView? = null
    private val mapBox = FrameLayout(act)

    private val detailPart = k.scroll(k.vbox(k.dp(12)).apply {
        addView(k.key(tr("‹ LISTE DES SATELLITES"), small = true) { showDetail(false) }, lp().apply { bottomMargin = k.dp(10) })
        addView(k.card().apply {
            addView(dName)
            addView(dSub)
            addView(mapBox, lp(MATCH, k.dp(240)).apply { topMargin = k.dp(10); bottomMargin = k.dp(10) })
            addView(dPos)
            addView(sstvSw)
        })
        addView(k.card().apply {
            addView(k.label(tr("Radio (Doppler corrigé)")), lp().apply { bottomMargin = k.dp(6) })
            addView(rfKey)
            addView(dRf, lp().apply { topMargin = k.dp(8); bottomMargin = k.dp(8) })
            addView(k.key(tr("MODIFIER LES FRÉQUENCES"), small = true) { editDialog() })
            addView(trackKey, lp().apply { topMargin = k.dp(8) })
            addView(k.note(tr("Le poste est accordé en mode fréquence et suit le Doppler ; le satellite s'affiche sur son écran.")))
        })
        addView(k.card().apply {
            addView(k.label(tr("Passages (24 h, heure locale)")), lp().apply { bottomMargin = k.dp(6) })
            addView(dPasses)
        })
        addView(removeKey)
    })

    private val frame = FrameLayout(act)
    override val view: View = frame
    private var inDetail = false

    init { showDetail(false) }

    private fun showDetail(on: Boolean) {
        inDetail = on
        frame.removeAllViews()
        frame.addView(if (on) detailPart else listPart, FrameLayout.LayoutParams(MATCH, MATCH))
        if (on) ensureMap()
    }

    private fun ensureMap() {
        val e = engine ?: return
        if (satMap != null) return
        val m = MapView(act, "satMap", e.map)
        m.showMarkers = false
        m.overlay = { g, mv -> drawOverlay(g, mv) }
        satMap = m
        mapBox.addView(m, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private val trackP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = act.dp(2).toFloat(); color = Col.AMBER }
    private val footP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = act.dp(1.5f).toFloat(); color = Col.alpha(Col.AMBER, 0xB0) }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Trace au sol (passe en pointilles, futur plein), empreinte, position du satellite et de l'observateur. */
    private fun drawOverlay(g: Canvas, mv: MapView) {
        val sm = engine?.sats ?: return
        val s = sm.selected ?: return
        val tr0 = sm.track
        if (tr0.size > 1) {
            val nowIdx = Math.min(tr0.size - 1, ((System.currentTimeMillis() - (sm.trackAt - 50 * 60_000L)) / 30_000L).toInt()).coerceAtLeast(0)
            fun poly(pts: List<DoubleArray>, dashed: Boolean) {
                val p = Path()
                var prevX = Float.NaN
                for ((i, pt) in pts.withIndex()) {
                    val sp = mv.toScreen(pt[0], pt[1])
                    if (i == 0 || Math.abs(sp.x - prevX) > mv.width / 2f) p.moveTo(sp.x, sp.y) else p.lineTo(sp.x, sp.y)
                    prevX = sp.x
                }
                trackP.pathEffect = if (dashed) DashPathEffect(floatArrayOf(10f, 10f), 0f) else null
                g.drawPath(p, trackP)
            }
            poly(tr0.subList(0, nowIdx + 1), true)
            poly(tr0.subList(nowIdx, tr0.size), false)
        }
        val l = sm.looks[s.norad]
        if (l != null) {
            val c = mv.toScreen(l.latDeg, l.lonDeg)
            val rpx = (l.footprintKm / mv.kmPerPixel()).toFloat()
            if (rpx in 2f..5000f) g.drawCircle(c.x, c.y, rpx, footP)
            dotP.color = Col.AMBER
            g.drawCircle(c.x, c.y, act.dp(7).toFloat(), dotP)
        }
        sm.observer?.let {
            val o = mv.toScreen(it.latDeg, it.lonDeg)
            dotP.color = Col.CYAN
            g.drawCircle(o.x, o.y, act.dp(5).toFloat(), dotP)
        }
    }

    private fun pickRf() {
        val sm = engine?.sats ?: return
        val s = sm.selected ?: return
        if (s.rf.isEmpty()) { k.alert(tr("Ce satellite n'a pas de configuration radio.")); return }
        k.pick(tr("Configuration radio"), s.rf.map { it.label() }) { sm.setRf(it) }
    }

    private fun editDialog() {
        val sm = engine?.sats ?: return
        val r = sm.rf() ?: run { k.alert(tr("Ce satellite n'a pas de configuration radio (ajoutez-en une avec AJOUTER…).")); return }
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val down = k.field(if (r.downHz > 0) SatCatalog.mhz(r.downHz) else "", tr("Réception (descente), MHz"), dec, true)
        val up = k.field(if (r.upHz > 0) SatCatalog.mhz(r.upHz) else "", tr("Émission (montée), MHz — vide = aucune"), dec, true)
        val tone = k.field(if (r.upTone > 0) String.format(Locale.ROOT, "%.1f", r.upTone / 100.0) else "", tr("CTCSS à l'émission, Hz — vide = aucun"), dec, true)
        val body = k.vbox(k.dp(18)).apply {
            addView(down); addView(up, lp().apply { topMargin = k.dp(8) }); addView(tone, lp().apply { topMargin = k.dp(8) })
            r.orig?.let { addView(k.note(tr("Origine : ↓ ") + SatCatalog.mhz(it[0]) + if (it[1] > 0) "  ↑ " + SatCatalog.mhz(it[1]) else "")) }
        }
        val d = k.dialog().setTitle((sm.selected?.name ?: "") + " · " + r.name.ifEmpty { "FM" }).setView(body)
            .setNegativeButton(tr("ANNULER"), null)
            .setPositiveButton(tr("ENREGISTRER")) { _, _ -> k.err(sm.editRf(down.text.toString(), up.text.toString(), tone.text.toString())) }
        if (r.orig != null) d.setNeutralButton(tr("ORIGINE")) { _, _ -> k.err(sm.resetRf()) }
        d.show()
    }

    private fun addDialog() {
        val sm = engine?.sats ?: return
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val name = k.field("", tr("Nom"))
        val norad = k.field("", tr("N° NORAD"), InputType.TYPE_CLASS_NUMBER, true)
        val l1 = k.field("", tr("TLE ligne 1"), mono = true)
        val l2 = k.field("", tr("TLE ligne 2"), mono = true)
        val rfName = k.field("FM", tr("Nom de la configuration"))
        val down = k.field("", tr("Descente MHz (facultatif)"), dec, true)
        val up = k.field("", tr("Montée MHz"), dec, true)
        val tone = k.field("", "CTCSS Hz", dec, true)
        val sstv = k.switchRow(tr("Satellite SSTV"), false) {}
        val fetch = k.key(tr("RÉCUPÉRER LE TLE (CELESTRAK)"), small = true) {
            val n = norad.text.toString()
            Thread({
                try {
                    val t = sm.fetchTle(n)
                    act.runOnUiThread { if (name.text.isEmpty()) name.setText(t[0]); l1.setText(t[1]); l2.setText(t[2]) }
                } catch (e: Exception) {
                    act.runOnUiThread { k.alert(tr("TLE introuvable : ") + e.message) }
                }
            }, "htmod-tle").start()
        }
        val body = k.vbox(k.dp(18)).apply {
            for (v in listOf(name, norad, fetch, l1, l2, rfName, down, up, tone, sstv)) addView(v, lp().apply { bottomMargin = k.dp(6) })
        }
        k.dialog().setTitle(tr("Ajouter un satellite")).setView(k.scroll(body))
            .setNegativeButton(tr("ANNULER"), null)
            .setPositiveButton(tr("AJOUTER")) { _, _ ->
                k.err(sm.add(name.text.toString(), l1.text.toString(), l2.text.toString(), rfName.text.toString(), down.text.toString(),
                    up.text.toString(), tone.text.toString(), sstv.on))
            }.show()
    }

    init {
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { engine?.sats?.search = s?.toString() ?: "" }
        })
    }

    override fun refresh() {
        val sm = engine?.sats ?: return
        status.text = sm.shown.size.toString() + " / " + sm.catalog.all().size + " · " + sm.status
        if (!inDetail) {
            items = sm.shown
            adapter.notifyDataSetChanged()
            return
        }
        val s = sm.selected ?: run { showDetail(false); return }
        val sstv = sm.catalog.isSstv(s)
        dName.text = s.name
        dName.setTextColor(if (sstv) Col.AMBER else Col.TEXT)
        dSub.text = "NORAD " + s.norad + tr(" · source ") + s.source + if (sstv) " · SSTV" else ""
        sstvSw.on = sstv
        val l = sm.looks[s.norad]
        dPos.text = if (l == null) tr("Position incalculable (TLE trop ancien ?)") else String.format(Locale.ROOT, "Az %.1f°  ·  Él %.1f°  ·  %.0f km\nAlt %.0f km  ·  %.3f, %.3f\n",
            l.azDeg, l.elDeg, l.rangeKm, l.altKm, l.latDeg, l.lonDeg) + if (l.elDeg > 0) tr("VISIBLE") + String.format(Locale.ROOT, tr("  ·  vitesse radiale %+.2f km/s"), l.rangeRateKms) else tr("sous l'horizon")
        dPos.setTextColor(if (l != null && l.elDeg > 0) Col.CYAN else Col.TEXT2)
        rfKey.setLabel((sm.rf()?.name?.ifEmpty { "FM" } ?: tr("aucune configuration")) + "  ›")
        dRf.text = sm.rfText()
        trackKey.setLabel(if (sm.tracking) tr("ARRÊTER LE SUIVI") else tr("SUIVRE AVEC LE POSTE"))
        dPasses.text = sm.passes
        removeKey.visibility = if (s.source == SatCatalog.SRC_USER) View.VISIBLE else View.GONE
        satMap?.let { m ->
            if (l != null && m.tag != s.norad) { m.tag = s.norad; m.center(l.latDeg, l.lonDeg) }
            m.invalidate()
        }
    }
}
