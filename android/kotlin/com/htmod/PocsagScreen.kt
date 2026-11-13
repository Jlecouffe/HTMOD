/*
 * HTMOD Android : onglet POCSAG : messages recus (heure + message en entier, le plus recent en haut) ;
 * toucher = fiche (RIC, fonction, debit, type, texte ; COPIER, SUPPRIMER, FERMER) ; appui long =
 * suppression. Touche EMETTRE : formulaire d'emission (radioamateurs).
 */
package com.htmod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView

class PocsagScreen(act: MainActivity) : Screen(act) {
    override val title = "POCSAG"
    private val sync = k.text(" ", 12.5f, Col.CYAN, mono = true)
    private val count = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val empty = k.text(tr("Aucun message pour l'instant. Les messages reçus par le poste apparaissent ici, et une alerte s'affiche à chaque réception."), 14f, Col.MUTED)
    private var items: List<PocsagMsg> = emptyList()
    private var shownVersion = -1L
    private val send = k.key(tr("ÉMETTRE UN MESSAGE"), primary = true) { onSend() }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = (convert as? android.widget.LinearLayout) ?: k.hbox(Gravity.TOP).apply {
                setPadding(k.dp(14), k.dp(12), k.dp(14), k.dp(12))
                addView(k.text("", 13f, Col.MUTED, mono = true), lp(k.dp(78), WRAP))
                addView(k.text("", 16f, Col.TEXT), lp(0, WRAP, 1f))
            }
            val m = items[p]
            (row.getChildAt(0) as TextView).text = m.time
            (row.getChildAt(1) as TextView).text = m.text
            row.setBackgroundColor(if (p % 2 == 0) Col.CARD else Col.alpha(Col.CARD, 0x80))
            return row
        }
    }

    private val list = ListView(act).apply {
        adapter = this@PocsagScreen.adapter
        divider = null
        setSelector(android.R.color.transparent)
        setOnItemClickListener { _, _, p, _ -> detail(items[p]) }
        setOnItemLongClickListener { _, v, p, _ ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            askDelete(items[p])
            true
        }
    }

    override val view: View = k.vbox(k.dp(12)).apply {
        val head = k.card().apply {
            addView(k.label(tr("Messages reçus · 512 / 1200 / 2400 bauds")))
            addView(sync, lp().apply { topMargin = k.dp(4) })
            addView(count)
        }
        addView(head)
        val box = k.card().apply { setPadding(0, k.dp(6), 0, k.dp(6)) }
        empty.setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16))
        box.addView(empty)
        box.addView(list, lp(MATCH, 0, 1f))
        addView(box, lp(MATCH, 0, 1f))
        addView(send)
    }

    override fun refresh() {
        val e = engine ?: return
        val p = e.pocsag
        sync.text = p.syncText()
        if (p.version != shownVersion) {
            shownVersion = p.version
            items = p.messages()
            adapter.notifyDataSetChanged()
        }
        count.text = items.size.toString() + tr(" message(s)")
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        send.setLabel(if (p.sending) tr("ARRÊTER L'ÉMISSION") else tr("ÉMETTRE UN MESSAGE"))
    }

    /** Fiche d'un message (depuis la liste ou l'alerte). */
    fun detail(m: PocsagMsg) {
        val body = k.vbox(k.dp(20))
        fun row(a: String, b: String) = k.hbox().apply {
            addView(k.text(a, 14f, Col.MUTED), lp(k.dp(96), WRAP))
            addView(k.text(b, 15f, Col.TEXT, bold = true, mono = true))
        }
        body.addView(row(tr("Heure"), m.time))
        body.addView(row("RIC", m.ric))
        body.addView(row(tr("Fonction"), m.func))
        body.addView(row(tr("Débit"), m.rate))
        body.addView(row(tr("Type"), m.type))
        body.addView(k.text(m.text, 17f, Col.TEXT, mono = true).apply {
            setTextIsSelectable(true)
            setPadding(k.dp(12), k.dp(12), k.dp(12), k.dp(12))
            background = round(Col.WELL, k.dp(10).toFloat(), k.dp(1), Col.EDGE)
        }, lp().apply { topMargin = k.dp(14) })
        k.dialog().setTitle("POCSAG RIC " + m.ric).setView(k.scroll(body))
            .setNeutralButton(tr("COPIER")) { _, _ ->
                (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("POCSAG", m.text))
                k.toast(tr("Message copié"))
            }
            .setNegativeButton(tr("SUPPRIMER")) { _, _ -> delete(m) }
            .setPositiveButton(tr("FERMER"), null)
            .show()
    }

    fun showById(id: String) {
        val m = engine?.pocsag?.messages()?.firstOrNull { it.id == id } ?: return
        detail(m)
    }

    private fun askDelete(m: PocsagMsg) {
        val t = if (m.text.length > 80) m.text.substring(0, 80) + "…" else m.text
        k.confirm(tr("Supprimer le message ?"), m.time + " · RIC " + m.ric + "\n" + t, tr("SUPPRIMER")) { delete(m) }
    }

    private fun delete(m: PocsagMsg) {
        engine?.pocsag?.delete(m.id)
        refresh()
        k.toast(tr("Message supprimé"))
    }

    // ------------------------------------------------------------------ Emission

    private fun onSend() {
        val p = engine?.pocsag ?: return
        if (p.sending) { p.stop(); return }
        val ric = k.field(p.ric, "RIC", InputType.TYPE_CLASS_NUMBER, mono = true)
        val msg = k.field(p.msg, tr("Message (vide = bip seul)"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val func = k.choice(listOf("0", "1", "2", "3"), p.func) {}
        val baud = k.choice(listOf("512", "1200", "2400"), p.baudIdx) {}
        val type = k.choice(listOf(tr("Alphanum."), tr("Numérique")), if (p.numeric) 1 else 0) {}
        val level = k.choice(listOf("40 %", "60 %", "80 %", "100 %"), p.levelIdx) {}
        val pre = k.switchRow(tr("Pré-compensation (pré-accentuation)"), p.precomp) {}
        val inv = k.switchRow(tr("Inverser la polarité"), p.invert) {}
        val afsk = k.switchRow(tr("Modulation AFSK 1200/2200 Hz (entre postes Benshi)"), p.afsk) {}
        val (tx, txColor) = p.txState()
        val body = k.vbox(k.dp(18)).apply {
            fun cap(s: String) = addView(k.label(s), lp().apply { topMargin = k.dp(12); bottomMargin = k.dp(6) })
            cap(tr("RIC (adresse du récepteur)")); addView(ric)
            cap(tr("Message")); addView(msg)
            cap(tr("Fonction")); addView(func)
            cap(tr("Débit (bauds)")); addView(baud)
            cap(tr("Type")); addView(type)
            cap(tr("Niveau audio")); addView(level)
            addView(pre); addView(inv); addView(afsk)
            addView(k.text(tx, 12.5f, txColor, mono = true), lp().apply { topMargin = k.dp(8) })
            addView(k.note(tr("Réservé aux radioamateurs sur une fréquence autorisée (ex. DAPNET 439,9875 MHz), bandes 144–146 / 430–440 MHz.")))
        }
        k.dialog().setTitle(tr("Émettre un message POCSAG")).setView(k.scroll(body))
            .setNegativeButton(tr("ANNULER"), null)
            .setPositiveButton(tr("ÉMETTRE")) { _, _ ->
                p.ric = ric.text.toString(); p.msg = msg.text.toString(); p.func = func.index; p.baudIdx = baud.index
                p.numeric = type.index == 1; p.levelIdx = level.index; p.precomp = pre.on; p.invert = inv.on; p.afsk = afsk.on
                val (err, what) = p.check()
                if (err != null) { k.alert(err, "POCSAG"); return@setPositiveButton }
                k.confirm(tr("Émettre le message POCSAG ?"), what + "\n\n" + tr("Je confirme être radioamateur et émettre sur une fréquence autorisée."), tr("ÉMETTRE")) {
                    k.err(p.send())
                }
            }.show()
    }
}
