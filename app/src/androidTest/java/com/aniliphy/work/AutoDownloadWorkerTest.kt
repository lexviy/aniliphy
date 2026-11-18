package com.aniliphy.work

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aniliphy.anime.OfflineAnime
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.core.SiteCookies
import com.aniliphy.core.Sources
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A new episode waiting in [AutoDownloads] is downloaded by [AutoDownloadWorker] through WorkManager, with its
 * notification. Uses a title id no AniList entry has and deletes the episode at the end. Touches no account.
 */
@RunWith(AndroidJUnit4::class)
class AutoDownloadWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val mediaId = -424244

    @Test
    fun downloadsWhatIsWaiting() {
        SiteCookies.init(context)
        Sources.init(context)
        val site = AnimeSites.site("hia")
        val show = site.search("Black Clover").first { it.title.equals("Black Clover", ignoreCase = true) }
        val episode = site.episodes(show.url).first { it.number == 1.0 }
        val offline = OfflineAnime.get(context)
        offline.delete(mediaId, episode.id)
        val store = AutoDownloads(File(context.filesDir, AutoDownloads.FILE))
        store.add(AutoDownload(true, mediaId, "Auto download test", "hia:${show.url}", from = 0.0, to = 1.0, mal = 34572, since = System.currentTimeMillis()))
        try {
            AutoDownloadWorker.schedule(context)
            val work = WorkManager.getInstance(context)
            val end = System.currentTimeMillis() + 15 * 60_000
            var state: WorkInfo.State? = null
            while (System.currentTimeMillis() < end) {
                state = work.getWorkInfosForUniqueWork("auto_downloads").get().firstOrNull()?.state
                if (state?.isFinished == true) break
                Thread.sleep(2_000)
            }
            val saved = offline.saved(mediaId, episode)
            Log.i("AutoDownload", "state=$state saved=${saved?.bytes} intro=${saved?.intro}")
            assertTrue("worker didn't finish: $state", state == WorkInfo.State.SUCCEEDED)
            assertNotNull("not saved", saved)
            assertTrue("still waiting", store.all().none { it.mediaId == mediaId })
        } finally {
            store.remove("a$mediaId")
            offline.delete(mediaId, episode.id)
        }
    }
}
