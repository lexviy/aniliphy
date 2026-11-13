package com.aniliphy.sync

import com.aniliphy.anilist.Goal
import com.aniliphy.anilist.GoalKind
import com.aniliphy.anilist.GoalPeriod
import com.aniliphy.core.ActivityDay
import com.aniliphy.ui.ProfileLocalData
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSyncTest {
    private val g1 = Goal("a", GoalKind.AnimeFinished, GoalPeriod.Year, 50)
    private val g2 = Goal("b", GoalKind.ChaptersRead, GoalPeriod.Week, 20)

    @Test fun newerSetsWinAndMomentsAddUp() {
        val phone = ProfilePayload(profile = ProfileLocalData(goals = listOf(g1), goalsUpdated = 100, showcase = listOf("x"), showcaseUpdated = 300, celebrated = setOf("m1"), seeded = setOf("streak")))
        // The other phone removed g1 and added g2 later; its showcase is older.
        val gist = ProfilePayload(profile = ProfileLocalData(goals = listOf(g2), goalsUpdated = 200, showcase = listOf("y"), showcaseUpdated = 50, celebrated = setOf("m2")))
        val merged = ProfileMerge.merge(phone, gist, "me").profile
        assertEquals(listOf(g2), merged.goals)
        assertEquals(200L, merged.goalsUpdated)
        assertEquals(listOf("x"), merged.showcase)
        assertEquals(setOf("m1", "m2"), merged.celebrated)
        assertEquals(setOf("streak"), merged.seeded)
    }

    @Test fun activityKeepsEachPhoneAndIsIdempotent() {
        val today = mapOf("2026-11-13" to ActivityDay(mangaChapters = 3))
        val other = mapOf("2026-11-13" to ActivityDay(animeMs = 60_000))
        val phone = ProfilePayload(activity = mapOf("me" to today, "old" to mapOf("2026-01-01" to ActivityDay(novelChapters = 1))))
        val gist = ProfilePayload(activity = mapOf("me" to mapOf("2026-11-12" to ActivityDay(mangaChapters = 9)), "tab" to other))
        val once = ProfileMerge.merge(phone, gist, "me")
        assertEquals(mapOf("me" to today, "tab" to other), once.activity)
        assertEquals(once, ProfileMerge.merge(phone, once, "me"))
    }

    @Test fun roundTripAndNewerVersionIgnored() {
        val p = ProfilePayload(profile = ProfileLocalData(goals = listOf(g1), goalsUpdated = 5), activity = mapOf("me" to mapOf("2026-11-13" to ActivityDay(animeMs = 1))))
        assertEquals(p, ProfilePayload.parse(SyncPayload.json.encodeToString(ProfilePayload.serializer(), p)))
        assertEquals(null, ProfilePayload.parse("""{"version": 2}"""))
    }

    @Test fun writeFileOnlyNamesItsFile() {
        val calls = mutableListOf<Triple<String, String, String?>>()
        val store = GistStore { method, path, _, body -> calls += Triple(method, path, body); 200 to """{"files":{"aniliphy-profile.json":{"content":"hi"}}}""" }
        assertEquals("hi", store.readFile("t", "g", ProfilePayload.FILE_NAME))
        store.writeFile("t", "g", ProfilePayload.FILE_NAME, "{}")
        val body = calls.last().third!!
        assertEquals("PATCH", calls.last().first)
        assertEquals(true, "aniliphy-profile.json" in body && SyncPayload.FILE_NAME !in body)
    }
}
