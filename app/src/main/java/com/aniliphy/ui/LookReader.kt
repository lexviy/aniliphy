package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The readers' and the player's own interface in the look: their bars, the title in them, the seek and page slider,
 * the little labels over the page or video, and the sheets that slide up. The reading surface itself (paper, ink,
 * the novel's background setting, PDF pages, the video) is left exactly as it is.
 */

/** The colours of text on a reader bar. */
internal class BarInk(val text: Color, val dim: Color, val track: Color)

/** The look's bar colours, for a bar over a page or, with [video], over a playing video (always dark). */
@Composable
internal fun readerBarInk(video: Boolean): BarInk {
    val theme = LocalAppTheme.current
    val onDark = video || Look.style == UiStyle.Shinya || Look.style == UiStyle.Eki
    return when {
        Look.style == UiStyle.Eki -> BarInk(EkiPlate, EkiFlap, Color.White.copy(alpha = 0.25f))
        onDark -> BarInk(Color.White, Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.3f))
        Look.style == UiStyle.Konbini -> BarInk(theme.paperInk, theme.paperInk.copy(alpha = 0.7f), theme.paperInk.copy(alpha = 0.2f))
        else -> BarInk(theme.foreground, theme.foreground.copy(alpha = 0.72f), theme.panel)
    }
}

private val FLOATING = setOf(UiStyle.Modern, UiStyle.Jrpg, UiStyle.Glass)

/**
 * A reader's or the player's bar in the look. [top] is the bar at the top of the screen (its decorated edge faces
 * down, toward the page); [insets] keeps it clear of the system bars; [video] puts it over a playing video. The
 * floating looks (Modern, JRPG, Glass) inset the bar from the edges as a panel of its own.
 */
@Composable
internal fun ReaderBar(
    top: Boolean,
    insets: Modifier,
    modifier: Modifier = Modifier,
    video: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val scrim = Color.Black.copy(alpha = 0.6f)
    if (style in FLOATING) {
        val shape: Shape = when (style) {
            UiStyle.Modern -> RoundedCornerShape(22.dp)
            UiStyle.Glass -> RoundedCornerShape(18.dp)
            else -> RoundedCornerShape(10.dp)
        }
        val panel = when (style) {
            UiStyle.Jrpg -> Modifier.rpgWindow(theme).let { if (video) it.background(Color(0xE60E1446)) else it }
            UiStyle.Glass -> if (video) Modifier.clip(shape).background(Color.Black.copy(alpha = 0.45f)).border(1.dp, Color.White.copy(alpha = 0.18f), shape) else Modifier.glass(theme, shape)
            else -> Modifier.clip(shape).background(if (video) Color.Black.copy(alpha = 0.62f) else theme.surface)
        }
        Column(
            modifier
                .fillMaxWidth()
                .then(insets)
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .then(panel)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            content = content,
        )
        return
    }
    val background = when (style) {
        UiStyle.Tankobon -> if (video) scrim else theme.surface
        UiStyle.Genga -> if (video) scrim else theme.background
        UiStyle.Bunko -> if (video) scrim else theme.surface
        UiStyle.Shinya -> Color.Black.copy(alpha = if (video) 0.7f else 0.92f)
        UiStyle.Eki -> EkiBoard.copy(alpha = if (video) 0.88f else 1f)
        UiStyle.Konbini -> if (video) scrim else theme.paper
        UiStyle.Stamp -> if (video) scrim else theme.paper
        else -> if (video) scrim else theme.surface
    }
    val line = if (video) Color.White else theme.ink
    Column(
        modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { barEdge(style, top, video, line, theme) }
            .then(insets)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}

/** The bar's edge toward the page: each look's own rule. */
private fun DrawScope.barEdge(style: UiStyle, top: Boolean, video: Boolean, line: Color, theme: AppTheme) {
    val y = if (top) size.height else 0f
    val inward = if (top) -1f else 1f
    when (style) {
        UiStyle.Tankobon -> {
            // A thick panel border, and a band of screentone along it.
            val step = 5.dp.toPx()
            var x: Float
            var row = 0
            var by = y + inward * (4.dp.toPx())
            while (row < 2) {
                x = if (row % 2 == 0) 0f else step / 2
                while (x < size.width) {
                    drawCircle(line.copy(alpha = 0.35f), 1.dp.toPx(), Offset(x, by))
                    x += step
                }
                by += inward * step
                row++
            }
            drawLine(line, Offset(0f, y + inward * 1.5.dp.toPx()), Offset(size.width, y + inward * 1.5.dp.toPx()), 3.dp.toPx())
        }
        UiStyle.Genga -> {
            // The timing sheet's edge: a ruled line with frame ticks, every sixth one longer, and the red pencil.
            val base = y + inward * 1.dp.toPx()
            drawLine(line, Offset(0f, base), Offset(size.width, base), 2.dp.toPx())
            val step = 8.dp.toPx()
            var i = 0
            var x = 0f
            while (x < size.width) {
                val long = i % 6 == 0
                drawLine(line.copy(alpha = 0.6f), Offset(x, base), Offset(x, base + inward * (if (long) 7 else 4).dp.toPx()), 1.dp.toPx())
                x += step
                i++
            }
            drawLine(theme.pencil, Offset(0f, base + inward * 9.dp.toPx()), Offset(size.width * 0.22f, base + inward * 9.dp.toPx()), 2.dp.toPx())
        }
        UiStyle.Bunko -> {
            val hair = if (video) Color.White.copy(alpha = 0.45f) else theme.hairline
            drawLine(hair, Offset(0f, y + inward * 0.5f), Offset(size.width, y + inward * 0.5f), 1.dp.toPx())
            drawLine(hair, Offset(0f, y + inward * 3.5.dp.toPx()), Offset(size.width, y + inward * 3.5.dp.toPx()), 1.dp.toPx())
        }
        UiStyle.Shinya -> {
            // Scanlines, and the station's colour along the edge like a lit strip.
            var sy = 0f
            while (sy < size.height) {
                drawLine(Color.White.copy(alpha = 0.04f), Offset(0f, sy), Offset(size.width, sy), 1f)
                sy += 3.dp.toPx()
            }
            drawLine(theme.primary, Offset(0f, y + inward * 1.dp.toPx()), Offset(size.width, y + inward * 1.dp.toPx()), 2.dp.toPx())
        }
        UiStyle.Eki -> {
            // The line colour under the board, as on the platform signs.
            drawRect(EkiFlap, Offset(0f, if (top) size.height - 4.dp.toPx() else 0f), Size(size.width, 4.dp.toPx()))
        }
        UiStyle.Konbini -> {
            // The receipt's torn edge.
            val tooth = 6.dp.toPx()
            val path = Path()
            val edge = if (top) size.height else 0f
            path.moveTo(0f, edge)
            var x = 0f
            while (x < size.width) {
                path.lineTo(x + tooth / 2, edge + inward * tooth / 2)
                path.lineTo(x + tooth, edge)
                x += tooth
            }
            drawPath(path, if (video) Color.White.copy(alpha = 0.5f) else theme.hairline, style = Stroke(1.5.dp.toPx()))
            drawLine(
                if (video) Color.White.copy(alpha = 0.5f) else theme.hairline,
                Offset(0f, y + inward * 6.dp.toPx()),
                Offset(size.width, y + inward * 6.dp.toPx()),
                1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
        }
        UiStyle.Stamp -> {
            drawLine(
                theme.pencil.copy(alpha = if (video) 0.9f else 0.75f),
                Offset(0f, y + inward * 2.dp.toPx()),
                Offset(size.width, y + inward * 2.dp.toPx()),
                2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
            )
        }
        else -> Unit
    }
}

/** The title and the chapter or episode under it, in the look's faces. */
@Composable
internal fun ReaderHeading(title: String, subtitle: String, ink: BarInk, modifier: Modifier = Modifier) {
    val style = Look.style
    Column(modifier.lookAlive(Alive.Heading)) {
        val shown = if (style == UiStyle.Konbini) title.uppercase() else title
        BasicText(shown, maxLines = 1, overflow = marqueeOverflow(), style = TitleStyle(color = ink.text, fontSize = 15.sp), modifier = Modifier.lookMarquee())
        val sub = when (style) {
            UiStyle.Genga -> "CUT · ${subtitle.uppercase()}"
            UiStyle.Jrpg -> "▶ $subtitle"
            UiStyle.Eki -> "→ $subtitle"
            UiStyle.Konbini -> "# $subtitle"
            else -> subtitle
        }
        BasicText(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = ink.dim, fontSize = 12.sp))
    }
}

/** Small text on a bar: page counts, times, chapter names. */
@Composable
internal fun ReaderBarText(text: String, ink: BarInk, modifier: Modifier = Modifier, dim: Boolean = false) {
    BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = if (dim) ink.dim else ink.text, fontSize = 12.sp), modifier = modifier)
}

/**
 * The seek or page slider with the look's own handle: a panel (Tankōbon), a registration mark (Genga), a bookmark
 * ribbon (Bunko), a lit bar (Shin'ya), the menu cursor (JRPG), a flap (Eki), a barcode (Konbini), a stamp (Stamp
 * rally) or a glass bead (Glass).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LookSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    ink: BarInk,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    inactive: Color = ink.track,
) {
    val theme = LocalAppTheme.current
    val accent = when (Look.style) {
        UiStyle.Eki -> EkiFlap
        UiStyle.Stamp, UiStyle.Genga -> theme.pencil
        else -> theme.primary
    }
    val colors = SliderDefaults.colors(
        thumbColor = accent,
        activeTrackColor = accent,
        inactiveTrackColor = inactive,
        disabledThumbColor = inactive,
        disabledActiveTrackColor = inactive,
    )
    if (Look.style == UiStyle.Modern) {
        Slider(value, onValueChange, modifier, enabled = enabled, onValueChangeFinished = onValueChangeFinished, colors = colors, valueRange = valueRange)
        return
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        valueRange = valueRange,
        thumb = { LookThumb(accent, ink) },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                enabled = enabled,
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null,
                modifier = Modifier.then(trackShape()),
            )
        },
    )
}

@Composable
private fun trackShape(): Modifier = when (Look.style) {
    UiStyle.Genga, UiStyle.Konbini, UiStyle.Bunko, UiStyle.Eki -> Modifier.clip(RectangleShape)
    else -> Modifier
}

@Composable
private fun LookThumb(accent: Color, ink: BarInk) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val size: Pair<Dp, Dp> = when (style) {
        UiStyle.Tankobon -> 14.dp to 24.dp
        UiStyle.Bunko -> 12.dp to 26.dp
        UiStyle.Shinya -> 5.dp to 26.dp
        UiStyle.Konbini -> 16.dp to 24.dp
        UiStyle.Eki -> 18.dp to 22.dp
        UiStyle.Stamp -> 26.dp to 26.dp
        else -> 22.dp to 22.dp
    }
    Box(Modifier.size(width = 26.dp, height = 28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size.first, size.second)) {
            val w = this.size.width
            val h = this.size.height
            when (style) {
                UiStyle.Tankobon -> {
                    drawRect(theme.background)
                    drawRect(accent, Offset(2.dp.toPx(), 2.dp.toPx()), Size(w - 4.dp.toPx(), h - 4.dp.toPx()))
                    drawRect(theme.ink, style = Stroke(2.dp.toPx()))
                }
                UiStyle.Genga -> {
                    // A registration mark in red pencil.
                    val c = Offset(w / 2, h / 2)
                    drawCircle(accent, w * 0.3f, c, style = Stroke(2.dp.toPx()))
                    drawLine(accent, Offset(0f, h / 2), Offset(w, h / 2), 1.5.dp.toPx())
                    drawLine(accent, Offset(w / 2, 0f), Offset(w / 2, h), 1.5.dp.toPx())
                }
                UiStyle.Bunko -> {
                    // A ribbon bookmark with its notched tail.
                    val path = Path().apply {
                        moveTo(0f, 0f); lineTo(w, 0f); lineTo(w, h); lineTo(w / 2, h - w * 0.6f); lineTo(0f, h); close()
                    }
                    drawPath(path, accent)
                }
                UiStyle.Shinya -> {
                    drawRect(accent.copy(alpha = 0.35f), Offset(-3.dp.toPx(), 0f), Size(w + 6.dp.toPx(), h))
                    drawRect(Color.White)
                }
                UiStyle.Jrpg -> {
                    // The menu cursor pointing down at the place.
                    val path = Path().apply { moveTo(w * 0.1f, h * 0.15f); lineTo(w * 0.9f, h * 0.15f); lineTo(w / 2, h * 0.85f); close() }
                    drawPath(path, ink.text)
                    drawPath(path, accent, style = Stroke(1.5.dp.toPx()))
                }
                UiStyle.Eki -> {
                    drawRect(accent)
                    drawLine(EkiBoard, Offset(0f, h / 2), Offset(w, h / 2), 1.5.dp.toPx())
                }
                UiStyle.Konbini -> {
                    // A strip of barcode.
                    drawRect(theme.paper)
                    val bars = listOf(2f, 1f, 3f, 1f, 2f, 1f, 1f, 3f)
                    var x = 1.dp.toPx()
                    bars.forEachIndexed { i, b ->
                        val bw = b * 0.6.dp.toPx()
                        if (i % 2 == 0) drawRect(theme.paperInk, Offset(x, 2.dp.toPx()), Size(bw, h - 4.dp.toPx()))
                        x += bw + 0.8.dp.toPx()
                    }
                    drawRect(theme.paperInk, style = Stroke(1.dp.toPx()))
                }
                UiStyle.Stamp -> {
                    val c = Offset(w / 2, h / 2)
                    drawCircle(accent.copy(alpha = 0.85f), w / 2 - 1.dp.toPx(), c)
                    drawCircle(Color.White.copy(alpha = 0.8f), w / 2 - 5.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                }
                UiStyle.Glass -> {
                    val c = Offset(w / 2, h / 2)
                    drawCircle(Color.White.copy(alpha = 0.55f), w / 2, c)
                    drawCircle(accent.copy(alpha = 0.8f), w / 2 - 5.dp.toPx(), c)
                    drawCircle(Color.White, w / 2, c, style = Stroke(1.dp.toPx()))
                }
                else -> drawCircle(accent)
            }
        }
    }
}

/** A short label over the page or the video (the page number, the swipe's volume or seek), in the look. */
@Composable
internal fun LookLabel(text: String, modifier: Modifier = Modifier, large: Boolean = false) {
    val theme = LocalAppTheme.current
    val textSize = if (large) 18.sp else 11.sp
    val padH = if (large) 16.dp else 8.dp
    val padV = if (large) 10.dp else 3.dp
    val dark = Color.Black.copy(alpha = 0.66f)
    val (box, color) = when (Look.style) {
        UiStyle.Tankobon -> Modifier.clip(CutCornerShape(bottomEnd = 8.dp)).background(Color.Black.copy(alpha = 0.85f)).border(2.dp, Color.White, CutCornerShape(bottomEnd = 8.dp)) to Color.White
        UiStyle.Genga -> Modifier.background(theme.background.copy(alpha = 0.92f)).border(1.5.dp, theme.ink) to theme.ink
        UiStyle.Bunko -> Modifier.clip(RoundedCornerShape(2.dp)).background(theme.paper.copy(alpha = 0.95f)).border(1.dp, theme.hairline, RoundedCornerShape(2.dp)) to theme.paperInk
        UiStyle.Shinya -> Modifier.background(Color.Black.copy(alpha = 0.85f)).drawBehind {
            drawRect(theme.primary, Offset.Zero, Size(3.dp.toPx(), size.height))
        } to Color.White
        UiStyle.Jrpg -> Modifier.rpgWindow(theme, 8.dp) to theme.foreground
        UiStyle.Eki -> Modifier.background(EkiBoard).border(1.dp, Color.Black) to EkiFlap
        UiStyle.Konbini -> Modifier.background(theme.paper).border(1.dp, theme.paperInk.copy(alpha = 0.5f)) to theme.paperInk
        UiStyle.Stamp -> Modifier.clip(RoundedCornerShape(50)).background(theme.paper).border(1.5.dp, theme.pencil, RoundedCornerShape(50)) to theme.pencil
        UiStyle.Glass -> Modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.4f)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(14.dp)) to Color.White
        UiStyle.Modern -> Modifier.clip(RoundedCornerShape(12.dp)).background(dark) to Color.White
    }
    val shown = if (Look.style == UiStyle.Konbini) text.uppercase() else text
    BasicText(
        shown,
        maxLines = 1,
        style = if (large) TitleStyle(color = color, fontSize = textSize) else NumberStyle(color = color, fontSize = textSize),
        modifier = modifier.then(box).padding(horizontal = padH, vertical = padV),
    )
}

/** A sheet sliding up over a reader or the player, framed the look's way. */
@Composable
internal fun LookSheet(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val frame = when (style) {
        UiStyle.Jrpg -> Modifier.padding(horizontal = 8.dp).rpgWindow(theme, 12.dp)
        UiStyle.Glass -> Modifier.glass(theme, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(theme.surface.copy(alpha = 0.6f))
        UiStyle.Modern -> Modifier.clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(theme.surface)
        UiStyle.Genga -> Modifier.background(theme.background).drawBehind { barEdge(style, top = false, video = false, line = theme.ink, theme = theme) }
        UiStyle.Konbini, UiStyle.Stamp -> Modifier.background(theme.paper).drawBehind { barEdge(style, top = false, video = false, line = theme.ink, theme = theme) }
        UiStyle.Tankobon -> Modifier.clip(CutCornerShape(topEnd = 14.dp)).background(theme.surface).drawWithContent {
            drawContent()
            drawLine(theme.ink, Offset(0f, 1.5.dp.toPx()), Offset(size.width, 1.5.dp.toPx()), 3.dp.toPx())
        }
        UiStyle.Shinya -> Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(theme.surface).drawBehind {
            drawLine(theme.primary, Offset(0f, 1.dp.toPx()), Offset(size.width, 1.dp.toPx()), 2.dp.toPx())
        }
        UiStyle.Bunko, UiStyle.Eki -> Modifier.clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(theme.surface).drawBehind {
            barEdge(style, top = false, video = false, line = theme.ink, theme = theme)
        }
    }
    Column(modifier.fillMaxWidth().then(frame), content = content)
}

/** The sheet's title, as each look heads a section. */
@Composable
internal fun LookSheetTitle(title: String, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val text = when (Look.style) {
        UiStyle.Konbini -> "*** ${title.uppercase()} ***"
        UiStyle.Genga -> title.uppercase()
        UiStyle.Jrpg -> "▶ $title"
        else -> title
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        if (Look.style == UiStyle.Eki) {
            Box(Modifier.size(width = 4.dp, height = 18.dp).background(EkiFlap))
            Spacer(Modifier.width(8.dp))
        }
        BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = if (Look.style == UiStyle.Konbini) theme.paperInk else theme.foreground, fontSize = 18.sp))
    }
}
