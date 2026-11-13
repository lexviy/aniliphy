package com.aniliphy.anilist

import kotlin.random.Random

/** How long a title takes, for the Planning roulette. */
enum class RouletteLength(val label: String) {
    Any("Any length"), Evening("An evening"), Short("Short"), Long("Long");

    companion object {
        /** Minutes of anime, or chapters of manga and light novels, up to which a title counts as [Evening] or [Short]. */
        const val EVENING_MINUTES = 180
        const val SHORT_MINUTES = 13 * 24 + 30
        const val EVENING_CHAPTERS = 10
        const val SHORT_CHAPTERS = 60
    }
}

data class RouletteFilter(
    val kind: MediaKind = MediaKind.Anime,
    val length: RouletteLength = RouletteLength.Any,
    val genre: String? = null,
    /** AniList formats (TV, MOVIE…), or null for any. */
    val formats: Set<String>? = null,
)

/** Picks something from your Planning list. Pure, so the draw can be tested with a fixed [Random]. */
object Roulette {
    /** The size of [media]: total minutes for anime, chapters otherwise; null when AniList doesn't know yet. */
    fun size(media: Media): Int? = if (media.kind == MediaKind.Anime) {
        val eps = media.episodes ?: return null
        eps * (media.duration ?: 24)
    } else {
        media.chapters ?: media.volumes?.let { it * 8 }
    }

    fun length(media: Media): RouletteLength? {
        val size = size(media) ?: return if (media.status == "RELEASING") RouletteLength.Long else null
        val anime = media.kind == MediaKind.Anime
        return when {
            size <= (if (anime) RouletteLength.EVENING_MINUTES else RouletteLength.EVENING_CHAPTERS) -> RouletteLength.Evening
            size <= (if (anime) RouletteLength.SHORT_MINUTES else RouletteLength.SHORT_CHAPTERS) -> RouletteLength.Short
            else -> RouletteLength.Long
        }
    }

    fun candidates(entries: Collection<ListEntry>, filter: RouletteFilter): List<ListEntry> = entries
        .filter { it.status == ListStatus.PLANNING && it.media.kind == filter.kind }
        .filter { e -> filter.genre?.let { it in e.media.genres } ?: true }
        .filter { e -> filter.formats?.let { e.media.format in it } ?: true }
        .filter { e -> filter.length == RouletteLength.Any || length(e.media) == filter.length }
        .sortedBy { it.media.id }

    /** A random one, not [avoid] (the last pick) when there's anything else. */
    fun pick(candidates: List<ListEntry>, random: Random, avoid: Int? = null): ListEntry? {
        val pool = candidates.filter { it.media.id != avoid }.ifEmpty { candidates }
        return pool.takeIf { it.isNotEmpty() }?.random(random)
    }

    /**
     * The covers the reel passes before stopping on [winner]: [count] random others, then the winner last. With few
     * candidates they repeat, so it still spins.
     */
    fun reel(candidates: List<ListEntry>, winner: ListEntry, random: Random, count: Int = 24): List<ListEntry> {
        val others = candidates.filter { it.media.id != winner.media.id }.ifEmpty { listOf(winner) }
        return List(count) { others.random(random) } + winner
    }
}
