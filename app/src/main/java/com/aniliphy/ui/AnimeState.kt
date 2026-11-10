package com.aniliphy.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListSync
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ProgressRules
import com.aniliphy.anilist.SessionState
import com.aniliphy.anime.AnimeHistory
import com.aniliphy.anime.AnimeRecord
import com.aniliphy.anime.AnimeRules
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.source.AudioVersion
import com.aniliphy.anime.NewEpisodes
import com.aniliphy.anime.OfflineAnime
import com.aniliphy.anime.VideoQuality
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.source.AnimeSite
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.SourceShow
import com.aniliphy.source.VideoStream
import com.aniliphy.novels.PendingProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal data class AnimePlayerRequest(
    val mediaId: Int,
    val title: String,
    val source: String,
    val titles: List<String> = emptyList(),
    val cover: String? = null,
    val totalEpisodes: Int? = null,
    val mal: Int? = null,
)

internal data class AnimeTitle(
    val mediaId: Int,
    val shown: String,
    val titles: List<String>,
    val cover: String?,
    val total: Int?,
    val mal: Int?,
    val released: Int? = null,
)

internal sealed interface ShowPick {
    val title: AnimeTitle

    data class Searching(override val title: AnimeTitle, val source: AnimeSite) : ShowPick
    data class Results(override val title: AnimeTitle, val source: AnimeSite, val results: List<SourceShow>) : ShowPick
    data class Nothing(override val title: AnimeTitle, val failed: Boolean, val tried: List<AnimeSite>) : ShowPick
}

internal data class Resolved(val stream: VideoStream, val note: String? = null, val from: String = AnimeSites.DEFAULT) {
    val server: String get() = "$from:${stream.server}"
}

internal object AnimeCache {
    private const val EPISODES_FOR_MS = 10 * 60_000L
    private const val STREAMS_FOR_MS = 5 * 60_000L
    private val episodeLists = ConcurrentHashMap<String, Pair<Long, List<SourceEpisode>>>()
    private val streams = ConcurrentHashMap<String, Pair<Long, Resolved>>()

    fun episodes(sourceId: String): List<SourceEpisode>? =
        episodeLists[sourceId]?.takeIf { System.currentTimeMillis() - it.first < EPISODES_FOR_MS }?.second

    fun keepEpisodes(sourceId: String, episodes: List<SourceEpisode>) {
        if (episodes.isNotEmpty()) episodeLists[sourceId] = System.currentTimeMillis() to episodes
    }

    fun stream(key: String): Resolved? = streams[key]?.takeIf { System.currentTimeMillis() - it.first < STREAMS_FOR_MS }?.second

    fun keepStream(key: String, resolved: Resolved) {
        streams[key] = System.currentTimeMillis() to resolved
    }

    fun forgetStream(key: String) {
        streams.remove(key)
    }
}

internal object AnimeDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val one = Mutex()

    val active = mutableStateMapOf<String, Int>()

    var version by mutableIntStateOf(0)
        private set

    fun key(mediaId: Int, episodeId: String) = "$mediaId/$episodeId"

    fun background(work: () -> Unit) {
        scope.launch { runCatching(work) }
    }

    fun delete(offline: OfflineAnime, mediaId: Int, episodeId: String) {
        scope.launch {
            runCatching { offline.delete(mediaId, episodeId) }
            withContext(Dispatchers.Main) { version++ }
        }
    }

    fun enqueue(
        app: Context,
        offline: OfflineAnime,
        request: AnimePlayerRequest,
        episodes: List<SourceEpisode>,
        quality: VideoQuality,
        resolve: suspend (SourceEpisode) -> Resolved,
    ) {
        val wanted = episodes.filter { key(request.mediaId, it.id) !in active }
        if (wanted.isEmpty()) return
        wanted.forEach { active[key(request.mediaId, it.id)] = -1 }
        scope.launch {
            var saved = 0
            var failed: SourceEpisode? = null
            for (episode in wanted) {
                val key = key(request.mediaId, episode.id)
                one.withLock {
                    try {
                        val stream = resolve(episode).stream
                        withContext(Dispatchers.Main) { active[key] = 0 }
                        offline.download(request.mediaId, request.source, request.title, request.cover, episode, stream, quality) { percent ->
                            scope.launch(Dispatchers.Main) { if (key in active) active[key] = percent }
                        }
                        saved++
                    } catch (_: IOException) {
                        failed = episode
                    } finally {
                        withContext(Dispatchers.Main) {
                            active.remove(key)
                            version++
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                val message = when {
                    failed != null -> "Could not download episode ${episodeLabel(failed!!)}" + if (saved > 0) " ($saved saved)" else ""
                    saved == 1 -> "Downloaded episode ${episodeLabel(wanted.first())}"
                    else -> "Downloaded $saved episodes"
                }
                app.showToast(message)
            }
        }
    }
}

internal fun episodeLabel(episode: SourceEpisode): String =
    if (episode.number % 1.0 == 0.0) episode.number.toInt().toString() else episode.number.toString()

internal class AnimeUi(
    context: Context,
    val aniList: AniListUi,
    private val scope: CoroutineScope,
    private val savedPlayer: () -> AnimePlayerRequest?,
    private val savePlayer: (AnimePlayerRequest?) -> Unit,
) {
    private val app = context.applicationContext
    val settings = AnimeSettings.get(app)
    val history = AnimeHistory(File(app.filesDir, "anime_history.json"))
    val offline: OfflineAnime get() = OfflineAnime.get(app)
    val newEpisodes = NewEpisodes(File(app.filesDir, "anime_new_episodes.json"))
    private val pending = PendingProgress(File(app.filesDir, "anime_pending.json"))
    private val sending = Mutex()
    private var toldWaiting = false
    private var searchJob: Job? = null

    var pick by mutableStateOf<ShowPick?>(null)

    var askStart by mutableStateOf<StartQuestion?>(null)
        private set

    var starting by mutableStateOf(false)
        private set

    var historyVersion by mutableIntStateOf(0)
        private set

    fun historyChanged() {
        historyVersion++
    }

    var newOut by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set

    val player: AnimePlayerRequest? get() = savedPlayer()

    /** Set by the app: switches to the AniList tab. */
    var onShowAniList: () -> Unit = {}

    /** Opens the title's AniList page, without changing anything on the account. */
    fun showOnAniList(mediaId: Int) {
        aniList.navigator.open(Route.Media(mediaId))
        onShowAniList()
    }

    val loggedIn: Boolean get() = aniList.session.state is SessionState.LoggedIn

    fun watchMedia(media: Media) {
        val title = AnimeTitle(
            mediaId = media.id,
            shown = media.title.display,
            titles = listOfNotNull(media.title.english, media.title.romaji, media.title.native)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            cover = media.coverImage.best,
            total = media.episodes,
            mal = media.idMal,
            released = media.nextAiringEpisode?.episode?.let { it - 1 },
        )
        if (title.titles.isEmpty()) {
            app.showToast("No title to search")
            return
        }
        begin(title)
    }

    fun continueWatching(mediaId: Int, record: AnimeRecord) {
        val shown = record.title ?: return
        begin(AnimeTitle(mediaId, shown, record.titles.ifEmpty { listOf(shown) }, record.cover, record.total, record.mal))
    }

    private fun begin(title: AnimeTitle) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                startOnAniList(title)
            } finally {
                starting = false
            }
            title.released?.let { released ->
                AnimeDownloads.background { newEpisodes.opened(title.mediaId, released) }
                newOut = newOut - title.mediaId
            }
            val source = withContext(Dispatchers.IO) { history.get(title.mediaId) }?.source
            if (AnimeSites.parse(source) != null) open(title, source!!) else search(title)
        }
    }

    private suspend fun startOnAniList(title: AnimeTitle) {
        if (!loggedIn) return
        val mediaId = title.mediaId
        try {
            val current = aniList.api.progress(mediaId)
            val resume = AnimeRules.shouldResume(current)
            val change = if (resume) null else ProgressRules.start(current)
            if (!resume && change == null) return
            val status = if (resume) ListStatus.CURRENT else change!!.status!!
            val label = ListStatus.label(status, MediaKind.Anime)
            if (settings.aniListSync == AniListSync.Ask) {
                val answer = CompletableDeferred<Boolean>()
                askStart = StartQuestion(
                    title.shown,
                    if (resume) "AniList would show it as $label again." else "AniList would show it as $label, with progress back to 0.",
                    answer,
                )
                val yes = try {
                    answer.await()
                } finally {
                    askStart = null
                }
                if (!yes) return
            }
            if (resume) {
                aniList.api.saveEntry(mediaId, ListStatus.CURRENT, current.progress)
                app.showToast("AniList status: $label")
            } else {
                aniList.api.startMedia(mediaId) ?: return
                withContext(Dispatchers.IO) { history.clearPlace(mediaId) }
                app.showToast("AniList status: $label (progress reset)")
            }
            aniList.navigator.listsChanged()
        } catch (_: AniListException) {
            app.showToast("Could not update your AniList status")
        }
    }

    fun answerStart(yes: Boolean) {
        askStart?.answer?.complete(yes)
    }

    fun checkNewEpisodes(entries: List<ListEntry>) {
        scope.launch {
            val items = entries.map { NewEpisodes.Airing(it.media.id, it.media.nextAiringEpisode?.episode, it.progress) }
            newOut = withContext(Dispatchers.IO) { newEpisodes.check(items) }
        }
    }

    fun search(title: AnimeTitle, only: AnimeSite? = null) {
        searchJob?.cancel()
        val order = only?.let { listOf(it) } ?: AnimeSites.order(settings.source)
        if (order.isEmpty()) {
            pick = ShowPick.Nothing(title, failed = false, tried = emptyList())
            return
        }
        pick = ShowPick.Searching(title, order.first())
        searchJob = scope.launch {
            var failed = false
            for (source in order) {
                pick = ShowPick.Searching(title, source)
                val site = source
                val results = withContext(Dispatchers.IO) {
                    title.titles.firstNotNullOfOrNull { query ->
                        try {
                            site.search(query).takeIf { it.isNotEmpty() }
                        } catch (_: IOException) {
                            failed = true
                            null
                        }
                    }
                }
                if (results != null) {
                    pick = ShowPick.Results(title, source, results)
                    return@launch
                }
            }
            pick = ShowPick.Nothing(title, failed, order)
        }
    }

    fun cancelPick() {
        searchJob?.cancel()
        pick = null
    }

    fun choose(title: AnimeTitle, source: AnimeSite, show: SourceShow) {
        val id = AnimeSites.sourceId(source.id, show.url)
        pick = null
        scope.launch {
            withContext(Dispatchers.IO) { history.setSource(title.mediaId, id, title.shown, title.cover) }
            open(title, id)
        }
    }

    private fun open(title: AnimeTitle, sourceId: String) {
        savePlayer(
            AnimePlayerRequest(
                mediaId = title.mediaId,
                title = title.shown,
                source = sourceId,
                titles = title.titles,
                cover = title.cover,
                totalEpisodes = title.total,
                mal = title.mal,
            ),
        )
    }

    fun titleOf(request: AnimePlayerRequest) = AnimeTitle(
        request.mediaId,
        request.title,
        request.titles.ifEmpty { listOf(request.title) },
        request.cover,
        request.totalEpisodes,
        request.mal,
    )

    fun forgetSource(request: AnimePlayerRequest) {
        scope.launch {
            withContext(Dispatchers.IO) { history.setSource(request.mediaId, null) }
            closePlayer()
            app.showToast("Source forgotten. Watch searches again next time")
        }
    }

    fun closePlayer() {
        savePlayer(null)
        historyVersion++
    }

    @Throws(IOException::class)
    fun episodes(sourceId: String): List<SourceEpisode> {
        AnimeCache.episodes(sourceId)?.let { return it }
        val (source, url) = AnimeSites.parse(sourceId) ?: throw IOException("Unknown source")
        return AnimeSites.site(source).episodes(url).also { AnimeCache.keepEpisodes(sourceId, it) }
    }

    @Throws(IOException::class)
    suspend fun resolve(
        request: AnimePlayerRequest,
        episode: SourceEpisode,
        audio: AudioVersion,
        fresh: Boolean = false,
        skip: Set<String> = emptySet(),
    ): Resolved =
        withContext(Dispatchers.IO) {
            val key = "${request.mediaId}/${episode.id}/$audio"
            if (fresh || skip.isNotEmpty()) AnimeCache.forgetStream(key)
            AnimeCache.stream(key)?.let { return@withContext it }
            val (source, _) = AnimeSites.parse(request.source) ?: throw IOException("Unknown source")
            val main = try {
                fromSite(source, episode, audio, skip)
            } catch (_: IOException) {
                null
            }
            val found = main ?: run {
                val backupId = backupSource(request, source)
                    ?: throw IOException("Episode ${episodeLabel(episode)} couldn't be played on ${AnimeSites.name(source)}")
                val (backup, _) = AnimeSites.parse(backupId)!!
                val there = episodes(backupId).firstOrNull { it.number == episode.number }
                    ?: throw IOException("Episode ${episodeLabel(episode)} isn't on ${AnimeSites.name(backup)}")
                val resolved = fromSite(backup, there, audio, skip)
                    ?: throw IOException("Episode ${episodeLabel(episode)} couldn't be played")
                resolved.copy(note = listOfNotNull(resolved.note, "Playing from ${AnimeSites.name(backup)}").joinToString(". "))
            }
            AnimeCache.keepStream(key, found)
            found
        }

    @Throws(IOException::class)
    private fun fromSite(source: String, episode: SourceEpisode, audio: AudioVersion, skip: Set<String>): Resolved? {
        val site = AnimeSites.site(source)
        val names = skip.filter { it.startsWith("$source:") }.map { it.substringAfter(':') }.toSet()
        AnimeSites.stream(site, episode, audio, names)?.let { return Resolved(it, from = source) }
        if (audio == AudioVersion.Dub) {
            AnimeSites.stream(site, episode, AudioVersion.Sub, names)?.let { return Resolved(it, "No dub for this episode, playing the sub", source) }
        }
        return null
    }

    /** Another source with the same show, to play what [main] can't. Remembered once found. */
    private fun backupSource(request: AnimePlayerRequest, main: String): String? {
        val record = history.get(request.mediaId)
        record?.backup?.takeIf { AnimeSites.parse(it)?.first.let { id -> id != main && AnimeSites.find(id) != null } }?.let { return it }
        val mal = request.mal ?: record?.mal
        val names = request.titles.map(::plain).toSet()
        for (site in AnimeSites.order(settings.source).filter { it.id != main }) for (query in request.titles) {
            val results = try {
                site.search(query)
            } catch (_: IOException) {
                continue
            }
            for (show in results.take(BACKUP_CANDIDATES)) {
                if (mal == null && plain(show.title) !in names && show.otherTitle?.let(::plain) !in names) continue
                val id = AnimeSites.sourceId(site.id, show.url)
                val episodes = try {
                    episodes(id)
                } catch (_: IOException) {
                    continue
                }
                if (mal != null && episodes.none { it.mal == mal }) continue
                history.setBackup(request.mediaId, id)
                return id
            }
        }
        return null
    }

    private fun plain(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    fun download(request: AnimePlayerRequest, episodes: List<SourceEpisode>, audio: AudioVersion) {
        if (episodes.isEmpty()) {
            app.showToast("Nothing to download")
            return
        }
        if (settings.downloadOnWifiOnly && app.onMeteredNetwork()) {
            app.showToast("Downloads are set to Wi-Fi only")
            return
        }
        AnimeDownloads.enqueue(app, offline, request, episodes, settings.quality) { resolve(request, it, audio) }
        app.showToast(if (episodes.size == 1) "Downloading episode ${episodeLabel(episodes.first())}" else "Downloading ${episodes.size} episodes")
    }

    fun sendProgress(mediaId: Int, episode: Int) {
        pending.add(mediaId, episode)
        flushPending()
    }

    fun flushPending() {
        if (!loggedIn) return
        scope.launch {
            sending.withLock {
                var changed = false
                val done = withContext(Dispatchers.IO) {
                    pending.flush { mediaId, episode ->
                        val total = aniList.api.progress(mediaId).episodes
                        if (aniList.api.saveEpisode(mediaId, episode, total) != null) changed = true
                    }
                }
                if (changed) {
                    toldWaiting = false
                    aniList.navigator.listsChanged()
                    val single = done.values.singleOrNull()
                    app.showToast(if (single != null) "AniList updated: episode $single watched" else "AniList updated")
                }
                if (withContext(Dispatchers.IO) { pending.all().isNotEmpty() } && !toldWaiting) {
                    toldWaiting = true
                    app.showToast("Progress saved. It goes to AniList once it can be reached")
                }
            }
        }
    }

    fun watchedHere(mediaId: Int, episode: Int) {
        AnimeDownloads.background { history.markWatched(mediaId, episode) }
    }

    companion object {
        private const val BACKUP_CANDIDATES = 4
    }
}

internal val LocalAnime = staticCompositionLocalOf<AnimeUi> { error("No anime") }

private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
internal fun rememberAnimeUi(aniList: AniListUi): AnimeUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val ui = remember {
        AnimeUi(
            context,
            aniList,
            scope,
            savedPlayer = { saved?.let { runCatching { requestJson.decodeFromString<AnimePlayerRequest>(it) }.getOrNull() } },
            savePlayer = { request -> saved = request?.let { requestJson.encodeToString(it) } },
        )
    }
    LaunchedEffect(aniList.session.state) { ui.flushPending() }
    DisposableEffect(ui) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = ui.flushPending()
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    return ui
}
