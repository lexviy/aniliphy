package com.aniliphy.anime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.core.SiteCookies
import com.aniliphy.core.Sources
import com.aniliphy.source.AudioVersion
import com.aniliphy.ui.AnimeDownloads
import com.aniliphy.ui.AnimePlayerRequest
import com.aniliphy.ui.Resolved
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real episode from the installed "hia" extension through the download queue, with AniSkip filling the opening
 * and ending (taken away from the source's own first, so the AniSkip path is the one used). Saved under a title id
 * no AniList entry has, then deleted. Touches no account. Run it with `am instrument`.
 */
@RunWith(AndroidJUnit4::class)
class RealDownloadTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val mediaId = -424243

    private fun log(text: String) = Log.i("RealDownload", text)

    @Test
    fun episodeWithAniSkip() {
        SiteCookies.init(context)
        Sources.init(context)
        val offline = OfflineAnime.get(context)
        val site = AnimeSites.site("hia")
        val show = site.search("Black Clover").first { it.title.equals("Black Clover", ignoreCase = true) }
        val episode = site.episodes(show.url).first { it.number == 1.0 }
        log("show ${show.title} ${show.url}, episode ${episode.id}")
        offline.delete(mediaId, episode.id)
        val request = AnimePlayerRequest(mediaId, "Download test", "hia:${show.url}", mal = 34572)
        try {
            instrumentation.runOnMainSync {
                AnimeDownloads.enqueue(context, offline, request, listOf(episode), VideoQuality.Worst, AudioVersion.Sub, { false }) {
                    val stream = AnimeSites.stream(site, it, AudioVersion.Sub)!!.copy(intro = null, outro = null)
                    Resolved(SkipTimes.shared(context).fill(stream, 34572, 1), from = "hia")
                }
            }
            val end = System.currentTimeMillis() + 15 * 60_000
            while (System.currentTimeMillis() < end) {
                var left = true
                instrumentation.runOnMainSync { left = AnimeDownloads.active.keys.any { it.startsWith("$mediaId/") } }
                if (!left) break
                Thread.sleep(2_000)
            }
            val saved = offline.saved(mediaId, episode)
            log("saved: bytes=${saved?.bytes} height=${saved?.height} intro=${saved?.intro} outro=${saved?.outro} subs=${saved?.subtitles?.size}")
            assertNotNull("not saved", saved)
            assertNotNull("AniSkip didn't fill the opening", saved!!.intro)
        } finally {
            offline.delete(mediaId, episode.id)
        }
    }
}
