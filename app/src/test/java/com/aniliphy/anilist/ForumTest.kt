package com.aniliphy.anilist

import com.aniliphy.anilist.ForumText.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForumTest {
    private fun t(id: Int, title: String, replies: Int = 0) = ForumThread(id = id, title = title, replyCount = replies)

    @Test fun findsTheEpisodeThreadAndNotItsTeens() {
        val threads = listOf(
            t(1, "[Spoilers] The Apothecary Diaries Season 3 - Episode 7 Discussion"),
            t(2, "[Spoilers] The Apothecary Diaries Season 3 - Episode 17 Discussion"),
            t(3, "Season 3 hype thread"),
        )
        assertEquals(1, EpisodeThreads.find(threads, 7, chapter = false)?.id)
        assertEquals(2, EpisodeThreads.find(threads, 17, chapter = false)?.id)
        assertNull(EpisodeThreads.find(threads, 3, chapter = false))
    }

    @Test fun chaptersAndRanges() {
        val threads = listOf(
            t(1, "[Spoilers] Witchriv - Chapter 46 Discussion"),
            t(2, "Gachiakuta – Chapters 170-172 Release Discussion", 5),
            t(3, "Gachiakuta – Chapter 171 thoughts", 1),
        )
        assertEquals(1, EpisodeThreads.find(threads, 46, chapter = true)?.id)
        assertEquals(2, EpisodeThreads.find(threads, 171, chapter = true)?.id)
        assertNull(EpisodeThreads.find(threads, 46, chapter = false))
        assertEquals(170..172, EpisodeThreads.numbers(threads[1].title, chapter = true))
    }

    @Test fun spoilersImagesAndCentring() {
        val blocks = ForumText.parse("Hello **world**\n\n~!secret img220(https://x/a.png)!~\n\n~~~centred~~~")
        assertEquals(Block.Text(listOf(ForumText.Span("Hello "), ForumText.Span("world", bold = true))), blocks[0])
        val spoiler = blocks[1] as Block.Spoiler
        assertTrue(spoiler.blocks.any { it == Block.Image("https://x/a.png") })
        assertEquals(Block.Text(listOf(ForumText.Span("centred")), centered = true), blocks[2])
    }

    @Test fun quotesLinksAndVideos() {
        val blocks = ForumText.parse("> quoted line\nnormal [site](https://anilist.co) text\nyoutube(dQw4w9WgXcQ)")
        assertTrue(blocks[0] is Block.Quote)
        val text = blocks[1] as Block.Text
        assertTrue(text.spans.any { it.link == "https://anilist.co" && it.text == "site" })
        assertEquals(Block.Video("https://youtu.be/dQw4w9WgXcQ"), blocks[2])
    }
}
