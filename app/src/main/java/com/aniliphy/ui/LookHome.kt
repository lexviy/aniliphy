package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus

/**
 * Home's "Airing from your list", drawn the look's way. The cover looks keep the row of covers; the others turn it
 * into a list: a TV guide, a party menu, a departure board, a receipt or a stamp card.
 */
@Composable
internal fun AiringBlock(entries: List<ListEntry>, now: Long, number: Int, state: LazyListState) {
    val ui = LocalAniList.current
    val title = "Airing from your list"
    val open = { entry: ListEntry -> ui.navigator.open(Route.Media(entry.media.id)) }
    when (Look.style) {
        UiStyle.Tankobon, UiStyle.Modern, UiStyle.Genga, UiStyle.Bunko, UiStyle.Glass ->
            CoverRow(title = title, items = entries, key = { it.media.id }, state = state, number = number) { entry ->
                CoverCard(
                    title = entry.media.title.display,
                    subtitle = ListStatus.label(entry.status, entry.media.kind),
                    countdown = Formatting.airingLine(entry.media, now),
                    url = entry.media.coverImage.best,
                    onClick = { open(entry) },
                    wide = false,
                    repeats = entry.repeat,
                    footer = if (Look.style == UiStyle.Genga) {
                        { ProgressSteps(entry.progress, entry.media.episodes, Modifier.padding(top = 4.dp)) }
                    } else {
                        null
                    },
                )
            }
        UiStyle.Shinya -> {
            SectionTitle(title, number)
            TvGuide(entries, now, open)
        }
        UiStyle.Jrpg -> {
            SectionTitle(title, number)
            PartyWindow(entries, now, open)
        }
        UiStyle.Eki -> {
            SectionTitle(title, number)
            DepartureBoard(entries, now, open)
        }
        UiStyle.Konbini -> {
            SectionTitle(title, number)
            AiringReceipt(entries, now, open)
        }
        UiStyle.Stamp -> {
            SectionTitle(title, number)
            StampCards(entries, now, open)
        }
    }
}

/** Taps a row: its cover (if it shows one) flies to the media page like a card's does. */
@Composable
private fun rowTap(entry: ListEntry, open: (ListEntry) -> Unit): Pair<Any, () -> Unit> {
    val card = remember(entry.media.id) { Any() }
    return card to {
        SharedCover.take(card, entry.media.coverImage.best, acrossTabs = false)
        open(entry)
    }
}

@Composable
private fun RowCover(entry: ListEntry, card: Any, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width).height(height)) {
        Cover(entry.media.coverImage.best, Modifier.fillMaxSize(), shared = SharedCover.card === card)
    }
}

private fun nextIn(entry: ListEntry, now: Long): String? {
    val at = entry.media.nextAiringEpisode?.airingAt ?: return null
    val left = at - now
    return if (left <= 0) "out now" else Formatting.countdown(left)
}

private fun nextEpisode(entry: ListEntry): Int? = entry.media.nextAiringEpisode?.episode

/** Shin'ya: the airing list as tonight's TV guide, times in late-night notation. */
@Composable
private fun TvGuide(entries: List<ListEntry>, now: Long, open: (ListEntry) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.surface).lookAlive(Alive.Block)) {
        entries.forEachIndexed { index, entry ->
            val (card, tap) = rowTap(entry, open)
            val first = index == 0
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(tap)
                    .drawBehind {
                        if (first) drawRect(theme.error, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                        if (index < entries.lastIndex) drawLine(theme.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                val slot = entry.media.nextAiringEpisode?.airingAt?.let(::lateNightSlot)
                Column(Modifier.widthIn(min = 60.dp).padding(end = 8.dp)) {
                    BasicText(slot?.first ?: "—", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
                    BasicText(slot?.second ?: "--:--", maxLines = 1, style = NumberStyle(color = theme.ink, fontSize = 20.sp))
                }
                Box {
                    RowCover(entry, card, 40.dp, 54.dp)
                    Box(Modifier.width(40.dp).height(54.dp).clip(coverShape()).scanlines())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(entry.media.title.display, maxLines = 1, overflow = marqueeOverflow(), style = TextStyle(color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.lookMarquee())
                    val line = listOfNotNull(nextEpisode(entry)?.let { "EP $it" }, nextIn(entry, now)?.let { "in $it" }).joinToString(" · ")
                    if (line.isNotEmpty()) BasicText(line, maxLines = 1, style = NumberStyle(color = theme.primary, fontSize = 13.sp))
                }
                if (first) {
                    BasicText(
                        "NEXT",
                        style = NumberStyle(color = Color.White, fontSize = 11.sp),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(theme.error).padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** Faint TV scanlines over a picture. */
internal fun Modifier.scanlines(): Modifier = drawBehind {
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawLine(Color.Black.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
        y += step
    }
}

/** JRPG: the airing list as a party, progress as HP, the next episode on the right. */
@Composable
private fun PartyWindow(entries: List<ListEntry>, now: Long, open: (ListEntry) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().rpgWindow(theme).lookAlive(Alive.Block).padding(horizontal = 10.dp, vertical = 6.dp)) {
        entries.forEachIndexed { index, entry ->
            val (card, tap) = rowTap(entry, open)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().pressable(tap).padding(vertical = 6.dp),
            ) {
                BlinkingCursor(theme.accent, 13.sp, Modifier.width(16.dp), visible = index == 0)
                RowCover(entry, card, 40.dp, 54.dp, Modifier.border(2.dp, theme.foreground, coverShape()))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(entry.media.title.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
                    Spacer(Modifier.height(3.dp))
                    val total = entry.media.episodes?.takeIf { it in 1..MAX_STEPS * 4 }
                    if (total != null) {
                        ProgressSteps(entry.progress, total)
                    } else {
                        BasicText("LV ${entry.progress}", style = NumberStyle(color = theme.foreground, fontSize = 12.sp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.End) {
                    BasicText("NEXT", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
                    BasicText(nextIn(entry, now) ?: "?", maxLines = 1, style = NumberStyle(color = theme.ink, fontSize = 13.sp))
                }
            }
        }
    }
}

/** Eki: the airing list as a departure board, with split-flap times and episode numbers. */
@Composable
private fun DepartureBoard(entries: List<ListEntry>, now: Long, open: (ListEntry) -> Unit) {
    val head = NumberStyle(color = Color(0xFF9AA0AD), fontSize = 10.sp, bold = true)
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(EkiBoard).lookAlive(Alive.Block).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            BasicText("TIME", style = head, modifier = Modifier.width(84.dp))
            BasicText("TITLE", style = head, modifier = Modifier.weight(1f))
            BasicText("EP", style = head, modifier = Modifier.width(42.dp))
            BasicText("STATUS", style = head, modifier = Modifier.width(54.dp))
        }
        entries.forEachIndexed { index, entry ->
            val (_, tap) = rowTap(entry, open)
            val at = entry.media.nextAiringEpisode?.airingAt
            val time = at?.let {
                val t = java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault())
                "%02d:%02d".format(t.hour, t.minute)
            } ?: "--:--"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().pressable(tap)) {
                FlapText(time, Modifier.width(84.dp), key = "board-time-${entry.media.id}")
                BasicText(
                    entry.media.title.display,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(0xFFF2F3F5), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
                FlapText(nextEpisode(entry)?.let { "%02d".format(it % 100) } ?: "--", Modifier.width(42.dp), key = "board-ep-${entry.media.id}")
                val status = if (index == 0 && at != null) "NEXT" else at?.let { lateNightSlot(it).first.take(3) } ?: "—"
                BasicText(
                    status,
                    maxLines = 1,
                    style = TitleStyle(color = if (index == 0) EkiGo else Color(0xFFC3C7D0), fontSize = 13.sp),
                    modifier = Modifier.width(54.dp).lookBlink(LookSignals.departing(at, now)),
                )
            }
            if (index == 0) {
                nextIn(entry, now)?.let { BasicText("departs in $it", style = TextStyle(color = EkiGo.copy(alpha = 0.85f), fontSize = 11.sp), modifier = Modifier.lookBlink(LookSignals.departing(at, now))) }
            }
        }
    }
}

/** Characters on split-flap tiles, each with the hinge line across its middle. */
@Composable
internal fun FlapText(text: String, modifier: Modifier = Modifier, tile: Dp = 14.dp, key: String? = null) {
    val shown = flapping(text, key)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        shown.forEach { char ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .width(tile)
                    .height(tile * 1.45f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF26282E))
                    .drawBehind { drawLine(Color.Black.copy(alpha = 0.6f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f) },
            ) {
                BasicText(char.toString(), style = NumberStyle(color = EkiFlap, fontSize = (tile.value * 1.0f).sp, bold = true))
            }
        }
    }
}

/** Konbini: the airing list printed on a receipt. */
@Composable
private fun AiringReceipt(entries: List<ListEntry>, now: Long, open: (ListEntry) -> Unit) {
    val theme = LocalAppTheme.current
    val ink = theme.paperInk
    Column(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth().printOnce("receipt-airing").clip(ReceiptShape).background(theme.paper).lookAlive(Alive.Block).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { entry ->
            val (card, tap) = rowTap(entry, open)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().pressable(tap)) {
                RowCover(entry, card, 28.dp, 38.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        BasicText(entry.media.title.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = ink, fontSize = 13.sp, bold = true), modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.weight(0.2f).height(14.dp).padding(horizontal = 4.dp).leaders(ink.copy(alpha = 0.45f)))
                        BasicText(nextEpisode(entry)?.let { "EP %02d".format(it) } ?: "EP --", style = NumberStyle(color = ink, fontSize = 13.sp, bold = true))
                    }
                    Row {
                        BasicText(nextIn(entry, now)?.let { "next in $it" } ?: "date unknown", style = NumberStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp), modifier = Modifier.weight(1f))
                        BasicText("${entry.progress}/${entry.media.episodes ?: "?"}", style = NumberStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp))
                    }
                }
            }
        }
    }
}

/** Stamp rally: a card per show with a stamp for every episode seen. */
@Composable
private fun StampCards(entries: List<ListEntry>, now: Long, open: (ListEntry) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.forEachIndexed { index, entry ->
            val (card, tap) = rowTap(entry, open)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(tap)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.surface)
                    .lookAlive(Alive.Block, active = index == 0)
                    .drawBehind {
                        val x = 9.dp.toPx()
                        drawLine(
                            theme.hairline,
                            Offset(x, 10.dp.toPx()),
                            Offset(x, size.height - 10.dp.toPx()),
                            2.dp.toPx(),
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) {
                RowCover(entry, card, 58.dp, 82.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(entry.media.title.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.Black))
                    val total = entry.media.episodes
                    if (total != null && total in 1..MAX_STEPS) {
                        ProgressSteps(entry.progress, total)
                    } else {
                        BasicText("${entry.progress} stamped", style = TextStyle(color = theme.pencil, fontSize = 12.sp, fontWeight = FontWeight.Black))
                    }
                    val next = listOfNotNull(nextEpisode(entry)?.let { "ep $it" }, nextIn(entry, now)).joinToString(" · ")
                    if (next.isNotEmpty()) BasicText(next, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold))
                }
            }
        }
    }
}
