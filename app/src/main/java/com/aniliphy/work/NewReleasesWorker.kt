package com.aniliphy.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.SessionState
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.anime.NewEpisodes
import com.aniliphy.anilist.MediaKind
import com.aniliphy.manga.FoundSources
import com.aniliphy.manga.MangaHistory
import com.aniliphy.manga.MangaRecord
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.manga.MangaSettings
import com.aniliphy.manga.NewChapterCheck
import com.aniliphy.manga.NewChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NewReleasesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val app = context.applicationContext
    private val notified = NotifiedReleases(File(app.filesDir, "notified_releases.json"))
    /** The look to word notifications in, read once per run (the app's own state isn't set up in the background). */
    private val look by lazy { com.aniliphy.ui.LookWords.of(com.aniliphy.ui.AppearanceState(app)) }

    override suspend fun doWork(): Result {
        com.aniliphy.widget.WidgetData.refresh(app)
        if (!ReleaseNotifications.allowed(app)) return Result.success()
        com.aniliphy.core.SiteCookies.init(app)
        com.aniliphy.core.Sources.init(app)
        if (MangaSettings.get(app).notifyNewChapters) chapters()
        if (AnimeSettings.get(app).notifyNewEpisodes) episodes()
        if (com.aniliphy.ui.SocialPrefs.notifyFriends(app)) friends()
        if (com.aniliphy.ui.SocialPrefs.notifyInbox(app)) inbox()
        return Result.success()
    }

    private suspend fun chapters() {
        val history = withContext(Dispatchers.IO) { MangaHistory(File(app.filesDir, "manga_history.json")).all() }
        val records = history + withContext(Dispatchers.IO) { foundOnSources(history) }
        withContext(Dispatchers.IO) {
            val newChapters = NewChapters(File(app.filesDir, "manga_new_chapters.json"))
            NewChapterCheck.refresh(records, newChapters)
            val told = notified.firstTime(NotifiedReleases.Kind.Chapter, NewChapterCheck.fresh(records, newChapters))
            for ((id, chapter) in told) {
                val title = records[id]?.title ?: continue
                val (head, body) = com.aniliphy.ui.LookWords.release(look, chapter = true, title = title, number = number(chapter))
                ReleaseNotifications.post(app, NotifiedReleases.Kind.Chapter, id, head, body)
            }
        }
    }

    private suspend fun foundOnSources(history: Map<Int, MangaRecord>): Map<Int, MangaRecord> {
        val userId = loggedInUser() ?: return emptyMap()
        val entries = try {
            AniListServices.get(app).api.reading(userId).value
        } catch (_: AniListException) {
            return emptyMap()
        }
        val found = FoundSources(File(app.filesDir, "manga_found_sources.json"))
        val order = MangaSites.order(MangaSettings.get(app).source).map { it.id }
        val now = System.currentTimeMillis()
        var searches = 0
        val records = mutableMapOf<Int, MangaRecord>()
        for (entry in entries) {
            val media = entry.media
            if (media.kind != MediaKind.Manga || history[media.id]?.source != null) continue
            var known = found.all()[media.id]
            if ((known == null || (known.source == null && now - known.looked > FoundSources.LOOK_AGAIN_MS)) && searches < FoundSources.SEARCHES_PER_CHECK) {
                searches++
                val titles = listOfNotNull(media.title.romaji, media.title.english)
                val source = try {
                    FoundSources.look(titles, order) { site, query -> MangaSites.site(site).search(query) }
                } catch (_: IOException) {
                    continue
                }
                known = FoundSources.Found(source, now)
                found.put(media.id, known)
            }
            val source = known?.source ?: continue
            records[media.id] = MangaRecord(source = source, title = media.title.display, chapterNumber = entry.progress.toDouble())
        }
        return records
    }

    private suspend fun loggedInUser(): Int? {
        val services = AniListServices.get(app)
        if (services.session.state is SessionState.Loading) withContext(Dispatchers.Main) { services.session.restore() }
        return services.session.userId
    }

    private suspend fun episodes() {
        val services = AniListServices.get(app)
        val userId = loggedInUser() ?: return
        val entries = try {
            services.api.watching(userId, refresh = true).value
        } catch (_: AniListException) {
            return
        }
        withContext(Dispatchers.IO) {
            val items = entries.map { NewEpisodes.Airing(it.media.id, it.media.nextAiringEpisode?.episode, it.progress) }
            val out = NewEpisodes(File(app.filesDir, "anime_new_episodes.json")).check(items)
            val told = notified.firstTime(NotifiedReleases.Kind.Episode, out.mapValues { it.value.toDouble() })
            val titles = entries.associate { it.media.id to it.media.title.display }
            for ((id, episode) in told) {
                val title = titles[id] ?: continue
                val (head, body) = com.aniliphy.ui.LookWords.release(look, chapter = false, title = title, number = number(episode))
                ReleaseNotifications.post(app, NotifiedReleases.Kind.Episode, id, head, body)
            }
        }
    }

    /** Replies, mentions, messages and follows since the last check; doesn't mark anything read. */
    private suspend fun inbox() {
        val api = AniListServices.get(app).api
        loggedInUser() ?: return
        val notes = try {
            api.notifications(1, markRead = false, refresh = true).value.items
        } catch (_: AniListException) {
            return
        }
        val last = com.aniliphy.ui.SocialPrefs.lastNoteId(app)
        notes.maxOfOrNull { it.id }?.let { if (last == null || it > last) com.aniliphy.ui.SocialPrefs.setLastNoteId(app, it) }
        val news = com.aniliphy.anilist.Inbox.forPhone(notes, last)
        if (news.size > 3) {
            ReleaseNotifications.postInbox(app, news.first().id, "${news.size} new on AniList", news.take(3).joinToString(" · ") { com.aniliphy.anilist.Inbox.text(it) })
        } else {
            news.forEach { n -> ReleaseNotifications.postInbox(app, n.id, "AniList", com.aniliphy.anilist.Inbox.text(n)) }
        }
    }

    private suspend fun friends() {
        val api = AniListServices.get(app).api
        val userId = loggedInUser() ?: return
        val feed = try {
            api.feed(1, refresh = true).value.items
        } catch (_: AniListException) {
            return
        }
        val last = com.aniliphy.ui.SocialPrefs.lastFeedId(app)
        com.aniliphy.anilist.FriendNews.newest(feed, last)?.let { com.aniliphy.ui.SocialPrefs.setLastFeedId(app, it) }
        if (last == null) return
        val onList = try {
            (api.animeList(userId).value + api.mangaList(userId).value).map { it.media.id }.toSet()
        } catch (_: AniListException) {
            return
        }
        val news = com.aniliphy.anilist.FriendNews.finished(feed, last, onList, userId)
        if (news.size > com.aniliphy.anilist.FriendNews.MAX_SINGLE) {
            val names = news.mapNotNull { it.user?.name }.distinct()
            ReleaseNotifications.postFriend(
                app, news.first().id, "${news.size} finishes from friends",
                names.take(3).joinToString(", ") + if (names.size > 3) " and others" else "",
            )
        } else {
            news.forEach { a ->
                ReleaseNotifications.postFriend(
                    app, a.id, "${a.user!!.name} finished ${a.media!!.title.display}", "It's on your list too",
                )
            }
        }
    }

    companion object {
        private const val NAME = "new_releases"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewReleasesWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        internal fun number(value: Double): String =
            if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
