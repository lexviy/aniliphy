package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class Phase8Test {
    private fun anime(id: Int, eps: Int?, format: String = "TV", genres: List<String> = emptyList(), status: String? = "FINISHED", duration: Int? = 24) =
        Media(id = id, type = "ANIME", format = format, episodes = eps, duration = duration, genres = genres, status = status)

    private fun entry(media: Media, status: String = ListStatus.PLANNING) = ListEntry(status = status, media = media)

    @Test fun lengths() {
        assertEquals(RouletteLength.Evening, Roulette.length(anime(1, 1, "MOVIE", duration = 110)))
        assertEquals(RouletteLength.Short, Roulette.length(anime(2, 12)))
        assertEquals(RouletteLength.Long, Roulette.length(anime(3, 24)))
        assertEquals(RouletteLength.Long, Roulette.length(anime(4, null, status = "RELEASING")))
        assertNull(Roulette.length(anime(5, null, status = "NOT_YET_RELEASED")))
        val manga = Media(id = 6, type = "MANGA", format = "MANGA", chapters = 45)
        assertEquals(RouletteLength.Short, Roulette.length(manga))
    }

    @Test fun candidatesOnlyPlanningAndFilters() {
        val entries = listOf(
            entry(anime(1, 12, genres = listOf("Comedy"))),
            entry(anime(2, 1, "MOVIE", genres = listOf("Drama"), duration = 100)),
            entry(anime(3, 12, genres = listOf("Comedy")), ListStatus.CURRENT),
            entry(Media(id = 4, type = "MANGA", format = "MANGA", chapters = 20)),
        )
        assertEquals(listOf(1, 2), Roulette.candidates(entries, RouletteFilter()).map { it.media.id })
        assertEquals(listOf(1), Roulette.candidates(entries, RouletteFilter(genre = "Comedy")).map { it.media.id })
        assertEquals(listOf(2), Roulette.candidates(entries, RouletteFilter(formats = setOf("MOVIE"))).map { it.media.id })
        assertEquals(listOf(2), Roulette.candidates(entries, RouletteFilter(length = RouletteLength.Evening)).map { it.media.id })
        assertEquals(listOf(4), Roulette.candidates(entries, RouletteFilter(kind = MediaKind.Manga)).map { it.media.id })
    }

    @Test fun pickAvoidsLastAndReelEndsOnWinner() {
        val list = (1..3).map { entry(anime(it, 12)) }
        repeat(20) { seed -> assertNotEquals(2, Roulette.pick(list, Random(seed), avoid = 2)!!.media.id) }
        assertEquals(1, Roulette.pick(list.take(1), Random(1), avoid = 1)!!.media.id)
        assertNull(Roulette.pick(emptyList(), Random(1)))
        val reel = Roulette.reel(list, list[0], Random(3), count = 10)
        assertEquals(11, reel.size)
        assertEquals(1, reel.last().media.id)
        assertTrue(reel.dropLast(1).none { it.media.id == 1 })
        assertEquals(4, Roulette.reel(list.take(1), list[0], Random(3), count = 3).size)
    }

    @Test fun spoilerNames() {
        assertFalse(SpoilerFree.hidesName(3.0, 3))
        assertTrue(SpoilerFree.hidesName(4.0, 3))
        assertTrue(SpoilerFree.hidesName(3.5, 3))
        assertTrue(SpoilerFree.hidesName(null, 3))
        assertEquals("Episode 4", SpoilerFree.name("Episode 4 · The Betrayal", "Episode 4", 4.0, 3, on = true))
        assertEquals("Episode 4 · The Betrayal", SpoilerFree.name("Episode 4 · The Betrayal", "Episode 4", 4.0, 3, on = false))
        assertEquals("Episode 2 · Start", SpoilerFree.name("Episode 2 · Start", "Episode 2", 2.0, 3, on = true))
        assertTrue(SpoilerFree.on(global = false, forTitle = true))
        assertFalse(SpoilerFree.on(global = true, forTitle = false))
        assertTrue(SpoilerFree.on(global = true, forTitle = null))
    }

    @Test fun synopsisHiddenWhilePrequelUnfinished() {
        val s1 = Media(id = 1, type = "ANIME")
        val manga = Media(id = 9, type = "MANGA")
        val s2 = Media(id = 2, type = "ANIME", relations = Edges(listOf(RelationEdge("PREQUEL", s1), RelationEdge("ADAPTATION", manga))))
        assertTrue(SpoilerFree.hidesSynopsis(s2) { null })
        assertTrue(SpoilerFree.hidesSynopsis(s2) { if (it == 1) ListStatus.CURRENT else null })
        assertFalse(SpoilerFree.hidesSynopsis(s2) { if (it == 1) ListStatus.COMPLETED else null })
        assertFalse(SpoilerFree.hidesSynopsis(s1) { null })
    }
}
