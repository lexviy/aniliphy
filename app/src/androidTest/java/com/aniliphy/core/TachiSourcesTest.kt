package com.aniliphy.core

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the installed, trusted Mihon and Aniyomi extensions end to end, reading only: nothing is saved to progress or
 * to AniList. Skipped when no such extension is installed. Run it with `am instrument`, which doesn't uninstall the app.
 */
@RunWith(AndroidJUnit4::class)
class TachiSourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(text: String) = Log.i("TachiTest", text)

    init {
        SiteCookies.init(context)
        Sources.init(context)
    }

    @Test
    fun extensionsLoad() {
        Sources.installed.forEach { log("installed ${it.label} ${it.version}: ${it.problem ?: it.sources}") }
    }

    @Test
    fun manga() {
        val sites = Sources.manga.filterIsInstance<TachiMangaSite>()
        sites.forEach { site ->
            val found = site.search("Frieren")
            log("${site.name} search: ${found.size} ${found.take(3).map { it.title }}")
            assertTrue("no results", found.isNotEmpty())
            val chapters = site.chapters(found.first().url)
            log("${site.name} chapters: ${chapters.size} first=${chapters.firstOrNull()} last=${chapters.lastOrNull()}")
            assertTrue("no chapters", chapters.isNotEmpty())
            val chapter = chapters.firstOrNull { it.number != null } ?: chapters.first()
            val pages = site.pages(chapter)
            log("${site.name} pages: ${pages.size} ${pages.take(2)}")
            assertTrue("no pages", pages.isNotEmpty())
            val (bytes, type) = site.image(pages.first())
            log("${site.name} image: ${bytes.size} bytes, $type")
            assertTrue("empty image", bytes.size > 1000)
        }
    }

    @Test
    fun anime() {
        val sites = Sources.anime.filterIsInstance<TachiAnimeSite>()
        val played = sites.count { site ->
            runCatching {
                val found = site.search("Frieren")
                log("${site.name} search: ${found.size} ${found.take(3).map { it.title }}")
                val episodes = site.episodes(found.first().url)
                log("${site.name} episodes: ${episodes.size} first=${episodes.firstOrNull()} last=${episodes.lastOrNull()}")
                val servers = site.servers(episodes.first())
                log("${site.name} servers: ${servers.map { "${it.name}/${it.audio}" }}")
                val stream = site.stream(servers.first())
                log("${site.name} stream: ${stream.url.take(120)} headers=${stream.headers.keys} subs=${stream.subtitles.size} intro=${stream.intro}")
                stream.url.startsWith("http")
            }.onFailure { log("${site.name} stopped: $it") }.getOrDefault(false)
        }
        assertTrue("no anime source got to a stream", sites.isEmpty() || played > 0)
    }

    /** Each stream is opened by a player with no screen, as the app's player opens it, until it is ready to play. */
    @Test
    fun animeStreamsOpen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = Sources.anime.filterIsInstance<TachiAnimeSite>().count { site ->
            val stream = runCatching {
                val show = site.search("Frieren").first()
                site.stream(site.servers(site.episodes(show.url).first()).first())
            }.getOrElse { log("${site.name} no stream: $it"); null } ?: return@count false
            val result = java.util.concurrent.CompletableFuture<String>()
            var player: androidx.media3.exoplayer.ExoPlayer? = null
            instrumentation.runOnMainSync {
                val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(com.aniliphy.manga.sources.SourceHttp.client)
                    .setUserAgent(com.aniliphy.manga.sources.SourceHttp.USER_AGENT)
                    .setDefaultRequestProperties(stream.headers)
                val item = androidx.media3.common.MediaItem.Builder().setUri(stream.url).setMimeType(com.aniliphy.anime.videoMimeOf(stream.url)).build()
                val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, http))
                val source = com.aniliphy.anime.withAudio(factory, factory.createMediaSource(item), com.aniliphy.anime.ExtraAudio.pick(stream.url, stream.audio)?.url)
                player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    addListener(object : androidx.media3.common.Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == androidx.media3.common.Player.STATE_READY) {
                                val sound = currentTracks.groups.count { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
                                result.complete("ready, ${duration / 1000}s, $sound audio")
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            result.complete("error: ${error.errorCodeName} ${error.cause}")
                        }
                    })
                    setMediaSource(source)
                    prepare()
                }
            }
            val outcome = runCatching { result.get(45, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { "timed out" }
            instrumentation.runOnMainSync { player?.release() }
            log("${site.name} player: $outcome (${com.aniliphy.anime.videoMimeOf(stream.url)})")
            outcome.startsWith("ready")
        }
        assertTrue("no stream opened", ready > 0)
    }

    /** A sort filter changes what a browse without words gives, read only. */
    @Test
    fun filtersApply() {
        (Sources.anime + Sources.manga).filterIsInstance<TachiSite>().forEach { site ->
            runCatching {
                val popular = site.browse(Listing.Popular, 1, "", null).items.take(3).map { it.title }
                val filters = site.filters()
                val sort = filters.items.filterIsInstance<SiteFilter.Select>().firstOrNull()
                sort?.set(1)
                val sorted = site.browse(Listing.Search, 1, "", filters).items.take(3).map { it.title }
                log("${site.name} popular=$popular ${sort?.name}=${sort?.values?.getOrNull(1)} -> $sorted")
            }.onFailure { log("${site.name} filters stopped: $it") }
        }
    }

    /** The background check for new chapters reads a Mihon source like any other, into a file of its own. */
    @Test
    fun newChapterCheck() {
        val site = Sources.manga.filterIsInstance<TachiMangaSite>().firstOrNull() ?: return
        val series = site.search("Frieren").take(6).firstOrNull { found ->
            runCatching { site.chapters(found.url).mapNotNull { it.number }.any { it > 1.0 } }.getOrDefault(false)
        } ?: error("no series with numbered chapters")
        val record = com.aniliphy.manga.MangaRecord(source = Sources.place(site.id, series.url), title = series.title, chapterNumber = 1.0)
        val file = java.io.File(context.cacheDir, "new_chapters_test.json").apply { delete() }
        try {
            val newChapters = com.aniliphy.manga.NewChapters(file)
            val records = mapOf(-424242 to record)
            // As if chapter 1 was the latest when last looked, long ago.
            newChapters.seen(-424242, record.source!!, 1.0, now = 0)
            val chapters = runCatching { site.chapters(series.url) }
            log("${site.name} ${series.title}: ${chapters.map { list -> list.size to list.mapNotNull { it.number }.maxOrNull() }}")
            com.aniliphy.manga.NewChapterCheck.refresh(records, newChapters)
            val fresh = com.aniliphy.manga.NewChapterCheck.fresh(records, newChapters)
            log("${site.name} new chapter check: ${newChapters.all()[-424242]} fresh=$fresh")
            assertTrue("no new chapter seen", fresh[-424242] != null)
        } finally {
            file.delete()
        }
    }

    /** A Mihon or Aniyomi backup is read the same in a minified build (its classes are kept by name nowhere). */
    @Test
    fun backupReads() {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("backup.tachibk").use { it.readBytes() }
        val library = TachiBackup.read(bytes)
        log("backup: manga=${library.manga.map { it.aniList }} anime=${library.anime.map { it.aniList }}")
        assertTrue(library.manga.firstOrNull()?.aniList == 4242)
        assertTrue(library.anime.map { it.aniList }.toSet() == setOf(777, 555))
    }
}
