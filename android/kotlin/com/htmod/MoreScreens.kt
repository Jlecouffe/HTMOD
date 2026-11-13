/*
 * HTMOD Android : onglet PLUS et ses ecrans simples : menu, decodages SARSAT / radiosondes, journal,
 * reglages (langue, options, decodage de fichiers WAV, dossier des resultats, mode d'emploi).
 */
package com.htmod

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menu des autres fonctions. */
class MoreScreen(act: MainActivity) : Screen(act) {
    override val title = tr("PLUS")

    override val view: View = k.scroll(k.vbox(k.dp(12)).apply {
        for ((id, name, sub) in listOf(
            Triple("canaux", tr("Canaux et fréquence"), tr("Groupes, canaux mémoire, accord libre, ajout et modification")),
            Triple("decodages", tr("Décodages SARSAT / sondes"), tr("Balises de détresse 406 MHz et radiosondes")),
            Triple("bandscope", "Bandscope", tr("Balayage ±100 kHz à ±1 MHz avec le S-mètre du poste")),
            Triple("sstv", "SSTV", tr("Réception d'images, galerie, émission")),
            Triple("satellites", tr("Satellites"), tr("Passages, Doppler, suivi avec le poste")),
            Triple("journal", tr("Journal"), tr("Événements de la liaison et des décodeurs")),
            Triple("reglages", tr("Réglages"), tr("Langue, options, fichiers WAV, dossier des résultats")))) {
            val c = k.card().apply {
                isClickable = true
                setOnClickListener { act.open(id) }
                val row = k.hbox()
                val t = k.vbox()
                t.addView(k.text(name, 18f, Col.TEXT, bold = true))
                t.addView(k.text(sub, 13f, Col.MUTED))
                row.addView(t, lp(0, WRAP, 1f))
                row.addView(k.text("›", 26f, Col.AMBER, bold = true))
                addView(row)
            }
            addView(c)
        }
    })
}

/** Decodages SARSAT et radiosondes. */
class DetectionsScreen(act: MainActivity) : Screen(act) {
    override val title = tr("Décodages")
    private var rows: List<Decoders.Detection> = emptyList()
    private val tf = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT)
    private val mode = k.chip()
    private val empty = k.text(tr("Aucun décodage. Nommez le canal du poste « SARSAT » ou « Radiosonde », ou forcez le mode dans l'onglet RADIO."), 14f, Col.MUTED)
        .apply { setPadding(k.dp(16), k.dp(16), k.dp(16), k.dp(16)) }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val v = (convert as? LinearLayout) ?: k.vbox().apply {
                setPadding(k.dp(14), k.dp(10), k.dp(14), k.dp(10))
                val top = k.hbox()
                top.addView(k.text("", 16f, Col.TEXT, bold = true), lp(0, WRAP, 1f))
                top.addView(k.text("", 12f, Col.MUTED, mono = true))
                addView(top)
                addView(k.text("", 13f, Col.TEXT2))
            }
            val d = rows[p]
            val top = v.getChildAt(0) as LinearLayout
            (top.getChildAt(0) as TextView).apply {
                text = d.kind + "  " + (d.id ?: "") + if (d.count > 1) " ×" + d.count else ""
                setTextColor(if (d.kind == "SARSAT") Col.RED else Col.GREEN)
            }
            (top.getChildAt(1) as TextView).text = tf.format(Date(d.receivedMs))
            (v.getChildAt(1) as TextView).text = (if (d.hasPos) Decoders.fmt(d.lat, 5) + ", " + Decoders.fmt(d.lon, 5) + "\n" else "") +
                (d.text ?: "").replace('\n', ' ')
            v.setBackgroundColor(if (p % 2 == 0) Col.CARD else Col.alpha(Col.CARD, 0x80))
            return v
        }
    }

    override val view: View = k.vbox(k.dp(12)).apply {
        addView(mode, lp(WRAP, WRAP).apply { bottomMargin = k.dp(10) })
        val box = k.card().apply { setPadding(0, k.dp(6), 0, k.dp(6)) }
        box.addView(empty)
        box.addView(ListView(act).apply {
            adapter = this@DetectionsScreen.adapter
            divider = null
            setSelector(android.R.color.transparent)
            setOnItemClickListener { _, _, p, _ -> detail(rows[p]) }
        }, lp(MATCH, 0, 1f))
        addView(box, lp(MATCH, 0, 1f))
    }

    private fun detail(d: Decoders.Detection) {
        val e = engine ?: return
        val dl = k.dialog().setTitle(d.title ?: d.kind).setMessage(d.text ?: "").setPositiveButton(tr("FERMER"), null)
        if (d.hasPos) dl.setNeutralButton(tr("VOIR SUR LA CARTE")) { _, _ -> act.showOnMap(e.mapKey(d)) }
        dl.show()
    }

    override fun refresh() {
        val e = engine ?: return
        rows = e.detections()
        adapter.notifyDataSetChanged()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        val (t, c) = e.mode()
        mode.set(t, c)
    }
}

/** Journal des evenements. */
class LogScreen(act: MainActivity) : Screen(act) {
    override val title = tr("Journal")
    private val text = k.text("", 12.5f, Col.TEXT2, mono = true).apply { setTextIsSelectable(true) }
    private val sv = ScrollView(act).apply { addView(text) }
    private var shown = -1L

    override val view: View = k.vbox(k.dp(12)).apply {
        val box = k.card()
        box.addView(sv, lp(MATCH, 0, 1f))
        addView(box, lp(MATCH, 0, 1f))
        addView(k.key(tr("COPIER LE JOURNAL"), small = true) {
            (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("HTMod", text.text))
            k.toast(tr("Journal copié"))
        })
    }

    override fun refresh() {
        val e = engine ?: return
        if (e.logVersion == shown) return
        shown = e.logVersion
        val atEnd = !sv.canScrollVertically(1)
        text.text = e.logLines().joinToString("\n")
        if (atEnd) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
}

/** Reglages et outils. */
class SettingsScreen(act: MainActivity) : Screen(act) {
    override val title = tr("Réglages")
    private val swAprs = k.switchRow(tr("APRS : démodulateur logiciel en plus du TNC du poste"), Prefs.getBoolean("swAprs", true)) { Prefs.putBoolean("swAprs", it) }
    private val folder = k.text("", 12.5f, Col.TEXT2, mono = true).apply { setTextIsSelectable(true) }

    override val view: View = k.scroll(k.vbox(k.dp(12)).apply {
        addView(k.card().apply {
            addView(k.label(tr("Langue")), lp().apply { bottomMargin = k.dp(8) })
            val cur = Tr.LANGS.indexOfFirst { it[0] == Tr.lang() }
            addView(k.choice(Tr.LANGS.map { it[1] }, cur) { i ->
                val code = Tr.LANGS[i][0]
                if (code == Tr.lang()) return@choice
                Tr.save(code)
                HtService.restartEngine()
            })
        })
        addView(k.card().apply {
            addView(k.label(tr("Décodage")))
            addView(swAprs)
        })
        addView(k.card().apply {
            addView(k.label(tr("Décoder un fichier WAV")), lp().apply { bottomMargin = k.dp(8) })
            for ((mode, name) in listOf(Pair(-2, "POCSAG"), Pair(-1, "APRS"), Pair(1, "SARSAT"), Pair(2, tr("Radiosonde")))) {
                addView(k.key(name, small = true) { decodeWav(mode) }, lp().apply { bottomMargin = k.dp(6) })
            }
            addView(k.note(tr("Le résultat apparaît dans l'onglet correspondant et dans le JOURNAL.")))
        })
        addView(k.card().apply {
            addView(k.label(tr("Dossier des résultats")), lp().apply { bottomMargin = k.dp(6) })
            addView(folder)
            addView(k.note(tr("CSV des décodages, aprs.txt, WAV, images SSTV, tuiles de carte. Accessible en branchant le téléphone à un PC (Android/data/com.htmod.android/files/HTMod).")))
            addView(k.key(tr("EFFACER LA CARTE ET LES TABLEAUX"), small = true) {
                k.confirm("HTMod", tr("Effacer la carte et les tableaux APRS et décodages ?"), tr("EFFACER")) { engine?.clearAll() }
            }, lp().apply { topMargin = k.dp(10) })
        })
        addView(k.card().apply {
            addView(k.label(tr("Mode d'emploi")), lp().apply { bottomMargin = k.dp(6) })
            addView(k.text(tr("1. Appairez le poste dans les réglages Bluetooth du téléphone (une seule fois).\n") +
                tr("2. Fermez l'appli du constructeur et HTMod sur le PC : le poste n'accepte qu'une connexion.\n") +
                tr("3. Onglet RADIO : choisissez le poste et touchez SE CONNECTER.\n") +
                tr("4. POCSAG : décodage permanent, alerte à chaque message, fiche au toucher.\n") +
                tr("5. SARSAT / radiosondes : canal nommé SARSAT ou Radiosonde, ou mode forcé.\n") +
                tr("6. HTMod reste actif en arrière-plan : QUITTER dans la notification pour l'arrêter."), 13.5f, Col.TEXT2))
            addView(k.text("HTMod " + Engine.VERSION + " Android", 12f, Col.MUTED, mono = true), lp().apply { topMargin = k.dp(10) })
        })
    })

    private fun decodeWav(mode: Int) {
        act.pickFile("audio/*") { data, _ ->
            val e = engine ?: return@pickFile
            val f = File(act.cacheDir, "decodage.wav")
            f.writeBytes(data)
            val err = e.decodeWav(f, mode)
            if (err != null) k.alert(err) else k.toast(tr("Décodage lancé (voir le JOURNAL)"))
        }
    }

    override fun refresh() {
        val e = engine ?: return
        folder.text = e.outDir.absolutePath
        swAprs.on = Prefs.getBoolean("swAprs", true)
    }
}
