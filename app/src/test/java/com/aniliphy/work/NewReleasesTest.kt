package com.aniliphy.work

import com.aniliphy.manga.MangaRecord
import com.aniliphy.manga.NewChapterCheck
import com.aniliphy.manga.NewChapters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NewReleasesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun eachReleaseIsToldOnce() {
        val notified = NotifiedReleases(File(folder.root, "told.json"))
        assertEquals(mapOf(1 to 10.0, 2 to 5.0), notified.firstTime(NotifiedReleases.Kind.Chapter, mapOf(1 to 10.0, 2 to 5.0)))
        assertEquals(emptyMap<Int, Double>(), notified.firstTime(NotifiedReleases.Kind.Chapter, mapOf(1 to 10.0)))
        assertEquals(mapOf(1 to 11.0), notified.firstTime(NotifiedReleases.Kind.Chapter, mapOf(1 to 11.0, 2 to 5.0)))
        assertEquals(mapOf(1 to 3.0), notified.firstTime(NotifiedReleases.Kind.Episode, mapOf(1 to 3.0)))
        assertEquals(emptyMap<Int, Double>(), NotifiedReleases(File(folder.root, "told.json")).firstTime(NotifiedReleases.Kind.Chapter, mapOf(1 to 11.0)))
    }

    @Test
    fun freshChaptersArePastTheSeenOnesAndThePlace() {
        val newChapters = NewChapters(File(folder.root, "new.json"))
        newChapters.checked(1, "first:a", 140.0)
        newChapters.checked(1, "first:a", 142.0)
        newChapters.checked(2, "first:b", 50.0)
        newChapters.checked(2, "first:b", 51.0)
        newChapters.checked(3, "first:c", 9.0)
        val records = mapOf(
            1 to MangaRecord(source = "first:a", chapterNumber = 139.0, title = "A"),
            2 to MangaRecord(source = "first:b", chapterNumber = 51.0, title = "B"),
            3 to MangaRecord(source = "first:c", chapterNumber = 2.0, title = "C"),
        )
        assertEquals(mapOf(1 to 142.0), NewChapterCheck.fresh(records, newChapters))
    }

    @Test
    fun numbersReadLikeTheDesktop() {
        assertEquals("12", NewReleasesWorker.number(12.0))
        assertEquals("12.5", NewReleasesWorker.number(12.5))
    }
}
