package com.aniliphy.anime

import com.aniliphy.core.FakeWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkipTimesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val naruto = """
        {"found":true,"results":[
          {"interval":{"startTime":1,"endTime":102},"skipType":"op","skipId":"a","episodeLength":1429.2},
          {"interval":{"startTime":1280.484,"endTime":1369.984},"skipType":"ed","skipId":"b","episodeLength":1400.5},
          {"interval":{"startTime":102,"endTime":160.227},"skipType":"recap","skipId":"c","episodeLength":1416.9}],
         "message":"Successfully found skip times","statusCode":200}
    """.trimIndent()

    @Test
    fun readsOpeningEndingAndRecap() {
        val skips = SkipTimes.parse(naruto, 1400.5)
        assertEquals(1.0, skips.intro!!.start, 0.01)
        assertEquals(102.0, skips.intro!!.end, 0.01)
        assertEquals(1280.484, skips.outro!!.start, 0.01)
        assertEquals(102.0, skips.recap!!.start, 0.01)
    }

    @Test
    fun movesTheEndingWhenOurVideoIsLonger() {
        // Our video has five more seconds, so the ending is five seconds later.
        val skips = SkipTimes.parse(naruto, 1405.5)
        assertEquals(1285.484, skips.outro!!.start, 0.01)
        // The opening is counted from the start, so it stays.
        assertEquals(1.0, skips.intro!!.start, 0.01)
    }

    @Test
    fun picksTheSubmissionClosestInLength() {
        val text = """{"found":true,"results":[
            {"interval":{"startTime":10,"endTime":100},"skipType":"op","episodeLength":1800},
            {"interval":{"startTime":20,"endTime":110},"skipType":"op","episodeLength":1420}]}"""
        assertEquals(20.0, SkipTimes.parse(text, 1421.0).intro!!.start, 0.01)
    }

    @Test
    fun nothingFoundIsEmpty() {
        assertTrue(SkipTimes.parse("""{"found":false,"results":[]}""", 1400.0).empty)
        assertTrue(SkipTimes.parse("not json", 1400.0).empty)
    }

    @Test
    fun asksOnceAndRemembers() {
        val web = FakeWeb { _, _, _ -> FakeWeb.json(naruto) }
        val file = folder.newFile("skips.json")
        val first = SkipTimes(file, web).find(20, 1, 1400.5, now = 1_000)
        assertEquals(102.0, first.intro!!.end, 0.01)
        assertTrue(web.calls.single().url.startsWith("https://api.aniskip.com/v2/skip-times/20/1?types[]=op"))
        assertTrue(web.calls.single().url.endsWith("episodeLength=1400.5"))
        // A new instance reads what was kept, without asking again.
        val again = SkipTimes(file, web).find(20, 1, 1400.5, now = 2_000)
        assertEquals(first, again)
        assertEquals(1, web.calls.size)
    }

    @Test
    fun notFoundIsAnAnswerToo() {
        val web = FakeWeb { _, _, _ -> FakeWeb.json("""{"found":false,"results":[],"statusCode":404}""", 404) }
        val skips = SkipTimes(null, web).find(1, 1, 1400.0)
        assertNull(skips.intro)
    }
}
