package com.aniliphy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun reportReadsBack() {
        val error = RuntimeException("outer", IllegalStateException("No episodes for 21\nsecond line"))
        val text = CrashLog.report(error, "main", 1_700_000_000_000, "aniliphy 0.7.0 (14)")
        val crash = CrashLog.parse(text, seen = false)!!
        assertEquals(1_700_000_000_000, crash.time)
        assertEquals("IllegalStateException: No episodes for 21", crash.summary)
        assertTrue(crash.report.contains("thread: main"))
        assertTrue(crash.report.contains("at com.aniliphy.core.CrashLogTest"))
    }

    @Test
    fun summaryWithoutMessage() {
        assertEquals("NullPointerException", CrashLog.summaryOf(NullPointerException()))
    }

    @Test
    fun longReportsAreCut() {
        val error = RuntimeException("x".repeat(100_000))
        val text = CrashLog.report(error, "main", 1, "app")
        assertTrue(text.length < 33_000)
        assertTrue(text.endsWith("(cut)"))
    }

    @Test
    fun brokenFileIsIgnored() {
        assertNull(CrashLog.parse("garbage", seen = false))
    }
}
