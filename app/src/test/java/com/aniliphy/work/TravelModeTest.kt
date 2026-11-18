package com.aniliphy.work

import com.aniliphy.anime.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class TravelModeTest {
    private fun pick(anime: Boolean, progress: Int, released: Int?, id: Int = 1) =
        TravelPick(anime, id, "T", null, "s:x", progress, released, released, null)

    @Test
    fun neverAsksForMoreThanIsOut() {
        assertEquals(2, TravelMode.count(pick(true, 10, 12), 3))
        assertEquals(0, TravelMode.count(pick(true, 12, 12), 3))
        // Unknown (ongoing manga): the site decides, up to what was asked.
        assertEquals(10, TravelMode.count(pick(false, 50, null), 10))
    }

    @Test
    fun estimateAddsUp() {
        val e = TravelMode.estimate(listOf(pick(true, 0, 12) to 3, pick(false, 0, null, id = 2) to 10), VideoQuality.P720, pages = true)
        assertEquals(3, e.episodes)
        assertEquals(10, e.chapters)
        assertEquals(3 * 150_000_000L + 10 * TravelMode.CHAPTER_BYTES + 2 * TravelMode.PAGE_BYTES, e.total)
        assertEquals(0L, TravelMode.estimate(listOf(pick(true, 0, 12) to 3), VideoQuality.Best, pages = false).pageBytes)
    }

    @Test
    fun backCountsWhatMovedForward() {
        val trip = TravelTrip(0, listOf(TravelTrip.Item(true, 1, "A", 4), TravelTrip.Item(false, 2, "B", 30), TravelTrip.Item(true, 3, "C", 2)))
        val back = TravelMode.back(trip, mapOf("a1" to 7, "m2" to 41, "a3" to 1), waiting = 2)
        assertEquals(3, back.episodes)
        assertEquals(11, back.chapters)
        assertEquals(2, back.waiting)
    }
}
