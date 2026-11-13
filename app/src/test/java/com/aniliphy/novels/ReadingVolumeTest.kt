package com.aniliphy.novels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ReadingVolumeTest {
    private val novel = NovelSeries(
        key = "local:tbate",
        title = "The Beginning After The End",
        volumes = listOf(VolumeFile("content://a/11", "Vol 11.epub"), VolumeFile("content://a/12", "Vol 12.epub")),
    )

    private fun store() = NovelProgressStore(File.createTempFile("progress", ".json").apply { delete(); deleteOnExit() })

    @Test
    fun theCoverFollowsTheVolumeBeingRead() {
        val progress = store()
        assertEquals("Vol 11.epub", progress.readingVolume(novel)?.name)
        progress.save(novel.key, "Vol 11.epub", BookState(chapter = 30))
        progress.save(novel.key, "Vol 12.epub", BookState(chapter = 2))
        assertEquals("Vol 12.epub", progress.readingVolume(novel)?.name)
    }

    @Test
    fun noVolumesNoCover() {
        assertNull(store().readingVolume(novel.copy(volumes = emptyList())))
    }

    @Test
    fun anImportedSeriesWithoutALastBookStillShowsWhereYouAre() {
        val progress = store()
        // The other side read Book 11 last; Book 12 went to a series of its own, which then has no "last".
        val text = """{"tbate":{"last":"Book 11.epub","updated":5.0,"books":{
            "Book 11.epub":{"chapter":3,"length":20},
            "Book 12.epub":{"chapter":16,"length":61}}}}"""
        progress.import(text, seriesFor = { _, book -> if ("12" in book) "custom:apotheosis" else "local:providence" })
        assertEquals("Book 12.epub", progress.lastRead("custom:apotheosis")?.book)
        assertEquals(17, progress.lastRead("custom:apotheosis")?.chapter)
        assertEquals("Book 11.epub", progress.lastRead("local:providence")?.book)
    }
}
