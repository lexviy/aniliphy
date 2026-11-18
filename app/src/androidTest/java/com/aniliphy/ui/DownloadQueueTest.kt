package com.aniliphy.ui

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniliphy.anime.OfflineAnime
import com.aniliphy.anime.VideoQuality
import com.aniliphy.source.AudioVersion
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.VideoStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download queue with public sample videos, under a title id no AniList entry has: pause keeps what was saved
 * and carries on, Cancel all empties everything, and a short one finishes. Waits at each step so the notification
 * can be seen. Run it with `am instrument` (doesn't uninstall the app).
 */
@RunWith(AndroidJUnit4::class)
class DownloadQueueTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val offline = OfflineAnime.get(context)
    private val mediaId = -424242
    /** Big enough (10 minutes at the best quality) to still be going when paused. */
    private val dash = "https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd"
    private val hls = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    private val mp4 = "https://storage.googleapis.com/exoplayer-test-media-1/mp4/sample.mp4"
    private val episodes = listOf(SourceEpisode("queue-test-1", 1.0), SourceEpisode("queue-test-2", 2.0), SourceEpisode("queue-test-3", 3.0))
    private val request = AnimePlayerRequest(mediaId, "Queue test", "offline-test")

    /** Seconds to stay paused (`-e hold 20`), to have time to look at the notification. */
    private val hold = InstrumentationRegistry.getArguments().getString("hold")?.toLongOrNull() ?: 6

    private fun log(text: String) = Log.i("QueueTest", text)

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitFor(what: String, seconds: Int = 90, check: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            var ok = false
            main { ok = check() }
            if (ok) return
            Thread.sleep(250)
        }
        error("timed out waiting for $what")
    }

    private fun percent(episode: SourceEpisode): Int {
        var value = -2
        main { value = AnimeDownloads.active[AnimeDownloads.key(mediaId, episode.id)] ?: -2 }
        return value
    }

    private fun enqueue(list: List<SourceEpisode>, quality: VideoQuality = VideoQuality.Worst, url: (SourceEpisode) -> String) = main {
        AnimeDownloads.enqueue(context, offline, request, list, quality, AudioVersion.Sub, { false }) { episode ->
            Resolved(VideoStream(url(episode)))
        }
    }

    @After
    fun tidy() {
        main { DownloadCenter.cancelAll(context) }
        Thread.sleep(1500)
        episodes.forEach { offline.delete(mediaId, it.id) }
    }

    @Test
    fun pauseResumeCancel() {
        episodes.forEach { offline.delete(mediaId, it.id) }
        enqueue(episodes.take(2), VideoQuality.Best) { if (it == episodes[0]) dash else hls }
        assertEquals(2, DownloadQueueStore.get(context).all().count { it.key.startsWith("$mediaId/") })
        waitFor("episode 1 to start") { (AnimeDownloads.active[AnimeDownloads.key(mediaId, "queue-test-1")] ?: -1) >= 1 }
        log("STEP downloading ${percent(episodes[0])}%")
        Thread.sleep(3000)

        main { DownloadCenter.pause(context) }
        waitFor("pause") { DownloadCenter.waiting == DownloadCenter.Wait.Paused }
        val before = percent(episodes[0])
        log("STEP paused at $before%")
        Thread.sleep(hold * 1000)
        assertTrue("still queued after pause", percent(episodes[0]) >= 0)

        main { DownloadCenter.resume(context) }
        waitFor("carry on") { DownloadCenter.waiting == DownloadCenter.Wait.None && (AnimeDownloads.active[AnimeDownloads.key(mediaId, "queue-test-1")] ?: -1) >= 0 }
        Thread.sleep(2500)
        val after = percent(episodes[0])
        log("STEP resumed: $before% -> $after%")
        assertTrue("started over instead of carrying on ($before -> $after)", after >= before - 2)

        main { DownloadCenter.cancelAll(context) }
        waitFor("cancel") { AnimeDownloads.active.keys.none { it.startsWith("$mediaId/") } && !DownloadCenter.busy }
        log("STEP cancelled")
        assertTrue(DownloadQueueStore.get(context).all().none { it.key.startsWith("$mediaId/") })
        Thread.sleep(1500)
        assertNull("cancelled episode was saved", offline.saved(mediaId, episodes[0]))
    }

    @Test
    fun shortOneFinishes() {
        enqueue(listOf(episodes[2])) { mp4 }
        waitFor("finish", seconds = 120) { AnimeDownloads.active.keys.none { it.startsWith("$mediaId/") } }
        log("STEP finished")
        assertNotNull("not saved", offline.saved(mediaId, episodes[2]))
        assertTrue(DownloadQueueStore.get(context).all().none { it.key.startsWith("$mediaId/") })
    }
}
