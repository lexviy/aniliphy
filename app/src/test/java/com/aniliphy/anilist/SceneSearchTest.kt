package com.aniliphy.anilist

import com.aniliphy.core.FakeWeb
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneSearchTest {
    private val answer = FakeWeb.resource("/web/tracemoe.json")

    @Test
    fun findsTheShowEpisodeAndMinute() {
        val web = FakeWeb { _, _, _ -> FakeWeb.json(answer) }
        val found = SceneSearch(web).search(byteArrayOf(1, 2, 3))
        val best = found.first()
        assertEquals(21034, best.mediaId)
        assertEquals(29787, best.mal)
        assertEquals("Is the Order a Rabbit?? Season 2", best.title.display)
        assertEquals(1, best.episode)
        assertEquals(279.78, best.atSeconds, 0.01)
        assertTrue(best.sure)
        assertTrue(best.clipUrl!!.startsWith("https://api.trace.moe/video/"))
        assertEquals("POST", web.calls.single().method)
        assertTrue(web.calls.single().url.contains("anilistInfo"))
    }

    @Test
    fun theSameSceneOnlyOnce() {
        val found = SceneSearch.parse(answer)
        assertEquals(found.size, found.map { it.mediaId to it.episode }.distinct().size)
    }

    @Test
    fun episodeComesInManyShapes() {
        assertEquals(12, SceneSearch.episodeOf(Json.parseToJsonElement("12")))
        assertEquals(3, SceneSearch.episodeOf(Json.parseToJsonElement("\"3\"")))
        assertEquals(5, SceneSearch.episodeOf(Json.parseToJsonElement("[5, 6]")))
        assertEquals(2, SceneSearch.episodeOf(Json.parseToJsonElement("\"OVA 2\"")))
        assertNull(SceneSearch.episodeOf(Json.parseToJsonElement("null")))
    }

    @Test
    fun explainsLimits() {
        val used = runCatching { SceneSearch.parse("""{"error":"Search quota depleted","result":[]}""", 402) }.exceptionOrNull()
        assertEquals("trace.moe's searches for this month are used up", used?.message)
        val busy = runCatching { SceneSearch.parse("""{"error":"Concurrency limit exceeded"}""", 429) }.exceptionOrNull()
        assertTrue(busy is SceneSearchException)
    }

    @Test
    fun weakMatchesAreNotSure() {
        val text = """{"result":[{"anilist":{"id":1,"title":{"romaji":"A"}},"episode":1,"from":1,"to":2,"at":1.5,"similarity":0.85}]}"""
        assertFalse(SceneSearch.parse(text).single().sure)
        val tooWeak = """{"result":[{"anilist":{"id":1,"title":{"romaji":"A"}},"episode":1,"similarity":0.5}]}"""
        assertTrue(SceneSearch.parse(tooWeak).isEmpty())
    }

    @Test
    fun readsTheQuota() {
        val quota = SceneSearch(FakeWeb { _, _, _ -> FakeWeb.json("""{"id":"1.2.3.4","quota":100,"quotaUsed":7}""") }).quota()!!
        assertEquals(93, quota.left)
    }
}
