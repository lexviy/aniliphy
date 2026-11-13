package com.aniliphy.anilist

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A guessed weekly release day for a manga, from when its new chapters were first seen. */
object ReleaseDays {
    /** At least this many sightings in [WINDOW_DAYS] before guessing. */
    const val MIN_SEEN = 3
    const val WINDOW_DAYS = 120L

    /** The weekday most of its chapters came out on, when that's clear (60% or more of them), else null. */
    fun guess(seen: List<Long>, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): DayOfWeek? {
        val recent = seen.filter { now - it in 0..WINDOW_DAYS * 86_400_000 }
        if (recent.size < MIN_SEEN) return null
        val days = recent.map { Instant.ofEpochMilli(it).atZone(zone).dayOfWeek }
        val (day, count) = days.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return day.takeIf { count * 10 >= recent.size * 6 }
    }
}

/** Something on a day of the week ahead: an episode airing, or a chapter usually out that day. */
data class CalendarItem(
    val media: Media,
    /** Seconds; null for a guessed chapter day. */
    val airingAt: Long?,
    val episode: Int?,
    val guessed: Boolean = false,
)

data class CalendarDay(val date: LocalDate, val items: List<CalendarItem>)

object WeekCalendar {
    /** Seven days from [today]: airing episodes in time order, then the manga usually out that weekday. */
    fun build(
        today: LocalDate,
        airing: List<AiringSlot>,
        mangaDays: Map<Media, DayOfWeek>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<CalendarDay> = (0L until 7L).map { offset ->
        val date = today.plusDays(offset)
        val episodes = airing.filter { it.media != null && Instant.ofEpochSecond(it.airingAt).atZone(zone).toLocalDate() == date }
            .sortedBy { it.airingAt }
            .map { CalendarItem(it.media!!, it.airingAt, it.episode) }
        val chapters = mangaDays.filter { it.value == date.dayOfWeek }.keys
            .sortedBy { it.title.display }
            .map { CalendarItem(it, null, null, guessed = true) }
        CalendarDay(date, episodes + chapters)
    }
}
