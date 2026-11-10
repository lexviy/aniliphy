package com.aniliphy.widget

import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Recommendation
import kotlin.math.ln

/** A title the recommendations start from: something being watched or read now, or finished with a high score. */
data class RecommendationSeed(val mediaId: Int, val title: String, val kind: MediaKind)

/**
 * Turns the community recommendations of a few of your titles into one list for the widget. Pure, so it can be
 * tested.
 */
object WidgetRecommendations {
    /** Seeds of each kind, so anime, manga and light novels each get their own recommendations. */
    const val SEEDS_PER_KIND = 4
    const val LIMIT = 15

    private val WATCHING = setOf(ListStatus.CURRENT, ListStatus.REPEATING)

    /**
     * Picks the titles to start from: of each kind, what you're watching or reading now (latest first) and what
     * you finished with the highest scores, half and half when there are both.
     */
    fun seeds(entries: List<ListEntry>, perKind: Int = SEEDS_PER_KIND): List<RecommendationSeed> {
        val half = (perKind + 1) / 2
        return MediaKind.entries.flatMap { kind ->
            val mine = entries.filter { it.media.kind == kind }
            val now = mine.filter { it.status in WATCHING }.sortedByDescending { it.updatedAt ?: 0 }
            val loved = mine.filter { it.status == ListStatus.COMPLETED && it.score > 0 }
                .sortedWith(compareByDescending<ListEntry> { it.score }.thenByDescending { it.updatedAt ?: 0 })
            (now.take(half) + loved.take(half) + now.drop(half) + loved.drop(half))
                .distinctBy { it.media.id }
                .take(perKind)
                .map { RecommendationSeed(it.media.id, it.media.title.display, kind) }
        }
    }

    /** Without AniList lists (guest mode), the titles in this phone's history, up to [perKind] of each kind. */
    fun seedsFromHistory(history: List<RecommendationSeed>, perKind: Int = SEEDS_PER_KIND): List<RecommendationSeed> =
        history.distinctBy { it.mediaId }.groupBy { it.kind }.values.flatMap { it.take(perKind) }

    /**
     * Each recommendation earns points from every seed that recommends it, more for more votes, so a title
     * recommended from several of your favourites comes first. Anything in [exclude] (your lists and history) is
     * left out. The kinds take turns, so anime, manga and light novels all show up.
     */
    fun pick(
        seeds: List<RecommendationSeed>,
        recommendations: Map<Int, List<Recommendation>>,
        exclude: Set<Int>,
        limit: Int = LIMIT,
    ): List<WidgetItem> {
        class Found(val kind: MediaKind, val title: String, val cover: String?) {
            var points = 0.0
            var best = 0.0
            var because: RecommendationSeed? = null
        }
        val seedIds = seeds.map { it.mediaId }.toSet()
        val found = LinkedHashMap<Int, Found>()
        for (seed in seeds) {
            for (recommendation in recommendations[seed.mediaId].orEmpty()) {
                val media = recommendation.mediaRecommendation ?: continue
                if (media.id in exclude || media.id in seedIds) continue
                val title = media.title.display.takeIf { it.isNotBlank() && it != "?" } ?: continue
                val earned = 1 + ln(1.0 + (recommendation.rating ?: 0).coerceAtLeast(0))
                val item = found.getOrPut(media.id) { Found(media.kind, title, media.coverImage.best) }
                item.points += earned
                if (earned > item.best) {
                    item.best = earned
                    item.because = seed
                }
            }
        }
        val byKind = found.entries
            .sortedByDescending { it.value.points }
            .groupBy { it.value.kind }
            .mapValues { (_, items) -> ArrayDeque(items) }
        val order = listOf(MediaKind.Anime, MediaKind.Manga, MediaKind.LightNovel).filter { it in byKind }
        val picked = mutableListOf<WidgetItem>()
        while (picked.size < limit && byKind.values.any { it.isNotEmpty() }) {
            for (kind in order) {
                val (id, item) = byKind.getValue(kind).removeFirstOrNull() ?: continue
                picked += WidgetItem(
                    kind = WidgetKind.Recommendation,
                    key = id.toString(),
                    title = item.title,
                    detail = item.because?.let(::reason).orEmpty(),
                    cover = item.cover,
                    of = item.kind.widgetKind,
                )
                if (picked.size == limit) break
            }
        }
        return picked
    }

    fun reason(seed: RecommendationSeed): String =
        (if (seed.kind == MediaKind.Anime) "Because you watched " else "Because you read ") + seed.title

    val MediaKind.widgetKind: WidgetKind
        get() = when (this) {
            MediaKind.Anime -> WidgetKind.Anime
            MediaKind.Manga -> WidgetKind.Manga
            MediaKind.LightNovel -> WidgetKind.Novel
        }
}
