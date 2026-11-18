package com.aniliphy.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.aniliphy.MainActivity
import com.aniliphy.R
import com.aniliphy.ui.DownloadCenter

/**
 * Keeps the app alive while episodes or chapters are being saved, so leaving the app doesn't stop them,
 * and shows how far along they are, with Pause and Cancel.
 */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> DownloadCenter.pause(this)
            ACTION_RESUME -> DownloadCenter.resume(this)
            ACTION_CANCEL -> DownloadCenter.cancelAll(this)
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, build(this), type) }
            .onFailure { stopSelf() }
        if (!DownloadCenter.busy) stop()
        return START_NOT_STICKY
    }

    /** Android allows a few hours a day of this; past that, pause and let the person carry on later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadCenter.pause(this)
        stop()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "downloads"
        /** Shared with [AutoDownloadWorker], so a queue started in the background shows the same notification. */
        internal const val NOTIFICATION_ID = 0x0D0A
        private const val ACTION_PAUSE = "com.aniliphy.downloads.PAUSE"
        private const val ACTION_RESUME = "com.aniliphy.downloads.RESUME"
        private const val ACTION_CANCEL = "com.aniliphy.downloads.CANCEL"
        private const val REFRESH_EVERY_MS = 700L

        @Volatile private var instance: DownloadService? = null
        @Volatile private var lastRefresh = 0L

        val running: Boolean get() = instance != null

        /** [force] for a change of state (paused, waiting); progress alone is shown at most every [REFRESH_EVERY_MS]. */
        fun refresh(context: Context, force: Boolean = false) {
            val service = instance ?: return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastRefresh < REFRESH_EVERY_MS) return
            lastRefresh = now
            try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(service))
            } catch (_: SecurityException) {
            }
        }

        fun finish() {
            instance?.stop()
        }

        private fun action(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getService(
                context, code, Intent(context, DownloadService::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        internal fun build(context: Context): android.app.Notification {
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
            val now = DownloadCenter.running.values.firstOrNull()
            val left = DownloadCenter.left.values.sum()
            val waitingMore = (left - DownloadCenter.running.size).coerceAtLeast(0)
            val wait = DownloadCenter.waiting.label
            val title = now?.let { "${it.title} · ${it.item}" } ?: "Downloads"
            val text = listOfNotNull(
                wait ?: now?.percent?.takeIf { it >= 0 }?.let { "$it%" } ?: "Starting…",
                waitingMore.takeIf { it > 0 }?.let { "$it more waiting" },
            ).joinToString(" · ")
            val open = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            val percent = now?.percent ?: -1
            if (wait == null) builder.setProgress(100, percent.coerceAtLeast(0), percent < 0)
            if (DownloadCenter.paused) builder.addAction(0, "Resume", action(context, ACTION_RESUME, 1))
            else builder.addAction(0, "Pause", action(context, ACTION_PAUSE, 2))
            builder.addAction(0, "Cancel all", action(context, ACTION_CANCEL, 3))
            return builder.build()
        }
    }
}
