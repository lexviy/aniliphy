package com.aniliphy.anime

import com.aniliphy.source.AudioVersion
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.aniliphy.source.Segment
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.SubtitleTrack
import com.aniliphy.source.VideoStream
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.manga.sources.SourceHttp
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class PlaylistPart(val group: Int, val index: Int, val period: Int = 0)

@Serializable
data class SavedEpisode(
    val episode: SourceEpisode,
    val url: String,
    val parts: List<PlaylistPart> = emptyList(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val intro: Segment? = null,
    val outro: Segment? = null,
    val audio: AudioVersion = AudioVersion.Sub,
    val height: Int? = null,
    val bytes: Long = 0,
    /** HLS, DASH or a plain file; null for downloads made before others were saved, which were all HLS. */
    val mime: String? = null,
    /** The sound, when the source serves it apart from the picture; saved in the same cache. */
    val audioUrl: String? = null,
)

@Serializable
data class SavedShow(
    val source: String,
    val title: String? = null,
    val cover: String? = null,
    val episodes: List<SavedEpisode> = emptyList(),
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class OfflineAnime private constructor(private val context: Context) {
    private val root = File(context.filesDir, DIR)
    private val metaDir = File(root, "meta")
    private val subsDir = File(root, "subs")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    val cache: SimpleCache by lazy { SimpleCache(File(root, "cache"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context)) }

    private fun metaFile(mediaId: Int) = File(metaDir, "$mediaId.json")

    fun show(mediaId: Int): SavedShow? = synchronized(lock) {
        runCatching { metaFile(mediaId).readTextOrNull()?.let { json.decodeFromString<SavedShow>(it) } }.getOrNull()
    }

    private fun write(mediaId: Int, show: SavedShow?) = synchronized(lock) {
        val file = metaFile(mediaId)
        if (show == null || show.episodes.isEmpty()) {
            file.delete()
        } else {
            metaDir.mkdirs()
            file.writeTextAtomically(json.encodeToString(show))
        }
    }

    fun downloadedIds(mediaId: Int): Set<String> = show(mediaId)?.episodes?.map { it.episode.id }?.toSet().orEmpty()

    fun saved(mediaId: Int, episode: SourceEpisode): SavedEpisode? =
        show(mediaId)?.episodes?.let { list -> list.firstOrNull { it.episode.id == episode.id } ?: list.firstOrNull { it.episode.number == episode.number } }

    fun savedEpisodes(mediaId: Int, source: String): List<SourceEpisode> =
        show(mediaId)?.takeIf { it.source == source }?.episodes?.map { it.episode }?.sortedBy { it.number }.orEmpty()

    fun titles(): List<Pair<Int, SavedShow>> =
        metaDir.listFiles().orEmpty().mapNotNull { file ->
            val id = file.nameWithoutExtension.toIntOrNull() ?: return@mapNotNull null
            show(id)?.let { id to it }
        }

    fun dataSourceFactory(headers: Map<String, String>): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory(headers)))
            .setCacheWriteDataSinkFactory(null)

    fun mediaItem(saved: SavedEpisode, subtitles: List<MediaItem.SubtitleConfiguration>): MediaItem =
        MediaItem.Builder()
            .setUri(saved.url)
            .setMimeType(saved.mime ?: MimeTypes.APPLICATION_M3U8)
            .setStreamKeys(saved.parts.map { StreamKey(it.period, it.group, it.index) })
            .setSubtitleConfigurations(subtitles)
            .build()

    @Throws(IOException::class)
    fun download(
        mediaId: Int,
        source: String,
        title: String?,
        cover: String?,
        episode: SourceEpisode,
        stream: VideoStream,
        quality: VideoQuality,
        onProgress: (Int) -> Unit = {},
    ) {
        val mime = probeVideoMime(stream.url, stream.headers)
        val (parts, height) = when (mime) {
            MimeTypes.APPLICATION_M3U8 -> chooseHls(stream, quality)
            MimeTypes.APPLICATION_MPD -> chooseDash(stream, quality)
            else -> emptyList<PlaylistPart>() to null
        }
        val downloader = downloader(stream.url, mime, parts, stream.headers)
        var bytes = 0L
        try {
            downloader.download { _, downloaded, percent ->
                bytes = downloaded
                if (percent >= 0) onProgress(percent.toInt().coerceIn(0, 100))
            }
        } catch (e: InterruptedException) {
            runCatching { downloader.remove() }
            throw IOException("Stopped", e)
        } catch (e: IOException) {
            runCatching { downloader.remove() }
            throw e
        }
        // Sound served apart from the picture is saved too, in the language picked.
        val audioUrl = ExtraAudio.pick(stream.url, stream.audio)?.url
        if (audioUrl != null) {
            val sound = downloader(audioUrl, videoMimeOf(audioUrl), emptyList(), stream.headers)
            val pictureBytes = bytes
            try {
                sound.download { _, downloaded, _ -> bytes = pictureBytes + downloaded }
            } catch (e: InterruptedException) {
                runCatching { sound.remove() }
                runCatching { downloader.remove() }
                throw IOException("Stopped", e)
            } catch (e: IOException) {
                runCatching { sound.remove() }
                runCatching { downloader.remove() }
                throw e
            }
        }
        val folder = File(subsDir, "$mediaId/${key(episode.id)}").apply { mkdirs() }
        val subtitles = stream.subtitles.mapIndexedNotNull { index, track ->
            runCatching {
                val (data, _) = SourceHttp.bytes(track.url, stream.headers)
                val file = File(folder, "$index.${subtitleExtensionOf(track.url)}")
                file.writeBytes(data)
                track.copy(url = Uri.fromFile(file).toString())
            }.getOrNull()
        }
        val saved = SavedEpisode(episode, stream.url, parts, subtitles, stream.intro, stream.outro, stream.audio, height, bytes, mime, audioUrl)
        synchronized(lock) {
            val old = show(mediaId)?.takeIf { it.source == source }
            if (old == null) show(mediaId)?.episodes?.forEach { removeVideo(mediaId, it) }
            write(
                mediaId,
                SavedShow(
                    source = source,
                    title = title ?: old?.title,
                    cover = cover ?: old?.cover,
                    episodes = (old?.episodes.orEmpty().filter { it.episode.id != episode.id } + saved).sortedBy { it.episode.number },
                ),
            )
        }
    }

    fun delete(mediaId: Int, episodeId: String) {
        val show = show(mediaId) ?: return
        val gone = show.episodes.filter { it.episode.id == episodeId }
        gone.forEach { removeVideo(mediaId, it) }
        write(mediaId, show.copy(episodes = show.episodes - gone.toSet()))
    }

    fun deleteAll() {
        titles().forEach { (id, show) -> show.episodes.forEach { removeVideo(id, it) } }
        metaDir.deleteRecursively()
        subsDir.deleteRecursively()
        runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
    }

    private fun removeVideo(mediaId: Int, saved: SavedEpisode) {
        runCatching { downloader(saved.url, saved.mime ?: MimeTypes.APPLICATION_M3U8, saved.parts, emptyMap()).remove() }
        saved.audioUrl?.let { url -> runCatching { downloader(url, videoMimeOf(url), emptyList(), emptyMap()).remove() } }
        val folder = File(subsDir, "$mediaId/${key(saved.episode.id)}")
        folder.deleteRecursively()
        folder.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
    }

    private fun httpFactory(headers: Map<String, String>) =
        OkHttpDataSource.Factory(SourceHttp.client).setUserAgent(SourceHttp.USER_AGENT).setDefaultRequestProperties(headers)

    private fun cacheWriter(headers: Map<String, String>) =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(httpFactory(headers))

    private fun downloader(url: String, mime: String, parts: List<PlaylistPart>, headers: Map<String, String>): Downloader {
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(mime)
            .setStreamKeys(parts.map { StreamKey(it.period, it.group, it.index) })
            .build()
        val writer = cacheWriter(headers)
        return when (mime) {
            MimeTypes.APPLICATION_M3U8 -> HlsDownloader(item, writer)
            MimeTypes.APPLICATION_MPD -> DashDownloader(item, writer)
            else -> ProgressiveDownloader(item, writer)
        }
    }

    /** One video quality and one audio track in every period, in the language of the Sub or Dub picked, so only those are saved. */
    @Throws(IOException::class)
    private fun chooseDash(stream: VideoStream, quality: VideoQuality): Pair<List<PlaylistPart>, Int?> {
        val text = SourceHttp.fetcher.get(stream.url, stream.headers)
        val manifest = runCatching { text.byteInputStream().use { DashManifestParser().parse(Uri.parse(stream.url), it) } }
            .getOrElse { throw IOException("The video's DASH manifest couldn't be read", it) }
        var height: Int? = null
        val parts = buildList {
            for (period in 0 until manifest.periodCount) {
                val sets = manifest.getPeriod(period).adaptationSets
                sets.withIndex().firstOrNull { it.value.type == C.TRACK_TYPE_VIDEO && it.value.representations.isNotEmpty() }?.let { (group, set) ->
                    val heights = set.representations.map { it.format.height.coerceAtLeast(0) }
                    val index = AnimeRules.pickHeight(heights, quality) ?: 0
                    add(PlaylistPart(group, index, period))
                    if (height == null) height = heights[index].takeIf { it > 0 }
                }
                val audios = sets.withIndex().filter { it.value.type == C.TRACK_TYPE_AUDIO && it.value.representations.isNotEmpty() }
                val wanted = AnimeRules.audioLanguageOf(stream.audio)
                val spoken = audios.firstOrNull { (_, set) -> AnimeRules.sameLanguage(set.representations.first().format.language, wanted) }
                (spoken ?: audios.firstOrNull())?.let { (group, set) ->
                    val best = set.representations.withIndex().maxBy { it.value.format.bitrate }.index
                    add(PlaylistPart(group, best, period))
                }
            }
        }
        return parts to height
    }

    @Throws(IOException::class)
    private fun chooseHls(stream: VideoStream, quality: VideoQuality): Pair<List<PlaylistPart>, Int?> {
        val text = SourceHttp.fetcher.get(stream.url, stream.headers)
        val playlist = text.byteInputStream().use { HlsPlaylistParser().parse(Uri.parse(stream.url), it) }
        if (playlist !is HlsMultivariantPlaylist || playlist.variants.isEmpty()) return emptyList<PlaylistPart>() to null
        val heights = playlist.variants.map { it.format.height.coerceAtLeast(0) }
        val index = AnimeRules.pickHeight(heights, quality) ?: 0
        val parts = buildList {
            add(PlaylistPart(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, index))
            if (playlist.audios.isNotEmpty()) add(PlaylistPart(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 0))
        }
        return parts to heights[index].takeIf { it > 0 }
    }

    companion object {
        const val DIR = "offline_anime"

        @Volatile private var instance: OfflineAnime? = null

        fun get(context: Context): OfflineAnime = instance ?: synchronized(this) {
            instance ?: OfflineAnime(context.applicationContext).also { instance = it }
        }

        private fun key(id: String): String =
            MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
