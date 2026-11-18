package com.aniliphy.anilist

import kotlin.math.ceil

/** Titles that stopped moving, and when the ones going will be finished at your pace. */
object Backlog {
    const val DAY_S = 24L * 60 * 60
    const val PACE_DAYS = 30

    /**
     * Entries you're watching or reading that haven't moved in [weeks] weeks (all times in seconds), the longest
     * idle first. [notNow] maps a media id to when "Not now" was tapped: it stays quiet until the entry moves again
     * or another [weeks] pass.
     */
    fun stalled(entries: List<ListEntry>, nowS: Long, weeks: Int, notNow: Map<Int, Long> = emptyMap()): List<ListEntry> {
        if (weeks <= 0) return emptyList()
        val limit = weeks * 7 * DAY_S
        return entries.filter { e ->
            val at = lastMoved(e) ?: return@filter false
            e.status == ListStatus.CURRENT && nowS - at >= limit &&
                notNow[e.media.id]?.let { tapped -> tapped < at || nowS - tapped >= limit } != false
        }.sortedBy { lastMoved(it) }
    }

    /** When the entry last changed. AniList answers 0 for some old imported entries: then when it was added, or null. */
    fun lastMoved(e: ListEntry): Long? = e.updatedAt?.takeIf { it > 0 } ?: e.createdAt?.takeIf { it > 0 }

    /** Whole weeks since [updatedAtS]. */
    fun weeksIdle(updatedAtS: Long, nowS: Long): Int = ((nowS - updatedAtS) / (7 * DAY_S)).toInt()

    /** The numbers in a list activity's progress: "5 - 7" → 5..7, "7" → 7..7. */
    fun span(progress: String?): IntRange? {
        val numbers = Regex("""\d+""").findAll(progress ?: return null).map { it.value.toInt() }.toList()
        return when (numbers.size) {
            0 -> null
            1 -> numbers[0]..numbers[0]
            else -> numbers.min()..numbers.max()
        }
    }

    /**
     * Episodes or chapters a day for each title over the last [PACE_DAYS] days, from list activity
     * ("watched episode 5 - 7"). Only titles with something in the window.
     */
    fun pace(activities: List<Activity>, nowS: Long): Map<Int, Double> {
        val from = nowS - PACE_DAYS * DAY_S
        return activities.asSequence()
            .filter { (it.createdAt ?: 0) >= from && it.media != null }
            .mapNotNull { a -> span(a.progress)?.let { a.media!!.id to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, spans) -> spans.toSet().sumOf { it.last - it.first + 1 }.toDouble() / PACE_DAYS }
            .filterValues { it > 0 }
    }

    data class Finish(val entry: ListEntry, val left: Int?, val days: Int?)

    /** For each entry going, what's left and in how many days it'd be done at its pace (null: no pace or no end). */
    fun finishing(entries: List<ListEntry>, pace: Map<Int, Double>): List<Finish> =
        entries.filter { it.status == ListStatus.CURRENT || it.status == ListStatus.REPEATING }.map { e ->
            val total = if (e.media.type == "ANIME") e.media.episodes else e.media.chapters
            val left = total?.let { (it - e.progress).coerceAtLeast(0) }
            val rate = pace[e.media.id]
            val days = if (left != null && rate != null && rate > 0) ceil(left / rate).toInt() else null
            Finish(e, left, days)
        }.sortedWith(compareBy(nullsLast()) { it.days })
}
