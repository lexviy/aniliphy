package com.aniliphy.work

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveRulesTest {
    private val now = 1_800_000_000L

    @Test
    fun countdownOnlyWhenCaughtUpAndSoon() {
        val items = listOf(
            AiringSoon(1, "Caught up", episode = 9, airingAt = now + 2 * 3600, progress = 8),
            AiringSoon(2, "Behind", episode = 9, airingAt = now + 3600, progress = 5),
            AiringSoon(3, "Tomorrow", episode = 4, airingAt = now + 20 * 3600, progress = 3),
            AiringSoon(4, "Already out", episode = 2, airingAt = now - 60, progress = 1),
        )
        assertEquals(listOf(1), LiveRules.due(items, now).map { it.mediaId })
    }

    @Test
    fun countdownBarAndChip() {
        assertEquals(0, LiveRules.countdownProgress(now + 3 * 3600, now))
        assertEquals(500, LiveRules.countdownProgress(now + 90 * 60, now))
        assertEquals(1000, LiveRules.countdownProgress(now, now))
        assertEquals("2:14", LiveRules.shortLeft(now + 2 * 3600 + 14 * 60 + 5, now))
    }

    @Test
    fun pausedBarMarksOpeningAndEnding() {
        val parts = LiveRules.pausedParts(positionS = 760.0, durationS = 1430.0, intro = 90.0..180.0, outro = 1340.0..1430.0)
        assertEquals(
            listOf(
                LiveRules.Part.Watched to 90.0,
                LiveRules.Part.Opening to 90.0,
                LiveRules.Part.Watched to 580.0,
                LiveRules.Part.Left to 580.0,
                LiveRules.Part.Ending to 90.0,
            ),
            parts,
        )
        assertEquals(1430.0, parts.sumOf { it.second }, 0.001)
        assertEquals(listOf(LiveRules.Part.Watched to 100.0, LiveRules.Part.Left to 200.0), LiveRules.pausedParts(100.0, 300.0, null, null))
        assertEquals(emptyList<Any>(), LiveRules.pausedParts(10.0, 0.0, null, null))
    }
}
