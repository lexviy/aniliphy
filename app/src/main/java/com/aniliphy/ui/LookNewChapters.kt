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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A manga on the reading list with a chapter out past where the reader stopped. */
internal class NewChapter(val id: Int, val title: String, val cover: String?, val readUpTo: String?, val latest: String)

/** Whether this look shows Home's lists as rows rather than covers. */
internal val Look.listHome: Boolean
    get() = when (style) {
        UiStyle.Tankobon, UiStyle.Modern, UiStyle.Genga, UiStyle.Bunko, UiStyle.Glass -> false
        UiStyle.Shinya, UiStyle.Jrpg, UiStyle.Eki, UiStyle.Konbini, UiStyle.Stamp -> true
    }

/**
 * Home's "New chapters" in the list looks, the same forms as their airing list: a TV guide, a party window, a
 * departure board, a receipt and stamp cards.
 */
@Composable
internal fun NewChaptersList(items: List<NewChapter>, number: Int, open: (NewChapter) -> Unit) {
    SectionTitle("New chapters", number)
    when (Look.style) {
        UiStyle.Shinya -> GuideChapters(items, open)
        UiStyle.Jrpg -> QuestChapters(items, open)
        UiStyle.Eki -> BoardChapters(items, open)
        UiStyle.Konbini -> ReceiptChapters(items, open)
        UiStyle.Stamp -> StampChapters(items, open)
        UiStyle.Tankobon, UiStyle.Modern, UiStyle.Genga, UiStyle.Bunko, UiStyle.Glass -> Unit
    }
}

@Composable
private fun chapterTap(item: NewChapter, open: (NewChapter) -> Unit): Pair<Any, () -> Unit> {
    val card = remember(item.id) { Any() }
    return card to {
        SharedCover.take(card, item.cover, acrossTabs = false)
        open(item)
    }
}

@Composable
private fun ChapterCover(item: NewChapter, card: Any, width: Dp, height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.width(width).height(height)) {
        Cover(item.cover, Modifier.fillMaxSize(), shared = SharedCover.card === card)
    }
}

@Composable
private fun GuideChapters(items: List<NewChapter>, open: (NewChapter) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.surface)) {
        items.forEachIndexed { index, item ->
            val (card, tap) = chapterTap(item, open)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(tap)
                    .drawBehind {
                        if (index < items.lastIndex) drawLine(theme.rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Column(Modifier.widthIn(min = 60.dp).padding(end = 8.dp)) {
                    BasicText("CH", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
                    BasicText(item.latest, maxLines = 1, style = NumberStyle(color = theme.ink, fontSize = 20.sp))
                }
                Box {
                    ChapterCover(item, card, 40.dp, 54.dp)
                    Box(Modifier.width(40.dp).height(54.dp).clip(coverShape()).scanlines())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    item.readUpTo?.let { BasicText("read up to $it", maxLines = 1, style = NumberStyle(color = theme.primary, fontSize = 13.sp)) }
                }
                Spacer(Modifier.width(8.dp))
                BasicText(
                    "NEW",
                    style = NumberStyle(color = Color.White, fontSize = 11.sp),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(theme.pencil).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun QuestChapters(items: List<NewChapter>, open: (NewChapter) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().rpgWindow(theme).padding(horizontal = 10.dp, vertical = 6.dp)) {
        items.forEach { item ->
            val (card, tap) = chapterTap(item, open)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().pressable(tap).padding(vertical = 6.dp),
            ) {
                ChapterCover(item, card, 40.dp, 54.dp, Modifier.border(2.dp, theme.foreground, coverShape()))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold))
                    Spacer(Modifier.height(3.dp))
                    BasicText(
                        listOfNotNull(item.readUpTo?.let { "CH $it" }, "CH ${item.latest}").joinToString(" ▶ "),
                        maxLines = 1,
                        style = NumberStyle(color = theme.foreground, fontSize = 12.sp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                BasicText("NEW!", style = NumberStyle(color = theme.accent, fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun BoardChapters(items: List<NewChapter>, open: (NewChapter) -> Unit) {
    val head = NumberStyle(color = Color(0xFF9AA0AD), fontSize = 10.sp, bold = true)
    val columnWidth = 70.dp
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(EkiBoard).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row {
            BasicText("CH", style = head, modifier = Modifier.widthIn(min = columnWidth))
            BasicText("TITLE", style = head, modifier = Modifier.weight(1f))
            BasicText("READ", style = head)
        }
        items.forEach { item ->
            val (_, tap) = chapterTap(item, open)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().pressable(tap)) {
                FlapText(item.latest, Modifier.widthIn(min = columnWidth).padding(end = 6.dp), key = "board-chapter-${item.id}")
                BasicText(
                    item.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = Color(0xFFF2F3F5), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f).padding(end = 6.dp),
                )
                BasicText(item.readUpTo ?: "—", maxLines = 1, style = NumberStyle(color = Color(0xFFC3C7D0), fontSize = 13.sp))
            }
        }
    }
}

@Composable
private fun ReceiptChapters(items: List<NewChapter>, open: (NewChapter) -> Unit) {
    val theme = LocalAppTheme.current
    val ink = theme.paperInk
    Column(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth().printOnce("receipt-chapters").clip(ReceiptShape).background(theme.paper).padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            val (card, tap) = chapterTap(item, open)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().pressable(tap)) {
                ChapterCover(item, card, 28.dp, 38.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = ink, fontSize = 13.sp, bold = true), modifier = Modifier.weight(1f, fill = false))
                        Box(Modifier.weight(0.2f).height(14.dp).padding(horizontal = 4.dp).leaders(ink.copy(alpha = 0.45f)))
                        BasicText("CH ${item.latest}", style = NumberStyle(color = ink, fontSize = 13.sp, bold = true))
                    }
                    item.readUpTo?.let { BasicText("read up to $it", style = NumberStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp)) }
                }
            }
        }
    }
}

@Composable
private fun StampChapters(items: List<NewChapter>, open: (NewChapter) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEachIndexed { index, item ->
            val (card, tap) = chapterTap(item, open)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressable(tap)
                    .clip(RoundedCornerShape(14.dp))
                    .background(theme.surface)
                    .drawBehind {
                        val x = 9.dp.toPx()
                        drawLine(
                            theme.hairline,
                            Offset(x, 10.dp.toPx()),
                            Offset(x, size.height - 10.dp.toPx()),
                            2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                    }
                    .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) {
                ChapterCover(item, card, 46.dp, 64.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.ink, fontSize = 15.sp, fontWeight = FontWeight.Black))
                    BasicText("Chapter ${item.latest} out", style = TextStyle(color = theme.pencil, fontSize = 12.sp, fontWeight = FontWeight.Black))
                    item.readUpTo?.let { BasicText("read up to $it", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp, fontWeight = FontWeight.Bold)) }
                }
                Spacer(Modifier.width(8.dp))
                Box(Modifier.stampDown("new-${item.id}-${item.latest}", index)) { StampMark("新", theme.pencil, 44.dp, tilt = STAMP_TILT[index % STAMP_TILT.size]) }
            }
        }
    }
}
