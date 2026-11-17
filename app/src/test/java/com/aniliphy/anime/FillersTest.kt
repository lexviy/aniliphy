package com.aniliphy.anime

import com.aniliphy.core.FakeWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class FillersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val showsHtml = FakeWeb.resource("/web/fillers-shows.html")
    private val shippudenHtml = FakeWeb.resource("/web/fillers-naruto-shippuden.html")

    @Test
    fun readsTheShowsLeavingOutFilmPages() {
        val shows = Fillers.parseShows(showsHtml)
        assertTrue(shows.size > 300)
        val index = shows.first { it.slug == "certain-magical-index" }
        assertEquals(listOf("A Certain Magical Index", "Toaru Majutsu No Index"), index.names)
        assertTrue(shows.none { it.names.first() == "Naruto Films" })
        assertTrue(shows.any { it.slug == "naruto-shippuden" })
    }

    @Test
    fun readsEpisodeKinds() {
        val list = Fillers.parseEpisodes(shippudenHtml)
        assertEquals(80, list.size)
        assertEquals(EpisodeKind.Filler, list.first { it.number == 57 }.kind)
        assertEquals("Robbed of Sleep", list.first { it.number == 57 }.title)
        assertEquals("2008-05-08", list.first { it.number == 57 }.aired)
        assertEquals(EpisodeKind.MangaCanon, list.first { it.number == 55 }.kind)
        assertEquals(EpisodeKind.Mixed, list.first { it.number == 1 }.kind)
        assertTrue(list.any { it.kind == EpisodeKind.Mixed })
    }

    @Test
    fun matchesNamesLoosely() {
        val shows = Fillers.parseShows(showsHtml)
        fun match(vararg titles: String) = Fillers.match(FillerTitle(1, titles.toList(), "TV", null, null), shows)
        assertEquals("naruto-shippuden" to true, match("Naruto: Shippuuden", "Naruto Shippuden").let { it!!.first.slug to it.second })
        assertEquals("fairy-tail" to false, match("Fairy Tail (2014)").let { it!!.first.slug to it.second })
        assertEquals("fairy-tail", match("Fairy Tail Final Series")!!.first.slug)
        // Something else that only starts with a show's name isn't it.
        assertNull(match("Naruto Spin-Off: Rock Lee & His Ninja Pals"))
        assertNull(match("Some Show Nobody Lists"))
    }

    @Test
    fun placesASplitTitleByAirDate() {
        val list = Fillers.parseEpisodes(shippudenHtml)
        val aired57 = LocalDate.parse(list.first { it.number == 57 }.aired)
        // A made-up "second part" starting the day episode 57 aired begins after episode 56.
        val part = FillerTitle(2, listOf("Naruto Shippuden 2nd Season"), "TV", 24, aired57)
        val show = FillerShow("naruto-shippuden", listOf("Naruto Shippuden"))
        assertEquals(56, Fillers.place(part, show, list, exact = false)!!.offset)
        // Starting years away from any episode, it isn't this show.
        val far = part.copy(start = aired57.plusYears(5))
        assertNull(Fillers.place(far, show, list, exact = false))
    }

    @Test
    fun numbersFillersAsTheTitleDoes() {
        val web = FakeWeb { _, url, _ -> FakeWeb.html(if (url.endsWith("/shows")) showsHtml else shippudenHtml) }
        val fillers = Fillers(folder.newFolder("fillers"), web)
        val first = LocalDate.parse(Fillers.parseEpisodes(shippudenHtml).first().aired)
        val title = FillerTitle(1735, listOf("Naruto: Shippuden", "Naruto: Shippuuden"), "TV", 500, first)
        val found = fillers.forTitle(title)!!
        assertEquals(0, found.link.offset)
        assertEquals(EpisodeKind.Filler, found.kindOf(57))
        assertEquals(57..71, found.runs(EpisodeKind.Filler).first())
        // After 56, the story goes on at 72: 57 to 71 are fillers.
        assertEquals(72, found.nextStory(56))
        assertEquals(56, found.nextStory(55))
        // Asked once each: the list of shows and the show's page.
        assertEquals(2, web.calls.size)
        fillers.forTitle(title)
        assertEquals(2, web.calls.size)
    }

    @Test
    fun moviesHaveNoFillers() {
        val web = FakeWeb { _, _, _ -> error("no call expected") }
        assertNull(Fillers(null, web).forTitle(FillerTitle(1, listOf("Naruto"), "MOVIE", 1, null)))
    }

    @Test
    fun runsOfNumbers() {
        assertEquals(listOf(1..3, 7..7, 9..10), Fillers.runs(listOf(3, 1, 2, 7, 9, 10)))
        assertEquals(emptyList<IntRange>(), Fillers.runs(emptyList()))
    }

    @Test
    fun normalizesRomaji() {
        assertEquals("naruto shippuden", Fillers.normalize("Naruto: Shippūden"))
        assertEquals("naruto shippuden", Fillers.normalize("NARUTO Shippuuden"))
        assertNotNull(Fillers.normalize("Gintama°"))
        assertEquals("gintama", Fillers.normalize("Gintama'"))
        assertFalse(Fillers.normalize("Fairy Tail (2014)").contains("("))
    }
}
