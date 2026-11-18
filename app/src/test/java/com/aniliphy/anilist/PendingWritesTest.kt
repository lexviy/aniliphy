package com.aniliphy.anilist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy

class PendingWritesTest {
    @get:Rule val folder = TemporaryFolder()

    private val unused = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AniListApi::class.java)) { _, m, _ ->
        throw NotImplementedError(m.name)
    } as AniListApi

    /** Records what was sent; [offlineAfter] sends that many, then acts as if the network went away. */
    private inner class Fake(
        var remote: Map<Int, ListProgress> = emptyMap(),
        var liked: MutableMap<Int, Boolean> = mutableMapOf(),
        var offlineAfter: Int = Int.MAX_VALUE,
        var gone: Set<Int> = emptySet(),
    ) : AniListApi by unused {
        val calls = mutableListOf<String>()
        private fun call(name: String) {
            if (calls.size >= offlineAfter) throw OfflineException(java.io.IOException("down"))
            calls += name
        }
        override suspend fun progress(mediaId: Int): ListProgress {
            if (calls.size >= offlineAfter) throw OfflineException(java.io.IOException("down"))
            return remote[mediaId] ?: ListProgress()
        }
        override suspend fun saveEntry(
            mediaId: Int, status: String, progress: Int?, progressVolumes: Int?, score: Double?, repeat: Int?, notes: String?, customLists: List<String>?,
        ): SavedEntry {
            call("save $mediaId $status $progress")
            return SavedEntry(mediaId, status, progress ?: 0)
        }
        override suspend fun toggleLike(activityId: Int): Liked {
            if (activityId in gone) throw NotFoundException()
            call("like $activityId")
            val now = !(liked[activityId] ?: false)
            liked[activityId] = now
            return Liked(now, 0)
        }
        override suspend fun reply(activityId: Int, text: String) = call("reply $activityId $text")
        override suspend fun comment(threadId: Int, text: String, parentId: Int?) = call("comment $threadId $text $parentId")
    }

    private fun store() = PendingWrites(File(folder.root, "p.json"))

    @Test
    fun sendsInOrderAndANewerEditReplacesTheOlder() = runBlocking {
        val store = store()
        store.add(PendingWrite.Entry(1, "A", true, "CURRENT", progress = 3, at = 1))
        store.add(PendingWrite.Reply(9, "hi", "someone", at = 2))
        store.add(PendingWrite.Entry(1, "A", true, "COMPLETED", progress = 12, at = 3))
        val api = Fake()
        val result = store.flush(api)
        assertEquals(listOf("reply 9 hi", "save 1 COMPLETED 12"), api.calls)
        assertEquals(2, result.sent.size)
        assertEquals(0, result.left)
        assertFalse(File(folder.root, "p.json").exists())
    }

    @Test
    fun stopsWhenOfflineAndKeepsTheRest() = runBlocking {
        val store = store()
        store.add(PendingWrite.Reply(1, "a", "x", at = 1))
        store.add(PendingWrite.Reply(2, "b", "x", at = 2))
        store.add(PendingWrite.Reply(3, "c", "x", at = 3))
        val result = store.flush(Fake(offlineAfter = 1))
        assertEquals(1, result.sent.size)
        assertEquals(listOf("b", "c"), store.all().map { (it as PendingWrite.Reply).text })
    }

    @Test
    fun aLikeEndsTheWayItWasMeant() = runBlocking {
        val store = store()
        store.add(PendingWrite.Like(5, liked = true, about = "x"))
        store.add(PendingWrite.Like(6, liked = true, about = "x"))
        // 6 was already liked (from another device): the first toggle unlikes it, so it toggles back.
        val api = Fake(liked = mutableMapOf(6 to true))
        store.flush(api)
        assertEquals(true, api.liked[5])
        assertEquals(true, api.liked[6])
        assertEquals(listOf("like 5", "like 6", "like 6"), api.calls)
    }

    @Test
    fun aWriteAniListRefusesIsDroppedAndTheRestGoes() = runBlocking {
        val store = store()
        store.add(PendingWrite.Like(5, liked = true, about = "x", at = 1))
        store.add(PendingWrite.Reply(7, "ok", "x", at = 2))
        val result = store.flush(Fake(gone = setOf(5)))
        assertEquals(1, result.dropped.size)
        assertEquals(1, result.sent.size)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun changedOnAniListMeanwhileIsAConflict() = runBlocking {
        val store = store()
        val before = ListProgress(entryId = 1, progress = 3, status = "CURRENT", updatedAt = 100)
        store.add(PendingWrite.Entry(1, "A", true, "CURRENT", progress = 5, seenUpdatedAt = 100, before = before))
        // Another edit of the same entry keeps the first view of AniList.
        store.add(PendingWrite.Entry(1, "A", true, "CURRENT", progress = 6, seenUpdatedAt = 150))
        val api = Fake(remote = mapOf(1 to ListProgress(entryId = 1, progress = 8, status = "CURRENT", updatedAt = 120)))
        val result = store.flush(api)
        assertTrue(api.calls.isEmpty())
        assertEquals(1, result.conflicts.size)
        assertEquals(100L, store.conflicts().single().mine.seenUpdatedAt)
        assertEquals(6, store.conflicts().single().mine.progress)

        store.settle(store.conflicts().single(), keepMine = true)
        assertTrue(store.conflicts().isEmpty())
        store.flush(api)
        assertEquals(listOf("save 1 CURRENT 6"), api.calls)
    }

    @Test
    fun keepingAniListsDropsMine() = runBlocking {
        val store = store()
        store.add(PendingWrite.Entry(1, "A", true, "DROPPED", seenUpdatedAt = 100))
        val api = Fake(remote = mapOf(1 to ListProgress(status = "COMPLETED", updatedAt = 200)))
        store.flush(api)
        store.settle(store.conflicts().single(), keepMine = false)
        assertTrue(store.all().isEmpty() && store.conflicts().isEmpty())
    }

    @Test
    fun theSameChangeOnBothSidesIsNoConflict() {
        val mine = PendingWrite.Entry(1, "A", true, "COMPLETED", progress = 12, seenUpdatedAt = 100)
        assertFalse(PendingWrites.isConflict(mine, ListProgress(status = "COMPLETED", progress = 12, updatedAt = 300)))
        assertTrue(PendingWrites.isConflict(mine, ListProgress(status = "CURRENT", progress = 11, updatedAt = 300)))
        assertFalse(PendingWrites.isConflict(mine, ListProgress(status = "CURRENT", progress = 11, updatedAt = 100)))
        assertFalse(PendingWrites.isConflict(mine.copy(seenUpdatedAt = null), ListProgress(status = "CURRENT", updatedAt = 300)))
    }
}
