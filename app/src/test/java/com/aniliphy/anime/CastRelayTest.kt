package com.aniliphy.anime

import com.aniliphy.japanese.Cue
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CastRelayTest {
    @Test
    fun playlistAddressesGoThroughTheRelay() {
        val playlist = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:4.0,
            seg-1.ts
            #EXTINF:4.0,
            https://cdn.example/other/seg-2.ts
            #EXT-X-MEDIA:TYPE=AUDIO,URI="../audio/index.m3u8"
        """.trimIndent()
        val out = CastRelay.rewritePlaylist(playlist, "https://site.example/v/1/index.m3u8") { "R(" + it + ")" }
        assertTrue(out.contains("""URI="R(https://site.example/v/1/key.bin)""""))
        assertTrue(out.contains("R(https://site.example/v/1/seg-1.ts)"))
        assertTrue(out.contains("R(https://cdn.example/other/seg-2.ts)"))
        assertTrue(out.contains("""URI="R(https://site.example/v/audio/index.m3u8)""""))
        assertTrue(out.contains("#EXTINF:4.0,"))
    }

    @Test
    fun subtitlesBecomeWebVtt() {
        val text = CastRelay.vtt(listOf(Cue(1_500, 3_000, "Hello"), Cue(3_723_004, 3_724_000, "Line one\nLine two")))
        assertEquals("WEBVTT\n\n00:00:01.500 --> 00:00:03.000\nHello\n\n01:02:03.004 --> 01:02:04.000\nLine one\nLine two\n\n", text)
    }

    @Test
    fun ranges() {
        assertEquals(0L to 99L, CastRelay.parseRange("bytes=0-", 100))
        assertEquals(10L to 19L, CastRelay.parseRange("bytes=10-19", 100))
        assertEquals(90L to 99L, CastRelay.parseRange("bytes=-10", 100))
        assertEquals(50L to 99L, CastRelay.parseRange("bytes=50-500", 100))
        assertEquals(null, CastRelay.parseRange("bytes=200-", 100))
        assertEquals(null, CastRelay.parseRange(null, 100))
    }

    @Test
    fun servesFilesPlaylistsAndSubtitlesOverHttp() {
        val dir = kotlin.io.path.createTempDirectory("relay").toFile()
        try {
            val video = File(dir, "video.mp4").apply { writeBytes(ByteArray(1000) { (it % 251).toByte() }) }
            val list = File(dir, "index.m3u8")
            val seg = File(dir, "seg.ts").apply { writeBytes(ByteArray(10) { 7 }) }
            val srt = File(dir, "subs.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n") }
            val client = OkHttpClient()
            val first = CastRelay(client, "test", emptyMap()) { File(java.net.URI(it)).readText() }
            first.start("127.0.0.1")
            try {
                list.writeText("#EXTM3U\n#EXTINF:4.0,\n" + first.link(seg.toURI().toString()) + "\n")
                // A file on the phone, whole and in part.
                val link = first.link(video.toURI().toString())
                client.newCall(Request.Builder().url(link).build()).execute().use {
                    assertEquals(200, it.code)
                    assertEquals("*", it.header("Access-Control-Allow-Origin"))
                    assertEquals(1000, it.body!!.bytes().size)
                }
                client.newCall(Request.Builder().url(link).header("Range", "bytes=100-199").build()).execute().use {
                    assertEquals(206, it.code)
                    assertEquals("bytes 100-199/1000", it.header("Content-Range"))
                    val bytes = it.body!!.bytes()
                    assertEquals(100, bytes.size)
                    assertEquals((100 % 251).toByte(), bytes[0])
                }
                // A second relay fetching from the first over HTTP, with a playlist rewritten on the way.
                val second = CastRelay(client, "test", mapOf("Referer" to "https://site.example/")) { "" }
                second.start("127.0.0.1")
                try {
                    val relayed = second.link(first.link(list.toURI().toString()))
                    val text = client.newCall(Request.Builder().url(relayed).build()).execute().use { it.body!!.string() }
                    val segment = text.lines().first { it.startsWith("http") }
                    assertTrue(segment.startsWith("http://127.0.0.1:${second.port}/"))
                    client.newCall(Request.Builder().url(segment).build()).execute().use {
                        assertEquals(200, it.code)
                        assertEquals(10, it.body!!.bytes().count { b -> b == 7.toByte() })
                    }
                    // Unknown addresses aren't served.
                    val unknown = relayed.substringBeforeLast("/m/") + "/m/zz/x"
                    client.newCall(Request.Builder().url(unknown).build()).execute().use { assertEquals(404, it.code) }
                    client.newCall(Request.Builder().url("http://127.0.0.1:${second.port}/wrong/m/1/x").build()).execute().use { assertEquals(404, it.code) }
                } finally {
                    second.stop()
                }
                // Subtitles as WebVTT.
                val vtt = client.newCall(Request.Builder().url(first.subtitleLink(srt.toURI().toString(), "English")).build()).execute().use { it.body!!.string() }
                assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n\n", vtt)
            } finally {
                first.stop()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
