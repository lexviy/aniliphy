package com.aniliphy.novels

import com.aniliphy.anilist.NotFoundException
import com.aniliphy.anilist.OfflineException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PendingProgressTest {
    @get:Rule val folder = TemporaryFolder()
    private val pending get() = PendingProgress(File(folder.root, "pending.json"))

    @Test fun keepsOnlyTheHighestCount() {
        pending.add(1, 5)
        pending.add(1, 3)
        pending.add(2, 1)
        assertEquals(mapOf(1 to 5, 2 to 1), pending.all())
    }

    @Test fun survivesAndEmptiesOnceSent() = runBlocking {
        pending.add(1, 5)
        val sent = ArrayList<Pair<Int, Int>>()
        val done = pending.flush { id, count -> sent.add(id to count) }
        assertEquals(listOf(1 to 5), sent)
        assertEquals(mapOf(1 to 5), done)
        assertEquals(emptyMap<Int, Int>(), pending.all())
    }

    @Test fun withNoConnectionEverythingWaits() = runBlocking {
        pending.add(1, 5)
        pending.add(2, 7)
        val done = pending.flush { _, _ -> throw OfflineException(IOException("down")) }
        assertEquals(emptyMap<Int, Int>(), done)
        assertEquals(mapOf(1 to 5, 2 to 7), pending.all())
    }

    @Test fun aTitleAniListLostIsDropped() = runBlocking {
        pending.add(1, 5)
        val done = pending.flush { _, _ -> throw NotFoundException() }
        assertEquals(emptyMap<Int, Int>(), done)
        assertEquals(emptyMap<Int, Int>(), pending.all())
    }

    @Test fun readingMoreWhileSendingIsNotLost() {
        pending.add(1, 5)
        pending.add(1, 6)
        pending.sent(1, 5)
        assertEquals(mapOf(1 to 6), pending.all())
    }

    @Test fun aBrokenFileIsEmpty() {
        File(folder.root, "pending.json").writeText("{nope")
        assertEquals(emptyMap<Int, Int>(), pending.all())
    }
}
