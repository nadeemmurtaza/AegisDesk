package com.newax.aegis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The continuous sync listener (item 7 — "Android isn't continuously
 * listening"): a dataSync foreground service that keeps [SyncEngine]'s LAN
 * transport up so a paired peer can reach this device at any moment, not only
 * inside the 15-minute [SyncWorker] window. The periodic worker stays as a
 * catch-up net when the service is stopped (battery saver, OEM kill, manual).
 *
 * Lifecycle: started from bootstrap (AegisApplication) when auto-sync is on
 * and from the Sync screen when the toggle flips on; the loop self-stops when
 * the toggle flips off (idle until the next start). START_STICKY so the
 * system restarts us after a kill; the loop is resilient (backoff + transport
 * restart) and idempotent (opId-deduped journal), so overlap with the worker
 * is harmless.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            "sync", "Device sync", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startAsForeground(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (loop == null || loop!!.isCompleted) {
            loop = scope.launch {
                SyncEngine.runContinuous()
            }
        }
        // Recreate after the system kills us (START_STICKY) — the loop restarts.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        loop?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, "sync")
            .setContentTitle("Aegis device sync")
            .setContentText("Listening for paired devices — encrypted sync active")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncForegroundService::class.java))
        }
    }
}
