package com.aniliphy.anilist

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FranchisesTest {
    private fun entry(id: Int, status: String = ListStatus.COMPLETED, type: String = "ANIME") =
        ListEntry(id = id, status = status, media = Media(id = id, type = type))

    private fun link(id: Int, relation: String, type: String = "ANIME") = MediaLink(id, type, relation)

    @Test fun seasonsGroupEvenAcrossAMissingOne() {
        val entries = listOf(entry(1), entry(3), entry(9))
        val links = mapOf(
            1 to listOf(link(2, "SEQUEL")),
            3 to listOf(link(2, "PREQUEL")),
            9 to listOf(link(10, "SEQUEL")),
        )
        val groups = Franchises.group(entries, links, Franchises.SEASONS)
        assertEquals(listOf(listOf(1, 3), listOf(9)), groups.map { g -> g.map { it.media.id } })
    }

    @Test fun sideStoriesOnlyJoinWhenAsked() {
        val entries = listOf(entry(1), entry(5))
        val links = mapOf(5 to listOf(link(1, "PARENT")))
        assertEquals(2, Franchises.group(entries, links, Franchises.SEASONS).size)
        assertEquals(1, Franchises.group(entries, links, Franchises.WITH_SIDE_STORIES).size)
    }

    @Test fun adaptationsAndOtherTypesNeverJoin() {
        val entries = listOf(entry(1), entry(2))
        val links = mapOf(1 to listOf(link(7, "SEQUEL", "MANGA"), link(2, "ADAPTATION")))
        assertEquals(2, Franchises.group(entries, links, Franchises.WITH_SIDE_STORIES).size)
    }

    @Test fun combinedStatus() {
        assertEquals(ListStatus.COMPLETED, Franchises.combinedStatus(listOf(entry(1), entry(2))))
        assertEquals(ListStatus.CURRENT, Franchises.combinedStatus(listOf(entry(1), entry(2, ListStatus.CURRENT))))
        assertEquals(ListStatus.CURRENT, Franchises.combinedStatus(listOf(entry(1), entry(2, ListStatus.PLANNING))))
        assertEquals(ListStatus.PLANNING, Franchises.combinedStatus(listOf(entry(1, ListStatus.PLANNING))))
    }

    @Test fun walkFollowsRelationsInBatchesAndOrdersByStart() = runTest {
        fun node(id: Int, year: Int, vararg links: Pair<Int, String>, type: String = "ANIME") = FranchiseNode(
            id = id, type = type, startDate = FuzzyDate(year, 1, 1),
            relations = Edges(links.map { (to, rel) -> LinkEdge(rel, LinkNode(to, if (to == 99) "MANGA" else "ANIME")) }),
        )
        val all = mapOf(
            1 to node(1, 2015, 2 to "SEQUEL", 99 to "ADAPTATION"),
            2 to node(2, 2017, 1 to "PREQUEL", 3 to "SEQUEL", 4 to "SIDE_STORY"),
            3 to node(3, 2019, 2 to "PREQUEL"),
            4 to node(4, 2016, 2 to "PARENT"),
        )
        val asked = mutableListOf<List<Int>>()
        val walked = Franchises.walk(listOf(3), Franchises.SEASONS) { ids -> asked += ids; ids.mapNotNull { all[it] } }
        assertEquals(listOf(1, 2, 3), walked.map { it.id })
        assertEquals(listOf(listOf(3), listOf(2), listOf(1)), asked)
        val withSide = Franchises.walk(listOf(3), Franchises.WITH_SIDE_STORIES) { ids -> ids.mapNotNull { all[it] } }
        assertEquals(listOf(1, 4, 2, 3), withSide.map { it.id })
        assertTrue(withSide.none { it.id == 99 })
    }

    @Test fun describeTellsStoryFromExtras() {
        fun node(id: Int, format: String, source: String, vararg links: Pair<Int, String>) = FranchiseNode(
            id = id, type = "ANIME", format = format, source = source,
            relations = Edges(links.map { (to, rel) -> LinkEdge(rel, LinkNode(to, "ANIME")) }),
        )
        val nodes = listOf(
            node(1, "TV", "MANGA", 2 to "SEQUEL", 5 to "SIDE_STORY", 6 to "SUMMARY"),
            node(5, "OVA", "MANGA", 1 to "PARENT"),
            node(6, "SPECIAL", "MANGA", 1 to "PARENT"),
            node(2, "TV", "MANGA", 1 to "PREQUEL", 3 to "SEQUEL", 4 to "SIDE_STORY"),
            node(4, "MOVIE", "ORIGINAL", 2 to "PARENT"),
            node(3, "MOVIE", "MANGA", 2 to "PREQUEL"),
        )
        val roles = Franchises.describe(nodes).associate { it.node.id to it.role }
        assertEquals(FranchiseRole.Main, roles[1])
        assertEquals(FranchiseRole.Main, roles[2])
        assertEquals(FranchiseRole.Main, roles[3])
        assertEquals(FranchiseRole.SideStory, roles[5])
        assertEquals(FranchiseRole.Recap, roles[6])
        assertEquals(FranchiseRole.AnimeOnly, roles[4])
        assertEquals("MANGA", Franchises.storySource(nodes))
        // An original anime has no anime-only extras: they're all its own story.
        val original = nodes.map { it.copy(source = "ORIGINAL") }
        assertEquals(FranchiseRole.SideStory, Franchises.describe(original).first { it.node.id == 4 }.role)
    }

    private fun node(id: Int, format: String, source: String, vararg links: Pair<Int, String>) = FranchiseNode(
        id = id, type = "ANIME", format = format, source = source,
        relations = Edges(links.map { (to, rel) -> LinkEdge(rel, LinkNode(to, "ANIME")) }),
    )

    @Test fun storyOrderFollowsPrequelsNotRelease() {
        // Monogatari in small, in release order: Kizumonogatari came out last but happens first.
        val bake = node(1, "TV", "LIGHT_NOVEL", 3 to "PREQUEL", 2 to "SEQUEL", 5 to "SIDE_STORY")
        val nise = node(2, "TV", "LIGHT_NOVEL", 1 to "PREQUEL", 4 to "SEQUEL")
        val kuro = node(3, "TV", "LIGHT_NOVEL", 6 to "PREQUEL", 1 to "SEQUEL")
        val second = node(4, "TV", "LIGHT_NOVEL", 2 to "PREQUEL")
        val koyomi = node(5, "ONA", "LIGHT_NOVEL", 1 to "PARENT", 2 to "PARENT")
        val kizu = node(6, "MOVIE", "LIGHT_NOVEL", 3 to "SEQUEL")
        val story = Franchises.storyOrder(listOf(bake, nise, kuro, second, koyomi, kizu))
        // Koyomimonogatari goes after the last title it hangs off.
        assertEquals(listOf(6, 3, 1, 2, 5, 4), story.map { it.id })
    }

    @Test fun storyOrderFallsBackToReleaseWithoutLinksOrInACircle() {
        val a = node(1, "TV", "MANGA", 2 to "PREQUEL")
        val b = node(2, "TV", "MANGA", 1 to "PREQUEL")
        val lone = node(3, "MOVIE", "MANGA")
        assertEquals(listOf(1, 2, 3), Franchises.storyOrder(listOf(a, b, lone)).map { it.id })
        val plain = listOf(node(1, "TV", "MANGA", 2 to "SEQUEL"), node(2, "TV", "MANGA", 1 to "PREQUEL"))
        assertEquals(plain, Franchises.storyOrder(plain))
    }

    @Test fun canonGoesBySourceAndYourCorrections() {
        val nodes = listOf(
            node(1, "TV", "MANGA", 2 to "SEQUEL", 3 to "SIDE_STORY", 4 to "SUMMARY"),
            node(2, "TV", "MANGA", 1 to "PREQUEL"),
            node(3, "MOVIE", "ORIGINAL", 1 to "PARENT"),
            node(4, "SPECIAL", "MANGA", 1 to "PARENT"),
        )
        val parts = Franchises.describe(nodes).associateBy { it.node.id }
        assertEquals(true, parts.getValue(1).canon)
        assertEquals(false, parts.getValue(3).canon)
        assertEquals(FranchiseRole.AnimeOnly, parts.getValue(3).role)
        // A recap is neither: it retells.
        assertEquals(null, parts.getValue(4).canon)
        // The Last: AniList says original, you say canon.
        val corrected = Franchises.describe(nodes, mapOf(3 to true, 2 to false)).associateBy { it.node.id }
        assertEquals(true, corrected.getValue(3).canon)
        assertEquals(FranchiseRole.SideStory, corrected.getValue(3).role)
        assertTrue(corrected.getValue(3).corrected)
        assertEquals(FranchiseRole.AnimeOnly, corrected.getValue(2).role)
        // In an anime-original franchise everything is its own story.
        assertTrue(Franchises.describe(nodes.map { it.copy(source = "ORIGINAL") }).all { it.canon == null })
    }
}
