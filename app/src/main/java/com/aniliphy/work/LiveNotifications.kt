package com.aniliphy.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aniliphy.MainActivity
import com.aniliphy.R
import com.aniliphy.widget.WidgetIntents
import com.aniliphy.widget.WidgetItem
import com.aniliphy.widget.WidgetKind
import java.util.concurrent.TimeUnit

/** An anime on the Watching list with its next episode's airing time. */
data class AiringSoon(val mediaId: Int, val title: String, val episode: Int, val airingAt: Long, val progress: Int)

/** Which shows get the countdown, and how the bar fills. Plain, so it's tested. */
object LiveRules {
    const val WINDOW_S = 3 * 3600L

    /** Airing within the window, for shows you're caught up on (the episode before it watched). */
    fun due(items: List<AiringSoon>, nowS: Long, windowS: Long = WINDOW_S): List<AiringSoon> =
        items.filter { it.airingAt - nowS in 1..windowS && it.progress >= it.episode - 1 }

    /** How far through the countdown window, 0..1000. */
    fun countdownProgress(airingAt: Long, nowS: Long, windowS: Long = WINDOW_S): Int =
        (((windowS - (airingAt - nowS)).coerceIn(0, windowS) * 1000) / windowS).toInt()

    /** "2:14" until airing, for the status bar chip. */
    fun shortLeft(airingAt: Long, nowS: Long): String {
        val left = (airingAt - nowS).coerceAtLeast(0)
        return "%d:%02d".format(left / 3600, (left % 3600) / 60)
    }

    enum class Part { Opening, Watched, Left, Ending }

    /** The paused episode's bar, as lengths in seconds: opening and ending marked, the rest watched or left. */
    fun pausedParts(positionS: Double, durationS: Double, intro: ClosedFloatingPointRange<Double>?, outro: ClosedFloatingPointRange<Double>?): List<Pair<Part, Double>> {
        if (durationS <= 0) return emptyList()
        val cuts = buildList {
            intro?.let { add(it.start.coerceIn(0.0, durationS) to (it.endInclusive.coerceIn(0.0, durationS) to Part.Opening)) }
            outro?.let { add(it.start.coerceIn(0.0, durationS) to (it.endInclusive.coerceIn(0.0, durationS) to Part.Ending)) }
        }.sortedBy { it.first }
        val out = mutableListOf<Pair<Part, Double>>()
        var at = 0.0
        fun plain(until: Double) {
            if (until <= at) return
            val watchedEnd = positionS.coerceIn(at, until)
            if (watchedEnd > at) out += Part.Watched to (watchedEnd - at)
            if (until > watchedEnd) out += Part.Left to (until - watchedEnd)
            at = until
        }
        for ((start, rest) in cuts) {
            val (end, part) = rest
            if (start < at || end <= start) continue
            plain(start)
            out += part to (end - start)
            at = end
        }
        plain(durationS)
        return out
    }
}

/** The countdown on airing day and the episode left paused. Settings in Anime settings › Notifications. */
object LiveNotifications {
    private const val CHANNEL = "live"
    /** Quiet: it only reminds where you stopped. */
    private const val PAUSED_CHANNEL = "paused"
    private const val PAUSED_ID = 0x7FFF_0E01
    const val ACTION_HIDE = "com.aniliphy.LIVE_HIDE"
    const val ACTION_CLOSE_PLAYER = "com.aniliphy.LIVE_CLOSE_PLAYER"
    const val EXTRA_ID = "id"

    /** Bumped by "Close" on the paused notification; the player closes when it changes. */
    var closePlayer by mutableIntStateOf(0)

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannels(
                listOf(
                    NotificationChannel(CHANNEL, "Airing countdown", NotificationManager.IMPORTANCE_DEFAULT),
                    NotificationChannel(PAUSED_CHANNEL, "Episode left paused", NotificationManager.IMPORTANCE_LOW),
                ),
            )
    }

    /** Same id as the release notification for that episode, so "out now" and the later release notice are one. */
    private fun countdownId(mediaId: Int) = mediaId * 2 + 1

    private fun openAnime(context: Context, mediaId: Int): PendingIntent {
        val intent = WidgetIntents.open(context, WidgetItem(WidgetKind.Anime, mediaId.toString(), "", detail = ""), "live-$mediaId")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, mediaId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, what: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, id, Intent(context, LiveActions::class.java).setAction(what).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }

    fun countdown(context: Context, item: AiringSoon, nowS: Long = System.currentTimeMillis() / 1000) {
        if (!ReleaseNotifications.allowed(context)) return
        channel(context)
        val id = countdownId(item.mediaId)
        val head = "${item.title} · Episode ${item.episode}"
        val at = java.time.Instant.ofEpochSecond(item.airingAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val text = "Airs at $at"
        val notification = if (Build.VERSION.SDK_INT >= 36) {
            // Android 16: a live update, with the time left in the status bar.
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(head)
                .setContentText(text)
                .setSubText("Airing today")
                .setWhen(item.airingAt * 1000).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAnime(context, item.mediaId))
                .addAction(Notification.Action.Builder(null, "Hide", action(context, ACTION_HIDE, id)).build())
                .setStyle(Notification.ProgressStyle().setProgress(LiveRules.countdownProgress(item.airingAt, nowS)).setProgressSegments(listOf(Notification.ProgressStyle.Segment(1000))))
                .setShortCriticalText(LiveRules.shortLeft(item.airingAt, nowS))
                .setRequestPromotedOngoing(true)
                .build()
        } else {
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(head)
                .setContentText(text)
                .setSubText("Airing today")
                .setWhen(item.airingAt * 1000).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(1000, LiveRules.countdownProgress(item.airingAt, nowS), false)
                .setContentIntent(openAnime(context, item.mediaId))
                .addAction(0, "Hide", action(context, ACTION_HIDE, id))
                .build()
        }
        notify(context, id, notification)
        // Turns into "out now" at airing time, even if the 30-minute check doesn't run then.
        val wait = (item.airingAt - nowS).coerceAtLeast(0)
        WorkManager.getInstance(context).enqueueUniqueWork(
            "airing-${item.mediaId}-${item.episode}",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AiringOutWorker>()
                .setInitialDelay(wait, TimeUnit.SECONDS)
                .setInputData(workDataOf("id" to item.mediaId, "title" to item.title, "episode" to item.episode))
                .build(),
        )
    }

    fun out(context: Context, mediaId: Int, title: String, episode: Int) {
        if (!ReleaseNotifications.allowed(context)) return
        channel(context)
        val open = openAnime(context, mediaId)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$title · Episode $episode is out")
            .setContentText("On the source you use · usually up within the hour")
            .setSubText("Out now")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Watch now", open)
            .build()
        notify(context, countdownId(mediaId), notification)
    }

    fun hide(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Leaving the app with the player open and paused. */
    fun paused(
        context: Context,
        mediaId: Int,
        title: String,
        episodeLabel: String,
        positionS: Double,
        durationS: Double,
        intro: ClosedFloatingPointRange<Double>?,
        outro: ClosedFloatingPointRange<Double>?,
    ) {
        if (!ReleaseNotifications.allowed(context) || durationS <= 0) return
        channel(context)
        fun clock(s: Double) = com.aniliphy.sync.HandoffRules.clock(s) ?: "0:00"
        val left = ((durationS - positionS) / 60).toInt().coerceAtLeast(0)
        val text = "${clock(positionS)} / ${clock(durationS)} · $left min left"
        val resume = PendingIntent.getActivity(
            context, PAUSED_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val close = action(context, ACTION_CLOSE_PLAYER, PAUSED_ID)
        val notification = if (Build.VERSION.SDK_INT >= 36) {
            val parts = LiveRules.pausedParts(positionS, durationS, intro, outro)
            val segments = parts.map { (part, length) ->
                Notification.ProgressStyle.Segment(maxOf(1, length.toInt())).setColor(
                    when (part) {
                        LiveRules.Part.Opening, LiveRules.Part.Ending -> 0xFFF5D63D.toInt()
                        LiveRules.Part.Watched -> 0xFF2DE2C9.toInt()
                        LiveRules.Part.Left -> 0xFF555555.toInt()
                    },
                )
            }
            Notification.Builder(context, PAUSED_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$title · $episodeLabel")
                .setContentText(text)
                .setSubText("Paused")
                .setOnlyAlertOnce(true)
                .setContentIntent(resume)
                .addAction(Notification.Action.Builder(null, "Resume", resume).build())
                .addAction(Notification.Action.Builder(null, "Close", close).build())
                .setStyle(Notification.ProgressStyle().setProgressSegments(segments).setProgress(positionS.toInt()))
                .build()
        } else {
            NotificationCompat.Builder(context, PAUSED_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$title · $episodeLabel")
                .setContentText(text)
                .setSubText("Paused")
                .setSilent(true)
                .setProgress(durationS.toInt(), positionS.toInt(), false)
                .setContentIntent(resume)
                .addAction(0, "Resume", resume)
                .addAction(0, "Close", close)
                .build()
        }
        notify(context, PAUSED_ID, notification)
    }

    fun clearPaused(context: Context) = NotificationManagerCompat.from(context).cancel(PAUSED_ID)
}

/** "Hide" on the countdown, "Close" on the paused episode. */
class LiveActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(LiveNotifications.EXTRA_ID, 0)
        when (intent.action) {
            LiveNotifications.ACTION_HIDE -> LiveNotifications.hide(context, id)
            LiveNotifications.ACTION_CLOSE_PLAYER -> {
                LiveNotifications.clearPaused(context)
                LiveNotifications.closePlayer++
            }
        }
    }
}

/** At airing time: the countdown becomes "out now". */
class AiringOutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getInt("id", 0).takeIf { it > 0 } ?: return Result.success()
        LiveNotifications.out(applicationContext, id, inputData.getString("title") ?: "", inputData.getInt("episode", 0))
        return Result.success()
    }
}
