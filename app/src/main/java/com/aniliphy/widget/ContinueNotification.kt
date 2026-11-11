package com.aniliphy.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.aniliphy.R
import com.aniliphy.core.PrefStore
import com.aniliphy.work.ReleaseNotifications

/** Settings → Activity. */
class ActivitySettings(context: Context) : PrefStore(context, "activity") {
    var continueNotification by boolPref("continue_notification", false)

    companion object {
        @Volatile private var instance: ActivitySettings? = null

        fun get(context: Context): ActivitySettings = instance ?: synchronized(this) {
            instance ?: ActivitySettings(context.applicationContext).also { instance = it }
        }

        fun forget() {
            instance = null
        }
    }
}

/**
 * A quiet notification with the latest anime, manga and light novel, each a button straight into the player or
 * reader, for when there's no widget. Off unless turned on; it is only posted again when something in it changes, so
 * swiping it away keeps it away until then.
 */
object ContinueNotification {
    private const val CHANNEL = "continue"
    private const val ID = 7_301
    private const val STATE = "continue_notification"
    private const val SHOWN = "shown"

    fun update(context: Context, snapshot: WidgetSnapshot) {
        val prefs = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        if (!ActivitySettings.get(context).continueNotification || !ReleaseNotifications.allowed(context)) {
            cancel(context)
            return
        }
        val latest = listOfNotNull(
            snapshot.anime.firstOrNull()?.let { "Anime" to it },
            snapshot.manga.firstOrNull()?.let { "Manga" to it },
            snapshot.novels.firstOrNull()?.let { "Light novel" to it },
        )
        if (latest.isEmpty()) {
            cancel(context)
            return
        }
        val lines = latest.map { (label, item) -> "$label · ${item.title} · ${item.detail}" }
        val shown = lines.joinToString("\n")
        if (prefs.getString(SHOWN, null) == shown) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Continue watching and reading", NotificationManager.IMPORTANCE_LOW))
        val newest = latest.maxBy { it.second.updated }.second
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Continue")
            .setContentText("${newest.title} · ${newest.detail}")
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setContentIntent(intent(context, newest, "notification/latest", 0))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
        latest.forEachIndexed { index, (label, item) ->
            builder.addAction(0, label, intent(context, item, "notification/$label", index + 1))
        }
        try {
            NotificationManagerCompat.from(context).notify(ID, builder.build())
            prefs.edit { putString(SHOWN, shown) }
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit { remove(SHOWN) }
    }

    private fun intent(context: Context, item: WidgetItem, where: String, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            ID + code,
            WidgetIntents.open(context, item, where),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
