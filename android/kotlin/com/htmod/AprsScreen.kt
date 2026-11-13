/*
 * HTMOD Android : onglet APRS : trafic recu (TNC du poste + demodulateur logiciel), fiche de la trame
 * (voir sur la carte) ; emission par le TNC du poste (balise de position, statut, message, balise auto).
 */
package com.htmod

import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AprsScreen(act: MainActivity) : Screen(act) {
    override val title = "APRS"
    private var rows: List<Engine.AprsRow> = emptyList()
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    private val header = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val empty = k.text(tr("Aucune trame APRS reçue pour l'instant."), 14f, Col.MUTED).apply { setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16)) }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: k.vbox().apply {
                setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(10))
                val top = k.hbox()
                top.addView(k.text("", 12.5f, Col.MUTED, mono = true), lp(k.dp(74), WRAP))
                top.addView(k.text("", 16f, Col.CYAN, bold = true), lp(0, WRAP, 1f))
                top.addView(k.text("", 12f, Col.TEXT2, mono = true))
                addView(top)
                addView(k.text("", 13.5f, Col.TEXT2), lp().apply { leftMargin = k.dp(74) })
            }
            val r = rows[p]
            val top = v.getChildAt(0) as LinearLayout
            (top.getChildAt(0) as TextView).text = tf.format(Date(r.time))
            (top.getChildAt(1) as TextView).text = r.name + if (r.via.isEmpty()) "" else "  (" + r.via + ")"
            (top.getChildAt(2) as TextView).text = r.type + if (r.origin == "TNC") "" else " ·"
            (v.getChildAt(1) as TextView).text = listOf(r.pos, r.info.ifEmpty { r.tnc2 }).filter { it.isNotEmpty() }.joinToString("\n")
            v.setBackgroundColor(if (p % 2 == 0) Col.CARD else Col.alpha(Col.CARD, 0x80))
            return v
        }
    }

    private val list = ListView(act).apply {
        adapter = this@AprsScreen.adapter
        divider = null
        setSelector(android.R.color.transparent)
        setOnItemClickListener { _, _, p, _ -> detail(rows[p]) }
    }

    // emission
    private val params = k.vbox()
    private val infoField = k.field(Prefs.get("aprsTxInfo", ""), tr("vide = balise de position"))
    private val txStatus = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val sendKey = k.key(tr("ÉMETTRE TRAME"), primary = true) { send() }
    private val autoSw = k.switchRow(tr("Balise automatique (intervalle du poste)"), false) { on -> auto(on) }
    private var paramsText = ""

    private val traffic = k.vbox(k.dp(12)).apply {
        addView(header, lp().apply { bottomMargin = k.dp(8) })
        val box = k.card().apply { setPadding(0, k.dp(6), 0, k.dp(6)) }
        box.addView(empty)
        box.addView(list, lp(MATCH, 0, 1f))
        addView(box, lp(MATCH, 0, 1f))
    }

    private val tx = k.scroll(k.vbox(k.dp(12)).apply {
        addView(k.card().apply {
            addView(k.label(tr("Réglages APRS du poste")), lp().apply { bottomMargin = k.dp(8) })
            addView(params)
            addView(k.key(tr("RELIRE LE POSTE"), small = true) { k.err(engine?.aprsTx?.reload()) }, lp(WRAP, WRAP).apply { topMargin = k.dp(10) })
        })
        addView(k.card().apply {
            addView(k.label(tr("Champ d'information")), lp().apply { bottomMargin = k.dp(6) })
            addView(infoField)
            addView(k.note(tr("Ex. « >En écoute » (statut) ou « :F4ABC    :Bonjour » (message) ; vide = position + commentaire du poste.")))
            addView(autoSw, lp().apply { topMargin = k.dp(8) })
            addView(txStatus, lp().apply { topMargin = k.dp(6); bottomMargin = k.dp(10) })
            addView(sendKey)
            addView(k.note(tr("La trame est modulée par le TNC du poste et émise dès que le canal est libre, sur le canal nommé « APRS » s'il existe, sinon sur celui du VFO A. Réservé aux radioamateurs.")))
        })
    })

    private val body = FrameLayout(act)
    private val tabs = k.choice(listOf(tr("TRAFIC"), tr("ÉMISSION")), 0) { i -> showPart(i) }

    override val view: View = k.vbox().apply {
        addView(tabs, lp().apply { setMargins(k.dp(12), k.dp(10), k.dp(12), 0) })
        addView(body, lp(MATCH, 0, 1f))
        showPart(0)
    }

    private fun showPart(i: Int) {
        body.removeAllViews()
        body.addView(if (i == 0) traffic else tx, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun detail(r: Engine.AprsRow) {
        val b = k.vbox(k.dp(20))
        b.addView(k.text(r.type + "  ·  " + tf.format(Date(r.time)) + "  ·  " + r.origin, 13f, Col.MUTED, mono = true))
        if (r.pos.isNotEmpty()) b.addView(k.text(r.pos, 15f, Col.TEXT, mono = true), lp().apply { topMargin = k.dp(8) })
        if (r.info.isNotEmpty()) b.addView(k.text(r.info, 15f, Col.TEXT2), lp().apply { topMargin = k.dp(8) })
        b.addView(k.text(r.tnc2, 13f, Col.TEXT2, mono = true).apply {
            setTextIsSelectable(true)
            setPadding(k.dp(10), k.dp(10), k.dp(10), k.dp(10))
            background = round(Col.WELL, k.dp(10).toFloat(), k.dp(1), Col.EDGE)
        }, lp().apply { topMargin = k.dp(12) })
        val d = k.dialog().setTitle(r.name).setView(k.scroll(b)).setPositiveButton(tr("FERMER"), null)
        if (r.mapKey != null) d.setNeutralButton(tr("VOIR SUR LA CARTE")) { _, _ -> act.showOnMap(r.mapKey) }
        d.show()
    }

    private fun send() {
        val a = engine?.aprsTx ?: return
        val info = infoField.text.toString()
        Prefs.put("aprsTxInfo", info)
        k.confirm(tr("Émettre la trame APRS ?"), (if (info.isBlank()) tr("Balise de position") else info) + "\n\n" +
            tr("Je confirme être radioamateur et émettre sur une fréquence autorisée."), tr("ÉMETTRE")) { k.err(a.send(info)) }
    }

    private fun auto(on: Boolean) {
        val a = engine?.aprsTx ?: return
        if (!on) { a.setAuto(false); return }
        k.dialog().setTitle("APRS").setMessage(tr("Balise automatique : une position toutes les ") + a.intervalText() + tr(", tant que HTMod est connecté.\nContinuer ?"))
            .setNegativeButton(tr("ANNULER")) { _, _ -> autoSw.on = false }
            .setPositiveButton("OK") { _, _ -> val err = a.setAuto(true); if (err != null) { autoSw.on = false; k.alert(err) } }
            .setOnCancelListener { autoSw.on = false }
            .show()
    }

    override fun refresh() {
        val e = engine ?: return
        rows = e.aprsRows()
        adapter.notifyDataSetChanged()
        header.text = rows.size.toString() + tr(" paquet(s) · ") + e.map.count(MapStore.KIND_APRS) + tr(" station(s) sur la carte")
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val a = e.aprsTx
        val ps = a.params()
        val txt = ps.joinToString("\n") { it.first + it.second }
        if (txt != paramsText) {
            paramsText = txt
            params.removeAllViews()
            for ((t, v) in ps) {
                val row = k.hbox(Gravity.TOP)
                if (t.isNotEmpty()) row.addView(k.text(t, 13f, Col.MUTED), lp(k.dp(104), WRAP))
                row.addView(k.text(v, 13.5f, Col.TEXT2), lp(0, WRAP, 1f))
                params.addView(row, lp().apply { bottomMargin = k.dp(4) })
            }
        }
        txStatus.text = a.status
        txStatus.setTextColor(a.statusColor)
        sendKey.setLabel(if (a.waiting) tr("EN ATTENTE DU CANAL…") else tr("ÉMETTRE TRAME"))
        autoSw.on = a.auto
    }
}
