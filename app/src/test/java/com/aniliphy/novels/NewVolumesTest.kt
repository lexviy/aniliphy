package com.aniliphy.novels

import org.junit.Assert.assertEquals
import org.junit.Test

class NewVolumesTest {
    private val start = NewVolumes.update(NewVolumes.State(), mapOf("tbate" to listOf("11.epub"), "cote" to listOf("1.epub")), setOf("tbate"))

    @Test
    fun theFirstLookIsNeverNew() {
        assertEquals(emptySet<String>(), start.fresh)
        assertEquals(listOf("11.epub"), start.seen["tbate"])
    }

    @Test
    fun aVolumeAddedToASeriesYoureReadingIsNew() {
        val later = NewVolumes.update(start, mapOf("tbate" to listOf("11.epub", "12.epub"), "cote" to listOf("1.epub", "2.epub")), setOf("tbate"))
        assertEquals(setOf("tbate"), later.fresh)
    }

    @Test
    fun openingTheSeriesClearsIt() {
        val later = NewVolumes.update(start, mapOf("tbate" to listOf("11.epub", "12.epub")), setOf("tbate"))
        val opened = NewVolumes.opened(later, "tbate", listOf("11.epub", "12.epub"))
        assertEquals(emptySet<String>(), opened.fresh)
        assertEquals(emptySet<String>(), NewVolumes.update(opened, mapOf("tbate" to listOf("11.epub", "12.epub")), setOf("tbate")).fresh)
    }

    @Test
    fun aSeriesGoneFromTheFolderIsNoLongerNew() {
        val later = NewVolumes.update(start, mapOf("tbate" to listOf("11.epub", "12.epub")), setOf("tbate"))
        assertEquals(emptySet<String>(), NewVolumes.update(later, mapOf("cote" to listOf("1.epub")), setOf("tbate")).fresh)
    }
}
