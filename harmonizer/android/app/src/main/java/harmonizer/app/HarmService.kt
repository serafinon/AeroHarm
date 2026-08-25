package harmonizer.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Servizio in primo piano. Spec §10.
 *
 * È il pezzo che decide l'affidabilità dal vivo, più della latenza: senza,
 * Android decide a metà pezzo che l'app non è importante e taglia le note.
 * Serve la notifica persistente E il wake lock; in più l'utente deve
 * escludere l'app dall'ottimizzazione batteria (vedi MainActivity).
 */
class HarmService : Service() {

    private var wake: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL = "aeroharm"
        const val NOTIF_ID = 1

        fun start(ctx: Context) {
            val i = Intent(ctx, HarmService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, HarmService::class.java))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        creaCanale()
        startForeground(NOTIF_ID, notifica())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aeroharm:midi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { if (wake?.isHeld == true) wake?.release() } catch (_: Exception) {}
        wake = null
        super.onDestroy()
    }

    private fun creaCanale() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "AeroHarm", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene attivo il motore MIDI durante l'esecuzione"
                setShowBadge(false)
            }
        )
    }

    private fun notifica(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("AeroHarm attivo")
            .setContentText("Motore MIDI in esecuzione")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
