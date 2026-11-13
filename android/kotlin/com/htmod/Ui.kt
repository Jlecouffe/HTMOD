/*
 * HTMOD Android : boite a outils de l'interface native (theme sombre « shack » de HTMod) : couleurs,
 * cartes, touches, choix segmentes, interrupteurs, champs, listes, dialogues, icones dessinees.
 */
package com.htmod

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Couleurs « Shack » (memes valeurs que Shack.java de la version Windows). */
object Col {
    const val BG = 0xFF0A0E13.toInt()
    const val CARD = 0xFF141B24.toInt()
    const val KEY = 0xFF1A222D.toInt()
    const val EDGE = 0xFF263241.toInt()
    const val WELL = 0xFF070A0E.toInt()
    const val TEXT = 0xFFE8EDF2.toInt()
    const val TEXT2 = 0xFFC3CCD6.toInt()
    const val MUTED = 0xFF8B97A6.toInt()
    const val AMBER = 0xFFF5A524.toInt()
    const val CYAN = 0xFF5FC8E8.toInt()
    const val RED = 0xFFE7625F.toInt()
    const val GREEN = 0xFF6DD69A.toInt()
    const val PURPLE = 0xFFC24DD6.toInt()

    fun alpha(c: Int, a: Int) = (c and 0xFFFFFF) or (a shl 24)
}

val MONO: Typeface = Typeface.MONOSPACE
val BOLD: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Fabrique de vues du theme. */
class K(val c: Context) {
    fun dp(v: Int) = c.dp(v)

    fun text(s: String = "", size: Float = 15f, color: Int = Col.TEXT, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            typeface = if (mono) (if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else MONO) else if (bold) BOLD else Typeface.DEFAULT
        }

    /** Petit titre espace (« RADIO », « RÉCEPTION »…). */
    fun label(s: String, color: Int = Col.MUTED): TextView = text(s.uppercase(), 11.5f, color, mono = true).apply { letterSpacing = 0.14f }

    fun section(s: String): TextView = label(s).apply { setPadding(dp(6), dp(18), 0, dp(8)) }

    fun vbox(pad: Int = 0): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

    fun hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }

    fun card(): LinearLayout = vbox(dp(14)).apply {
        background = round(Col.CARD, dp(18).toFloat(), dp(1), Col.EDGE)
        layoutParams = lp().apply { bottomMargin = dp(10) }
    }

    fun well(): LinearLayout = vbox(dp(10)).apply { background = round(Col.WELL, dp(12).toFloat(), dp(1), Col.EDGE) }

    fun scroll(child: View): ScrollView = ScrollView(c).apply {
        isFillViewport = true
        addView(child)
    }

    fun hscroll(child: View): HorizontalScrollView = HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        addView(child)
    }

    /** Touche : primary = ambre plein ; sinon fond sombre ; setSel(true) = contour ambre. */
    fun key(s: String, primary: Boolean = false, small: Boolean = false, onClick: (() -> Unit)? = null): Key = Key(c, s, primary, small).apply {
        if (onClick != null) setOnClickListener { onClick() }
    }

    /** Choix segmente (une seule touche active). */
    fun choice(labels: List<String>, sel: Int, small: Boolean = true, onSel: (Int) -> Unit): Choice = Choice(this, labels, sel, small, onSel)

    fun switchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit): SwitchRow = SwitchRow(this, label, on, onChange)

    fun field(value: String = "", hint: String = "", type: Int = InputType.TYPE_CLASS_TEXT, mono: Boolean = false): EditText = EditText(c).apply {
        setText(value)
        this.hint = hint
        inputType = type
        setTextColor(Col.TEXT)
        setHintTextColor(Col.alpha(Col.MUTED, 0xA0))
        textSize = 16f
        if (mono) typeface = MONO
        background = round(Col.WELL, dp(10).toFloat(), dp(1), Col.EDGE)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isSingleLine = type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
    }

    fun chip(): Chip = Chip(this)

    fun gap(h: Int): View = View(c).apply { layoutParams = lp(MATCH, dp(h)) }

    fun note(s: String): TextView = text(s, 12.5f, Col.MUTED).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.15f) }

    fun toast(s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

    fun dialog(): AlertDialog.Builder = AlertDialog.Builder(c, android.R.style.Theme_Material_Dialog_Alert)

    fun alert(msg: String, title: String = "HTMod") { dialog().setTitle(title).setMessage(msg).setPositiveButton("OK", null).show() }

    fun confirm(title: String, msg: String, ok: String, onOk: () -> Unit) {
        dialog().setTitle(title).setMessage(msg).setNegativeButton(tr("ANNULER"), null).setPositiveButton(ok) { _, _ -> onOk() }.show()
    }

    /** Affiche le message d'erreur s'il y en a un. */
    fun err(e: String?) { if (e != null) alert(e) }

    fun pick(title: String, items: List<String>, onPick: (Int) -> Unit) {
        dialog().setTitle(title).setItems(items.toTypedArray()) { _, i -> onPick(i) }.show()
    }
}

/** Touche du theme. */
class Key(c: Context, s: String, private val primary: Boolean, small: Boolean) : TextView(c) {
    private var selected0 = false
    private var selColor = Col.AMBER

    init {
        text = s
        gravity = Gravity.CENTER
        typeface = BOLD
        textSize = if (small) 13.5f else 15f
        isAllCaps = false
        val ph = c.dp(if (small) 12 else 16)
        setPadding(ph, c.dp(if (small) 8 else 12), ph, c.dp(if (small) 8 else 12))
        minHeight = c.dp(if (small) 40 else 48)
        isClickable = true
        isFocusable = true
        paint()
    }

    private fun paint() {
        val r = context.dp(12).toFloat()
        val bg = if (primary) round(Col.AMBER, r) else round(Col.KEY, r, context.dp(if (selected0) 2 else 1), if (selected0) selColor else Col.EDGE)
        background = RippleDrawable(ColorStateList.valueOf(Col.alpha(Color.WHITE, 0x30)), bg, null)
        setTextColor(if (primary) Col.BG else if (selected0) selColor else Col.TEXT)
        alpha = if (isEnabled) 1f else 0.45f
    }

    fun setSel(on: Boolean, color: Int = Col.AMBER) {
        if (on == selected0 && color == selColor) return
        selected0 = on
        selColor = color
        paint()
    }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); paint() }

    fun setLabel(s: String) { if (text.toString() != s) text = s }
}

/** Rangee de touches dont une seule est active. */
class Choice(k: K, labels: List<String>, sel: Int, small: Boolean, private val onSel: (Int) -> Unit) : LinearLayout(k.c) {
    private val keys = ArrayList<Key>()
    var index = sel
        private set

    init {
        orientation = HORIZONTAL
        labels.forEachIndexed { i, l ->
            val key = k.key(l, small = small) { set(i); onSel(i) }
            keys.add(key)
            addView(key, LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = k.dp(6) })
        }
        set(sel)
    }

    fun set(i: Int) {
        index = i
        keys.forEachIndexed { j, key -> key.setSel(j == i) }
    }
}

/** Interrupteur avec son libelle. */
class SwitchRow(k: K, label: String, on: Boolean, private val onChange: (Boolean) -> Unit) : LinearLayout(k.c) {
    private val sw = Switch(k.c)
    private var silent = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, k.dp(6), 0, k.dp(6))
        addView(k.text(label, 15f, Col.TEXT2), LayoutParams(0, WRAP, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(states, intArrayOf(Col.AMBER, Col.MUTED))
        sw.trackTintList = ColorStateList(states, intArrayOf(Col.alpha(Col.AMBER, 0x70), Col.EDGE))
        sw.isChecked = on
        sw.setOnCheckedChangeListener { _, v -> if (!silent) onChange(v) }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    var on: Boolean
        get() = sw.isChecked
        set(v) { if (sw.isChecked != v) { silent = true; sw.isChecked = v; silent = false } }
}

/** Pastille d'etat (point de couleur + texte). */
class Chip(k: K) : LinearLayout(k.c) {
    private val dot = View(k.c)
    private val t = k.text("", 12f, Col.TEXT2, mono = true)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(k.dp(10), k.dp(6), k.dp(12), k.dp(6))
        background = round(Col.WELL, k.dp(20).toFloat(), k.dp(1), Col.EDGE)
        addView(dot, LayoutParams(k.dp(9), k.dp(9)).apply { rightMargin = k.dp(8) })
        addView(t)
    }

    fun set(text: String, color: Int) {
        if (t.text.toString() != text) t.text = text
        dot.background = round(color, 50f)
        t.setTextColor(if (color == Col.MUTED) Col.MUTED else Col.TEXT2)
    }
}

/** Mini-spectre audio (cyan, rempli, repere ambre sur le pic), comme SpectrumStrip. */
class SpectrumView(c: Context) : View(c) {
    var values = FloatArray(Spectrum.COLS)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.CYAN; style = Paint.Style.STROKE; strokeWidth = c.dp(1.5f).toFloat() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.alpha(Col.CYAN, 0x26) }
    private val peakP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Col.AMBER; textSize = c.dp(10).toFloat(); typeface = MONO }

    init { background = round(Col.WELL, c.dp(12).toFloat(), c.dp(1), Col.EDGE) }

    override fun onDraw(g: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val base = h * 0.88f
        val t = System.currentTimeMillis()
        val p = Path()
        val a = Path()
        var peak = 0
        for (i in values.indices) if (values[i] > values[peak]) peak = i
        for (i in values.indices) {
            val x = i / (values.size - 1f) * w
            val y = base - h * 0.03f * Math.abs(Math.sin(x * 0.11 + t / 110.0)).toFloat() - values[i] * h * 0.78f
            if (i == 0) { p.moveTo(x, y); a.moveTo(x, h); a.lineTo(x, y) } else { p.lineTo(x, y); a.lineTo(x, y) }
        }
        a.lineTo(w, h); a.close()
        g.drawPath(a, fill)
        g.drawPath(p, line)
        if (values[peak] > 0.15f) {
            val px = peak / (values.size - 1f) * w
            g.drawLine(px, 0f, px, h, peakP)
            val f = String.format(java.util.Locale.ROOT, "%.1f kHz", peak * 16.0 / (values.size - 1))
            g.drawText(f, Math.min(px + 6, w - peakP.measureText(f) - 6), peakP.textSize + 4, peakP)
        }
    }
}

/** Barre de niveau (micro du PTT). */
class MeterView(c: Context) : View(c) {
    var level = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(g: Canvas) {
        val h = height.toFloat()
        p.color = Col.WELL
        g.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p)
        val fw = width * Math.min(1f, level / 100f)
        if (fw > 0) {
            p.color = if (level > 90) Col.RED else if (level > 60) Col.AMBER else Col.GREEN
            g.drawRoundRect(RectF(0f, 0f, fw, h), h / 2, h / 2, p)
        }
    }
}

/** Icones au trait de la barre d'onglets (radio, pager, epingle, liste, grille). */
class IconView(c: Context, private val kind: String) : View(c) {
    var color = Col.MUTED
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(g: Canvas) {
        val s = Math.min(width, height) / 24f
        g.save()
        g.translate((width - 24 * s) / 2, (height - 24 * s) / 2)
        g.scale(s, s)
        p.strokeWidth = 1.8f
        p.color = color
        when (kind) {
            "radio" -> {
                g.drawRoundRect(RectF(6f, 7f, 18f, 22f), 2.5f, 2.5f, p)
                g.drawLine(9f, 7f, 9f, 2f, p)
                g.drawLine(9f, 11f, 15f, 11f, p)
                g.drawCircle(12f, 17f, 2.2f, p)
            }
            "pager" -> {
                g.drawRoundRect(RectF(2f, 5f, 22f, 19f), 5f, 5f, p)
                g.drawLine(6f, 10f, 18f, 10f, p)
                g.drawLine(6f, 14f, 13f, 14f, p)
            }
            "pin" -> {
                val path = Path()
                path.moveTo(12f, 22f)
                path.cubicTo(11f, 18f, 5f, 16.5f, 5f, 10f)
                path.cubicTo(5f, 2f, 19f, 2f, 19f, 10f)
                path.cubicTo(19f, 16.5f, 13f, 18f, 12f, 22f)
                g.drawPath(path, p)
                g.drawCircle(12f, 10f, 2.6f, p)
            }
            "list" -> {
                for (y in floatArrayOf(6f, 12f, 18f)) { g.drawLine(8f, y, 21f, y, p); g.drawCircle(4f, y, 0.9f, p) }
            }
            else -> {
                for (x in floatArrayOf(3f, 13.5f)) for (y in floatArrayOf(3f, 13.5f)) g.drawRoundRect(RectF(x, y, x + 7.5f, y + 7.5f), 2f, 2f, p)
            }
        }
        g.restore()
    }
}

/** Ecran de l'appli (onglet ou sous-ecran de PLUS). */
abstract class Screen(val act: MainActivity) {
    val k = K(act)
    abstract val title: String
    abstract val view: View
    /** Rafraichissement periodique (fil de l'interface, ~2 fois par seconde). */
    open fun refresh() {}
    open fun onShow() {}
    val engine: Engine? get() = HtService.engine
}

/** Cadre a etiquette « TITRE » au-dessus d'un contenu. */
fun K.titled(title: String, vararg views: View): LinearLayout = card().apply {
    addView(label(title), lp().apply { bottomMargin = dp(8) })
    for (v in views) addView(v)
}

/** Detecteur d'appui long sur une vue de liste, sans selection de texte. */
fun View.onHold(ms: Long = 550, action: () -> Unit) {
    var fired = false
    val run = Runnable { fired = true; performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); action() }
    setOnTouchListener { v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { fired = false; v.postDelayed(run, ms) }
            MotionEvent.ACTION_MOVE -> {}
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.removeCallbacks(run); if (fired) return@setOnTouchListener true }
        }
        false
    }
}

fun FrameLayout.LayoutParams.gravity(g: Int): FrameLayout.LayoutParams { gravity = g; return this }
