package com.aniliphy.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDifferencesTest {
    @Test
    fun aDifferentPlaceIsADifference() {
        val local = SyncPayload(manga = mapOf("1" to SyncedManga(title = "Wistoria", number = 69.0, page = 0, updated = 10.0)))
        val remote = SyncPayload(manga = mapOf("1" to SyncedManga(title = "Wistoria", number = 70.0, page = 4, updated = 20.0)))
        val found = SyncDifferences.find(local, remote, now = 5).single()
        assertEquals("Ch 69", found.phone)
        assertEquals("Ch 70 · p. 5", found.computer)
        assertTrue(found.keptComputer)
        // What wasn't kept is this phone's place, to go back to.
        assertEquals(69.0, SyncPayload.json.decodeFromString<SyncedManga>(found.other).number)
    }

    @Test
    fun theSamePlaceSavedAtDifferentTimesIsNot() {
        val local = SyncPayload(anime = mapOf("2" to SyncedAnime(title = "Haruhi", episode = 5.0, position = 154.0, updated = 1.0)))
        val remote = SyncPayload(anime = mapOf("2" to SyncedAnime(title = "Haruhi", episode = 5.0, position = 154.0, updated = 9.0)))
        assertEquals(emptyList<SyncDifference>(), SyncDifferences.find(local, remote, now = 0))
    }

    @Test
    fun titlesOnOneSideOnlyAreNot() {
        val local = SyncPayload(anime = mapOf("2" to SyncedAnime(title = "Only here", episode = 1.0)))
        assertEquals(emptyList<SyncDifference>(), SyncDifferences.find(local, SyncPayload(), now = 0))
    }

    @Test
    fun lightNovelsShowTheBookAndChapter() {
        val novel = { book: String, chapter: Int, updated: Double ->
            Json.parseToJsonElement("""{"last":"$book","updated":$updated,"title":"TBATE","books":{"$book":{"chapter":$chapter}}}""").jsonObject
        }
        val local = SyncPayload(novels = mapOf("k" to novel("Vol 12.epub", 3, 50.0)))
        val remote = SyncPayload(novels = mapOf("k" to novel("Vol 11.epub", 30, 40.0)))
        val found = SyncDifferences.find(local, remote, now = 0).single()
        assertEquals("Vol 12 · chapter 4", found.phone)
        assertEquals("Vol 11 · chapter 31", found.computer)
        assertFalse(found.keptComputer)
        assertEquals("Vol 11.epub", (Json.parseToJsonElement(found.other) as JsonObject)["last"].toString().trim('"'))
    }

    @Test
    fun keepsOneDifferencePerTitleNewestFirst() {
        val old = SyncDifference(SyncDifference.Kind.Manga, "1", "A", "Ch 1", "Ch 2", true, "{}", at = 1)
        val newer = old.copy(phone = "Ch 3", at = 2)
        val other = old.copy(key = "2", at = 3)
        assertEquals(listOf(other, newer), SyncDifferences.remember(listOf(old), listOf(newer, other)))
    }
}
