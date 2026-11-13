/*
 * HTMOD Android : service de premier plan qui porte le moteur (liaison Bluetooth, decodeurs) : HTMod
 * continue de recevoir et de decoder ecran eteint, appli fermee ou retiree des applis recentes.
 * Notification permanente (etat de la liaison, touche QUITTER) ; fenetre surgissante a chaque message
 * POCSAG ; notifications des autres decodages.
 */
package com.htmod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicInteger

class HtService : Service(), Engine.Ui {
    private val nextId = AtomicInteger(100)
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var state = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RUN, tr("HTMod actif"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DEC, tr("Décodages (APRS, SSTV, sondes…)"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, tr("Alertes SARSAT"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_POCSAG, tr("Messages POCSAG reçus"), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 250, 120, 250)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        state = tr("Déconnecté")
        val n = runNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID_RUN, n)
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "htmod:liaison").apply {
            setReferenceCounted(false)
            acquire()
        }
        if (engine == null) {
            val e = Engine(this, this)
            engine = e
            e.autoConnect()
        }
    }

    private fun openApp(req: Int, extra: PocsagMsg? = null): PendingIntent {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (extra != null) i.putExtra(EXTRA_POCSAG, extra.id)
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun runNotification(): Notification {
        val quit = PendingIntent.getService(this, 1, Intent(this, HtService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("HTMod")
            .setContentText(state)
            .setStyle(Notification.BigTextStyle().bigText(state))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(0))
            .addAction(Notification.Action.Builder(null, tr("QUITTER"), quit).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) { quit(); return START_NOT_STICKY }
        return START_STICKY
    }

    /** Arret complet : liaison fermee, service et appli termines. */
    fun quit() {
        Prefs.putBoolean("wasConnected", false)
        val e = engine
        engine = null
        e?.shutdown()
        MainActivity.finishAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ Engine.Ui

    override fun notify(title: String, text: String, alert: Boolean) {
        val n = Notification.Builder(this, if (alert) CH_ALERT else CH_DEC)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(2))
            .build()
        try { getSystemService(NotificationManager::class.java).notify(nextId.incrementAndGet(), n) } catch (e: SecurityException) {}
    }

    /** Message POCSAG : fenetre surgissante ; la toucher ouvre la fiche du message dans l'appli. */
    override fun pocsag(m: PocsagMsg) {
        val id = nextId.incrementAndGet()
        val n = Notification.Builder(this, CH_POCSAG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("POCSAG RIC " + m.ric + " · " + m.func)
            .setContentText(m.text)
            .setSubText(m.rate + " · " + m.time)
            .setStyle(Notification.BigTextStyle().bigText(m.text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openApp(id, m))
            .build()
        try { getSystemService(NotificationManager::class.java).notify(id, n) } catch (e: SecurityException) {}
        MainActivity.current()?.let { a -> a.runOnUiThread { a.onPocsag(m) } }
    }

    override fun status(text: String) {
        if (text == state) return
        state = text
        try { getSystemService(NotificationManager::class.java).notify(ID_RUN, runNotification()) } catch (e: SecurityException) {}
    }

    override fun askMic() {
        MainActivity.current()?.let { a -> a.runOnUiThread { a.askMic() } }
    }

    companion object {
        const val ACTION_QUIT = "com.htmod.QUITTER"
        const val EXTRA_POCSAG = "com.htmod.pocsag"
        private const val CH_RUN = "htmod_service"
        private const val CH_DEC = "htmod_decodages"
        private const val CH_ALERT = "htmod_alertes"
        private const val CH_POCSAG = "htmod_pocsag_alerte"
        private const val ID_RUN = 1
        @Volatile var engine: Engine? = null
            private set
        @Volatile private var instance: HtService? = null

        fun start(c: Context) { c.startForegroundService(Intent(c, HtService::class.java)) }

        /** Changement de langue : moteur recree dans la nouvelle langue (la liaison est rouverte). */
        fun restartEngine() {
            val s = instance ?: return
            Thread({
                val e = engine
                engine = null
                e?.shutdown()
                Tr.reload()
                val n = Engine(s, s)
                engine = n
                n.autoConnect()
                MainActivity.current()?.let { a -> a.runOnUiThread { a.recreate() } }
            }, "htmod-redemarrage").start()
        }
    }
}
