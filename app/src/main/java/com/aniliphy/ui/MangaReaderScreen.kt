@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import coil3.size.Precision
import coil3.size.Size
import com.aniliphy.ReaderKeys
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListSync
import com.aniliphy.core.AdvanceMode
import com.aniliphy.core.TapZones
import com.aniliphy.manga.DownloadDefault
import com.aniliphy.manga.MangaRules
import com.aniliphy.manga.PageFit
import com.aniliphy.manga.ReaderBackground
import com.aniliphy.manga.ReadingDirection
import com.aniliphy.manga.ReadingMode
import com.aniliphy.manga.StartPlace
import com.aniliphy.source.MangaSite
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.manga.sources.MissingMangaSite
import com.aniliphy.core.MissingSourceException
import com.aniliphy.source.SourceChapter
import com.aniliphy.manga.sources.SourceHttp
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private class OpenedManga(
    val site: MangaSite,
    val chapters: List<SourceChapter>,
    val offlineOnly: Boolean,
    /** The source's extension isn't installed. */
    val missing: Boolean,
    val start: StartPlace,
)

private suspend fun openManga(manga: MangaUi, request: MangaReaderRequest): OpenedManga {
    val (source, url) = MangaSites.parse(request.source) ?: throw IOException("This source isn't known. Choose another one.")
    val site = MangaSites.find(source) ?: MissingMangaSite(source)
    val sourceName = site.name
    var offlineOnly = false
    val chapters = try {
        MangaCache.chapters(request.source) ?: site.chapters(url).also {
            manga.offline.rememberChapters(request.mediaId, request.source, it)
            MangaCache.keepChapters(request.source, it)
        }
    } catch (e: IOException) {
        offlineOnly = true
        manga.offline.cachedChapters(request.mediaId, request.source).ifEmpty {
            throw if (e is MissingSourceException) e else IOException("Could not reach $sourceName", e)
        }
    }
    if (chapters.isEmpty()) throw IOException("$sourceName has no chapters of this series")
    val progress = if (manga.loggedIn) {
        withTimeoutOrNull(FIND_TIMEOUT_MS) {
            try {
                manga.aniList.api.progress(request.mediaId)
            } catch (_: AniListException) {
                null
            }
        }
    } else {
        null
    }
    val start = MangaRules.start(chapters, progress, manga.history.get(request.mediaId))
    return OpenedManga(site, chapters, offlineOnly, missing = site is MissingMangaSite, start = start)
}

private sealed interface ChapterPages {
    data object Loading : ChapterPages
    data object NeedsData : ChapterPages
    data class Failed(val message: String) : ChapterPages
    data class Ready(val chapterId: String, val images: List<Any>, val local: Boolean) : ChapterPages
}

private interface PageMover {
    fun move(direction: Int)
    fun jump(page: Int)
}

private enum class MangaSheet { Chapters, Download, Source, Options }

@Composable
internal fun MangaReaderScreen(request: MangaReaderRequest, onClose: () -> Unit) {
    val manga = LocalManga.current
    val settings = manga.settings
    val theme = LocalAppTheme.current
    BackHandler(onBack = onClose)
    ReaderWindow(settings.keepScreenOn, settings.orientation)

    val paper = when (settings.background) {
        ReaderBackground.Black -> Color.Black
        ReaderBackground.White -> Color.White
        ReaderBackground.Theme -> theme.background
    }
    val ink = when (settings.background) {
        ReaderBackground.Black -> Color.White
        ReaderBackground.White -> Color.Black
        ReaderBackground.Theme -> theme.foreground
    }
    var attempt by remember { mutableIntStateOf(0) }
    val opened by produceState<Result<OpenedManga>?>(null, request, attempt) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                Result.success(openManga(manga, request))
            } catch (e: IOException) {
                Result.failure(e)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(paper)) {
        val outcome = opened
        val ready = outcome?.getOrNull()
        when {
            outcome == null -> LookLoading("Opening ${request.title}...", Modifier.align(Alignment.Center).padding(24.dp), color = ink)
            ready == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Hint(outcome.exceptionOrNull()?.message ?: "Could not open ${request.title}", color = ink)
                PillButton("Try again", onClick = { attempt++ })
                PillButton("Change source", onClick = { manga.search(manga.titleOf(request)) }, filled = false)
                PillButton("Close", onClick = onClose, filled = false)
            }
            else -> key(ready) { MangaReaderBody(request, ready, paper, ink, onClose) }
        }
    }
}

@Composable
private fun MangaReaderBody(request: MangaReaderRequest, opened: OpenedManga, paper: Color, ink: Color, onClose: () -> Unit) {
    val manga = LocalManga.current
    val settings = manga.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chapters = opened.chapters
    val sourceName = opened.site.name

    var chapterIndex by rememberSaveable { mutableIntStateOf(opened.start.chapter.coerceIn(chapters.indices)) }
    var page by rememberSaveable { mutableIntStateOf(opened.start.page) }
    var bars by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<MangaSheet?>(null) }
    var dataAllowed by remember { mutableStateOf(!settings.askOnMobileData) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var longStrip by remember { mutableStateOf(false) }
    var askNext by remember { mutableStateOf(false) }
    var askAniList by remember { mutableStateOf<Int?>(null) }
    var mover by remember { mutableStateOf<PageMover?>(null) }
    var chosenMode by rememberSaveable { mutableStateOf<ReadingMode?>(null) }
    val pageWidth = context.resources.displayMetrics.widthPixels
    val pageCache = MangaCache.pages
    val counted = remember { mutableStateMapOf<String, Boolean>() }
    val endHandled = remember { mutableStateMapOf<String, Boolean>() }
    val shifted = remember { mutableStateMapOf<String, Boolean>() }
    val downloaded by produceState(emptySet<String>(), MangaDownloads.version) {
        value = withContext(Dispatchers.IO) { manga.offline.downloadedIds(request.mediaId) }
    }
    val chapter = chapters[chapterIndex]
    val hasNext = chapterIndex < chapters.lastIndex
    SystemBars(bars)

    LaunchedEffect(opened) {
        if (!opened.offlineOnly) manga.seenChapters(request.mediaId, request.source, chapters)
        if (opened.offlineOnly) {
            context.showToast(
                if (opened.missing) "The \"$sourceName\" source isn't installed. Showing downloaded chapters"
                else "$sourceName can't be reached. Showing downloaded chapters",
            )
        }
    }

    val pages by produceState<ChapterPages>(ChapterPages.Loading, chapterIndex, loadAttempt, dataAllowed) {
        value = ChapterPages.Loading
        value = withContext(Dispatchers.IO) {
            manga.offline.localPages(request.mediaId, chapter.id)?.let { return@withContext ChapterPages.Ready(chapter.id, it, local = true) }
            if (!dataAllowed && context.onMeteredNetwork()) return@withContext ChapterPages.NeedsData
            try {
                val urls = pageCache[chapter.url] ?: opened.site.pages(chapter).also { if (it.isNotEmpty()) pageCache[chapter.url] = it }
                if (urls.isEmpty()) ChapterPages.Failed("$sourceName has no pages for ${chapter.name}") else ChapterPages.Ready(chapter.id, urls.map { pageModel(opened.site, it) }, local = false)
            } catch (_: IOException) {
                ChapterPages.Failed("Could not load ${chapter.name} from $sourceName")
            }
        }
    }
    val current = pages.let { if (it is ChapterPages.Ready && it.chapterId != chapter.id) ChapterPages.Loading else it }
    val ready = current as? ChapterPages.Ready
    val pageCount = ready?.images?.size ?: 0

    val webtoon = chosenMode?.let { it == ReadingMode.Webtoon }
        ?: (settings.mode == ReadingMode.Webtoon || (settings.webtoonForLongStrips && (request.webtoon || longStrip)))
    val twoPages = !webtoon && (chosenMode ?: settings.mode) == ReadingMode.TwoPages
    val shownMode = when {
        webtoon -> ReadingMode.Webtoon
        twoPages -> ReadingMode.TwoPages
        else -> ReadingMode.Page
    }
    val rtl = settings.direction == ReadingDirection.RightToLeft

    fun leave(left: SourceChapter) {
        if (settings.deleteAfterReading && counted[left.id] == true && left.id in downloaded) {
            MangaDownloads.delete(manga.offline, request.mediaId, left.id)
        }
    }
    fun goChapter(index: Int, atPage: Int = 0) {
        if (index !in chapters.indices) return
        leave(chapter)
        askNext = false
        page = atPage
        chapterIndex = index
    }
    fun nextChapter() {
        if (hasNext) goChapter(chapterIndex + 1) else context.showToast("That's the latest chapter on $sourceName")
    }
    fun previousChapter(atEnd: Boolean) {
        if (chapterIndex > 0) goChapter(chapterIndex - 1, if (atEnd) -1 else 0) else context.showToast("That's the first chapter")
    }
    // The forum thread for the chapter just read, found when it counts as read.
    var discussion by remember { mutableStateOf<Pair<Int, com.aniliphy.anilist.ForumThread>?>(null) }
    fun markRead(read: SourceChapter) {
        if (counted[read.id] == true) return
        counted[read.id] = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.aniliphy.core.ActivityLog.mangaChapter(context) }
        val number = MangaRules.aniListChapter(read, request.totalChapters) ?: return
        scope.launch {
            episodeThread(manga.aniList.api, request.mediaId, number, chapter = true)?.let { discussion = number to it }
        }
        LookMoment.fire(episode = false, number = number, total = request.totalChapters, next = chapters.indexOfFirst { it.id == read.id }.takeIf { it >= 0 }?.let { chapters.getOrNull(it + 1) }?.name)
        if (!manga.loggedIn) return
        if (settings.aniListSync == AniListSync.Automatic) {
            manga.sendProgress(request.mediaId, number)
        } else {
            scope.launch {
                val known = try {
                    manga.aniList.api.progress(request.mediaId).progress
                } catch (_: AniListException) {
                    null
                }
                if (known == null || known < number) askAniList = maxOf(askAniList ?: 0, number)
            }
        }
    }
    fun onPage(shown: Int, lastShown: Int) {
        page = shown
        if (MangaRules.counts(settings.readMark, lastShown, pageCount, atEnd = false)) markRead(chapter)
    }
    fun onEnd() {
        markRead(chapter)
        if (!hasNext || endHandled[chapter.id] == true) return
        endHandled[chapter.id] = true
        if (settings.nextChapter == AdvanceMode.Automatic) nextChapter() else askNext = true
    }
    val onTap: (Float, Boolean) -> Unit = { share, paged ->
        val left = share < TAP_SIDE
        val right = share > 1 - TAP_SIDE
        val zones = settings.tapZones
        val forwardSide = if (paged && rtl) left else right
        when {
            bars || sheet != null -> {
                sheet = null
                bars = false
            }
            zones == TapZones.Off || (!left && !right) -> bars = true
            forwardSide != (zones == TapZones.Inverted) -> mover?.move(1)
            else -> mover?.move(-1)
        }
    }

    LaunchedEffect(chapterIndex, page, pageCount) {
        if (pageCount == 0 || page < 0) return@LaunchedEffect
        delay(SAVE_DELAY_MS)
        withContext(Dispatchers.IO) {
            manga.history.savePlace(request.mediaId, chapter, page, pageCount, request.title, request.cover, total = request.totalChapters, titles = request.titles, webtoon = request.webtoon)
        }
    }
    val latest by rememberUpdatedState(Triple(chapter, page, pageCount))
    DisposableEffect(Unit) {
        onDispose {
            val (last, at, count) = latest
            if (count > 0 && at >= 0) {
                MangaDownloads.background {
                    manga.history.savePlace(request.mediaId, last, at, count, request.title, request.cover, total = request.totalChapters, titles = request.titles, webtoon = request.webtoon)
                }
            }
            leave(last)
        }
    }
    LaunchedEffect(page, ready, chapterIndex) {
        val images = ready?.images ?: return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        val ahead = settings.preload.pages
        if (!ready.local) {
            val from = page.coerceAtLeast(0) + 1
            (from until minOf(images.size, from + ahead)).forEach { index ->
                loader.enqueue(pageRequest(context, images[index], opened.site.imageHeaders, pageWidth.takeIf { webtoon }))
            }
        }
        suspend fun preload(near: SourceChapter?, pick: (List<String>) -> List<String>) {
            if (near == null || near.id in downloaded) return
            val urls = pageCache[near.url] ?: withContext(Dispatchers.IO) {
                runCatching { opened.site.pages(near) }.getOrNull()?.takeIf { it.isNotEmpty() }?.also { pageCache[near.url] = it }
            } ?: return
            pick(urls).forEach { loader.enqueue(pageRequest(context, pageModel(opened.site, it), opened.site.imageHeaders, pageWidth.takeIf { webtoon })) }
        }
        if (page >= images.size - 3) preload(chapters.getOrNull(chapterIndex + 1)) { it.take(ahead) }
        if (page in 0..2) preload(chapters.getOrNull(chapterIndex - 1)) { it.takeLast(ahead) }
    }
    DisposableEffect(settings.volumeButtons) {
        ReaderKeys.onVolume = if (settings.volumeButtons) {
            { up ->
                mover?.move(if (up) -1 else 1)
                true
            }
        } else {
            null
        }
        onDispose { ReaderKeys.onVolume = null }
    }
    BackHandler(enabled = sheet != null) { sheet = null }

    Box(Modifier.fillMaxSize().background(paper)) {
        when (val state = current) {
            ChapterPages.Loading -> LookLoading("Loading ${chapter.name}...", Modifier.align(Alignment.Center), color = ink)
            ChapterPages.NeedsData -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Hint("You're on mobile data. ${chapter.name} isn't downloaded.", color = ink)
                PillButton("Read anyway", onClick = { dataAllowed = true })
                PillButton("Close", onClick = onClose, filled = false)
            }
            is ChapterPages.Failed -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Hint(state.message, color = ink)
                PillButton("Try again", onClick = { loadAttempt++ })
                PillButton("Chapters", onClick = { sheet = MangaSheet.Chapters }, filled = false)
                PillButton("Close", onClick = onClose, filled = false)
            }
            is ChapterPages.Ready -> key(chapterIndex, webtoon, twoPages) {
                val end = @Composable {
                    ChapterEnd(chapter, chapters.getOrNull(chapterIndex + 1), sourceName, ink, onNext = ::nextChapter) {
                        discussion?.let { (n, thread) -> DiscussPill(n, thread, chapter = true, onOpen = onClose) }
                    }
                }
                val previous = chapters.getOrNull(chapterIndex - 1)
                val start: (@Composable () -> Unit)? = previous?.let { before ->
                    @Composable { ChapterStart(before, ink) }
                }
                val onSize: (Int, Int, Int) -> Unit = { index, width, height ->
                    if (index < 2 && chosenMode == null && settings.webtoonForLongStrips && settings.mode != ReadingMode.Webtoon && MangaRules.isLongStrip(width, height)) {
                        longStrip = true
                    }
                }
                if (webtoon) {
                    WebtoonView(
                        images = state.images,
                        headers = opened.site.imageHeaders,
                        initial = page,
                        gap = settings.webtoonGap.dp,
                        pageWidth = pageWidth,
                        width = settings.webtoonWidth,
                        onWidth = { settings.webtoonWidth = it },
                        paper = paper,
                        onReady = { mover = it },
                        onPage = ::onPage,
                        onEnd = ::onEnd,
                        onBeforeStart = { previousChapter(atEnd = true) },
                        onPastEnd = ::nextChapter,
                        onTap = { onTap(it, false) },
                        onSize = onSize,
                        start = start,
                        end = end,
                    )
                } else {
                    PagedView(
                        images = state.images,
                        headers = opened.site.imageHeaders,
                        initial = page,
                        twoPages = twoPages,
                        shifted = shifted[chapter.id] == true,
                        rtl = rtl,
                        fit = settings.fit,
                        pageWidth = pageWidth,
                        onReady = { mover = it },
                        onPage = ::onPage,
                        onEnd = ::onEnd,
                        onBeforeStart = { previousChapter(atEnd = true) },
                        onPastEnd = ::nextChapter,
                        onTap = { onTap(it, true) },
                        onSize = onSize,
                        start = start,
                        end = end,
                    )
                }
            }
        }
        if (settings.pageIndicator && !bars && pageCount > 0) {
            LookLabel(
                "${chapter.name} · ${(page.coerceAtLeast(0) + 1).coerceAtMost(pageCount)} / $pageCount",
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 6.dp),
            )
        }
        SizedControls(settings.controlsSize) {
        if (bars) {
            MangaTopBar(
                request.title,
                chapter.name,
                onClose = onClose,
                onChapters = { sheet = MangaSheet.Chapters },
                onDownload = {
                    when (settings.downloadDefault) {
                        DownloadDefault.Current ->
                            if (chapter.id in downloaded) context.showToast("${chapter.name} is already downloaded") else manga.download(request, listOf(chapter))
                        DownloadDefault.NextFive -> manga.download(request, chapters.drop(chapterIndex + 1).filter { it.id !in downloaded }.take(5))
                        DownloadDefault.Choose -> sheet = MangaSheet.Download
                    }
                },
                onSource = { sheet = MangaSheet.Source },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            MangaBottomBar(
                label = listOfNotNull(
                    chapter.name,
                    "Chapter ${chapterIndex + 1} of ${chapters.size}",
                ).joinToString(" · "),
                page = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
                pageCount = pageCount,
                reversed = rtl && !webtoon,
                onJump = { mover?.jump(it) },
                onPrevious = { previousChapter(atEnd = false) },
                onNext = ::nextChapter,
                onOptions = { sheet = MangaSheet.Options },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        when (sheet) {
            MangaSheet.Chapters -> ReaderSheetFrame("Chapters", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                ChapterList(
                    chapters,
                    chapterIndex,
                    downloaded,
                    request.mediaId,
                    onGo = {
                        sheet = null
                        bars = false
                        if (it != chapterIndex) goChapter(it)
                    },
                    onReadUpTo = { index ->
                        sheet = null
                        bars = false
                        // AniList only ever moves forward, so marking an earlier chapter changes nothing there.
                        val number = MangaRules.aniListChapter(chapters[index], request.totalChapters)
                        if (number != null && manga.loggedIn) manga.sendProgress(request.mediaId, number)
                        if (index + 1 < chapters.size) goChapter(index + 1) else context.showToast("Marked as read")
                    },
                )
            }
            MangaSheet.Download -> ReaderSheetFrame("Download", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                DownloadList(chapters, chapterIndex, downloaded, request.mediaId, onDownload = { chosen ->
                    sheet = null
                    manga.download(request, chosen)
                }, onDelete = { MangaDownloads.delete(manga.offline, request.mediaId, it.id) })
            }
            MangaSheet.Source -> ReaderSheetFrame("Source", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                SourceSheet(sourceName, MangaSites.parse(request.source)?.second.orEmpty(), onSearch = {
                    sheet = null
                    manga.search(manga.titleOf(request))
                }, onForget = { manga.forgetSource(request) })
            }
            MangaSheet.Options -> ReaderSheetFrame("Options", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SegmentedRow("Reading mode", ReadingMode.entries, shownMode, { it.label }, {
                        chosenMode = it
                        settings.mode = it
                    }, description = "Two pages shows pages side by side")
                    if (twoPages) {
                        SegmentedRow(
                            "First page",
                            listOf(false, true),
                            shifted[chapter.id] == true,
                            { if (it) "Paired" else "Alone" },
                            { shifted[chapter.id] = it },
                            description = "Change it if pages that belong together come apart in this chapter",
                        )
                    }
                    if (webtoon) {
                        SliderRow(
                            label = "Webtoon width",
                            value = settings.webtoonWidth,
                            valueRange = MIN_WEBTOON_WIDTH..1f,
                            valueLabel = "${(settings.webtoonWidth * 100).roundToInt()}%",
                            onChange = { settings.webtoonWidth = (it * 20).roundToInt() / 20f },
                        )
                    }
                    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
                    SegmentedRow("Direction", ReadingDirection.entries, settings.direction, { it.label }, { settings.direction = it })
                    SegmentedRow("Page fit", PageFit.entries, settings.fit, { it.label }, { settings.fit = it })
                    SegmentedRow("Background", ReaderBackground.entries, settings.background, { it.label }, { settings.background = it })
                    if (manga.loggedIn) {
                        SegmentedRow("Update AniList", AniListSync.entries, settings.aniListSync, { it.label }, { settings.aniListSync = it })
                    }
                }
            }
            null -> Unit
        }
        }
    }
    if (askNext && hasNext) {
        ConfirmDialog(
            title = "Next chapter?",
            message = chapterName(chapters[chapterIndex + 1], chapterIndex + 1, spoilerChapterSeen(request.mediaId, chapter)),
            confirmLabel = "Go",
            onConfirm = ::nextChapter,
            onDismiss = { askNext = false },
        )
    }
    askAniList?.let { number ->
        ConfirmDialog(
            title = "Update AniList?",
            message = "Set ${request.title} to ${chaptersText(number)} read.",
            confirmLabel = "Update",
            onConfirm = {
                askAniList = null
                manga.sendProgress(request.mediaId, number)
            },
            onDismiss = { askAniList = null },
        )
    }
}

private const val TAP_SIDE = 0.3f
private const val SAVE_DELAY_MS = 600L
private const val FIND_TIMEOUT_MS = 5_000L
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.2f
private const val MAX_PAGE_PIXELS = 20_000_000
internal const val MIN_WEBTOON_WIDTH = 0.4f

private fun pageRequest(context: android.content.Context, model: Any, headers: Map<String, String>, width: Int? = null): ImageRequest =
    ImageRequest.Builder(context).data(model).apply {
        if (width != null && width > 0) {
            size(Size(Dimension(width), Dimension.Undefined))
            precision(Precision.INEXACT)
            maxBitmapSize(Size(width, MAX_PAGE_PIXELS / width))
            allowHardware(false)
        }
        if (model is SitePage) {
            fetcherFactory(SitePageFetcher.Factory())
            memoryCacheKey(model.url)
        }
        if (model is String) {
            httpHeaders(
                NetworkHeaders.Builder().apply {
                    set("User-Agent", SourceHttp.userAgent(model))
                    headers.forEach { (name, value) -> set(name, value) }
                }.build(),
            )
        }
    }.build()

@Composable
private fun PageImage(
    model: Any,
    headers: Map<String, String>,
    contentScale: ContentScale,
    modifier: Modifier,
    alignment: Alignment = Alignment.Center,
    width: Int? = null,
    onSize: (Int, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var attempt by remember(model) { mutableIntStateOf(0) }
    var failed by remember(model) { mutableStateOf(false) }
    if (failed) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .heightIn(min = 220.dp)
                .clickable {
                    failed = false
                    attempt++
                },
        ) {
            BasicText(
                "This page didn't load. Tap to try again",
                style = TextStyle(color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center),
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }
    val request = remember(model, attempt, width) { pageRequest(context, model, headers, width) }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
        onState = { state ->
            when (state) {
                is AsyncImagePainter.State.Error -> failed = true
                is AsyncImagePainter.State.Success -> onSize(state.result.image.width, state.result.image.height)
                else -> Unit
            }
        },
    )
}

@Composable
private fun ChapterEnd(chapter: SourceChapter, next: SourceChapter?, source: String, ink: Color, onNext: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
    ) {
        BasicText("End of ${chapter.name}", style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        if (next != null) {
            BasicText("Next: ${next.name}", style = TextStyle(color = ink.copy(alpha = 0.75f), fontSize = 13.sp))
            PillButton("Read ${next.name}", onClick = onNext)
        } else {
            BasicText("No more chapters on $source yet", style = TextStyle(color = ink.copy(alpha = 0.75f), fontSize = 13.sp))
        }
        extra()
    }
}

@Composable
private fun ChapterStart(previous: SourceChapter, ink: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
    ) {
        BasicText("Previous: ${previous.name}", style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        BasicText("Going back to it...", style = TextStyle(color = ink.copy(alpha = 0.75f), fontSize = 13.sp))
    }
}

private class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun toggle() {
        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
        offset = Offset.Zero
    }
}

@Composable
private fun zoomModifier(zoom: Zoom, onTap: (Float) -> Unit, shrink: ((Float) -> Boolean)? = null): Modifier {
    val tap by rememberUpdatedState(onTap)
    val narrow by rememberUpdatedState(shrink)
    val state = rememberTransformableState { change, pan, _ ->
        if (zoom.scale <= 1f && narrow?.invoke(change) == true) {
            zoom.reset()
            return@rememberTransformableState
        }
        zoom.scale = (zoom.scale * change).coerceIn(1f, MAX_ZOOM)
        zoom.offset = if (zoom.scale > 1f) zoom.offset + pan else Offset.Zero
    }
    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { tap(it.x / size.width.coerceAtLeast(1)) },
                onDoubleTap = { zoom.toggle() },
            )
        }
        .transformable(state, canPan = { zoom.scale > 1f })
}

private fun Modifier.zoomed(zoom: Zoom) = graphicsLayer {
    scaleX = zoom.scale
    scaleY = zoom.scale
    translationX = zoom.offset.x
    translationY = zoom.offset.y
}

@Composable
private fun PagedView(
    images: List<Any>,
    headers: Map<String, String>,
    initial: Int,
    twoPages: Boolean,
    shifted: Boolean,
    rtl: Boolean,
    fit: PageFit,
    pageWidth: Int,
    onReady: (PageMover) -> Unit,
    onPage: (Int, Int) -> Unit,
    onEnd: () -> Unit,
    onBeforeStart: () -> Unit,
    onPastEnd: () -> Unit,
    onTap: (Float) -> Unit,
    onSize: (Int, Int, Int) -> Unit,
    start: (@Composable () -> Unit)?,
    end: @Composable () -> Unit,
) {
    val wide = remember { mutableStateMapOf<Int, Boolean>() }
    val wideSet = wide.keys.toSet()
    val units = remember(images.size, twoPages, wideSet, shifted) {
        if (twoPages) MangaRules.spreads(images.size, wideSet, shifted) else images.indices.map { listOf(it) }
    }
    val currentUnits by rememberUpdatedState(units)
    val measured: (Int, Int, Int) -> Unit = { index, width, height ->
        if (width > height && height > 0) wide[index] = true
        onSize(index, width, height)
    }
    val lead = if (start != null) 1 else 0
    val first = remember { lead + if (initial < 0) units.lastIndex else units.indexOfFirst { initial in it }.coerceAtLeast(0) }
    val pager = rememberPagerState(initialPage = first) { lead + units.size + 1 }
    val scope = rememberCoroutineScope()
    val zoom = remember { Zoom() }
    val page by rememberUpdatedState(onPage)
    val ended by rememberUpdatedState(onEnd)
    val back by rememberUpdatedState(onBeforeStart)
    var shownPage by remember { mutableIntStateOf(-1) }

    LaunchedEffect(pager) {
        onReady(object : PageMover {
            override fun move(direction: Int) {
                val target = pager.currentPage + direction
                when {
                    target < lead -> onBeforeStart()
                    target > lead + currentUnits.size -> onPastEnd()
                    else -> scope.launch { pager.animateScrollToPage(target) }
                }
            }

            override fun jump(page: Int) {
                val unit = currentUnits.indexOfFirst { page in it }
                if (unit >= 0) scope.launch { pager.scrollToPage(lead + unit) }
            }
        })
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to currentUnits }.collect { (index, units) ->
            zoom.reset()
            val unit = index - lead
            when {
                unit < 0 -> Unit
                unit >= units.size -> ended()
                else -> {
                    shownPage = units[unit].first()
                    page(units[unit].first(), units[unit].last())
                }
            }
        }
    }
    LaunchedEffect(units) {
        if (shownPage < 0) return@LaunchedEffect
        val unit = units.indexOfFirst { shownPage in it }
        if (unit >= 0 && pager.currentPage != lead + unit) pager.scrollToPage(lead + unit)
    }
    LaunchedEffect(pager, lead) {
        if (lead == 0) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.collect { if (it == 0) back() }
    }
    Box(Modifier.fillMaxSize().then(zoomModifier(zoom, onTap))) {
        HorizontalPager(
            pager,
            reverseLayout = rtl,
            userScrollEnabled = zoom.scale == 1f,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().zoomed(zoom),
        ) { index ->
            if (index < lead) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { start?.invoke() }
                return@HorizontalPager
            }
            if (index - lead >= units.size) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { end() }
                return@HorizontalPager
            }
            val shown = units[index - lead]
            if (shown.size == 2) {
                Row(Modifier.fillMaxSize()) {
                    val ordered = if (rtl) shown.reversed() else shown
                    ordered.forEachIndexed { side, pageIndex ->
                        PageImage(
                            images[pageIndex],
                            headers,
                            ContentScale.Fit,
                            Modifier.weight(1f).fillMaxHeight(),
                            alignment = if (side == 0) Alignment.CenterEnd else Alignment.CenterStart,
                            onSize = { w, h -> measured(pageIndex, w, h) },
                        )
                    }
                }
            } else {
                SinglePage(images[shown.first()], headers, fit, rtl, pageWidth) { w, h -> measured(shown.first(), w, h) }
            }
        }
    }
}

@Composable
private fun SinglePage(model: Any, headers: Map<String, String>, fit: PageFit, rtl: Boolean, pageWidth: Int, onSize: (Int, Int) -> Unit) {
    when (fit) {
        PageFit.Whole -> PageImage(model, headers, ContentScale.Fit, Modifier.fillMaxSize(), onSize = onSize)
        PageFit.Width -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), Alignment.Center) {
            PageImage(model, headers, ContentScale.FillWidth, Modifier.fillMaxWidth(), width = pageWidth, onSize = onSize)
        }
        PageFit.Height -> Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), reverseScrolling = rtl), Alignment.Center) {
            PageImage(model, headers, ContentScale.FillHeight, Modifier.fillMaxHeight(), onSize = onSize)
        }
    }
}

@Composable
private fun WebtoonView(
    images: List<Any>,
    headers: Map<String, String>,
    initial: Int,
    gap: Int,
    pageWidth: Int,
    width: Float,
    onWidth: (Float) -> Unit,
    paper: Color,
    onReady: (PageMover) -> Unit,
    onPage: (Int, Int) -> Unit,
    onEnd: () -> Unit,
    onBeforeStart: () -> Unit,
    onPastEnd: () -> Unit,
    onTap: (Float) -> Unit,
    onSize: (Int, Int, Int) -> Unit,
    start: (@Composable () -> Unit)?,
    end: @Composable () -> Unit,
) {
    val lead = if (start != null) 1 else 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = lead + if (initial < 0) images.lastIndex else initial.coerceIn(0, images.lastIndex))
    val scope = rememberCoroutineScope()
    val zoom = remember { Zoom() }
    val ratios = remember { mutableStateMapOf<Int, Float>() }
    var height by remember { mutableIntStateOf(0) }
    val page by rememberUpdatedState(onPage)
    val ended by rememberUpdatedState(onEnd)

    var shownWidth by remember { mutableFloatStateOf(width.coerceIn(MIN_WEBTOON_WIDTH, 1f)) }
    LaunchedEffect(width) { shownWidth = width.coerceIn(MIN_WEBTOON_WIDTH, 1f) }
    LaunchedEffect(shownWidth) {
        delay(WIDTH_SAVE_DELAY_MS)
        if (shownWidth != width) onWidth(shownWidth)
    }
    LaunchedEffect(list) {
        onReady(object : PageMover {
            override fun move(direction: Int) {
                when {
                    direction < 0 && !list.canScrollBackward -> onBeforeStart()
                    direction > 0 && !list.canScrollForward -> onPastEnd()
                    else -> scope.launch { list.animateScrollBy(direction * height * SCREEN_STEP) }
                }
            }

            override fun jump(page: Int) {
                scope.launch { list.scrollToItem(lead + page.coerceIn(0, images.lastIndex)) }
            }
        })
    }
    val back by rememberUpdatedState(onBeforeStart)
    LaunchedEffect(list) {
        snapshotFlow {
            val last = (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) - lead
            Triple((list.firstVisibleItemIndex - lead).coerceAtLeast(0), last, last >= images.size && !list.canScrollForward)
        }.collect { (first, last, atBottom) ->
            if (first < images.size && last >= 0) page(first, minOf(last, images.lastIndex))
            if (atBottom) ended()
        }
    }
    LaunchedEffect(list, lead) {
        if (lead == 0) return@LaunchedEffect
        snapshotFlow { list.isScrollInProgress && !list.canScrollBackward }.collect { if (it) back() }
    }
    val shrink: (Float) -> Boolean = { change ->
        if (change < 1f || shownWidth < 1f) {
            shownWidth = (shownWidth * change).coerceIn(MIN_WEBTOON_WIDTH, 1f)
            true
        } else {
            false
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { height = it.height }.then(zoomModifier(zoom, onTap, shrink))) {
        LazyColumn(
            state = list,
            userScrollEnabled = zoom.scale == 1f,
            verticalArrangement = Arrangement.spacedBy(gap.dp),
            modifier = Modifier.fillMaxHeight().fillMaxWidth(shownWidth).align(Alignment.TopCenter).background(paper).zoomed(zoom),
        ) {
            if (start != null) item { start() }
            itemsIndexed(images) { index, model ->
                val ratio = ratios[index]
                PageImage(
                    model,
                    headers,
                    ContentScale.FillWidth,
                    Modifier.fillMaxWidth().then(if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.height(PLACEHOLDER_HEIGHT.dp)),
                    width = pageWidth,
                    onSize = { w, h ->
                        if (w > 0 && h > 0) ratios[index] = w.toFloat() / h
                        onSize(index, w, h)
                    },
                )
            }
            item { end() }
        }
    }
}

private const val SCREEN_STEP = 0.85f
private const val PLACEHOLDER_HEIGHT = 560
private const val WIDTH_SAVE_DELAY_MS = 400L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaTopBar(
    title: String,
    chapterName: String,
    onClose: () -> Unit,
    onChapters: () -> Unit,
    onDownload: () -> Unit,
    onSource: () -> Unit,
    modifier: Modifier,
) {
    val ink = readerBarInk(video = false)
    ReaderBar(top = true, insets = Modifier.statusBarsPadding(), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Back", onClick = onClose, filled = false)
            Spacer(Modifier.width(10.dp))
            ReaderHeading(title, chapterName, ink, Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            PillButton("Chapters", onClick = onChapters, filled = false)
            PillButton("Download", onClick = onDownload, filled = false)
            PillButton("Source", onClick = onSource, filled = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MangaBottomBar(
    label: String,
    page: Int,
    pageCount: Int,
    reversed: Boolean,
    onJump: (Int) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier,
) {
    val ink = readerBarInk(video = false)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.roundToInt() ?: page
    ReaderBar(top = false, insets = Modifier.navigationBarsPadding(), modifier = modifier) {
        ReaderBarText(label, ink)
        if (pageCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                ReaderBarText(LookWords.pageOf(Look.style, shown + 1, pageCount), ink, Modifier.width(96.dp), dim = true)
                CompositionLocalProvider(LocalLayoutDirection provides if (reversed) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    LookSlider(
                        value = dragging ?: page.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { onJump(it.roundToInt()) }
                            dragging = null
                        },
                        ink = ink,
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                PillButton("Previous", onClick = onPrevious, filled = false)
                PillButton("Next", onClick = onNext, filled = false)
            }
            PillButton("Options", onClick = onOptions)
        }
    }
}

private fun downloadNote(mediaId: Int, chapter: SourceChapter, downloaded: Set<String>): String? {
    MangaDownloads.active[MangaDownloads.key(mediaId, chapter.id)]?.let { progress ->
        return if (progress.total == 0) "Waiting" else "${progress.done}/${progress.total}"
    }
    return if (chapter.id in downloaded) "Saved" else null
}

@Composable
private fun ChapterList(
    chapters: List<SourceChapter>,
    current: Int,
    downloaded: Set<String>,
    mediaId: Int,
    onGo: (Int) -> Unit,
    onReadUpTo: (Int) -> Unit,
) {
    val theme = LocalAppTheme.current
    val seen = spoilerChapterSeen(mediaId, chapters.getOrNull(current))
    var filter by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Int?>(null) }
    val shown = remember(filter, chapters, seen) {
        chapters.indices.filter { filter.isBlank() || chapterName(chapters[it], it, seen, it == current).contains(filter.trim(), ignoreCase = true) }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Column {
        SearchField(filter, { filter = it }, "Find a chapter", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(state = list, modifier = Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(shown, key = { _, index -> chapters[index].id }) { _, index ->
                val chapter = chapters[index]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onGo(index) }, onLongClick = { asking = index })
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    BasicText(
                        chapterName(chapter, index, seen, index == current),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (index == current) theme.primary else theme.foreground,
                            fontSize = 14.sp,
                            fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    downloadNote(mediaId, chapter, downloaded)?.let {
                        BasicText(it, style = TextStyle(color = theme.secondary, fontSize = 12.sp))
                    }
                }
            }
        }
        Hint("Hold a chapter to mark it and the ones before as read", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    asking?.let { index ->
        ConfirmDialog(
            title = "Read up to here?",
            message = "Marks ${chapters[index].name} and everything before it as read on AniList, and opens the next chapter.",
            confirmLabel = "Mark as read",
            onConfirm = {
                asking = null
                onReadUpTo(index)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
internal fun DownloadList(
    chapters: List<SourceChapter>,
    current: Int,
    downloaded: Set<String>,
    mediaId: Int,
    onDownload: (List<SourceChapter>) -> Unit,
    onDelete: (SourceChapter) -> Unit,
) {
    val theme = LocalAppTheme.current
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    val notSaved = { chapter: SourceChapter -> chapter.id !in downloaded }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            PillButton("This chapter", onClick = { onDownload(listOf(chapters[current]).filter(notSaved)) }, filled = false)
            PillButton("Next 5", onClick = { onDownload(chapters.drop(current + 1).filter(notSaved).take(5)) }, filled = false)
        }
        LazyColumn(state = list, modifier = Modifier.heightIn(max = 360.dp)) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { _, chapter ->
                val note = downloadNote(mediaId, chapter, downloaded)
                val selected = chosen[chapter.id] == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = note == null) { chosen[chapter.id] = !selected }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    BasicText(
                        if (note == null) (if (selected) "☑" else "☐") else "✓",
                        style = TextStyle(color = theme.primary, fontSize = 16.sp),
                        modifier = Modifier.width(28.dp),
                    )
                    BasicText(chapter.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.weight(1f))
                    when (note) {
                        null -> Unit
                        "Saved" -> PillButton("Delete", onClick = { onDelete(chapter) }, filled = false)
                        else -> BasicText(note, style = TextStyle(color = theme.secondary, fontSize = 12.sp))
                    }
                }
            }
        }
        val picked = chapters.filter { chosen[it.id] == true && it.id !in downloaded }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            PillButton(if (picked.isEmpty()) "Choose chapters" else "Download ${picked.size}", onClick = { onDownload(picked) }, enabled = picked.isNotEmpty())
        }
    }
}

@Composable
private fun SourceSheet(name: String, address: String, onSearch: () -> Unit, onForget: () -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("Reading from $name", style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        BasicText(address, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
        BasicText(
            "If this is the wrong series, or chapters are missing, search again to choose another one.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Search again", onClick = onSearch)
            PillButton("Forget source", onClick = onForget, filled = false)
        }
    }
}

/** With spoiler-free on for [mediaId], the furthest chapter reached (AniList progress or the one open); else null. */
@Composable
internal fun spoilerChapterSeen(mediaId: Int, reading: SourceChapter?): Double? {
    val context = LocalContext.current
    if (!SpoilerPrefs.on(context, mediaId)) return null
    val progress = LocalManga.current.aniList.entryFor(mediaId)?.progress ?: 0
    return maxOf(progress.toDouble(), reading?.number ?: 0.0)
}

/** The source's chapter name, or just its number when it's past [seen] (spoiler-free). */
internal fun chapterName(chapter: SourceChapter, index: Int, seen: Double?, current: Boolean = false): String {
    if (seen == null || current) return chapter.name
    val number = chapter.number
    if (number != null && number <= seen) return chapter.name
    return number?.let { "Chapter ${chapterNumber(it)}" } ?: "Chapter ${index + 1}"
}
