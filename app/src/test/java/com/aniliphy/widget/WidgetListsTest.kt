package com.aniliphy.widget

import com.aniliphy.anime.AnimeRecord
import com.aniliphy.manga.LatestChapter
import com.aniliphy.manga.MangaRecord
import com.aniliphy.novels.LastRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetListsTest {
    @Test
    fun animeIsNewestFirstAndSkipsTitlesWithoutAPlace() {
        val records = mapOf(
            1 to AnimeRecord(episodeNumber = 3.0, title = "Old", updated = 100),
            2 to AnimeRecord(episodeNumber = 7.0, positionMs = 754_000, title = "New", cover = "https://c/2", updated = 300),
            3 to AnimeRecord(source = "first:show", title = "Only a source", updated = 500),
            4 to AnimeRecord(episodeNumber = 1.0, title = null, updated = 400),
        )
        val items = WidgetLists.anime(records)
        assertEquals(listOf("2", "1"), items.map { it.key })
        assertEquals(WidgetItem(WidgetKind.Anime, "2", "New", "Ep 7 · 12:34", "https://c/2", updated = 300), items[0])
        assertEquals("Ep 3", items[1].detail)
    }

    @Test
    fun animePlaceUsesTheNameWhenThereIsNoNumber() {
        assertEquals("Ep 12.5 · 1:02:03", WidgetLists.animePlace(AnimeRecord(episodeNumber = 12.5, positionMs = 3_723_000)))
        assertEquals("Special", WidgetLists.animePlace(AnimeRecord(episodeId = "x", episodeName = "Special")))
    }

    @Test
    fun mangaShowsChapterAndPage() {
        val items = WidgetLists.manga(
            mapOf(
                5 to MangaRecord(chapterId = "a", chapterNumber = 138.0, page = 13, pages = 20, title = "Kagurabachi", updated = 2),
                6 to MangaRecord(chapterId = "b", chapterName = "Oneshot", title = "Other", updated = 1),
            ),
        )
        assertEquals(listOf("Ch 138 · p. 14/20", "Oneshot"), items.map { it.detail })
    }

    @Test
    fun keepsOnlyTheLimit() {
        val records = (1..15).associateWith { AnimeRecord(episodeNumber = 1.0, title = "T$it", updated = it.toLong()) }
        val items = WidgetLists.anime(records, limit = 10)
        assertEquals(10, items.size)
        assertEquals("15", items.first().key)
    }

    @Test
    fun novelsPreferTheLibraryTitleAndShortenTheLabel() {
        val places = listOf(
            NovelPlace("1", LastRead("Vol 1.epub", 4, updated = 10.0, title = "Stored", label = "Chapter 4 of 12")),
            NovelPlace("local:x", LastRead("x.epub", 2, updated = 20.0, title = null, label = "")),
            NovelPlace("local:y", LastRead("y.epub", 1, updated = 30.0, title = "Y", label = "Prologue")),
        )
        val items = WidgetLists.novels(places, titles = mapOf("1" to "From library"), covers = mapOf("1" to "/covers/1.jpg"))
        assertEquals(listOf("local:y", "1"), items.map { it.key })
        assertEquals(WidgetItem(WidgetKind.Novel, "1", "From library", "Chapter 4", "/covers/1.jpg", updated = 10_000), items[1])
        assertEquals("Prologue", items[0].detail)
    }

    @Test
    fun novelsNotOnThePhoneAreLeftOut() {
        val places = listOf(
            NovelPlace("here", LastRead("a.epub", 1, updated = 1.0, title = "Here")),
            NovelPlace("synced", LastRead("b.epub", 1, updated = 2.0, title = "Only synced")),
        )
        assertEquals(listOf("here"), WidgetLists.novels(places, library = listOf("here")).map { it.key })
        assertEquals(listOf("synced", "here"), WidgetLists.novels(places).map { it.key })
    }

    @Test
    fun mangaCarriesProgressAndNewChapters() {
        val records = mapOf(
            1 to MangaRecord(source = "s:1", chapterId = "a", chapterNumber = 10.0, title = "Behind", updated = 2),
            2 to MangaRecord(source = "s:2", chapterId = "b", chapterNumber = 5.0, title = "Caught up", updated = 1),
        )
        val latest = mapOf(1 to LatestChapter("s:1", latest = 20.0, seen = 10.0), 2 to LatestChapter("s:2", latest = 5.0, seen = 5.0))
        val items = WidgetLists.manga(records, latest)
        assertEquals(45, items[0].percent)
        assertTrue(items[0].isNew)
        assertFalse(items[1].isNew)
    }

    @Test
    fun animeCarriesProgressAndNewEpisodes() {
        val records = mapOf(7 to AnimeRecord(episodeNumber = 3.0, title = "Airing", updated = 1))
        val item = WidgetLists.anime(records, released = mapOf(7 to 10), newOut = setOf(7)).single()
        assertEquals(20, item.percent)
        assertTrue(item.isNew)
        assertEquals(null, WidgetLists.anime(records).single().percent)
    }
}
