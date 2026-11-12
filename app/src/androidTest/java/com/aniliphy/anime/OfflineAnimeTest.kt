package com.aniliphy.anime

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aniliphy.core.SiteCookies
import com.aniliphy.core.Sources
import com.aniliphy.core.TachiAnimeSite
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.VideoStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Downloads small public sample videos, HLS, DASH and MP4, under a title id no AniList entry has, plays each back from the
 * cache alone, then deletes it. Run it with `am instrument`, which doesn't uninstall the app.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class OfflineAnimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val offline = OfflineAnime.get(context)

    private fun log(text: String) = Log.i("OfflineTest", text)

    private fun roundTrip(url: String, expectedMime: String, audio: com.aniliphy.source.AudioVersion = com.aniliphy.source.AudioVersion.Sub, check: (SavedEpisode) -> Unit = {}) {
        val mediaId = -424242
        val episode = SourceEpisode("offline-test-${expectedMime.hashCode()}", 1.0)
        offline.delete(mediaId, episode.id)
        try {
            offline.download(mediaId, "offline-test", "Test", null, episode, VideoStream(url, audio = audio), VideoQuality.Worst) {}
            val saved = offline.saved(mediaId, episode) ?: error("not saved")
            log("saved $url: mime=${saved.mime} parts=${saved.parts} height=${saved.height} bytes=${saved.bytes}")
            assertEquals(expectedMime, saved.mime)
            assertTrue("nothing saved", saved.bytes > 0)
            check(saved)
            assertEquals("ready", play(saved).substringBefore(','))
        } finally {
            offline.delete(mediaId, episode.id)
            assertNull("still saved", offline.saved(mediaId, episode))
        }
    }

    /** Plays [saved] with no network at all: whatever isn't in the cache fails. */
    private fun play(saved: SavedEpisode): String {
        val noNetwork = DataSource.Factory {
            object : DataSource {
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit
                override fun open(dataSpec: DataSpec): Long = throw IOException("not in the cache: ${dataSpec.uri}")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("closed")
                override fun getUri() = null
                override fun close() = Unit
            }
        }
        val cacheOnly = CacheDataSource.Factory().setCache(offline.cache).setUpstreamDataSourceFactory(noNetwork).setCacheWriteDataSinkFactory(null)
        val result = CompletableFuture<String>()
        var player: ExoPlayer? = null
        instrumentation.runOnMainSync {
            val factory = DefaultMediaSourceFactory(cacheOnly)
            val source = withAudio(factory, factory.createMediaSource(offline.mediaItem(saved, emptyList())), saved.audioUrl)
            player = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            val sound = currentTracks.groups.count { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                            log("offline ready with $sound audio track groups")
                            result.complete("ready, $sound")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        result.complete("error: ${error.errorCodeName} ${error.cause}")
                    }
                })
                setMediaSource(source)
                prepare()
            }
        }
        val outcome = runCatching { result.get(30, TimeUnit.SECONDS) }.getOrElse { "timed out" }
        instrumentation.runOnMainSync { player?.release() }
        return outcome
    }

    @Test
    fun hlsDownload() = roundTrip("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", androidx.media3.common.MimeTypes.APPLICATION_M3U8)

    /** Picture and sound in separate playlists, as some sources serve them: both are saved and play together. */
    @Test
    fun separateAudioDownload() {
        val base = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts"
        val video = "$base/v2/prog_index.m3u8"
        ExtraAudio.put(video, listOf(AudioTrack("$base/a1/prog_index.m3u8", "English")))
        try {
            roundTrip(video, androidx.media3.common.MimeTypes.APPLICATION_M3U8, com.aniliphy.source.AudioVersion.Dub) { saved ->
                assertEquals("$base/a1/prog_index.m3u8", saved.audioUrl)
            }
        } finally {
            ExtraAudio.put(video, emptyList())
        }
    }

    @Test
    fun mp4Download() = roundTrip("https://storage.googleapis.com/exoplayer-test-media-1/mp4/sample.mp4", androidx.media3.common.MimeTypes.VIDEO_MP4)

    @Test
    fun dashDownload() = roundTrip("https://dash.akamaized.net/akamai/bbb_30fps/bbb_30fps.mpd", androidx.media3.common.MimeTypes.APPLICATION_MPD)

    /** What kind the installed extensions' streams turn out to be, read only. */
    @Test
    fun extensionStreamKinds() {
        SiteCookies.init(context)
        Sources.init(context)
        Sources.anime.filterIsInstance<TachiAnimeSite>().forEach { site ->
            runCatching {
                val show = site.search("Frieren").first()
                val stream = site.stream(site.servers(site.episodes(show.url).first()).first())
                log("${site.name}: ${probeVideoMime(stream.url, stream.headers)} from ${stream.url}, separate audio ${ExtraAudio.of(stream.url).map { it.label to it.url }}")
                if (probeVideoMime(stream.url, stream.headers) == androidx.media3.common.MimeTypes.APPLICATION_MPD) {
                    val text = com.aniliphy.manga.sources.SourceHttp.fetcher.get(stream.url, stream.headers)
                    val manifest = text.byteInputStream().use {
                        androidx.media3.exoplayer.dash.manifest.DashManifestParser().parse(android.net.Uri.parse(stream.url), it)
                    }
                    val sets = manifest.getPeriod(0).adaptationSets
                    log("${site.name} DASH ${stream.audio}: " + sets.joinToString { set ->
                        "type=${set.type} lang=${set.representations.firstOrNull()?.format?.language} n=${set.representations.size}"
                    })
                }
            }.onFailure { log("${site.name} no stream: $it") }
        }
    }
}
