/*
 * HTMOD Android : canaux et frequence : groupe de canaux du poste, canaux memoire (toucher : mettre sur
 * le VFO A / B, modifier), ajout d'une frequence dans un canal libre, renommage du groupe, accord sur
 * une frequence libre (mode frequence du poste, rien n'est ecrit en memoire) et prereglages.
 */
package com.htmod

import android.text.InputType
import android.view.Gravity
import android.view.View

class ChannelsScreen(act: MainActivity) : Screen(act) {
    override val title = tr("Canaux et fréquence")
    private val group = k.key(tr("GROUPE ›"), small = true) { pickGroup() }
    private val chState = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val list = k.vbox()
    private val freq = k.field(Prefs.get("vfoFreq", ""), "439.9875", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, mono = true)
    private val tuneState = k.text(" ", 12.5f, Col.CYAN, mono = true)
    private var shownKey = ""

    override val view: View = k.scroll(k.vbox(k.dp(12)).apply {
        addView(k.card().apply {
            addView(k.label(tr("Changer de fréquence (réception et émission)")), lp().apply { bottomMargin = k.dp(8) })
            val row = k.hbox()
            row.addView(freq, lp(0, WRAP, 1f))
            row.addView(k.key(tr("ACCORDER"), primary = true, small = true) { k.err(engine?.vfo?.tune(freq.text.toString())) }, lp(WRAP, WRAP).apply { leftMargin = k.dp(8) })
            addView(row)
            val pr = k.hbox()
            for ((name, f) in listOf(Pair("DAPNET 439,9875", "439.9875"), Pair("APRS 144,800", "144.800"), Pair("ISS 145,800", "145.800"),
                Pair(tr("Appel 2 m 145,500"), "145.500"), Pair(tr("Appel 70 cm 433,500"), "433.500"))) {
                pr.addView(k.key(name, small = true) { freq.setText(f); k.err(engine?.vfo?.tune(f)) }, lp(WRAP, WRAP).apply { rightMargin = k.dp(6) })
            }
            addView(k.hscroll(pr), lp().apply { topMargin = k.dp(8) })
            addView(tuneState, lp().apply { topMargin = k.dp(8) })
            addView(k.key(tr("RETOUR AUX CANAUX MÉMOIRE"), small = true) { engine?.vfo?.back() }, lp().apply { topMargin = k.dp(6) })
            addView(k.note(tr("Le mode fréquence du poste est utilisé : aucun canal n'est modifié en mémoire. L'émission (PTT, POCSAG, SSTV) se fait sur la fréquence accordée.")))
        })
        addView(k.card().apply {
            val top = k.hbox()
            top.addView(k.label(tr("Groupes et canaux mémoire")), lp(0, WRAP, 1f))
            top.addView(group)
            addView(top)
            addView(chState, lp().apply { topMargin = k.dp(6); bottomMargin = k.dp(8) })
            addView(list)
            val keys = k.hbox()
            keys.addView(k.key(tr("AJOUTER UNE FRÉQUENCE"), small = true) { addChannel() }, lp(0, WRAP, 1f))
            keys.addView(k.key(tr("RENOMMER"), small = true) { renameGroup() }, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            addView(keys, lp().apply { topMargin = k.dp(10) })
            addView(k.note(tr("Touchez un canal pour le mettre sur le VFO A ou B, ou le modifier (nom, fréquences, sous-tons, puissance, audio plat pour le POCSAG…).")))
        })
    })

    private fun pickGroup() {
        val v = engine?.vfo ?: return
        val gs = v.groups()
        if (gs.isEmpty()) { k.alert(tr("Connectez d'abord le poste.")); return }
        k.pick(tr("Groupe de canaux"), gs) { v.group(it) }
    }

    private fun renameGroup() {
        val e = engine ?: return
        val r = e.radio
        if (!r.isRunning) { k.alert(tr("Connectez d'abord le poste.")); return }
        val f = k.field(r.regionName(r.currentRegion()))
        k.dialog().setTitle(tr("Renommer le groupe ") + (r.currentRegion() + 1)).setView(k.vbox(k.dp(18)).apply { addView(f) })
            .setNegativeButton(tr("ANNULER"), null)
            .setPositiveButton(tr("RENOMMER")) { _, _ -> k.err(e.vfo.renameGroup(f.text.toString())) }.show()
    }

    private fun channelMenu(c: VfoMod.Ch) {
        val v = engine?.vfo ?: return
        k.pick(tr("Canal ") + (c.ch + 1) + if (c.name.isEmpty()) "" else " · " + c.name,
            listOf(tr("Mettre sur le VFO A"), tr("Mettre sur le VFO B"), tr("Modifier le canal…"))) { i ->
            when (i) {
                0 -> k.err(v.assign(1, c.ch))
                1 -> k.err(v.assign(2, c.ch))
                else -> edit(c.ch, false)
            }
        }
    }

    private fun addChannel() {
        val e = engine ?: return
        if (!e.radio.isRunning) { k.alert(tr("Connectez d'abord le poste.")); return }
        val free = e.vfo.firstFree()
        if (free < 0) {
            k.alert(if (e.radio.channelCount() == 0) tr("Canaux pas encore lus, réessayez dans un instant.")
            else tr("Plus de canal libre dans ce groupe : modifiez un canal existant ou changez de groupe."))
            return
        }
        edit(free, true)
    }

    /** Fenetre de modification d'un canal memoire, puis ecriture dans le poste. */
    private fun edit(ch: Int, isNew: Boolean) {
        val v = engine?.vfo ?: return
        val c = v.channel(ch) ?: run { k.alert(tr("Canal pas encore lu par le poste, réessayez dans un instant.")); return }
        if (isNew) { c.wide = true; c.maxPower = true; c.medPower = false; c.txDisable = false; c.rxMod = 0; c.txMod = 0; c.rxSub = 0; c.txSub = 0 }
        val num = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val name = k.field(c.name, tr("Nom (10 caractères au plus)"))
        val rx = k.field(if (c.rxHz > 0) VfoMod.mhz(c.rxHz) else "", tr("Réception (MHz)"), num, true)
        val tx = k.field(if (c.txHz > 0) VfoMod.mhz(c.txHz) else "", tr("Émission (MHz, vide = réception)"), num, true)
        val rxT = k.field(Radio.Channel.subText(c.rxSub), tr("Sous-ton réception (88.5 ou D023)"))
        val txT = k.field(Radio.Channel.subText(c.txSub), tr("Sous-ton émission (88.5 ou D023)"))
        val power = k.choice(listOf(tr("HAUTE"), tr("MOYENNE"), tr("BASSE")), if (c.maxPower) 0 else if (c.medPower) 1 else 2) {}
        val bw = k.choice(listOf("25 kHz", "12,5 kHz"), if (c.wide) 0 else 1) {}
        val mod = k.choice(listOf("FM", "AM"), if (c.rxMod == 1) 1 else 0) {}
        val scan = k.switchRow(tr("Inclus dans le balayage (scan)"), c.scan) {}
        val txOff = k.switchRow(tr("Émission interdite sur ce canal"), c.txDisable) {}
        val flat = k.switchRow(tr("Audio plat (sans désaccentuation) : POCSAG, SARSAT, sondes"), c.preDeEmphBypass) {}
        val body = k.vbox(k.dp(18)).apply {
            fun cap(s: String) = addView(k.label(s), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            cap(tr("Nom")); addView(name)
            cap(tr("Fréquences")); addView(rx); addView(tx, lp().apply { topMargin = k.dp(6) })
            cap(tr("Sous-tons")); addView(rxT); addView(txT, lp().apply { topMargin = k.dp(6) })
            cap(tr("Puissance d'émission")); addView(power)
            cap(tr("Largeur de bande")); addView(bw)
            cap(tr("Modulation")); addView(mod)
            addView(scan); addView(txOff); addView(flat)
        }
        val r = engine?.radio ?: return
        k.dialog().setTitle((if (isNew) tr("Nouvelle fréquence · canal ") else tr("Canal ")) + (ch + 1) + tr(" · groupe ") + (r.currentRegion() + 1))
            .setView(k.scroll(body))
            .setNegativeButton(tr("ANNULER"), null)
            .setPositiveButton(tr("ENREGISTRER DANS LE POSTE")) { _, _ ->
                k.err(v.write(c, name.text.toString(), rx.text.toString(), tx.text.toString(), rxT.text.toString(), txT.text.toString(),
                    power.index, bw.index == 0, mod.index == 1, scan.on, txOff.on, flat.on))
            }.show()
    }

    override fun refresh() {
        val e = engine ?: return
        val v = e.vfo
        val r = e.radio
        tuneState.text = v.tuneState()
        val gs = v.groups()
        val cur = if (r.isRunning) r.currentRegion() else -1
        group.setLabel(if (cur in gs.indices) gs[cur] + "  ›" else tr("GROUPE ›"))
        val chs = v.channels()
        chState.text = if (!r.isRunning) tr("Poste non connecté") else chs.size.toString() + tr(" canal(aux) dans le groupe") + if (cur >= 0) " " + (cur + 1) else ""
        val key = chs.joinToString("|") { "${it.ch};${it.name};${it.rx};${it.tx};${it.vfo}" }
        if (key == shownKey) return
        shownKey = key
        list.removeAllViews()
        for (c in chs) {
            val row = k.hbox(Gravity.CENTER_VERTICAL).apply {
                setPadding(k.dp(10), k.dp(10), k.dp(10), k.dp(10))
                background = round(Col.WELL, k.dp(10).toFloat(), k.dp(1), if (c.vfo.isNotEmpty() && !c.vfo.startsWith("(")) Col.AMBER else Col.EDGE)
                setOnClickListener { channelMenu(c) }
            }
            row.addView(k.text((c.ch + 1).toString(), 14f, Col.MUTED, mono = true), lp(k.dp(34), WRAP))
            val mid = k.vbox()
            mid.addView(k.text(c.name.ifEmpty { "—" }, 16f, Col.TEXT, bold = true))
            mid.addView(k.text("↓ " + c.rx + "   ↑ " + c.tx, 12.5f, Col.TEXT2, mono = true))
            row.addView(mid, lp(0, WRAP, 1f))
            row.addView(k.text(c.vfo, 14f, Col.AMBER, bold = true))
            list.addView(row, lp().apply { bottomMargin = k.dp(6) })
        }
    }
}
