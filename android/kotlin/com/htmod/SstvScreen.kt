/*
 * HTMOD Android : SSTV : image recue (en direct pendant la reception), galerie des receptions,
 * reception on/off ; emission d'une image du telephone ou de la mire (mode, texte incruste, niveau).
 */
package com.htmod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File

class SstvScreen(act: MainActivity) : Screen(act) {
    override val title = "SSTV"
    private val state = k.text(" ", 12.5f, Col.CYAN, mono = true)
    private val rxName = k.text(" ", 12f, Col.MUTED, mono = true)
    private val rxImage = ImageView(act).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Col.WELL) }
    private val rxOn = k.switchRow(tr("Réception SSTV"), Prefs.getBoolean("sstvRx", true)) { engine?.sstv?.setRx(it) }
    private val gallery = k.hbox()
    private val txImage = ImageView(act).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Col.WELL) }
    private val modeKey = k.key(" ", small = true) { pickMode() }
    private val textField = k.field(Prefs.get("sstvText", ""), tr("Texte incrusté (indicatif)"))
    private val level = k.choice(listOf("40 %", "60 %", "80 %", "100 %"), Prefs.getInt("sstvLevelIdx", 2)) { engine?.sstv?.levelIdx = it }
    private val txInfo = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val txFreq = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val send = k.key(tr("ÉMETTRE L'IMAGE"), primary = true) { onSend() }
    private var rxV = -1L
    private var txV = -1L
    private var galleryKey = ""
    private var rxBitmap: Bitmap? = null
    private var txBitmap: Bitmap? = null

    override val view: View = k.scroll(k.vbox(k.dp(12)).apply {
        addView(k.card().apply {
            addView(k.label(tr("Réception · Robot, Martin, Scottie, PD")))
            addView(state, lp().apply { topMargin = k.dp(4) })
            addView(rxImage, lp().apply { topMargin = k.dp(10) })
            addView(rxName, lp().apply { topMargin = k.dp(6) })
            addView(rxOn)
            addView(k.key(tr("TERMINER L'IMAGE EN COURS"), small = true) { engine?.sstv?.finish() })
        })
        addView(k.card().apply {
            addView(k.label(tr("Images reçues")), lp().apply { bottomMargin = k.dp(8) })
            addView(k.hscroll(gallery))
        })
        addView(k.card().apply {
            addView(k.label(tr("Émission")))
            addView(txImage, lp().apply { topMargin = k.dp(10) })
            val row = k.hbox()
            row.addView(k.key(tr("CHOISIR UNE IMAGE"), small = true) { pickImage() }, lp(0, WRAP, 1f))
            row.addView(k.key(tr("MIRE"), small = true) { engine?.sstv?.testCard() }, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            addView(row, lp().apply { topMargin = k.dp(10) })
            addView(k.label(tr("Mode")), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            addView(modeKey)
            addView(k.label(tr("Texte incrusté (indicatif)")), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            val tr0 = k.hbox()
            tr0.addView(textField, lp(0, WRAP, 1f))
            tr0.addView(k.key("OK", small = true) { engine?.sstv?.setText(textField.text.toString()) }, lp(WRAP, WRAP).apply { leftMargin = k.dp(6) })
            addView(tr0)
            addView(k.label(tr("Niveau audio")), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            addView(level)
            addView(txInfo, lp().apply { topMargin = k.dp(10) })
            addView(txFreq, lp().apply { bottomMargin = k.dp(10) })
            addView(send)
            addView(k.note(tr("Émission sur la fréquence du canal ou celle accordée, réservée aux radioamateurs. Pensez au temps d'émission maximal du poste (TOT) pour les modes longs.")))
        })
    })

    private fun pickMode() {
        val s = engine?.sstv ?: return
        k.pick(tr("Mode SSTV"), Sstv.MODES.map { it.name + "  ·  " + it.width + "×" + it.height + "  ·  " + Math.round(it.seconds()) + " s" }) { s.setMode(Sstv.MODES[it]) }
    }

    private fun pickImage() {
        act.pickFile("image/*") { data, _ -> k.err(engine?.sstv?.setImage(data)) }
    }

    private fun onSend() {
        val s = engine?.sstv ?: return
        if (s.sending) { s.stop(); return }
        s.setText(textField.text.toString())
        val (err, what) = s.check()
        if (err != null) { k.alert(err, "SSTV"); return }
        k.confirm(tr("Émettre l'image SSTV ?"), what + "\n\n" + tr("Je confirme être radioamateur et émettre sur une fréquence autorisée."), tr("ÉMETTRE")) {
            k.err(s.send())
        }
    }

    override fun refresh() {
        val s = engine?.sstv ?: return
        val (t, c) = s.state()
        state.text = t
        state.setTextColor(c)
        rxName.text = s.rxName
        rxOn.on = s.rxOn
        if (s.rxVersion != rxV) {
            rxV = s.rxVersion
            val img = s.rxImg
            rxBitmap?.recycle()
            rxBitmap = img?.toBitmap()
            rxImage.setImageBitmap(rxBitmap)
            rxImage.minimumHeight = if (img == null) k.dp(120) else 0
        }
        if (s.txVersion != txV) {
            txV = s.txVersion
            txBitmap?.recycle()
            txBitmap = s.txImg?.toBitmap()
            txImage.setImageBitmap(txBitmap)
        }
        modeKey.setLabel(s.txMode.name + "  ›")
        txInfo.text = s.txInfo
        txInfo.setTextColor(s.txInfoColor)
        val (ft, fc) = s.txFreq()
        txFreq.text = ft
        txFreq.setTextColor(fc)
        send.setLabel(if (s.sending) tr("ARRÊTER L'ÉMISSION") else tr("ÉMETTRE L'IMAGE"))
        val files = s.gallery()
        val key = files.joinToString("|") { it.name }
        if (key != galleryKey) {
            galleryKey = key
            gallery.removeAllViews()
            if (files.isEmpty()) gallery.addView(k.text(tr("Aucune image pour l'instant."), 13f, Col.MUTED))
            Thread({
                for (f in files.take(40)) {
                    val th = thumb(f) ?: continue
                    act.runOnUiThread {
                        if (galleryKey != key) return@runOnUiThread
                        gallery.addView(ImageView(act).apply {
                            setImageBitmap(th)
                            setOnClickListener { s.show(f) }
                        }, LinearLayout.LayoutParams(k.dp(120), k.dp(92)).apply { rightMargin = k.dp(6) })
                    }
                }
            }, "htmod-sstv-galerie").start()
        }
    }

    private fun thumb(f: File): Bitmap? = try {
        val b = BitmapFactory.decodeFile(f.absolutePath)
        if (b == null) null else Bitmap.createScaledBitmap(b, 120 * 2, 92 * 2, true).also { if (it !== b) b.recycle() }
    } catch (e: Exception) { null }
}
