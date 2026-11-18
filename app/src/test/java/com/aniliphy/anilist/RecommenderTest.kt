package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommenderTest {
    private fun media(id: Int, genres: List<String> = emptyList(), tags: List<String> = emptyList(), studio: String? = null, score: Int? = 75, adult: Boolean = false) =
        DiscoverMedia(
            id = id,
            type = "ANIME",
            title = MediaTitle(english = "T$id"),
            genres = genres,
            tags = tags.map { MediaTag(it, rank = 80) },
            studios = Nodes(listOfNotNull(studio?.let { Named(id * 10, it) })),
            averageScore = score,
            isAdult = adult,
        )

    private fun entry(m: DiscoverMedia, score: Double, status: String = ListStatus.COMPLETED) = TasteEntry(status, score, m)

    // Loves calm slice of life, drops action, scores on 10.
    private val mine = listOf(
        entry(media(1, listOf("Slice of Life"), listOf("Iyashikei"), "Artland"), 9.5),
        entry(media(2, listOf("Slice of Life"), listOf("Iyashikei")), 9.0),
        entry(media(3, listOf("Slice of Life", "Drama")), 8.5),
        entry(media(4, listOf("Action"), listOf("Battle Royale"), "Mappa"), 4.0, ListStatus.DROPPED),
        entry(media(5, listOf("Action"), listOf("Battle Royale")), 5.0),
        entry(media(6, listOf("Comedy")), 7.0),
    )

    @Test
    fun likesFollowTheUsersOwnScale() {
        val likes = Recommender.likes(mine)
        assertTrue(likes.getValue(1) > likes.getValue(6))
        assertTrue(likes.getValue(4) < 0)
        assertTrue(likes.getValue(1) > 0)
    }

    @Test
    fun withoutScoresStatusDecides() {
        val unscored = mine.map { it.copy(score = 0.0) }
        val likes = Recommender.likes(unscored)
        assertEquals(1.0, likes.getValue(1), 0.0)
        assertEquals(-1.0, likes.getValue(4), 0.0)
    }

    @Test
    fun ranksWhatMatchesTasteAndLeavesOutTheList() {
        val calm = media(10, listOf("Slice of Life"), listOf("Iyashikei"))
        val fights = media(11, listOf("Action"), listOf("Battle Royale"))
        val result = Recommender.rank(mine, listOf(calm, fights, mine[0].media, media(12, listOf("Slice of Life"), adult = true)))
        assertEquals(listOf(10), result.map { it.media.id })
        assertTrue(result[0].match in 80..99)
        assertTrue(result[0].reasons.any { it.label == "Iyashikei (tag)" && it.weight > 0 })
    }

    @Test
    fun aniListLinksAndFriendsAddReasons() {
        val plain = media(20, listOf("Comedy"))
        val result = Recommender.rank(
            mine,
            listOf(plain),
            links = mapOf(1 to listOf(20 to 40)),
            friends = listOf(FriendTaste("a", mapOf(20 to 9.0)), FriendTaste("b", mapOf(20 to 8.0))),
        )
        val labels = result.single().reasons.map { it.label }
        assertTrue("Like T1 (9.5)" in labels)
        assertTrue("2 you follow loved it: 9, 8" in labels)
        assertEquals(setOf("Like T1 (9.5)", "2 you follow loved it: 9, 8"), result.single().short.split(" · ").toSet())
    }

    @Test
    fun aSeriesIsNamedOnce() {
        val sequel = entry(media(7, listOf("Slice of Life")).copy(title = MediaTitle(english = "T1 Season 2")), 9.5)
        val result = Recommender.rank(mine + sequel, listOf(media(20, listOf("Comedy"))), links = mapOf(1 to listOf(20 to 40), 7 to listOf(20 to 40)))
        assertEquals(1, result.single().reasons.count { it.kind == ReasonKind.Seed })
    }

    @Test
    fun notForMeIsLearned() {
        val calm = media(10, listOf("Slice of Life"), listOf("Iyashikei"))
        assertTrue(Recommender.rank(mine, listOf(calm), learned = DiscoverLearned(hidden = setOf(10))).isEmpty())
        val before = Recommender.rank(mine, listOf(calm)).single()
        val after = Recommender.rank(mine, listOf(calm), learned = DiscoverLearned(lessTags = setOf("Iyashikei")))
        assertTrue(after.isEmpty() || after.single().reasons.any { it.label == "Less Iyashikei (you asked)" })
        assertTrue(after.isEmpty() || after.single().reasons.sumOf { it.weight } < before.reasons.sumOf { it.weight })
    }

    @Test
    fun favouritesAndSeeds() {
        assertEquals(listOf("Iyashikei"), Recommender.favourites(mine, "t:", 3))
        assertEquals(listOf(1, 2), Recommender.seeds(mine, 2))
    }

    @Test
    fun lovedByFriends() {
        fun le(id: Int, score: Double, status: String = ListStatus.COMPLETED) = ListEntry(status = status, score = score, media = Media(id = id))
        val scorer = (1..6).map { le(it, if (it < 3) 9.0 else 6.0) }
        assertEquals(setOf(1, 2), Recommender.loved(scorer).keys)
        val nonScorer = listOf(le(1, 0.0), le(2, 0.0, ListStatus.DROPPED))
        assertEquals(setOf(1), Recommender.loved(nonScorer).keys)
    }
}
