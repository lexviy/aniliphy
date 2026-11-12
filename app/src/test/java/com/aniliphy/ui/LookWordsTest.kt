package com.aniliphy.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookWordsTest {
    private val styles: List<UiStyle?> = UiStyle.entries + null

    @Test
    fun releaseKeepsTitleAndNumberInEveryLook() {
        for (style in styles) for (chapter in listOf(true, false)) {
            val (head, text) = LookWords.release(style, chapter, "Frieren", "117.5")
            assertTrue("$style head empty", head.isNotBlank())
            assertTrue("$style lost the title: $text", "Frieren" in text)
            assertTrue("$style lost the number: $text", "117.5" in text)
        }
    }

    @Test
    fun plainWordsWhenMomentsAreOff() {
        assertEquals("New chapter" to "Frieren — chapter 3", LookWords.release(null, true, "Frieren", "3"))
        assertEquals("Synced.", LookWords.synced(null))
        assertEquals("Continue", LookWords.continueTitle(null))
        assertNull(LookWords.endOfList(null))
        assertNull(LookWords.listCount(null, 4, true))
    }

    @Test
    fun dayKeepsThePlainWordsWhereItWraps() {
        val day = LocalDate.of(2026, 11, 5) // a Thursday
        val today = LocalDate.of(2026, 11, 12)
        assertEquals("Thursday, 5 November", LookWords.day(UiStyle.Modern, day, "Thursday, 5 November", today))
        assertEquals("木 · Thursday, 5 November", LookWords.day(UiStyle.Tankobon, day, "Thursday, 5 November", today))
        assertEquals("Thursday, the 5th of November", LookWords.day(UiStyle.Bunko, day, "Thursday, 5 November", today))
        assertEquals("Yesterday", LookWords.day(UiStyle.Bunko, today.minusDays(1), "Yesterday", today))
        assertEquals("2026-11-05 (THU)", LookWords.day(UiStyle.Konbini, day, "Thursday, 5 November", today))
        for (style in UiStyle.entries) assertTrue(LookWords.day(style, day, "x", today).isNotBlank())
    }

    @Test
    fun listCountSingularAndPlural() {
        assertEquals("1 title", LookWords.listCount(UiStyle.Modern, 1, true))
        assertEquals("3 titles", LookWords.listCount(UiStyle.Modern, 3, false))
        assertEquals("Party: 1 member", LookWords.listCount(UiStyle.Jrpg, 1, true))
        assertEquals("12 books on the shelf", LookWords.listCount(UiStyle.Bunko, 12, false))
        for (style in UiStyle.entries) {
            assertNotNull(LookWords.listCount(style, 7, true)!!.takeIf { "7" in it })
            assertNotNull(LookWords.endOfList(style))
        }
    }

    @Test
    fun milestoneKeepsWhatWasReached() {
        for (style in styles) assertTrue("1,000 episodes" in LookWords.milestone(style, "1,000 episodes"))
    }
}
