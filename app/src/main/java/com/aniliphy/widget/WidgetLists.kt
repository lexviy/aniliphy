package com.aniliphy.widget

import com.aniliphy.anime.AnimeRecord
import com.aniliphy.anime.AnimeRules
import com.aniliphy.manga.LatestChapter
import com.aniliphy.manga.MangaRecord
import com.aniliphy.manga.MangaRules
import com.aniliphy.manga.NewChapters
import com.aniliphy.novels.LastRead
import com.aniliphy.ui.clock
import kotlinx.serialization.Serializable

@Serializable
enum class WidgetKind { Anime, Manga, Novel, Recommendation }

/** One card of the widget. */
@Serializable
data class WidgetItem(
    val kind: WidgetKind,
    /** The AniList id, or for light novels the series key used by the progress store. */
    val key: String,
    val title: String,
    /** Where you stopped, like "Ep 7 · 12:34", or why it is recommended. */
    val detail: String,
    /** Where the cover comes from: a URL or a file on the phone. */
    val cover: String? = null,
    /** The reduced copy of the cover the widget shows, in [WidgetData.IMAGES]. */
    val image: String? = null,
    val updated: Long = 0,
    /** For recommendations, what kind of title it is. */
    val of: WidgetKind? = null,
    /** How far through you are, for the bar under the cover, like the cards in the app. */
    val percent: Int? = null,
    /** A new episode or chapter is out that you haven't seen. */
    val isNew: Boolean = false,
)

/** Everything the widgets show. Written by the app, only read by the widgets, which never go online. */
@Serializable
data class WidgetSnapshot(
    val anime: List<WidgetItem> = emptyList(),
    val manga: List<WidgetItem> = emptyList(),
    val novels: List<WidgetItem> = emptyList(),
    val recommendations: List<WidgetItem> = emptyList(),
    /** Light novel series key → cover, remembered from the last time the library was loaded. */
    val novelCovers: Map<String, String> = emptyMap(),
    /** AniList id → cover, for titles whose saved place came without one (like those synced from anilixy). */
    val mediaCovers: Map<String, String> = emptyMap(),
    /** The series keys of the light novels on this phone, or null until the library has loaded once. */
    val library: List<String>? = null,
    /** When the recommendations were last worked out; they are redone once a day. */
    val recommended: Long = 0,
    val made: Long = 0,
) {
    val items: List<WidgetItem> get() = anime + manga + novels + recommendations
}

/** A light novel series with a place to carry on from. */
data class NovelPlace(val key: String, val last: LastRead)

/** Builds the widget's lists from what the app already keeps. Pure, so it can be tested. */
object WidgetLists {
    const val LIMIT = 10

    /** [released] is how many episodes are out (from AniList); [newOut] the titles with an episode you haven't seen. */
    fun anime(
        records: Map<Int, AnimeRecord>,
        released: Map<Int, Int> = emptyMap(),
        newOut: Set<Int> = emptySet(),
        limit: Int = LIMIT,
    ): List<WidgetItem> =
        records.filterValues { it.hasPlace && !it.title.isNullOrBlank() }
            .entries.sortedByDescending { it.value.updated }
            .take(limit)
            .map { (id, record) ->
                WidgetItem(
                    WidgetKind.Anime,
                    id.toString(),
                    record.title!!,
                    animePlace(record),
                    record.cover,
                    updated = record.updated,
                    percent = AnimeRules.percent(record, released[id]?.toDouble()),
                    isNew = id in newOut,
                )
            }

    /** [latest] is the newest chapter found at each title's source, as the app keeps it. */
    fun manga(records: Map<Int, MangaRecord>, latest: Map<Int, LatestChapter> = emptyMap(), limit: Int = LIMIT): List<WidgetItem> =
        records.filterValues { it.hasPlace && !it.title.isNullOrBlank() }
            .entries.sortedByDescending { it.value.updated }
            .take(limit)
            .map { (id, record) ->
                WidgetItem(
                    WidgetKind.Manga,
                    id.toString(),
                    record.title!!,
                    mangaPlace(record),
                    record.cover,
                    updated = record.updated,
                    percent = MangaRules.percent(record, latest[id]?.latest),
                    isNew = NewChapters.isNew(latest[id], record),
                )
            }

    /** [titles] and [covers] come from the library, keyed by series; the progress store's own title is the fallback. */
    /** With a [library], only the books on this phone are kept: the others can't be opened from the widget. */
    fun novels(
        places: List<NovelPlace>,
        titles: Map<String, String> = emptyMap(),
        covers: Map<String, String> = emptyMap(),
        library: Collection<String>? = null,
        limit: Int = LIMIT,
    ): List<WidgetItem> =
        places.filter { library == null || it.key in library }.mapNotNull { place ->
            val title = titles[place.key] ?: place.last.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WidgetItem(
                WidgetKind.Novel,
                place.key,
                title,
                novelPlace(place.last),
                covers[place.key],
                updated = (place.last.updated * 1000).toLong(),
            )
        }.sortedByDescending { it.updated }.take(limit)

    fun animePlace(record: AnimeRecord): String {
        val episode = record.episodeNumber?.let { "Ep ${number(it)}" } ?: record.episodeName ?: "Episode"
        return if (record.positionMs > 0) "$episode · ${clock(record.positionMs)}" else episode
    }

    fun mangaPlace(record: MangaRecord): String {
        val chapter = record.chapterNumber?.let { "Ch ${number(it)}" } ?: record.chapterName ?: "Chapter"
        return if (record.pages > 0) "$chapter · p. ${record.page + 1}/${record.pages}" else chapter
    }

    /** The progress store's label is like "Chapter 4 of 12"; the widget has room for "Chapter 4". */
    fun novelPlace(last: LastRead): String =
        last.label.substringBefore(" of ").trim().ifEmpty { "Chapter ${last.chapter}" }

    private fun number(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
