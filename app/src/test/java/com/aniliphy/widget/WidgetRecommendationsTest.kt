package com.aniliphy.widget

import com.aniliphy.anilist.CoverImage
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.MediaTitle
import com.aniliphy.anilist.Recommendation
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetRecommendationsTest {
    private fun media(id: Int, type: String = "ANIME", format: String? = null) =
        Media(id = id, type = type, format = format, title = MediaTitle(english = "M$id"), coverImage = CoverImage(large = "https://c/$id"))

    private fun rec(id: Int, rating: Int, type: String = "ANIME", format: String? = null) = Recommendation(rating, media(id, type, format))

    private val frieren = RecommendationSeed(1, "Frieren", MediaKind.Anime)
    private val kagura = RecommendationSeed(2, "Kagurabachi", MediaKind.Manga)

    @Test
    fun recommendedFromSeveralSeedsComesFirst() {
        val picked = WidgetRecommendations.pick(
            listOf(frieren, kagura),
            mapOf(
                1 to listOf(rec(10, 500), rec(11, 20)),
                2 to listOf(rec(11, 20)),
            ),
            exclude = emptySet(),
        )
        assertEquals(listOf("11", "10"), picked.map { it.key })
    }

    @Test
    fun leavesOutListsHistoryAndSeedsAndUntitled() {
        val untitled = Recommendation(5, Media(id = 13, type = "ANIME"))
        val picked = WidgetRecommendations.pick(
            listOf(frieren, kagura),
            mapOf(1 to listOf(rec(10, 5), rec(2, 5), rec(12, 5), untitled)),
            exclude = setOf(10),
        )
        assertEquals(listOf("12"), picked.map { it.key })
    }

    @Test
    fun kindsTakeTurnsAndSayWhy() {
        val picked = WidgetRecommendations.pick(
            listOf(frieren, kagura),
            mapOf(
                1 to listOf(rec(10, 90), rec(11, 80), rec(12, 70)),
                2 to listOf(rec(20, 5, "MANGA", "MANGA"), rec(30, 1, "MANGA", "NOVEL")),
            ),
            exclude = emptySet(),
        )
        assertEquals(listOf("10", "20", "30", "11", "12"), picked.map { it.key })
        assertEquals(listOf(WidgetKind.Anime, WidgetKind.Manga, WidgetKind.Novel), picked.take(3).map { it.of })
        assertEquals("Because you watched Frieren", picked[0].detail)
        assertEquals("Because you read Kagurabachi", picked[1].detail)
        assertEquals(WidgetKind.Recommendation, picked[0].kind)
        assertEquals("https://c/10", picked[0].cover)
    }

    @Test
    fun keepsTheLimit() {
        val many = (100 until 140).map { rec(it, 1) }
        assertEquals(15, WidgetRecommendations.pick(listOf(frieren), mapOf(1 to many), emptySet()).size)
    }

    private fun entry(id: Int, status: String, type: String = "MANGA", format: String? = "MANGA", score: Double = 0.0, updated: Long = 0) =
        ListEntry(status = status, updatedAt = updated, score = score, media = media(id, type, format))

    @Test
    fun seedsAreBalancedBetweenKindsAndHalfNowHalfLoved() {
        val entries = (1..8).map { entry(it, "CURRENT", updated = it.toLong()) } +
            listOf(
                entry(20, "COMPLETED", score = 9.0),
                entry(21, "COMPLETED", score = 10.0),
                entry(22, "COMPLETED", score = 0.0),
                entry(23, "PLANNING", score = 10.0),
                entry(30, "COMPLETED", "ANIME", null, score = 8.0),
                entry(31, "REPEATING", "ANIME", null, updated = 5),
                entry(40, "CURRENT", "MANGA", "NOVEL"),
            )
        val seeds = WidgetRecommendations.seeds(entries)
        assertEquals(listOf(31, 30, 8, 7, 21, 20, 40), seeds.map { it.mediaId })
        assertEquals(listOf(MediaKind.Anime, MediaKind.Anime), seeds.take(2).map { it.kind })
        assertEquals(MediaKind.LightNovel, seeds.last().kind)
    }

    @Test
    fun guestSeedsComeFromHistory() {
        val history = (1..6).map { RecommendationSeed(it, "A$it", MediaKind.Anime) } + RecommendationSeed(9, "M", MediaKind.Manga) +
            RecommendationSeed(1, "again", MediaKind.Anime)
        assertEquals(listOf(1, 2, 3, 4, 9), WidgetRecommendations.seedsFromHistory(history).map { it.mediaId })
    }
}
