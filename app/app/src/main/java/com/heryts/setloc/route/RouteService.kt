package com.heryts.setloc.route

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.heryts.setloc.R
import com.heryts.setloc.ui.MapActivity
import com.heryts.setloc.utils.PrefManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service supaya perjalanan rute tetap jalan saat app di background / layar mati.
 * Logika posisi ada di [RouteRunner]; service ini hanya menjaga proses tetap hidup + notifikasi.
 */
class RouteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            RouteRunner.stop()
            // spoof ikut dimatikan, sama seperti tombol Stop di app
            PrefManager.updateNow(
                false,
                PrefManager.getLat,
                PrefManager.getLng
            )
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!RouteRunner.isRunning) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification(0))
        acquireWakeLock()

        scope.launch {
            RouteRunner.state.collect { s ->
                if (!s.running) {
                    stopForeground(true)
                    stopSelf()
                } else if (s.progressPercent != lastPercent) {
                    lastPercent = s.progressPercent
                    NotificationManagerCompat.from(this@RouteService)
                        .notify(NOTIF_ID, buildNotification(s.progressPercent))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "setloc:route").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(percent: Int): Notification {
        NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW)
            .setName(getString(R.string.route_channel_name))
            .build()
            .also { NotificationManagerCompat.from(this).createNotificationChannel(it) }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MapActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RouteService::class.java).setAction(ACTION_STOP), flags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.route_running))
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, getString(R.string.route_stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.heryts.setloc.route.STOP"
        private const val CHANNEL_ID = "set.route"
        private const val NOTIF_ID = 124
    }
}
