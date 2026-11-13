package com.aniliphy.ui

import com.aniliphy.anilist.FuzzyDate
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSortTest {
    private fun entry(
        id: Int,
        title: String,
        updated: Long = 0,
        start: FuzzyDate? = null,
        episodes: Int? = null,
        chapters: Int? = null,
        format: String? = null,
        score: Double = 0.0,
    ) = ListEntry(
        updatedAt = updated,
        score = score,
        media = Media(id = id, title = MediaTitle(romaji = title), startDate = start, episodes = episodes, chapters = chapters, format = format),
    )

    private fun ids(list: List<ListEntry>) = list.map { it.media.id }

    @Test
    fun `title goes alphabetically, ignoring case`() {
        val list = listOf(entry(1, "beta"), entry(2, "Alpha"), entry(3, "gamma"))
        assertEquals(listOf(2, 1, 3), ids(ListSort.apply(list, ListSort.Title, descending = false)))
        assertEquals(listOf(3, 1, 2), ids(ListSort.apply(list, ListSort.Title, descending = true)))
    }

    @Test
    fun `release date uses year, month and day, unknown last either way`() {
        val list = listOf(
            entry(1, "a", start = FuzzyDate(2019, 5, 1)),
            entry(2, "b"),
            entry(3, "c", start = FuzzyDate(2019, 4, 30)),
            entry(4, "d", start = FuzzyDate(2022)),
        )
        assertEquals(listOf(4, 1, 3, 2), ids(ListSort.apply(list, ListSort.Release, descending = true)))
        assertEquals(listOf(3, 1, 4, 2), ids(ListSort.apply(list, ListSort.Release, descending = false)))
    }

    @Test
    fun `length counts episodes or chapters, ties keep the most recent first`() {
        val list = listOf(
            entry(1, "a", updated = 1, episodes = 12),
            entry(2, "b", updated = 5, chapters = 12),
            entry(3, "c", episodes = 24),
            entry(4, "d", updated = 9),
        )
        assertEquals(listOf(3, 2, 1, 4), ids(ListSort.apply(list, ListSort.Length, descending = true)))
    }

    @Test
    fun `unscored titles go last`() {
        val list = listOf(entry(1, "a", score = 0.0), entry(2, "b", score = 7.5), entry(3, "c", score = 9.0))
        assertEquals(listOf(2, 3, 1), ids(ListSort.apply(list, ListSort.Score, descending = false)))
    }

    @Test
    fun `type groups by format`() {
        val list = listOf(entry(1, "a", format = "TV"), entry(2, "b", format = "MOVIE"), entry(3, "c", format = "TV"))
        val sorted = ids(ListSort.apply(list, ListSort.Format, descending = false))
        assertEquals(2, sorted.first())
    }
}
