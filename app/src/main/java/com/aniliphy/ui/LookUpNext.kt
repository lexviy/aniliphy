package com.aniliphy.ui

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniliphy.WidgetOpen
import com.aniliphy.WidgetOpenRequest
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.core.ActivityDay
import com.aniliphy.core.ActivityLog
import com.aniliphy.core.ActivityStats
import com.aniliphy.widget.WidgetData
import com.aniliphy.widget.WidgetItem
import com.aniliphy.widget.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

/*
 * The top of Home, a different piece in every look: what to carry on with, drawn as a manga page (Tankōbon), a big
 * card and today's times (Modern), an animator's timesheet (Genga), a bookshelf (Bunko), a broadcast's "coming up"
 * (Shin'ya), a save point (JRPG), the next departure (Eki), the week's receipt (Konbini), today's stamp (Stamp rally)
 * and a frosted card (Glass). [airing] is null for guests, whose pieces then leave out what needs the list.
 */
@Composable
internal fun LookHomeTop(airing: List<ListEntry>?, now: Long) {
    val manga = LocalManga.current
    val anime = LocalAnime.current
    val context = LocalContext.current
    val items by produceState<List<WidgetItem>>(emptyList(), manga.historyVersion, anime.historyVersion) {
        value = withContext(Dispatchers.IO) {
            val snapshot = WidgetData.read(context)
            (snapshot.anime + snapshot.manga + snapshot.novels).sortedByDescending { it.updated }
        }
    }
    when (Look.style) {
        UiStyle.Tankobon -> PanelPage(items)
        UiStyle.Modern -> {
            UpNextCard()
            airing?.let { TodayChips(it, now) }
        }
        UiStyle.Genga -> airing?.takeIf { it.isNotEmpty() }?.let { Timesheet(it, now) }
        UiStyle.Bunko -> Shelf(items)
        UiStyle.Shinya -> items.firstOrNull()?.let { ComingUp(it, now) }
        UiStyle.Jrpg -> SavePoint(items)
        UiStyle.Eki -> airing?.let { NextDeparture(it, now) }
        UiStyle.Konbini -> WeekReceipt()
        UiStyle.Stamp -> TodayStamp()
        UiStyle.Glass -> items.firstOrNull()?.let { FrostedUpNext(it) }
    }
}

private fun open(item: WidgetItem) {
    WidgetOpenRequest.open = WidgetOpen(item.kind, item.key)
}

private fun kindLabel(kind: WidgetKind): String = when (kind) {
    WidgetKind.Anime -> "ANIME"
    WidgetKind.Manga -> "MANGA"
    WidgetKind.Novel -> "LIGHT NOVEL"
    WidgetKind.Recommendation -> "PICK"
}

/** The most recent of each kind, newest first: one for anime, manga and light novels. */
private fun onePerKind(items: List<WidgetItem>): List<WidgetItem> = items.distinctBy { it.kind }

@Composable
private fun ItemCover(item: WidgetItem, modifier: Modifier) {
    AsyncImage(model = item.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}

// ——— Tankōbon ———————————————————————————————————————————————————————————————————————————————————————————————————————

/** Tankōbon: a manga page of up to three panels, the biggest for what you last had open. */
@Composable
private fun PanelPage(items: List<WidgetItem>) {
    val shown = onePerKind(items).take(3)
    if (shown.isEmpty()) return
    SectionTitle("Up next")
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(206.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Panel(shown[0], big = true, modifier = Modifier.weight(if (shown.size > 1) 1.4f else 1f).fillMaxHeight().lookAlive(Alive.Block))
        if (shown.size > 1) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                shown.drop(1).forEach { Panel(it, big = false, modifier = Modifier.weight(1f).fillMaxWidth()) }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Panel(item: WidgetItem, big: Boolean, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val shape = CutCornerShape(bottomEnd = if (big) 18.dp else 12.dp)
    Box(
        modifier
            .pressable(onClick = { open(item) })
            .clip(shape)
            .background(theme.surface)
            .border(2.5.dp, theme.ink, shape),
    ) {
        ItemCover(item, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().screentone(theme.background.copy(alpha = 0.35f), spacing = 5.dp, radius = 0.9.dp))
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.35f to Color.Transparent, 1f to theme.background.copy(alpha = 0.95f)),
            ),
        )
        BasicText(
            kindLabel(item.kind),
            style = NumberStyle(color = theme.background, fontSize = 10.sp, bold = true),
            modifier = Modifier
                .padding(8.dp)
                .clip(SkewShape)
                .background(theme.ink)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
            BasicText(
                item.title,
                maxLines = if (big) 3 else 1,
                overflow = TextOverflow.Ellipsis,
                style = TitleStyle(color = theme.ink, fontSize = if (big) 19.sp else 14.sp, lineHeight = if (big) 22.sp else 16.sp),
            )
            BasicText(item.detail, maxLines = 1, style = NumberStyle(color = theme.primary, fontSize = 12.sp, bold = true))
        }
        if (big) {
            // A speech balloon: "to be continued".
            BasicText(
                "つづく",
                style = TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .border(1.5.dp, Color.Black, RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
    }
}

// ——— Modern ——————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Modern: what airs today, as chips with the time, under the big card. */
@Composable
private fun TodayChips(airing: List<ListEntry>, now: Long) {
    val theme = LocalAppTheme.current
    val ui = LocalAniList.current
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val todays = airing
        .mapNotNull { entry -> entry.media.nextAiringEpisode?.airingAt?.let { entry to it } }
        .filter { (_, at) -> Instant.ofEpochSecond(at).atZone(zone).toLocalDate() == today }
        .sortedBy { it.second }
    if (todays.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText("Today", style = TitleStyle(color = theme.ink, fontSize = 15.sp), modifier = Modifier.padding(end = 4.dp))
        todays.forEach { (entry, at) ->
            val time = Instant.ofEpochSecond(at).atZone(zone)
            val out = at <= now
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (out) theme.primary else lerp(theme.surface, theme.foreground, 0.05f))
                    .pressable(onClick = { ui.navigator.open(Route.Media(entry.media.id)) })
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                BasicText(
                    if (out) "Out" else "%02d:%02d".format(time.hour, time.minute),
                    style = NumberStyle(color = if (out) theme.background else theme.primary, fontSize = 12.sp, bold = true),
                )
                Spacer(Modifier.width(6.dp))
                BasicText(
                    entry.media.title.display,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = if (out) theme.background else theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                entry.media.nextAiringEpisode?.episode?.let {
                    Spacer(Modifier.width(6.dp))
                    BasicText("ep $it", style = NumberStyle(color = (if (out) theme.background else theme.foreground).copy(alpha = 0.7f), fontSize = 11.sp))
                }
            }
        }
    }
}

// ——— Genga ———————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Genga: the week as an exposure sheet, a row per show and its episode marked in the day it comes out. */
@Composable
private fun Timesheet(airing: List<ListEntry>, now: Long) {
    val theme = LocalAppTheme.current
    val ui = LocalAniList.current
    val zone = ZoneId.systemDefault()
    val rows = airing
        .mapNotNull { entry -> entry.media.nextAiringEpisode?.airingAt?.let { entry to Instant.ofEpochSecond(it).atZone(zone) } }
        .sortedBy { it.second }
        .take(7)
    if (rows.isEmpty()) return
    val today = LocalDate.now(zone).dayOfWeek
    val days = DayOfWeek.entries
    SectionTitle("Timesheet")
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(theme.background)
            .border(1.5.dp, theme.ink)
            .lookAlive(Alive.Block),
    ) {
        Row(Modifier.fillMaxWidth().height(24.dp).drawBehind { drawLine(theme.ink, Offset(0f, size.height), Offset(size.width, size.height), 1.5.dp.toPx()) }) {
            SheetCell(Modifier.weight(2.6f)) {
                BasicText("CUT / TITLE", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 9.sp, bold = true))
            }
            days.forEach { day ->
                val isToday = day == today
                SheetCell(Modifier.weight(1f).then(if (isToday) Modifier.hatch(theme.primary.copy(alpha = 0.3f)) else Modifier), center = true) {
                    BasicText(
                        day.getDisplayName(DateTextStyle.NARROW, Locale.ENGLISH),
                        style = NumberStyle(color = if (isToday) theme.primary else theme.foreground.copy(alpha = 0.7f), fontSize = 10.sp, bold = true),
                    )
                }
            }
        }
        rows.forEachIndexed { index, (entry, time) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .pressable(onClick = { ui.navigator.open(Route.Media(entry.media.id)) })
                    .drawBehind {
                        if (index < rows.lastIndex) drawLine(theme.hairline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    },
            ) {
                SheetCell(Modifier.weight(2.6f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText("%02d".format(index + 1), style = NumberStyle(color = theme.primary, fontSize = 10.sp, bold = true))
                        Spacer(Modifier.width(5.dp))
                        BasicText(entry.media.title.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    }
                }
                days.forEach { day ->
                    val isToday = day == today
                    val here = time.dayOfWeek == day
                    SheetCell(Modifier.weight(1f).then(if (isToday) Modifier.hatch(theme.primary.copy(alpha = 0.18f)) else Modifier), center = true) {
                        if (here) {
                            val next = entry.media.nextAiringEpisode
                            val out = (next?.airingAt ?: Long.MAX_VALUE) <= now
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(2.dp)
                                    .background(if (out) theme.primary else theme.primary.copy(alpha = 0.22f))
                                    .border(1.dp, theme.primary),
                            ) {
                                BasicText(
                                    next?.episode?.toString() ?: "•",
                                    style = NumberStyle(color = if (out) theme.background else theme.primary, fontSize = 10.sp, bold = true),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    BasicText(
        "Numbers are the next episode · filled = out",
        style = NumberStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 10.sp),
        modifier = Modifier.padding(start = 18.dp, top = 4.dp),
    )
}

@Composable
private fun SheetCell(modifier: Modifier, center: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        contentAlignment = if (center) Alignment.Center else Alignment.CenterStart,
        modifier = modifier
            .fillMaxHeight()
            .drawBehind { drawLine(theme.hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = if (center) 0.dp else 6.dp),
        content = content,
    )
}

private fun Modifier.hatch(color: Color): Modifier = drawBehind {
    val step = 5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(color, Offset(x, size.height), Offset(x + size.height, 0f), 1.dp.toPx())
        x += step
    }
}

// ——— Bunko ———————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Bunko: what you're reading and watching as spines on a shelf, each with an obi band saying where you are. */
@Composable
private fun Shelf(items: List<WidgetItem>) {
    val shown = items.take(12)
    if (shown.isEmpty()) return
    val theme = LocalAppTheme.current
    SectionTitle("On the shelf")
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            shown.forEachIndexed { index, item -> Spine(item, index) }
        }
        // The shelf board.
        Box(Modifier.fillMaxWidth().height(7.dp).background(lerp(theme.surface, theme.foreground, 0.18f)))
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.18f)))
    }
}

@Composable
private fun Spine(item: WidgetItem, index: Int) {
    val theme = LocalAppTheme.current
    val tones = listOf(theme.primary, theme.secondary, theme.accent, theme.deepPrimary, lerp(theme.surface, theme.foreground, 0.25f))
    val tone = tones[(item.title.hashCode() and 0x7fffffff) % tones.size]
    val heights = listOf(188, 176, 194, 182, 170, 190)
    val height = heights[index % heights.size].dp
    val text = if (tone.luminance() > 0.5f) Color(0xFF1B1B1F) else Color(0xFFF6F2EA)
    Column(
        Modifier
            .width(44.dp)
            .height(height)
            .pressable(onClick = { open(item) })
            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            .background(tone)
            .lookAlive(Alive.Block, active = index == 0)
            .drawBehind {
                drawLine(Color.White.copy(alpha = 0.2f), Offset(3.dp.toPx(), 0f), Offset(3.dp.toPx(), size.height), 1.dp.toPx())
                drawLine(Color.Black.copy(alpha = 0.25f), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 2.dp.toPx())
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        BasicText(
            when (item.kind) {
                WidgetKind.Anime -> "映"
                WidgetKind.Manga -> "漫"
                else -> "文"
            },
            style = TextStyle(color = text.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold),
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            BasicText(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TitleStyle(color = text, fontSize = 13.sp),
                modifier = Modifier.sideways(),
            )
        }
        // The obi: a paper band round the bottom of the book with where you stopped.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().height(44.dp).background(Color(0xFFF2E9D8)).padding(horizontal = 2.dp),
        ) {
            BasicText(
                shortDetail(item.detail),
                maxLines = 2,
                style = NumberStyle(color = Color(0xFF8A1C1C), fontSize = 9.sp, bold = true).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 10.sp),
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** "Ep 7 · 12:34" → "Ep 7": what fits on an obi. */
private fun shortDetail(detail: String): String = detail.substringBefore(" · ").take(14)

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/** Lays text along the spine, turned a quarter. */
private fun Modifier.sideways(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(
        Constraints(maxWidth = constraints.maxHeight.coerceAtLeast(0), maxHeight = constraints.maxWidth.coerceAtLeast(0)),
    )
    layout(placeable.height, placeable.width) {
        placeable.placeWithLayer((placeable.height - placeable.width) / 2, (placeable.width - placeable.height) / 2) {
            rotationZ = 90f
        }
    }
}

// ——— Shin'ya —————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Shin'ya: "coming up next", as the broadcast announces it, with the station clock in late-night hours. */
@Composable
private fun ComingUp(item: WidgetItem, now: Long) {
    val theme = LocalAppTheme.current
    val (day, clock) = lateNightSlot(now)
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF07080C))
            .lookAlive(Alive.Block)
            .pressable(onClick = { open(item) }),
    ) {
        Box(Modifier.width(118.dp).fillMaxHeight()) {
            ItemCover(item, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().scanlines())
            BasicText(
                "NEXT",
                style = NumberStyle(color = Color.White, fontSize = 11.sp),
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(4.dp)).background(theme.error).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // On air when an episode from the list airs within the hour, off air otherwise.
                val onAir = LookSignals.airingSoon
                BasicText(
                    if (onAir) "ON AIR" else "OFF AIR",
                    style = NumberStyle(color = if (onAir) Color.White else Color.White.copy(alpha = 0.6f), fontSize = 10.sp),
                    modifier = Modifier.lookBlink(onAir).clip(RoundedCornerShape(3.dp)).background(if (onAir) theme.error else Color.White.copy(alpha = 0.15f)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                BasicText("COMING UP · 次回", style = NumberStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp))
                Spacer(Modifier.weight(1f))
                BasicText("$day $clock", style = NumberStyle(color = Color(0xFF8EF0FF), fontSize = 14.sp))
            }
            BasicText(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = Color.White, fontSize = 18.sp, lineHeight = 21.sp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(kindLabel(item.kind), style = NumberStyle(color = theme.primary, fontSize = 11.sp))
                Spacer(Modifier.width(8.dp))
                BasicText(item.detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp))
            }
        }
    }
}

// ——— JRPG ————————————————————————————————————————————————————————————————————————————————————————————————————————————

/** JRPG: a save point. Each slot is something to carry on, its "HP" how far through it you are. */
@Composable
private fun SavePoint(items: List<WidgetItem>) {
    val shown = onePerKind(items).take(3)
    if (shown.isEmpty()) return
    val theme = LocalAppTheme.current
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .rpgWindow(theme)
            .lookAlive(Alive.Block)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("✦ SAVE POINT", style = NumberStyle(color = theme.accent, fontSize = 13.sp))
            Spacer(Modifier.weight(1f))
            BasicText("Continue?", style = NumberStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp))
        }
        shown.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().pressable(onClick = { open(item) })) {
                BlinkingCursor(theme.accent, 13.sp, visible = index == 0)
                Spacer(Modifier.width(6.dp))
                BasicText("FILE ${index + 1}", style = NumberStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = Color.White, fontSize = 14.sp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(shortDetail(item.detail), style = NumberStyle(color = theme.accent, fontSize = 11.sp))
                        item.percent?.let { percent ->
                            Spacer(Modifier.width(8.dp))
                            BasicText("HP", style = NumberStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp))
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
                                Box(Modifier.fillMaxWidth(fillOnShow(percent / 100f)).fillMaxHeight().background(if (percent > 30) EkiGo else theme.warning))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ——— Eki —————————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Eki: the next episode to come out on your list, as the big "next departure" line at the top of the board. */
@Composable
private fun NextDeparture(airing: List<ListEntry>, now: Long) {
    val (next, airingAt) = airing
        .mapNotNull { entry -> entry.media.nextAiringEpisode?.airingAt?.takeIf { it > now }?.let { entry to it } }
        .minByOrNull { it.second }
        ?: return
    val episode = next.media.nextAiringEpisode?.episode
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val at = Instant.ofEpochSecond(airingAt).atZone(ZoneId.systemDefault())
    val title = next.media.title.display.uppercase().filter { it.isLetterOrDigit() || it == ' ' }.take(12).padEnd(12)
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EkiBoard)
            .lookAlive(Alive.Block)
            .pressable(onClick = { ui.navigator.open(Route.Media(next.media.id)) })
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("次発", style = TextStyle(color = EkiFlap, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(6.dp))
            // The first trains of the morning and the last ones at night are announced as such.
            val hour = java.time.LocalTime.now().hour
            BasicText(
                when {
                    hour in 5..8 -> "FIRST TRAIN · NEXT DEPARTURE"
                    hour >= 23 || hour < 1 -> "LAST TRAIN · NEXT DEPARTURE"
                    else -> "NEXT DEPARTURE"
                },
                style = NumberStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, bold = true),
            )
            Spacer(Modifier.weight(1f))
            BasicText(
                "in " + Formatting.countdown(airingAt - now),
                style = NumberStyle(color = EkiGo, fontSize = 12.sp, bold = true),
                modifier = Modifier.lookBlink(LookSignals.departing(airingAt, now)),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlapText("%02d:%02d".format(at.hour, at.minute), tile = 17.dp, key = "eki-next-time")
            Spacer(Modifier.width(10.dp))
            Column {
                FlapText(title, tile = 12.dp, key = "eki-next-title")
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge("EP", episode?.let { "%02d".format(it % 100) } ?: "--", theme.ekiLine(next.media.id), 22.dp)
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        at.dayOfWeek.getDisplayName(DateTextStyle.SHORT, Locale.ENGLISH).uppercase(),
                        style = NumberStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, bold = true),
                    )
                }
            }
        }
    }
}

// ——— Konbini —————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Konbini: this week's watching and reading, rung up on a receipt. */
@Composable
private fun WeekReceipt() {
    val context = LocalContext.current
    val manga = LocalManga.current
    val anime = LocalAnime.current
    val summary by produceState<ActivityStats.Summary?>(null, manga.historyVersion, anime.historyVersion) {
        value = withContext(Dispatchers.IO) { ActivityStats.summary(ActivityLog.all(context), LocalDate.now()) }
    }
    val week = summary?.week ?: return
    val theme = LocalAppTheme.current
    val ink = theme.paperInk
    val today = LocalDate.now()
    Column(
        Modifier
            .padding(start = 18.dp, end = 18.dp, top = 10.dp)
            .fillMaxWidth()
            .printOnce("receipt-week")
            .clip(ReceiptShape)
            .background(theme.paper)
            .lookAlive(Alive.Block)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        BasicText(
            "ANILIPHY MART · 週間レシート",
            style = NumberStyle(color = ink, fontSize = 13.sp, bold = true),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        BasicText(
            "WEEK ${today.minusDays(6)} — $today",
            style = NumberStyle(color = ink.copy(alpha = 0.65f), fontSize = 10.sp),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        DashedRule(ink.copy(alpha = 0.5f))
        ReceiptLine("ANIME WATCHED", if (week.animeMs > 0) ActivityStats.duration(week.animeMs) else "0 min", ink)
        // The counts run up like a register's display the first time the receipt shows.
        ReceiptLine("MANGA CHAPTERS", "x ${countUp(week.mangaChapters, "receipt-manga")}", ink)
        ReceiptLine("NOVEL CHAPTERS", "x ${countUp(week.novelChapters, "receipt-novel")}", ink)
        DashedRule(ink.copy(alpha = 0.5f))
        ReceiptLine("STREAK", "${countUp(summary?.streak ?: 0, "receipt-streak")} DAYS", ink, bold = true)
        ReceiptLine("TOTAL", "¥0", ink, bold = true)
        BasicText(
            if (week.isEmpty) "NOTHING RUNG UP YET · SEE YOU SOON" else "THANK YOU · またお越しください",
            style = NumberStyle(color = ink.copy(alpha = 0.7f), fontSize = 10.sp),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp),
        )
    }
}

@Composable
private fun ReceiptLine(label: String, value: String, ink: Color, bold: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        BasicText(label, style = NumberStyle(color = ink, fontSize = 12.sp, bold = bold))
        Box(Modifier.weight(1f).height(12.dp).padding(horizontal = 4.dp).leaders(ink.copy(alpha = 0.4f)))
        BasicText(value, style = NumberStyle(color = ink, fontSize = 12.sp, bold = bold))
    }
}

// ——— Stamp rally —————————————————————————————————————————————————————————————————————————————————————————————————————

/** Stamp rally: today's stamp, inked once you've watched or read something today, and the week's card beside it. */
@Composable
private fun TodayStamp() {
    val context = LocalContext.current
    val manga = LocalManga.current
    val anime = LocalAnime.current
    val summary by produceState<ActivityStats.Summary?>(null, manga.historyVersion, anime.historyVersion) {
        value = withContext(Dispatchers.IO) { ActivityStats.summary(ActivityLog.all(context), LocalDate.now()) }
    }
    val shown = summary ?: return
    val theme = LocalAppTheme.current
    val today = LocalDate.now()
    val done = shown.lastWeek.lastOrNull()?.second?.isEmpty == false
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(theme.paper)
            .lookAlive(Alive.Block, active = !done)
            .padding(14.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            if (done) {
                Box(Modifier.stampDown("today-${today}")) {
                    StampMark("済", theme.pencil, 92.dp, tilt = -8f, small = "%d.%d".format(today.monthValue, today.dayOfMonth))
                }
            } else {
                Canvas(Modifier.size(88.dp)) {
                    drawCircle(
                        theme.paperInk.copy(alpha = 0.35f),
                        size.minDimension / 2 - 2.dp.toPx(),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                }
                BasicText("TODAY", style = NumberStyle(color = theme.paperInk.copy(alpha = 0.5f), fontSize = 12.sp, bold = true))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                if (done) "Today's stamp is in" else "No stamp yet today",
                style = TitleStyle(color = theme.paperInk, fontSize = 16.sp),
            )
            BasicText(
                if (shown.streak > 1) "${shown.streak} days in a row" else "Watch or read anything to stamp it",
                style = TextStyle(color = theme.paperInk.copy(alpha = 0.7f), fontSize = 12.sp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                shown.lastWeek.forEachIndexed { index, (day, activity) ->
                    WeekDot(day, activity, index)
                }
            }
        }
    }
}

@Composable
private fun WeekDot(day: LocalDate, activity: ActivityDay, index: Int) {
    val theme = LocalAppTheme.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .then(
                    if (activity.isEmpty) {
                        Modifier.drawBehind {
                            drawCircle(theme.paperInk.copy(alpha = 0.3f), size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))))
                        }
                    } else {
                        Modifier.stampDown("week-$day", index).clip(CircleShape).background(theme.pencil.copy(alpha = 0.85f))
                    },
                ),
        ) {
            if (!activity.isEmpty) BasicText("✓", style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        }
        BasicText(
            day.dayOfWeek.getDisplayName(DateTextStyle.NARROW, Locale.ENGLISH),
            style = NumberStyle(color = theme.paperInk.copy(alpha = 0.6f), fontSize = 9.sp),
        )
    }
}

// ——— Glass ———————————————————————————————————————————————————————————————————————————————————————————————————————————

/** Glass: what you were last on, a pane of frosted glass lying over its own blurred cover. */
@Composable
private fun FrostedUpNext(item: WidgetItem) {
    val theme = LocalAppTheme.current
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .height(210.dp)
            .pressable(onClick = { open(item) })
            .clip(shape)
            .lookAlive(Alive.Block),
    ) {
        ItemCover(
            item,
            Modifier
                .fillMaxSize()
                .then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(28.dp) else Modifier.alpha(0.55f)),
        )
        Box(Modifier.fillMaxSize().background(theme.background.copy(alpha = 0.25f)))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth().glass(theme, RoundedCornerShape(20.dp)).padding(12.dp),
        ) {
            ItemCover(item, Modifier.width(58.dp).height(82.dp).clip(RoundedCornerShape(12.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText(kindLabel(item.kind), style = TextStyle(color = theme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                BasicText(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 17.sp, lineHeight = 20.sp))
                BasicText(item.detail, maxLines = 1, style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 12.sp))
                item.percent?.let { percent ->
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(theme.foreground.copy(alpha = 0.15f))) {
                        Box(Modifier.fillMaxWidth(fillOnShow(percent / 100f)).fillMaxHeight().background(theme.primary))
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(46.dp).clip(CircleShape).background(theme.primary),
            ) {
                Canvas(Modifier.size(16.dp)) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.1f)
                        lineTo(size.width * 0.92f, size.height * 0.5f)
                        lineTo(size.width * 0.2f, size.height * 0.9f)
                        close()
                    }
                    drawPath(path, theme.background)
                }
            }
        }
    }
}
