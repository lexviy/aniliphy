package com.aniliphy.anilist

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/**
 * The activity heatmap: AniList's day-by-day history (about six months) together with what this phone counted
 * (longer, but only what was done in the app).
 */
object ActivityCalendar {
    /**
     * AniList gives each day as its midnight in some time zone; adding half a day and reading the date in UTC lands
     * on the right day whatever that zone was.
     */
    fun aniListDays(history: List<ActivityHistoryDay>): Map<LocalDate, Int> = history
        .filter { it.amount > 0 }
        .groupBy { Instant.ofEpochSecond(it.date + 12 * 3600).atOffset(ZoneOffset.UTC).toLocalDate() }
        .mapValues { (_, days) -> days.sumOf { it.amount } }

    /**
     * Both together. Things done in the app usually reach AniList too, so a day counts the larger of the two rather
     * than their sum.
     */
    fun merge(aniList: Map<LocalDate, Int>, local: Map<LocalDate, Int>): Map<LocalDate, Int> =
        (aniList.keys + local.keys).associateWith { maxOf(aniList[it] ?: 0, local[it] ?: 0) }.filterValues { it > 0 }

    /** 0 (nothing) to 4 (one of your busiest days), relative to your own busy days. */
    fun level(amount: Int, days: Map<LocalDate, Int>): Int {
        if (amount <= 0) return 0
        val sorted = days.values.filter { it > 0 }.sorted()
        if (sorted.isEmpty()) return 0
        val top = sorted[((sorted.size - 1) * 0.9).toInt()].coerceAtLeast(1)
        return (1 + (3f * amount / top).toInt()).coerceIn(1, 4)
    }

    /** Days in a row with something done, up to today (today counts once something's been done). */
    fun streak(days: Map<LocalDate, Int>, today: LocalDate): Int {
        var streak = 0
        var day = if ((days[today] ?: 0) > 0) today else today.minusDays(1)
        while ((days[day] ?: 0) > 0) {
            streak++
            day = day.minusDays(1)
        }
        return streak
    }

    fun longestStreak(days: Map<LocalDate, Int>): Int {
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        days.filterValues { it > 0 }.keys.sorted().forEach { day ->
            run = if (previous != null && previous!!.plusDays(1) == day) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }

    /** Columns of seven days (Monday first) ending with this week, [weeks] of them. */
    fun weeks(today: LocalDate, weeks: Int): List<List<LocalDate>> {
        val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (weeks - 1 downTo 0).map { back ->
            val monday = lastMonday.minusWeeks(back.toLong())
            (0L..6L).map { monday.plusDays(it) }
        }
    }

    /** Streak lengths worth a moment. */
    val STREAK_MARKS = listOf(7, 30, 100)
}
