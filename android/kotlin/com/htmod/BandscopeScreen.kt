/*
 * HTMOD Android : bandscope : niveau (S-metre) a chaque pas du balayage, crete maintenue, cascade des
 * balayages ; toucher = lecture de la frequence, double-appui = ecouter cette frequence.
 */
package com.htmod

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale

class BandscopeView(c: Context, private val onPark: (Long) -> Unit) : View(c) {
    var bs: BandscopeMod? = null
    private var touchX = -1f
    private val grid = Paint().apply { color = Col.alpha(Col.EDGE, 0xA0) }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.MUTED; textSize = c.dp(10).toFloat(); typeface = MONO }
    private val bar = Paint()
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.AMBER; strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(8f, 10f), 0f) }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.alpha(Col.BG, 0xE0) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.TEXT; textSize = c.dp(13).toFloat(); typeface = MONO }

    private val gestures = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { touchX = e.x; invalidate(); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean { touchX = e2.x; invalidate(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { freqAt(e.x)?.let(onPark); return true }
    })

    init { background = round(Col.WELL, c.dp(12).toFloat(), c.dp(1), Col.EDGE) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        return gestures.onTouchEvent(e)
    }

    private fun x0() = context.dp(34).toFloat()
    private fun pw() = width - x0() - context.dp(8)

    private fun freqAt(x: Float): Long? {
        val b = bs ?: return null
        if (b.centerHz <= 0) return null
        val n = 2 * b.halfSpanHz / b.stepHz + 1
        val i = Math.round((x - x0()) / pw() * (n - 1)).coerceIn(0, n - 1)
        return b.freqAt(i)
    }

    override fun onDraw(g: Canvas) {
        val b = bs ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val x0 = x0()
        val pw = pw()
        val specTop = context.dp(16).toFloat()
        val specH = h * 0.5f
        val axisY = specTop + specH
        val wfTop = axisY + context.dp(26)
        val lv = b.levels
        val pk = b.peaks
        val sq = b.squelch
        val n = if (lv.isNotEmpty()) lv.size else 2 * b.halfSpanHz / b.stepHz + 1
        for (s in 0..15 step 3) {
            val y = axisY - s / 15f * specH
            g.drawLine(x0, y, x0 + pw, y, grid)
            g.drawText(BandscopeMod.sUnits(s), 4f, y + 4, txt)
        }
        val c = b.centerHz
        if (c <= 0) {
            big.color = Col.MUTED
            g.drawText(tr("Connectez le poste puis touchez BALAYER."), x0, specTop + 40, big)
            return
        }
        val tick = if (b.halfSpanHz >= 500_000) 200_000 else if (b.halfSpanHz >= 250_000) 100_000 else 50_000
        var f = ((c - b.halfSpanHz) / tick + 1) * tick
        while (f <= c + b.halfSpanHz) {
            val x = x0 + ((f - (c - b.halfSpanHz)) / (2.0 * b.halfSpanHz) * pw).toFloat()
            g.drawLine(x, specTop, x, axisY, grid)
            val l = String.format(Locale.ROOT, "%.2f", f / 1e6)
            val lw = txt.measureText(l)
            if (x - lw / 2 > x0 - 4 && x + lw / 2 < x0 + pw + 4) g.drawText(l, x - lw / 2, axisY + context.dp(14), txt)
            f += tick
        }
        val bw = pw / Math.max(1, n - 1)
        for (i in lv.indices) {
            val x = x0 + i * bw - bw / 2
            val wb = Math.max(1f, bw - if (bw > 4) 1 else 0)
            if (pk.size == lv.size && pk[i] > 0) {
                bar.color = Col.alpha(Col.AMBER, 0x70)
                val yp = axisY - pk[i] / 15f * specH
                g.drawRect(x, yp, x + wb, yp + 3, bar)
            }
            if (lv[i] < 0) continue
            val hh = Math.max(1f, lv[i] / 15f * specH)
            val col = if (sq.size == lv.size && sq[i]) Col.AMBER else Col.CYAN
            bar.color = Col.alpha(col, 0x50)
            g.drawRect(x, axisY - hh, x + wb, axisY, bar)
            bar.color = col
            g.drawRect(x, axisY - hh, x + wb, axisY - hh + Math.min(3f, hh), bar)
        }
        val xc = x0 + pw / 2
        g.drawLine(xc, specTop - 8, xc, h, dash)
        if (b.running && b.cursor >= 0) {
            bar.color = Col.alpha(Col.CYAN, 0xA0)
            val x = x0 + b.cursor * bw
            g.drawRect(x - 1, specTop, x + 1, axisY, bar)
        }
        if (b.parkedHz != 0L) {
            bar.color = Col.GREEN
            val x = x0 + ((b.parkedHz - (c - b.halfSpanHz)) / (2.0 * b.halfSpanHz) * pw).toFloat()
            g.drawRect(x - 2, specTop, x + 2, axisY, bar)
        }
        // cascade (le balayage le plus recent en haut)
        val rowH = context.dp(4).toFloat()
        var y = wfTop
        for (hl in b.history()) {
            if (y + rowH > h - 4) break
            val cw = pw / hl.size
            for (i in hl.indices) {
                bar.color = BandscopeMod.levelColor(hl[i])
                g.drawRect(x0 + i * cw, y, x0 + (i + 1) * cw + 0.5f, y + rowH, bar)
            }
            y += rowH
        }
        // lecture au doigt
        if (touchX >= x0 && touchX <= x0 + pw) {
            val fz = freqAt(touchX) ?: return
            val i = ((fz - (c - b.halfSpanHz)) / b.stepHz).toInt()
            val l = Engine.mhz(fz) + " MHz" + if (i in lv.indices && lv[i] >= 0) "  " + BandscopeMod.sUnits(lv[i]) else ""
            bar.color = Col.alpha(Col.TEXT, 0x70)
            g.drawRect(touchX - 1, specTop, touchX + 1, axisY, bar)
            val lw = big.measureText(l) + 20
            val lx = Math.min(touchX + 10, w - lw - 4)
            g.drawRoundRect(RectF(lx, specTop + 6, lx + lw, specTop + 6 + big.textSize * 1.8f), 10f, 10f, box)
            big.color = Col.TEXT
            g.drawText(l, lx + 10, specTop + 6 + big.textSize * 1.3f, big)
        }
    }
}

class BandscopeScreen(act: MainActivity) : Screen(act) {
    override val title = "Bandscope"
    private val center = k.text("—", 24f, Col.TEXT, bold = true)
    private val info = k.text(" ", 12.5f, Col.MUTED, mono = true)
    private val start = k.key(tr("BALAYER"), primary = true) { toggle() }
    private val back = k.key(tr("RETOUR AU CANAL"), small = true) { engine?.bandscope?.backToChannel() }
    private val spans = k.choice(BandscopeMod.SPANS.map { if (it >= 1_000_000) "±" + it / 1_000_000 + " MHz" else "±" + it / 1000 + " kHz" }, -1) {
        engine?.bandscope?.setSpan(BandscopeMod.SPANS[it])
    }
    private val steps = k.choice(BandscopeMod.STEPS.map { if (it % 1000 == 0) "${it / 1000}" else String.format(Locale.ROOT, "%.2f", it / 1000.0).trimEnd('0') }, -1) {
        engine?.bandscope?.setStep(BandscopeMod.STEPS[it])
    }
    private val bsView = BandscopeView(act) { f ->
        engine?.bandscope?.park(f)
        k.toast(tr("Écoute de ") + Engine.mhz(f) + " MHz")
    }

    override val view: View = k.vbox(k.dp(12)).apply {
        addView(k.card().apply {
            addView(k.label(tr("Bandscope · fréquence centrale")))
            addView(center)
            addView(info)
            val row = k.hbox()
            row.addView(start, lp(0, WRAP, 1f))
            row.addView(back, lp(WRAP, WRAP).apply { leftMargin = k.dp(8) })
            addView(row, lp().apply { topMargin = k.dp(10) })
            addView(k.label(tr("Largeur")), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            addView(spans)
            addView(k.label(tr("Pas (kHz)")), lp().apply { topMargin = k.dp(10); bottomMargin = k.dp(6) })
            addView(steps)
        })
        addView(bsView, lp(MATCH, 0, 1f))
        addView(k.note(tr("Toucher : lire la fréquence. Double-appui : écouter cette fréquence. Pendant le balayage, SARSAT, sondes, POCSAG et SSTV sont en pause.")))
    }

    private fun toggle() {
        val b = engine?.bandscope ?: return
        if (b.running) b.stop(true) else k.err(b.start())
    }

    override fun refresh() {
        val b = engine?.bandscope ?: return
        b.refreshCenter()
        bsView.bs = b
        center.text = if (b.centerHz > 0) Engine.mhz(b.centerHz) + " MHz" else "—"
        info.text = b.info()
        start.setLabel(if (b.running) tr("ARRÊTER") else tr("BALAYER"))
        back.visibility = if (!b.running && b.parkedHz != 0L) View.VISIBLE else View.GONE
        spans.set(BandscopeMod.SPANS.indexOf(b.halfSpanHz))
        steps.set(BandscopeMod.STEPS.indexOf(b.stepHz))
        bsView.invalidate()
    }
}
