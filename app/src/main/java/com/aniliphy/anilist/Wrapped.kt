package com.aniliphy.anilist

import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/** A year in review, from the lists' finish dates (and what the app logged, for time). */
data class Wrapped(
    val year: Int,
    val anime: PeriodTotals,
    val manga: PeriodTotals,
    val novels: PeriodTotals,
    val topGenres: List<Pair<String, Int>>,
    /** The finished title you scored highest (ties: the longest). */
    val favourite: ListEntry?,
    val runnersUp: List<ListEntry>,
    /** Titles finished each month, January first. */
    val months: List<Int>,
    val busiestMonth: Month?,
    val achievements: List<AchievementResult>,
    /** Finish dates that are guesses. */
    val guessed: Int,
    /** Minutes watched and chapters read in the app this year. */
    val appMinutes: Long,
    val appChapters: Int,
) {
    val titles: Int get() = anime.titles + manga.titles + novels.titles
    val hours: Long get() = anime.minutes / 60
    val empty: Boolean get() = titles == 0
}

object WrappedMaker {
    /** The year to offer: this one from December on, otherwise the last one. */
    fun yearFor(today: LocalDate): Int = if (today.monthValue == 12) today.year else today.year - 1

    fun of(
        year: Int,
        anime: List<ListEntry>,
        paper: List<ListEntry>,
        achievements: List<AchievementResult>,
        localChapters: Map<LocalDate, Int> = emptyMap(),
        localMinutes: Map<LocalDate, Int> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Wrapped {
        fun finishedIn(entries: List<ListEntry>) = Finishes.of(entries, zone).filter { it.on.year == year }
        val animeDone = finishedIn(anime)
        val mangaDone = finishedIn(paper.ofKind(MediaKind.Manga))
        val novelsDone = finishedIn(paper.ofKind(MediaKind.LightNovel))
        val all = animeDone + mangaDone + novelsDone
        val months = (1..12).map { m -> all.count { it.on.monthValue == m } }
        val ranked = all.map { it.entry }
            .sortedWith(compareByDescending<ListEntry> { it.score }.thenByDescending { ProfileInsights.units(it, it.media.kind) })
        return Wrapped(
            year = year,
            anime = ProfileInsights.totals(animeDone, MediaKind.Anime),
            manga = ProfileInsights.totals(mangaDone, MediaKind.Manga),
            novels = ProfileInsights.totals(novelsDone, MediaKind.LightNovel),
            topGenres = all.flatMap { it.entry.media.genres }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            favourite = ranked.firstOrNull(),
            runnersUp = ranked.drop(1).take(4),
            months = months,
            busiestMonth = months.withIndex().filter { it.value > 0 }.maxByOrNull { it.value }?.let { Month.of(it.index + 1) },
            achievements = achievements.filter { it.reachedOn?.year == year },
            guessed = all.count { it.guessed },
            appMinutes = localMinutes.filterKeys { it.year == year }.values.sum().toLong(),
            appChapters = localChapters.filterKeys { it.year == year }.values.sum(),
        )
    }
}
