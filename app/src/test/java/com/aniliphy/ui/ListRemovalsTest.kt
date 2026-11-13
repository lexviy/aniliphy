package com.aniliphy.ui

import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.ServerException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListRemovalsTest {
    private class Spy {
        val deleted = mutableListOf<Int>()
        var removedCalls = 0
        var fail = false
    }

    private fun TestScope.removals(spy: Spy) = ListRemovals(
        delete = { id ->
            if (spy.fail) throw ServerException(200, "nope")
            spy.deleted += id
        },
        scope = backgroundScope,
        describe = { e: AniListException -> e.message ?: "error" },
        onRemoved = { spy.removedCalls++ },
    )

    @Test fun undoingSendsNothing() = runTest {
        val spy = Spy()
        val removals = removals(spy)
        removals.remove(entryId = 5, mediaId = 50, title = "Frieren")
        assertTrue(50 in removals.hidden)
        advanceTimeBy(ListRemovals.UNDO_MILLIS - 1)
        removals.undo()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(spy.deleted.isEmpty())
        assertTrue(removals.hidden.isEmpty())
        assertNull(removals.pending)
        assertEquals(0, spy.removedCalls)
    }

    @Test fun theRemovalIsSentWhenTheUndoTimeRunsOut() = runTest {
        val spy = Spy()
        val removals = removals(spy)
        removals.remove(entryId = 5, mediaId = 50, title = "Frieren")
        advanceTimeBy(ListRemovals.UNDO_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(5), spy.deleted)
        assertNull(removals.pending)
        assertEquals(1, spy.removedCalls)
        assertTrue("still hidden while lists reload", 50 in removals.hidden)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(removals.hidden.isEmpty())
    }

    @Test fun aSecondRemovalSendsTheFirstAtOnce() = runTest {
        val spy = Spy()
        val removals = removals(spy)
        removals.remove(entryId = 5, mediaId = 50, title = "A")
        removals.remove(entryId = 6, mediaId = 60, title = "B")
        runCurrent()
        assertEquals(listOf(5), spy.deleted)
        assertEquals(60, removals.pending?.mediaId)
    }

    @Test fun savingTheTitleAgainKeepsIt() = runTest {
        val spy = Spy()
        val removals = removals(spy)
        removals.remove(entryId = 5, mediaId = 50, title = "A")
        removals.keep(50)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(spy.deleted.isEmpty())
    }

    @Test fun aFailureIsShownAndTheTitleComesBack() = runTest {
        val spy = Spy().apply { fail = true }
        val removals = removals(spy)
        removals.remove(entryId = 5, mediaId = 50, title = "Frieren")
        advanceTimeBy(60_000)
        runCurrent()
        assertNotNull(removals.failed)
        assertTrue(removals.hidden.isEmpty())
        assertEquals(0, spy.removedCalls)
    }
}
