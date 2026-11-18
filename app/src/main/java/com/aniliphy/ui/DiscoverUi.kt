package com.aniliphy.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.aniliphy.anilist.AniListApi
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.DiscoverBuilder
import com.aniliphy.anilist.DiscoverFeedKind
import com.aniliphy.anilist.DiscoverFile
import com.aniliphy.anilist.DiscoverLearned
import com.aniliphy.anilist.DiscoverSettings
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ReasonKind
import com.aniliphy.anilist.Suggestion
import com.aniliphy.anime.AnimeTheme
import com.aniliphy.anime.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Discover's builder and what it learned, shared by the Home rows and the feed. */
internal object DiscoverMemory {
    var learned by mutableStateOf(DiscoverLearned())
        private set
    private var builder: DiscoverBuilder? = null

    fun builder(context: Context, api: AniListApi): DiscoverBuilder = builder ?: DiscoverBuilder(
        api,
        DiscoverFile.of(context.applicationContext),
        DiscoverSettings.get(context),
    ).also {
        builder = it
        learned = it.saved().learned
    }

    fun learn(context: Context, api: AniListApi, change: (DiscoverLearned) -> DiscoverLearned) {
        learned = builder(context, api).learn(change).learned
    }

    fun forget(context: Context, api: AniListApi) {
        learned = builder(context, api).forget().learned
    }
}

private fun kindLabel(kind: MediaKind) = if (kind == MediaKind.Anime) "anime" else "manga"

/** "For you" on Home: the best few suggestions for [kind]; nothing when there are none. */
@Composable
internal fun ForYouRow(kind: MediaKind, retained: Retained, number: Int): Boolean {
    val ui = LocalAniList.current
    val context = LocalContext.current
    val userId = ui.session.userId ?: return false
    val load = retained.get("forYou${kind.name}") {
        ui.load<List<Suggestion>> { Fetched(DiscoverMemory.builder(context, ui.api).suggestions(userId, kind)) }
    }
    val row = retained.get("forYouRow${kind.name}") { LazyListState() }
    val items = DiscoverBuilder.filtered(load.value.orEmpty(), DiscoverMemory.learned).filter { ui.entryFor(it.media.id) == null }
    val title = "For you · ${kindLabel(kind)}"
    when {
        items.isNotEmpty() -> {
            CoverRow(title, items.take(HOME_ROW), { it.media.id }, row, number) { s ->
                CoverCard(
                    title = s.media.title.display,
                    subtitle = s.short,
                    url = s.media.coverImage.best,
                    onClick = { ui.navigator.open(Route.Media(s.media.id)) },
                    wide = false,
                    mediaId = s.media.id,
                    badge = "${s.match}%",
                )
            }
            HomeLink("Discover more ${kindLabel(kind)} ›") { ui.navigator.open(Route.Discover(kind.ordinal)) }
            return true
        }
        load.loading && load.value == null -> {
            SectionTitle(title, number)
            Loading()
            return true
        }
    }
    return false
}

/** The vertical feed: one suggestion per screen, with its opening playing at the top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DiscoverPage(kindOrdinal: Int, retained: Retained) {
    val ui = LocalAniList.current
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    val settings = remember { DiscoverSettings.get(context) }
    val userId = ui.session.userId
    val start = if (MediaKind.entries.getOrNull(kindOrdinal) == MediaKind.Anime) MediaKind.Anime else MediaKind.Manga
    var kind by remember { mutableStateOf(if (settings.feed == DiscoverFeedKind.Manga) MediaKind.Manga else start) }
    val both = settings.feed == DiscoverFeedKind.Both
    val loads = listOf(MediaKind.Anime, MediaKind.Manga).associateWith { k ->
        retained.get("discover${k.name}") {
            ui.load<List<Suggestion>>(autoStart = false) { refresh ->
                Fetched(if (userId == null) emptyList() else DiscoverMemory.builder(context, ui.api).suggestions(userId, k, refresh))
            }
        }
    }
    val wanted = if (both) listOf(MediaKind.Anime, MediaKind.Manga) else listOf(kind)
    LaunchedEffect(wanted) { wanted.forEach { k -> loads.getValue(k).let { if (it.value == null && !it.loading) it.load() } } }
    val added = remember { mutableStateMapOf<Int, Boolean>() }
    val pending = remember { mutableStateMapOf<Int, Job>() }
    val list = remember(wanted.map { loads.getValue(it).value }, DiscoverMemory.learned) {
        val parts = wanted.map { DiscoverBuilder.filtered(loads.getValue(it).value.orEmpty(), DiscoverMemory.learned) }
        if (parts.size == 1) parts[0] else interleave(parts[0], parts[1])
    }.filter { ui.entryFor(it.media.id) == null || added.containsKey(it.media.id) }
    var why by remember { mutableStateOf<Suggestion?>(null) }
    var notForMe by remember { mutableStateOf<Suggestion?>(null) }
    var undo by remember { mutableStateOf<Pair<String, DiscoverLearned>?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    LaunchedEffect(undo) {
        if (undo != null) {
            delay(UNDO_MS)
            undo = null
        }
    }

    Box(Modifier.fillMaxSize().background(theme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                PillButton("‹ Back", onClick = { ui.navigator.back() }, filled = false)
                Spacer(Modifier.width(10.dp))
                BasicText("Discover", style = TextStyle(color = theme.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                if (!both) {
                    FilterChip("Anime", kind == MediaKind.Anime) { kind = MediaKind.Anime }
                    Spacer(Modifier.width(6.dp))
                    FilterChip("Manga", kind == MediaKind.Manga) { kind = MediaKind.Manga }
                    Spacer(Modifier.width(6.dp))
                }
                PillButton("↻", onClick = { wanted.forEach { loads.getValue(it).load(refresh = true) } }, filled = false)
                Spacer(Modifier.width(6.dp))
                PillButton("⚙", onClick = { settingsOpen = true }, filled = false)
            }
            val loading = wanted.any { loads.getValue(it).loading }
            val error = wanted.firstNotNullOfOrNull { loads.getValue(it).error }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    userId == null -> Empty("Log in to AniList to get suggestions", Modifier.align(Alignment.Center).padding(24.dp))
                    list.isNotEmpty() -> DiscoverFeed(list, settings, added, pending, onWhy = { why = it }, onNotForMe = { notForMe = it })
                    loading -> LookLoading("Looking through your lists…", Modifier.align(Alignment.Center), theme.foreground.copy(alpha = 0.7f))
                    error != null -> Box(Modifier.align(Alignment.Center)) { ErrorRow(error) { wanted.forEach { loads.getValue(it).load() } } }
                    else -> Empty("Nothing to suggest yet. Add a few more titles to your list (and score them) first.", Modifier.align(Alignment.Center).padding(24.dp))
                }
            }
        }
        undo?.let { (text, before) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().background(theme.surface, lookShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                BasicText(text, style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.weight(1f))
                PillButton("Undo", onClick = {
                    DiscoverMemory.learn(context, ui.api) { before }
                    undo = null
                })
            }
        }
        why?.let { s ->
            BackHandler { why = null }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { why = null })
            ReaderSheetFrame("Why ${s.media.title.display}", { why = null }, Modifier.align(Alignment.BottomCenter)) { WhySheet(s) }
        }
        notForMe?.let { s ->
            BackHandler { notForMe = null }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { notForMe = null })
            ReaderSheetFrame("Not for me", { notForMe = null }, Modifier.align(Alignment.BottomCenter)) {
                val before = DiscoverMemory.learned
                fun pick(text: String, change: (DiscoverLearned) -> DiscoverLearned) {
                    DiscoverMemory.learn(context, ui.api, change)
                    notForMe = null
                    undo = text to before
                }
                Column(Modifier.padding(bottom = 8.dp)) {
                    ChoiceRow("Just this one", "won't show it again") { pick("Hidden") { it.copy(hidden = it.hidden + s.media.id) } }
                    s.mainTag?.let { tag -> ChoiceRow("Less $tag", "the tag it leaned on most") { pick("Less $tag") { it.copy(lessTags = it.lessTags + tag) } } }
                    s.media.studios.nodes.firstNotNullOfOrNull { it.name }?.let { studio ->
                        ChoiceRow("Less from $studio", "the studio") { pick("Less from $studio") { it.copy(lessStudios = it.lessStudios + studio) } }
                    }
                    ChoiceRow("Seen it somewhere else", "hide it (no change to AniList)") { pick("Hidden") { it.copy(hidden = it.hidden + s.media.id) } }
                }
            }
        }
        if (settingsOpen) {
            BackHandler { settingsOpen = false }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { settingsOpen = false })
            ReaderSheetFrame("Discover", { settingsOpen = false }, Modifier.align(Alignment.BottomCenter)) {
                DiscoverSettingsBody(settings) { loads.values.forEach { it.load(refresh = true) } }
            }
        }
    }
}

private fun interleave(a: List<Suggestion>, b: List<Suggestion>): List<Suggestion> = buildList {
    for (i in 0 until maxOf(a.size, b.size)) {
        a.getOrNull(i)?.let(::add)
        b.getOrNull(i)?.let(::add)
    }
}

@Composable
private fun ChoiceRow(title: String, detail: String, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        BasicText(detail, style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiscoverFeed(
    list: List<Suggestion>,
    settings: DiscoverSettings,
    added: MutableMap<Int, Boolean>,
    pending: MutableMap<Int, Job>,
    onWhy: (Suggestion) -> Unit,
    onNotForMe: (Suggestion) -> Unit,
) {
    val ui = LocalAniList.current
    val context = LocalContext.current
    val pager = rememberPagerState { list.size }
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
        }
    }
    var muted by remember { mutableStateOf(true) }
    var opening by remember { mutableStateOf<Pair<Int, AnimeTheme>?>(null) }
    var showing by remember { mutableStateOf<Int?>(null) }
    var allowData by remember { mutableStateOf(settings.videoOnData) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        ThemeRadio.pause()
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
            if (event == Lifecycle.Event.ON_START && showing != null) player.play()
        }
        lifecycle.addObserver(watcher)
        onDispose {
            lifecycle.removeObserver(watcher)
            player.release()
        }
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    val current = list.getOrNull(pager.settledPage)
    LaunchedEffect(current?.media?.id, allowData) {
        player.stop()
        showing = null
        val s = current ?: return@LaunchedEffect
        if (DiscoverBuilder.kindOf(s.media) != MediaKind.Anime) {
            opening = null
            return@LaunchedEffect
        }
        val themes = withContext(Dispatchers.IO) { runCatching { Themes.shared(context).forMedia(listOf(s.media.id))[s.media.id] }.getOrNull().orEmpty() }
        val op = themes.firstOrNull { it.type == "OP" && it.videoUrl != null && !it.nsfw && !it.spoiler }
        opening = op?.let { s.media.id to it }
        if (op == null) return@LaunchedEffect
        val metered = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false
        if (metered && !allowData) return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(op.videoUrl!!))
        player.prepare()
        player.play()
        showing = s.media.id
    }

    VerticalPager(pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { list[it].media.id }) { index ->
        val s = list[index]
        val theme = LocalAppTheme.current
        val op = opening?.takeIf { it.first == s.media.id }?.second
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(0.42f).background(Color.Black)) {
                AsyncImage(
                    model = s.media.bannerImage ?: s.media.coverImage.best,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showing == s.media.id) {
                    AndroidView(
                        factory = { c ->
                            PlayerView(c).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                this.player = player
                            }
                        },
                        update = { it.player = player },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to theme.background)))
                if (op != null) {
                    BasicText(
                        "${op.slug}${op.song?.let { " · \"$it\"" }.orEmpty()}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = NumberStyle(color = Color.White, fontSize = 11.sp),
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    val label = when {
                        showing == s.media.id -> if (muted) "🔇" else "🔊"
                        else -> "▶ Opening"
                    }
                    BasicText(
                        label,
                        style = TextStyle(color = Color.White, fontSize = 15.sp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).background(Color.Black.copy(alpha = 0.6f))
                            .clickable { if (showing == s.media.id) muted = !muted else allowData = true }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
                BasicText(s.media.title.display, style = TextStyle(color = theme.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                BasicText(meta(s), style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (s.media.genres.take(3) + s.media.tags.filter { !it.isMediaSpoiler && !it.isGeneralSpoiler && (it.rank ?: 0) >= 60 }.take(2).map { it.name }).forEach {
                        BasicText(it, style = TextStyle(color = theme.foreground, fontSize = 12.sp), modifier = Modifier.border(1.dp, theme.panel, lookShape(4.dp)).padding(horizontal = 7.dp, vertical = 2.dp))
                    }
                }
                Row(Modifier.padding(top = 10.dp).fillMaxWidth().background(theme.primary.copy(alpha = 0.10f), lookShape(4.dp))) {
                    Box(Modifier.width(3.dp).height(IntrinsicHeightHint).background(theme.primary))
                    BasicText(
                        "${s.match}% match. ${explain(s)}",
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp, lineHeight = 19.sp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
                BasicText(
                    Formatting.cleanDescription(s.media.description),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.5.sp, lineHeight = 19.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    val id = s.media.id
                    when {
                        pending.containsKey(id) -> PillButton("Undo", onClick = {
                            pending.remove(id)?.cancel()
                            added.remove(id)
                        })
                        added[id] == true || ui.entryFor(id)?.status == ListStatus.PLANNING -> PillButton("On Planning ✓", onClick = {}, filled = false)
                        else -> PillButton("+ Planning", alive = true, onClick = {
                            added[id] = false
                            pending[id] = ui.scope.launch {
                                delay(ADD_UNDO_MS)
                                try {
                                    ui.sendOrQueue({ com.aniliphy.anilist.PendingWrite.Entry(id, s.media.title.display, DiscoverBuilder.kindOf(s.media) == MediaKind.Anime, ListStatus.PLANNING) }) {
                                        ui.api.saveEntry(id, ListStatus.PLANNING)
                                    }
                                    added[id] = true
                                    ui.navigator.listsChanged()
                                } catch (e: AniListException) {
                                    added.remove(id)
                                    context.showToast(ui.describe(e))
                                } finally {
                                    pending.remove(id)
                                }
                            }
                        })
                    }
                    PillButton("Not for me", onClick = { onNotForMe(s) }, filled = false)
                    PillButton("Why?", onClick = { onWhy(s) }, filled = false)
                    PillButton("Open ›", onClick = { ui.navigator.open(Route.Media(id)) }, filled = false)
                }
                BasicText(
                    "⌃ swipe up · ${index + 1} / ${list.size}",
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.5f), fontSize = 11.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 14.dp),
                )
            }
        }
    }
}

private val IntrinsicHeightHint = 46.dp

private fun meta(s: Suggestion): String {
    val m = s.media
    val count = when {
        m.episodes != null -> "${m.episodes} eps"
        m.chapters != null -> "${m.chapters} ch"
        else -> null
    }
    return listOfNotNull(
        m.format?.let { com.aniliphy.anilist.SearchFilters.formatLabel(it) },
        m.seasonYear?.toString(),
        count,
        m.studios.nodes.firstNotNullOfOrNull { it.name },
        m.averageScore?.let { "AniList $it%" },
    ).joinToString(" · ")
}

/** "Because you liked Frieren (9) and Natsume (8.5); you like Iyashikei." */
internal fun explain(s: Suggestion): String {
    val seeds = s.reasons.filter { it.kind == ReasonKind.Seed }.map { it.label.removePrefix("Like ") }.take(2)
    val feature = s.reasons.firstOrNull { it.weight > 0 && it.kind in setOf(ReasonKind.Tag, ReasonKind.Genre, ReasonKind.Studio, ReasonKind.Staff) }
        ?.label?.removeSuffix(" (tag)")
    val friends = s.reasons.firstOrNull { it.kind == ReasonKind.Friends }?.label
    val parts = buildList {
        if (seeds.isNotEmpty()) add("Because you liked ${seeds.joinToString(" and ")}")
        feature?.let { add(if (isEmpty()) "Because you like $it" else "you like $it") }
        friends?.let(::add)
    }
    return if (parts.isEmpty()) "Close to what you watch and read." else parts.joinToString("; ") + "."
}

@Composable
private fun WhySheet(s: Suggestion) {
    val theme = LocalAppTheme.current
    val biggest = s.reasons.maxOfOrNull { kotlin.math.abs(it.weight) }?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        s.reasons.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                BasicText(r.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.sp), modifier = Modifier.weight(1f))
                Box(Modifier.width((90 * kotlin.math.abs(r.weight) / biggest).dp.coerceAtLeast(3.dp)).height(8.dp).background(if (r.weight >= 0) theme.primary else theme.error))
                BasicText(
                    (if (r.weight >= 0) "+" else "−") + (kotlin.math.abs(r.weight) * 100).toInt(),
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp),
                    modifier = Modifier.width(44.dp).padding(start = 6.dp),
                )
            }
        }
        Hint("Worked out from your list. What you mark \"Not for me\" counts against.", Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun DiscoverSettingsBody(settings: DiscoverSettings, onChanged: () -> Unit) {
    val ui = LocalAniList.current
    val context = LocalContext.current
    Column(Modifier.padding(bottom = 8.dp)) {
        SwitchRow("Use the lists of people I follow", settings.useFollowed, {
            settings.useFollowed = it
            onChanged()
        }, description = "Reads up to 10 of them, once a day")
        SwitchRow("Openings on mobile data", settings.videoOnData, { settings.videoOnData = it })
        SegmentedRow("Feed", DiscoverFeedKind.entries, settings.feed, { it.label }, { settings.feed = it })
        val learned = DiscoverMemory.learned
        val count = learned.hidden.size + learned.lessTags.size + learned.lessStudios.size
        SettingsLinkRow(
            "What it learned",
            "${learned.hidden.size} hidden · ${learned.lessTags.size + learned.lessStudios.size} \"less like this\"" + if (count > 0) " · tap to reset" else "",
        ) {
            if (count > 0) {
                DiscoverMemory.forget(context, ui.api)
                onChanged()
            }
        }
    }
}

@Composable
private fun SettingsLinkRow(title: String, detail: String, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp)) {
        BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 15.sp))
        BasicText(detail, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp))
    }
}

/** Settings › Discover. */
@Composable
internal fun DiscoverSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DiscoverSettings.get(context) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Discover", onBack)
        DiscoverSettingsBody(settings) {}
    }
}

private const val HOME_ROW = 15
private const val ADD_UNDO_MS = 4_000L
private const val UNDO_MS = 5_000L
