package com.aniliphy.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * More of each look's own touches: empty states and the end of lists, list counts, day headings, the search field's
 * label, the activity bars, the profile card, your score, a media page coming in, loading in the player and readers,
 * the intro when the app starts, and milestones. All of it follows the Look moments switch.
 */

/** The look to word and draw things in, or null when Look moments are off. */
internal val momentStyle: UiStyle? get() = Look.style.takeIf { LookPrefs.moments }

// ——— Empty and the end of a list ————————————————————————————————————————————————————————————————————————————————

/** An empty list or search, set the look's way (a balloon, a menu window, a receipt...). */
@Composable
internal fun Empty(text: String, modifier: Modifier = Modifier, color: Color = LocalAppTheme.current.foreground.copy(alpha = 0.7f)) {
    if (LookPrefs.moments) LookHint(text, modifier, color) else Hint(text, modifier, color)
}

/** "Nothing found" in the look's words. */
@Composable
internal fun NothingFound(modifier: Modifier = Modifier, color: Color = LocalAppTheme.current.foreground.copy(alpha = 0.7f)) {
    Empty(LookWords.nothingFound(momentStyle), modifier, color)
}

/** A line under the last item of a finished list: "END OF LINE", "THANK YOU FOR SHOPPING"... */
@Composable
internal fun EndOfList(modifier: Modifier = Modifier) {
    val text = LookWords.endOfList(momentStyle) ?: return
    val theme = LocalAppTheme.current
    val ink = theme.foreground.copy(alpha = 0.55f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        val line: Modifier = when (Look.style) {
            UiStyle.Konbini, UiStyle.Genga -> Modifier.drawBehind {
                drawLine(ink, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            }
            UiStyle.Eki -> Modifier.drawBehind { drawLine(theme.primary, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx()) }
            else -> Modifier.drawBehind { drawLine(ink.copy(alpha = 0.35f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx()) }
        }
        Box(Modifier.weight(1f).height(10.dp).then(line))
        if (Look.style == UiStyle.Eki) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(theme.background).border(3.dp, theme.primary, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        BasicText(text, maxLines = 1, style = NumberStyle(color = ink, fontSize = 11.sp, bold = true))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(10.dp).then(if (Look.style == UiStyle.Eki) Modifier else line))
    }
}

/** How many are on a list, the look's way ("Party: 12 members"), above the list. */
@Composable
internal fun ListCount(count: Int, anime: Boolean, modifier: Modifier = Modifier) {
    val text = LookWords.listCount(momentStyle, count, anime) ?: return
    val theme = LocalAppTheme.current
    val style = Look.style
    val box: Modifier = when (style) {
        UiStyle.Jrpg -> Modifier.rpgWindow(theme, 8.dp).padding(horizontal = 10.dp, vertical = 4.dp)
        UiStyle.Eki -> Modifier.clip(RoundedCornerShape(6.dp)).background(EkiBoard).padding(horizontal = 10.dp, vertical = 4.dp)
        UiStyle.Konbini -> Modifier.background(theme.paper).padding(horizontal = 10.dp, vertical = 4.dp)
        UiStyle.Tankobon -> Modifier.clip(SkewShape).background(theme.ink).padding(horizontal = 14.dp, vertical = 3.dp)
        else -> Modifier.padding(vertical = 2.dp)
    }
    val color = when (style) {
        UiStyle.Jrpg -> Color.White
        UiStyle.Eki -> EkiFlap
        UiStyle.Konbini -> theme.paperInk
        UiStyle.Tankobon -> theme.background
        UiStyle.Stamp -> theme.pencil
        else -> theme.foreground.copy(alpha = 0.7f)
    }
    BasicText(text, style = NumberStyle(color = color, fontSize = 12.sp, bold = true), modifier = modifier.padding(start = 16.dp, bottom = 6.dp).then(box))
}

/** A day's heading on the Anime and Manga tabs, in the look's way of writing dates. */
@Composable
internal fun DayTitle(day: java.time.LocalDate) {
    SectionTitle(LookWords.day(momentStyle, day, dayLabel(day)))
}

// ——— The search field ———————————————————————————————————————————————————————————————————————————————————————————

/** The little label in front of the search field: a channel, a destination, a scanner... */
@Composable
internal fun SearchLead(typing: Boolean) {
    val style = momentStyle ?: return
    val theme = LocalAppTheme.current
    when (style) {
        UiStyle.Jrpg -> BlinkingCursor(theme.accent, 12.sp, Modifier.padding(end = 6.dp))
        UiStyle.Konbini -> {
            // The scanner's red line runs while you type.
            val moving = typing && LookPrefs.uiMotion
            val at = if (moving) {
                val scan = rememberInfiniteTransition(label = "scan")
                scan.animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.ms(900), easing = LinearEasing)), label = "at").value
            } else {
                0.5f
            }
            Canvas(Modifier.padding(end = 8.dp).size(20.dp, 14.dp)) {
                var x = 0f
                var i = 0
                while (x < size.width) {
                    val w = if (i % 3 == 0) 2.dp.toPx() else 1.dp.toPx()
                    drawRect(theme.foreground.copy(alpha = 0.7f), Offset(x, 0f), Size(w, size.height))
                    x += w + 1.5.dp.toPx()
                    i++
                }
                val y = if (moving) at * size.height else size.height / 2
                drawLine(Color.Red, Offset(-2f, y), Offset(size.width + 2f, y), 1.5.dp.toPx())
            }
        }
        else -> {
            val label = when (style) {
                UiStyle.Tankobon -> "?!"
                UiStyle.Genga -> "SC."
                UiStyle.Bunko -> "索引"
                UiStyle.Shinya -> "CH"
                UiStyle.Eki -> "行先"
                UiStyle.Stamp -> "◎"
                else -> return
            }
            val (fg, bg) = when (style) {
                UiStyle.Tankobon -> theme.background to theme.ink
                UiStyle.Eki -> EkiFlap to EkiBoard
                UiStyle.Shinya -> Color.White to theme.error
                UiStyle.Stamp -> theme.pencil to Color.Transparent
                else -> theme.primary to Color.Transparent
            }
            BasicText(
                label,
                style = NumberStyle(color = fg, fontSize = 11.sp, bold = true),
                modifier = Modifier.padding(end = 8.dp).lookAlive(Alive.Lead).clip(RoundedCornerShape(3.dp)).background(bg).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

// ——— Activity ———————————————————————————————————————————————————————————————————————————————————————————————————

/** One day's bar in the Activity chart: pencil hatching, LED dots, stamps, a spine, a meter... */
@Composable
internal fun ActivityBar(share: Float, active: Boolean, modifier: Modifier) {
    val theme = LocalAppTheme.current
    val style = momentStyle
    val fill = 0.85f * share + 0.02f
    val muted = theme.foreground.copy(alpha = 0.15f)
    if (style == null || style == UiStyle.Modern || style == UiStyle.Glass) {
        Box(
            modifier.width(14.dp).fillMaxHeight(fill).clip(RoundedCornerShape(4.dp))
                .background(if (!active) muted else if (style == UiStyle.Glass) theme.primary.copy(alpha = 0.7f) else theme.primary),
        )
        return
    }
    Canvas(modifier.width(18.dp).fillMaxHeight()) {
        val h = size.height * fill
        val top = size.height - h
        val w = size.width
        when (style) {
            UiStyle.Tankobon -> {
                drawRect(if (active) theme.ink else muted, Offset(0f, top), Size(w, h))
                if (active) {
                    var y = top + 3.dp.toPx()
                    while (y < size.height) {
                        drawCircle(theme.background.copy(alpha = 0.4f), 1.dp.toPx(), Offset(w / 2, y))
                        y += 5.dp.toPx()
                    }
                }
            }
            UiStyle.Genga -> {
                drawRect(if (active) theme.primary else muted, Offset(0f, top), Size(w, h), style = Stroke(1.5.dp.toPx()))
                if (active) {
                    clipRect(0f, top, w, size.height) {
                        var x = -h
                        while (x < w) {
                            drawLine(theme.primary.copy(alpha = 0.5f), Offset(x, size.height), Offset(x + h, top), 1.dp.toPx())
                            x += 4.dp.toPx()
                        }
                    }
                }
            }
            UiStyle.Bunko -> {
                drawRect(if (active) theme.primary else muted, Offset(0f, top), Size(w, h))
                if (active && h > 16.dp.toPx()) drawRect(Color(0xFFF2E9D8), Offset(0f, size.height - 12.dp.toPx()), Size(w, 8.dp.toPx()))
            }
            UiStyle.Shinya -> {
                // A level meter: segments from green up to red.
                val seg = 5.dp.toPx()
                var y = size.height - seg
                var i = 0
                while (y >= 0) {
                    val lit = active && y >= top
                    val c = when {
                        y < size.height * 0.2f -> Color(0xFFFF4A5A)
                        y < size.height * 0.45f -> Color(0xFFFFC24A)
                        else -> Color(0xFF5BE38A)
                    }
                    drawRect(if (lit) c else muted, Offset(0f, y), Size(w, seg - 1.5.dp.toPx()))
                    y -= seg
                    i++
                }
            }
            UiStyle.Jrpg -> {
                drawRect(Color.White.copy(alpha = 0.15f), Offset(0f, 0f), Size(w, size.height))
                if (active) drawRect(if (share > 0.3f) EkiGo else theme.warning, Offset(2.dp.toPx(), top), Size(w - 4.dp.toPx(), h))
                drawRect(Color.White.copy(alpha = 0.8f), style = Stroke(1.5.dp.toPx()))
            }
            UiStyle.Eki -> {
                val dot = 4.dp.toPx()
                var y = size.height - dot
                while (y >= 0) {
                    var x = dot / 2
                    while (x < w) {
                        drawCircle(if (active && y >= top) EkiFlap else Color(0xFF2A2C33), dot * 0.4f, Offset(x, y))
                        x += dot
                    }
                    y -= dot
                }
            }
            UiStyle.Konbini -> {
                drawRect(theme.paper, Offset(0f, top), Size(w, h))
                var y = top + 3.dp.toPx()
                while (y < size.height) {
                    drawLine(theme.paperInk.copy(alpha = if (active) 0.6f else 0.2f), Offset(2.dp.toPx(), y), Offset(w - 2.dp.toPx(), y), 1.dp.toPx())
                    y += 4.dp.toPx()
                }
            }
            UiStyle.Stamp -> {
                // A column of stamps, one per share of the busiest day.
                val r = w / 2
                val count = if (active) (share * 5).toInt().coerceIn(1, 5) else 0
                for (i in 0 until 5) {
                    val c = Offset(w / 2, size.height - r - i * (r * 2 + 2.dp.toPx()))
                    if (c.y < 0) break
                    if (i < count) drawCircle(theme.pencil.copy(alpha = 0.85f), r, c) else drawCircle(muted, r - 1.dp.toPx(), c, style = Stroke(1.dp.toPx()))
                }
            }
            else -> drawRect(theme.primary, Offset(0f, top), Size(w, h))
        }
    }
}

// ——— Profile ————————————————————————————————————————————————————————————————————————————————————————————————————

/**
 * The top of the profile as the look's own card: a character sheet, a status window, a passport, a point card, a
 * commuter pass... [episodes] and [chapters] are AniList's own counts.
 */
@Composable
internal fun ProfileCard(
    name: String,
    avatar: String?,
    counts: String,
    episodes: Int,
    chapters: Int,
    banner: String? = null,
    bio: String? = null,
) {
    val theme = LocalAppTheme.current
    val style = momentStyle ?: return PlainProfile(name, avatar, counts, banner, bio)
    val level = 1 + floor(sqrt((episodes + chapters / 3).toDouble()) / 2).toInt()
    val into = (sqrt((episodes + chapters / 3).toDouble()) / 2) % 1.0
    data class Card(val header: String, val box: Modifier, val ink: Color, val accent: Color, val avatarShape: androidx.compose.ui.graphics.Shape)
    val card = when (style) {
        UiStyle.Tankobon -> Card("CHARACTER INTRO", Modifier.clip(CutCornerShape(bottomEnd = 16.dp)).background(theme.surface).border(2.5.dp, theme.ink, CutCornerShape(bottomEnd = 16.dp)), theme.ink, theme.primary, CutCornerShape(bottomEnd = 10.dp))
        UiStyle.Modern -> Card("PROFILE", Modifier.clip(RoundedCornerShape(24.dp)).background(theme.surface), theme.ink, theme.primary, CircleShape)
        UiStyle.Genga -> Card("CHARACTER SHEET · 設定", Modifier.background(theme.background).border(1.5.dp, theme.ink), theme.ink, theme.primary, androidx.compose.ui.graphics.RectangleShape)
        UiStyle.Bunko -> Card("著者 · ABOUT THE READER", Modifier.background(theme.paper).border(1.dp, theme.hairline), theme.paperInk, theme.primary, androidx.compose.ui.graphics.RectangleShape)
        UiStyle.Shinya -> Card("CAST · 出演", Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF07080C)), Color.White, Color(0xFF8EF0FF), RoundedCornerShape(10.dp))
        UiStyle.Jrpg -> Card("STATUS", Modifier.rpgWindow(theme), Color.White, theme.accent, RoundedCornerShape(8.dp))
        UiStyle.Eki -> Card("COMMUTER PASS · 定期券", Modifier.clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF2A7F62), Color(0xFF1A4F8A)))), Color.White, EkiFlap, RoundedCornerShape(8.dp))
        UiStyle.Konbini -> Card("ANILIPHY MART · POINT CARD", Modifier.clip(ReceiptShape).background(theme.paper), theme.paperInk, theme.primary, androidx.compose.ui.graphics.RectangleShape)
        UiStyle.Stamp -> Card("STAMP PASSPORT", Modifier.clip(RoundedCornerShape(18.dp)).background(theme.paper), theme.paperInk, theme.pencil, CircleShape)
        UiStyle.Glass -> Card("PROFILE", Modifier.glass(theme, RoundedCornerShape(24.dp)), theme.ink, theme.primary, CircleShape)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().then(card.box).lookAlive(Alive.Block)) {
    if (banner != null) ProfileBanner(banner, if (style == UiStyle.Tankobon || style == UiStyle.Bunko || style == UiStyle.Konbini) theme.paper else Color.Transparent)
    Column(Modifier.padding(14.dp)) {
        BasicText(card.header, style = NumberStyle(color = card.accent, fontSize = 10.sp, bold = true))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(
                    model = avatar,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp).clip(card.avatarShape).background(theme.panel)
                        .then(if (style == UiStyle.Stamp) Modifier.border(2.dp, theme.pencil, CircleShape) else Modifier),
                )
                if (style == UiStyle.Stamp) {
                    Box(Modifier.align(Alignment.BottomEnd).padding(0.dp)) { StampMark("済", theme.pencil, 30.dp, tilt = -12f) }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = card.ink, fontSize = 20.sp))
                val title = when (style) {
                    UiStyle.Jrpg -> "LV $level · ${if (episodes >= chapters) "Anime Knight" else "Manga Sage"}"
                    UiStyle.Eki -> "Valid between Anime ⇄ Manga"
                    UiStyle.Konbini -> "${episodes + chapters} points"
                    UiStyle.Stamp -> "${episodes + chapters} stamps collected"
                    UiStyle.Genga -> "Lead · ${if (episodes >= chapters) "animation" else "manga"}"
                    UiStyle.Shinya -> "Tonight's guest"
                    UiStyle.Bunko -> "Reader since the first printing"
                    else -> counts
                }
                BasicText(title, maxLines = 1, style = NumberStyle(color = card.accent, fontSize = 12.sp, bold = true))
                if (style != UiStyle.Modern && style != UiStyle.Glass && style != UiStyle.Tankobon) {
                    BasicText(counts, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = card.ink.copy(alpha = 0.7f), fontSize = 12.sp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val (a, b) = when (style) {
                UiStyle.Jrpg -> "HP" to "MP"
                UiStyle.Eki -> "Rides · anime" to "Rides · manga"
                UiStyle.Konbini -> "Anime pts" to "Manga pts"
                UiStyle.Genga -> "Cuts watched" to "Pages read"
                else -> "Episodes" to "Chapters"
            }
            ProfileStat(a, episodes, card.ink, card.accent, Modifier.weight(1f))
            ProfileStat(b, chapters, card.ink, card.accent, Modifier.weight(1f))
        }
        if (bio != null) ProfileBio(bio, card.ink, card.accent)
        if (style == UiStyle.Jrpg) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText("EXP", style = NumberStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.18f))) {
                    Box(Modifier.fillMaxWidth(fillOnShow(into.toFloat())).fillMaxHeight().background(theme.accent))
                }
                Spacer(Modifier.width(6.dp))
                BasicText("next LV ${level + 1}", style = NumberStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp))
            }
        }
        if (style == UiStyle.Konbini) {
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val rnd = Random(name.hashCode())
                var x = 0f
                while (x < size.width) {
                    val w = (1 + rnd.nextInt(3)) * 1.5.dp.toPx()
                    drawRect(theme.paperInk, Offset(x, 0f), Size(w, size.height))
                    x += w + (1 + rnd.nextInt(3)) * 1.5.dp.toPx()
                }
            }
        }
    }
}
}

@Composable
private fun ProfileStat(label: String, value: Int, ink: Color, accent: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).background(ink.copy(alpha = 0.07f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        BasicText(label, style = TextStyle(color = ink.copy(alpha = 0.65f), fontSize = 11.sp))
        BasicText("%,d".format(value), style = NumberStyle(color = accent, fontSize = 17.sp, bold = true))
    }
}

@Composable
private fun PlainProfile(name: String, avatar: String?, counts: String, banner: String?, bio: String?) {
    val theme = LocalAppTheme.current
    if (banner != null) Box(Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))) { ProfileBanner(banner, Color.Transparent) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        AsyncImage(model = avatar, contentDescription = null, modifier = Modifier.size(56.dp).clip(CircleShape).background(theme.panel))
        Spacer(Modifier.width(14.dp))
        Column {
            BasicText(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            BasicText(counts, style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 13.sp))
        }
    }
}

// ——— Media page ——————————————————————————————————————————————————————————————————————————————————————————————————

/** Your own score under a media page's title, as pips the look's way (out of 10, shown as five). */
@Composable
internal fun YourScore(score: Double?) {
    val value = score?.takeIf { it > 0 } ?: return
    val theme = LocalAppTheme.current
    val style = Look.style
    val out10 = value.coerceIn(0.0, 10.0)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        BasicText(LookWords.yourScore(momentStyle) + " ", style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 12.sp))
        BasicText(ScoreText.of(out10) + "/10", style = NumberStyle(color = theme.ink, fontSize = 12.sp, bold = true))
        Spacer(Modifier.width(8.dp))
        val color = if (style == UiStyle.Stamp) theme.pencil else theme.primary
        for (i in 0 until 5) {
            val lit = out10 / 2 >= i + 0.75
            Canvas(Modifier.padding(end = 3.dp).size(11.dp)) {
                when (style) {
                    UiStyle.Stamp -> if (lit) drawCircle(color) else drawCircle(color.copy(alpha = 0.4f), style = Stroke(1.dp.toPx()))
                    UiStyle.Jrpg, UiStyle.Eki, UiStyle.Shinya -> drawRect(if (lit) color else color.copy(alpha = 0.2f))
                    else -> {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            for (k in 0 until 10) {
                                val a = Math.PI / 5 * k - Math.PI / 2
                                val r = if (k % 2 == 0) size.minDimension / 2 else size.minDimension / 4.6f
                                val p = Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat())
                                if (k == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                            }
                            close()
                        }
                        if (lit) drawPath(path, color) else drawPath(path, color.copy(alpha = 0.4f), style = Stroke(1.dp.toPx()))
                    }
                }
            }
        }
    }
}

private object ScoreText {
    fun of(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
}

/**
 * A media page's header comes in the look's way the first time the page opens: [played] remembers it, so coming back
 * to the page doesn't play it again. [order] staggers the parts.
 */
internal fun Modifier.pageEntrance(played: androidx.compose.runtime.MutableState<Boolean>, order: Int): Modifier = composed {
    val style = momentStyle
    if (style == null || played.value) return@composed this
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(Motion.ms(90 * order).toLong())
        t.animateTo(1f, tween(Motion.ms(420), easing = lookEasing(FastOutSlowInEasing, Motion.ms(420))))
        if (order >= 1) played.value = true
    }
    val v = t.value
    when (style) {
        UiStyle.Tankobon -> graphicsLayer { translationX = (1f - v) * 60.dp.toPx(); rotationZ = (1f - v) * -3f; alpha = v }
        UiStyle.Modern -> graphicsLayer { translationY = (1f - v) * 24.dp.toPx(); alpha = v }
        UiStyle.Genga -> graphicsLayer { translationX = (1f - v) * -40.dp.toPx(); alpha = v }
        UiStyle.Bunko -> graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0.5f)
            rotationY = (1f - v) * 80f
            cameraDistance = 12f * density
            alpha = if (v > 0.05f) 1f else v * 20
        }
        UiStyle.Shinya -> drawWithContent {
            val h = size.height * v
            clipRect(top = (size.height - h) / 2, bottom = (size.height + h) / 2) { this@drawWithContent.drawContent() }
        }
        UiStyle.Jrpg -> graphicsLayer { scaleY = 0.1f + 0.9f * v; transformOrigin = TransformOrigin(0.5f, 0f); alpha = v }
        UiStyle.Eki -> graphicsLayer { rotationX = (1f - v) * -90f; transformOrigin = TransformOrigin(0.5f, 0f); cameraDistance = 12f * density }
        UiStyle.Konbini -> drawWithContent {
            val line = 14.dp.toPx()
            val shown = floor(v * size.height / line) * line
            clipRect(bottom = if (v >= 1f) size.height else shown) { this@drawWithContent.drawContent() }
        }
        UiStyle.Stamp -> graphicsLayer { val s = 1.4f - 0.4f * v; scaleX = s; scaleY = s; alpha = v }
        UiStyle.Glass -> graphicsLayer {
            alpha = v
            val r = (1f - v) * 18.dp.toPx()
            renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
        }
    }
}

// ——— Loading in the player and readers ——————————————————————————————————————————————————————————————————————————

/**
 * Opening or loading in the player and the readers: TV static (Shin'ya), "Now loading" (JRPG), a film leader counting
 * down (Genga), a departure board (Eki), the printer (Konbini)... [color] is the text color on that screen.
 */
@Composable
internal fun LookLoading(text: String, modifier: Modifier = Modifier, color: Color) {
    val style = momentStyle
    if (style == null || style == UiStyle.Modern || style == UiStyle.Glass) {
        Hint(text, modifier, color)
        return
    }
    val theme = LocalAppTheme.current
    val loop = rememberInfiniteTransition(label = "loading")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.ms(1200), easing = LinearEasing)), label = "t")
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when (style) {
            UiStyle.Shinya -> Canvas(Modifier.size(140.dp, 90.dp).clip(RoundedCornerShape(10.dp))) {
                val rnd = Random((t * 30).toInt())
                val cell = 4.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    var x = 0f
                    while (x < size.width) {
                        val g = rnd.nextFloat()
                        drawRect(Color(g, g, g), Offset(x, y), Size(cell, cell))
                        x += cell
                    }
                    y += cell
                }
            }
            UiStyle.Genga, UiStyle.Bunko -> Canvas(Modifier.size(90.dp)) {
                // A film leader: the sweep goes round once a second and the number counts down.
                drawCircle(color.copy(alpha = 0.25f), style = Stroke(2.dp.toPx()))
                drawLine(color.copy(alpha = 0.25f), Offset(center.x, 0f), Offset(center.x, size.height), 1.dp.toPx())
                drawLine(color.copy(alpha = 0.25f), Offset(0f, center.y), Offset(size.width, center.y), 1.dp.toPx())
                drawArc(color.copy(alpha = 0.35f), -90f, 360f * t, true)
            }
            UiStyle.Tankobon -> Canvas(Modifier.size(70.dp)) {
                for (i in 0 until 12) {
                    val a = i * Math.PI / 6 + t * Math.PI * 2
                    val o = Offset(center.x + (size.minDimension / 2 * kotlin.math.cos(a)).toFloat(), center.y + (size.minDimension / 2 * kotlin.math.sin(a)).toFloat())
                    drawLine(color.copy(alpha = 0.3f + 0.7f * (i / 12f)), center + (o - center) * 0.45f, o, 2.dp.toPx())
                }
            }
            UiStyle.Stamp -> Canvas(Modifier.size(70.dp)) {
                val s = 0.8f + 0.2f * kotlin.math.abs(kotlin.math.sin(t * Math.PI * 2)).toFloat()
                drawCircle(theme.pencil, size.minDimension / 2 * s, style = Stroke(3.dp.toPx()))
                drawCircle(theme.pencil.copy(alpha = 0.6f), size.minDimension / 2 * s - 8.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
            UiStyle.Eki -> FlapText(listOf("PLEASE WAIT", "  LOADING  ")[(t * 2).toInt() % 2], tile = 14.dp, key = "eki-loading")
            UiStyle.Jrpg -> Unit
            UiStyle.Konbini -> Canvas(Modifier.size(80.dp, 70.dp)) {
                val h = size.height * t
                drawRect(theme.paper, Offset(size.width * 0.15f, 0f), Size(size.width * 0.7f, h))
                drawRect(color.copy(alpha = 0.6f), Offset(0f, 0f), Size(size.width, 3.dp.toPx()))
            }
            else -> Unit
        }
        Spacer(Modifier.height(12.dp))
        val label = when (style) {
            UiStyle.Jrpg -> "Now loading" + ".".repeat((t * 4).toInt() % 4)
            UiStyle.Konbini -> "PRINTING · $text"
            else -> text
        }
        BasicText(
            label,
            style = NumberStyle(color = color, fontSize = 14.sp),
            modifier = if (style == UiStyle.Jrpg) Modifier.rpgWindow(theme).padding(horizontal = 16.dp, vertical = 10.dp) else Modifier,
        )
        if (style == UiStyle.Jrpg) BasicText(text, style = NumberStyle(color = color.copy(alpha = 0.7f), fontSize = 12.sp), modifier = Modifier.padding(top = 6.dp))
    }
}

// ——— The intro when the app starts ——————————————————————————————————————————————————————————————————————————————

/** Only the first start of the process plays the intro. */
private var introPlayed = false

/**
 * A short intro in the look's way when the app is started fresh: the look's mark over its background, then it lifts.
 * Skipped when the app was opened to do something (from the widget, a notification or a shortcut).
 */
@Composable
internal fun LookIntro(asked: Boolean) {
    var showing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(!introPlayed && !asked && LookPrefs.moments) }
    if (!showing) {
        introPlayed = true
        return
    }
    val theme = LocalAppTheme.current
    val style = Look.style
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        introPlayed = true
        t.animateTo(1f, tween(Motion.ms(1150), easing = LinearEasing))
        showing = false
    }
    val v = t.value
    val inT = (v / 0.35f).coerceIn(0f, 1f)
    val outT = ((v - 0.72f) / 0.28f).coerceIn(0f, 1f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = 1f - outT }
            .background(theme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        val pop = FastOutSlowInEasing.transform(inT)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.graphicsLayer {
            val s = when (style) {
                UiStyle.Stamp -> 1.6f - 0.6f * pop
                UiStyle.Tankobon -> 1.3f - 0.3f * pop
                else -> 0.9f + 0.1f * pop
            }
            scaleX = s
            scaleY = s
            alpha = pop
        }) {
            when (style) {
                UiStyle.Stamp -> StampMark("ア", theme.pencil, 110.dp, tilt = -8f, small = "aniliphy")
                UiStyle.Eki -> FlapText("ANILIPHY", tile = 26.dp, key = "intro")
                UiStyle.Jrpg -> Box(Modifier.rpgWindow(theme).padding(horizontal = 26.dp, vertical = 16.dp)) {
                    BasicText("▶ NEW GAME  ▶ CONTINUE", style = NumberStyle(color = Color.White, fontSize = 16.sp))
                }
                UiStyle.Konbini -> Column(Modifier.background(theme.paper).padding(horizontal = 26.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("ANILIPHY MART", style = NumberStyle(color = theme.paperInk, fontSize = 20.sp, bold = true))
                    BasicText("いらっしゃいませ", style = NumberStyle(color = theme.paperInk.copy(alpha = 0.7f), fontSize = 12.sp))
                }
                UiStyle.Shinya -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(180.dp, 100.dp)) {
                        val bars = listOf(0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0)
                        val w = size.width / bars.size
                        bars.forEachIndexed { i, c -> drawRect(Color(c), Offset(i * w, 0f), Size(w + 1f, size.height)) }
                    }
                    BasicText("ANILIPHY 24:00", style = NumberStyle(color = theme.ink, fontSize = 14.sp), modifier = Modifier.padding(top = 8.dp))
                }
                UiStyle.Bunko -> Column(Modifier.border(1.dp, theme.hairline).padding(horizontal = 30.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("aniliphy", style = TitleStyle(color = theme.ink, fontSize = 30.sp))
                    BasicText("文庫", style = TextStyle(color = theme.primary, fontSize = 14.sp))
                }
                UiStyle.Genga -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText("CUT 001", style = NumberStyle(color = theme.primary, fontSize = 13.sp, bold = true))
                    BasicText("aniliphy", style = TitleStyle(color = theme.ink, fontSize = 34.sp))
                }
                UiStyle.Tankobon -> Column(
                    Modifier.clip(CutCornerShape(bottomEnd = 18.dp)).background(theme.ink).padding(horizontal = 26.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BasicText("aniliphy", style = TitleStyle(color = theme.background, fontSize = 34.sp))
                    BasicText("第1巻", style = TextStyle(color = theme.background.copy(alpha = 0.8f), fontSize = 13.sp))
                }
                UiStyle.Modern, UiStyle.Glass -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.clip(CircleShape).background(theme.primary).padding(horizontal = 30.dp, vertical = 14.dp),
                ) {
                    BasicText("aniliphy", style = TitleStyle(color = theme.background, fontSize = 28.sp))
                }
            }
        }
    }
}

// ——— Milestones —————————————————————————————————————————————————————————————————————————————————————————————————

/**
 * Celebrates passing a milestone (episodes and chapters on AniList, days in a row here). Ones already passed when this
 * first runs are marked quietly, so only new ones are celebrated, once each.
 */
internal object Milestones {
    private val EPISODES = listOf(100, 250, 500, 1000, 2500, 5000, 10000)
    private val CHAPTERS = listOf(100, 500, 1000, 2500, 5000, 10000, 25000)
    private val STREAK = listOf(7, 30, 100, 365)

    /** The milestones [episodes], [chapters] and [streak] have reached, as keys and the words to show. */
    fun reached(episodes: Int, chapters: Int, streak: Int): List<Pair<String, String>> = buildList {
        EPISODES.filter { episodes >= it }.forEach { add("ep$it" to "%,d episodes watched".format(it)) }
        CHAPTERS.filter { chapters >= it }.forEach { add("ch$it" to "%,d chapters read".format(it)) }
        STREAK.filter { streak >= it }.forEach { add("streak$it" to "$it days in a row") }
    }

    /** Fires a moment for the newest milestone not shown before, and remembers all of them as shown. */
    fun check(context: Context, episodes: Int, chapters: Int, streak: Int) {
        val prefs = context.getSharedPreferences("milestones", Context.MODE_PRIVATE)
        val first = !prefs.getBoolean("started", false)
        val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
        val now = reached(episodes, chapters, streak)
        val fresh = now.filter { it.first !in seen }
        prefs.edit().putBoolean("started", true).putStringSet("seen", seen + now.map { it.first }).apply()
        if (first || fresh.isEmpty()) return
        LookMoment.milestone(LookWords.milestone(momentStyle, fresh.last().second))
    }
}

/** Your AniList banner across the top of the profile card, fading into it. */
@Composable
private fun ProfileBanner(url: String, fadeTo: Color) {
    val theme = LocalAppTheme.current
    val end = if (fadeTo == Color.Transparent) theme.surface.copy(alpha = 0f) else fadeTo
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.matchParentSize().graphicsLayer { alpha = 0.85f },
        )
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, end))))
    }
}

/** The bio from AniList: two lines, the rest a tap away. */
@Composable
private fun ProfileBio(bio: String, ink: Color, accent: Color) {
    var open by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    BasicText(
        bio,
        maxLines = if (open) 12 else 2,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = ink.copy(alpha = 0.8f), fontSize = 12.5.sp, lineHeight = 17.sp),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    )
}
