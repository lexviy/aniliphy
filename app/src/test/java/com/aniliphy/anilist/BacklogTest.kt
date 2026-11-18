package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklogTest {
    private val now = 100L * Backlog.DAY_S
    private fun entry(id: Int, status: String, daysAgo: Long, progress: Int = 0, total: Int? = 12, anime: Boolean = true) =
        ListEntry(status = status, updatedAt = now - daysAgo * Backlog.DAY_S, progress = progress,
            media = Media(id, type = if (anime) "ANIME" else "MANGA", episodes = total.takeIf { anime }, chapters = total.takeIf { !anime }))

    @Test
    fun stalledOnlyWatchingAndOldEnough() {
        val list = listOf(entry(1, "CURRENT", 30), entry(2, "CURRENT", 5), entry(3, "PAUSED", 60), entry(4, "CURRENT", 50))
        assertEquals(listOf(4, 1), Backlog.stalled(list, now, 3).map { it.media.id })
        assertTrue(Backlog.stalled(list, now, 0).isEmpty())
    }

    @Test
    fun notNowWaitsUntilItMovesOrWeeksPass() {
        val list = listOf(entry(1, "CURRENT", 30))
        assertTrue(Backlog.stalled(list, now, 3, mapOf(1 to now - 2 * Backlog.DAY_S)).isEmpty())
        assertEquals(1, Backlog.stalled(list, now, 3, mapOf(1 to now - 22 * Backlog.DAY_S)).size)
    }

    @Test
    fun zeroUpdatedAtFallsBackToAdded() {
        val old = ListEntry(status = "CURRENT", updatedAt = 0, createdAt = 0, media = Media(9))
        val added = ListEntry(status = "CURRENT", updatedAt = 0, createdAt = now - 40 * Backlog.DAY_S, media = Media(8))
        assertEquals(listOf(8), Backlog.stalled(listOf(old, added), now, 3).map { it.media.id })
    }

    @Test
    fun spanReadsRanges() {
        assertEquals(5..7, Backlog.span("5 - 7"))
        assertEquals(12..12, Backlog.span("12"))
        assertNull(Backlog.span(null))
    }

    @Test
    fun paceAndFinish() {
        val m = Media(1, type = "ANIME", episodes = 24)
        val acts = listOf(
            Activity(progress = "1 - 5", createdAt = now - 10 * Backlog.DAY_S, media = m),
            Activity(progress = "6 - 10", createdAt = now - 3 * Backlog.DAY_S, media = m),
            Activity(progress = "1 - 99", createdAt = now - 40 * Backlog.DAY_S, media = m),
        )
        val pace = Backlog.pace(acts, now)
        assertEquals(10.0 / 30, pace[1]!!, 1e-9)
        val f = Backlog.finishing(listOf(entry(1, "CURRENT", 1, progress = 10, total = 24), entry(2, "CURRENT", 1, total = null)), pace)
        assertEquals(14, f[0].left)
        assertEquals(42, f[0].days)
        assertNull(f[1].days)
    }

    @Test
    fun filtersBuildVariables() {
        val f = SearchFilters(
            genres = mapOf("Fantasy" to Pick.Want, "Ecchi" to Pick.LeaveOut),
            yearFrom = 2018, yearTo = 2026, hideMine = true,
        )
        val v = f.variables(null, 1, loggedIn = true).toString()
        assertTrue(v, v.contains("\"genreIn\":[\"Fantasy\"]"))
        assertTrue(v, v.contains("\"genreOut\":[\"Ecchi\"]"))
        assertTrue(v, v.contains("\"from\":20171231"))
        assertTrue(v, v.contains("\"to\":20270101"))
        assertTrue(v, v.contains("\"onList\":false"))
        assertTrue(!f.variables(null, 1, loggedIn = false).toString().contains("onList"))
        assertTrue(SearchFilters(kind = MediaKind.LightNovel).variables("x", 1, false).toString().contains("\"formats\":[\"NOVEL\"]"))
        assertEquals(listOf("+ Fantasy", "− Ecchi", "2018–2026", "Not on my list"), f.summary())
    }

    @Test
    fun spoilerPartsAndPost() {
        val p = SpoilerText.parts("Good. ~!He dies!~ The end")
        assertEquals(listOf(false, true, false), p.map { it.spoiler })
        assertEquals("He dies", p[1].text)
        assertEquals("Hi bold", SpoilerText.clean("Hi __bold__img220(http://x.png)"))
        assertEquals("This has spoilers and snake_case", SpoilerText.clean("_This has spoilers_ and snake_case"))
        val text = FeedPost.text(" Great ", FeedPost.Attached(5, "Frieren", true, "CURRENT", 21))
        assertEquals("Great\n\n— Watching · Frieren · episode 21\nhttps://anilist.co/anime/5/", text)
    }
}
