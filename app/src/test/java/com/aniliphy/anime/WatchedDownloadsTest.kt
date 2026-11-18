package com.aniliphy.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WatchedDownloadsTest {
    @get:Rule val folder = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun deletesOnlyWhenTheTimeComes() {
        val store = WatchedDownloads(File(folder.root, "w.json"))
        store.mark(1, "a", at = 0)
        store.mark(1, "b", at = day)
        val saved = { _: Int, _: String -> true }
        assertTrue(store.takeDue(DeleteWatched.Never, 10 * day, saved).isEmpty())
        assertEquals(listOf(1 to "a"), store.takeDue(DeleteWatched.Day, day + 1, saved))
        // Already taken: not given twice.
        assertTrue(store.takeDue(DeleteWatched.Day, day + 2, saved).isEmpty())
        assertEquals(listOf(1 to "b"), store.takeDue(DeleteWatched.Week, 9 * day, saved))
    }

    @Test
    fun theOnePlayingStaysAndGoneOnesAreForgotten() {
        val file = File(folder.root, "w.json")
        val store = WatchedDownloads(file)
        store.mark(1, "a", at = 0)
        store.mark(2, "x", at = 0)
        val due = store.takeDue(DeleteWatched.RightAway, 1, { id, _ -> id == 1 }, keep = "1/a")
        assertTrue(due.isEmpty())
        assertEquals(listOf(1 to "a"), store.takeDue(DeleteWatched.RightAway, 1, { _, _ -> true }))
        assertTrue(!file.exists())
    }

    @Test
    fun markingTwiceKeepsTheFirstTime() {
        val store = WatchedDownloads(File(folder.root, "w.json"))
        store.mark(1, "a", at = 0)
        store.mark(1, "a", at = 5 * day)
        assertEquals(listOf(1 to "a"), store.takeDue(DeleteWatched.Day, day, { _, _ -> true }))
    }
}
