/*
 * HTMOD Android : fenetre de l'appli (interface native pour telephone) : barre du haut (liaison, son),
 * onglets en bas RADIO / POCSAG / CARTE / APRS / PLUS (canaux, decodages, bandscope, SSTV, satellites,
 * journal, reglages). Demande les autorisations puis demarre le service HtService.
 */
package com.htmod

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

class MainActivity : Activity() {
    private lateinit var k: K
    private lateinit var content: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var backKey: TextView
    private lateinit var linkChip: Chip
    private lateinit var soundKey: Key
    private val tabs = ArrayList<Triple<String, IconView, TextView>>()
    private val screens = HashMap<String, Screen>()
    private var current: Screen? = null
    private var currentTab = "radio"
    private var subOf: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var pendingPocsag: String? = null
    private val tick = object : Runnable {
        override fun run() {
            try { refresh() } catch (e: Exception) { android.util.Log.w("HTMod", "interface : $e") }
            handler.postDelayed(this, 450)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        ref = WeakReference(this)
        k = K(this)
        window.statusBarColor = Col.BG
        window.navigationBarColor = Col.BG
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(build())
        pendingPocsag = intent?.getStringExtra(HtService.EXTRA_POCSAG)
        askPermissions()
    }

    private fun build(): View {
        val root = k.vbox().apply { setBackgroundColor(Col.BG) }
        // barre du haut
        val top = k.hbox().apply { setPadding(k.dp(12), k.dp(8), k.dp(12), k.dp(8)) }
        backKey = k.text("‹", 30f, Col.AMBER, bold = true).apply {
            setPadding(k.dp(4), 0, k.dp(14), k.dp(2))
            visibility = View.GONE
            setOnClickListener { back() }
        }
        top.addView(backKey)
        val brand = k.hbox()
        brand.addView(k.text("HT", 24f, Col.TEXT, bold = true))
        brand.addView(k.text("MOD", 24f, Col.AMBER, bold = true))
        titleView = k.text("", 20f, Col.TEXT, bold = true).apply { visibility = View.GONE }
        top.addView(brand)
        top.addView(titleView)
        top.addView(View(this), lp(0, 1, 1f))
        linkChip = k.chip()
        linkChip.setOnClickListener { showTab("radio") }
        top.addView(linkChip)
        soundKey = k.key("🔇", small = true) {
            val e = HtService.engine ?: return@key
            e.listen = !e.listen
            k.toast(if (e.listen) tr("Son du poste sur le haut-parleur du téléphone") else tr("Son coupé"))
        }
        top.addView(soundKey, lp(WRAP, WRAP).apply { leftMargin = k.dp(8) })
        root.addView(top)
        // contenu
        content = FrameLayout(this)
        root.addView(content, lp(MATCH, 0, 1f))
        // onglets du bas
        val nav = k.hbox(Gravity.CENTER).apply {
            setBackgroundColor(Col.CARD)
            setPadding(0, k.dp(6), 0, k.dp(6))
        }
        for ((id, icon, label) in listOf(Triple("radio", "radio", "RADIO"), Triple("pocsag", "pager", "POCSAG"),
            Triple("carte", "pin", tr("CARTE")), Triple("aprs", "list", "APRS"), Triple("plus", "grid", tr("PLUS")))) {
            val col = k.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setOnClickListener { showTab(id) } }
            val iv = IconView(this, icon)
            col.addView(iv, lp(k.dp(26), k.dp(26)))
            val tv = k.text(label, 11f, Col.MUTED, bold = true)
            col.addView(tv, lp(WRAP, WRAP))
            nav.addView(col, lp(0, WRAP, 1f))
            tabs.add(Triple(id, iv, tv))
        }
        root.addView(nav)
        return root
    }

    // ------------------------------------------------------------------ Navigation

    private fun screen(id: String): Screen = screens.getOrPut(id) {
        when (id) {
            "radio" -> RadioScreen(this)
            "pocsag" -> PocsagScreen(this)
            "carte" -> MapScreen(this)
            "aprs" -> AprsScreen(this)
            "plus" -> MoreScreen(this)
            "canaux" -> ChannelsScreen(this)
            "decodages" -> DetectionsScreen(this)
            "bandscope" -> BandscopeScreen(this)
            "sstv" -> SstvScreen(this)
            "satellites" -> SatScreen(this)
            "journal" -> LogScreen(this)
            else -> SettingsScreen(this)
        }
    }

    fun showTab(id: String) {
        subOf = null
        currentTab = id
        show(screen(id))
    }

    /** Onglet CARTE centre sur une epingle (APRS, sonde, SARSAT), fiche ouverte. */
    fun showOnMap(key: String) {
        showTab("carte")
        (screen("carte") as MapScreen).focus(key)
    }

    /** Sous-ecran de PLUS (touche retour en haut). */
    fun open(id: String) {
        subOf = currentTab
        show(screen(id))
    }

    private fun show(s: Screen) {
        current = s
        content.removeAllViews()
        (s.view.parent as? android.view.ViewGroup)?.removeView(s.view)
        content.addView(s.view, FrameLayout.LayoutParams(MATCH, MATCH))
        val sub = subOf != null
        backKey.visibility = if (sub) View.VISIBLE else View.GONE
        titleView.visibility = if (sub) View.VISIBLE else View.GONE
        (titleView.parent as LinearLayout).getChildAt(1).visibility = if (sub) View.GONE else View.VISIBLE
        titleView.text = s.title
        for ((id, iv, tv) in tabs) {
            val on = id == currentTab
            iv.color = if (on) Col.AMBER else Col.MUTED
            tv.setTextColor(if (on) Col.AMBER else Col.MUTED)
        }
        s.onShow()
        s.refresh()
    }

    private fun back(): Boolean {
        val p = subOf ?: return false
        showTab(p)
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (back()) return
        if (currentTab != "radio") { showTab("radio"); return }
        moveTaskToBack(true) // HTMod continue en arriere-plan (QUITTER dans la notification pour l'arreter)
    }

    private fun refresh() {
        val e = HtService.engine
        if (e == null) { linkChip.set(tr("DÉMARRAGE…"), Col.MUTED); return }
        when {
            e.connected -> linkChip.set(tr("CONNECTÉ"), Col.CYAN)
            e.reconnecting -> linkChip.set(tr("NOUVEL ESSAI…"), Col.AMBER)
            e.radio.isRunning -> linkChip.set(tr("CONNEXION…"), Col.AMBER)
            else -> linkChip.set(tr("HORS LIGNE"), Col.MUTED)
        }
        soundKey.setLabel(if (e.listen) "🔊" else "🔇")
        soundKey.setSel(e.listen)
        current?.refresh()
        pendingPocsag?.let { id ->
            pendingPocsag = null
            showTab("pocsag")
            (screen("pocsag") as PocsagScreen).showById(id)
        }
    }

    // ------------------------------------------------------------------ POCSAG, autorisations, fichiers

    /** Message POCSAG recu pendant que l'appli est ouverte (la fenetre surgissante est deja affichee). */
    fun onPocsag(m: PocsagMsg) { (screens["pocsag"] as? PocsagScreen)?.refresh() }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        i.getStringExtra(HtService.EXTRA_POCSAG)?.let { pendingPocsag = it }
    }

    private fun askPermissions() {
        val need = ArrayList<String>()
        need.add(Manifest.permission.BLUETOOTH_CONNECT)
        need.add(Manifest.permission.BLUETOOTH_SCAN)
        if (android.os.Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (!Prefs.getBoolean("micAsked", false)) { need.add(Manifest.permission.RECORD_AUDIO); Prefs.putBoolean("micAsked", true) }
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startAll() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        if (req == REQ_PERMS) startAll()
    }

    private fun startAll() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            k.dialog().setTitle("HTMod")
                .setMessage(tr("HTMod a besoin de l'autorisation « Appareils à proximité » pour se connecter au poste en Bluetooth."))
                .setPositiveButton(tr("RÉESSAYER")) { _, _ -> askPermissions() }
                .setNegativeButton(tr("RÉGLAGES")) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
                .setCancelable(false).show()
            return
        }
        HtService.start(this)
        if (!started) {
            started = true
            showTab("radio")
            handler.post(tick)
        }
        if (!Engine.bluetoothOn(this)) {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) {}
        } else askBattery()
    }

    /** Samsung / Android coupent les applis en arriere-plan : exemption d'optimisation de batterie (une fois). */
    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || Prefs.getBoolean("batteryAsked", false)) return
        Prefs.putBoolean("batteryAsked", true)
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
    }

    fun askMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private var fileCallback: ((ByteArray, String) -> Unit)? = null

    /** Choix d'un fichier du telephone (image, WAV...) ; le contenu est lu et passe a onFile. */
    fun pickFile(mime: String, onFile: (ByteArray, String) -> Unit) {
        fileCallback = onFile
        val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
        try { startActivityForResult(Intent.createChooser(i, tr("Choisir un fichier")), REQ_FILE) } catch (e: Exception) { k.alert(e.toString()) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == REQ_FILE) {
            val cb = fileCallback
            fileCallback = null
            val uri = data?.data
            if (res != RESULT_OK || uri == null || cb == null) return
            Thread({
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { Net.readAll(it) }
                    val name = uri.lastPathSegment ?: "fichier"
                    runOnUiThread { cb(bytes, name) }
                } catch (e: Exception) {
                    runOnUiThread { k.alert(tr("Fichier illisible : ") + e.message) }
                }
            }, "htmod-fichier").start()
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(req, res, data)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        if (ref.get() === this) ref = WeakReference(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_MIC = 2
        private const val REQ_FILE = 3
        @Volatile private var ref = WeakReference<MainActivity>(null)

        fun current(): MainActivity? = ref.get()

        fun finishAll() { current()?.let { a -> a.runOnUiThread { a.finishAndRemoveTask() } } }
    }
}
