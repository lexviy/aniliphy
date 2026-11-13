package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityTest {
    private fun e(id: Int, score: Double = 0.0, status: String = ListStatus.COMPLETED) =
        ListEntry(id = id, status = status, score = score, media = Media(id = id, type = "ANIME"))

    @Test fun tooFewInCommonHasNoPercent() {
        val c = Compatibility.compare(listOf(e(1, 8.0), e(2, 7.0)), listOf(e(1, 8.0), e(2, 6.0)))
        assertNull(c.percent)
        assertEquals(2, c.shared.size)
    }

    @Test fun sameTasteScoresHighAndOppositeLow() {
        val mine = (1..8).map { e(it, it.toDouble() + 1) }
        val same = (1..8).map { e(it, it.toDouble() + 1) }
        val opposite = (1..8).map { e(it, 10.0 - it) }
        assertEquals(100, Compatibility.compare(mine, same).percent)
        assertTrue(Compatibility.compare(mine, opposite).percent!! < 35)
    }

    @Test fun flatScorersStillMatchOnCloseness() {
        val mine = (1..6).map { e(it, 8.0) }
        val theirs = (1..6).map { e(it, 8.0) }
        assertEquals(100, Compatibility.compare(mine, theirs).percent)
    }

    @Test fun lovedListsAndDisagreements() {
        val mine = listOf(e(1, 9.0), e(2, 3.0), e(3, 7.0), e(4, 6.0), e(5, 5.0), e(6, 9.5))
        val theirs = listOf(e(1, 9.0), e(2, 9.0), e(3, 7.0), e(4, 6.0), e(5, 5.0), e(7, 10.0), e(8, 4.0))
        val c = Compatibility.compare(mine, theirs)
        assertEquals(listOf(2), c.disagreements.map { it.media.id })
        assertEquals(listOf(7), c.theyLoved.map { it.media.id })
        assertEquals(listOf(6), c.youLoved.map { it.media.id })
        assertEquals(5, c.scoredTogether)
    }

    @Test fun overlapForPeopleWhoDontScore() {
        val mine = (1..4).map { e(it) }
        val theirs = (3..10).map { e(it) }
        val c = Compatibility.compare(mine, theirs)
        assertNull(c.percent)
        assertEquals(50, c.overlap)
    }

    @Test fun bothOnlyPlanningDoesNotCount() {
        val c = Compatibility.compare(listOf(e(1, status = ListStatus.PLANNING)), listOf(e(1, status = ListStatus.PLANNING)))
        assertTrue(c.shared.isEmpty())
    }
}
