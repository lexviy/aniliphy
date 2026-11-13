package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class Phase7Test {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(date: String, hour: Int = 12) = LocalDate.parse(date).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun notificationTexts() {
        val like = AniNotification(id = 1, type = "ACTIVITY_LIKE", context = " liked your activity.", activityId = 9, user = UserCard(3, "mika"))
        assertEquals("mika liked your activity.", Inbox.text(like))
        assertEquals(NoteTarget.Activity(9), Inbox.target(like))
        val airing = AniNotification(id = 2, type = "AIRING", episode = 7, contexts = listOf("Episode ", " of ", " aired."), media = Media(id = 5, title = MediaTitle(romaji = "Frieren")))
        assertEquals("Episode 7 of Frieren aired.", Inbox.text(airing))
        val reply = AniNotification(id = 3, type = "THREAD_COMMENT_REPLY", context = " replied to your comment in", commentId = 44, thread = ThreadRef(10, "Ep 7 Discussion"), user = UserCard(4, "ken"))
        assertEquals("ken replied to your comment in Ep 7 Discussion", Inbox.text(reply))
        assertEquals(NoteTarget.Thread(10, 44), Inbox.target(reply))
    }

    @Test fun phoneOnlyForNewRepliesAndFollows() {
        val notes = listOf(
            AniNotification(id = 12, type = "ACTIVITY_REPLY"),
            AniNotification(id = 11, type = "ACTIVITY_LIKE"),
            AniNotification(id = 9, type = "FOLLOWING"),
        )
        assertTrue(Inbox.forPhone(notes, null).isEmpty())
        assertEquals(listOf(12), Inbox.forPhone(notes, 10).map { it.id })
    }

    @Test fun releaseDayNeedsAClearPattern() {
        val now = at("2026-11-13")
        val fridays = listOf("2026-10-23", "2026-10-30", "2026-11-06", "2026-11-13").map { at(it) }
        assertEquals(DayOfWeek.FRIDAY, ReleaseDays.guess(fridays, utc, now))
        val mixed = listOf("2026-10-20", "2026-10-30", "2026-11-04", "2026-11-13").map { at(it) }
        assertNull(ReleaseDays.guess(mixed, utc, now))
        assertNull(ReleaseDays.guess(fridays.take(2), utc, now))
    }

    @Test fun weekPutsEpisodesAndChaptersOnTheirDays() {
        val today = LocalDate.parse("2026-11-13") // a Friday
        val show = Media(id = 1, type = "ANIME")
        val manga = Media(id = 2, type = "MANGA")
        val slot = AiringSlot(1, 8, LocalDate.parse("2026-11-15").atTime(14, 0).toEpochSecond(ZoneOffset.UTC), show)
        val week = WeekCalendar.build(today, listOf(slot), mapOf(manga to DayOfWeek.FRIDAY), utc)
        assertEquals(7, week.size)
        assertEquals(listOf(2), week[0].items.map { it.media.id })
        assertTrue(week[0].items[0].guessed)
        assertEquals(listOf(1), week[2].items.map { it.media.id })
        assertTrue(week[1].items.isEmpty())
    }
}
