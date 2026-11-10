package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressRulesTest {
    private fun at(status: String?, progress: Int = 0, volumes: Int = 0, repeat: Int = 0, chapters: Int? = null) =
        ListProgress(progress = progress, progressVolumes = volumes, status = status, repeat = repeat, chapters = chapters)

    @Test fun startLeavesActiveEntriesAlone() {
        assertNull(ProgressRules.start(at(ListStatus.CURRENT, 5)))
        assertNull(ProgressRules.start(at(ListStatus.REPEATING, 5)))
    }

    @Test fun startingAFinishedTitleBeginsARewatch() {
        assertEquals(
            ProgressChange(progress = 0, status = ListStatus.REPEATING),
            ProgressRules.start(at(ListStatus.COMPLETED, 12)),
        )
    }

    @Test fun startingAnythingElseMakesItCurrent() {
        for (status in listOf(null, ListStatus.PLANNING, ListStatus.DROPPED, ListStatus.PAUSED)) {
            assertEquals(ProgressChange(progress = 0, status = ListStatus.CURRENT), ProgressRules.start(at(status, 3)))
        }
    }

    @Test fun startResetsVolumesOnlyWhenThereAreSome() {
        assertEquals(0, ProgressRules.start(at(ListStatus.PAUSED, 3, volumes = 2))?.progressVolumes)
        assertNull(ProgressRules.start(at(ListStatus.PAUSED, 3))?.progressVolumes)
    }

    @Test fun episodeNeverLowersProgress() {
        assertNull(ProgressRules.episode(at(ListStatus.CURRENT, 5), 5, 12))
        assertNull(ProgressRules.episode(at(ListStatus.CURRENT, 5), 3, 12))
    }

    @Test fun episodeAdvances() {
        assertEquals(
            ProgressChange(progress = 6, status = ListStatus.CURRENT),
            ProgressRules.episode(at(ListStatus.PLANNING, 5), 6, 12),
        )
    }

    @Test fun lastEpisodeCompletes() {
        assertEquals(
            ProgressChange(progress = 12, status = ListStatus.COMPLETED),
            ProgressRules.episode(at(ListStatus.CURRENT, 11), 12, 12),
        )
    }

    @Test fun finishingARewatchCountsIt() {
        assertEquals(
            ProgressChange(progress = 12, status = ListStatus.COMPLETED, repeat = 3),
            ProgressRules.episode(at(ListStatus.REPEATING, 11, repeat = 2), 12, 12),
        )
    }

    @Test fun midRewatchStaysRewatching() {
        assertEquals(
            ProgressChange(progress = 6, status = ListStatus.REPEATING),
            ProgressRules.episode(at(ListStatus.REPEATING, 5, repeat = 2), 6, 12),
        )
    }

    @Test fun unknownTotalNeverCompletes() {
        assertEquals(ListStatus.CURRENT, ProgressRules.episode(at(ListStatus.CURRENT, 99), 100, null)?.status)
        assertEquals(ListStatus.CURRENT, ProgressRules.episode(at(ListStatus.CURRENT, 99), 100, 0)?.status)
    }

    @Test fun chapterNeverLowersEitherNumber() {
        val change = ProgressRules.chapter(at(ListStatus.CURRENT, 10, volumes = 3), chapter = 4, volume = 5)
        assertEquals(ProgressChange(progress = 10, progressVolumes = 5, status = ListStatus.CURRENT), change)
    }

    @Test fun chapterWithNothingNewSavesNothing() {
        for (status in listOf(ListStatus.CURRENT, ListStatus.REPEATING, ListStatus.COMPLETED)) {
            assertNull(ProgressRules.chapter(at(status, 10, volumes = 3), chapter = 10, volume = 3))
        }
    }

    @Test fun rereadingAnOldChapterBringsAPausedTitleBack() {
        assertEquals(
            ProgressChange(progress = 10, status = ListStatus.CURRENT),
            ProgressRules.chapter(at(ListStatus.PAUSED, 10), chapter = 4, volume = null),
        )
    }

    @Test fun lastChapterCompletes() {
        assertEquals(
            ProgressChange(progress = 50, status = ListStatus.COMPLETED),
            ProgressRules.chapter(at(ListStatus.CURRENT, 49, chapters = 50), chapter = 50, volume = null),
        )
    }

    @Test fun finishingARereadCountsIt() {
        assertEquals(
            ProgressChange(progress = 50, status = ListStatus.COMPLETED, repeat = 1),
            ProgressRules.chapter(at(ListStatus.REPEATING, 49, chapters = 50), chapter = 50, volume = null),
        )
    }

    @Test fun noVolumesMeansTheFieldIsLeftOut() {
        assertNull(ProgressRules.chapter(at(ListStatus.CURRENT, 1), chapter = 2, volume = null)?.progressVolumes)
    }

    @Test fun completionIsOfferedWhenTheCountCanNotDoItAlone() {
        val noTotal = ListProgress(progress = 3, status = ListStatus.CURRENT)
        assertTrue(ProgressRules.canOfferCompletion(noTotal, 3, 1, "FINISHED"))
        val shortCount = ListProgress(progress = 6, status = ListStatus.CURRENT, chapters = 8, volumes = 1)
        assertTrue(ProgressRules.canOfferCompletion(shortCount, 6, 1, "FINISHED"))
    }

    @Test fun completionIsNotOfferedWhenMoreIsKnownToRemain() {
        val current = ListProgress(progress = 3, status = ListStatus.CURRENT)
        assertFalse(ProgressRules.canOfferCompletion(current, 3, 1, "RELEASING"))
        assertFalse(ProgressRules.canOfferCompletion(current.copy(volumes = 14), 3, 2, "FINISHED"))
        assertFalse(ProgressRules.canOfferCompletion(current.copy(chapters = 3), 3, 1, "FINISHED"))
        assertFalse(ProgressRules.canOfferCompletion(current.copy(status = ListStatus.COMPLETED), 3, 1, null))
        assertFalse(ProgressRules.canOfferCompletion(current, 0, 1, null))
    }

    @Test fun completingUsesAniListsTotals() {
        val change = ProgressRules.complete(ListProgress(progress = 5, status = ListStatus.REPEATING, repeat = 1, chapters = 8, volumes = 2), 6)
        assertEquals(8, change.progress)
        assertEquals(2, change.progressVolumes)
        assertEquals(ListStatus.COMPLETED, change.status)
        assertEquals(2, change.repeat)
    }

    @Test fun completingWithNoTotalsKeepsTheCount() {
        val change = ProgressRules.complete(ListProgress(progress = 2, status = ListStatus.CURRENT), 6)
        assertEquals(6, change.progress)
        assertEquals(null, change.repeat)
    }
}
