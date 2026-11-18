package com.aniliphy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadQueueStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun item(key: String, anime: Boolean = true) = QueuedDownload(anime, key, "{}", "{\"id\":\"$key\"}", "Sub")

    @Test
    fun keepsOrderAndSkipsRepeats() {
        val file = File(folder.root, "q.json")
        val store = DownloadQueueStore(file)
        store.add(listOf(item("1/a"), item("1/b")))
        store.add(listOf(item("1/b"), item("2/c", anime = false)))
        assertEquals(listOf("1/a", "1/b", "2/c"), store.all().map { it.key })
        // A new store on the same file reads the same queue, as after the app was closed.
        assertEquals(3, DownloadQueueStore(file).all().size)
    }

    @Test
    fun removingTheLastDeletesTheFile() {
        val file = File(folder.root, "q.json")
        val store = DownloadQueueStore(file)
        store.add(listOf(item("1/a")))
        store.remove("1/a")
        assertTrue(store.all().isEmpty())
        assertTrue(!file.exists())
    }

    @Test
    fun brokenFileIsAnEmptyQueue() {
        val file = File(folder.root, "q.json").apply { writeText("{not json") }
        assertTrue(DownloadQueueStore(file).all().isEmpty())
    }
}
