package com.aniliphy.anilist

import kotlinx.serialization.Serializable
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

/** A title as Discover needs it: enough to score it, show it and explain it. */
@Serializable
data class DiscoverMedia(
    val id: Int = 0,
    val idMal: Int? = null,
    val type: String? = null,
    val format: String? = null,
    val status: String? = null,
    val title: MediaTitle = MediaTitle(),
    val coverImage: CoverImage = CoverImage(),
    val bannerImage: String? = null,
    val description: String? = null,
    val averageScore: Int? = null,
    val popularity: Int? = null,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val seasonYear: Int? = null,
    val isAdult: Boolean = false,
    val genres: List<String> = emptyList(),
    val tags: List<MediaTag> = emptyList(),
    val studios: Nodes<Named> = Nodes(),
    val staff: Edges<StaffEdge> = Edges(),
    val trailer: Trailer? = null,
    val mediaListEntry: ListMark? = null,
) {
    fun toMedia(): Media = Media(
        id = id,
        idMal = idMal,
        type = type,
        format = format,
        status = status,
        title = title,
        description = description,
        coverImage = coverImage,
        bannerImage = bannerImage,
        averageScore = averageScore,
        episodes = episodes,
        chapters = chapters,
        genres = genres,
        tags = tags,
        seasonYear = seasonYear,
        studios = studios,
    )
}

@Serializable
data class ListMark(val status: String? = null)

/** One title on your list, with what Discover learns from it. */
@Serializable
data class TasteEntry(val status: String? = null, val score: Double = 0.0, val media: DiscoverMedia = DiscoverMedia())

enum class ReasonKind { Genre, Tag, Studio, Staff, Seed, Friends, Learned, Quality }

@Serializable
data class Reason(val label: String, val weight: Double, val kind: ReasonKind)

@Serializable
data class Suggestion(val media: DiscoverMedia, val match: Int, val reasons: List<Reason>) {
    /** "Like Frieren (9) · Iyashikei", for under a cover. */
    val short: String
        get() = reasons.filter { it.weight > 0 && it.kind != ReasonKind.Quality }.take(2).joinToString(" · ") { it.label.removeSuffix(" (tag)") }

    /** The tag it leaned on most, for "Less …". */
    val mainTag: String?
        get() = reasons.firstOrNull { it.kind == ReasonKind.Tag && it.weight > 0 }?.label?.removeSuffix(" (tag)")
}

/** What "Not for me" taught it. */
@Serializable
data class DiscoverLearned(
    val hidden: Set<Int> = emptySet(),
    val lessTags: Set<String> = emptySet(),
    val lessStudios: Set<String> = emptySet(),
)

/** Someone you follow and what they loved (media id to their score on 10; 0 when they don't score). */
data class FriendTaste(val name: String, val loved: Map<Int, Double>)

/**
 * Suggestions worked out on the phone from your list: how much you liked each title (your score against your own
 * average, or how far you got when you don't score) spread over its genres, tags, studios and staff; AniList's
 * "if you liked X" links from the titles you liked most; and what people you follow loved.
 */
object Recommender {
    private const val TAG_MIN_RANK = 40
    private const val SHRINK = 2.0
    private const val STAFF_WEIGHT = 0.6
    private const val SEED_WEIGHT = 0.9
    private const val FRIEND_WEIGHT = 0.35
    private const val LESS_PENALTY = 0.7
    private const val MIN_SCORED = 5

    private data class Feature(val key: String, val label: String, val kind: ReasonKind, val weight: Double)

    private fun features(media: DiscoverMedia): List<Feature> = buildList {
        media.genres.forEach { add(Feature("g:$it", it, ReasonKind.Genre, 1.0)) }
        media.tags.filter { (it.rank ?: 0) >= TAG_MIN_RANK && !it.isMediaSpoiler && !it.isGeneralSpoiler }
            .forEach { add(Feature("t:${it.name}", "${it.name} (tag)", ReasonKind.Tag, (it.rank ?: 0) / 100.0)) }
        media.studios.nodes.mapNotNull { it.name }.forEach { add(Feature("s:$it", "Studio: $it", ReasonKind.Studio, 1.0)) }
        media.staff.edges.mapNotNull { it.node }.filter { it.id != 0 }
            .forEach { add(Feature("p:${it.id}", "Staff: ${it.name.full ?: "?"}", ReasonKind.Staff, STAFF_WEIGHT)) }
    }

    /** How much you liked an entry, about −2..2. Scores count against your own average when you score enough. */
    fun likes(mine: List<TasteEntry>): Map<Int, Double> {
        val scored = mine.filter { it.score > 0 && it.status != ListStatus.PLANNING }.map { it.score }
        val scores = scored.size >= MIN_SCORED
        val mean = if (scores) scored.average() else 0.0
        val spread = if (scores) sqrt(scored.sumOf { (it - mean) * (it - mean) } / scored.size).coerceAtLeast(0.75) else 1.0
        return mine.associate { entry ->
            val byStatus = when (entry.status) {
                ListStatus.COMPLETED -> 1.0
                ListStatus.CURRENT, ListStatus.REPEATING -> 0.6
                ListStatus.PAUSED -> -0.3
                ListStatus.DROPPED -> -1.0
                ListStatus.PLANNING -> 0.2
                else -> 0.0
            }
            val like = if (scores && entry.score > 0) {
                ((entry.score - mean) / spread).coerceIn(-2.0, 2.0) + if (entry.status == ListStatus.DROPPED) -0.5 else 0.0
            } else {
                byStatus
            }
            entry.media.id to like
        }
    }

    /** Your taste per genre, tag, studio and person, with how many titles it came from. */
    fun profile(mine: List<TasteEntry>): Map<String, Double> {
        val likes = likes(mine)
        val sum = HashMap<String, Double>()
        val weight = HashMap<String, Double>()
        for (entry in mine) {
            val like = likes[entry.media.id] ?: continue
            for (f in features(entry.media)) {
                sum[f.key] = (sum[f.key] ?: 0.0) + like * f.weight
                weight[f.key] = (weight[f.key] ?: 0.0) + f.weight
            }
        }
        return sum.mapValues { (key, total) -> total / ((weight[key] ?: 0.0) + SHRINK) }
    }

    /** The strongest liked features, to look for more of them ("t:Iyashikei" → "Iyashikei"). */
    fun favourites(mine: List<TasteEntry>, prefix: String, count: Int): List<String> =
        profile(mine).filterKeys { it.startsWith(prefix) }.filterValues { it > 0 }
            .entries.sortedByDescending { it.value }.take(count).map { it.key.removePrefix(prefix) }

    /** The titles you liked most, whose AniList recommendations are worth following. */
    fun seeds(mine: List<TasteEntry>, count: Int): List<Int> =
        likes(mine).filterValues { it > 0.4 }.entries.sortedByDescending { it.value }.take(count).map { it.key }

    /**
     * Ranks [candidates] for you. [links] are AniList's recommendations: seed media id to (candidate id, votes).
     */
    fun rank(
        mine: List<TasteEntry>,
        candidates: Collection<DiscoverMedia>,
        links: Map<Int, List<Pair<Int, Int>>> = emptyMap(),
        friends: List<FriendTaste> = emptyList(),
        learned: DiscoverLearned = DiscoverLearned(),
        limit: Int = 40,
    ): List<Suggestion> {
        val profile = profile(mine)
        val likes = likes(mine)
        val onList = mine.map { it.media.id }.toSet()
        val byId = mine.associateBy { it.media.id }
        val linked = HashMap<Int, MutableList<Pair<Int, Int>>>()
        links.forEach { (seed, list) -> list.forEach { (candidate, votes) -> linked.getOrPut(candidate) { mutableListOf() } += seed to votes } }

        val scored = candidates.asSequence()
            .distinctBy { it.id }
            .filter { it.id !in onList && it.id !in learned.hidden && it.mediaListEntry == null && !it.isAdult }
            .map { candidate ->
                val reasons = ArrayList<Reason>()
                val feats = features(candidate)
                val norm = sqrt(feats.sumOf { it.weight } + 1.0)
                for (f in feats) {
                    val taste = profile[f.key] ?: continue
                    val part = taste * f.weight / norm
                    if (kotlin.math.abs(part) < 0.01) continue
                    val label = if (part < 0 && f.kind in setOf(ReasonKind.Tag, ReasonKind.Genre)) "${f.label.removeSuffix(" (tag)")} (you drop these)" else f.label
                    reasons += Reason(label, part, f.kind)
                }
                val named = ArrayList<String>()
                linked[candidate.id]?.sortedByDescending { likes[it.first] ?: 0.0 }?.forEach { (seed, votes) ->
                    if (named.size >= 3) return@forEach
                    val like = likes[seed] ?: return@forEach
                    if (like <= 0) return@forEach
                    val strength = (ln(1.0 + votes) / ln(51.0)).coerceAtMost(1.5)
                    val entry = byId[seed]
                    val title = entry?.media?.title?.display ?: "?"
                    // Another season of a series already named says nothing new ("86" and "86 Part 2").
                    val key = title.lowercase()
                    if (named.any { key.startsWith(it) || it.startsWith(key) }) return@forEach
                    named += key
                    val label = if ((entry?.score ?: 0.0) > 0) "Like $title (${number(entry!!.score)})" else "Like $title"
                    reasons += Reason(label, SEED_WEIGHT * like.coerceAtMost(1.5) * strength / 1.5, ReasonKind.Seed)
                }
                val fans = friends.mapNotNull { friend -> friend.loved[candidate.id]?.let { friend to it } }
                if (fans.isNotEmpty()) {
                    val scores = fans.map { it.second }.filter { it > 0 }.sortedDescending()
                    val who = if (fans.size == 1) "${fans[0].first.name} loved it" else "${fans.size} you follow loved it"
                    val label = if (scores.isEmpty()) who else "$who: ${scores.take(3).joinToString(", ") { number(it) }}"
                    reasons += Reason(label, FRIEND_WEIGHT * fans.size.coerceAtMost(3), ReasonKind.Friends)
                }
                candidate.tags.filter { (it.rank ?: 0) >= TAG_MIN_RANK && it.name in learned.lessTags }
                    .forEach { reasons += Reason("Less ${it.name} (you asked)", -LESS_PENALTY, ReasonKind.Learned) }
                candidate.studios.nodes.mapNotNull { it.name }.filter { it in learned.lessStudios }
                    .forEach { reasons += Reason("Less from $it (you asked)", -LESS_PENALTY, ReasonKind.Learned) }
                candidate.averageScore?.let { reasons += Reason("AniList ${it}%", (it - 70) / 200.0, ReasonKind.Quality) }
                candidate to reasons
            }
            .map { (candidate, reasons) -> Triple(candidate, reasons.sumOf { it.weight }, reasons) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()
        val top = scored.firstOrNull()?.second ?: return emptyList()
        val scale = top / 1.4
        return scored.map { (candidate, total, reasons) ->
            Suggestion(
                media = candidate,
                match = (50 + 49 * tanh(total / scale)).toInt().coerceIn(1, 99),
                reasons = reasons.sortedByDescending { kotlin.math.abs(it.weight) }.take(8),
            )
        }
    }

    /** What people you follow loved: a score of 8 or more, or finished when they don't score. */
    fun loved(entries: List<ListEntry>): Map<Int, Double> {
        val scores = entries.count { it.score > 0 } >= MIN_SCORED
        return entries.filter { entry ->
            if (scores) entry.score >= 8.0 else entry.status == ListStatus.COMPLETED
        }.associate { it.media.id to if (scores) it.score else 0.0 }
    }

    fun number(score: Double): String = if (score % 1.0 == 0.0) score.toInt().toString() else "%.1f".format(java.util.Locale.ROOT, score)
}
