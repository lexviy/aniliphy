package com.aniliphy.anilist

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** What a goal counts. The "app" ones come from what this phone logged (the lists only keep totals). */
@Serializable
enum class GoalKind(val label: String, val unit: String, val fromApp: Boolean = false) {
    AnimeFinished("Finish anime", "anime"),
    MangaFinished("Finish manga", "manga"),
    NovelsFinished("Finish light novels", "light novels"),
    TitlesFinished("Finish anything", "titles"),
    ChaptersRead("Read chapters in the app", "chapters", fromApp = true),
    HoursWatched("Watch in the app", "hours", fromApp = true),
}

@Serializable
enum class GoalPeriod(val label: String, val each: String) { Week("This week", "a week"), Month("This month", "a month"), Year("This year", "a year") }

/** A goal like "50 anime this year" or "1 volume a week". Kept on the phone (and synced later). */
@Serializable
data class Goal(val id: String, val kind: GoalKind, val period: GoalPeriod, val target: Int)

data class GoalProgress(
    val goal: Goal,
    val value: Int,
    val from: LocalDate,
    val to: LocalDate,
    /** Where you'd be today going evenly from [from] to [to], as a share of the target. */
    val pace: Float,
) {
    val done: Boolean get() = value >= goal.target
    val share: Float get() = (value.toFloat() / goal.target).coerceIn(0f, 1f)
    val ahead: Boolean get() = share >= pace
}

object Goals {
    fun range(period: GoalPeriod, today: LocalDate): Pair<LocalDate, LocalDate> = when (period) {
        GoalPeriod.Week -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
        GoalPeriod.Month -> today.withDayOfMonth(1).let { it to it.plusMonths(1).minusDays(1) }
        GoalPeriod.Year -> today.withDayOfYear(1).let { it to it.plusYears(1).minusDays(1) }
    }

    /**
     * How far [goal] is. [localChapters] and [localMinutes] are what the app logged per day (for the app-only kinds).
     */
    fun progress(
        goal: Goal,
        anime: List<ListEntry>,
        paper: List<ListEntry>,
        localChapters: Map<LocalDate, Int>,
        localMinutes: Map<LocalDate, Int>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): GoalProgress {
        val (from, to) = range(goal.period, today)
        fun inside(day: LocalDate) = day in from..to
        fun finished(entries: List<ListEntry>) = Finishes.of(entries, zone).count { inside(it.on) }
        val value = when (goal.kind) {
            GoalKind.AnimeFinished -> finished(anime)
            GoalKind.MangaFinished -> finished(paper.ofKind(MediaKind.Manga))
            GoalKind.NovelsFinished -> finished(paper.ofKind(MediaKind.LightNovel))
            GoalKind.TitlesFinished -> finished(anime + paper)
            GoalKind.ChaptersRead -> localChapters.filterKeys(::inside).values.sum()
            GoalKind.HoursWatched -> localMinutes.filterKeys(::inside).values.sum() / 60
        }
        val length = ChronoUnit.DAYS.between(from, to) + 1
        val gone = ChronoUnit.DAYS.between(from, today) + 1
        return GoalProgress(goal, value, from, to, (gone.toFloat() / length).coerceIn(0f, 1f))
    }
}
