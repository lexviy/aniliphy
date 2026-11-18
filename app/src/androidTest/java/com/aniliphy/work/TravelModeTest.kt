package com.aniliphy.work

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniliphy.anime.OfflineAnime
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.core.SiteCookies
import com.aniliphy.core.Sources
import com.aniliphy.ui.AnimeDownloads
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** "Get everything ready" for one made-up title, one episode ahead: queued, downloaded, trip kept. Cleans up. */
@RunWith(AndroidJUnit4::class)
class TravelModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val mediaId = -424245

    @Test
    fun getsOneEpisodeReady() = runBlocking {
        SiteCookies.init(context)
        Sources.init(context)
        val site = AnimeSites.site("hia")
        val show = site.search("Black Clover").first { it.title.equals("Black Clover", ignoreCase = true) }
        val episode = site.episodes(show.url).first { it.number == 1.0 }
        val offline = OfflineAnime.get(context)
        offline.delete(mediaId, episode.id)
        val hadTrip = TravelMode.trip(context)
        try {
            val pick = TravelPick(true, mediaId, "Travel test", null, "hia:${show.url}", progress = 0, released = 170, total = 170, mal = 34572)
            val queued = TravelMode.prepare(context, listOf(pick to 1), pages = false)
            assertEquals(1, queued)
            assertEquals(mediaId, TravelMode.trip(context)!!.items.single().mediaId)
            val end = System.currentTimeMillis() + 15 * 60_000
            while (System.currentTimeMillis() < end) {
                var left = true
                instrumentation.runOnMainSync { left = AnimeDownloads.active.keys.any { it.startsWith("$mediaId/") } }
                if (!left) break
                Thread.sleep(2_000)
            }
            val saved = offline.saved(mediaId, episode)
            Log.i("Travel", "saved=${saved?.bytes}")
            assertNotNull(saved)
            // Only one, though more are out.
            assertEquals(setOf(episode.id), offline.downloadedIds(mediaId))
        } finally {
            offline.downloadedIds(mediaId).forEach { offline.delete(mediaId, it) }
            if (hadTrip == null) TravelMode.endTrip(context)
        }
    }
}
