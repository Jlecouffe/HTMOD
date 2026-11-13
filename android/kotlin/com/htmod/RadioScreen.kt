/*
 * HTMOD Android : onglet RADIO : poste et connexion, mini-spectre, canal en reception et mode de
 * decodage (AUTO / SARSAT / SONDE), VFO A / B et double veille, volume et squelch du poste, PTT avec le
 * micro du telephone, options d'ecoute.
 */
package com.htmod

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

class RadioScreen(act: MainActivity) : Screen(act) {
    override val title = "RADIO"
    private val model = k.text(tr("Aucun poste"), 24f, Col.TEXT, bold = true)
    private val chip = k.chip()
    private val spectrum = SpectrumView(act)
    private val device = k.text(tr("Poste ›"), 14f, Col.AMBER, mono = true)
    private val connect = k.key(tr("SE CONNECTER"), primary = true) { connect() }
    private val status = k.text(tr("Déconnecté"), 12.5f, Col.MUTED, mono = true)
    private val channel = k.text("–", 26f, Col.TEXT, bold = true)
    private val mode = k.chip()
    private val forced = k.choice(listOf("AUTO", "SARSAT", tr("SONDE")), 0) { HtService.engine?.forcedMode = it }
    private val vfoA = k.text("–", 18f, Col.TEXT, bold = true)
    private val freqA = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val vfoB = k.text("–", 18f, Col.TEXT, bold = true)
    private val freqB = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val dualChip = k.chip()
    private val dual = k.choice(listOf(tr("DOUBLE VEILLE"), tr("UN SEUL VFO")), -1) { i -> k.err(HtService.engine?.vfo?.setDual(i == 0)) }
    private val vol = k.text("–", 24f, Col.TEXT, bold = true)
    private val sq = k.text("–", 24f, Col.TEXT, bold = true)
    private val ptt = k.key(tr("PTT · MAINTENIR POUR ÉMETTRE"), primary = true)
    private val meter = MeterView(act)
    private val pttState = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val gain = k.choice(listOf("50 %", "100 %", "150 %", "200 %"), Prefs.getInt("pttGainIdx", 1)) { HtService.engine?.vfo?.gainIdx = it }
    private val swListen = k.switchRow(tr("Écouter sur le téléphone"), false) { HtService.engine?.listen = it }
    private val swMute = k.switchRow(tr("Couper le son des trames APRS"), Prefs.getBoolean("muteAprs", false)) { Prefs.putBoolean("muteAprs", it) }
    private val swMutePocsag = k.switchRow(tr("Couper le son des trames POCSAG"), Prefs.getBoolean("mutePocsag", false)) { Prefs.putBoolean("mutePocsag", it) }
    private val swWav =k.switchRow(tr("Enregistrer les WAV"), Prefs.getBoolean("saveWav", false)) { HtService.engine?.saveWavOn = it }

    @SuppressLint("ClickableViewAccessibility")
    override val view: View = k.scroll(k.vbox(k.dp(12)).apply {
        // poste
        val top = k.hbox()
        top.addView(k.vbox().apply { addView(k.label("RADIO")); addView(model) }, lp(0, WRAP, 1f))
        top.addView(chip)
        val bottom = k.hbox()
        device.setOnClickListener { pickDevice() }
        bottom.addView(device, lp(0, WRAP, 1f))
        bottom.addView(connect)
        addView(k.card().apply {
            addView(top)
            addView(spectrum, lp(MATCH, k.dp(70)).apply { topMargin = k.dp(10); bottomMargin = k.dp(10) })
            addView(bottom)
            addView(status, lp().apply { topMargin = k.dp(8) })
        })
        // reception
        addView(k.card().apply {
            addView(k.label(tr("Canal en réception")))
            addView(channel)
            addView(mode, lp(WRAP, WRAP).apply { topMargin = k.dp(4); bottomMargin = k.dp(10) })
            addView(k.label(tr("Décodage SARSAT / sondes")), lp().apply { bottomMargin = k.dp(6) })
            addView(forced)
        })
        // VFO
        addView(k.card().apply {
            val ab = k.hbox(Gravity.TOP)
            ab.addView(k.well().apply { addView(k.label("VFO A")); addView(vfoA); addView(freqA) }, lp(0, WRAP, 1f).apply { rightMargin = k.dp(6) })
            ab.addView(k.well().apply { addView(k.label("VFO B")); addView(vfoB); addView(freqB) }, lp(0, WRAP, 1f).apply { leftMargin = k.dp(6) })
            addView(ab)
            addView(dualChip, lp(WRAP, WRAP).apply { topMargin = k.dp(10); bottomMargin = k.dp(10) })
            addView(dual)
            addView(k.note(tr("Le poste surveille le VFO A et le VFO B et bascule sur celui qui reçoit. Les décodeurs suivent le VFO qui reçoit.")))
        })
        // volume, squelch
        addView(k.card().apply {
            val row = k.hbox()
            row.addView(stepper(tr("VOLUME (0–15)"), vol) { HtService.engine?.vfo?.volume(it) }, lp(0, WRAP, 1f).apply { rightMargin = k.dp(6) })
            row.addView(stepper(tr("SQUELCH (0–9)"), sq) { HtService.engine?.vfo?.squelch(it) }, lp(0, WRAP, 1f).apply { leftMargin = k.dp(6) })
            addView(row)
        })
        // PTT
        ptt.minHeight = k.dp(72)
        ptt.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; HtService.engine?.vfo?.pttDown() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; HtService.engine?.vfo?.pttUp() }
            }
            true
        }
        addView(k.card().apply {
            addView(k.label(tr("Émission (micro du téléphone)")), lp().apply { bottomMargin = k.dp(8) })
            addView(ptt)
            addView(meter, lp(MATCH, k.dp(10)).apply { topMargin = k.dp(10) })
            addView(pttState, lp().apply { topMargin = k.dp(6); bottomMargin = k.dp(8) })
            addView(k.label(tr("Gain du micro")), lp().apply { bottomMargin = k.dp(6) })
            addView(gain)
            addView(k.note(tr("Maintenez PTT pour parler : le poste émet sur la fréquence du VFO actif (ou celle accordée) et repasse en réception au relâchement. Coupure de sécurité après 3 minutes.")))
        })
        // options
        addView(k.card().apply {
            addView(k.label(tr("Options")))
            addView(swListen)
            addView(swMute)
            addView(swMutePocsag)
            addView(swWav)
        })
    })

    private fun stepper(title: String, value: View, onStep: (Int) -> Unit): View = k.well().apply {
        addView(k.label(title))
        val row = k.hbox()
        row.addView(value, lp(0, WRAP, 1f))
        row.addView(k.key("−", small = true) { onStep(-1) }, lp(k.dp(48), WRAP))
        row.addView(k.key("+", small = true) { onStep(1) }, lp(k.dp(48), WRAP).apply { leftMargin = k.dp(6) })
        addView(row)
    }

    private fun connect() {
        val e = engine ?: return
        if (e.device == null && !e.radio.isRunning) { pickDevice(); return }
        k.err(e.toggleConnect())
    }

    private fun pickDevice() {
        val e = engine ?: return
        e.refreshDevices(false)
        val ds = e.devices
        if (ds.isEmpty()) {
            k.alert(tr("Aucun poste appairé : appairez le poste dans les réglages Bluetooth du téléphone, puis revenez ici."))
            return
        }
        k.pick(tr("Choisir le poste"), ds.map { it.name + "   " + it.mac }) { i ->
            e.selectDevice(ds[i])
            if (!e.radio.isRunning) k.err(e.toggleConnect())
        }
    }

    override fun refresh() {
        val e = engine ?: return
        val r = e.radio
        val on = r.isRunning
        val d = e.device
        model.text = d?.name ?: tr("Aucun poste")
        device.text = if (d == null) tr("Poste ›") else d.mac + "  ›"
        when {
            e.connected -> chip.set(tr("CONNECTÉ"), Col.CYAN)
            e.reconnecting -> chip.set(tr("NOUVEL ESSAI…"), Col.AMBER)
            on -> chip.set(tr("CONNEXION…"), Col.AMBER)
            else -> chip.set(tr("HORS LIGNE"), Col.MUTED)
        }
        connect.setLabel(if (on || e.reconnecting) tr("DÉCONNECTER") else tr("SE CONNECTER"))
        status.text = e.status
        spectrum.values = e.spectrum.values()
        spectrum.invalidate()
        channel.text = if (on) e.channelName.ifEmpty { "?" } else "–"
        channel.setTextColor(if (e.receiving) Col.CYAN else Col.TEXT)
        val (mt, mc) = e.mode()
        mode.set(mt, mc)
        forced.set(e.forcedMode)
        val v = e.vfo
        val a = if (on) r.channelA() else -1
        val b = if (on) r.channelB() else -1
        vfoA.text = v.label(a); freqA.text = v.freq(a)
        vfoB.text = v.label(b); freqB.text = v.freq(b)
        val dw = r.dualWatch()
        vfoA.setTextColor(if (dw > 0 && r.activeVfo() == 1) Col.CYAN else Col.TEXT)
        vfoB.setTextColor(if (dw > 0 && r.activeVfo() == 2) Col.CYAN else if (dw > 0) Col.TEXT else Col.MUTED)
        val (dt, dc) = v.dualState()
        dualChip.set(dt, dc)
        dual.set(if (!on) -1 else if (dw > 0) 0 else if (dw == 0) 1 else -1)
        vol.text = if (on && r.volume() >= 0) r.volume().toString() else "–"
        sq.text = if (on && r.squelch() >= 0) r.squelch().toString() else "–"
        ptt.setLabel(if (v.pttHeld) tr("ÉMISSION… RELÂCHER") else tr("PTT · MAINTENIR POUR ÉMETTRE"))
        meter.level = if (v.pttHeld) e.transmitter.micLevel else 0
        val (pt, pc) = v.pttState()
        pttState.text = pt
        pttState.setTextColor(pc)
        swListen.on = e.listen
        swWav.on = e.saveWavOn
    }
}
