package com.aniliphy.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.FavouriteTarget
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun MediaPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val loggedIn = ui.session.userId != null
    val load = retained.get("media") {
        ui.load<Media> {
            coroutineScope {
                val summary = async { ui.api.media(id) }
                val details = async { ui.api.details(id) }
                val a = summary.await()
                val b = details.await()
                Fetched(
                    b.value.copy(
                        description = a.value.description,
                        coverImage = a.value.coverImage,
                        bannerImage = a.value.bannerImage,
                        averageScore = a.value.averageScore,
                        episodes = a.value.episodes,
                        chapters = a.value.chapters,
                        volumes = a.value.volumes,
                        genres = a.value.genres,
                        siteUrl = a.value.siteUrl,
                        status = a.value.status,
                        countryOfOrigin = a.value.countryOfOrigin,
                        idMal = a.value.idMal,
                        title = b.value.title.copy(native = b.value.title.native ?: a.value.title.native),
                    ),
                    a.stale || b.stale,
                )
            }
        }
    }
    val progress = retained.get("progress") {
        ui.load<ListProgress>(autoStart = loggedIn) { Fetched(ui.api.progress(id)) }
    }
    val scroll = retained.get("scroll") { ScrollState(0) }
    var editing by remember { mutableStateOf(false) }
    val media = load.value

    val tappedCover = retained.get("tappedCover") { mutableStateOf(SharedCover.url) }.value
    // Used up here, so a page opened some other way (the widget, a link) doesn't show a stale cover while loading.
    androidx.compose.runtime.SideEffect { SharedCover.url = null }
    if (media != null || load.error == null) {
        StyledMediaPage(media, tappedCover, progress.value, loggedIn, retained, scroll, load.stale, onEdit = { editing = true }) { ui.navigator.back() }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BackHeader("Details") { ui.navigator.back() }
            ErrorRow(load.error!!) { load.load() }
        }
    }

    if (editing && media != null) {
        ListEditorDialog(
            media = media,
            current = progress.value,
            onSaved = {
                editing = false
                progress.load()
                ui.navigator.listsChanged()
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun FavouriteStar(media: Media, retained: Retained) {
    val target = if (media.kind == MediaKind.Anime) FavouriteTarget.Anime(media.id) else FavouriteTarget.Manga(media.id)
    Star(retained, media.isFavourite, target)
}

@Composable
internal fun Star(retained: Retained, initial: Boolean, target: FavouriteTarget) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favourite = retained.get("favourite") { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = if (favourite.value) "Remove from favourites" else "Add to favourites") {
                busy = true
                scope.launch {
                    try {
                        ui.api.toggleFavourite(target)
                        favourite.value = !favourite.value
                        ui.navigator.listsChanged()
                        context.showToast(if (favourite.value) "Added to favourites" else "Removed from favourites")
                    } catch (e: AniListException) {
                        context.showToast(ui.describe(e))
                    } finally {
                        busy = false
                    }
                }
            },
    ) {
        BasicText(
            if (favourite.value) "★" else "☆",
            style = TextStyle(color = if (favourite.value) theme.accent else theme.foreground, fontSize = 24.sp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBody(media: Media) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val open = ui.navigator::open
    val body = TextStyle(color = theme.foreground, fontSize = 14.sp, lineHeight = 20.sp)

    SectionTitle("Synopsis")
    BasicText(Formatting.cleanDescription(media.description), style = body, modifier = Modifier.padding(horizontal = 20.dp))

    if (media.genres.isNotEmpty()) {
        SectionTitle("Genres")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            media.genres.forEach { genre ->
                Box(
                    Modifier
                        .clip(lookShape(8.dp))
                        .then(
                            when (Look.style) {
                                UiStyle.Tankobon -> Modifier.background(theme.primary.copy(alpha = 0.14f))
                                UiStyle.Glass -> Modifier.glass(theme, lookShape(8.dp))
                                UiStyle.Jrpg -> Modifier.rpgWindow(theme, 8.dp)
                                UiStyle.Bunko, UiStyle.Stamp -> Modifier.border(1.dp, theme.pencil, lookShape(8.dp))
                                else -> Modifier.border(1.dp, theme.primary.copy(alpha = 0.4f), lookShape(8.dp))
                            },
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    BasicText(genre, style = TextStyle(color = theme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }

    SectionTitle("Information")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Formatting.infoRows(media).forEach { (label, value) ->
            Row {
                BasicText(
                    label,
                    style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.width(92.dp),
                )
                BasicText(value, style = TextStyle(color = theme.foreground, fontSize = 13.sp))
            }
        }
    }

    val (studios, producers) = Formatting.splitStudios(media)
    if (studios.isNotEmpty()) {
        SectionTitle("Studios")
        NamedLinks(studios.map { (it.name ?: "?") to it.id }) { open(Route.Studio(it)) }
    }
    if (producers.isNotEmpty()) {
        SectionTitle("Producers")
        NamedLinks(producers.map { (it.name ?: "?") to it.id }) { open(Route.Studio(it)) }
    }

    val characters = media.characters.edges.filter { it.node != null }
    if (characters.isNotEmpty()) {
        CoverRow("Characters", characters, { it.node!!.id }) { edge ->
            val node = edge.node!!
            CoverCard(
                title = node.name.full ?: "?",
                subtitle = listOfNotNull(
                    Formatting.nice(edge.role),
                    edge.voiceActors.firstOrNull()?.name?.full?.let { "VA: $it" },
                ).joinToString("\n"),
                url = node.image?.best,
                onClick = { open(Route.Character(node.id)) },
                wide = false,
            )
        }
    }
    val staff = media.staff.edges.filter { it.node != null }
    if (staff.isNotEmpty()) {
        CoverRow("Staff", staff, { "${it.node!!.id}:${it.role}" }) { edge ->
            val node = edge.node!!
            CoverCard(
                title = node.name.full ?: "?",
                subtitle = edge.role,
                url = node.image?.best,
                onClick = { open(Route.Staff(node.id)) },
                wide = false,
            )
        }
    }
    val relations = Formatting.relations(media)
    if (relations.isNotEmpty()) {
        CoverRow("Related", relations, { it.id }) { item ->
            CoverCard(item.title, item.kind, item.cover, { open(Route.Media(item.id)) }, wide = false)
        }
    }
    val recommendations = Formatting.recommendations(media)
    if (recommendations.isNotEmpty()) {
        CoverRow("Recommendations", recommendations, { it.id }) { item ->
            CoverCard(item.title, item.kind, item.cover, { open(Route.Media(item.id)) }, wide = false)
        }
    }
    media.siteUrl?.let { url ->
        Spacer(Modifier.height(8.dp))
        ActionRow("Open on AniList", onClick = { context.openLink(url) }, color = theme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamedLinks(items: List<Pair<String, Int>>, open: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        items.forEach { (name, id) ->
            Box(
                Modifier
                    .clip(lookShape(8.dp))
                    .background(theme.surface)
                    .clickable { open(id) }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                BasicText(name, style = TextStyle(color = theme.foreground, fontSize = 13.sp))
            }
        }
    }
}

/** The media page: the banner and the whole page take the cover's colour, blended into the theme. */
@Composable
private fun StyledMediaPage(
    media: Media?,
    /** The cover of the card that opened the page, shown before the page loads so the cover has somewhere to fly. */
    tappedCover: String?,
    progress: ListProgress?,
    loggedIn: Boolean,
    retained: Retained,
    scroll: ScrollState,
    stale: Boolean,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val base = LocalAppTheme.current
    val coverColor = remember(media?.coverImage?.color) { parseHexColor(media?.coverImage?.color) }
    val theme = remember(base, coverColor) { base.tintedBy(coverColor) }
    val density = LocalDensity.current
    val bannerPx = with(density) { BannerHeight.toPx() }
    CompositionLocalProvider(LocalAppTheme provides theme) {
        Box(Modifier.fillMaxSize()) {
            MediaBanner(media, coverColor, Modifier.graphicsLayer {
                translationY = -scroll.value * 0.5f
                alpha = (1f - scroll.value / bannerPx).coerceIn(0f, 1f)
            })
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
                Spacer(Modifier.statusBarsPadding().height(BannerHeight - 128.dp))
                if (media != null) {
                    StaleNotice(stale)
                    MediaHeader(media, progress, loggedIn, retained, onEdit)
                    DetailsBody(media)
                } else {
                    Row(Modifier.padding(horizontal = 16.dp)) { HeaderCover(tappedCover) }
                    Loading(Modifier.padding(top = 12.dp))
                }
            }
            // Once the page scrolls, a bar fades in behind the status bar and the back button so text doesn't run
            // under them.
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = (scroll.value / (bannerPx * 0.6f)).coerceIn(0f, 1f) }
                    .background(theme.background)
                    .statusBarsPadding()
                    .height(56.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(buttonShape())
                        .background(theme.background.copy(alpha = 0.6f))
                        .clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
                ) {
                    Canvas(Modifier.size(20.dp)) {
                        val u = size.minDimension / 24f
                        listOf(Offset(15f, 5f), Offset(15f, 19f)).forEach { end ->
                            drawLine(theme.ink, Offset(8f, 12f) * u, end * u, 2.4f * u, StrokeCap.Round)
                        }
                    }
                }
                if (media != null) {
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        Formatting.kindLabel(media.kind).uppercase(),
                        style = NumberStyle(color = theme.ink, fontSize = 12.sp, bold = true),
                        modifier = Modifier
                            .clip(if (Look.tankobon) RectangleShape else lookShape(12.dp))
                            .background(theme.background.copy(alpha = 0.6f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private val BannerHeight = 250.dp

@Composable
private fun MediaBanner(media: Media?, coverColor: Color?, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val ink = if (theme.dark) Color.White else Color.Black
    // Fades out at the bottom instead of ending on a colour, since the page behind it can show the animated scene.
    Box(
        modifier
            .fillMaxWidth()
            .height(BannerHeight)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        Box(Modifier.matchParentSize().background(lerp(theme.background, coverColor ?: theme.primary, if (theme.dark) 0.35f else 0.4f)))
        media?.bannerImage?.let { url ->
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().alpha(0.7f))
        }
        if (Look.tankobon) Box(Modifier.matchParentSize().screentone(ink.copy(alpha = 0.13f), spacing = 9.dp, radius = 2.dp))
        if (Look.style == UiStyle.Shinya) Box(Modifier.matchParentSize().scanlines())
        media?.title?.native?.takeIf { it.isNotBlank() && Look.tankobon }?.let { native ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp),
            ) {
                native.take(MAX_WATERMARK).forEach { char ->
                    BasicText(char.toString(), style = TextStyle(color = ink.copy(alpha = 0.16f), fontSize = 40.sp, lineHeight = 40.sp))
                }
            }
        }
    }
}

private const val MAX_WATERMARK = 8

@Composable
private fun MediaHeader(media: Media, progress: ListProgress?, loggedIn: Boolean, retained: Retained, onEdit: () -> Unit) {
    val theme = LocalAppTheme.current
    val anime = media.kind == MediaKind.Anime
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HeaderCover(media.coverImage.best)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(bottom = 2.dp)) {
            BasicText(media.title.display, style = TitleStyle(color = theme.ink, fontSize = if (Look.tankobon) 22.sp else 24.sp, lineHeight = 27.sp))
            Spacer(Modifier.height(6.dp))
            val facts = listOfNotNull(
                Formatting.format(media.format).takeIf { media.format != null },
                media.averageScore?.takeIf { it > 0 }?.let { "★ $it%" },
                if (anime) media.episodes?.let { "$it eps" } else media.chapters?.let { "$it ch" },
                if (anime) null else media.volumes?.let { "$it vols" },
            ).joinToString(" · ")
            BasicText(facts, style = NumberStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 12.sp))
        }
        if (loggedIn) FavouriteStar(media, retained)
    }

    val listed = loggedIn && progress?.status != null
    val next = Formatting.nextEpisode(media)?.takeIf { anime }
    if (Look.modern || Look.style == UiStyle.Glass) {
        ModernStats(media, progress.takeIf { listed })
    } else if (!Look.tankobon) {
        StyledStats(media, progress.takeIf { listed })
    } else if (listed || next != null) {
        Spacer(Modifier.height(14.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(CutCornerShape(bottomEnd = 8.dp))
                .background(theme.surface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (listed && progress != null) {
                val total = (if (anime) media.episodes else media.chapters)?.takeIf { it > 0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(ListStatus.label(progress.status, media.kind), style = TextStyle(color = theme.primary, fontSize = 13.sp), modifier = Modifier.weight(1f))
                    BasicText("${progress.progress} / ${total ?: "?"}", style = NumberStyle(color = theme.ink, fontSize = 13.sp, bold = true))
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(10.dp).background(theme.rule)) {
                    val fraction = fillOnShow(if (total != null) (progress.progress.toFloat() / total).coerceIn(0f, 1f) else 0f)
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(theme.primary.copy(alpha = 0.25f))
                            .screentone(theme.primary)
                            .drawWithContent {
                                drawContent()
                                drawRect(theme.primary, topLeft = Offset(size.width - 3.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                            },
                    )
                }
            }
            val score = if (listed) Formatting.score(progress?.score) else null
            if (score != null || next != null) {
                Spacer(Modifier.height(10.dp))
                Row {
                    if (score != null) {
                        BasicText("Your score ", style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 12.sp))
                        BasicText("$score/10", style = NumberStyle(color = theme.ink, fontSize = 12.sp, bold = true))
                    }
                    Spacer(Modifier.weight(1f))
                    media.nextAiringEpisode?.takeIf { anime && it.airingAt != null }?.let { airing ->
                        BasicText("Ep ${airing.episode} in ", style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 12.sp))
                        BasicText(Formatting.countdown(airing.airingAt!! - Formatting.nowSeconds()), style = NumberStyle(color = theme.ink, fontSize = 12.sp, bold = true))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        val novels = LocalNovels.current
        val manga = LocalManga.current
        val watcher = LocalAnime.current
        val nextNumber = nextToOpen(media, progress)
        PillButton(
            if (anime) "Watch" else "Read",
            onClick = {
                when {
                    anime -> watcher.watchMedia(media)
                    media.kind == MediaKind.LightNovel -> novels.readMedia(media)
                    else -> manga.readMedia(media)
                }
            },
            modifier = Modifier.weight(1f),
            extra = nextNumber?.let {
                when (media.kind) {
                    MediaKind.Anime -> "ep $it"
                    MediaKind.Manga -> "ch $it"
                    else -> null
                }
            },
        )
        if (loggedIn) {
            PillButton(
                if (progress?.status == null) "Add to list" else "Edit entry",
                onEdit,
                modifier = Modifier.weight(1f),
                filled = false,
                enabled = progress != null,
            )
        }
    }
}

@Composable
private fun HeaderCover(url: String?) {
    val theme = LocalAppTheme.current
    val shape = when (Look.style) {
        UiStyle.Tankobon -> CutCornerShape(bottomEnd = 16.dp)
        UiStyle.Genga, UiStyle.Konbini, UiStyle.Bunko -> RectangleShape
        else -> RoundedCornerShape(18.dp)
    }
    val flat = Look.style in setOf(UiStyle.Tankobon, UiStyle.Genga, UiStyle.Konbini)
    Box(
        Modifier
            .width(118.dp)
            .height(170.dp)
            .then(if (flat) Modifier else Modifier.shadow(16.dp, shape))
            .clip(shape)
            .background(theme.background)
            .padding(3.dp),
    ) {
        Cover(url, Modifier.fillMaxSize(), shared = true)
    }
}

/**
 * The episode or chapter Watch or Read will open, when it can be told for sure: the one after the AniList progress
 * for something being watched or read, unless the phone has its own place further on. Anything else (Planning,
 * Completed, a reset on opening, the end reached) goes through rules decided when it opens, so no number is shown.
 */
@Composable
private fun nextToOpen(media: Media, progress: ListProgress?): Int? {
    val anime = LocalAnime.current
    val manga = LocalManga.current
    val local by androidx.compose.runtime.produceState<Double?>(null, media.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (media.kind) {
                MediaKind.Anime -> anime.history.get(media.id)?.episodeNumber
                MediaKind.Manga -> manga.history.get(media.id)?.chapterNumber
                else -> null
            }
        }
    }
    if (progress == null || media.kind == MediaKind.LightNovel) return null
    if (progress.status != ListStatus.CURRENT && progress.status != ListStatus.REPEATING) return null
    val total = (if (media.kind == MediaKind.Anime) media.episodes else media.chapters)?.takeIf { it > 0 }
    if (total != null && progress.progress >= total) return null
    if ((local ?: 0.0) > progress.progress) return null
    return progress.progress + 1
}

/** Modern's row of tiles under the title: the score on a ring, your progress, and the next episode or the year. */
@Composable
private fun ModernStats(media: Media, progress: ListProgress?) {
    val theme = LocalAppTheme.current
    val anime = media.kind == MediaKind.Anime
    val total = (if (anime) media.episodes else media.chapters)?.takeIf { it > 0 }
    val unit = if (anime) "episodes" else "chapters"
    val airing = media.nextAiringEpisode?.takeIf { anime && it.airingAt != null }
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val tile = Modifier
            .weight(1f)
            .height(64.dp)
            .then(
                if (Look.style == UiStyle.Glass) {
                    Modifier.glass(theme, RoundedCornerShape(20.dp))
                } else {
                    Modifier.clip(RoundedCornerShape(18.dp)).background(theme.primary.copy(alpha = 0.12f))
                },
            )
            .padding(horizontal = 10.dp)
        media.averageScore?.takeIf { it > 0 }?.let { score ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = tile) {
                val fill = fillOnShow(score / 100f)
                Canvas(Modifier.size(36.dp)) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2f
                    val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
                    drawArc(theme.primary.copy(alpha = 0.22f), 0f, 360f, false, Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    drawArc(theme.primary, -90f, 360f * fill, false, Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
                }
                Spacer(Modifier.width(8.dp))
                StatText("$score%", "score")
            }
        }
        Column(verticalArrangement = Arrangement.Center, modifier = tile) {
            if (progress != null) StatText("${progress.progress}/${total ?: "?"}", unit) else StatText(total?.toString() ?: "?", unit)
        }
        val third = when {
            airing != null -> Formatting.countdown(airing.airingAt!! - Formatting.nowSeconds()).substringBeforeLast(' ') to "ep ${airing.episode} in"
            media.seasonYear != null -> media.seasonYear.toString() to "year"
            else -> null
        }
        third?.let { (value, label) ->
            Column(verticalArrangement = Arrangement.Center, modifier = tile) { StatText(value, label) }
        }
    }
    if (progress != null) {
        Spacer(Modifier.height(10.dp))
        val score = Formatting.score(progress.score)
        BasicText(
            ListStatus.label(progress.status, media.kind) + (score?.let { " · your score $it/10" } ?: ""),
            style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun StatText(value: String, label: String) {
    val theme = LocalAppTheme.current
    Column {
        BasicText(value, maxLines = 1, style = TitleStyle(color = theme.ink, fontSize = 17.sp))
        BasicText(label, maxLines = 1, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
    }
}
