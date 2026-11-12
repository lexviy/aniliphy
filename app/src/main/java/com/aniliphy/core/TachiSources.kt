package com.aniliphy.core

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Bundle
import com.aniliphy.compat.TachiCompat
import com.aniliphy.manga.sources.SourceHttp
import com.aniliphy.source.AnimeSite
import com.aniliphy.source.AudioVersion
import com.aniliphy.source.BotCheckException
import com.aniliphy.source.Extension
import com.aniliphy.source.MangaSite
import com.aniliphy.source.Segment
import com.aniliphy.source.ServerLink
import com.aniliphy.source.SourceChapter
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.SourceSeries
import com.aniliphy.source.SourceServices
import com.aniliphy.source.SourceShow
import com.aniliphy.source.SubtitleTrack
import com.aniliphy.source.VideoStream
import androidx.preference.PreferenceScreen
import dalvik.system.DelegateLastClassLoader
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.sourcePreferences as animePreferences
import eu.kanade.tachiyomi.animesource.model.ChapterType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.sourcePreferences as mangaPreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * Mihon (manga) and Aniyomi (anime) extensions: APKs made for those apps, run through the classes in :tachi-compat
 * and shown to the rest of aniliphy as ordinary sources.
 */
internal object TachiExtensions {
    private const val MANGA_FEATURE = "tachiyomi.extension"
    private const val ANIME_FEATURE = "tachiyomi.animeextension"

    /** The extension library versions this app can run, as Mihon and Aniyomi check them. */
    private val MANGA_LIBS = listOf(1.4, 1.6)
    private val ANIME_LIBS = listOf(14.0, 16.0, 17.0)

    enum class Kind { Manga, Anime }

    fun kindOf(info: PackageInfo): Kind? {
        val features = info.reqFeatures.orEmpty().map { it.name }
        return when {
            MANGA_FEATURE in features -> Kind.Manga
            ANIME_FEATURE in features -> Kind.Anime
            else -> null
        }
    }

    fun name(kind: Kind, meta: Bundle?): String? =
        meta?.getString(if (kind == Kind.Manga) "tachiyomix.name" else "aniyomix.name")?.takeIf { it.isNotBlank() }

    /** Why the extension can't run here, or null when it can. */
    fun problem(kind: Kind, meta: Bundle?, versionName: String?): String? {
        val lib = when (kind) {
            Kind.Manga -> meta?.getFloat("tachiyomix.extensionLib")?.takeIf { it != 0f }?.toString()?.toDouble()
            Kind.Anime -> meta?.getInt("aniyomix.extensionLib")?.takeIf { it != 0 }?.toDouble()
        } ?: versionName?.substringBeforeLast('.')?.toDoubleOrNull()
        val supported = if (kind == Kind.Manga) MANGA_LIBS else ANIME_LIBS
        return when {
            lib == null -> "It doesn't say which version of the extension library it was made for"
            lib !in supported -> "It was made for extension library $lib, and aniliphy runs ${supported.joinToString(" and ")}"
            else -> null
        }
    }

    /** Creates the extension's sources. Throws whatever the extension throws. */
    fun load(context: Context, kind: Kind, info: PackageInfo): Extension {
        ready(context)
        val app = info.applicationInfo ?: throw IOException("It couldn't be read")
        val key = if (kind == Kind.Manga) "tachiyomi.extension.class" else "tachiyomi.animeextension.class"
        val classes = app.metaData?.getString(key)?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { if (it.startsWith(".")) info.packageName + it else it }
            ?.ifEmpty { null } ?: throw IOException("It doesn't say where its sources are")
        // Child first, as Mihon and Aniyomi load them, so an extension's own copy of a library wins over the app's.
        val loader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            DelegateLastClassLoader(app.sourceDir, app.nativeLibraryDir, context.classLoader)
        } else {
            PathClassLoader(app.sourceDir, app.nativeLibraryDir, context.classLoader)
        }
        val made = classes.flatMap { name ->
            when (val made = Class.forName(name, false, loader).getDeclaredConstructor().newInstance()) {
                is Source -> listOf(made)
                is SourceFactory -> made.createSources()
                is AnimeSource -> listOf(made)
                is AnimeSourceFactory -> made.createSources()
                else -> throw IOException("${made.javaClass.name} isn't a source")
            }
        }
        val manga = keepLanguages(made.filterIsInstance<CatalogueSource>()) { it.lang }
        val anime = keepLanguages(made.filterIsInstance<AnimeCatalogueSource>()) { it.lang }
        val manyLanguages = (manga.map { it.lang } + anime.map { it.lang }).toSet().size > 1
        fun label(name: String, lang: String) = if (manyLanguages && lang.isNotBlank()) "$name (${lang.uppercase()})" else name
        return object : Extension {
            private val mangaSites by lazy { manga.map { TachiMangaSite(it, "tm${it.id}", label(it.name, it.lang)) } }
            private val animeSites by lazy { anime.map { TachiAnimeSite(it, "ta${it.id}", label(it.name, it.lang)) } }

            override fun manga(services: SourceServices): List<MangaSite> = mangaSites

            override fun anime(services: SourceServices): List<AnimeSite> = animeSites
        }
    }

    /**
     * One extension can bring the same site in dozens of languages. Only English, the phone's language and the
     * multi-language ones are kept, unless that leaves nothing.
     */
    private fun <T> keepLanguages(sources: List<T>, lang: (T) -> String): List<T> {
        val phone = Locale.getDefault()
        val wanted = setOf("", "all", "en", phone.language, "${phone.language}-${phone.country}")
        return sources.filter { lang(it) in wanted }.ifEmpty { sources }
    }

    @Volatile
    private var compatReady = false

    private fun ready(context: Context) {
        if (compatReady) return
        synchronized(this) {
            if (compatReady) return
            val app = context.applicationContext as Application
            val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
            @Suppress("DEPRECATION")
            TachiCompat.init(
                app = app,
                versionCode = info?.versionCode ?: 0,
                versionName = info?.versionName.orEmpty(),
                client = SourceHttp.client,
                cookies = SiteCookies,
                forgetCookies = SiteCookies::forget,
                userAgent = { SourceHttp.USER_AGENT },
                siteCheck = ::siteCheck,
            )
            compatReady = true
        }
    }

    /**
     * Uses the browser's user agent on sites where a robot check was passed, since its cookies only work with it,
     * and turns a new check into the app's [BotCheckException].
     */
    private fun siteCheck(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val agent = SiteCookies.agentFor(request.url.host)
        val response = chain.proceed(if (agent != null) request.newBuilder().header("User-Agent", agent).build() else request)
        if (!response.isSuccessful && BotCheck.isCheck(response)) {
            response.close()
            val url = request.url.toString()
            BotCheck.pending = url
            throw BotCheckException(url)
        }
        return response
    }
}

/** Runs an extension's call, turning whatever it throws into the [IOException] the app expects from sources. */
private fun <T> call(what: String, block: suspend () -> T): T = try {
    runBlocking { block() }
} catch (e: IOException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    throw IOException("$what failed: ${e.message ?: e.javaClass.simpleName}", e)
}

private fun Headers.toMap(): Map<String, String> = names().associateWith { get(it).orEmpty() }

private fun fetchCover(client: OkHttpClient?, headers: Headers?, url: String): Pair<ByteArray, String?> {
    val request = Request.Builder().url(url).apply { headers?.let(::headers) }.build()
    return (client ?: SourceHttp.client).newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        response.body.bytes() to response.header("Content-Type")
    }
}

/** Tachiyomi sites list the newest first, and use -1 for "no number". */
private val CHAPTER_NUMBER = Regex("""(?i)(?:ch(?:apter)?|ep(?:isode)?)\.?\s*(\d+(?:\.\d+)?)""")

private fun numberOf(given: Float, name: String): Double? =
    given.takeIf { it >= 0f }?.toDouble() ?: CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toDoubleOrNull()

/** A Mihon manga source. */
internal class TachiMangaSite(
    private val source: CatalogueSource,
    override val id: String,
    override val name: String,
) : MangaSite, TachiSite {
    private val http get() = source as? HttpSource

    override val isAnime = false

    override val supportsLatest: Boolean get() = source.supportsLatest

    override val configurable: Boolean get() = source is ConfigurableSource

    override fun preferences(): SharedPreferences? = (source as? ConfigurableSource)?.mangaPreferences()

    override fun setupPreferences(screen: PreferenceScreen) {
        (source as? ConfigurableSource)?.setupPreferenceScreen(screen)
    }

    override fun filters(): SiteFilters = siteFilters(source.getFilterList())

    override fun browse(listing: Listing, page: Int, query: String, filters: SiteFilters?): BrowsePage = call("Browsing $name") {
        val found = when (listing) {
            Listing.Popular -> source.getPopularManga(page)
            Listing.Latest -> source.getLatestUpdates(page)
            Listing.Search -> source.getSearchManga(page, query, filters?.raw as? FilterList ?: source.getFilterList())
        }
        BrowsePage(found.mangas.map { BrowseItem(it.title, it.url, it.thumbnail_url) }, found.hasNextPage)
    }

    override fun cover(url: String): Pair<ByteArray, String?> = call("Getting a cover from $name") { fetchCover(http?.client, http?.headers, url) }

    override val imageHeaders: Map<String, String> get() = http?.headers?.toMap().orEmpty()

    override fun search(query: String): List<SourceSeries> = call("Searching $name") {
        source.getSearchManga(1, query, source.getFilterList()).mangas.map { SourceSeries(it.title, it.url, it.thumbnail_url) }
    }

    override fun chapters(seriesUrl: String): List<SourceChapter> = call("Getting the chapters from $name") {
        val manga = SManga.create().apply {
            url = seriesUrl
            title = ""
        }
        source.getMangaUpdate(manga, emptyList(), fetchDetails = false, fetchChapters = true).chapters
            .onEach { http?.prepareNewChapter(it, manga) }
            .map { chapter ->
                SourceChapter(
                    id = chapter.url,
                    name = chapter.name,
                    url = runCatching { http?.getChapterUrl(chapter) }.getOrNull() ?: chapter.url,
                    number = numberOf(chapter.chapter_number, chapter.name),
                )
            }
            .reversed()
    }

    override fun pages(chapter: SourceChapter): List<String> = call("Getting the pages from $name") {
        val asked = SChapter.create().apply {
            url = chapter.id
            name = chapter.name
        }
        // The whole page is kept, not just its image address: many sites need its url or index to fetch the image.
        source.getPageList(asked).sortedBy { it.index }.map { page ->
            PAGE + PAGE_JSON.encodeToString(SavedPage.serializer(), SavedPage(page.index, page.url, page.imageUrl?.takeIf { it.isNotBlank() }))
        }
    }

    /** One page's image, fetched the way the extension does it: its own client, headers and image tricks. */
    @Throws(IOException::class)
    fun image(url: String): Pair<ByteArray, String?> = call("Getting a page from $name") {
        val http = http ?: throw IOException("$name can't fetch pages itself")
        val saved = if (url.startsWith(PAGE)) PAGE_JSON.decodeFromString(SavedPage.serializer(), url.removePrefix(PAGE)) else SavedPage(0, "", url)
        val page = Page(saved.index, saved.url, saved.image)
        if (page.imageUrl == null) page.imageUrl = http.getImageUrl(page)
        http.getImage(page).use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.bytes() to response.header("Content-Type")
        }
    }

    @Serializable
    private data class SavedPage(val index: Int, val url: String, val image: String? = null)

    private companion object {
        /** Marks a page given as the extension's own page, not as an image address. */
        const val PAGE = "tachi-page:"
        val PAGE_JSON = Json { ignoreUnknownKeys = true }
    }
}

/** An Aniyomi anime source. */
internal class TachiAnimeSite(
    private val source: AnimeCatalogueSource,
    override val id: String,
    override val name: String,
) : AnimeSite, TachiSite {
    private val http get() = source as? AnimeHttpSource

    override val isAnime = true

    override val supportsLatest: Boolean get() = source.supportsLatest

    override val configurable: Boolean get() = source is ConfigurableAnimeSource

    override fun preferences(): SharedPreferences? = (source as? ConfigurableAnimeSource)?.animePreferences()

    override fun setupPreferences(screen: PreferenceScreen) {
        (source as? ConfigurableAnimeSource)?.setupPreferenceScreen(screen)
    }

    override fun filters(): SiteFilters = siteFilters(source.getFilterList())

    override fun browse(listing: Listing, page: Int, query: String, filters: SiteFilters?): BrowsePage = call("Browsing $name") {
        val found = when (listing) {
            Listing.Popular -> source.getPopularAnime(page)
            Listing.Latest -> source.getLatestUpdates(page)
            Listing.Search -> source.getSearchAnime(page, query, filters?.raw as? AnimeFilterList ?: source.getFilterList())
        }
        BrowsePage(found.animes.map { BrowseItem(it.title, it.url, it.thumbnail_url) }, found.hasNextPage)
    }

    override fun cover(url: String): Pair<ByteArray, String?> = call("Getting a cover from $name") { fetchCover(http?.client, http?.headers, url) }

    override fun search(query: String): List<SourceShow> = call("Searching $name") {
        source.getSearchAnime(1, query, source.getFilterList()).animes.map { SourceShow(it.title, it.url, it.thumbnail_url) }
    }

    override fun episodes(showUrl: String): List<SourceEpisode> = call("Getting the episodes from $name") {
        val anime = SAnime.create().apply {
            url = showUrl
            title = ""
        }
        val found = source.getEpisodeList(anime).onEach { http?.prepareNewEpisode(it, anime) }.reversed()
        found.mapIndexed { index, episode ->
            SourceEpisode(id = episode.url, number = numberOf(episode.episode_number, episode.name) ?: (index + 1.0), name = episode.name)
        }.sortedBy { it.number }
    }

    /** What a [ServerLink] stands for, until it is played. */
    private class Choice(val video: Video?, val hoster: Hoster?)

    private val choices = object : LinkedHashMap<String, Choice>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Choice>?) = size > 300
    }

    override fun servers(episode: SourceEpisode): List<ServerLink> = call("Getting the servers from $name") {
        val asked = SEpisode.create().apply {
            url = episode.id
            name = episode.name
            episode_number = episode.number.toFloat()
        }
        val http = http
        val found: List<Pair<String, Choice>> = if (http != null && hasHosters(http)) {
            with(http) { source.getHosterList(asked).sortHosters() }.flatMap { hoster ->
                val videos = hoster.videoList
                if (videos != null) {
                    videos.map { label(hoster.hosterName, it.videoTitle) to Choice(it, null) }
                } else {
                    listOf(hoster.hosterName to Choice(null, hoster))
                }
            }
        } else {
            val videos = source.getVideoList(asked)
            (if (http != null) with(http) { videos.sortVideos() } else videos).map { it.videoTitle to Choice(it, null) }
        }
        synchronized(choices) {
            found.mapIndexed { index, (label, choice) ->
                val link = "$index|${episode.id}"
                choices[link] = choice
                ServerLink(label.ifBlank { "Server ${index + 1}" }, if ("dub" in label.lowercase()) AudioVersion.Dub else AudioVersion.Sub, link)
            }
        }
    }

    override fun stream(server: ServerLink): VideoStream = call("Getting the video from $name") {
        val choice = synchronized(choices) { choices[server.link] } ?: run {
            // Not asked for in this run of the app: ask again.
            val episodeId = server.link.substringAfter('|')
            servers(SourceEpisode(id = episodeId, number = 0.0))
            synchronized(choices) { choices[server.link] }
        } ?: throw IOException("This server is no longer listed")
        val http = http
        val video = choice.video ?: run {
            val hoster = choice.hoster ?: throw IOException("Nothing to play")
            val videos = hoster.videoList ?: source.getVideoList(hoster)
            (if (http != null) with(http) { videos.sortVideos() } else videos).firstOrNull()
                ?: throw IOException("${hoster.hosterName} has no video")
        }
        val resolved = if (http == null) {
            video
        } else {
            val withUrl = if (video.videoUrl.isBlank() || video.videoUrl == "null") video.copy(videoUrl = http.getVideoUrl(video)) else video
            if (withUrl.initialized) withUrl else http.resolveVideo(withUrl) ?: throw IOException("The video couldn't be found")
        }
        com.aniliphy.anime.ExtraAudio.put(
            resolved.videoUrl,
            resolved.audioTracks.filter { it.url.startsWith("http") }.map { com.aniliphy.anime.AudioTrack(it.url, it.lang) },
        )
        fun segment(type: ChapterType) = resolved.timestamps.firstOrNull { it.type == type }?.let { Segment(it.start, it.end) }?.takeIf { it.valid }
        VideoStream(
            url = resolved.videoUrl,
            headers = (resolved.headers ?: http?.headers)?.toMap().orEmpty(),
            subtitles = resolved.subtitleTracks.map { SubtitleTrack(it.lang, it.url) },
            intro = segment(ChapterType.Opening),
            outro = segment(ChapterType.Ending),
            server = server.name,
            audio = server.audio,
        )
    }

    /** A hoster's video, named for the server list. Older sources put all their videos under one unnamed hoster. */
    private fun label(hoster: String, video: String) = when {
        hoster == Hoster.NO_HOSTER_LIST -> video
        video.isBlank() -> hoster
        hoster in video -> video
        else -> "$hoster · $video"
    }

    /** Sources made for extension library 16 and up list hosters first, then their videos; Aniyomi checks it this way. */
    private fun hasHosters(source: AnimeHttpSource): Boolean {
        var current: Class<*>? = source.javaClass
        while (current != null && current != ParsedAnimeHttpSource::class.java && current != AnimeHttpSource::class.java) {
            if (current.declaredMethods.any { it.name in HOSTER_METHODS }) return true
            current = current.superclass
        }
        return false
    }

    private companion object {
        val HOSTER_METHODS = setOf("getHosterList", "hosterListRequest", "hosterListParse")
    }
}
