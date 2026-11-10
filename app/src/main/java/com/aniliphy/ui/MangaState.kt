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
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ProgressRules
import com.aniliphy.anilist.AniListSync
import com.aniliphy.anilist.SessionState
import com.aniliphy.manga.MangaHistory
import com.aniliphy.manga.MangaRecord
import com.aniliphy.manga.NewChapterCheck
import com.aniliphy.manga.NewChapters
import com.aniliphy.manga.MangaRules
import com.aniliphy.manga.MangaSettings
import com.aniliphy.source.MangaSite
import com.aniliphy.manga.OfflineManga
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.source.SourceChapter
import com.aniliphy.manga.sources.SourceHttp
import com.aniliphy.source.SourceSeries
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
internal data class MangaReaderRequest(
    val mediaId: Int,
    val title: String,
    val source: String,
    val titles: List<String> = emptyList(),
    val cover: String? = null,
    val totalChapters: Int? = null,
    val webtoon: Boolean = false,
)

internal data class MangaTitle(
    val mediaId: Int,
    val shown: String,
    val titles: List<String>,
    val cover: String?,
    val total: Int?,
    val webtoon: Boolean,
)

internal sealed interface SourcePick {
    val title: MangaTitle

    data class Searching(override val title: MangaTitle, val source: MangaSite) : SourcePick
    data class Results(override val title: MangaTitle, val source: MangaSite, val results: List<SourceSeries>) : SourcePick
    data class Nothing(override val title: MangaTitle, val failed: Boolean, val tried: List<MangaSite>) : SourcePick
}

internal data class DownloadProgress(val done: Int, val total: Int)

internal data class StartQuestion(val title: String, val message: String, val answer: CompletableDeferred<Boolean>)

internal object MangaCache {
    private const val CHAPTERS_FOR_MS = 10 * 60_000L
    private val chapterLists = ConcurrentHashMap<String, Pair<Long, List<SourceChapter>>>()

    val pages = ConcurrentHashMap<String, List<String>>()

    fun chapters(sourceId: String): List<SourceChapter>? =
        chapterLists[sourceId]?.takeIf { System.currentTimeMillis() - it.first < CHAPTERS_FOR_MS }?.second

    fun keepChapters(sourceId: String, chapters: List<SourceChapter>) {
        if (chapters.isNotEmpty()) chapterLists[sourceId] = System.currentTimeMillis() to chapters
    }
}

internal object MangaDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val one = Mutex()

    val active = mutableStateMapOf<String, DownloadProgress>()

    var version by mutableIntStateOf(0)
        private set

    fun key(mediaId: Int, chapterId: String) = "$mediaId/$chapterId"

    fun background(work: () -> Unit) {
        scope.launch { runCatching(work) }
    }

    fun delete(offline: OfflineManga, mediaId: Int, chapterId: String) {
        scope.launch {
            runCatching { offline.delete(mediaId, chapterId) }
            withContext(Dispatchers.Main) { version++ }
        }
    }

    fun enqueue(app: Context, offline: OfflineManga, request: MangaReaderRequest, chapters: List<SourceChapter>) {
        val (source, _) = MangaSites.parse(request.source) ?: return
        val site = MangaSites.site(source)
        val wanted = chapters.filter { key(request.mediaId, it.id) !in active }
        if (wanted.isEmpty()) return
        wanted.forEach { active[key(request.mediaId, it.id)] = DownloadProgress(0, 0) }
        scope.launch {
            var saved = 0
            var failed: SourceChapter? = null
            for (chapter in wanted) {
                val key = key(request.mediaId, chapter.id)
                one.withLock {
                    try {
                        val pages = site.pages(chapter)
                        withContext(Dispatchers.Main) { active[key] = DownloadProgress(0, pages.size) }
                        offline.download(request.mediaId, request.source, request.title, chapter, pages, site.imageHeaders) { done ->
                            scope.launch(Dispatchers.Main) { if (key in active) active[key] = DownloadProgress(done, pages.size) }
                        }
                        saved++
                    } catch (_: IOException) {
                        failed = chapter
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
                    failed != null -> "Could not download ${failed!!.name}" + if (saved > 0) " (${saved} saved)" else ""
                    saved == 1 -> "Downloaded ${wanted.first().name}"
                    else -> "Downloaded $saved chapters"
                }
                app.showToast(message)
            }
        }
    }
}

internal class MangaUi(
    context: Context,
    val aniList: AniListUi,
    private val scope: CoroutineScope,
    private val savedReader: () -> MangaReaderRequest?,
    private val saveReader: (MangaReaderRequest?) -> Unit,
) {
    private val app = context.applicationContext
    val settings = MangaSettings.get(app)
    val history = MangaHistory(File(app.filesDir, "manga_history.json"))
    val offline = OfflineManga(File(app.filesDir, OFFLINE_DIR), SourceHttp::bytes)
    val newChapters = NewChapters(File(app.filesDir, "manga_new_chapters.json"))
    private val pending = PendingProgress(File(app.filesDir, "manga_pending.json"))
    private val sending = Mutex()
    private var toldWaiting = false
    private var searchJob: Job? = null

    var pick by mutableStateOf<SourcePick?>(null)

    var askStart by mutableStateOf<StartQuestion?>(null)
        private set

    var starting by mutableStateOf(false)
        private set

    var historyVersion by mutableIntStateOf(0)
        private set

    fun historyChanged() {
        historyVersion++
    }

    private var checkingNew = false

    val reader: MangaReaderRequest? get() = savedReader()

    /** Set by the app: switches to the AniList tab. */
    var onShowAniList: () -> Unit = {}

    /** Opens the title's AniList page, without changing anything on the account. */
    fun showOnAniList(mediaId: Int) {
        aniList.navigator.open(Route.Media(mediaId))
        onShowAniList()
    }

    val loggedIn: Boolean get() = aniList.session.state is SessionState.LoggedIn

    fun readMedia(media: Media) {
        val title = MangaTitle(
            mediaId = media.id,
            shown = media.title.display,
            titles = listOfNotNull(media.title.english, media.title.romaji, media.title.native)
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            cover = media.coverImage.best,
            total = media.chapters,
            webtoon = MangaRules.isWebtoonOrigin(media.countryOfOrigin),
        )
        if (title.titles.isEmpty()) {
            app.showToast("No title to search")
            return
        }
        begin(title)
    }

    fun continueReading(mediaId: Int, record: MangaRecord) {
        val shown = record.title ?: return
        begin(MangaTitle(mediaId, shown, record.titles.ifEmpty { listOf(shown) }, record.cover, record.total, record.webtoon))
    }

    private fun begin(title: MangaTitle) {
        if (starting) return
        starting = true
        scope.launch {
            try {
                startOnAniList(title)
            } finally {
                starting = false
            }
            val known = withContext(Dispatchers.IO) { history.get(title.mediaId) }?.source
            if (MangaSites.parse(known) != null) open(title, known!!) else search(title)
        }
    }

    private suspend fun startOnAniList(title: MangaTitle) {
        if (!loggedIn) return
        val mediaId = title.mediaId
        try {
            val current = aniList.api.progress(mediaId)
            val resume = MangaRules.shouldResume(current)
            val change = if (resume) null else ProgressRules.start(current)
            if (!resume && change == null) return
            val status = if (resume) ListStatus.CURRENT else change!!.status!!
            val label = ListStatus.label(status, MediaKind.Manga)
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

    fun seenChapters(mediaId: Int, source: String, chapters: List<SourceChapter>) {
        val latest = MangaRules.latest(chapters) ?: return
        MangaDownloads.background { newChapters.seen(mediaId, source, latest) }
    }

    fun checkNewChapters(records: Map<Int, MangaRecord>) {
        if (checkingNew) return
        checkingNew = true
        scope.launch {
            try {
                val found = withContext(Dispatchers.IO) {
                    NewChapterCheck.refresh(records, newChapters) { sourceId, chapters -> MangaCache.keepChapters(sourceId, chapters) }
                }
                if (found) historyVersion++
            } finally {
                checkingNew = false
            }
        }
    }

    fun search(title: MangaTitle, only: MangaSite? = null) {
        searchJob?.cancel()
        val order = only?.let { listOf(it) } ?: MangaSites.order(settings.source)
        if (order.isEmpty()) {
            pick = SourcePick.Nothing(title, failed = false, tried = emptyList())
            return
        }
        pick = SourcePick.Searching(title, order.first())
        searchJob = scope.launch {
            var failed = false
            for (source in order) {
                pick = SourcePick.Searching(title, source)
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
                    pick = SourcePick.Results(title, source, results)
                    return@launch
                }
            }
            pick = SourcePick.Nothing(title, failed, order)
        }
    }

    fun cancelPick() {
        searchJob?.cancel()
        pick = null
    }

    fun choose(title: MangaTitle, source: MangaSite, series: SourceSeries) {
        val id = MangaSites.sourceId(source.id, series.url)
        pick = null
        scope.launch {
            withContext(Dispatchers.IO) { history.setSource(title.mediaId, id, title.shown, title.cover) }
            open(title, id)
        }
    }

    private fun open(title: MangaTitle, sourceId: String) {
        saveReader(
            MangaReaderRequest(
                mediaId = title.mediaId,
                title = title.shown,
                source = sourceId,
                titles = title.titles,
                cover = title.cover,
                totalChapters = title.total,
                webtoon = title.webtoon,
            ),
        )
    }

    fun titleOf(request: MangaReaderRequest) = MangaTitle(
        request.mediaId,
        request.title,
        request.titles.ifEmpty { listOf(request.title) },
        request.cover,
        request.totalChapters,
        request.webtoon,
    )

    fun forgetSource(request: MangaReaderRequest) {
        scope.launch {
            withContext(Dispatchers.IO) { history.setSource(request.mediaId, null) }
            closeReader()
            app.showToast("Source forgotten. Read searches again next time")
        }
    }

    fun closeReader() {
        saveReader(null)
        historyVersion++
    }

    fun download(request: MangaReaderRequest, chapters: List<SourceChapter>) {
        if (chapters.isEmpty()) {
            app.showToast("Nothing to download")
            return
        }
        if (settings.downloadOnWifiOnly && app.onMeteredNetwork()) {
            app.showToast("Downloads are set to Wi-Fi only")
            return
        }
        MangaDownloads.enqueue(app, offline, request, chapters)
        app.showToast(if (chapters.size == 1) "Downloading ${chapters.first().name}" else "Downloading ${chapters.size} chapters")
    }

    fun sendProgress(mediaId: Int, chapters: Int) {
        pending.add(mediaId, chapters)
        flushPending()
    }

    fun flushPending() {
        if (!loggedIn) return
        scope.launch {
            sending.withLock {
                var changed = false
                val done = withContext(Dispatchers.IO) {
                    pending.flush { mediaId, chapters ->
                        if (aniList.api.saveChapter(mediaId, chapters, null) != null) changed = true
                    }
                }
                if (changed) {
                    toldWaiting = false
                    aniList.navigator.listsChanged()
                    val single = done.values.singleOrNull()
                    app.showToast(if (single != null) "AniList updated: ${chaptersText(single)} read" else "AniList updated")
                }
                if (withContext(Dispatchers.IO) { pending.all().isNotEmpty() } && !toldWaiting) {
                    toldWaiting = true
                    app.showToast("Progress saved. It goes to AniList once it can be reached")
                }
            }
        }
    }

    companion object {

        const val OFFLINE_DIR = "offline_manga"
    }
}

internal fun Context.onMeteredNetwork(): Boolean =
    getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

internal val LocalManga = staticCompositionLocalOf<MangaUi> { error("No manga") }

private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
internal fun rememberMangaUi(aniList: AniListUi): MangaUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val ui = remember {
        MangaUi(
            context,
            aniList,
            scope,
            savedReader = { saved?.let { runCatching { requestJson.decodeFromString<MangaReaderRequest>(it) }.getOrNull() } },
            saveReader = { request -> saved = request?.let { requestJson.encodeToString(it) } },
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
