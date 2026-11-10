package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EntryFormTest {
    private val form = EntryForm(ListStatus.CURRENT, "5", "0", "", "0")

    @Test fun startsFromTheCurrentEntry() {
        val from = EntryForm.from(ListProgress(progress = 3, progressVolumes = 1, status = ListStatus.PAUSED, score = 8.5, repeat = 2))
        assertEquals(EntryForm(ListStatus.PAUSED, "3", "1", "8.5", "2"), from)
    }

    @Test fun titleNotOnTheListStartsAsPlanning() {
        val from = EntryForm.from(ListProgress())
        assertEquals(ListStatus.PLANNING, from.status)
        assertEquals("", from.score)
    }

    @Test fun totalsCapWhatCanBeEntered() {
        val values = form.copy(progress = "99", volumes = "9").values(12, 3, true, 0)
        assertEquals(12, values.progress)
        assertEquals(3, values.volumes)
    }

    @Test fun unknownTotalsDoNotCap() {
        assertEquals(99, form.copy(progress = "99").values(null, null, false, 0).progress)
        assertEquals(99, form.copy(progress = "99").values(0, null, false, 0).progress)
    }

    @Test fun unreadableTextCountsAsZero() {
        val values = form.copy(progress = "abc", repeat = "-4", score = "x").values(12, null, false, 0)
        assertEquals(0, values.progress)
        assertEquals(0, values.repeat)
        assertEquals(0.0, values.score, 0.0)
    }

    @Test fun scoreAcceptsACommaAndIsKeptInRange() {
        assertEquals(8.5, form.copy(score = "8,5").values(null, null, false, 0).score, 0.0)
        assertEquals(10.0, form.copy(score = "42").values(null, null, false, 0).score, 0.0)
        assertEquals(7.3, form.copy(score = "7.26").values(null, null, false, 0).score, 0.0)
    }

    @Test fun volumesAreLeftOutWhenTheyDoNotApply() {
        assertNull(form.copy(volumes = "3").values(null, null, false, 0).volumes)
        assertNull(form.copy(volumes = "0").values(null, null, true, 0).volumes)
    }

    @Test fun volumesCanBeClearedOnceTheyExist() {
        assertEquals(0, form.copy(volumes = "0").values(null, null, true, 2).volumes)
    }

    @Test fun pickingCompletedFillsInKnownTotals() {
        val done = form.withStatus(ListStatus.COMPLETED, 12, 3)
        assertEquals("12", done.progress)
        assertEquals("3", done.volumes)
    }

    @Test fun pickingCompletedKeepsTypedNumbersWhenTotalsAreUnknown() {
        assertEquals("5", form.withStatus(ListStatus.COMPLETED, null, null).progress)
    }

    @Test fun otherStatusesLeaveTheNumbersAlone() {
        assertEquals("5", form.withStatus(ListStatus.DROPPED, 12, 3).progress)
    }
}
