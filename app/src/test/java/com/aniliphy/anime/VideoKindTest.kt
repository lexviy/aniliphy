package com.aniliphy.anime

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoKindTest {
    @Test
    fun kindFromAddress() {
        assertEquals(MimeTypes.APPLICATION_MPD, videoMimeOf("https://a.b/x/manifest.MPD?t=1"))
        assertEquals(MimeTypes.VIDEO_MP4, videoMimeOf("https://a.b/ep1.mp4#t=3"))
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeOf("https://a.b/master.m3u8"))
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeOf("https://a.b/stream"))
    }

    @Test
    fun kindFromServerType() {
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeOfType("application/vnd.apple.mpegurl"))
        assertEquals(MimeTypes.APPLICATION_M3U8, videoMimeOfType("audio/x-mpegURL; charset=utf-8"))
        assertEquals(MimeTypes.APPLICATION_MPD, videoMimeOfType("application/dash+xml"))
        assertEquals(MimeTypes.VIDEO_MP4, videoMimeOfType("video/mp4"))
        assertEquals(MimeTypes.VIDEO_MATROSKA, videoMimeOfType("video/x-matroska"))
        assertNull(videoMimeOfType("application/octet-stream"))
        assertNull(videoMimeOfType(null))
    }

    @Test
    fun subtitleKeepsItsKind() {
        assertEquals("srt", subtitleExtensionOf("https://a.b/en.SRT?x=1"))
        assertEquals("ass", subtitleExtensionOf("https://a.b/en.ass"))
        assertEquals("vtt", subtitleExtensionOf("https://a.b/subs?id=4"))
    }

    @Test
    fun audioLanguage() {
        assertEquals("ja", AnimeRules.audioLanguageOf(com.aniliphy.source.AudioVersion.Sub))
        assertEquals("en", AnimeRules.audioLanguageOf(com.aniliphy.source.AudioVersion.Dub))
        org.junit.Assert.assertTrue(AnimeRules.sameLanguage("jpn", "ja"))
        org.junit.Assert.assertTrue(AnimeRules.sameLanguage("en-US", "en"))
        org.junit.Assert.assertFalse(AnimeRules.sameLanguage("eng", "ja"))
        org.junit.Assert.assertFalse(AnimeRules.sameLanguage(null, "ja"))
    }
}
