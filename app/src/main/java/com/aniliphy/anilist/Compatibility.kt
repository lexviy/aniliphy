package com.aniliphy.anilist

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One title on both lists, with both scores (0 = not scored). */
data class SharedTitle(val mine: ListEntry, val theirs: ListEntry) {
    val media: Media get() = mine.media
    val bothScored: Boolean get() = mine.score > 0 && theirs.score > 0
}

/**
 * Your lists next to someone else's. [percent] is how alike your scores are on what you both scored (null with too
 * few in common to say).
 */
data class ListComparison(
    val shared: List<SharedTitle>,
    val percent: Int?,
    /** Scored by both, furthest apart first. */
    val disagreements: List<SharedTitle>,
    /** Scored by both, closest and highest first. */
    val agreements: List<SharedTitle>,
    /** What they scored highly (or completed, if they don't score) that isn't on your list. */
    val theyLoved: List<ListEntry>,
    /** And the other way round. */
    val youLoved: List<ListEntry>,
    val scoredTogether: Int,
    /**
     * For when there aren't enough scores: of the shorter list (not counting planning), how much is on the other
     * one too, 0–100. Null when either list is empty.
     */
    val overlap: Int? = null,
)

object Compatibility {
    /** Fewer titles scored by both than this and there's no percent. */
    const val MIN_SCORED = 5

    fun compare(mine: List<ListEntry>, theirs: List<ListEntry>, limit: Int = 20): ListComparison {
        val myById = mine.associateBy { it.media.id }
        val theirById = theirs.associateBy { it.media.id }
        val shared = mine.mapNotNull { m -> theirById[m.media.id]?.let { SharedTitle(m, it) } }
            .filter { it.mine.status != ListStatus.PLANNING || it.theirs.status != ListStatus.PLANNING }
        val scored = shared.filter { it.bothScored }
        return ListComparison(
            shared = shared,
            percent = percent(scored),
            disagreements = scored.sortedByDescending { abs(it.mine.score - it.theirs.score) }
                .filter { abs(it.mine.score - it.theirs.score) >= 2.0 }.take(limit),
            agreements = scored.sortedWith(compareBy<SharedTitle> { abs(it.mine.score - it.theirs.score) }.thenByDescending { it.mine.score + it.theirs.score })
                .take(limit),
            theyLoved = loved(theirs).filter { it.media.id !in myById }.take(limit),
            youLoved = loved(mine).filter { it.media.id !in theirById }.take(limit),
            scoredTogether = scored.size,
            overlap = overlap(mine, theirs, shared.size),
        )
    }

    private fun overlap(mine: List<ListEntry>, theirs: List<ListEntry>, shared: Int): Int? {
        val smaller = minOf(mine.count { it.status != ListStatus.PLANNING }, theirs.count { it.status != ListStatus.PLANNING })
        if (smaller == 0) return null
        return (100.0 * shared / smaller).roundToInt().coerceIn(0, 100)
    }

    /**
     * How alike two sets of scores are, 0–100: the correlation of the scores (each person's habits taken out by
     * comparing against their own mean), mixed with how close the raw scores are, so two people who give everything
     * 8 still match.
     */
    fun percent(scored: List<SharedTitle>): Int? {
        if (scored.size < MIN_SCORED) return null
        val a = scored.map { it.mine.score }
        val b = scored.map { it.theirs.score }
        val ma = a.average()
        val mb = b.average()
        val cov = a.indices.sumOf { (a[it] - ma) * (b[it] - mb) }
        val va = a.sumOf { (it - ma) * (it - ma) }
        val vb = b.sumOf { (it - mb) * (it - mb) }
        val correlation = if (va == 0.0 || vb == 0.0) null else cov / sqrt(va * vb)
        val closeness = 1.0 - a.indices.map { abs(a[it] - b[it]) }.average() / 9.0
        val mixed = if (correlation == null) closeness else 0.6 * ((correlation + 1) / 2) + 0.4 * closeness
        return (mixed * 100).roundToInt().coerceIn(0, 100)
    }

    private fun loved(entries: List<ListEntry>): List<ListEntry> {
        val scores = entries.map { it.score }.filter { it > 0 }
        return if (scores.size >= MIN_SCORED) {
            val bar = maxOf(8.0, scores.sorted()[(scores.size * 0.8).toInt().coerceAtMost(scores.lastIndex)])
            entries.filter { it.score >= bar }.sortedByDescending { it.score }
        } else {
            entries.filter { it.status == ListStatus.COMPLETED || it.status == ListStatus.REPEATING }.sortedByDescending { it.repeat }
        }
    }
}
