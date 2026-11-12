package com.aniliphy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max

/*
 * Each look's own bit of motion, kept to its own parts so the page transitions (and predictive back) stay shared:
 * Genga moves on twos, Eki's flaps turn over, the JRPG cursor blinks,
 * Konbini's receipts print, and Stamp's stamps come down. All of it follows the animation speed setting.
 */

/** Twelve drawings a second: an animator's "on twos". */
private const val TWOS_MS = 83

/** [base] as the look moves: Genga holds each drawing for two frames, every other look is smooth. */
internal fun lookEasing(base: Easing, durationMs: Int): Easing {
    if (Look.style != UiStyle.Genga) return base
    val steps = max(2, durationMs / TWOS_MS)
    return Easing { f -> if (f >= 1f) 1f else floor(base.transform(f) * steps) / steps }
}

/** What each split-flap board last showed, so a board flips when its value changes and not on every scroll back. */
private val FlapMemory = mutableMapOf<String, String>()

private const val FLAP_ORDER = " 0123456789:.-/ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/** The characters the flaps show now: they turn over one place at a time from what was there to [text]. */
@Composable
internal fun flapping(text: String, key: String?): String {
    if (key == null) return text
    val start = remember(key) { FlapMemory[key] }
    var shown by remember(key) { mutableStateOf(start ?: text.map { ' ' }.joinToString("")) }
    LaunchedEffect(key, text) {
        if (FlapMemory[key] == text && shown == text) return@LaunchedEffect
        FlapMemory[key] = text
        val from = shown.padEnd(text.length).take(text.length)
        val current = from.toCharArray()
        val target = text.toCharArray()
        // Long journeys round the drum are cut short: at most ten turns before the right character.
        for (i in current.indices) {
            val a = FLAP_ORDER.indexOf(current[i])
            val b = FLAP_ORDER.indexOf(target[i])
            if (a < 0 || b < 0) current[i] = target[i] else if (b - a > 10 || a > b) current[i] = FLAP_ORDER[max(0, b - 10)]
        }
        shown = String(current)
        while (!current.contentEquals(target)) {
            delay(Motion.ms(55).toLong())
            for (i in current.indices) {
                if (current[i] == target[i]) continue
                val a = FLAP_ORDER.indexOf(current[i])
                current[i] = if (a < 0) target[i] else FLAP_ORDER[(a + 1) % FLAP_ORDER.length]
            }
            shown = String(current)
        }
    }
    return if (shown.length == text.length) shown else text
}

/** JRPG: the ▶ cursor blinks, on and off like a text box waiting for a button. */
@Composable
internal fun BlinkingCursor(color: Color, fontSize: TextUnit, modifier: Modifier = Modifier, visible: Boolean = true) {
    if (!visible) {
        // Keeps the cursor's room in the row without running a blink nobody sees.
        BasicText("▶", style = NumberStyle(color = Color.Transparent, fontSize = fontSize), modifier = modifier)
        return
    }
    val blink = rememberInfiniteTransition(label = "cursor")
    val on by blink.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = Motion.ms(1000)
                1f at 0
                0f at Motion.ms(560)
                1f at Motion.ms(999)
            },
            RepeatMode.Restart,
            StartOffset(0),
        ),
        label = "cursor",
    )
    BasicText(
        "▶",
        style = NumberStyle(color = color.copy(alpha = if (on > 0.5f) 1f else 0f), fontSize = fontSize),
        modifier = modifier,
    )
}

/** Receipts already printed this session. */
private val Printed = mutableSetOf<String>()

/** Konbini: the first time a receipt shows it comes out of the printer, a line at a time from the top. */
internal fun Modifier.printOnce(key: String): Modifier = composed {
    if (Look.style != UiStyle.Konbini || key in Printed) return@composed this
    val printed = remember { Animatable(0f) }
    LaunchedEffect(key) {
        printed.animateTo(1f, tween(Motion.ms(650), easing = LinearEasing))
        Printed += key
    }
    drawWithContent {
        val line = 16.dp.toPx()
        val shown = (floor(printed.value * size.height / line) * line).coerceAtMost(size.height)
        clipRect(bottom = if (printed.value >= 1f) size.height else shown) { this@drawWithContent.drawContent() }
    }
}

/** Stamps already brought down this session. */
private val Stamped = mutableSetOf<String>()

/** Stamp rally: a stamp comes down onto the page the first time it shows, a beat after the one before it. */
internal fun Modifier.stampDown(key: String, index: Int = 0): Modifier = composed {
    if (key in Stamped) return@composed this
    val down = remember { Animatable(0f) }
    LaunchedEffect(key) {
        delay(Motion.ms(70 * minOf(index, 12)).toLong())
        down.animateTo(1f, tween(Motion.ms(240), easing = FastOutSlowInEasing))
        Stamped += key
    }
    graphicsLayer {
        val scale = 1.6f - 0.6f * down.value
        scaleX = scale
        scaleY = scale
        alpha = down.value
    }
}
