package com.aniliphy.novels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingSessionTest {
    @get:Rule val folder = TemporaryFolder()

    private val store get() = NovelProgressStore(File(folder.root, "novel_progress.json"))
    private val titles = listOf("Cover", "Prologue", "Chapter 1", "Chapter 2", "Afterword")
    private val volumes = listOf("v1.epub", "v2.epub")

    private fun session(index: Int = 0, manual: Boolean = false, entry: VolumeEntry = VolumeEntry.Resume) =
        ReadingSession(store, "42", "Novel", volumes, index, titles, listOf(10, 10, 40, 30, 10), manual, entry)

    @Test fun firstOpenDetectsChaptersAndStartsAtThePrologue() {
        val session = session()
        assertEquals(2, session.detected)
        assertTrue(session.auto)
        assertEquals(1, session.chapter)
        assertEquals(0, session.aniListChapter())
    }

    @Test fun manualCountingFindsNothingByItself() {
        val session = session(manual = true)
        assertNull(session.detected)
        assertEquals(emptyList<Segment>(), session.segments)
    }

    @Test fun resumesWhereItWasLeft() {
        session().apply {
            moveTo(3, 0.5)
            persist()
        }
        val again = session()
        assertEquals(3, again.chapter)
        assertEquals(0.5, again.position, 0.0)
        assertNull(again.detected)
        assertEquals(2, again.aniListChapter())
        assertEquals("v1.epub", store.lastRead("42")?.book)
        assertEquals("Novel", store.lastRead("42")?.title)
    }

    @Test fun laterVolumesAddTheEarlierCount() {
        session().apply {
            moveTo(3)
            persist()
        }
        val second = session(index = 1, entry = VolumeEntry.Start)
        assertEquals(2, second.base)
        second.moveTo(2)
        assertEquals(3, second.aniListChapter())
    }

    @Test fun enteringFromTheNextVolumeOpensTheEnd() {
        assertEquals(4, session(entry = VolumeEntry.End).chapter)
    }

    @Test fun progressThroughTheVolumeFollowsChapterLength() {
        val session = session()
        session.moveTo(2, 0.5)
        assertEquals(0.4, session.volumeFraction(), 0.0001)
    }

    @Test fun countingByHandReplacesTheAutomaticCount() {
        val session = session()
        session.moveTo(1)
        assertTrue("Automatic count off" in session.toggleCounting())
        assertEquals(listOf(Segment(1, null)), session.segments)
        assertTrue(session.counting)
        session.moveTo(3)
        assertEquals(3, session.aniListChapter())
        assertTrue("Paused. 3 chapters" in session.toggleCounting())
        assertEquals(listOf(Segment(1, 3)), session.segments)
        assertFalse(session.counting)
        session.moveTo(2)
        assertEquals("This chapter is already counted", session.toggleCounting())
        assertEquals(listOf(Segment(1, 3)), session.segments)
    }

    @Test fun stoppingBeforeTheStartIsRefused() {
        val session = session()
        session.moveTo(3)
        session.toggleCounting()
        session.moveTo(1)
        assertEquals("Go to a chapter after the start", session.toggleCounting())
        assertTrue(session.counting)
    }

    @Test fun resetGoesBackToTheDetectedCount() {
        val session = session()
        session.moveTo(1)
        session.toggleCounting()
        assertTrue("2 chapters" in session.resetCounting())
        assertEquals(listOf(Segment(2, 3)), session.segments)
        assertTrue(session.auto)
    }

    @Test fun readsTheDesktopAppsFile() {
        File(folder.root, "novel_progress.json").writeText(
            """{"117853":{"last":"T.pdf","books":{"T.pdf":{"chapter":1,"segments":[[5,5],[7,10]],"length":15,"auto":true}}},"bad":3}""",
        )
        val state = store.state("117853", "T.pdf")
        assertEquals(listOf(Segment(5, 5), Segment(7, 10)), state.segments)
        assertEquals(15, state.length)
        assertEquals(2, store.lastRead("117853")?.chapter)
        assertEquals(5, store.baseFor("117853", listOf("T.pdf", "U.pdf"), 1))
    }

    @Test fun aniListOnlyHearsOfFinishedChapters() {
        val session = session()
        assertEquals(0, session.chaptersRead())
        session.moveTo(2)
        assertEquals(0, session.chaptersRead())
        session.setPosition(0.5)
        assertEquals(0, session.chaptersRead())
        session.setPosition(1.0)
        assertEquals(1, session.chaptersRead())
        session.moveTo(3)
        assertEquals(1, session.chaptersRead())
        session.setPosition(1.0)
        assertEquals(2, session.chaptersRead())
    }

    @Test fun whatComesAfterTheChaptersAddsNothing() {
        val session = session()
        session.moveTo(4, 1.0)
        assertEquals(2, session.chaptersRead())
    }

    @Test fun theFrontMatterAddsNothing() {
        val session = session()
        session.moveTo(1, 1.0)
        assertEquals(0, session.chaptersRead())
    }

    @Test fun finishedChaptersIncludeEarlierVolumes() {
        session().apply {
            moveTo(3)
            persist()
        }
        val second = session(index = 1, entry = VolumeEntry.Start)
        assertEquals(2, second.chaptersRead())
        second.moveTo(2, 1.0)
        assertEquals(3, second.chaptersRead())
    }

    private val spreadTitles = listOf("Cover", "Chapter 1", "Chapter 2", "Chapter 2", "Chapter 2", "Chapter 3", "Afterword")

    private fun spread(index: Int = 0, entry: VolumeEntry = VolumeEntry.Resume, manual: Boolean = false) = ReadingSession(
        store, "s", "Spread", volumes, index, spreadTitles, spreadTitles.map { 10L }, manual, entry, starts = listOf(0, 1, 2, 5, 6),
    )

    @Test fun aSpreadChapterCountsOnce() {
        val session = spread()
        assertEquals(3, session.detected)
        session.moveTo(3)
        assertEquals(2, session.aniListChapter())
        assertEquals("Chapter 2 of 3", session.label())
    }

    @Test fun aSpreadChapterIsOnlyFinishedAtItsLastSection() {
        val session = spread()
        session.moveTo(2, 1.0)
        assertEquals(1, session.chaptersRead())
        session.moveTo(3, 1.0)
        assertEquals(1, session.chaptersRead())
        session.moveTo(4, 1.0)
        assertEquals(2, session.chaptersRead())
        session.moveTo(5)
        assertEquals(2, session.chaptersRead())
        session.setPosition(1.0)
        assertEquals(3, session.chaptersRead())
        assertTrue(session.finishedVolume())
    }

    @Test fun spreadChaptersAddUpAcrossVolumes() {
        spread().apply {
            moveTo(5)
            persist()
        }
        assertEquals(3, spread(index = 1, entry = VolumeEntry.Start).base)
    }

    @Test fun nextAndPreviousSkipTheSectionsInsideAChapter() {
        val session = spread()
        session.moveTo(3)
        assertEquals(5, session.nextChapter())
        assertEquals(1, session.previousChapter())
        assertEquals(2, session.chapterStart())
        assertEquals(listOf(0, 1, 2, 5, 6), session.listed)
    }

    @Test fun countingByHandInASpreadChapterStartsAtItsBeginning() {
        val session = spread(manual = true)
        session.moveTo(3)
        session.toggleCounting()
        assertEquals(listOf(Segment(2, null)), session.segments)
        assertEquals(1, session.aniListChapter())
    }

    @Test fun anAutomaticCountFollowsTheCurrentRules() {
        store.save("s", "v1.epub", BookState(chapter = 3, segments = listOf(Segment(1, 2), Segment(3, 4)), length = spreadTitles.size, auto = true))
        assertEquals(listOf(Segment(1, 5)), spread().segments)
    }

    @Test fun aHandMadeCountIsKept() {
        store.save("s", "v1.epub", BookState(chapter = 3, segments = listOf(Segment(1, 2)), length = spreadTitles.size, auto = false))
        assertEquals(listOf(Segment(1, 2)), spread().segments)
    }

    @Test fun aVolumeOnlyCountedOpensAtItsFirstChapter() {
        store.save("s", "v1.epub", BookState(segments = listOf(Segment(1, 5)), length = spreadTitles.size, auto = true))
        assertEquals(1, spread().chapter)
    }

    @Test fun finishingOnlyPartOfTheBookIsNotFinishingIt() {
        val session = spread()
        session.moveTo(2, 1.0)
        assertFalse(session.finishedVolume())
    }

    @Test fun progressIsImportedInTheDesktopLayout() {
        val desktop = """{"117853":{"last":"a.pdf","updated":10.0,"books":{"a.pdf":{"chapter":3,"position":null,"segments":[[5,5],[7,10]],"length":15,"auto":true}}}}"""
        assertEquals(ImportResult(1, 0), store.import(desktop))
        val state = store.state("117853", "a.pdf")
        assertEquals(3, state.chapter)
        assertEquals(0.0, state.position, 0.0)
        assertEquals(listOf(Segment(5, 5), Segment(7, 10)), state.segments)
        assertEquals("a.pdf", store.lastRead("117853")?.book)
    }

    @Test fun importingKeepsWhatWasReadHereMoreRecently() {
        store.save("1", "a.epub", BookState(chapter = 9, length = 20), now = 100.0)
        val older = """{"1":{"last":"a.epub","updated":50.0,"books":{"a.epub":{"chapter":2,"length":20}}}}"""
        assertEquals(ImportResult(0, 1), store.import(older))
        assertEquals(9, store.state("1", "a.epub").chapter)
        val newer = """{"1":{"last":"a.epub","updated":500.0,"books":{"a.epub":{"chapter":12,"length":20}}}}"""
        assertEquals(ImportResult(1, 0), store.import(newer))
        assertEquals(12, store.state("1", "a.epub").chapter)
    }

    @Test fun importingMovesBooksToTheSeriesTheyBelongToHere() {
        val desktop = """{"custom:x":{"last":"a.epub","updated":1.0,"books":{"a.epub":{"chapter":2,"length":20}}}}"""
        store.import(desktop, seriesFor = { _, _ -> "local:x" })
        assertEquals(2, store.state("local:x", "a.epub").chapter)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aDifferentFileIsNotImported() {
        store.import("""{"theme":"dark"}""")
    }

    @Test fun movingASeriesKeepsItsProgress() {
        store.save("local:x", "a.epub", BookState(chapter = 4, length = 20), now = 5.0)
        store.move("local:x", "77")
        assertEquals(4, store.state("77", "a.epub").chapter)
        assertEquals(BookState(), store.state("local:x", "a.epub"))
    }

    @Test fun aChapterShareFollowsItsOwnSections() {
        val session = spread()
        session.moveTo(2, 0.5)
        assertEquals(1.0 / 6, session.chapterFraction(), 0.0001)
        session.moveTo(3, 0.5)
        assertEquals(0.5, session.chapterFraction(), 0.0001)
        session.moveTo(4, 1.0)
        assertEquals(1.0, session.chapterFraction(), 0.0001)
    }

    @Test fun aSingleSectionChapterShareIsItsPosition() {
        val session = session()
        session.moveTo(2, 0.25)
        assertEquals(0.25, session.chapterFraction(), 0.0001)
    }

    @Test fun aBrokenFileStartsClean() {
        File(folder.root, "novel_progress.json").writeText("{nope")
        assertEquals(BookState(), store.state("1", "a"))
    }
}
