package com.aniliphy.novels

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudTextTest {
    private fun said(text: String) = ReadAloudText.sentences(text).map { text.substring(it.first, it.last + 1) }

    @Test
    fun splitsSentencesButNotAbbreviationsOrNumbers() {
        assertEquals(
            listOf("Mr. Smith paid 3.5 gold.", "Dr. Who didn't.", "Why?!", "Fine…"),
            said("Mr. Smith paid 3.5 gold. Dr. Who didn't. Why?! Fine…"),
        )
    }

    @Test
    fun quotesStayWithWhatFollows() {
        assertEquals(listOf("\"You're late,\" said a voice.", "He turned."), said("\"You're late,\" said a voice. He turned."))
        assertEquals(listOf("\"Wait!\" he said.", "Nobody did."), said("\"Wait!\" he said. Nobody did."))
    }

    @Test
    fun skipsPunctuationOnlyAndBlank() {
        assertEquals(listOf("Yes."), said("  * * *  Yes.  "))
        assertEquals(emptyList<String>(), said("   "))
    }

    @Test
    fun cutsVeryLongSentencesAtSpaces() {
        val long = (1..900).joinToString(" ") { "word" } + "."
        val parts = said(long)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= ReadAloudText.MAX_LENGTH })
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun marksTheSameSentencesItSays() {
        val document = Jsoup.parse(
            "<html><head><title>Not read</title></head><body>" +
                "<h1>Chapter 4</h1><p>He ran. The <i>gates were</i> shut.</p><div>Line one<br/>Line two</div>" +
                "<p><ruby>漢<rt>kan</rt></ruby></p><p>   </p></body></html>",
        )
        val spoken = ReadAloudText.mark(document)
        assertEquals(listOf("Chapter 4", "He ran.", "The gates were shut.", "Line one", "Line two", "漢"), spoken.map { it.text })
        assertEquals(listOf(true, false, true, true, true, true), spoken.map { it.paragraphEnd })
        spoken.indices.forEach { i ->
            assertEquals(spoken[i].text, document.select("[data-s=$i]").joinToString("") { it.wholeText() }.replace(Regex("\\s+"), " "))
        }
        assertEquals(3, document.select("[data-s=2]").size)
        assertTrue(document.select("rt").text() == "kan")
    }

    @Test
    fun prepareAndSpokenAgree() {
        val html = "<html><body><p>One. Two.</p><svg><image href=\"a.png\"/></svg><p>Three</p></body></html>"
        val style = PageStyle(true, 17, NovelFont.Serif, 1.5f, 20, 16, 28, true, "#fff", "#000", true)
        val page = Jsoup.parse(EpubPage.prepare(html, style))
        val spoken = EpubPage.spoken(html)
        assertEquals(listOf("One.", "Two.", "Three"), spoken.map { it.text })
        assertEquals(spoken.size, page.select("[data-s]").map { it.attr("data-s") }.distinct().size)
    }

    @Test
    fun timeLeft() {
        val spoken = listOf(Spoken("a".repeat(900), false, 0), Spoken("b".repeat(900), true, 900))
        assertEquals(2, ReadAloudText.minutesLeft(spoken, 0, 1f))
        assertEquals(1, ReadAloudText.minutesLeft(spoken, 1, 1f))
        assertEquals(1, ReadAloudText.minutesLeft(spoken, 0, 2f))
        assertEquals(0, ReadAloudText.minutesLeft(spoken, 2, 1f))
    }
}
