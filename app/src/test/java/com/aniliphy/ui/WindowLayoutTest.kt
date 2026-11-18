package com.aniliphy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test
    fun sizes() {
        assertEquals(WindowLayout.Compact, WindowLayout.of(411, 914, tv = false))
        assertEquals(WindowLayout.Compact, WindowLayout.of(599, 900, tv = false))
        assertEquals(WindowLayout.Medium, WindowLayout.of(600, 960, tv = false))
        // A phone on its side is wide but short: Medium, so its own bottom bar stays.
        assertEquals(WindowLayout.Medium, WindowLayout.of(838, 360, tv = false))
        assertEquals(WindowLayout.Expanded, WindowLayout.of(1280, 800, tv = false))
        assertEquals(WindowLayout.Expanded, WindowLayout.of(840, 1200, tv = false))
        assertEquals(WindowLayout.Tv, WindowLayout.of(960, 540, tv = true))
        assertEquals(WindowLayout.Tv, WindowLayout.of(400, 300, tv = true))

        assertFalse(WindowLayout.Medium.rail)
        assertTrue(WindowLayout.Expanded.twoPanes)
        assertTrue(WindowLayout.Tv.coverMinDp > WindowLayout.Expanded.coverMinDp)
        assertTrue(WindowLayout.Expanded.coverMinDp > WindowLayout.Compact.coverMinDp)
    }
}
