package com.aniliphy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatesTest {
    private val release = """
        {"tag_name":"v0.6.10","name":"aniliphy 0.6.10","html_url":"https://github.com/lexviy/aniliphy/releases/tag/v0.6.10",
         "draft":false,"prerelease":false,"published_at":"2026-11-20T10:00:00Z",
         "body":"Faster lists\r\n- one\r\n- two",
         "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":3},
                   {"name":"aniliphy-0.6.10.apk","browser_download_url":"https://x/aniliphy-0.6.10.apk","size":18505735}]}
    """.trimIndent()

    @Test
    fun readsTheReleaseAndItsApk() {
        val found = AppUpdates(FakeWeb { _, _, _ -> FakeWeb.json(release) }).latest()!!
        assertEquals("0.6.10", found.version)
        assertEquals("https://x/aniliphy-0.6.10.apk", found.apkUrl)
        assertEquals(18505735L, found.apkBytes)
        assertEquals("Faster lists\n- one\n- two", found.notes)
    }

    @Test
    fun comparesVersionsByNumber() {
        assertTrue(AppUpdates.isNewer("0.6.10", "0.6.9"))
        assertTrue(AppUpdates.isNewer("v0.7", "0.6.2"))
        assertTrue(AppUpdates.isNewer("1.0.0", "0.99.99"))
        assertFalse(AppUpdates.isNewer("0.6.2", "0.6.2"))
        assertFalse(AppUpdates.isNewer("0.6.2", "0.6.10"))
        assertFalse(AppUpdates.isNewer("0.6.2-beta", "0.6.2"))
    }

    @Test
    fun leavesOutDraftsAndPrereleases() {
        assertNull(AppUpdates.parse("""{"tag_name":"v9","prerelease":true}"""))
        assertNull(AppUpdates.parse("""{"tag_name":"v9","draft":true}"""))
        assertNull(AppUpdates.parse("<html>busy</html>"))
    }

    @Test
    fun noReleasesIsNotAnError() {
        assertNull(AppUpdates(FakeWeb { _, _, _ -> FakeWeb.json("""{"message":"Not Found"}""", 404) }).latest())
    }

    @Test
    fun asksAtMostOnceADay() {
        val day = 24L * 60 * 60 * 1000
        assertFalse(AppUpdates.dueForCheck(1_000, 1_000 + day - 1))
        assertTrue(AppUpdates.dueForCheck(1_000, 1_000 + day))
        // A clock moved back doesn't stop it for good.
        assertTrue(AppUpdates.dueForCheck(5 * day, day))
    }
}
