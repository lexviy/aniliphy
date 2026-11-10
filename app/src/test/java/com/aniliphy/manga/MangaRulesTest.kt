package com.aniliphy.manga

import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.source.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaRulesTest {
    private fun chapter(number: Double?, id: String = "c$number") = SourceChapter(id, "Chapter $number", "https://x/$id", number)

    private val chapters = listOf(1.0, 2.0, 2.5, 3.0, 4.0, 5.0).map { chapter(it) }

    private fun place(number: Double, page: Int, id: String? = "c$number") = MangaRecord(chapterId = id, chapterNumber = number, page = page)

    @Test fun startsAtTheBeginningWithNothingKnown() {
        assertEquals(StartPlace(0, 0), MangaRules.start(chapters, null, null))
    }

    @Test fun resumesWhereReadingStopped() {
        assertEquals(StartPlace(2, 7), MangaRules.start(chapters, null, place(2.5, 7)))
        assertEquals(StartPlace(2, 7), MangaRules.start(chapters, ListProgress(progress = 2, status = ListStatus.CURRENT), place(2.5, 7)))
    }

    @Test fun findsTheSavedChapterByNumberOnAnotherSource() {
        assertEquals(StartPlace(3, 4), MangaRules.start(chapters, null, place(3.0, 4, id = null)))
        assertEquals(StartPlace(3, 4), MangaRules.start(chapters, null, place(3.0, 4, id = "other-source-id")))
    }

    @Test fun aniListFurtherOnOpensTheChapterAfterIt() {
        assertEquals(StartPlace(2, 0), MangaRules.start(chapters, ListProgress(progress = 2, status = ListStatus.CURRENT), place(1.0, 9)))
        assertEquals(StartPlace(4, 0), MangaRules.start(chapters, ListProgress(progress = 3, status = ListStatus.CURRENT), place(3.0, 2)))
        assertEquals(StartPlace(4, 0), MangaRules.start(chapters, ListProgress(progress = 3, status = ListStatus.CURRENT), null))
        assertEquals(StartPlace(5, 0), MangaRules.start(chapters, ListProgress(progress = 9, status = ListStatus.CURRENT), null))
        val gap = listOf(1.0, 2.0, 5.0).map { chapter(it) }
        assertEquals(StartPlace(2, 0), MangaRules.start(gap, ListProgress(progress = 3, status = ListStatus.CURRENT), null))
        val late = listOf(101.0, 102.0).map { chapter(it) }
        assertEquals(StartPlace(0, 0), MangaRules.start(late, ListProgress(progress = 50, status = ListStatus.CURRENT), null))
    }

    @Test fun completedStartsAgain() {
        val done = ListProgress(progress = 5, status = ListStatus.COMPLETED)
        assertEquals(StartPlace(0, 0), MangaRules.start(chapters, done, null))
        assertEquals(StartPlace(0, 0), MangaRules.start(chapters, done, place(5.0, 12)))
        assertEquals(StartPlace(0, 0), MangaRules.start(chapters, done, place(2.0, 3)))
    }

    @Test fun percentRead() {
        assertEquals(0, MangaRules.percent(MangaRecord(chapterNumber = 2.0, page = 12, pages = 26, total = 232), null))
        assertEquals(50, MangaRules.percent(MangaRecord(chapterNumber = 50.0, page = 25, pages = 26, total = 100), null))
        assertEquals(25, MangaRules.percent(MangaRecord(chapterNumber = 10.0, page = 9, pages = 10), latest = 40.0))
        assertEquals(100, MangaRules.percent(MangaRecord(chapterNumber = 12.0, page = 19, pages = 20, total = 10), null))
        assertNull(MangaRules.percent(MangaRecord(chapterNumber = 3.0, page = 1, pages = 10), latest = null))
        assertNull(MangaRules.percent(MangaRecord(), latest = 20.0))
        assertEquals(5.0, MangaRules.latest(chapters)!!, 0.0)
    }

    @Test fun chapterCountsAreWholeAndCapped() {
        assertEquals(12, MangaRules.aniListChapter(chapter(12.5)))
        assertEquals(12, MangaRules.aniListChapter(chapter(12.0)))
        assertNull(MangaRules.aniListChapter(chapter(0.0)))
        assertNull(MangaRules.aniListChapter(chapter(0.5)))
        assertNull(MangaRules.aniListChapter(chapter(null)))
        assertEquals(100, MangaRules.aniListChapter(chapter(101.0), total = 100))
        assertEquals(101, MangaRules.aniListChapter(chapter(101.0), total = 0))
    }

    @Test fun readMarks() {
        assertTrue(MangaRules.counts(ReadMark.LastPageOpens, page = 19, pages = 20, atEnd = false))
        assertFalse(MangaRules.counts(ReadMark.LastPageOpens, page = 18, pages = 20, atEnd = false))
        assertFalse(MangaRules.counts(ReadMark.EndOfLastPage, page = 19, pages = 20, atEnd = false))
        assertTrue(MangaRules.counts(ReadMark.EndOfLastPage, page = 19, pages = 20, atEnd = true))
        assertFalse(MangaRules.counts(ReadMark.LastPageOpens, page = 0, pages = 0, atEnd = true))
    }

    @Test fun resumingOnAniList() {
        assertTrue(MangaRules.shouldResume(ListProgress(progress = 3, status = ListStatus.PAUSED)))
        assertTrue(MangaRules.shouldResume(ListProgress(progress = 3, status = ListStatus.DROPPED)))
        assertTrue(MangaRules.shouldResume(ListProgress(progress = 1, status = ListStatus.PLANNING)))
        assertFalse(MangaRules.shouldResume(ListProgress(progress = 0, status = ListStatus.PLANNING)))
        assertFalse(MangaRules.shouldResume(ListProgress(progress = 3, status = ListStatus.CURRENT)))
        assertFalse(MangaRules.shouldResume(ListProgress(progress = 3, status = ListStatus.COMPLETED)))
        assertFalse(MangaRules.shouldResume(null))
    }

    @Test fun spreadsAndStrips() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), MangaRules.spreads(5))
        assertEquals(listOf(13, 14), MangaRules.spreads(15).last())
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), MangaRules.spreads(5, shifted = true))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4), listOf(5)), MangaRules.spreads(6, wide = setOf(2)))
        assertTrue(MangaRules.isLongStrip(800, 12000))
        assertFalse(MangaRules.isLongStrip(800, 1200))
        assertFalse(MangaRules.isLongStrip(0, 1200))
        assertTrue(MangaRules.isWebtoonOrigin("KR"))
        assertFalse(MangaRules.isWebtoonOrigin("JP"))
        assertFalse(MangaRules.isWebtoonOrigin(null))
    }
}
