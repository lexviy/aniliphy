package com.aniliphy.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aniliphy.ReaderKeys
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListSync
import com.aniliphy.anilist.ProgressRules
import com.aniliphy.core.AdvanceMode
import com.aniliphy.core.ReaderOrientation
import com.aniliphy.core.TapZones
import com.aniliphy.novels.BookException
import com.aniliphy.novels.ChapterCounting
import com.aniliphy.novels.EpubBook
import com.aniliphy.novels.EpubPage
import com.aniliphy.novels.NovelAlignment
import com.aniliphy.novels.NovelBackground
import com.aniliphy.novels.NovelIndicator
import com.aniliphy.novels.NovelReadingMode
import com.aniliphy.novels.NovelSettings
import com.aniliphy.novels.PageStyle
import com.aniliphy.novels.PdfBook
import com.aniliphy.novels.ReadingSession
import com.aniliphy.novels.VolumeEntry
import com.aniliphy.novels.VolumeFile
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

private sealed interface OpenBook {
    fun close()

    class Epub(val book: EpubBook) : OpenBook {
        override fun close() = book.close()
    }

    class Pdf(val book: PdfBook) : OpenBook {
        override fun close() = book.close()
    }
}

private class LoadedBook(val book: OpenBook, val session: ReadingSession)

private enum class ReaderSheet { Chapters, Volumes, Text }

private interface BookControls {
    fun move(direction: Int)
    fun goToChapter(index: Int, position: Double)
}

private fun paperColors(settings: NovelSettings, theme: AppTheme): Pair<Color, Color> = when (settings.background) {
    NovelBackground.Theme -> theme.background to theme.foreground
    NovelBackground.White -> Color.White to Color(0xFF1A1A1A)
    NovelBackground.Sepia -> Color(0xFFF4ECD8) to Color(0xFF3B2F1E)
    NovelBackground.Black -> Color.Black to Color(0xFFDDDDDD)
}

private fun Color.css(): String = "#%06x".format(toArgb() and 0xFFFFFF)

private fun primeEarlierVolumes(novels: NovelsUi, request: ReaderRequest) {
    if (novels.settings.counting == ChapterCounting.Manual) return
    request.volumes.take(request.index).forEach { file ->
        runCatching { novels.countedVolume(request.series, file) }
    }
}

private fun openBook(context: Context, novels: NovelsUi, request: ReaderRequest): LoadedBook {
    primeEarlierVolumes(novels, request)
    val file = request.volumes[request.index]
    val copy = novels.files.localCopy(file)
    val opened: OpenBook
    val titles: List<String>
    val sizes: List<Long>
    var starts: List<Int>? = null
    if (file.isEpub) {
        val book = EpubBook(copy)
        if (book.chapters.isEmpty()) {
            book.close()
            throw BookException("No chapters found in this book")
        }
        opened = OpenBook.Epub(book)
        titles = book.chapters.map { it.title }
        sizes = book.chapters.map { it.size }
        starts = book.chapterStarts
    } else {
        val book = PdfBook.open(context, copy, novels.files.chaptersCache(file))
        if (book.pageCount == 0) {
            book.close()
            throw BookException("This PDF has no pages")
        }
        opened = OpenBook.Pdf(book)
        titles = book.chapters.map { it.title }
        sizes = book.chapters.map { (it.end - it.start).toLong() }
    }
    val session = ReadingSession(
        novels.progress,
        request.series,
        request.title,
        request.volumes.map { it.name },
        request.index,
        titles,
        sizes,
        manualCounting = novels.settings.counting == ChapterCounting.Manual,
        entry = request.entry,
        starts = starts,
    )
    return LoadedBook(opened, session)
}

@Composable
internal fun NovelReaderScreen(request: ReaderRequest, onClose: () -> Unit) {
    val novels = LocalNovels.current
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    val settings = novels.settings
    val (paper, ink) = paperColors(settings, theme)
    BackHandler(onBack = onClose)
    ReaderWindow(settings.keepScreenOn, settings.orientation)

    val loaded by produceState<Result<LoadedBook>?>(null, request.volumes[request.index], request.entry) {
        value = null
        value = withContext(Dispatchers.IO) {
            try {
                Result.success(openBook(context, novels, request))
            } catch (e: BookException) {
                Result.failure(e)
            } catch (e: java.io.IOException) {
                Result.failure(BookException("Could not open the book: ${e.message}", e))
            } catch (e: SecurityException) {
                Result.failure(BookException("The app can't reach this file anymore. Choose the folder again.", e))
            }
        }
    }
    Box(Modifier.fillMaxSize().background(paper)) {
        val outcome = loaded
        val book = outcome?.getOrNull()
        when {
            outcome == null -> Hint("Opening...", Modifier.align(Alignment.Center), color = ink)
            book == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Hint(outcome.exceptionOrNull()?.message ?: "Could not open the book", color = ink)
                PillButton("Close", onClick = onClose)
            }
            else -> androidx.compose.runtime.key(book) {
                DisposableEffect(book) {
                    onDispose {
                        book.session.persist()
                        book.book.close()
                    }
                }
                ReaderBody(request, book, paper, ink, onClose)
            }
        }
    }
}

@Composable
internal fun ReaderWindow(keepScreenOn: Boolean, orientation: ReaderOrientation) {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(orientation) {
        activity?.requestedOrientation = when (orientation) {
            ReaderOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            ReaderOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            ReaderOrientation.FollowPhone -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

@Composable
internal fun SystemBars(visible: Boolean) {
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(visible) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ReaderBody(request: ReaderRequest, loaded: LoadedBook, paper: Color, ink: Color, onClose: () -> Unit) {
    val novels = LocalNovels.current
    val settings = novels.settings
    val session = loaded.session
    val context = LocalContext.current
    var bars by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var chapter by remember { mutableIntStateOf(session.chapter) }
    var fraction by remember { mutableFloatStateOf(session.volumeFraction().toFloat()) }
    var chapterFraction by remember { mutableFloatStateOf(session.chapterFraction().toFloat()) }
    var chaptersRead by remember { mutableIntStateOf(session.chaptersRead()) }
    // Chapters finished while reading count towards the statistics in Settings → Activity.
    var countedChapters by remember { mutableIntStateOf(chaptersRead) }
    LaunchedEffect(chaptersRead) {
        if (chaptersRead > countedChapters) {
            val more = chaptersRead - countedChapters
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aniliphy.core.ActivityLog.novelChapters(context, more) }
        }
        countedChapters = maxOf(countedChapters, chaptersRead)
    }
    var askNextChapter by remember { mutableStateOf(false) }
    var askNextVolume by remember { mutableStateOf(false) }
    var pageLabel by remember { mutableStateOf("") }
    var controls by remember { mutableStateOf<BookControls?>(null) }
    var countVersion by remember { mutableIntStateOf(0) }
    var linkReturn by remember { mutableStateOf<Pair<Int, Double>?>(null) }
    SystemBars(bars)
    AniListProgress(request, session, chaptersRead, countVersion)

    val nextVolume = request.index + 1 < request.volumes.size
    fun openVolume(index: Int, entry: VolumeEntry) {
        session.persist()
        novels.changeVolume(request, index, entry)
    }
    fun goChapter(index: Int, position: Double = 0.0) {
        controls?.goToChapter(index, position)
    }
    BackHandler(enabled = linkReturn != null) {
        linkReturn?.let { (index, position) -> goChapter(index, position) }
        linkReturn = null
    }
    BackHandler(enabled = sheet != null) { sheet = null }
    fun pastEnd(viaPage: Boolean) {
        val last = session.titles.size - 1
        when {
            session.chapter < last -> when {
                settings.nextChapter == AdvanceMode.Automatic || !viaPage ||
                    session.chapterStart(session.chapter + 1) == session.chapterStart() -> goChapter(session.chapter + 1)
                else -> askNextChapter = true
            }
            !nextVolume -> {
                session.setPosition(1.0)
                chaptersRead = session.chaptersRead()
                context.showToast("That's the last volume")
            }
            settings.nextVolume == AdvanceMode.Automatic -> openVolume(request.index + 1, VolumeEntry.Start)
            else -> askNextVolume = true
        }
    }
    fun beforeStart() {
        when {
            session.chapter > 0 -> goChapter(session.chapter - 1, 1.0)
            request.index > 0 -> openVolume(request.index - 1, VolumeEntry.End)
            else -> context.showToast("That's the beginning")
        }
    }
    val onMoved = { position: Double, label: String ->
        session.setPosition(position)
        fraction = session.volumeFraction().toFloat()
        chapterFraction = session.chapterFraction().toFloat()
        chaptersRead = session.chaptersRead()
        pageLabel = label
    }
    val onChapterShown = { index: Int, position: Double ->
        session.moveTo(index, position)
        chapter = index
        fraction = session.volumeFraction().toFloat()
        chapterFraction = session.chapterFraction().toFloat()
        chaptersRead = session.chaptersRead()
        session.persist()
    }
    val onTap: (Float) -> Unit = { share ->
        val left = share < TAP_SIDE
        val right = share > 1 - TAP_SIDE
        val zones = settings.tapZones
        when {
            bars || sheet != null -> {
                sheet = null
                bars = false
            }
            zones == TapZones.Off || (!left && !right) -> bars = true
            (left && zones == TapZones.Normal) || (right && zones == TapZones.Inverted) -> controls?.move(-1)
            else -> controls?.move(1)
        }
    }

    LaunchedEffect(session) {
        while (true) {
            delay(SAVE_EVERY_MS)
            withContext(Dispatchers.IO) { session.persist() }
        }
    }
    DisposableEffect(settings.volumeButtons) {
        ReaderKeys.onVolume = if (settings.volumeButtons) {
            { up ->
                controls?.move(if (up) -1 else 1)
                true
            }
        } else {
            null
        }
        onDispose { ReaderKeys.onVolume = null }
    }

    Box(Modifier.fillMaxSize()) {
        when (val book = loaded.book) {
            is OpenBook.Epub -> EpubView(
                book.book,
                session,
                paper,
                ink,
                onReady = { controls = it },
                onMoved = onMoved,
                onChapterShown = onChapterShown,
                onTap = onTap,
                onPastEnd = ::pastEnd,
                onBeforeStart = ::beforeStart,
                linksActive = { !bars && sheet == null },
                onFollowLink = { index, position ->
                    linkReturn = index to position
                    context.showToast("Back returns to where you were")
                },
            )
            is OpenBook.Pdf -> PdfView(
                book.book,
                session,
                onReady = { controls = it },
                onMoved = onMoved,
                onChapterShown = onChapterShown,
                onTap = onTap,
                onPastEnd = { pastEnd(viaPage = false) },
                onBeforeStart = ::beforeStart,
            )
        }
        val isEpub = loaded.book is OpenBook.Epub
        ReadingStatus(
            settings,
            session.titles.getOrNull(chapter).orEmpty(),
            chapter,
            session.titles.size,
            fraction,
            chapterFraction,
            if (isEpub) ink else Color.White,
            Modifier.align(Alignment.BottomCenter).background(if (isEpub) paper else PDF_BACKGROUND),
        )
        SizedControls(settings.controlsSize) {
        if (bars) {
            ReaderTopBar(request, session.titles.getOrNull(chapter).orEmpty(), onClose, { sheet = ReaderSheet.Chapters }, { sheet = ReaderSheet.Volumes }, Modifier.align(Alignment.TopCenter))
            ReaderBottomBar(
                label = listOf(session.label(), pageLabel, "${(fraction * 100).toInt()}%").filter { it.isNotEmpty() }.joinToString(" · "),
                onPrevious = { session.previousChapter()?.let { goChapter(it) } ?: beforeStart() },
                onNext = { session.nextChapter()?.let { goChapter(it) } ?: pastEnd(viaPage = false) },
                onText = { sheet = ReaderSheet.Text },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        when (sheet) {
            ReaderSheet.Chapters -> ReaderSheetFrame("Chapters", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                ChaptersSheet(session, chapter, countVersion, onGo = {
                    goChapter(it)
                    sheet = null
                    bars = false
                }, onCounted = { message ->
                    context.showToast(message)
                    countVersion++
                })
            }
            ReaderSheet.Volumes -> ReaderSheetFrame("Volumes", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                VolumesSheet(request, onGo = {
                    sheet = null
                    if (it != request.index) openVolume(it, VolumeEntry.Resume)
                })
            }
            ReaderSheet.Text -> ReaderSheetFrame("Text", { sheet = null }, Modifier.align(Alignment.BottomCenter)) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SegmentedRow(
                        label = "Reading mode",
                        options = NovelReadingMode.entries,
                        selected = settings.mode,
                        optionLabel = { it.label },
                        onSelect = { settings.mode = it },
                    )
                    if (request.mediaId != null) {
                        SegmentedRow(
                            label = "Update AniList",
                            options = AniListSync.entries,
                            selected = settings.aniListSync,
                            optionLabel = { it.label },
                            onSelect = { settings.aniListSync = it },
                        )
                    }
                    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
                    TextSection(settings)
                }
            }
            null -> Unit
        }
        }
    }
    if (askNextChapter) {
        ConfirmDialog(
            title = "Next chapter?",
            message = session.titles.getOrNull(session.chapter + 1).orEmpty(),
            confirmLabel = "Go",
            onConfirm = {
                askNextChapter = false
                goChapter(session.chapter + 1)
            },
            onDismiss = { askNextChapter = false },
        )
    }
    if (askNextVolume) {
        ConfirmDialog(
            title = "Next volume?",
            message = request.volumes[request.index + 1].stem,
            confirmLabel = "Open",
            onConfirm = {
                askNextVolume = false
                openVolume(request.index + 1, VolumeEntry.Start)
            },
            onDismiss = { askNextVolume = false },
        )
    }
}

private const val TAP_SIDE = 0.3f
private const val SAVE_EVERY_MS = 2_000L
private const val ANILIST_DELAY_MS = 1_500L

@Composable
private fun AniListProgress(request: ReaderRequest, session: ReadingSession, chaptersRead: Int, countVersion: Int) {
    val novels = LocalNovels.current
    val mediaId = request.mediaId
    var offer by remember { mutableStateOf<Int?>(null) }
    var offerCompletion by remember { mutableStateOf(false) }
    var completionAnswered by remember { mutableStateOf(false) }
    var handled by remember { mutableIntStateOf(0) }
    if (mediaId == null || !novels.loggedIn) return

    fun send(progress: Int) {
        handled = progress
        novels.sendProgress(mediaId, progress)
    }
    fun wanted(): Int {
        val read = session.chaptersRead()
        return request.totalChapters?.takeIf { it > 0 }?.let { minOf(read, it) } ?: read
    }
    LaunchedEffect(chaptersRead, countVersion, novels.settings.aniListSync) {
        if (session.segments.isEmpty()) return@LaunchedEffect
        val progress = wanted()
        if (progress <= handled || progress <= 0) return@LaunchedEffect
        delay(ANILIST_DELAY_MS)
        if (novels.settings.aniListSync == AniListSync.Automatic) {
            send(progress)
        } else {
            val known = try {
                novels.aniList.api.progress(mediaId).progress
            } catch (_: AniListException) {
                null
            }
            if (known != null && known >= progress) handled = progress else offer = progress
        }
    }
    DisposableEffect(session) {
        onDispose {
            val progress = wanted()
            if (novels.settings.aniListSync == AniListSync.Automatic && session.segments.isNotEmpty() && progress > handled && progress > 0) {
                send(progress)
            }
        }
    }
    LaunchedEffect(chaptersRead, countVersion, offer == null) {
        val lastVolume = request.index == request.volumes.size - 1
        if (completionAnswered || offer != null || !lastVolume || !session.finishedVolume()) return@LaunchedEffect
        delay(ANILIST_DELAY_MS * 2)
        val current = try {
            novels.aniList.api.progress(mediaId)
        } catch (_: AniListException) {
            return@LaunchedEffect
        }
        offerCompletion = ProgressRules.canOfferCompletion(current, session.chaptersRead(), request.volumes.size, request.mediaStatus)
    }
    if (offerCompletion && offer == null) {
        ConfirmDialog(
            title = "Mark as completed?",
            message = "You finished the last volume here. AniList would show ${request.title} as completed.",
            confirmLabel = "Complete",
            onConfirm = {
                offerCompletion = false
                completionAnswered = true
                novels.completeOnAniList(mediaId, session.chaptersRead())
            },
            onDismiss = {
                offerCompletion = false
                completionAnswered = true
            },
        )
    }
    offer?.let { progress ->
        ConfirmDialog(
            title = "Update AniList?",
            message = "Set ${request.title} to ${chaptersText(progress)} read.",
            confirmLabel = "Update",
            onConfirm = {
                offer = null
                send(progress)
            },
            onDismiss = {
                offer = null
                handled = progress
            },
        )
    }
}

@Composable
private fun ReadingStatus(
    settings: NovelSettings,
    title: String,
    chapter: Int,
    chapters: Int,
    fraction: Float,
    chapterFraction: Float,
    ink: Color,
    modifier: Modifier,
) {
    val style = TextStyle(color = ink.copy(alpha = 0.65f), fontSize = 11.sp)
    if (settings.indicator == NovelIndicator.Off && !settings.clockAndBattery) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        val text = when (settings.indicator) {
            NovelIndicator.Chapter -> title.ifEmpty { "Chapter ${chapter + 1} of $chapters" }
            NovelIndicator.Percent -> "${(fraction * 100).toInt()}%"
            NovelIndicator.Off -> ""
        }
        BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = style, modifier = Modifier.weight(1f))
        if (settings.indicator != NovelIndicator.Off) {
            BasicText("${(chapterFraction * 100).toInt()}%", style = style, modifier = Modifier.padding(start = 12.dp))
        }
        if (settings.clockAndBattery) {
            Spacer(Modifier.width(12.dp))
            val context = LocalContext.current
            val now by produceState(clockText(context)) {
                while (true) {
                    delay(CLOCK_EVERY_MS)
                    value = clockText(context)
                }
            }
            BasicText(now, style = style)
        }
    }
}

private const val CLOCK_EVERY_MS = 30_000L

private fun clockText(context: Context): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) "$time · ${level * 100 / scale}%" else time
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTopBar(
    request: ReaderRequest,
    chapterTitle: String,
    onClose: () -> Unit,
    onChapters: () -> Unit,
    onVolumes: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalAppTheme.current
    Column(modifier.fillMaxWidth().background(theme.surface).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton("Back", onClick = onClose, filled = false)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                BasicText(request.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                BasicText(chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            PillButton("Chapters", onClick = onChapters, filled = false)
            if (request.volumes.size > 1) PillButton("Volumes", onClick = onVolumes, filled = false)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBottomBar(label: String, onPrevious: () -> Unit, onNext: () -> Unit, onText: () -> Unit, modifier: Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier.fillMaxWidth().background(theme.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        BasicText(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 12.sp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                PillButton("Previous", onClick = onPrevious, filled = false)
                PillButton("Next", onClick = onNext, filled = false)
            }
            PillButton("Text", onClick = onText)
        }
    }
}

@Composable
internal fun ReaderSheetFrame(title: String, onClose: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val theme = LocalAppTheme.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(theme.surface)
            .navigationBarsPadding()
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
            BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            PillButton("Close", onClick = onClose, filled = false)
        }
        Spacer(Modifier.padding(top = 6.dp))
        content()
    }
}

@Composable
private fun ChaptersSheet(session: ReadingSession, current: Int, version: Int, onGo: (Int) -> Unit, onCounted: (String) -> Unit) {
    val theme = LocalAppTheme.current
    val shown = session.chapterStart(current)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (session.listed.indexOf(shown) - 2).coerceAtLeast(0))
    Column {
        val segments = remember(version) { session.segments }
        BasicText(
            if (segments.isEmpty()) "Not counting chapters here" else "Chapters read for AniList: ${session.chaptersRead()}",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            PillButton(if (session.counting) "Stop here" else "Start counting here", onClick = { onCounted(session.toggleCounting()) }, filled = false)
            PillButton("Reset count", onClick = { onCounted(session.resetCounting()) }, filled = false)
        }
        LazyColumn(state = list, modifier = Modifier.heightIn(max = 380.dp)) {
            itemsIndexed(session.listed) { _, index ->
                val title = session.titles[index]
                val counted = segments.any { index >= it.start && (it.end == null || index <= it.end) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index == shown) theme.panel else Color.Transparent)
                        .clickable { onGo(index) }
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    BasicText(
                        title.ifEmpty { "Section ${index + 1}" },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = if (index == shown) theme.primary else theme.foreground, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (counted) {
                        BasicText("counted", style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumesSheet(request: ReaderRequest, onGo: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    LazyColumn(Modifier.heightIn(max = 420.dp)) {
        itemsIndexed(request.volumes) { index, file: VolumeFile ->
            BasicText(
                file.stem,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (index == request.index) theme.primary else theme.foreground, fontSize = 14.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (index == request.index) theme.panel else Color.Transparent)
                    .clickable { onGo(index) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

private class PageState(val page: Int, val count: Int, val position: Double, val edge: String?) {
    val label: String get() = if (count > 1) "Page ${page + 1} of $count" else ""

    companion object {
        fun parse(raw: String?): PageState? {
            val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.contentOrNull }.getOrNull() ?: return null
            val item = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
            return PageState(
                (item["page"] as? JsonPrimitive)?.intOrNull ?: 0,
                (item["count"] as? JsonPrimitive)?.intOrNull ?: 1,
                (item["pos"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                (item["edge"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun EpubView(
    book: EpubBook,
    session: ReadingSession,
    paper: Color,
    ink: Color,
    onReady: (BookControls) -> Unit,
    onMoved: (Double, String) -> Unit,
    onChapterShown: (Int, Double) -> Unit,
    onTap: (Float) -> Unit,
    onPastEnd: (Boolean) -> Unit,
    onBeforeStart: () -> Unit,
    linksActive: () -> Boolean,
    onFollowLink: (Int, Double) -> Unit,
) {
    val settings = LocalNovels.current.settings
    val density = LocalDensity.current
    val cutoutTop = with(density) { WindowInsets.displayCutout.getTop(this).toDp().value.toInt() }
    val hasStatus = settings.indicator != NovelIndicator.Off || settings.clockAndBattery
    val style = PageStyle(
        paged = settings.mode == NovelReadingMode.Pages,
        fontSize = settings.fontSize,
        font = settings.font,
        lineSpacing = settings.lineSpacing.factor,
        marginDp = settings.margins.dp,
        topDp = maxOf(cutoutTop, 16),
        bottomDp = if (hasStatus) 28 else 14,
        justified = settings.alignment == NovelAlignment.Justified,
        background = paper.css(),
        ink = ink.css(),
        illustrations = settings.showIllustrations,
    )
    val currentStyle by rememberUpdatedState(style)
    val moved by rememberUpdatedState(onMoved)
    val shown by rememberUpdatedState(onChapterShown)
    val tap by rememberUpdatedState(onTap)
    val pastEnd by rememberUpdatedState(onPastEnd)
    val beforeStart by rememberUpdatedState(onBeforeStart)
    val linksOn by rememberUpdatedState(linksActive)
    val followed by rememberUpdatedState(onFollowLink)

    val controller = remember(book) {
        object {
            var web: WebView? = null
            var ready = false
            var chapter = session.chapter
            var position = session.position

            var loading: String? = null

            var fragment: String? = null

            fun follow(href: String): Boolean {
                val current = book.chapters[chapter].path
                val target = if (href.startsWith("#")) current else EpubBook.resolve(current.substringBeforeLast('/', ""), href)
                val index = book.chapters.indexOfFirst { it.path.equals(target, ignoreCase = true) }
                if (index < 0) return false
                val id = Uri.decode(href.substringAfter('#', ""))
                followed(chapter, position)
                if (index == chapter && id.isNotEmpty()) {
                    anchor(id)
                } else {
                    fragment = id.ifEmpty { null }
                    load(index, 0.0)
                }
                return true
            }

            fun anchor(id: String) {
                web?.evaluateJavascript("R.anchor(${JsonPrimitive(id)})") { report(it) }
            }

            fun load(index: Int, at: Double) {
                chapter = index
                position = at
                ready = false
                web?.alpha = 0f
                val path = book.chapters[index].path
                val url = Uri.Builder().scheme("https").authority(EpubPage.HOST).path(path).build().toString()
                loading = url
                web?.loadUrl(url)
            }

            fun report(raw: String?) {
                val state = PageState.parse(raw) ?: return
                when (state.edge) {
                    "end" -> pastEnd(true)
                    "start" -> beforeStart()
                    else -> {
                        position = state.position
                        moved(state.position, state.label)
                    }
                }
            }

            fun move(direction: Int) {
                if (ready) web?.evaluateJavascript("R.move($direction)") { report(it) }
            }

            fun relayout() {
                if (!ready) return
                web?.evaluateJavascript("R.init(${currentStyle.paged},$position)") { report(it) }
            }
        }
    }
    LaunchedEffect(controller) {
        onReady(object : BookControls {
            override fun move(direction: Int) = controller.move(direction)
            override fun goToChapter(index: Int, position: Double) = controller.load(index, position)
        })
    }
    LaunchedEffect(controller, style) {
        val web = controller.web ?: return@LaunchedEffect
        web.setBackgroundColor(paper.toArgb())
        controller.load(controller.chapter, controller.position)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(paper.toArgb())
                alpha = 0f
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                isLongClickable = false
                setOnLongClickListener { true }
                with(settings_()) {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    setGeolocationEnabled(false)
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    textZoom = 100
                    mediaPlaybackRequiresUserGesture = true
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setSupportZoom(false)
                    builtInZoomControls = false
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                        request.url.toString() != controller.loading

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.url.host != EpubPage.HOST) return blocked()
                        val path = request.url.path.orEmpty().removePrefix("/")
                        val bytes = runCatching { book.read(path) }.getOrNull() ?: return missing()
                        val mime = EpubPage.mimeType(path)
                        if (mime == "text/html") {
                            val page = EpubPage.prepare(bytes.toString(Charsets.UTF_8), currentStyle)
                            return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(page.toByteArray()))
                        }
                        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        settle(view, controller.chapter, controller.position, INIT_TRIES)
                    }

                    private fun settle(view: WebView, index: Int, at: Double, tries: Int) {
                        if (controller.web !== view || controller.chapter != index) return
                        view.evaluateJavascript(EpubPage.SCRIPT + ";R.init(${currentStyle.paged},$at)") { raw ->
                            val state = PageState.parse(raw)
                            val unfinished = if (currentStyle.paged) state?.count == 1 else state?.position == 0.0
                            if (at > 0.0 && unfinished && tries > 0) {
                                view.postDelayed({ settle(view, index, at, tries - 1) }, INIT_RETRY_MS)
                            } else {
                                controller.ready = true
                                view.alpha = 1f
                                shown(index, at)
                                controller.report(raw)
                                controller.fragment?.let { id ->
                                    controller.fragment = null
                                    controller.anchor(id)
                                }
                            }
                        }
                    }
                }
                val detector = GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            val share = e.x / width.coerceAtLeast(1)
                            if (!controller.ready || !linksOn()) {
                                tap(share)
                                return true
                            }
                            val scale = resources.displayMetrics.density
                            evaluateJavascript("R.linkAt(${e.x / scale},${e.y / scale})") { raw ->
                                val href = runCatching { (Json.parseToJsonElement(raw) as? JsonPrimitive)?.contentOrNull }.getOrNull().orEmpty()
                                if (href.isEmpty() || !controller.follow(href)) tap(share)
                            }
                            return true
                        }

                        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                            val start = e1 ?: return false
                            val dx = e2.x - start.x
                            val dy = e2.y - start.y
                            if (currentStyle.paged) {
                                if (abs(dx) > abs(dy) && abs(dx) > SWIPE_MIN) {
                                    controller.move(if (dx < 0) 1 else -1)
                                    return true
                                }
                            } else if (abs(dy) > abs(dx)) {
                                if (dy < 0 && !canScrollVertically(1)) controller.move(1)
                                if (dy > 0 && !canScrollVertically(-1)) controller.move(-1)
                            }
                            return false
                        }
                    },
                )
                setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    currentStyle.paged
                }
                setOnScrollChangeListener { _, _, _, _, _ ->
                    if (!currentStyle.paged && controller.ready) {
                        evaluateJavascript("R.state()") { controller.report(it) }
                    }
                }
                addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) controller.relayout()
                }
                controller.web = this
                controller.load(session.chapter, session.position)
            }
        },
        onRelease = { web ->
            web.stopLoading()
            web.destroy()
        },
    )
}

private const val SWIPE_MIN = 60f
private const val INIT_TRIES = 10
private const val INIT_RETRY_MS = 100L

private fun WebView.settings_(): WebSettings = settings

private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun missing() = WebResourceResponse("text/plain", "utf-8", 404, "Missing", emptyMap(), ByteArrayInputStream(ByteArray(0)))

@Composable
private fun PdfView(
    book: PdfBook,
    session: ReadingSession,
    onReady: (BookControls) -> Unit,
    onMoved: (Double, String) -> Unit,
    onChapterShown: (Int, Double) -> Unit,
    onTap: (Float) -> Unit,
    onPastEnd: () -> Unit,
    onBeforeStart: () -> Unit,
) {
    val settings = LocalNovels.current.settings
    val paged = settings.mode == NovelReadingMode.Pages
    fun pageFor(chapter: Int, position: Double): Int {
        val c = book.chapters[chapter]
        return (c.start + (position * (c.end - c.start - 1).coerceAtLeast(0)).toInt()).coerceIn(0, book.pageCount - 1)
    }
    val first = remember(book) { pageFor(session.chapter, session.position) }
    val pager = rememberPagerState(initialPage = first) { book.pageCount }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = first)
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val current = { if (paged) pager.currentPage else list.firstVisibleItemIndex }
    val moved by rememberUpdatedState(onMoved)
    val shown by rememberUpdatedState(onChapterShown)
    val tap by rememberUpdatedState(onTap)

    LaunchedEffect(book, paged) {
        onReady(object : BookControls {
            override fun move(direction: Int) {
                val next = current() + direction
                when {
                    next < 0 -> onBeforeStart()
                    next >= book.pageCount -> onPastEnd()
                    paged -> scope.launch { pager.animateScrollToPage(next) }
                    else -> scope.launch { list.animateScrollToItem(next) }
                }
            }

            override fun goToChapter(index: Int, position: Double) {
                val page = pageFor(index.coerceIn(book.chapters.indices), if (position >= 1.0) 1.0 else position)
                scope.launch { if (paged) pager.scrollToPage(page) else list.scrollToItem(page) }
            }
        })
    }
    LaunchedEffect(book, paged) {
        snapshotFlow { current() to (!paged && !list.canScrollForward) }.collectLatest { (page, atBottom) ->
            val chapter = book.chapterOf(page)
            val c = book.chapters[chapter]
            val share = if (atBottom) 1.0 else (page - c.start).toDouble() / (c.end - c.start - 1).coerceAtLeast(1)
            if (chapter != session.chapter) shown(chapter, share)
            moved(share, "Page ${page + 1} of ${book.pageCount}")
            scale = 1f
            offset = Offset.Zero
        }
    }
    val zoom = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, MAX_ZOOM)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(PDF_BACKGROUND)
            .onSizeChanged { width = it.width }
            .pointerInput(book) {
                detectTapGestures(
                    onTap = { tap(it.x / size.width.coerceAtLeast(1)) },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                        offset = Offset.Zero
                    },
                )
            }
            .transformable(zoom, canPan = { scale > 1f }),
    ) {
        val layer = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        if (width > 0) {
            if (paged) {
                HorizontalPager(pager, userScrollEnabled = scale == 1f, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize().then(layer)) { page ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { PdfPageImage(book, page, width) }
                }
            } else {
                LazyColumn(state = list, userScrollEnabled = scale == 1f, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize().then(layer)) {
                    items(book.pageCount) { page -> PdfPageImage(book, page, width) }
                }
            }
        }
    }
}

private val PDF_BACKGROUND = Color(0xFF222222)
private const val MAX_ZOOM = 4f
private const val DOUBLE_TAP_ZOOM = 2.2f

@Composable
private fun PdfPageImage(book: PdfBook, page: Int, width: Int) {
    val shape = book.shapes[page]
    val bitmap by produceState<Bitmap?>(null, book, page, width) {
        value = withContext(Dispatchers.IO) { runCatching { book.render(page, (width * PDF_SHARPNESS).toInt()) }.getOrNull() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / shape).background(Color.White)) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

private const val PDF_SHARPNESS = 1.5f
