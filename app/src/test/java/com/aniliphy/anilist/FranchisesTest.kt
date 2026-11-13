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
}
