package com.aniliphy.mal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class MalTest {
    @Test
    fun login() {
        val verifier = MalAuth.verifier()
        assertEquals(96, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" })
        val url = MalAuth.authUrl("abc", verifier, "S1")
        assertTrue(url.startsWith("https://myanimelist.net/v1/oauth2/authorize?response_type=code&client_id=abc"))
        assertTrue(url.contains("code_challenge=" + java.net.URLEncoder.encode(verifier, "UTF-8") + "&code_challenge_method=plain"))
        assertTrue(url.contains("redirect_uri=aniliphy%3A%2F%2Fmal&state=S1"))

        val now = 1_000_000L
        assertEquals(MalAuth.Answer.Code("XYZ"), MalAuth.read("aniliphy", "mal", "code=XYZ&state=S1", "S1", now - 1000, now))
        // Another state, no login waiting, too late, or AniList's link: not ours.
        assertEquals(MalAuth.Answer.NotOurs, MalAuth.read("aniliphy", "mal", "code=XYZ&state=S2", "S1", now - 1000, now))
        assertEquals(MalAuth.Answer.NotOurs, MalAuth.read("aniliphy", "mal", "code=XYZ&state=S1", "S1", null, now))
        assertEquals(MalAuth.Answer.NotOurs, MalAuth.read("aniliphy", "mal", "code=XYZ&state=S1", "S1", now - MalAuth.WAIT_MS - 1, now))
        assertEquals(MalAuth.Answer.NotOurs, MalAuth.read("aniliphy", "auth", "code=XYZ&state=S1", "S1", now - 1000, now))
        assertEquals(MalAuth.Answer.Refused("You said no on MyAnimeList"), MalAuth.read("aniliphy", "mal", "error=access_denied&state=S1", "S1", now - 1000, now))

        assertEquals("authorization_code", MalAuth.codeForm("abc", "XYZ", verifier)["grant_type"])
        assertEquals(verifier, MalAuth.codeForm("abc", "XYZ", verifier)["code_verifier"])
    }

    @Test
    fun aniListBecomesMal() {
        assertEquals("watching", MalList.status("CURRENT", true))
        assertEquals("reading", MalList.status("REPEATING", false))
        assertEquals("plan_to_read", MalList.status("PLANNING", false))
        assertEquals("on_hold", MalList.status("PAUSED", true))
        assertEquals(8, MalList.score(7.5))
        assertEquals(0, MalList.score(0.0))
        assertEquals(1, MalList.score(0.4))
        assertEquals(null, MalList.score(null))

        val rewatch = MalList.entry(5114, true, "REPEATING", 3, null, 9.0, 1)
        assertEquals(
            mapOf("status" to "watching", "num_watched_episodes" to "3", "num_times_rewatched" to "1", "is_rewatching" to "true", "score" to "9"),
            MalList.form(rewatch),
        )
        // A progress save leaves the score alone; manga sends chapters and volumes.
        val manga = MalList.entry(114745, false, "CURRENT", 369, 33, null, 0)
        assertEquals(
            mapOf("status" to "reading", "num_chapters_read" to "369", "num_volumes_read" to "33", "num_times_reread" to "0", "is_rereading" to "false"),
            MalList.form(manga),
        )
    }

    @Test
    fun comparison() {
        val aniList = listOf(
            AniListSide(1, 52991, true, "Frieren", "COMPLETED", 28, 0, 9.0, 0),
            AniListSide(2, 114745, false, "Blue Lock", "CURRENT", 369, 33, 0.0, 0),
            AniListSide(3, 140000, false, "Kagurabachi", "CURRENT", 139, 0, 0.0, 0),
            AniListSide(4, 849, true, "Haruhi", "CURRENT", 5, 0, 7.0, 0),
            AniListSide(5, null, false, "TBATE", "CURRENT", 10, 0, 0.0, 0),
        )
        val mal = listOf(
            MalEntry(52991, true, "watching", 18, score = 0, repeat = 0, repeating = false),
            MalEntry(114745, false, "reading", 301, 33, 0, 0, false),
            MalEntry(849, true, "watching", 5, score = 7, repeat = 0, repeating = false),
            MalEntry(1, true, "completed", 12),
            // Same number, other kind: not the same title.
            MalEntry(52991, false, "reading", 1),
        )
        val result = MalList.compare(aniList, mal)
        assertEquals(listOf("Frieren", "Blue Lock"), result.differ.map { it.title })
        assertEquals(listOf("watching ep 18 → completed ep 28", "score – → 9"), result.differ[0].changes)
        assertEquals(listOf("reading ch 301 → reading ch 369"), result.differ[1].changes)
        assertEquals(listOf("Kagurabachi"), result.missing.map { it.title })
        assertEquals(listOf("not on MAL → reading ch 139"), result.missing[0].changes)
        assertEquals(1, result.matching)
        assertEquals(listOf("TBATE"), result.noMalPage)
        assertEquals(2, result.onlyOnMal)

        // MAL at the end of the count it knows (705 chapters) is as far as it goes: no difference.
        val bleach = MalList.compare(
            listOf(AniListSide(9, 12, false, "Bleach", "COMPLETED", 706, 74, 0.0, 0)),
            listOf(MalEntry(12, false, "completed", 705, 74, 0, 0, false, total = 705)),
        )
        assertEquals(1, bleach.matching)
    }

    private class FakeWriter(var failWith: IOException? = null) : MalWriter {
        val done = ArrayList<String>()
        override suspend fun update(entry: MalEntry) {
            if (entry.malId == 404) throw MalRefusedException("not found")
            failWith?.let { throw it }
            done += "u${entry.malId}:${entry.progress}:${entry.score}"
        }

        override suspend fun delete(malId: Int, anime: Boolean) {
            failWith?.let { throw it }
            done += "d$malId"
        }
    }

    @Test
    fun queue() = runBlocking {
        val dir = Files.createTempDirectory("mal").toFile()
        try {
            val queue = MalQueue(java.io.File(dir, MalQueue.FILE))
            queue.add(MalWrite.Update(MalList.entry(10, true, "CURRENT", 3, null, 8.0, 0), "A"))
            // A later progress save keeps the score the first one had.
            queue.add(MalWrite.Update(MalList.entry(10, true, "CURRENT", 4, null, null, null), "A"))
            queue.add(MalWrite.Update(MalList.entry(404, true, "CURRENT", 1, null, null, null), "Gone"))
            queue.add(MalWrite.Delete(20, false, "B"))
            assertEquals(3, queue.all().size)

            val offline = FakeWriter(IOException("no network"))
            val first = queue.flush(offline)
            assertEquals(0, first.sent)
            assertEquals(3, first.left)

            val online = FakeWriter()
            val second = queue.flush(online)
            assertEquals(listOf("u10:4:8", "d20"), online.done)
            assertEquals(2, second.sent)
            assertEquals("Gone", second.dropped.single().first.title)
            assertEquals(0, second.left)
            assertTrue(queue.all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
