package com.aniliphy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class TachiBackupTest {
    private fun library() = TachiBackup.read(javaClass.getResourceAsStream("/backup.tachibk")!!.readBytes())

    @Test
    fun readsTitlesSourcesAndAniList() {
        val library = library()
        assertEquals(2, library.manga.size)
        val tracked = library.manga[0]
        assertEquals("tm6404943692147160087", tracked.source)
        assertEquals("/manga/abc", tracked.url)
        assertEquals("Tracked Manga", tracked.title)
        assertEquals("https://x/c.jpg", tracked.cover)
        // Only the AniList tracker counts, not the other one on the same title.
        assertEquals(4242, tracked.aniList)
        assertNull(library.manga[1].aniList)
        // Anime from both the new and the older Aniyomi layout, the older one with the id in its old field.
        assertEquals(setOf(777, 555), library.anime.map { it.aniList }.toSet())
        assertEquals(setOf("ta954848840440936266"), library.anime.map { it.source }.toSet())
    }

    @Test
    fun planOnlyFillsEntriesWithoutASource() {
        val items = listOf(
            TachiBackup.Item("tm1", "/a", "A", null, 1),
            TachiBackup.Item("tm1", "/b", "B", null, 2),
            TachiBackup.Item("tm9", "/c", "C", null, 3),
            TachiBackup.Item("tm1", "/d", "D", null, null),
            TachiBackup.Item("tm1", "/e", "E", null, 1),
        )
        val plan = TachiBackup.plan(items, installed = setOf("tm1"), current = mapOf(2 to "hia:/x"))
        assertEquals(listOf(1), plan.link.keys.toList())
        assertEquals("/a", plan.link[1]!!.url)
        assertEquals(2, plan.alreadySet)
        assertEquals(1, plan.notOnAniList)
        assertEquals(1, plan.notInstalled)
    }

    @Test(expected = IOException::class)
    fun notABackup() {
        TachiBackup.read("hello, not a backup".toByteArray())
    }
}
