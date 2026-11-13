package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset

class ProfileEnginesTest {
    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 13)
    private var nextId = 1

    private fun date(d: LocalDate) = FuzzyDate(d.year, d.monthValue, d.dayOfMonth)

    private fun anime(
        status: String = ListStatus.COMPLETED,
        done: LocalDate? = null,
        episodes: Int = 12,
        score: Double = 0.0,
        average: Int? = null,
        format: String = "TV",
        genres: List<String> = emptyList(),
        season: String? = null,
        seasonYear: Int? = null,
        updated: LocalDate? = null,
        repeat: Int = 0,
        started: LocalDate? = null,
        country: String? = null,
    ) = ListEntry(
        id = nextId,
        status = status,
        progress = if (status == ListStatus.COMPLETED) episodes else 0,
        score = score,
        repeat = repeat,
        completedAt = done?.let(::date),
        startedAt = started?.let(::date),
        updatedAt = updated?.atStartOfDay(utc)?.toEpochSecond(),
        media = Media(
            id = nextId++, type = "ANIME", format = format, episodes = episodes, duration = 24, averageScore = average,
            genres = genres, season = season, seasonYear = seasonYear, countryOfOrigin = country,
        ),
    )

    private fun manga(done: LocalDate?, chapters: Int = 100, format: String = "MANGA") = ListEntry(
        id = nextId,
        status = ListStatus.COMPLETED,
        progress = chapters,
        completedAt = done?.let(::date),
        media = Media(id = nextId++, type = "MANGA", format = format, chapters = chapters, volumes = 10),
    )

    @Test fun finishesUseTheCompletedDateOrGuessFromTheLastChange() {
        val finishes = Finishes.of(
            listOf(
                anime(done = LocalDate.of(2026, 3, 2)),
                anime(updated = LocalDate.of(2025, 5, 1)),
                anime(status = ListStatus.CURRENT),
            ),
            utc,
        )
        assertEquals(2, finishes.size)
        assertEquals(LocalDate.of(2026, 3, 2), finishes[0].on)
        assertFalse(finishes[0].guessed)
        assertTrue(finishes[1].guessed)
    }

    @Test fun comparesMonthsAndYears() {
        val list = listOf(
            anime(done = LocalDate.of(2026, 11, 2)),
            anime(done = LocalDate.of(2026, 11, 10), episodes = 24),
            anime(done = LocalDate.of(2026, 10, 1)),
            anime(done = LocalDate.of(2025, 6, 1)),
        )
        val c = ProfileInsights.compare(list, MediaKind.Anime, today, utc)
        assertEquals(2, c.thisMonth.titles)
        assertEquals(36, c.thisMonth.units)
        assertEquals(36L * 24, c.thisMonth.minutes)
        assertEquals(1, c.lastMonth.titles)
        assertEquals(3, c.thisYear.titles)
        assertEquals(1, c.lastYear.titles)
        assertEquals(0, c.guessed)
    }

    @Test fun forecastsPlanningFromTheLastSixMonths() {
        val list = List(6) { anime(done = today.minusMonths(it.toLong()).minusDays(1)) } +
            List(12) { anime(status = ListStatus.PLANNING) }
        val f = ProfileInsights.forecast(list, today, utc)
        assertEquals(12, f.planning)
        assertEquals(1.0, f.perMonth, 0.001)
        assertEquals(12.0, f.months!!, 0.001)
        assertNull(ProfileInsights.forecast(listOf(anime(status = ListStatus.PLANNING)), today, utc).months)
    }

    @Test fun hotTakesSplitAboveAndBelow() {
        val (above, below) = ProfileInsights.hotTakes(
            listOf(
                anime(score = 9.0, average = 60),
                anime(score = 4.0, average = 80),
                anime(score = 7.5, average = 74),
                anime(score = 0.0, average = 20),
            ),
        )
        assertEquals(listOf(30), above.map { it.difference })
        assertEquals(listOf(-40), below.map { it.difference })
    }

    @Test fun achievementsLevelUpAndDateTheUnlock() {
        val list = (1..12).map { anime(done = LocalDate.of(2026, 1, it)) }
        val results = Achievements.of(list, emptyList(), today, utc).associateBy { it.id }
        val done = results.getValue("anime-done")
        assertEquals(12, done.value)
        assertEquals(1, done.level)
        assertEquals(100, done.next)
        assertEquals(LocalDate.of(2026, 1, 10), done.reachedOn)
        assertEquals(0, results.getValue("movies").level)
        assertEquals(144, results.getValue("episodes").value)
    }

    @Test fun secretAndTasteAchievements() {
        val day = LocalDate.of(2026, 2, 3)
        val list = listOf(
            anime(score = 10.0, average = 70, done = day, started = day, genres = listOf("Action", "Drama"), country = "JP"),
            anime(done = day, genres = listOf("Comedy"), country = "KR"),
        )
        val results = Achievements.of(list, emptyList(), today, utc).associateBy { it.id }
        assertTrue(results.getValue("perfect").earned)
        assertTrue(results.getValue("same-day").earned)
        assertEquals(3, results.getValue("genres").value)
        assertEquals(1, results.getValue("world").level)
        assertTrue(results.getValue("perfect").single)
    }

    @Test fun seasonBadgesCountOnlyFinishesWhileAiring() {
        val list = listOf(
            anime(done = LocalDate.of(2026, 10, 20), season = "FALL", seasonYear = 2026),
            anime(done = LocalDate.of(2027, 1, 5), season = "FALL", seasonYear = 2026),
            anime(done = LocalDate.of(2026, 4, 2), season = "SPRING", seasonYear = 2026),
            anime(done = LocalDate.of(2026, 9, 1), season = "SPRING", seasonYear = 2026),
        )
        val seasons = Achievements.seasons(list, utc)
        assertEquals(listOf("Fall 2026", "Spring 2026"), seasons.map { it.achievement.title })
        assertEquals(listOf(1, 1), seasons.map { it.value })
    }

    @Test fun calendarMergesStreaksAndLevels() {
        val history = listOf(
            // Midnight in UTC+1 (23:00 UTC the day before) and midnight UTC both land on the right day.
            ActivityHistoryDay(LocalDate.of(2026, 11, 10).atStartOfDay(utc).toEpochSecond() - 3600, 2),
            ActivityHistoryDay(LocalDate.of(2026, 11, 11).atStartOfDay(utc).toEpochSecond(), 1),
        )
        val ani = ActivityCalendar.aniListDays(history)
        assertEquals(mapOf(LocalDate.of(2026, 11, 10) to 2, LocalDate.of(2026, 11, 11) to 1), ani)
        val merged = ActivityCalendar.merge(ani, mapOf(LocalDate.of(2026, 11, 11) to 5, LocalDate.of(2026, 11, 12) to 1))
        assertEquals(5, merged[LocalDate.of(2026, 11, 11)])
        assertEquals(3, ActivityCalendar.streak(merged, today))
        assertEquals(3, ActivityCalendar.streak(merged, LocalDate.of(2026, 11, 12)))
        assertEquals(0, ActivityCalendar.streak(merged, LocalDate.of(2026, 11, 20)))
        assertEquals(3, ActivityCalendar.longestStreak(merged + (LocalDate.of(2026, 1, 1) to 1)))
        assertEquals(0, ActivityCalendar.level(0, merged))
        assertEquals(4, ActivityCalendar.level(5, merged))
        val weeks = ActivityCalendar.weeks(today, 3)
        assertEquals(3, weeks.size)
        assertEquals(LocalDate.of(2026, 11, 9), weeks.last().first())
    }

    @Test fun goalsCountTheirPeriod() {
        val list = listOf(anime(done = LocalDate.of(2026, 11, 10)), anime(done = LocalDate.of(2026, 11, 1)), anime(done = LocalDate.of(2026, 1, 1)))
        val week = Goals.progress(Goal("a", GoalKind.AnimeFinished, GoalPeriod.Week, 2), list, emptyList(), emptyMap(), emptyMap(), today, utc)
        assertEquals(1, week.value)
        assertFalse(week.done)
        val year = Goals.progress(Goal("b", GoalKind.TitlesFinished, GoalPeriod.Year, 3), list, emptyList(), emptyMap(), emptyMap(), today, utc)
        assertTrue(year.done)
        val hours = Goals.progress(
            Goal("c", GoalKind.HoursWatched, GoalPeriod.Month, 10), list, emptyList(), emptyMap(),
            mapOf(LocalDate.of(2026, 11, 3) to 90, LocalDate.of(2026, 10, 3) to 600), today, utc,
        )
        assertEquals(1, hours.value)
    }

    @Test fun wrappedSumsTheYear() {
        val list = listOf(
            anime(done = LocalDate.of(2025, 3, 1), score = 8.0, genres = listOf("Action")),
            anime(done = LocalDate.of(2025, 3, 9), score = 9.5, genres = listOf("Action", "Drama")),
            anime(done = LocalDate.of(2024, 3, 9), score = 10.0),
        )
        val w = WrappedMaker.of(2025, list, listOf(manga(LocalDate.of(2025, 7, 1))), emptyList(), zone = utc)
        assertEquals(3, w.titles)
        assertEquals(9.5, w.favourite!!.score, 0.0)
        assertEquals(Month.MARCH, w.busiestMonth)
        assertEquals("Action" to 2, w.topGenres.first())
        assertEquals(2025, WrappedMaker.yearFor(today))
        assertEquals(2026, WrappedMaker.yearFor(LocalDate.of(2026, 12, 2)))
    }

    @Test fun bioLosesMarkupButKeepsWords() {
        assertEquals(
            "Hi! I like slow sci-fi. My list here",
            plainBio("<center>img220(https://x.png)</center>\n# Hi!\nI like **slow** __sci-fi__. ~!spoiler!~\n[My list](https://anilist.co) here"),
        )
        assertNull(plainBio("img(https://x.png)"))
        assertNull(plainBio(null))
    }
}
