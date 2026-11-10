package com.aniliphy.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ofKind
import com.aniliphy.novels.BookState
import com.aniliphy.novels.ChapterCounting
import com.aniliphy.novels.ChapterRules
import com.aniliphy.novels.ImportResult
import com.aniliphy.novels.EpubBook
import com.aniliphy.core.Sources
import com.aniliphy.source.NovelWork
import com.aniliphy.novels.NovelCustomStore
import com.aniliphy.novels.NovelFiles
import com.aniliphy.novels.NovelLibrary
import com.aniliphy.novels.NovelProgressStore
import com.aniliphy.novels.NovelSeries
import com.aniliphy.novels.NovelSettings
import com.aniliphy.novels.PdfBook
import com.aniliphy.novels.PendingProgress
import com.aniliphy.novels.VolumeEntry
import com.aniliphy.novels.VolumeFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
internal data class ReaderRequest(
    val series: String,
    val title: String,
    val mediaId: Int? = null,
    val totalChapters: Int? = null,
    val volumes: List<VolumeFile>,
    val index: Int,
    val entry: VolumeEntry = VolumeEntry.Resume,
    val mediaStatus: String? = null,
)

internal sealed interface VolumeSearch {
    val title: String

    data class Searching(override val title: String) : VolumeSearch
    data class Found(override val title: String, val work: NovelWork, val site: String) : VolumeSearch
    data class Nothing(override val title: String, val failed: Boolean, val noSources: Boolean = false) : VolumeSearch
}

internal class NovelsUi(
    context: Context,
    val aniList: AniListUi,
    private val scope: CoroutineScope,
    private val savedReader: () -> ReaderRequest?,
    private val saveReader: (ReaderRequest?) -> Unit,
) {
    private val app = context.applicationContext
    val settings = NovelSettings.get(app)
    val files = NovelFiles(app)
    val progress = NovelProgressStore(File(app.filesDir, "novel_progress.json"))
    val customs = NovelCustomStore(File(app.filesDir, "novels"))
    private val pending = PendingProgress(File(app.filesDir, "novel_pending.json"))
    private val sending = Mutex()
    private var toldWaiting = false

    var finding by mutableStateOf(false)
        private set

    var series by mutableStateOf<List<NovelSeries>>(emptyList())
        private set

    var repeats by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set

    var missing by mutableStateOf<List<Media>>(emptyList())
        private set
    var folderFiles by mutableStateOf<List<VolumeFile>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var progressVersion by mutableStateOf(0)
        private set

    var openSeries by mutableStateOf<String?>(null)
    var volumeSearch by mutableStateOf<VolumeSearch?>(null)

    val reader: ReaderRequest? get() = savedReader()

    private var job: Job? = null

    val loggedIn: Boolean get() = aniList.session.state is SessionState.LoggedIn

    fun refresh() {
        flushPending()
        job?.cancel()
        loading = true
        error = null
        job = scope.launch {
            val folder = settings.folderUri
            val entries = withContext(Dispatchers.Default) { listTitles() }
            if (entries != null) repeats = entries.filter { it.repeat > 0 }.associate { it.media.id to it.repeat }
            val list = entries.orEmpty().map { it.media }
            var found = withContext(Dispatchers.IO) { if (folder.isEmpty()) emptyList() else files.cachedIndex() }
            if (found.isNotEmpty()) publish(found, list)
            try {
                found = withContext(Dispatchers.IO) { files.scan(folder) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "Could not read the folder. Choose it again in the Light Novel settings."
            }
            publish(found, list)
            loading = false
        }
    }

    private suspend fun listTitles(): List<ListEntry>? {
        val id = (aniList.session.state as? SessionState.LoggedIn)?.viewer?.id ?: return null
        return try {
            aniList.api.mangaList(id).value.ofKind(MediaKind.LightNovel)
        } catch (_: AniListException) {
            null
        }
    }

    private suspend fun publish(found: List<VolumeFile>, list: List<Media>) {
        val format = settings.preferredFormat
        val (grouped, chosen) = withContext(Dispatchers.Default) {
            val chosen = NovelLibrary.chooseFormats(found, format)
            NovelLibrary.group(chosen, list, customs.customs(), customs.links()) to chosen
        }
        folderFiles = chosen
        series = grouped
        val linked = grouped.mapNotNull { it.mediaId }.toSet()
        missing = list.filter { it.id !in linked }
    }

    var onShowAniList: () -> Unit = {}

    fun aniListRequested() = onShowAniList()

    fun progressChanged() {
        progressVersion++
    }

    fun open(novel: NovelSeries, volume: Int? = null, entry: VolumeEntry = VolumeEntry.Resume) {
        if (novel.volumes.isEmpty()) return
        val id = novel.mediaId
        val firstTime = volume == null && entry == VolumeEntry.Resume && id != null && loggedIn &&
            settings.counting != ChapterCounting.Manual && progress.lastRead(novel.key) == null
        if (!firstTime || id == null) {
            show(novel, volume, entry)
            return
        }
        finding = true
        scope.launch {
            try {
                findPlace(novel, id)
            } finally {
                finding = false
            }
            show(novel, null, entry)
        }
    }

    private suspend fun findPlace(novel: NovelSeries, mediaId: Int) {
        val current = try {
            withTimeoutOrNull(FIND_TIMEOUT_MS) { aniList.api.progress(mediaId) }
        } catch (_: AniListException) {
            null
        } ?: return
        if (current.progress <= 0 || current.status == ListStatus.COMPLETED) return
        val wanted = current.progress + 1
        val place = withContext(Dispatchers.IO) {
            val states = novel.volumes.map { file ->
                try {
                    countedVolume(novel.key, file)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    BookState()
                }
            }
            val found = ChapterRules.locate(wanted, states.map { Triple(it.segments, it.length, it.starts) })
            if (found != null) {
                val file = novel.volumes[found.first]
                progress.save(novel.key, file.name, states[found.first].copy(chapter = found.second, position = 0.0), title = novel.title)
            }
            found
        }
        if (place == null) app.showToast("Chapter $wanted isn't in these volumes")
    }

    private fun readTitles(file: VolumeFile): Pair<List<String>, List<Int>?> {
        val copy = files.localCopy(file)
        return if (file.isEpub) {
            EpubBook(copy).use { book -> book.chapters.map { it.title } to book.chapterStarts }
        } else {
            PdfBook.open(app, copy, files.chaptersCache(file)).let { book ->
                try {
                    book.chapters.map { it.title } to null
                } finally {
                    book.close()
                }
            }
        }
    }

    fun countedVolume(series: String, file: VolumeFile): BookState {
        val known = progress.state(series, file.name)
        if (known.length != 0) return known
        val (titles, starts) = readTitles(file)
        val runs = ChapterRules.detectRuns(titles)
        val state = BookState(segments = runs, length = titles.size, auto = runs.isNotEmpty(), starts = starts)
        progress.save(series, file.name, state, touch = false)
        return state
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

    fun completeOnAniList(mediaId: Int, read: Int) {
        scope.launch {
            try {
                aniList.api.completeChapters(mediaId, read)
                aniList.navigator.listsChanged()
                app.showToast("Marked as completed on AniList")
            } catch (e: AniListException) {
                app.showToast(aniList.describe(e))
            }
        }
    }

    fun link(novel: NovelSeries, mediaId: Int?, fileTitle: String) {
        customs.link(fileTitle, mediaId)
        progress.move(novel.key, mediaId?.toString() ?: (NovelLibrary.LOCAL_PREFIX + NovelLibrary.slug(fileTitle)))
        openSeries = null
        refresh()
    }

    fun linkedFileTitle(mediaId: Int): String? = customs.links().entries.firstOrNull { it.value == mediaId }?.key

    suspend fun importDesktopProgress(uri: Uri): String {
        refresh()
        job?.join()
        val message = withContext(Dispatchers.IO) {
            val text = try {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: IOException) {
                null
            } ?: return@withContext "Could not read that file"
            try {
                val result = importProgressText(text)
                buildString {
                    append(if (result.imported == 1) "Imported 1 book" else "Imported ${result.imported} books")
                    if (result.kept > 0) append(", kept ${result.kept} read more recently here")
                }
            } catch (_: IllegalArgumentException) {
                "That isn't a novel_progress.json file"
            }
        }
        progressChanged()
        return message
    }

    private fun importProgressText(text: String): ImportResult {
        val here = series.flatMap { novel -> novel.volumes.map { it.name to (novel to it) } }.toMap()
        return progress.import(
            text,
            seriesFor = { desktop, book -> here[book]?.first?.key ?: desktop },
            prepare = { book, state ->
                val file = here[book]?.second
                val titles = file?.let { runCatching { readTitles(it) }.getOrNull() }
                when {
                    titles == null -> state
                    state.auto -> state.copy(
                        segments = ChapterRules.detectRuns(titles.first),
                        length = titles.first.size,
                        starts = titles.second,
                    )
                    else -> state.copy(length = titles.first.size, starts = titles.second)
                }
            },
        )
    }

    fun importSynced(text: String) {
        runCatching<ImportResult> { importProgressText(text) }
    }

    suspend fun prepareForSync() {
        refresh()
        job?.join()
    }

    private fun show(novel: NovelSeries, volume: Int?, entry: VolumeEntry) {
        val names = novel.volumes.map { it.name }
        saveReader(
            ReaderRequest(
                novel.key,
                novel.title,
                novel.mediaId,
                novel.media?.chapters,
                novel.volumes,
                (volume ?: progress.pickVolume(novel.key, names)).coerceIn(names.indices),
                entry,
                novel.media?.status,
            ),
        )
    }

    fun changeVolume(request: ReaderRequest, index: Int, entry: VolumeEntry) {
        saveReader(request.copy(index = index, entry = entry))
    }

    fun closeReader() {
        saveReader(null)
        progressChanged()
    }

    fun readMedia(media: Media) {
        scope.launch {
            val novel = series.firstOrNull { it.mediaId == media.id } ?: run {
                job?.join()
                val matching = withContext(Dispatchers.Default) {
                    val chosen = NovelLibrary.chooseFormats(
                        withContext(Dispatchers.IO) { runCatching { files.scan(settings.folderUri) }.getOrDefault(files.cachedIndex()) },
                        settings.preferredFormat,
                    )
                    NovelLibrary.group(chosen, listOf(media), customs.customs(), customs.links())
                }
                matching.firstOrNull { it.mediaId == media.id }
            }
            if (novel != null && novel.volumes.isNotEmpty()) {
                open(novel.copy(media = novel.media ?: media))
            } else {
                searchVolumes(listOfNotNull(media.title.english, media.title.romaji).filter { it.isNotBlank() }, media.title.display)
            }
        }
    }

    fun searchVolumes(titles: List<String>, shown: String) {
        val sites = Sources.novels
        if (sites.isEmpty()) {
            volumeSearch = VolumeSearch.Nothing(shown, failed = false, noSources = true)
            return
        }
        volumeSearch = VolumeSearch.Searching(shown)
        scope.launch {
            var failed = false
            val work = withContext(Dispatchers.IO) {
                sites.flatMap { site -> titles.map { site to it } }.firstNotNullOfOrNull { (site, title) ->
                    try {
                        site.find(title)?.let { it to site.name }
                    } catch (_: IOException) {
                        failed = true
                        null
                    } catch (_: kotlinx.serialization.SerializationException) {
                        failed = true
                        null
                    }
                }
            }
            if (volumeSearch is VolumeSearch.Searching) {
                volumeSearch = if (work != null) VolumeSearch.Found(shown, work.first, work.second) else VolumeSearch.Nothing(shown, failed)
            }
        }
    }
}

private const val FIND_TIMEOUT_MS = 5_000L

internal fun chaptersText(count: Int) = if (count == 1) "1 chapter" else "$count chapters"

internal val LocalNovels = staticCompositionLocalOf<NovelsUi> { error("No novels") }

private val requestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
internal fun rememberNovelsUi(aniList: AniListUi): NovelsUi {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by rememberSaveable { mutableStateOf<String?>(null) }
    val ui = remember {
        NovelsUi(
            context,
            aniList,
            scope,
            savedReader = { saved?.let { runCatching { requestJson.decodeFromString<ReaderRequest>(it) }.getOrNull() } },
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
