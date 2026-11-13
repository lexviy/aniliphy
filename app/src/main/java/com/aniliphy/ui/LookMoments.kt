package com.aniliphy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/*
 * The small moments, each look's own way: pulling to refresh, an empty list, and an episode or chapter marked as
 * seen. All of it can be turned off in Appearance (Look moments), which brings back the plain versions.
 */

// ——— Pull to refresh ——————————————————————————————————————————————————————————————————————————————————————————————

/** The pull-to-refresh indicator, drawn the look's way. It comes down with the pull and keeps moving while loading. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookRefreshIndicator(state: PullToRefreshState, isRefreshing: Boolean, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    if (!LookPrefs.moments) {
        androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            containerColor = theme.surface,
            color = theme.primary,
            modifier = modifier,
        )
        return
    }
    val pulled = if (isRefreshing) 1f else state.distanceFraction
    if (pulled <= 0f && !isRefreshing) return
    val loop = rememberInfiniteTransition(label = "refresh")
    val turn by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(Motion.ms(1100), easing = LinearEasing)), label = "turn")
    val spin = if (isRefreshing) turn else 0f
    val p = pulled.coerceIn(0f, 1f)
    val style = Look.style
    val wide = style == UiStyle.Jrpg || style == UiStyle.Eki
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .graphicsLayer {
                translationY = min(pulled, 1.25f) * 76.dp.toPx() - 48.dp.toPx()
                alpha = min(1f, pulled * 1.6f)
            }
            .size(if (wide) 132.dp else 52.dp, if (wide) 38.dp else 52.dp)
            .semantics { contentDescription = if (isRefreshing) "Refreshing" else "Pull to refresh" },
    ) {
        when (style) {
            UiStyle.Jrpg -> Box(Modifier.fillMaxSize().rpgWindow(theme, 8.dp), contentAlignment = Alignment.CenterStart) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
                    BlinkingCursor(theme.accent, 12.sp, visible = isRefreshing)
                    Spacer(Modifier.width(4.dp))
                    if (isRefreshing) {
                        BasicText("Loading" + ".".repeat((spin * 4).toInt() % 4), style = NumberStyle(color = Color.White, fontSize = 13.sp))
                    } else {
                        Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(theme.accent))
                        }
                    }
                }
            }
            UiStyle.Eki -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(EkiBoard),
            ) {
                val text = if (isRefreshing) {
                    listOf("UPDATING", "  ....  ", "UPDATING", "PLS WAIT")[(spin * 4).toInt() % 4]
                } else {
                    "  %3d%%  ".format((p * 100).toInt())
                }
                FlapText(text, tile = 12.dp, key = if (isRefreshing) "eki-refresh" else null)
            }
            else -> Canvas(Modifier.fillMaxSize()) { refreshMark(style, p, spin, isRefreshing, theme) }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.refreshMark(style: UiStyle, p: Float, spin: Float, loading: Boolean, theme: AppTheme) {
    val c = center
    val r = size.minDimension / 2
    when (style) {
        UiStyle.Tankobon -> {
            // Speed lines bursting out of the middle, longer the further you pull.
            drawCircle(theme.surface, r)
            drawCircle(theme.ink, r, style = Stroke(2.dp.toPx()))
            val lines = 14
            rotate(spin * 360f / lines * 3) {
                for (i in 0 until lines) {
                    val a = i * 2 * PI / lines
                    val inner = r * (0.62f - 0.42f * p)
                    val outer = r * 0.86f
                    drawLine(
                        theme.ink,
                        Offset(c.x + cos(a).toFloat() * inner, c.y + sin(a).toFloat() * inner),
                        Offset(c.x + cos(a).toFloat() * outer, c.y + sin(a).toFloat() * outer),
                        (if (i % 2 == 0) 2.5f else 1.4f).dp.toPx(),
                    )
                }
            }
        }
        UiStyle.Modern -> {
            // The bar's pill, stretching like elastic, then bouncing while it loads.
            val bounce = if (loading) sin(spin * 2 * PI).toFloat() * 0.25f else 0f
            val h = (14.dp.toPx() + 22.dp.toPx() * p) * (1f + bounce)
            val w = 26.dp.toPx() * (1f - 0.25f * p)
            drawRoundRect(
                theme.primary,
                Offset(c.x - w / 2, c.y - h / 2),
                Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(w / 2),
            )
        }
        UiStyle.Genga -> {
            // A pencil circle drawn in, on twos.
            drawRect(theme.background)
            drawRect(theme.hairline, style = Stroke(1.dp.toPx()))
            val steps = 8f
            val sweep = floor(p * steps) / steps * 340f
            val start = if (loading) floor(spin * 12) / 12 * 360f else -90f
            drawArc(theme.primary, start, if (loading) 280f else sweep, false, Offset(r * 0.3f, r * 0.3f), Size(r * 1.4f, r * 1.4f), style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
        }
        UiStyle.Bunko -> {
            // A ribbon bookmark coming down, swaying while it loads.
            val sway = if (loading) sin(spin * 2 * PI).toFloat() * 8f else 0f
            rotate(sway, Offset(c.x, 0f)) {
                val w = 12.dp.toPx()
                val h = size.height * (0.35f + 0.6f * p)
                val ribbon = Path().apply {
                    moveTo(c.x - w / 2, 0f)
                    lineTo(c.x + w / 2, 0f)
                    lineTo(c.x + w / 2, h)
                    lineTo(c.x, h - w * 0.7f)
                    lineTo(c.x - w / 2, h)
                    close()
                }
                drawPath(ribbon, theme.primary)
            }
        }
        UiStyle.Shinya -> {
            // Colour bars tuning in.
            val bars = listOf(0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0)
            val w = size.width / bars.size
            val shift = if (loading) spin * size.width else 0f
            drawRoundRect(Color.Black, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            bars.forEachIndexed { i, colour ->
                val x = ((i * w + shift) % size.width)
                drawRect(Color(colour).copy(alpha = 0.25f + 0.75f * p), Offset(x, size.height * 0.15f), Size(w + 1f, size.height * 0.7f))
            }
        }
        UiStyle.Konbini -> {
            // A receipt coming out of the printer.
            val w = size.width * 0.7f
            val h = size.height * (0.2f + 0.8f * p)
            val left = c.x - w / 2
            drawRect(theme.paper, Offset(left, 0f), Size(w, h))
            val step = 6.dp.toPx()
            var y = step - (if (loading) spin * step else 0f)
            while (y < h - 2) {
                drawLine(theme.paperInk.copy(alpha = 0.45f), Offset(left + 5.dp.toPx(), y), Offset(left + w * (0.5f + 0.35f * ((y / step).toInt() % 2)), y), 1.5.dp.toPx())
                y += step
            }
            drawRect(theme.paperInk.copy(alpha = 0.7f), Offset(left - 4.dp.toPx(), 0f), Size(w + 8.dp.toPx(), 3.dp.toPx()))
        }
        UiStyle.Stamp -> {
            // The stamp lifts as you pull and comes down, again and again, while it loads.
            val lift = if (loading) (1f - (spin * 2 % 1f)) else p
            val scale = 0.8f + 0.35f * lift
            drawCircle(theme.pencil.copy(alpha = 0.95f - 0.45f * lift), r * 0.8f * scale, style = Stroke(3.dp.toPx()))
            drawCircle(theme.pencil.copy(alpha = 0.7f - 0.4f * lift), r * 0.55f * scale, style = Stroke(1.5.dp.toPx()))
        }
        UiStyle.Glass -> {
            // A glass drop filling up.
            drawCircle(theme.glassPanel, r)
            drawCircle(theme.glassEdge, r, style = Stroke(1.5.dp.toPx()))
            val level = if (loading) 0.55f + 0.1f * sin(spin * 2 * PI).toFloat() else p
            val top = size.height * (1f - level)
            val clip = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r - 3.dp.toPx())) }
            clipPath(clip) {
                drawRect(theme.primary.copy(alpha = 0.75f), Offset(0f, top), Size(size.width, size.height - top))
            }
        }
        UiStyle.Jrpg, UiStyle.Eki -> Unit
    }
}

// ——— Empty and hint text ——————————————————————————————————————————————————————————————————————————————————————————

/** A line of hint or empty-state text, set in the look's way: a speech balloon, a test card, a menu window... */
@Composable
internal fun LookHint(text: String, modifier: Modifier, color: Color) {
    val theme = LocalAppTheme.current
    val plain = TextStyle(color = color, fontSize = 14.sp)
    val outer = modifier.padding(horizontal = 20.dp, vertical = 10.dp)
    when (Look.style) {
        UiStyle.Tankobon -> Box(outer) {
            // A speech balloon with its tail.
            BasicText(
                text,
                style = plain,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .drawBehind {
                        val tail = Path().apply {
                            moveTo(22.dp.toPx(), size.height - 1f)
                            lineTo(16.dp.toPx(), size.height + 8.dp.toPx())
                            lineTo(34.dp.toPx(), size.height - 1f)
                            close()
                        }
                        drawPath(tail, theme.surface)
                        drawPath(tail, theme.ink, style = Stroke(1.5.dp.toPx()))
                    }
                    .clip(RoundedCornerShape(50))
                    .background(theme.surface)
                    .border(1.5.dp, theme.ink, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
        UiStyle.Modern -> BasicText(
            text,
            style = plain,
            modifier = outer.clip(RoundedCornerShape(16.dp)).background(theme.surface).padding(horizontal = 16.dp, vertical = 12.dp),
        )
        UiStyle.Genga -> Column(outer.border(1.dp, theme.hairline).padding(horizontal = 10.dp, vertical = 8.dp)) {
            BasicText("MEMO", style = NumberStyle(color = theme.primary, fontSize = 9.sp, bold = true))
            BasicText(text, style = TextStyle(color = color, fontSize = 14.sp, fontFamily = Kalam, fontWeight = FontWeight.Bold))
        }
        UiStyle.Bunko -> Column(outer.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("❦", style = TextStyle(color = theme.primary, fontSize = 16.sp))
            BasicText(text, style = NumberStyle(color = color, fontSize = 14.sp).copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
        UiStyle.Shinya -> Row(outer, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(26.dp).height(18.dp)) {
                val bars = listOf(0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0)
                val w = size.width / bars.size
                bars.forEachIndexed { i, colour -> drawRect(Color(colour), Offset(i * w, 0f), Size(w + 0.5f, size.height)) }
            }
            Spacer(Modifier.width(10.dp))
            BasicText(text, style = plain)
        }
        UiStyle.Jrpg -> Row(
            outer.fillMaxWidth().rpgWindow(theme).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text, style = NumberStyle(color = Color.White, fontSize = 14.sp), modifier = Modifier.weight(1f))
            BlinkingCursor(theme.accent, 11.sp, modifier = Modifier.graphicsLayer { rotationZ = 90f })
        }
        UiStyle.Eki -> Row(
            outer.clip(RoundedCornerShape(8.dp)).background(EkiBoard).padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(EkiFlap))
            Spacer(Modifier.width(8.dp))
            BasicText(text, style = NumberStyle(color = EkiFlap, fontSize = 13.sp, bold = true))
        }
        UiStyle.Konbini -> Column(outer.fillMaxWidth().background(theme.paper).padding(horizontal = 12.dp, vertical = 10.dp)) {
            DashedRule(theme.paperInk.copy(alpha = 0.4f))
            BasicText(text, style = NumberStyle(color = theme.paperInk, fontSize = 13.sp), modifier = Modifier.padding(vertical = 6.dp))
            DashedRule(theme.paperInk.copy(alpha = 0.4f))
        }
        UiStyle.Stamp -> Row(outer, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(30.dp)) {
                drawCircle(theme.pencil.copy(alpha = 0.5f), size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))))
            }
            Spacer(Modifier.width(10.dp))
            BasicText(text, style = plain)
        }
        UiStyle.Glass -> BasicText(
            text,
            style = plain,
            modifier = outer.glass(theme, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 11.dp),
        )
    }
}

// ——— Marked as seen ———————————————————————————————————————————————————————————————————————————————————————————————

internal enum class MomentKind { Episode, Chapter, Completed, Status, Milestone }

/**
 * Something just done, celebrated for a moment at the top of the screen: an episode or chapter seen (with the next
 * chapter, in a reader), a title completed, a list status changed, or a milestone passed.
 */
internal class Moment(
    val id: Int,
    val style: UiStyle,
    val kind: MomentKind,
    val number: Int? = null,
    val text: String? = null,
    val next: String? = null,
)

internal object LookMoment {
    var current by mutableStateOf<Moment?>(null)
    private var count = 0

    private fun show(kind: MomentKind, number: Int? = null, text: String? = null, next: String? = null) {
        if (!LookPrefs.moments) return
        current = Moment(++count, Look.style, kind, number, text, next)
    }

    /**
     * An episode ([episode]) or chapter numbered [number] was watched or read. When it was the last of [total] the
     * title is celebrated as completed instead. [next] is the chapter a reader goes on to, if known.
     */
    fun fire(episode: Boolean, number: Int?, total: Int? = null, next: String? = null) {
        if (number != null && total != null && total > 0 && number >= total) {
            show(MomentKind.Completed, number, text = if (episode) "All $total episodes" else "All $total chapters")
        } else {
            show(if (episode) MomentKind.Episode else MomentKind.Chapter, number, next = next)
        }
    }

    /** A title's list status changed to [label], like "Watching" or "Completed". */
    fun status(label: String, completed: Boolean, title: String) {
        if (completed) show(MomentKind.Completed, text = title) else show(MomentKind.Status, text = "Moved to $label")
    }

    fun milestone(text: String) = show(MomentKind.Milestone, text = text)
}

/** Draws the moment, if there is one, over whatever is on screen. */
@Composable
internal fun MomentOverlay() {
    val moment = LookMoment.current ?: return
    val view = LocalView.current
    val time = remember(moment.id) { Animatable(0f) }
    val long = moment.kind == MomentKind.Completed || moment.kind == MomentKind.Milestone
    LaunchedEffect(moment.id) {
        LookHaptics.moment(view, moment.style)
        LookSounds.done(moment.style)
        time.animateTo(1f, tween(Motion.ms(if (long) 2400 else 1700), easing = LinearEasing))
        if (LookMoment.current === moment) LookMoment.current = null
    }
    val t = time.value
    // In for the first 18%, out for the last 18%.
    val inT = (t / 0.18f).coerceIn(0f, 1f)
    val outT = ((t - 0.82f) / 0.18f).coerceIn(0f, 1f)
    Box(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = 20.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = 1f - outT
                translationY = -outT * 16.dp.toPx()
            },
        ) {
            MomentBody(moment, words(moment), inT, t)
            moment.next?.let { next ->
                Spacer(Modifier.height(8.dp))
                NextLine(moment.style, next, inT)
            }
        }
    }
}

/** The moment's big line and its small one, in the look's words. */
private class MomentWords(val head: String, val line: String)

private fun words(moment: Moment): MomentWords {
    val episode = moment.kind == MomentKind.Episode
    val seen = if (episode) "watched" else "read"
    val label = (if (episode) "Episode" else "Chapter") + (moment.number?.let { " $it" } ?: "")
    val short = (if (episode) "EP" else "CH") + (moment.number?.let { " %02d".format(it) } ?: "")
    val text = moment.text.orEmpty()
    return when (moment.kind) {
        MomentKind.Episode, MomentKind.Chapter -> when (moment.style) {
            UiStyle.Tankobon -> MomentWords("ドン!", "$short $seen".uppercase())
            UiStyle.Modern, UiStyle.Glass -> MomentWords("$label $seen", "")
            UiStyle.Genga -> MomentWords("作監 OK", "$short · $seen".uppercase())
            UiStyle.Bunko -> MomentWords((moment.number?.let { "第${it}${if (episode) "話" else "章"} · " } ?: "") + "$label $seen", "")
            UiStyle.Shinya -> MomentWords("END OF $short", if (episode) "Thanks for watching" else "Thanks for reading")
            UiStyle.Jrpg -> MomentWords("$label cleared!", "+1 EXP")
            UiStyle.Eki -> MomentWords("$short ${seen.uppercase()}", "発車 DEPARTED")
            UiStyle.Konbini -> MomentWords("1 x $short", "${seen.uppercase()} · ありがとう")
            UiStyle.Stamp -> MomentWords("Stamped!", "$label $seen")
        }
        MomentKind.Completed -> MomentWords(
            when (moment.style) {
                UiStyle.Tankobon -> "完!"
                UiStyle.Modern, UiStyle.Glass -> "Completed!"
                UiStyle.Genga -> "ALL UP"
                UiStyle.Bunko -> "完 · The end"
                UiStyle.Shinya -> "FINAL EPISODE"
                UiStyle.Jrpg -> "QUEST COMPLETE!"
                UiStyle.Eki -> "LAST STOP"
                UiStyle.Konbini -> "PAID IN FULL"
                UiStyle.Stamp -> "Card complete!"
            },
            text,
        )
        MomentKind.Milestone -> MomentWords(
            when (moment.style) {
                UiStyle.Tankobon -> "やった!"
                UiStyle.Modern, UiStyle.Glass -> "Milestone!"
                UiStyle.Genga -> "MILESTONE"
                UiStyle.Bunko -> "A page of your own"
                UiStyle.Shinya -> "SPECIAL BROADCAST"
                UiStyle.Jrpg -> "LEVEL UP!"
                UiStyle.Eki -> "MILESTONE"
                UiStyle.Konbini -> "BONUS POINTS"
                UiStyle.Stamp -> "Gold stamp!"
            },
            text,
        )
        MomentKind.Status -> MomentWords(text, "")
    }
}

/** In a reader, the chapter that comes next, under the moment: "To be continued → Ch 139", "Next stop: Ch 139". */
@Composable
private fun NextLine(style: UiStyle, next: String, inT: Float) {
    val theme = LocalAppTheme.current
    val text = when (style) {
        UiStyle.Tankobon -> "つづく → $next"
        UiStyle.Genga -> "NEXT CUT: $next"
        UiStyle.Shinya -> "NEXT: $next"
        UiStyle.Jrpg -> "Next quest: $next"
        UiStyle.Eki -> "Next stop: $next"
        UiStyle.Konbini -> "NEXT ITEM: ${next.uppercase()}"
        UiStyle.Stamp -> "Next stamp: $next"
        else -> "Up next: $next"
    }
    BasicText(
        text,
        style = NumberStyle(color = theme.ink, fontSize = 12.sp, bold = true),
        modifier = Modifier
            .graphicsLayer { alpha = inT; translationY = (1f - inT) * -8.dp.toPx() }
            .clip(RoundedCornerShape(50))
            .background(theme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MomentBody(moment: Moment, words: MomentWords, inT: Float, t: Float) {
    val theme = LocalAppTheme.current
    val pop = FastOutSlowInEasing.transform(inT)
    val head = words.head
    val line = words.line
    val big = moment.kind == MomentKind.Completed || moment.kind == MomentKind.Milestone
    val glyph = when (moment.kind) {
        MomentKind.Completed -> "完"
        MomentKind.Milestone -> "祝"
        MomentKind.Status -> "報"
        else -> "済"
    }
    when (moment.style) {
        UiStyle.Tankobon -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer {
                    val s = 1.7f - 0.7f * pop
                    scaleX = s
                    scaleY = s
                    rotationZ = -6f
                }
                .size(if (big || moment.kind == MomentKind.Status) 230.dp else 170.dp, 110.dp)
                .drawBehind {
                    // A jagged burst behind the sound effect.
                    val spikes = 14
                    val path = Path()
                    for (i in 0 until spikes * 2) {
                        val a = i * PI / spikes
                        val rr = if (i % 2 == 0) 1f else 0.72f
                        val x = center.x + cos(a).toFloat() * size.width / 2 * rr
                        val y = center.y + sin(a).toFloat() * size.height / 2 * rr
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    drawPath(path, theme.background)
                    drawPath(path, theme.ink, style = Stroke(3.dp.toPx()))
                },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(head, maxLines = 1, style = TextStyle(color = theme.ink, fontSize = if (head.length > 8) 18.sp else 34.sp, fontWeight = FontWeight.Black))
                if (line.isNotEmpty()) BasicText(line, maxLines = 1, style = NumberStyle(color = theme.primary, fontSize = 11.sp, bold = true))
            }
        }
        UiStyle.Modern -> Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(260.dp, 90.dp)) {
                val colours = listOf(theme.primary, theme.secondary, theme.accent)
                val n = if (big) 30 else 18
                for (i in 0 until n) {
                    val a = i * 2 * PI / n + i
                    val d = 30.dp.toPx() + 70.dp.toPx() * t
                    val o = Offset(center.x + cos(a).toFloat() * d * 1.3f, center.y + sin(a).toFloat() * d * 0.5f + 20.dp.toPx() * t * t)
                    drawCircle(colours[i % 3].copy(alpha = (1f - t).coerceIn(0f, 1f)), 3.5.dp.toPx(), o)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .graphicsLayer { scaleX = 0.8f + 0.2f * pop; scaleY = 0.8f + 0.2f * pop }
                    .clip(RoundedCornerShape(28.dp))
                    .background(theme.primary)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText("✓", style = TextStyle(color = theme.background, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(8.dp))
                    BasicText(head, style = TitleStyle(color = theme.background, fontSize = 15.sp))
                }
                if (line.isNotEmpty()) BasicText(line, style = TextStyle(color = theme.background.copy(alpha = 0.85f), fontSize = 12.sp))
            }
        }
        UiStyle.Genga -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.background(theme.background).border(1.5.dp, theme.ink).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // The animation director's red circle, drawn on twos.
            val drawn = floor(pop * 6) / 6
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(40.dp)) {
                    drawArc(theme.pencil, -90f, 360f * drawn, false, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                if (moment.kind != MomentKind.Episode && moment.kind != MomentKind.Chapter) {
                    BasicText(glyph, style = TextStyle(color = theme.pencil, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                BasicText(head, style = TextStyle(color = theme.pencil, fontSize = 15.sp, fontFamily = Kalam, fontWeight = FontWeight.Bold))
                if (line.isNotEmpty()) BasicText(line.uppercase(), style = NumberStyle(color = theme.foreground, fontSize = 11.sp, bold = true))
            }
        }
        UiStyle.Bunko -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .graphicsLayer { translationX = (1f - pop) * 420.dp.toPx() }
                .fillMaxWidth()
                .height(if (line.isEmpty()) 44.dp else 58.dp)
                .background(Color(0xFFF2E9D8))
                .drawBehind {
                    drawLine(Color(0xFF8A1C1C), Offset(0f, 4.dp.toPx()), Offset(size.width, 4.dp.toPx()), 1.dp.toPx())
                    drawLine(Color(0xFF8A1C1C), Offset(0f, size.height - 4.dp.toPx()), Offset(size.width, size.height - 4.dp.toPx()), 1.dp.toPx())
                },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(head, style = NumberStyle(color = Color(0xFF8A1C1C), fontSize = 15.sp, bold = true))
                if (line.isNotEmpty()) BasicText(line, style = NumberStyle(color = Color(0xFF5A3A2A), fontSize = 12.sp))
            }
        }
        UiStyle.Shinya -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.85f)).scanlines().padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            BasicText(if (moment.kind == MomentKind.Episode || moment.kind == MomentKind.Chapter) "終" else glyph, style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.width(10.dp))
            Column {
                BasicText(head, style = NumberStyle(color = Color(0xFF8EF0FF), fontSize = 13.sp))
                if (line.isNotEmpty()) BasicText(line, style = NumberStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp))
            }
        }
        UiStyle.Jrpg -> Column(
            Modifier.widthIn(min = 220.dp).rpgWindow(theme).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicText(head, style = NumberStyle(color = if (big) theme.accent else Color.White, fontSize = if (big) 18.sp else 15.sp))
            if (line.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(line, style = NumberStyle(color = if (big) Color.White else theme.accent, fontSize = 13.sp))
                    if (moment.kind == MomentKind.Episode || moment.kind == MomentKind.Chapter) {
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.width(110.dp).height(7.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.2f))) {
                            Box(Modifier.fillMaxWidth((0.35f + 0.65f * pop).coerceAtMost(1f)).fillMaxHeight().background(EkiGo))
                        }
                    }
                }
            }
        }
        UiStyle.Eki -> Column(
            Modifier.clip(RoundedCornerShape(8.dp)).background(EkiBoard).padding(10.dp),
        ) {
            if (line.isNotEmpty()) {
                BasicText(line, style = NumberStyle(color = EkiGo, fontSize = 11.sp, bold = true))
                Spacer(Modifier.height(4.dp))
            }
            if (head.length <= 18) {
                FlapText(head.uppercase(), tile = 13.dp, key = "moment-${moment.id}")
            } else {
                BasicText(head, style = NumberStyle(color = EkiFlap, fontSize = 14.sp, bold = true))
            }
        }
        UiStyle.Konbini -> Column(
            Modifier.width(260.dp).printOnce("moment-${moment.id}").clip(ReceiptShape).background(theme.paper).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicText(head.uppercase(), maxLines = 1, style = NumberStyle(color = theme.paperInk, fontSize = 13.sp, bold = true), modifier = Modifier.weight(1f, fill = false))
                Box(Modifier.weight(0.3f).height(12.dp).padding(horizontal = 4.dp).leaders(theme.paperInk.copy(alpha = 0.4f)))
                BasicText("¥0", style = NumberStyle(color = theme.paperInk, fontSize = 13.sp, bold = true))
            }
            if (line.isNotEmpty()) BasicText(line, style = NumberStyle(color = theme.paperInk.copy(alpha = 0.7f), fontSize = 10.sp))
        }
        UiStyle.Stamp -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(theme.paper).padding(10.dp)) {
            Box(Modifier.stampDown("moment-${moment.id}")) {
                StampMark(glyph, if (big) Color(0xFFC9A227) else theme.pencil, 58.dp, tilt = -10f, small = moment.number?.toString())
            }
            Spacer(Modifier.width(10.dp))
            Column {
                BasicText(head, style = TitleStyle(color = theme.paperInk, fontSize = 15.sp))
                if (line.isNotEmpty()) BasicText(line, style = TextStyle(color = theme.paperInk.copy(alpha = 0.7f), fontSize = 12.sp))
            }
        }
        UiStyle.Glass -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .glass(theme, RoundedCornerShape(28.dp))
                .drawBehind {
                    // A shine passing across the glass.
                    val x = size.width * (t * 1.6f - 0.3f)
                    drawRect(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                            startX = x - 40.dp.toPx(),
                            endX = x + 40.dp.toPx(),
                        ),
                    )
                }
                .padding(horizontal = 20.dp, vertical = 11.dp),
        ) {
            BasicText(head, style = TitleStyle(color = theme.ink, fontSize = 15.sp))
            if (line.isNotEmpty()) BasicText(line, style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 12.sp))
        }
    }
}
