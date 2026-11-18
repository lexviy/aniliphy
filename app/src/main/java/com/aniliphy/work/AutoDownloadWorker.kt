package com.aniliphy.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aniliphy.anime.AnimeRules
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.anime.OfflineAnime
import com.aniliphy.anime.SkipTimes
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.manga.MangaSettings
import com.aniliphy.manga.OfflineManga
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.manga.sources.SourceHttp
import com.aniliphy.ui.AnimeDownloads
import com.aniliphy.ui.AnimePlayerRequest
import com.aniliphy.ui.DownloadCenter
import com.aniliphy.ui.MangaDownloads
import com.aniliphy.ui.MangaReaderRequest
import com.aniliphy.ui.MangaUi
import com.aniliphy.ui.Resolved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Downloads the new episodes and chapters [NewReleasesWorker] found, through the same queue as downloads started by
 * hand, while showing the queue's notification. Runs only on the network, charge and storage the settings ask for.
 */
class AutoDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext

    override suspend fun getForegroundInfo(): ForegroundInfo = info()

    private fun info(): ForegroundInfo {
        val notification = DownloadService.build(app)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(DownloadService.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(DownloadService.NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val store = AutoDownloads(File(app.filesDir, AutoDownloads.FILE))
        val waiting = store.all()
        if (waiting.isEmpty()) return Result.success()
        runCatching { setForeground(info()) }
        DownloadCenter.hostedByWorker = true
        try {
            com.aniliphy.core.SiteCookies.init(app)
            com.aniliphy.core.Sources.init(app)
            val now = System.currentTimeMillis()
            for (item in waiting) {
                val queued = withContext(Dispatchers.IO) {
                    try {
                        if (item.anime) queueAnime(app, item) else queueManga(app, item)
                    } catch (_: Exception) {
                        false
                    }
                }
                if (queued || now - item.since > AutoDownloads.GIVE_UP_AFTER_MS) store.remove(item.key)
            }
            // Stays (and keeps the notification) while the queue works.
            while (DownloadCenter.busy) {
                delay(2_000)
                runCatching { setForeground(info()) }
            }
        } finally {
            DownloadCenter.hostedByWorker = false
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "auto_downloads"

        /** True when something was queued (or there was nothing left to get). */
        internal suspend fun queueAnime(app: Context, item: AutoDownload, cap: Int = AutoDownloads.MAX_EPISODES): Boolean {
            val settings = AnimeSettings.get(app)
            val offline = OfflineAnime.get(app)
            val (siteId, url) = AnimeSites.parse(item.source) ?: return true
            val site = AnimeSites.site(siteId)
            val episodes = site.episodes(url)
            val byNumber = episodes.groupBy { AnimeRules.aniListEpisode(it, item.total)?.toDouble() }
            val numbers = AutoDownloads.wanted(byNumber.keys.filterNotNull(), item.from, item.to, cap)
            if (numbers.isEmpty()) return false
            val saved = offline.downloadedIds(item.mediaId)
            val chosen = numbers.mapNotNull { byNumber[it]?.firstOrNull() }.filter { it.id !in saved }
            if (chosen.isEmpty()) return true
            val request = AnimePlayerRequest(item.mediaId, item.title, item.source, cover = item.cover, totalEpisodes = item.total, mal = item.mal)
            val audio = settings.audio
            withContext(Dispatchers.Main) {
                AnimeDownloads.enqueue(app, offline, request, chosen, settings.quality, audio, { settings.downloadOnWifiOnly }) { episode ->
                    val stream = withContext(Dispatchers.IO) { AnimeSites.stream(site, episode, audio) } ?: throw IOException("No stream")
                    val filled = if (settings.aniSkip) SkipTimes.shared(app).fill(stream, item.mal, AnimeRules.aniListEpisode(episode, item.total)) else stream
                    Resolved(filled, from = siteId)
                }
            }
            return true
        }

        internal suspend fun queueManga(app: Context, item: AutoDownload, cap: Int = AutoDownloads.MAX_CHAPTERS): Boolean {
            val settings = MangaSettings.get(app)
            val (siteId, url) = MangaSites.parse(item.source) ?: return true
            val chapters = MangaSites.site(siteId).chapters(url)
            val numbers = AutoDownloads.wanted(chapters.mapNotNull { it.number }, item.from, item.to, cap)
            if (numbers.isEmpty()) return false
            val offline = OfflineManga(File(app.filesDir, MangaUi.OFFLINE_DIR), SourceHttp::bytes)
            val saved = offline.downloadedIds(item.mediaId)
            val chosen = numbers.mapNotNull { n -> chapters.firstOrNull { it.number == n } }.filter { it.id !in saved }
            if (chosen.isEmpty()) return true
            val request = MangaReaderRequest(item.mediaId, item.title, item.source, cover = item.cover)
            withContext(Dispatchers.Main) {
                MangaDownloads.enqueue(app, offline, request, chosen) { settings.downloadOnWifiOnly }
            }
            return true
        }


        fun schedule(context: Context) {
            val anime = AnimeSettings.get(context)
            val manga = MangaSettings.get(context)
            val wifi = (anime.autoDownload && anime.downloadOnWifiOnly) || (manga.autoDownload && manga.downloadOnWifiOnly)
            val charging = (anime.autoDownload && anime.autoDownloadCharging) || (manga.autoDownload && manga.autoDownloadCharging)
            val request = OneTimeWorkRequestBuilder<AutoDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresCharging(charging)
                        .setRequiresStorageNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
