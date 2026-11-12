package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colours the looks need beyond the theme's own, all worked out from the theme so custom themes get them too.

/** Primary darkened until white text on it reads (Shin'ya's telop outlines and buttons). */
internal val AppTheme.deepPrimary: Color
    get() {
        var color = primary
        repeat(10) {
            if (contrast(color, Color.White) >= 4.5f) return color
            color = lerp(color, Color.Black, 0.15f)
        }
        return color
    }

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/** Genga's red pencil and the stamps' ink. */
internal val AppTheme.pencil: Color get() = error

/** Thin frame lines on Genga's sheet and Konbini's receipt. */
internal val AppTheme.hairline: Color get() = foreground.copy(alpha = if (dark) 0.32f else 0.35f)

/** The receipt paper: a lighter sheet on dark themes, near-white on light ones. */
internal val AppTheme.paper: Color get() = if (dark) lerp(surface, foreground, 0.05f) else lerp(background, Color.White, 0.85f)
internal val AppTheme.paperInk: Color get() = if (dark) lerp(foreground, Color.White, 0.4f) else Color(0xFF1D1F24)

/** Eki's station signs stay white plates with black letters, as real ones do. */
internal val EkiPlate = Color(0xFFF4F5F7)
internal val EkiPlateInk = Color(0xFF15171C)
internal val EkiBoard = Color(0xFF15171C)
internal val EkiFlap = Color(0xFFFFC24A)
internal val EkiGo = Color(0xFF66D17A)

/** A line colour for each numbered section, like the colours of train lines. */
internal fun AppTheme.ekiLine(number: Int?): Color = when ((number ?: 1) % 5) {
    1 -> primary
    2 -> Color(0xFFE8780E)
    3 -> Color(0xFF2E9E5B)
    4 -> Color(0xFF8E5BD0)
    else -> Color(0xFF1D9BB5)
}

/** Glass panels: smoked on dark themes, milky on light ones. */
internal val AppTheme.glassPanel: Color get() = if (dark) surface.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.58f)
internal val AppTheme.glassEdge: Color get() = if (dark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.9f)

/** The JRPG window frame: a double line, the outer one in the foreground colour. */
internal fun Modifier.rpgWindow(theme: AppTheme, radius: Dp = 10.dp): Modifier =
    this
        .clip(RoundedCornerShape(radius))
        .background(theme.surface)
        .border(2.dp, theme.foreground, RoundedCornerShape(radius))
        .padding(2.dp)
        .border(2.dp, theme.surface, RoundedCornerShape(radius - 2.dp))
        .padding(2.dp)
        .border(1.dp, theme.foreground.copy(alpha = 0.7f), RoundedCornerShape(radius - 4.dp))

/** A frosted glass panel. */
internal fun Modifier.glass(theme: AppTheme, shape: androidx.compose.ui.graphics.Shape): Modifier =
    this.clip(shape).background(theme.glassPanel).border(1.dp, theme.glassEdge, shape)

/** Things that sit behind every screen in some looks: Genga's drafting grid, Glass's light from the covers. */
@Composable
internal fun StyleBackdrop(modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    when (Look.style) {
        UiStyle.Genga -> Box(
            modifier.drawBehind {
                val step = 24.dp.toPx()
                val line = theme.primary.copy(alpha = if (theme.dark) 0.07f else 0.09f)
                var x = 0f
                while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
                var y = 0f
                while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y), 1f); y += step }
            },
        )
        UiStyle.Glass -> Box(
            modifier.drawBehind {
                val strength = if (theme.dark) 0.34f else 0.24f
                fun glow(color: Color, x: Float, y: Float, r: Float) = drawRect(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = strength), color.copy(alpha = 0f)),
                        center = Offset(size.width * x, size.height * y),
                        radius = size.minDimension * r,
                    ),
                )
                glow(theme.primary, 0.15f, 0.12f, 0.75f)
                glow(theme.accent, 0.95f, 0.35f, 0.7f)
                glow(theme.secondary, 0.3f, 0.8f, 0.8f)
            },
        )
        else -> Unit
    }
}

/**
 * A section heading in the looks other than Tankōbon and Modern. [number], when given, is the section's place on
 * the page: a CUT number, a kanji chapter, a line badge or a stamp.
 */
@Composable
internal fun StyledHeading(title: String, number: Int?, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    when (Look.style) {
        UiStyle.Genga -> Row(modifier.border(1.dp, theme.hairline).background(theme.background)) {
            if (number != null) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.drawBehind {
                        drawLine(theme.hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                    }.padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BasicText("CUT", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 8.sp, bold = true))
                    BasicText("%02d".format(number), style = NumberStyle(color = theme.primary, fontSize = 15.sp, bold = true))
                }
            }
            BasicText(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TitleStyle(color = theme.ink, fontSize = 17.sp),
                modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        UiStyle.Bunko -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            if (number != null) {
                BasicText(kanjiNumeral(number), style = TitleStyle(color = theme.primary, fontSize = 23.sp))
                Spacer(Modifier.width(10.dp))
            }
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 19.sp))
            Spacer(Modifier.width(10.dp))
            DoubleRule(theme.hairline, Modifier.weight(1f))
        }
        UiStyle.Shinya -> TelopText(title, 22.sp, fill = Color.White, stroke = theme.deepPrimary, modifier = modifier)
        UiStyle.Jrpg -> Row(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rpgWindow(theme, 8.dp).padding(horizontal = 12.dp, vertical = 4.dp)) {
                BlinkingCursor(theme.accent, 13.sp)
                Spacer(Modifier.width(8.dp))
                BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 17.sp))
            }
        }
        UiStyle.Eki -> Column(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (number != null) {
                    LineBadge(stationCode(title), "%02d".format(number), theme.ekiLine(number), 34.dp)
                    Spacer(Modifier.width(10.dp))
                }
                BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 20.sp))
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).background(theme.ekiLine(number)))
        }
        UiStyle.Konbini -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                "** ${title.uppercase()} **",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TitleStyle(color = theme.ink, fontSize = 15.sp),
            )
            Spacer(Modifier.height(6.dp))
            DashedRule(theme.hairline, Modifier.fillMaxWidth())
        }
        UiStyle.Stamp -> Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
            if (number != null) {
                StampMark(number.toString(), theme.pencil, 30.dp, tilt = -8f)
                Spacer(Modifier.width(10.dp))
            }
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 19.sp))
        }
        UiStyle.Glass, UiStyle.Modern, UiStyle.Tankobon ->
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.ink, fontSize = 21.sp), modifier = modifier)
    }
}

private val KANJI = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")

private fun kanjiNumeral(number: Int): String = KANJI.getOrNull(number - 1) ?: number.toString()

/** Two letters for a station badge, from the section's title. */
private fun stationCode(title: String): String = title.filter { it.isLetter() }.take(2).uppercase()

@Composable
internal fun DoubleRule(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).drawBehind {
        drawLine(color, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
        drawLine(color, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx())
    })
}

@Composable
internal fun DashedRule(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(2.dp).drawBehind {
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
    })
}

/** Eki's station-number badge: a white square ringed in the line colour, a code over a number. */
@Composable
internal fun LineBadge(code: String, number: String, line: Color, size: Dp) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.2f))
            .background(EkiPlate)
            .border(size * 0.09f, line, RoundedCornerShape(size * 0.2f)),
    ) {
        BasicText(code, maxLines = 1, style = TitleStyle(color = EkiPlateInk, fontSize = (size.value * 0.28f).sp, lineHeight = (size.value * 0.3f).sp))
        BasicText(number, maxLines = 1, style = TitleStyle(color = EkiPlateInk, fontSize = (size.value * 0.36f).sp, lineHeight = (size.value * 0.38f).sp))
    }
}

/** A round rubber stamp: a double ring with brush lettering, a little tilted. */
@Composable
internal fun StampMark(text: String, ink: Color, size: Dp, tilt: Float = 0f, small: String? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .size(size)
            .rotate(tilt)
            .drawBehind {
                val outer = this.size.minDimension / 2f - 1.5.dp.toPx()
                drawCircle(ink, outer, style = Stroke(2.2.dp.toPx()))
                drawCircle(ink, outer - 3.5.dp.toPx(), style = Stroke(1.dp.toPx()))
            },
    ) {
        if (small != null) BasicText(small, maxLines = 1, style = TextStyle(color = ink, fontSize = (size.value * 0.14f).sp, fontWeight = FontWeight.Black))
        BasicText(
            text,
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(color = ink, fontSize = (size.value * if (text.length > 2) 0.26f else 0.4f).sp, fontFamily = YujiSyuku),
        )
    }
}

/** Variety-show captions: heavy letters with a thick outline drawn first, the fill over it. */
@Composable
internal fun TelopText(
    text: String,
    fontSize: TextUnit,
    fill: Color,
    stroke: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val width = with(LocalDensity.current) { fontSize.toPx() * 0.24f }
    Box(modifier) {
        BasicText(
            text,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = TitleStyle(color = stroke, fontSize = fontSize).copy(drawStyle = Stroke(width = width, join = StrokeJoin.Round)),
        )
        BasicText(text, maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = fill, fontSize = fontSize))
    }
}

/** Whether this look prints the countdown on the cover (the others show it under the title). */
internal val Look.countdownOnCover: Boolean
    get() = when (style) {
        UiStyle.Tankobon, UiStyle.Genga, UiStyle.Bunko, UiStyle.Eki, UiStyle.Glass -> true
        UiStyle.Modern, UiStyle.Shinya, UiStyle.Jrpg, UiStyle.Konbini, UiStyle.Stamp -> false
    }

/** The countdown on a cover ("Ep 7 in 1d 4h"), placed and drawn the way the look does it. */
@Composable
internal fun BoxScope.CoverCountdown(text: String, compact: Boolean) {
    val theme = LocalAppTheme.current
    val size = if (compact) 9.sp else 11.sp
    val autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = size)
    when (Look.style) {
        UiStyle.Genga -> BasicText(
            text.substringAfter(" in ", text),
            maxLines = 1,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = if (compact) 11.sp else 14.sp),
            style = androidx.compose.ui.text.TextStyle(color = theme.pencil, fontFamily = Kalam, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .rotate(-6f)
                .background(theme.background, CircleShape)
                .border(2.dp, theme.pencil, CircleShape)
                .padding(horizontal = 8.dp, vertical = 1.dp),
        )
        UiStyle.Bunko -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(if (compact) 30.dp else 42.dp)
                .background(theme.primary),
        ) {
            val (top, main) = if (" in " in text) text.substringBefore(" in ").uppercase() + " IN" to text.substringAfter(" in ") else "" to text
            if (top.isNotEmpty()) BasicText(top, maxLines = 1, autoSize = autoSize, style = TextStyle(color = theme.background, fontSize = 9.sp, fontWeight = FontWeight.Bold))
            BasicText(
                main,
                maxLines = 1,
                autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = if (compact) 12.sp else 16.sp),
                style = TitleStyle(color = theme.background, fontSize = 16.sp),
            )
        }
        UiStyle.Eki -> BasicText(
            text.substringAfter(" in ", text),
            maxLines = 1,
            autoSize = autoSize,
            style = NumberStyle(color = EkiFlap, fontSize = size, bold = true),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(EkiBoard)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        UiStyle.Glass -> BasicText(
            text,
            maxLines = 1,
            autoSize = autoSize,
            style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.ExtraBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF14141C).copy(alpha = 0.55f))
                .padding(vertical = 4.dp, horizontal = 4.dp),
        )
        else -> CountdownTag(text, Modifier.align(Alignment.BottomStart).padding(bottom = if (compact) 8.dp else 14.dp), fontSize = size)
    }
}

/** The notice in a cover's corner in the looks other than Tankōbon and Modern. */
@Composable
internal fun StyledNewBadge(text: String, modifier: Modifier, fontSize: TextUnit) {
    val theme = LocalAppTheme.current
    when (Look.style) {
        UiStyle.Genga -> BasicText(
            text.lowercase() + "!",
            maxLines = 1,
            style = androidx.compose.ui.text.TextStyle(color = theme.pencil, fontSize = fontSize * 1.3f, fontFamily = Kalam, fontWeight = FontWeight.Bold),
            modifier = modifier
                .rotate(-7f)
                .background(theme.background)
                .drawBehind { drawLine(theme.pencil, Offset(0f, size.height - 1.dp.toPx()), Offset(size.width, size.height - 1.dp.toPx()), 2.dp.toPx()) }
                .padding(horizontal = 5.dp),
        )
        UiStyle.Bunko -> BasicText(
            "新刊 · $text",
            maxLines = 1,
            style = TitleStyle(color = Color.White, fontSize = fontSize),
            modifier = modifier.background(theme.error).padding(horizontal = 7.dp, vertical = 2.dp),
        )
        UiStyle.Shinya -> BasicText(
            text.uppercase(),
            maxLines = 1,
            style = NumberStyle(color = Color.White, fontSize = fontSize),
            modifier = modifier.clip(RoundedCornerShape(4.dp)).background(theme.error).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        UiStyle.Jrpg -> BasicText(
            "! $text",
            maxLines = 1,
            style = NumberStyle(color = theme.accent, fontSize = fontSize),
            modifier = modifier.rpgWindow(theme, 7.dp).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        UiStyle.Eki -> BasicText(
            text.uppercase(),
            maxLines = 1,
            style = TitleStyle(color = Color.White, fontSize = fontSize),
            modifier = modifier.clip(RoundedCornerShape(4.dp)).background(theme.ekiLine(2)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        UiStyle.Konbini -> BasicText(
            "*${text.uppercase()}*",
            maxLines = 1,
            style = NumberStyle(color = theme.background, fontSize = fontSize, bold = true),
            modifier = modifier.background(theme.foreground).padding(horizontal = 5.dp, vertical = 1.dp),
        )
        UiStyle.Stamp -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .rotate(-9f)
                .clip(RoundedCornerShape(50))
                .background(theme.background.copy(alpha = 0.9f))
                .border(2.dp, theme.pencil, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .semantics { contentDescription = text },
        ) {
            BasicText("新", style = androidx.compose.ui.text.TextStyle(color = theme.pencil, fontSize = fontSize * 1.5f, fontFamily = YujiSyuku))
            Spacer(Modifier.width(4.dp))
            BasicText(text, maxLines = 1, style = TextStyle(color = theme.pencil, fontSize = fontSize, fontWeight = FontWeight.Black))
        }
        UiStyle.Glass, UiStyle.Modern, UiStyle.Tankobon -> BasicText(
            text,
            maxLines = 1,
            style = TextStyle(color = Color.White, fontSize = fontSize, fontWeight = FontWeight.ExtraBold),
            modifier = modifier.clip(RoundedCornerShape(50)).background(Color(0xFF14141C).copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Something still loading, in the looks other than Tankōbon and Modern. */
@Composable
internal fun StyledLoading(modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val row = modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { contentDescription = "Loading" }
    when (Look.style) {
        UiStyle.Jrpg -> Box(row.fillMaxWidth().rpgWindow(theme).padding(horizontal = 14.dp, vertical = 12.dp)) {
            BasicText("Now loading…", style = NumberStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp))
        }
        UiStyle.Konbini -> Column(row.fillMaxWidth().background(theme.paper).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.fillMaxWidth(0.9f).height(8.dp).background(theme.paperInk.copy(alpha = 0.2f)))
            Box(Modifier.fillMaxWidth(0.7f).height(8.dp).background(theme.paperInk.copy(alpha = 0.2f)))
            BasicText("PRINTING...", style = NumberStyle(color = theme.paperInk.copy(alpha = 0.7f), fontSize = 12.sp))
        }
        else -> Row(row, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Box(
                    Modifier
                        .width(RowCardWidth)
                        .aspectRatio(if (Look.style == UiStyle.Shinya) 1f else 0.7f)
                        .clip(coverShape())
                        .background(theme.surface)
                        .then(
                            when (Look.style) {
                                UiStyle.Shinya -> Modifier.drawBehind {
                                    val bars = listOf(0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000, 0xFFC000C0, 0xFFC00000, 0xFF0000C0)
                                    val w = size.width / bars.size
                                    bars.forEachIndexed { i, c ->
                                        drawRect(Color(c).copy(alpha = 0.3f), Offset(i * w, 0f), androidx.compose.ui.geometry.Size(w + 1f, size.height))
                                    }
                                }
                                UiStyle.Genga -> Modifier.border(1.dp, theme.hairline).drawBehind {
                                    val line = theme.primary.copy(alpha = 0.45f)
                                    drawLine(line, Offset.Zero, Offset(size.width, size.height), 1.dp.toPx())
                                    drawLine(line, Offset(size.width, 0f), Offset(0f, size.height), 1.dp.toPx())
                                }
                                UiStyle.Stamp -> Modifier.drawBehind {
                                    drawCircle(theme.pencil.copy(alpha = 0.4f), size.minDimension * 0.3f, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                                }
                                else -> Modifier.shimmer(theme.foreground.copy(alpha = 0.08f))
                            },
                        ),
                )
            }
        }
    }
}

/**
 * How far through something is, drawn the look's way: one cell per episode on Genga's timing sheet, stamp slots,
 * an HP bar, stops on a line, a plain bar elsewhere. With no known total, or more than [MAX_STEPS], it falls back to
 * a bar (or nothing, with no total).
 */
@Composable
internal fun ProgressSteps(done: Int, total: Int?, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val description = Modifier.semantics { contentDescription = if (total != null) "$done of $total" else "$done so far" }
    if (total == null || total <= 0) return
    val fraction = (done.toFloat() / total).coerceIn(0f, 1f)
    if (total > MAX_STEPS) {
        Box(modifier.then(description).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(theme.rule)) {
            Box(Modifier.fillMaxWidth(fillOnShow(fraction)).fillMaxHeight().background(theme.primary))
        }
        return
    }
    when (Look.style) {
        UiStyle.Genga -> Row(modifier.then(description).fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(total) { i ->
                Box(Modifier.weight(1f).fillMaxHeight().border(1.dp, theme.hairline).background(if (i < done) theme.primary else Color.Transparent))
            }
        }
        UiStyle.Stamp -> androidx.compose.foundation.layout.FlowRow(
            modifier = modifier.then(description),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(total) { i ->
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(17.dp).rotate(if (i < done) STAMP_TILT[i % STAMP_TILT.size] else 0f).drawBehind {
                        val r = size.minDimension / 2f - 1.dp.toPx()
                        when {
                            i < done -> drawCircle(theme.pencil, r, style = Stroke(2.dp.toPx()))
                            i == done -> drawCircle(theme.pencil, r, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                            else -> drawCircle(theme.rule, r, style = Stroke(1.5.dp.toPx()))
                        }
                    },
                ) {
                    if (i < done) BasicText("${i + 1}", style = TextStyle(color = theme.pencil, fontSize = 8.sp, fontWeight = FontWeight.Black))
                }
            }
        }
        UiStyle.Jrpg -> Row(modifier.then(description), verticalAlignment = Alignment.CenterVertically) {
            BasicText("HP", style = NumberStyle(color = theme.accent, fontSize = 12.sp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f).height(7.dp).clip(RoundedCornerShape(4.dp)).background(theme.background).border(1.dp, theme.foreground, RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxWidth(fillOnShow(fraction)).fillMaxHeight().background(theme.success))
            }
            Spacer(Modifier.width(6.dp))
            BasicText("$done/$total", style = NumberStyle(color = theme.foreground, fontSize = 12.sp))
        }
        UiStyle.Eki -> Box(modifier.then(description).fillMaxWidth().height(22.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2
                val r = 5.dp.toPx()
                val step = (size.width - 2 * r) / (total - 1).coerceAtLeast(1)
                drawLine(theme.rule, Offset(r, y), Offset(size.width - r, y), 5.dp.toPx())
                drawLine(theme.primary, Offset(r, y), Offset(r + step * (done - 1).coerceAtLeast(0), y), 5.dp.toPx())
                repeat(total) { i ->
                    val c = Offset(r + step * i, y)
                    when {
                        i < done -> { drawCircle(EkiPlate, r, c); drawCircle(theme.primary, r, c, style = Stroke(2.5.dp.toPx())) }
                        i == done -> { drawCircle(EkiGo, r + 2.dp.toPx(), c); drawCircle(EkiPlate, r + 2.dp.toPx(), c, style = Stroke(2.dp.toPx())) }
                        else -> drawCircle(theme.rule, r * 0.7f, c, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        else -> Box(modifier.then(description).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(theme.rule)) {
            Box(Modifier.fillMaxWidth(fillOnShow(fraction)).fillMaxHeight().background(theme.primary))
        }
    }
}

/** Above this many episodes or chapters, steps become a plain bar. */
internal const val MAX_STEPS = 26

internal val STAMP_TILT = listOf(-12f, 8f, -4f, 14f, -9f, 5f, -15f, 10f, -6f, 12f, -3f, 7f)

/**
 * When something airs, the way late-night TV listings print it: after midnight and before 5 the hour runs on from
 * the evening before (Saturday 01:30 is "FRI 25:30"). In the phone's own time zone.
 */
internal fun lateNightSlot(airingAt: Long): Pair<String, String> {
    val time = java.time.Instant.ofEpochSecond(airingAt).atZone(java.time.ZoneId.systemDefault())
    var day = time.toLocalDate()
    var hour = time.hour
    if (hour < 5) {
        hour += 24
        day = day.minusDays(1)
    }
    val name = day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).uppercase()
    return name to "%02d:%02d".format(hour, time.minute)
}

/** A flat line of dots running between a label and its value on a receipt. */
internal fun Modifier.leaders(color: Color): Modifier = drawBehind {
    drawLine(
        color,
        Offset(0f, size.height - 4.dp.toPx()),
        Offset(size.width, size.height - 4.dp.toPx()),
        2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
    )
}

/** Receipt paper with a torn zigzag along the top and bottom. */
internal val ReceiptShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val tooth = 30f
    val depth = 14f
    moveTo(0f, depth)
    var x = 0f
    while (x < size.width) {
        lineTo((x + tooth / 2).coerceAtMost(size.width), 0f)
        lineTo((x + tooth).coerceAtMost(size.width), depth)
        x += tooth
    }
    lineTo(size.width, size.height - depth)
    x = size.width
    while (x > 0f) {
        lineTo((x - tooth / 2).coerceAtLeast(0f), size.height)
        lineTo((x - tooth).coerceAtLeast(0f), size.height - depth)
        x -= tooth
    }
    close()
}

internal val Square = RectangleShape
