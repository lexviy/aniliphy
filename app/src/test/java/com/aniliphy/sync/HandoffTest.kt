package com.aniliphy.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffTest {
    private val now = 1_800_000_000.0
    private fun place(id: String = "a", at: Double = now - 60, from: String = "pc") = HandoffPlace(
        id = id, kind = "anime", key = "97940", title = "Black Clover", at = at, from = from,
        anime = SyncedAnime(title = "Black Clover", episode = 8.0, position = 760.4, duration = 1430.0, updated = at),
    )

    @Test
    fun roundTripsAndIgnoresOtherFiles() {
        val file = HandoffFile(request = place())
        assertEquals(file, HandoffFile.parse(HandoffFile.encode(file)))
        assertNull(HandoffFile.parse("""{"app":"anilixy-sync","version":1}"""))
        assertNull(HandoffFile.parse("""{"app":"anilixy-handoff","version":9}"""))
        assertNull(HandoffFile.parse("not json"))
        // A newer anilixy may add fields.
        assertNotNull(HandoffFile.parse("""{"app":"anilixy-handoff","version":1,"request":null,"extra":3}"""))
    }

    @Test
    fun waitingOnlyWhenFreshAndNotTaken() {
        assertNotNull(HandoffRules.waiting(HandoffFile(request = place()), now))
        assertNull(HandoffRules.waiting(HandoffFile(request = place(), taken = "a"), now))
        assertNull(HandoffRules.waiting(HandoffFile(request = place(at = now - 13 * 3600)), now))
        assertNull(HandoffRules.waiting(HandoffFile(), now))
        assertNull(HandoffRules.waiting(null, now))
    }

    @Test
    fun pcCardOnlyWhenNewerThanThePhone() {
        val last = HandoffFile(request = place(at = now - 600))
        assertNotNull(HandoffRules.pcCard(last, null, emptySet(), now))
        assertNotNull(HandoffRules.pcCard(last, now - 3600, emptySet(), now))
        // The phone's own place came back from the PC through the sync: same time, no card.
        assertNull(HandoffRules.pcCard(last, now - 630, emptySet(), now))
        assertNull(HandoffRules.pcCard(last, now - 300, emptySet(), now))
        assertNull(HandoffRules.pcCard(last, null, setOf("a"), now))
        assertNull(HandoffRules.pcCard(HandoffFile(request = place(from = "phone")), null, emptySet(), now))
    }

    @Test
    fun describesThePlace() {
        assertEquals("Episode 8 · 12:40", HandoffRules.where(place()))
        val manga = HandoffPlace("m", "manga", "1", at = now, from = "phone", manga = SyncedManga(number = 41.5, page = 6))
        assertEquals("Chapter 41.5 · page 7", HandoffRules.where(manga))
        assertEquals("1:02:03", HandoffRules.clock(3723.0))
    }
}
