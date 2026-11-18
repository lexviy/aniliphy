package com.aniliphy.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AutoDownloadsTest {
    @get:Rule val folder = TemporaryFolder()

    private fun item(to: Double, from: Double = 3.0, anime: Boolean = true) =
        AutoDownload(anime, 7, "Show", "hia:x", from = from, to = to)

    @Test
    fun aNewerReleaseWidensTheOneWaiting() {
        val store = AutoDownloads(File(folder.root, "a.json"))
        store.add(item(4.0))
        store.add(item(5.0))
        store.add(item(5.0, anime = false))
        val all = store.all()
        assertEquals(2, all.size)
        assertEquals(5.0, all.first { it.anime }.to, 0.0)
        assertEquals(3.0, all.first { it.anime }.from, 0.0)
        store.remove("a7")
        store.remove("m7")
        assertTrue(store.all().isEmpty())
        assertTrue(!File(folder.root, "a.json").exists())
    }

    @Test
    fun wantedTakesTheNewestAfterWhereYouAre() {
        val numbers = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 6.0, 7.0)
        assertEquals(listOf(6.0, 7.0), AutoDownloads.wanted(numbers, from = 3.0, to = 7.0, cap = 2))
        assertEquals(listOf(4.0, 5.0), AutoDownloads.wanted(numbers, from = 3.0, to = 5.0, cap = 5))
        assertTrue(AutoDownloads.wanted(numbers, from = 7.0, to = 7.0, cap = 2).isEmpty())
    }
}
