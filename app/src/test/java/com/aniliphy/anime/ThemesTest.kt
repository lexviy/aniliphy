package com.aniliphy.anime

import com.aniliphy.core.FakeWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ThemesTest {
    private val answer = FakeWeb.resource("/web/animethemes.json")

    @Test
    fun readsThemesByAniListId() {
        val themes = Themes.parse(answer)
        val naruto = themes.getValue(20)
        val op2 = naruto.first { it.slug == "OP2" }
        assertEquals("OP", op2.type)
        assertEquals("Haruka Kanata", op2.song)
        assertEquals(listOf("Asian Kung-Fu Generation"), op2.artists)
        assertEquals("26-53", op2.episodes)
        assertEquals("https://v.animethemes.moe/Naruto-OP2.webm", op2.videoUrl)
        assertEquals("https://a.animethemes.moe/Naruto-OP2.ogg", op2.audioUrl)
        assertEquals("Opening 2", op2.label)
        // Openings first, then endings.
        assertEquals("OP", naruto.first().type)
        assertTrue(themes.getValue(21).size > 50)
    }

    @Test
    fun prefersTheCleanVideo() {
        val op1 = Themes.parse(answer).getValue(21).first { it.slug == "OP1" }
        assertEquals("https://v.animethemes.moe/OnePiece-OP1-NCDVD480.webm", op1.videoUrl)
    }

    @Test
    fun spoilersAreMarkedOrLater() {
        val onePiece = Themes.parse(answer).getValue(21)
        assertTrue(onePiece.first { it.slug == "OP14" }.spoils(seen = 2000))
        val op2 = onePiece.first { it.slug == "OP2" }
        // Plays from episode 48: a spoiler at episode 10, not at 47.
        assertTrue(op2.spoils(seen = 10))
        assertFalse(op2.spoils(seen = 47))
        assertFalse(op2.spoils(seen = null))
    }

    @Test
    fun readsEpisodeRanges() {
        assertEquals(listOf(517..541, 543..589), Themes.ranges("517-541, 543-589"))
        assertEquals(listOf(128..128), Themes.ranges("128"))
        assertEquals(emptyList<IntRange>(), Themes.ranges(null))
    }

    @Test
    fun asksManyTitlesAtOnceAndKeepsThem() {
        val web = FakeWeb { _, _, _ -> FakeWeb.json(answer) }
        val themes = Themes(null, web)
        val found = themes.forMedia(listOf(20, 21, 99999), now = 1_000)
        assertEquals(1, web.calls.size)
        assertTrue(web.calls.single().body!!.decodeToString().contains("id: [20,21,99999]"))
        assertTrue(found.getValue(99999).isEmpty())
        themes.forMedia(listOf(21), now = 2_000)
        assertEquals(1, web.calls.size)
    }

    @Test(expected = IOException::class)
    fun aBotCheckPageIsAFailure() {
        Themes(null, FakeWeb { _, _, _ -> FakeWeb.html("<html>503</html>", 200) }).forMedia(listOf(1))
    }
}
