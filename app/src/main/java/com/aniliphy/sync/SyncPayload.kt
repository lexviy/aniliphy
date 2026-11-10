package com.aniliphy.sync

import com.aniliphy.anime.AnimeRecord
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.manga.MangaRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToLong

@Serializable
data class SyncedAnime(
    val title: String? = null,
    val show: String? = null,
    val episode: Double? = null,
    val position: Double? = null,
    val duration: Double? = null,
    val updated: Double = 0.0,
)

@Serializable
data class SyncedManga(
    val title: String? = null,
    val source: String? = null,
    @SerialName("chapter_id") val chapterId: String? = null,
    val chapter: String? = null,
    val number: Double? = null,
    val page: Int = 0,
    val updated: Double = 0.0,
)

@Serializable
data class SyncPayload(
    val app: String = APP,
    val version: Int = VERSION,
    val anime: Map<String, SyncedAnime> = emptyMap(),
    val manga: Map<String, SyncedManga> = emptyMap(),
    val novels: Map<String, JsonObject> = emptyMap(),
) {
    companion object {
        const val APP = "anilixy-sync"
        const val VERSION = 1
        const val FILE_NAME = "anilixy-sync.json"

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        fun parse(text: String): SyncPayload? =
            runCatching { json.decodeFromString<SyncPayload>(text) }.getOrNull()
                ?.takeIf { it.app == APP && it.version <= VERSION }
    }
}

object SyncRules {
    private fun <T> newer(local: T?, remote: T?, updated: (T) -> Double): T? = when {
        local == null -> remote
        remote == null -> local
        updated(remote) > updated(local) -> remote
        else -> local
    }

    fun merge(local: SyncPayload, remote: SyncPayload): SyncPayload {
        val anime = (local.anime.keys + remote.anime.keys).associateWith { id ->
            val l = local.anime[id]
            val r = remote.anime[id]
            val won = newer(l, r) { it.updated }!!
            if (won.show == null) won.copy(show = l?.show ?: r?.show) else won
        }
        val manga = (local.manga.keys + remote.manga.keys).associateWith { id ->
            val l = local.manga[id]
            val r = remote.manga[id]
            val won = newer(l, r) { it.updated }!!
            if (won.source == null) won.copy(source = l?.source ?: r?.source) else won
        }
        val novels = (local.novels.keys + remote.novels.keys).associateWith { id ->
            newer(local.novels[id], remote.novels[id]) { (it["updated"] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }!!
        }
        return SyncPayload(anime = anime, manga = manga, novels = novels)
    }

    private fun seconds(ms: Long) = ms / 1000.0

    private fun tenths(ms: Long) = (ms / 100.0).roundToLong() / 10.0

    /** How anilixy names the show at [source], when its site is installed and anilixy uses it. */
    fun showOf(source: String?): String? {
        val (id, url) = AnimeSites.parse(source) ?: return null
        return AnimeSites.find(id)?.syncedShow(url)
    }

    /** The source for a show named by anilixy, from the first installed site that knows the name. */
    fun sourceOf(show: String): String? =
        AnimeSites.all().firstNotNullOfOrNull { site -> site.showFromSync(show)?.let { AnimeSites.sourceId(site.id, it) } }

    fun anime(record: AnimeRecord) = SyncedAnime(
        title = record.title,
        show = showOf(record.source),
        episode = record.episodeNumber,
        position = record.episodeNumber?.let { tenths(record.positionMs) },
        duration = record.episodeNumber?.let { tenths(record.durationMs) }?.takeIf { it > 0 },
        updated = seconds(record.updated),
    )

    fun applyAnime(old: AnimeRecord, synced: SyncedAnime): AnimeRecord {
        val source = synced.show?.let(::sourceOf)
        val newSource = source != null && source != old.source
        val sameEpisode = !newSource && synced.episode == old.episodeNumber
        return old.copy(
            source = source ?: old.source,
            backup = if (newSource) null else old.backup,
            episodeId = if (sameEpisode) old.episodeId else null,
            episodeName = if (sameEpisode) old.episodeName else null,
            episodeNumber = synced.episode,
            positionMs = synced.episode?.let { ((synced.position ?: 0.0) * 1000).roundToLong() } ?: 0,
            durationMs = synced.episode?.let { ((synced.duration ?: 0.0) * 1000).roundToLong() } ?: 0,
            title = old.title ?: synced.title,
            updated = (synced.updated * 1000).roundToLong(),
        )
    }

    private fun mangaSite(source: String?) = MangaSites.find(MangaSites.parse(source)?.first)

    private fun desktopChapterId(source: String?, id: String?): String? =
        id?.let { mangaSite(source)?.syncedChapterId(it) ?: it }

    private fun phoneChapterId(source: String?, id: String?): String? =
        id?.let { mangaSite(source)?.chapterIdFromSync(it) ?: it }

    fun manga(record: MangaRecord) = SyncedManga(
        title = record.title,
        source = record.source,
        chapterId = desktopChapterId(record.source, record.chapterId),
        chapter = record.chapterName,
        number = record.chapterNumber,
        page = record.page,
        updated = seconds(record.updated),
    )

    fun applyManga(old: MangaRecord, synced: SyncedManga): MangaRecord {
        val source = synced.source ?: old.source
        val chapterId = phoneChapterId(source, synced.chapterId)
        val number = synced.number ?: synced.chapter?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        return old.copy(
            source = source,
            chapterId = chapterId,
            chapterName = synced.chapter,
            chapterNumber = number,
            page = synced.page,
            pages = if (chapterId != null && chapterId == old.chapterId) old.pages else 0,
            title = old.title ?: synced.title,
            updated = (synced.updated * 1000).roundToLong(),
        )
    }
}
