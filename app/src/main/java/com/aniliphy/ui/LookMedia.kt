package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind

/** What the media page's block under the title works from. */
private class Facts(media: Media, val progress: ListProgress?) {
    val anime = media.kind == MediaKind.Anime
    val total: Int? = (if (anime) media.episodes else media.chapters)?.takeIf { it > 0 }
    val done: Int? = progress?.progress
    val unit = if (anime) "episodes" else "chapters"
    val short = if (anime) "EP" else "CH"
    val score: Int? = media.averageScore?.takeIf { it > 0 }
    val format: String? = media.format?.let { Formatting.format(it) }
    val studio: String? = Formatting.splitStudios(media).first.firstOrNull()?.name
    val status: String? = progress?.status?.let { ListStatus.label(it, media.kind) }
    val nextEpisode: Int? = media.nextAiringEpisode?.takeIf { anime && it.airingAt != null }?.episode
    val nextIn: String? = media.nextAiringEpisode?.airingAt?.takeIf { anime }?.let { at ->
        val left = at - Formatting.nowSeconds()
        if (left <= 0) "out now" else Formatting.countdown(left)
    }
    val percent: Int? = if (done != null && total != null) (done * 100 / total).coerceIn(0, 100) else null
    val id = media.id
    val duration: Int? = media.duration
}

/** The block under the media page's title in the looks other than Tankōbon and Modern. */
@Composable
internal fun StyledStats(media: Media, progress: ListProgress?) {
    val facts = Facts(media, progress)
    Spacer(Modifier.height(14.dp))
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).lookAlive(Alive.Stats)
    when (Look.style) {
        UiStyle.Genga -> SheetHeader(facts, modifier)
        UiStyle.Bunko -> Colophon(facts, modifier)
        UiStyle.Shinya -> BroadcastMeters(facts, modifier)
        UiStyle.Jrpg -> StatusScreen(facts, modifier)
        UiStyle.Eki -> StationSign(facts, modifier)
        UiStyle.Konbini -> Receipt(facts, modifier)
        UiStyle.Stamp -> StampSheet(facts, modifier)
        UiStyle.Glass, UiStyle.Modern, UiStyle.Tankobon -> Unit
    }
}

/** Genga: the timing sheet's header boxes, a red-pencil note of the next episode, and one cell per episode. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetHeader(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().border(1.dp, theme.hairline).background(theme.background)) {
            @Composable
            fun Cell(label: String, value: String, weight: Float, color: Color = theme.ink) {
                Column(
                    Modifier.weight(weight).drawBehind {
                        drawLine(theme.hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                    }.padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    BasicText(label, style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 9.sp, bold = true))
                    BasicText(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = color, fontSize = 17.sp, bold = true))
                }
            }
            Cell("SCORE", facts.score?.toString() ?: "--", 1f, theme.primary)
            Cell(facts.short + "S", "${facts.done ?: "-"}/${facts.total ?: "?"}", 1.2f)
            Cell("STATUS", facts.status?.uppercase() ?: "—", 1.6f)
        }
        if (facts.nextEpisode != null && facts.nextIn != null) {
            BasicText(
                "ep ${facts.nextEpisode} in ${facts.nextIn}",
                style = androidx.compose.ui.text.TextStyle(color = theme.pencil, fontSize = 16.sp, fontFamily = Kalam, fontWeight = FontWeight.Bold),
                modifier = Modifier.rotate(-3f).border(2.dp, theme.pencil, CircleShape).padding(horizontal = 12.dp, vertical = 1.dp),
            )
        }
        val total = facts.total
        if (facts.done != null && total != null) {
            Row(Modifier.fillMaxWidth()) {
                BasicText("TIME SHEET", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 10.sp, bold = true), modifier = Modifier.weight(1f))
                BasicText("${facts.done} / $total · ${facts.percent}%", style = NumberStyle(color = theme.ink, fontSize = 11.sp, bold = true))
            }
            if (total <= SHEET_CELLS) {
                val perRow = 13
                Column(Modifier.border(1.dp, theme.hairline).semantics { contentDescription = "${facts.done} of $total ${facts.unit}" }) {
                    (0 until total).chunked(perRow).forEach { row ->
                        Row(Modifier.fillMaxWidth().height(26.dp)) {
                            row.forEach { i ->
                                val watched = i < facts.done
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .border(0.5.dp, theme.hairline)
                                        .background(if (watched) theme.primary else Color.Transparent)
                                        .then(if (i == facts.done) Modifier.padding(3.dp).border(2.dp, theme.pencil) else Modifier),
                                ) {
                                    BasicText(
                                        "%02d".format((i + 1) % 100),
                                        style = NumberStyle(color = if (watched) theme.background else theme.foreground.copy(alpha = 0.7f), fontSize = 9.sp, bold = true),
                                    )
                                }
                            }
                            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                ProgressSteps(facts.done, total)
            }
        }
    }
}

private const val SHEET_CELLS = 52

/** Bunko: the next episode on an obi band, then a colophon of facts between double rules. */
@Composable
private fun Colophon(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (facts.status != null) {
            BasicText(
                listOfNotNull(facts.status, facts.done?.let { "$it of ${facts.total ?: "?"}" }).joinToString(" · "),
                style = TextStyle(color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold),
            )
        }
        if (facts.nextEpisode != null && facts.nextIn != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().background(theme.primary).padding(vertical = 8.dp),
            ) {
                BasicText("EPISODE ${facts.nextEpisode} IN  ", style = TextStyle(color = theme.background, fontSize = 11.sp, fontWeight = FontWeight.Bold))
                BasicText(facts.nextIn, style = TitleStyle(color = theme.background, fontSize = 19.sp))
            }
        }
        DoubleRule(theme.hairline, Modifier.fillMaxWidth())
        val rows = listOfNotNull(
            facts.studio?.let { "Studio" to it },
            facts.score?.let { "Score" to "$it%" },
            facts.format?.let { "Format" to (it + (facts.duration?.takeIf { facts.anime }?.let { d -> " · $d min" } ?: "")) },
            facts.total?.let { (if (facts.anime) "Episodes" else "Chapters") to it.toString() },
        )
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                pair.forEach { (label, value) ->
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        BasicText(label, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
                        Spacer(Modifier.weight(1f))
                        BasicText(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 14.sp))
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        DoubleRule(theme.hairline, Modifier.fillMaxWidth())
    }
}

/** Shin'ya: an on-air bug for the next episode, the score as a level meter, progress as a tape counter. */
@Composable
private fun BroadcastMeters(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (facts.nextEpisode != null && facts.nextIn != null) {
            BasicText(
                "NEXT ${facts.short} ${facts.nextEpisode} · ${facts.nextIn}",
                style = NumberStyle(color = Color.White, fontSize = 13.sp),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(theme.error).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tile = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(theme.surface).padding(horizontal = 12.dp, vertical = 10.dp)
            Column(tile, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("RATING", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
                BasicText(facts.score?.let { "$it%" } ?: "--", style = NumberStyle(color = theme.ink, fontSize = 24.sp))
                val lit = ((facts.score ?: 0) + 5) / 10
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.semantics { contentDescription = "Score ${facts.score ?: 0} percent" }) {
                    repeat(10) { i ->
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(1.dp)).background(if (i < lit) (if (i < 6) theme.deepPrimary else theme.error) else theme.rule))
                    }
                }
            }
            Column(tile, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(theme.error))
                    Spacer(Modifier.width(6.dp))
                    BasicText(if (facts.anime) "WATCHED" else "READ", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
                }
                BasicText("%02d / %s".format(facts.done ?: 0, facts.total?.toString() ?: "?"), style = NumberStyle(color = theme.ink, fontSize = 24.sp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(theme.rule)) {
                    Box(Modifier.fillMaxWidth(fillOnShow((facts.percent ?: 0) / 100f)).fillMaxHeight().background(theme.deepPrimary))
                }
            }
        }
        facts.status?.let { BasicText(it, style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
    }
}

/** JRPG: a status screen. LV is how many you've seen, HP the share of the whole, ATK the score. */
@Composable
private fun StatusScreen(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().rpgWindow(theme).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            @Composable
            fun Stat(label: String, value: String) {
                Row(Modifier.fillMaxWidth()) {
                    BasicText(label, style = NumberStyle(color = theme.accent, fontSize = 14.sp), modifier = Modifier.width(64.dp))
                    BasicText(value, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = theme.ink, fontSize = 14.sp), modifier = Modifier.weight(1f))
                }
            }
            Stat("LV", (facts.done ?: 0).toString())
            if (facts.total != null && facts.done != null) ProgressSteps(facts.done, facts.total)
            Stat("ATK", facts.score?.toString() ?: "--")
            facts.format?.let { Stat("CLASS", it) }
            facts.studio?.let { Stat("GUILD", it) }
            facts.status?.let { Stat("STATE", it) }
        }
        if (facts.nextEpisode != null && facts.nextIn != null) {
            Row(Modifier.fillMaxWidth().rpgWindow(theme).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Next battle", style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
                BasicText("${facts.short} ${facts.nextEpisode} · ${facts.nextIn}", style = NumberStyle(color = theme.ink, fontSize = 14.sp))
            }
        }
    }
}

/** Eki: a station sign for where you are (the last one seen) with the stops either side, then your line. */
@Composable
private fun StationSign(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val done = facts.done
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (done != null) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(EkiPlate)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
                    LineBadge(facts.short, "%02d".format(done % 100), theme.primary, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        BasicText(if (done == 0) "Not started" else "You are here", style = TextStyle(color = EkiPlateInk.copy(alpha = 0.7f), fontSize = 12.sp))
                        BasicText(facts.status ?: "", style = TitleStyle(color = EkiPlateInk, fontSize = 20.sp))
                    }
                }
                Row(Modifier.fillMaxWidth().background(theme.primary).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicText(if (done > 0) "◀ ${facts.short.lowercase()} ${done}" else "", style = TitleStyle(color = Color.White, fontSize = 14.sp), modifier = Modifier.weight(1f))
                    val total = facts.total
                    if (total == null || done < total) BasicText("${facts.short.lowercase()} ${done + 1} ▶", style = TitleStyle(color = Color.White, fontSize = 14.sp))
                }
            }
        }
        if (facts.nextEpisode != null && facts.nextIn != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(EkiBoard).padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicText("NEXT", style = NumberStyle(color = Color(0xFF9AA0AD), fontSize = 11.sp, bold = true))
                Spacer(Modifier.width(10.dp))
                FlapText("${facts.short} %02d".format(facts.nextEpisode % 100), tile = 16.dp, key = "plate-next-${facts.id}")
                Spacer(Modifier.weight(1f))
                BasicText("in ${facts.nextIn}", style = TitleStyle(color = EkiGo, fontSize = 15.sp))
            }
        }
        if (done != null && facts.total != null) {
            Row(Modifier.fillMaxWidth()) {
                BasicText("YOUR LINE", style = TitleStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp), modifier = Modifier.weight(1f))
                BasicText("$done / ${facts.total}", style = TitleStyle(color = theme.ink, fontSize = 12.sp))
            }
            ProgressSteps(done, facts.total)
        }
    }
}

/** Konbini: the facts printed on a receipt, the share seen as its TOTAL, and a barcode of the AniList ID. */
@Composable
private fun Receipt(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val ink = theme.paperInk
    Column(modifier.printOnce("receipt-${facts.id}").clip(ReceiptShape).background(theme.paper).padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        @Composable
        fun Line(label: String, value: String, big: Boolean = false) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                BasicText(label, style = NumberStyle(color = ink, fontSize = if (big) 18.sp else 13.sp, bold = big))
                Box(Modifier.weight(1f).height(14.dp).padding(horizontal = 6.dp).leaders(ink.copy(alpha = 0.4f)))
                BasicText(value, maxLines = 1, style = NumberStyle(color = ink, fontSize = if (big) 18.sp else 13.sp, bold = big))
            }
        }
        facts.format?.let { Line("FORMAT", it.uppercase()) }
        facts.studio?.let { Line("STUDIO", it.uppercase().take(18)) }
        facts.score?.let { Line("SCORE", "$it%") }
        facts.total?.let { Line(facts.unit.uppercase(), it.toString()) }
        facts.done?.let { Line(if (facts.anime) "WATCHED" else "READ", it.toString()) }
        if (facts.nextEpisode != null && facts.nextIn != null) Line("NEXT ${facts.short} ${facts.nextEpisode}", facts.nextIn)
        DashedRule(ink.copy(alpha = 0.3f), Modifier.fillMaxWidth().padding(vertical = 4.dp))
        facts.percent?.let { Line("TOTAL", "$it%", big = true) }
        facts.status?.let { BasicText("STATUS: ${it.uppercase()}", style = NumberStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp)) }
        Spacer(Modifier.height(4.dp))
        Barcode(facts.id, ink, Modifier.fillMaxWidth().height(40.dp))
        BasicText(
            "ANILIST ${facts.id}",
            style = NumberStyle(color = ink.copy(alpha = 0.7f), fontSize = 11.sp, bold = false),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** Bars made from the digits of [id]: decoration, not a code a scanner would read. */
@Composable
private fun Barcode(id: Int, ink: Color, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Barcode" }) {
        val widths = id.toString().flatMap { d -> val n = d - '0'; listOf(1 + n % 3, 1 + (n + 1) % 2, 2 + n % 2, 1) }
        val unit = size.width / (widths.sum() + 20).toFloat()
        var x = unit * 10
        widths.forEachIndexed { i, w ->
            if (i % 2 == 0) drawRect(ink, Offset(x, 0f), androidx.compose.ui.geometry.Size(w * unit, size.height))
            x += w * unit
        }
    }
}

/** Stamp rally: a sheet with a stamp for every episode seen, the next one a dashed ring with its countdown. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StampSheet(facts: Facts, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val total = facts.total
    val done = facts.done ?: 0
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(theme.surface)
            .drawBehind {
                val x = 12.dp.toPx()
                drawLine(theme.hairline, Offset(x, 14.dp.toPx()), Offset(x, size.height - 14.dp.toPx()), 2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
            .padding(start = 26.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            BasicText("Stamp sheet", style = TitleStyle(color = theme.ink, fontSize = 15.sp), modifier = Modifier.weight(1f))
            BasicText("$done / ${total ?: "?"}", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
        if (total != null && total <= STAMP_SHEET) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 5,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$done of $total ${facts.unit} stamped" },
            ) {
                repeat(total) { i ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        when {
                            i < done -> Box(Modifier.stampDown("sheet-${facts.id}-$i", i)) { StampMark("${i + 1}", theme.pencil, 52.dp, STAMP_TILT[i % STAMP_TILT.size], small = facts.short) }
                            else -> Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(52.dp).drawBehind {
                                    val r = size.minDimension / 2f - 2.dp.toPx()
                                    if (i == done) {
                                        drawCircle(theme.pencil, r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                                    } else {
                                        drawCircle(theme.rule, r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                    }
                                },
                            ) {
                                BasicText("${i + 1}", style = TextStyle(color = if (i == done) theme.pencil else theme.foreground.copy(alpha = 0.55f), fontSize = 14.sp, fontWeight = FontWeight.Black))
                            }
                        }
                    }
                }
                repeat((5 - total % 5) % 5) { Spacer(Modifier.weight(1f)) }
            }
        } else if (total != null) {
            ProgressSteps(done, total)
        }
        if (facts.nextEpisode != null && facts.nextIn != null) {
            BasicText("Next stamp: ${facts.short.lowercase()} ${facts.nextEpisode} in ${facts.nextIn}", style = TextStyle(color = theme.pencil, fontSize = 13.sp, fontWeight = FontWeight.Black))
        }
        facts.status?.let { BasicText(it, style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)) }
    }
}

private const val STAMP_SHEET = 30
