package com.aniliphy.manga

import com.aniliphy.source.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MangaStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private val one = SourceChapter("id-1", "Chapter 1", "https://x/1", 1.0)
    private val two = SourceChapter("id-2", "Chapter 2", "https://x/2", 2.0)

    @Test fun historyKeepsSourceAndPlace() {
        val history = MangaHistory(File(folder.root, "h.json"))
        assertNull(history.get(5))
        history.setSource(5, "first:https://a", title = "Title", cover = "c")
        history.savePlace(5, two, page = 4, pages = 20, title = null, cover = null, now = 99)
        val record = history.get(5)!!
        assertEquals("first:https://a", record.source)
        assertEquals("id-2", record.chapterId)
        assertEquals(2.0, record.chapterNumber!!, 0.0)
        assertEquals(4, record.page)
        assertEquals("Title", record.title)
        assertEquals(99L, record.updated)
        assertEquals(record, MangaHistory(File(folder.root, "h.json")).get(5))
    }

    @Test fun anotherSourceKeepsThePlaceByNumber() {
        val history = MangaHistory(File(folder.root, "h.json"))
        history.setSource(5, "first:https://a")
        history.savePlace(5, two, 4, 20, null, null)
        history.setSource(5, "second:https://b")
        val record = history.get(5)!!
        assertNull(record.chapterId)
        assertEquals(2.0, record.chapterNumber!!, 0.0)
        assertEquals(4, record.page)
        history.setSource(5, null)
        assertNull(history.get(5)!!.source)
        history.setSource(6, "first:x")
        history.setSource(6, null)
        assertNull(history.get(6))
    }

    private fun offline(fetch: (String, Map<String, String>) -> Pair<ByteArray, String?>) =
        OfflineManga(File(folder.root, "offline"), fetch)

    @Test fun downloadsAndReadsBack() {
        val asked = mutableListOf<Map<String, String>>()
        val store = offline { url, headers ->
            asked += headers
            url.toByteArray() to if (url.endsWith("b")) "image/webp" else null
        }
        store.download(7, "second:s", "Title", one, listOf("https://img/a.png", "https://img/b"), mapOf("Referer" to "r"))
        val pages = store.localPages(7, "id-1")!!
        assertEquals(listOf("0001.png", "0002.webp"), pages.map { it.name })
        assertEquals("https://img/a.png", pages[0].readText())
        assertEquals("r", asked.first()["Referer"])
        assertEquals(setOf("id-1"), store.downloadedIds(7))
        assertEquals(listOf(one), store.cachedChapters(7, "second:s"))
        assertTrue(store.cachedChapters(7, "first:s").isEmpty())
        assertNull(store.localPages(7, "id-2"))

        store.rememberChapters(7, "second:s", listOf(one, two))
        assertEquals(listOf(one), store.cachedChapters(7, "second:s"))

        store.delete(7, "id-1")
        assertNull(store.localPages(7, "id-1"))
        assertTrue(store.titles().isEmpty())
    }

    @Test fun aFailedPageLeavesNothingBehind() {
        var calls = 0
        val store = offline { url, _ ->
            calls++
            if (url.endsWith("2")) throw IOException("down") else ByteArray(1) to "image/jpeg"
        }
        try {
            store.download(7, "second:s", null, one, listOf("https://img/1", "https://img/2"), emptyMap())
            throw AssertionError("should fail")
        } catch (_: IOException) {
        }
        assertEquals(4, calls)
        assertFalse(File(folder.root, "offline/7/${OfflineManga.folderName(one)}").exists())
        assertTrue(store.downloadedIds(7).isEmpty())
    }

    @Test fun downloadsFromAnotherSourceReplaceTheOldOnes() {
        val store = offline { _, _ -> ByteArray(1) to "image/jpeg" }
        store.download(7, "second:s", null, one, listOf("https://img/1"), emptyMap())
        store.download(7, "first:s", null, two, listOf("https://img/1"), emptyMap())
        assertEquals(setOf("id-2"), store.downloadedIds(7))
        assertFalse(File(folder.root, "offline/7/${OfflineManga.folderName(one)}").exists())
    }

    @Test fun namesAndExtensions() {
        assertTrue(OfflineManga.folderName(SourceChapter("abc", "Chapter 12: The/End?", "u")).startsWith("Chapter 12 TheEnd ["))
        assertEquals(".jpg", OfflineManga.extension("https://x/a.JPG?token=1", null))
        assertEquals(".png", OfflineManga.extension("https://x/a", "image/png; q=1"))
        assertEquals(".jpg", OfflineManga.extension("https://x/a", "image/jpeg"))
    }

    @Test fun clearingThePlaceKeepsTheSource() {
        val history = MangaHistory(File(folder.root, "h.json"))
        history.setSource(5, "first:a")
        history.savePlace(5, two, 4, 20, "T", null, total = 100, titles = listOf("T", "Romaji"), webtoon = true)
        assertEquals(100, history.get(5)!!.total)
        assertEquals(listOf("T", "Romaji"), history.get(5)!!.titles)
        history.clearPlace(5)
        val record = history.get(5)!!
        assertFalse(record.hasPlace)
        assertEquals("first:a", record.source)
        assertTrue(record.webtoon)
    }

    @Test fun newChaptersAreThoseOutSinceLastOpened() {
        val store = NewChapters(File(folder.root, "n.json"))
        val record = MangaRecord(source = "first:a", chapterNumber = 10.0)
        store.checked(5, "first:a", 12.0, now = 1)
        assertFalse(NewChapters.isNew(store.all()[5], record))
        store.checked(5, "first:a", 13.0, now = 2)
        assertTrue(NewChapters.isNew(store.all()[5], record))
        store.seen(5, "first:a", 13.0)
        assertFalse(NewChapters.isNew(store.all()[5], record))
        store.checked(5, "first:a", 14.0)
        assertFalse(NewChapters.isNew(store.all()[5], record.copy(chapterNumber = 14.0)))
        store.checked(5, "second:b", 20.0)
        assertFalse(NewChapters.isNew(store.all()[5], record.copy(source = "second:b")))
    }
}
