package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiscoverFeedTest {
    @get:Rule val folder = TemporaryFolder()

    private fun s(id: Int, tag: String? = null, studio: String? = null) = Suggestion(
        DiscoverMedia(id = id, tags = listOfNotNull(tag?.let { MediaTag(it, rank = 80) }), studios = Nodes(listOfNotNull(studio?.let { Named(1, it) }))),
        80,
        emptyList(),
    )

    @Test
    fun filteredDropsWhatWasTurnedDown() {
        val list = listOf(s(1), s(2, tag = "Isekai"), s(3, studio = "Mappa"), s(4))
        val learned = DiscoverLearned(hidden = setOf(4), lessTags = setOf("Isekai"), lessStudios = setOf("Mappa"))
        assertEquals(listOf(1), DiscoverBuilder.filtered(list, learned).map { it.media.id })
    }

    @Test
    fun keptForADayAndPerAccount() {
        val file = DiscoverFile(File(folder.root, "d.json"))
        file.write(DiscoverSaved(userId = 7, anime = listOf(s(1)), animeAt = 1_000))
        val saved = file.read()
        assertEquals(1, saved.anime.single().media.id)
        assertNull(fresh(saved, 8, 2_000))
        assertEquals(1, fresh(saved, 7, 2_000)?.size)
        assertNull(fresh(saved, 7, 1_000 + DiscoverBuilder.DAY_MS))
        file.update { it.copy(learned = DiscoverLearned(hidden = setOf(9))) }
        assertEquals(setOf(9), file.read().learned.hidden)
        assertEquals(1, file.read().anime.size)
    }

    private fun fresh(saved: DiscoverSaved, userId: Int, now: Long) = DiscoverBuilder.fresh(saved, userId, MediaKind.Anime, now)
}
