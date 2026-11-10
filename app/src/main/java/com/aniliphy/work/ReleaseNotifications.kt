package com.aniliphy.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aniliphy.MainActivity
import com.aniliphy.R

object ReleaseNotifications {
    private const val CHAPTERS = "new_chapters"
    private const val EPISODES = "new_episodes"

    const val EXTRA_TAB = "com.aniliphy.OPEN_TAB"

    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHAPTERS, "New chapters", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(EPISODES, "New episodes", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun post(context: Context, kind: NotifiedReleases.Kind, mediaId: Int, title: String, body: String) {
        if (!allowed(context)) return
        channels(context)
        val chapter = kind == NotifiedReleases.Kind.Chapter
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_TAB, if (chapter) "Manga" else "Anime")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val id = mediaId * 2 + if (chapter) 0 else 1
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, if (chapter) CHAPTERS else EPISODES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}
