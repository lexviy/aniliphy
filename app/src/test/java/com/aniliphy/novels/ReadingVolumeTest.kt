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
}
