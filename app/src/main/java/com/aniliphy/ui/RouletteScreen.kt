package com.aniliphy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Roulette
import com.aniliphy.anilist.RouletteFilter
import com.aniliphy.anilist.RouletteLength
import com.aniliphy.anilist.SessionState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.random.Random

private val CARD_W = 86.dp
private val CARD_GAP = 8.dp
private const val SPIN_MS = 3200

private val FORMAT_CHOICES = mapOf(
    MediaKind.Anime to listOf("TV" to setOf("TV", "TV_SHORT"), "Movie" to setOf("MOVIE"), "ONA & OVA" to setOf("ONA", "OVA", "SPECIAL")),
    MediaKind.Manga to listOf("Manga" to setOf("MANGA"), "One-shot" to setOf("ONE_SHOT")),
    MediaKind.LightNovel to emptyList(),
)

/** "What do I start next?": a reel of your Planning list that stops on one. */
@Composable
internal fun RoulettePage(kindOrdinal: Int, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val view = LocalView.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val filter: MutableState<RouletteFilter> = retained.get("filter") {
        mutableStateOf(RouletteFilter(kind = MediaKind.entries.getOrElse(kindOrdinal) { MediaKind.Anime }))
    }
    val winner: MutableState<Int?> = retained.get("winner") { mutableStateOf(null) }
    val reel: MutableState<List<ListEntry>> = retained.get("reel") { mutableStateOf(emptyList()) }
    val offset = retained.get("offset") { Animatable(0f) }
    var spinning by remember { mutableStateOf(false) }
    var landed by remember { mutableStateOf(winner.value != null) }
    var genresOpen by remember { mutableStateOf(false) }
    var boxPx by remember { mutableStateOf(0f) }

    val f = filter.value
    val planning = ui.onList.entries.values.filter { it.status == com.aniliphy.anilist.ListStatus.PLANNING && it.media.kind == f.kind }
    val candidates = remember(ui.onList.entries.size, f) { Roulette.candidates(ui.onList.entries.values, f) }
    val cardPx = with(density) { (CARD_W + CARD_GAP).toPx() }

    fun spin() {
        if (spinning || candidates.isEmpty()) return
        val pick = Roulette.pick(candidates, Random.Default, avoid = winner.value) ?: return
        reel.value = Roulette.reel(candidates, pick, Random.Default)
        winner.value = pick.media.id
        landed = false
        spinning = true
        scope.launch {
            offset.snapTo(0f)
            val target = (reel.value.size - 1) * cardPx + with(density) { CARD_W.toPx() } / 2 - boxPx / 2
            offset.animateTo(target, tween(Motion.ms(SPIN_MS), easing = CubicBezierEasing(0.12f, 0.75f, 0.15f, 1f)))
            spinning = false
            landed = true
            Look.style.let { style ->
                LookHaptics.moment(view, style)
                LookSounds.done(style)
            }
        }
    }

    // A soft tap each time a cover passes the pin, while it spins.
    LaunchedEffect(spinning) {
        if (!spinning) return@LaunchedEffect
        snapshotFlow { (offset.value / cardPx).toInt() }.distinctUntilChanged().collect { LookSounds.tap() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Planning roulette") { ui.navigator.back() }
        if (ui.session.state !is SessionState.LoggedIn) {
            LoginInvite("Log in to spin your Planning list")
            return@Column
        }
        val kinds = retained.get("kinds") { LazyListState() }
        LazyRow(state = kinds, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MediaKind.entries, key = { it.name }) { k ->
                FilterChip(Formatting.kindLabel(k), f.kind == k) {
                    if (f.kind != k) filter.value = RouletteFilter(kind = k, length = f.length)
                    winner.value = null
                    landed = false
                }
            }
        }
        val lengths = retained.get("lengths") { LazyListState() }
        LazyRow(state = lengths, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            items(RouletteLength.entries, key = { it.name }) { l -> FilterChip(l.label, f.length == l) { filter.value = f.copy(length = l) } }
        }
        val more = retained.get("more") { LazyListState() }
        LazyRow(state = more, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            item { FilterChip(f.genre?.let { "$it ▾" } ?: "Genre ▾", f.genre != null) { genresOpen = true } }
            item { FilterChip("Any format", f.formats == null) { filter.value = f.copy(formats = null) } }
            items(FORMAT_CHOICES[f.kind].orEmpty(), key = { it.first }) { (label, formats) ->
                FilterChip(label, f.formats == formats) { filter.value = f.copy(formats = if (f.formats == formats) null else formats) }
            }
        }

        if (candidates.isEmpty()) {
            Hint(
                if (planning.isEmpty()) "Your Planning list is empty. Add some from Seasons or recommendations first."
                else "Nothing on Planning fits these filters. Try another length, genre or format.",
                Modifier.padding(20.dp),
            )
            return@Column
        }

        BoxWithConstraints(
            Modifier
                .padding(top = 14.dp)
                .fillMaxWidth()
                .height(150.dp)
                .background(theme.surface.copy(alpha = 0.85f)),
        ) {
            val width = maxWidth
            boxPx = with(density) { width.toPx() }
            val shown = remember(reel.value, candidates) {
                // A few after the last one too, so the stop doesn't leave an empty end.
                reel.value.ifEmpty { candidates.shuffled(Random(candidates.size)).take(8) } + candidates.shuffled(Random(reel.value.size + 7)).take(4)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .graphicsLayer { translationX = -offset.value },
            ) {
                shown.forEach { e -> Cover(e.media.coverImage.best, Modifier.size(width = CARD_W, height = 122.dp)) }
            }
            Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().background(theme.accent))
            LaunchedEffect(Unit) { if (winner.value == null) spin() }
        }

        val picked = candidates.firstOrNull { it.media.id == winner.value } ?: reel.value.lastOrNull()
        if (landed && picked != null) {
            BasicText(
                LookWords.landed(Look.style.takeIf { LookPrefs.moments }),
                style = NumberStyle(color = theme.primary, fontSize = 12.sp, bold = true),
                modifier = Modifier.padding(start = 20.dp, top = 14.dp),
            )
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(lookShape(10.dp))
                    .background(theme.surface.copy(alpha = 0.9f))
                    .clickable { ui.navigator.open(Route.Media(picked.media.id)) }
                    .padding(10.dp),
            ) {
                Cover(picked.media.coverImage.best, Modifier.size(width = 56.dp, height = 78.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val m = picked.media
                    BasicText(m.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    val size = when {
                        m.kind == MediaKind.Anime && m.episodes != null -> "${m.episodes} eps" + (Roulette.size(m)?.let { " · ~${(it + 30) / 60} h" } ?: "")
                        m.chapters != null -> "${m.chapters} ch"
                        m.volumes != null -> "${m.volumes} vols"
                        else -> null
                    }
                    BasicText(listOfNotNull(m.format?.let { Formatting.format(it) }, size).joinToString(" · "), style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
                    BasicText(m.genres.take(3).joinToString(" · "), style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
                    picked.createdAt?.takeIf { it > 0 }?.let {
                        BasicText("On Planning since " + java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH)), style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
                    }
                }
            }
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(if (spinning) "Spinning…" else if (winner.value == null) "Spin" else "Spin again", onClick = { spin() }, enabled = !spinning)
            if (landed && picked != null) PillButton("Open title", onClick = { ui.navigator.open(Route.Media(picked.media.id)) }, filled = false)
        }
        Hint(
            "${candidates.size} on your Planning list match. Opening the title changes nothing on your list.",
            Modifier.padding(horizontal = 20.dp),
        )
    }

    if (genresOpen) {
        val genres = planning.flatMap { it.media.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        androidx.compose.ui.window.Dialog(onDismissRequest = { genresOpen = false }) {
            Column(Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).verticalScroll(rememberScrollState()).padding(vertical = 10.dp)) {
                (listOf<String?>(null) + genres).forEach { g ->
                    BasicText(
                        g ?: "Any genre",
                        style = TextStyle(color = if (f.genre == g) theme.primary else theme.foreground, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().clickable { filter.value = f.copy(genre = g); genresOpen = false }.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
