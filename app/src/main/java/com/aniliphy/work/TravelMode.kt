package com.aniliphy.work

import android.content.Context
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anime.AnimeHistory
import com.aniliphy.anime.VideoQuality
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.manga.MangaHistory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A title travel mode can get ready: what you're watching or reading, with the site it's watched or read on. */
data class TravelPick(
    val anime: Boolean,
    val mediaId: Int,
    val title: String,
    val cover: String?,
    /** Null when no site is linked yet: shown, but can't be picked. */
    val source: String?,
    val progress: Int,
    /** How many are out, when AniList knows. */
    val released: Int?,
    val total: Int?,
    val mal: Int?,
) {
    val key: String get() = (if (anime) "a" else "m") + mediaId

    /** How many can still be fetched ahead of where you are; null when unknown (the site will say). */
    val left: Int? get() = released?.let { (it - progress).coerceAtLeast(0) }
}

/** Kept from the moment travel mode got things ready, for the "welcome back" summary. */
@Serializable
data class TravelTrip(
    val startedAt: Long,
    val items: List<Item> = emptyList(),
) {
    @Serializable
    data class Item(val anime: Boolean, val mediaId: Int, val title: String, val startProgress: Int)
}

object TravelMode {
    const val ANIME_AHEAD = 3
    const val MANGA_AHEAD = 10
    const val MAX_AHEAD = 50
    private const val FILE = "travel_trip.json"
    private val json = Json { ignoreUnknownKeys = true }

    /** A rough size, from real downloads: a 24-minute 1080p episode is about 250 MB. */
    fun episodeBytes(quality: VideoQuality): Long = when (quality) {
        VideoQuality.Best, VideoQuality.P1080 -> 250L
        VideoQuality.P720 -> 150L
        VideoQuality.P480 -> 90L
        VideoQuality.P360, VideoQuality.Worst -> 60L
    } * 1_000_000

    const val CHAPTER_BYTES = 12_000_000L
    const val PAGE_BYTES = 400_000L

    /** How many [pick] gets with [ahead] asked for. */
    fun count(pick: TravelPick, ahead: Int): Int = pick.left?.let { minOf(it, ahead) } ?: ahead

    fun estimate(picks: List<Pair<TravelPick, Int>>, quality: VideoQuality, pages: Boolean): Estimate {
        val episodes = picks.filter { it.first.anime }.sumOf { (p, n) -> count(p, n) }
        val chapters = picks.filter { !it.first.anime }.sumOf { (p, n) -> count(p, n) }
        return Estimate(
            episodes, chapters,
            episodes * episodeBytes(quality), chapters * CHAPTER_BYTES,
            if (pages) picks.size * PAGE_BYTES else 0,
        )
    }

    data class Estimate(val episodes: Int, val chapters: Int, val episodeBytes: Long, val chapterBytes: Long, val pageBytes: Long) {
        val total: Long get() = episodeBytes + chapterBytes + pageBytes
    }

    /** Everything you're watching and reading, with the site from where you stopped (here or synced from anilixy). */
    fun picks(context: Context, watching: List<ListEntry>, reading: List<ListEntry>): List<TravelPick> {
        val animeHistory = AnimeHistory(File(context.filesDir, "anime_history.json")).all()
        val mangaHistory = MangaHistory(File(context.filesDir, "manga_history.json")).all()
        val anime = watching.filter { it.media.kind == MediaKind.Anime }.map { e ->
            val m = e.media
            val record = animeHistory[m.id]
            TravelPick(
                true, m.id, m.title.display, m.coverImage.best, record?.source, e.progress,
                released = m.nextAiringEpisode?.episode?.minus(1) ?: m.episodes, total = m.episodes, mal = record?.mal ?: m.idMal,
            )
        }
        val manga = reading.filter { it.media.kind == MediaKind.Manga }.map { e ->
            val m = e.media
            TravelPick(false, m.id, m.title.display, m.coverImage.best, mangaHistory[m.id]?.source, e.progress, m.chapters, m.chapters, null)
        }
        return (anime + manga).sortedWith(compareBy({ it.source == null }, { it.title.lowercase() }))
    }

    /** Puts each chosen title's next ones in the download queue; true for each title that queued anything. */
    suspend fun prepare(context: Context, chosen: List<Pair<TravelPick, Int>>, pages: Boolean): Int {
        val now = System.currentTimeMillis()
        saveTrip(context, TravelTrip(now, chosen.map { (p, _) -> TravelTrip.Item(p.anime, p.mediaId, p.title, p.progress) }))
        var queued = 0
        for ((pick, ahead) in chosen) {
            val source = pick.source ?: continue
            val item = AutoDownload(
                pick.anime, pick.mediaId, pick.title, source,
                from = pick.progress.toDouble(), to = (pick.progress + ahead).toDouble(),
                cover = pick.cover, mal = pick.mal, total = pick.total, since = now,
            )
            val ok = runCatching {
                if (pick.anime) AutoDownloadWorker.queueAnime(context, item, ahead) else AutoDownloadWorker.queueManga(context, item, ahead)
            }.getOrDefault(false)
            if (ok) queued++
        }
        if (pages) savePages(context, chosen.map { it.first })
        return queued
    }

    /** The AniList pages and covers, so they open without a connection (from the cache the app already keeps). */
    private suspend fun savePages(context: Context, picks: List<TravelPick>) {
        val services = AniListServices.get(context)
        val api = services.api
        services.session.userId?.let { user ->
            runCatching { api.animeList(user, refresh = true) }
            runCatching { api.mangaList(user, refresh = true) }
        }
        val images = coil3.SingletonImageLoader.get(context)
        for (pick in picks) {
            runCatching { api.details(pick.mediaId) }
            pick.cover?.let { images.enqueue(coil3.request.ImageRequest.Builder(context).data(it).build()) }
        }
    }

    fun trip(context: Context): TravelTrip? =
        File(context.filesDir, FILE).readTextOrNull()?.let { runCatching { json.decodeFromString<TravelTrip>(it) }.getOrNull() }

    private fun saveTrip(context: Context, trip: TravelTrip) {
        runCatching { File(context.filesDir, FILE).writeTextAtomically(json.encodeToString(trip)) }
    }

    fun endTrip(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /** What happened since the trip began, from where you stopped in each title. */
    data class Back(val episodes: Int, val chapters: Int, val waiting: Int)

    fun back(trip: TravelTrip, now: Map<String, Int>, waiting: Int): Back {
        var episodes = 0
        var chapters = 0
        for (item in trip.items) {
            val key = (if (item.anime) "a" else "m") + item.mediaId
            val moved = ((now[key] ?: item.startProgress) - item.startProgress).coerceAtLeast(0)
            if (item.anime) episodes += moved else chapters += moved
        }
        return Back(episodes, chapters, waiting)
    }
}
