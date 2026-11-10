package com.aniliphy.novels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChapterRulesTest {
    private val titles = listOf("Cover", "Prologue", "Chapter 1", "Chapter 2: Rain", "Interlude", "Chapter III", "Afterword")

    @Test fun runsAreConsecutiveChapters() {
        assertEquals(listOf(Segment(2, 3), Segment(5, 5)), ChapterRules.detectRuns(titles))
    }

    @Test fun otherLanguagesAndBareNumbersCount() {
        val runs = ChapterRules.detectRuns(listOf("Capítulo 2", "第3章", "12 The Lake", "Notes"))
        assertEquals(listOf(Segment(0, 2)), runs)
    }

    @Test fun aNewVolumeStartsAtItsPrologue() {
        assertEquals(1, ChapterRules.leadIn(titles, ChapterRules.detectRuns(titles)))
        assertNull(ChapterRules.leadIn(listOf("Cover"), emptyList()))
    }

    @Test fun countingAddsSegmentsUpToAChapter() {
        val segments = listOf(Segment(2, 3), Segment(5, null))
        assertEquals(5, ChapterRules.counted(segments, 8))
        assertEquals(1, ChapterRules.counted(segments, 8, upTo = 2))
        assertEquals(2, ChapterRules.counted(segments, 8, upTo = 4))
        assertEquals(0, ChapterRules.counted(segments, 8, upTo = 0))
    }

    @Test fun outlineUsesTheShallowestLevelWithEnoughPages() {
        val outline = listOf(
            Triple(1, "Book", 0),
            Triple(2, "One", 3),
            Triple(2, "Two", 8),
            Triple(2, " Three ", 12),
            Triple(2, "Too far", 99),
        )
        assertEquals(listOf("One" to 3, "Two" to 8, "Three" to 12), ChapterRules.outlineStarts(outline, 20))
        assertNull(ChapterRules.outlineStarts(listOf(Triple(1, "Only", 0)), 20))
    }

    @Test fun headingsIgnoreTheTableOfContents() {
        val pages = listOf(
            listOf("Chapter 1", "Chapter 2", "Chapter 3"),
            listOf("Chapter 1", "text"),
            listOf("text"),
            listOf("page", "Chapter 2"),
            listOf("text"),
        )
        assertEquals(listOf("Chapter 1" to 1, "Chapter 2" to 3), ChapterRules.headingStarts(pages))
        assertNull(ChapterRules.headingStarts(listOf(listOf("Chapter 1"), listOf("text"))))
    }

    @Test fun pdfWithoutHeadingsIsSplitInBlocks() {
        val chapters = ChapterRules.pdfChapters(null, 23)
        assertEquals(listOf("Pages 1-10", "Pages 11-20", "Pages 21-23"), chapters.map { it.title })
        assertEquals(23, chapters.last().end)
    }

    @Test fun pdfWithStartsKeepsTheFrontMatter() {
        val chapters = ChapterRules.pdfChapters(listOf("One" to 3, "Two" to 8), 12)
        assertEquals(
            listOf(PdfChapter("Front matter", 0, 3), PdfChapter("One", 3, 8), PdfChapter("Two", 8, 12)),
            chapters,
        )
    }

    @Test fun theFinalChapterIsAChapter() {
        val titles = listOf("Title Page", "Chapter 1: A", "Chapter 2: B", "Final Chapter", "Afterword")
        assertEquals(listOf(Segment(1, 3)), ChapterRules.detectRuns(titles))
    }

    @Test fun findsAChapterAcrossVolumes() {
        val volumes = listOf(
            Triple(listOf(Segment(1, 5)), 7, listOf(0, 1, 2, 5, 6)),
            Triple(listOf(Segment(2, 3)), 5, null as List<Int>?),
        )
        assertEquals(0 to 1, ChapterRules.locate(1, volumes))
        assertEquals(0 to 5, ChapterRules.locate(3, volumes))
        assertEquals(1 to 2, ChapterRules.locate(4, volumes))
        assertEquals(1 to 3, ChapterRules.locate(5, volumes))
        assertEquals(null, ChapterRules.locate(6, volumes))
        assertEquals(null, ChapterRules.locate(0, volumes))
    }

    @Test fun labelsUseChapterNumbers() {
        val segments = listOf(Segment(1, 5))
        val starts = listOf(0, 1, 2, 5, 6)
        assertEquals("Before chapter 1", ChapterRules.label(segments, 7, 0, starts))
        assertEquals("Chapter 2 of 3", ChapterRules.label(segments, 7, 4, starts))
        assertEquals("After chapter 3", ChapterRules.label(segments, 7, 6, starts))
        assertEquals("Section 2 of 7", ChapterRules.label(emptyList(), 7, 1))
    }
}
