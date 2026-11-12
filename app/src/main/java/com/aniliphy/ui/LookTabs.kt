package com.aniliphy.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/*
 * Moving between tabs: the swipe that switches them, and each look's own way of cutting from one tab to the next.
 *
 * Six looks cut with something drawn over the screen (the old tab is swapped for the new one while it is covered):
 * Tankōbon's ink panel, Shin'ya's CRT, the JRPG battle bars, Eki's flaps, Konbini's printer and Stamp's ink. The
 * other four move the screens themselves: Modern grows the new tab out of the bar's pill, Bunko turns the old one over
 * like a page, Genga leaves an onion-skin ghost and Glass frosts over and clears.
 */

/** The look settings that motion code reads outside composition. Mirrored from [AppearanceState] by AppRoot. */
internal object LookPrefs {
    var tabMotion by mutableStateOf(true)
    var moments by mutableStateOf(true)
    var haptics by mutableStateOf(true)
}

/** Screens deeper than a tab's root (a page, an open drawer, a settings page) keep the swipe for themselves. */
internal object TabSwipe {
    private val holds = mutableStateMapOf<Any, Boolean>()
    val held: Boolean get() = holds.isNotEmpty()

    fun hold(key: Any, on: Boolean) {
        if (on) holds[key] = true else holds.remove(key)
    }
}

/** While [active], swiping sideways doesn't switch tabs. */
@Composable
internal fun HoldTabSwipe(active: Boolean) {
    val key = remember { Any() }
    DisposableEffect(key, active) {
        TabSwipe.hold(key, active)
        onDispose { TabSwipe.hold(key, false) }
    }
}

/**
 * Swiping the screen sideways goes to the tab next to this one. Only a drag nothing underneath used counts, so rows
 * of covers, sliders and the drawer keep theirs; drags from the very edges are left to the system's back gesture.
 * The screen follows the finger a little, with resistance, and springs back.
 *
 * [canGo] says whether there is a tab that way (1 = the next one, -1 = the one before).
 */
internal fun Modifier.tabSwipe(enabled: Boolean, canGo: (Int) -> Boolean, onSwipe: (Int) -> Unit): Modifier = composed {
    if (!enabled) return@composed this
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val density = LocalDensity.current
    val edge = with(density) { 24.dp.toPx() }
    val flingSpeed = with(density) { 700.dp.toPx() }
    val go by rememberUpdatedState(canGo)
    val swipe by rememberUpdatedState(onSwipe)
    pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (TabSwipe.held || down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var dx = 0f
            var dy = 0f
            var owned = false
            var released = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.size > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    released = true
                    break
                }
                if (!owned && change.isConsumed) break
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.position - change.previousPosition
                dx += delta.x
                dy += delta.y
                if (!owned) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) break
                    if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.6f) owned = true else continue
                }
                change.consume()
                val way = if (dx < 0) 1 else -1
                val resist = if (go(way)) 0.32f else 0.1f
                scope.launch { pull.snapTo(dx * resist) }
            }
            if (!owned) return@awaitEachGesture
            val way = if (dx < 0) 1 else -1
            val speed = tracker.calculateVelocity().x
            val far = abs(dx) > size.width * 0.28f
            val flung = abs(speed) > flingSpeed && sign(speed) == sign(dx)
            if (released && (far || flung) && go(way)) {
                swipe(way)
                scope.launch { pull.animateTo(0f, tween(Motion.ms(160))) }
            } else {
                scope.launch { pull.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
            }
        }
    }.graphicsLayer { translationX = pull.value }
}

/** One switch from tab [from] to tab [to] (their places in the bar), the way the look in use moves it. */
internal class TabSwitch(
    val id: Int,
    val from: Int,
    val to: Int,
    val style: UiStyle,
    /** Where it starts, in the tabs' own coordinates: the tapped tab or the side swiped from. */
    val origin: Offset?,
    val animated: Boolean,
) {
    val direction: Int get() = if (to > from) 1 else -1
}

internal object TabMotion {
    /** The middle of each tab in the bar, in window coordinates, by place. */
    val centers = HashMap<Int, Offset>()

    /** Where the tab area sits in the window. */
    var areaTopLeft = Offset.Zero
    var areaSize = Size.Zero

    /** The origin of the next switch when it doesn't come from a tab (a swipe starts at the screen's side). */
    var nextOrigin: Offset? = null

    /** Stamp's thump shakes the screen sideways by this much (px). */
    var shake by mutableFloatStateOf(0f)

    private var flown: Any? = null
    private var count = 0

    fun begin(from: Int, to: Int): TabSwitch? {
        if (from == to) return null
        // A cover flying across from a held card is the transition this time; anything of the look's would hide it.
        val flying = SharedCover.acrossTabs && SharedCover.card != null && SharedCover.card !== flown
        flown = SharedCover.card
        val origin = (nextOrigin ?: centers[to])?.let { it - areaTopLeft }
        nextOrigin = null
        return TabSwitch(++count, from, to, Look.style, origin, animated = LookPrefs.tabMotion && !flying)
    }
}

/** Records where a tab sits in the bar, so the look's transition can start from it. */
internal fun Modifier.tabCenter(index: Int): Modifier =
    onGloballyPositioned { TabMotion.centers[index] = it.boundsInWindow().center }

/** Records where the tabs are drawn. */
internal fun Modifier.tabArea(): Modifier = onGloballyPositioned {
    val bounds = it.boundsInWindow()
    TabMotion.areaTopLeft = bounds.topLeft
    TabMotion.areaSize = bounds.size
}

/** How long each look's switch takes at Normal speed, and when (as a share of it) the old tab is swapped. */
private fun timing(style: UiStyle): Pair<Int, Float> = when (style) {
    UiStyle.Tankobon -> 460 to 0.5f
    UiStyle.Jrpg -> 540 to 0.5f
    UiStyle.Eki -> 560 to 0.5f
    UiStyle.Stamp -> 500 to 0.4f
    UiStyle.Konbini -> 520 to 0f
    UiStyle.Shinya -> 300 to 0.5f
    UiStyle.Modern -> 440 to 0f
    UiStyle.Bunko -> 560 to 0f
    UiStyle.Genga -> 580 to 0f
    UiStyle.Glass -> 460 to 0f
}

private val UiStyle.coversScreen: Boolean
    get() = this == UiStyle.Tankobon || this == UiStyle.Jrpg || this == UiStyle.Eki || this == UiStyle.Stamp ||
        this == UiStyle.Konbini || this == UiStyle.Shinya

/** The shared slide: the new tab comes in from the side it was tapped on, the old one drifts the other way. */
private fun plainTransform(direction: Int): ContentTransform {
    val slide = tween<androidx.compose.ui.unit.IntOffset>(Motion.ms(340), easing = FastOutSlowInEasing)
    return (slideInHorizontally(slide) { it / 6 * direction } + fadeIn(tween(Motion.ms(280)))) togetherWith
        (slideOutHorizontally(slide) { -it / 6 * direction } + fadeOut(tween(Motion.ms(200))))
}

private fun keep(duration: Int): ExitTransition = fadeOut(tween(Motion.ms(duration)), targetAlpha = 0.99f)

/** How the tab content itself changes over for [switch]. */
internal fun tabTransform(switch: TabSwitch?, direction: Int): ContentTransform {
    if (switch == null || !switch.animated) return plainTransform(direction)
    val (duration, cut) = timing(switch.style)
    return when (switch.style) {
        UiStyle.Shinya -> plainTransform(direction)
        UiStyle.Tankobon, UiStyle.Jrpg, UiStyle.Eki, UiStyle.Stamp, UiStyle.Konbini -> {
            // Swapped in one frame while the overlay covers the screen.
            val at = Motion.ms((duration * cut).toInt()).coerceAtLeast(0)
            fadeIn(tween(1, delayMillis = at)) togetherWith fadeOut(tween(1, delayMillis = at))
        }
        // The old tab is kept (all but invisibly faded) for as long as the look moves it; tabMotion does the rest.
        UiStyle.Bunko -> ContentTransform(EnterTransition.None, keep(duration), targetContentZIndex = -1f)
        UiStyle.Modern, UiStyle.Genga, UiStyle.Glass -> ContentTransform(EnterTransition.None, keep(duration))
    }
}

/**
 * The look's movement of one tab's screen, for the four looks that move the screens rather than cover them. Applied
 * inside the tab's AnimatedContent, so it runs as part of the switch and the old tab stays until it's done.
 */
@Composable
internal fun AnimatedVisibilityScope.tabMotion(switch: TabSwitch?): Modifier {
    val style = switch?.takeIf { it.animated && !it.style.coversScreen }?.style
    val duration = style?.let { timing(it).first } ?: 0
    val phase by transition.animateFloat(
        transitionSpec = {
            if (duration == 0) {
                snap()
            } else {
                val base = if (style == UiStyle.Bunko) androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.5f, 1f) else FastOutSlowInEasing
                tween(Motion.ms(duration), easing = lookEasing(base, Motion.ms(duration)))
            }
        },
        label = "tabMotion",
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 2f
        }
    }
    if (style == null || switch == null) return Modifier
    val entering = phase <= 1f
    val t = if (entering) phase else phase - 1f
    if (entering && t >= 1f) return Modifier
    val way = switch.direction
    return when (style) {
        UiStyle.Modern -> if (entering) {
            val origin = switch.origin
            Modifier
                .graphicsLayer { alpha = min(1f, t * 4f) }
                .drawWithContent {
                    val from = origin ?: Offset(size.width / 2, size.height)
                    val w0 = 72.dp.toPx()
                    val h0 = 48.dp.toPx()
                    val start = Rect(from.x - w0 / 2, min(from.y, size.height) - h0, from.x + w0 / 2, min(from.y, size.height))
                    val rect = Rect(
                        lerpF(start.left, 0f, t), lerpF(start.top, 0f, t),
                        lerpF(start.right, size.width, t), lerpF(start.bottom, size.height, t),
                    )
                    val radius = lerpF(32.dp.toPx(), 0f, t * t)
                    val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(radius))) }
                    clipPath(path) { this@drawWithContent.drawContent() }
                }
        } else {
            Modifier.graphicsLayer {
                val scale = 1f - 0.06f * t
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.7f * t
            }
        }
        UiStyle.Bunko -> if (entering) {
            Modifier.drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = 0.32f * (1f - t)))
            }
        } else {
            // The old page turns over on its spine: the left edge going forward, the right one going back.
            Modifier
                .graphicsLayer {
                    transformOrigin = TransformOrigin(if (way > 0) 0f else 1f, 0.5f)
                    rotationY = (if (way > 0) -1f else 1f) * 92f * t
                    cameraDistance = 14f * density
                    alpha = if (t > 0.92f) (1f - t) / 0.08f else 1f
                }
                .drawWithContent {
                    drawContent()
                    drawRect(Color.Black.copy(alpha = 0.45f * t))
                    // The fold's highlight along the spine.
                    val x = if (way > 0) 0f else size.width
                    drawRect(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            listOf(Color.White.copy(alpha = 0.18f * t), Color.Transparent),
                            startX = x,
                            endX = x + (if (way > 0) 1 else -1) * size.width * 0.2f,
                        ),
                    )
                }
        }
        UiStyle.Genga -> if (entering) {
            Modifier.graphicsLayer {
                translationX = (1f - t) * way * 56.dp.toPx()
                alpha = t
            }
        } else {
            // The drawing before stays on the light table as a faint ghost, then is lifted away.
            Modifier.graphicsLayer {
                translationX = -t * way * 18.dp.toPx()
                alpha = when {
                    t < 0.3f -> 1f - t / 0.3f * 0.72f
                    t < 0.85f -> 0.28f
                    else -> 0.28f * (1f - t) / 0.15f
                }
            }
        }
        UiStyle.Glass -> Modifier.graphicsLayer {
            val frost = if (entering) 1f - t else t
            alpha = if (entering) t else 1f - t
            val scale = if (entering) 1.03f - 0.03f * t else 1f + 0.02f * t
            scaleX = scale
            scaleY = scale
            val radius = 26.dp.toPx() * frost
            renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0.5f) {
                BlurEffect(radius, radius, TileMode.Decal)
            } else {
                null
            }
        }
        else -> Modifier
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Stamp's thump: once the ink is down the screen shakes a little. */
private suspend fun thump(dp: Float) {
    val shake = Animatable(0f)
    for (to in listOf(4f, -3f, 2f, -1f, 0f).map { it * dp }) {
        shake.animateTo(to, tween(Motion.ms(38), easing = LinearEasing)) { TabMotion.shake = value }
    }
}

/** What the look draws over the screen while the tabs swap, for the looks that cover it. */
@Composable
internal fun TabOverlay(switch: TabSwitch?) {
    if (switch == null || !switch.animated || !switch.style.coversScreen) return
    val (duration, cut) = timing(switch.style)
    val time = remember(switch.id) { Animatable(0f) }
    val density = LocalDensity.current
    LaunchedEffect(switch.id) {
        if (switch.style == UiStyle.Stamp) {
            launch {
                kotlinx.coroutines.delay(Motion.ms((duration * cut).toInt()).toLong())
                thump(density.density)
            }
        }
        time.animateTo(1f, tween(Motion.ms(duration), easing = LinearEasing))
    }
    val v = time.value
    if (v >= 1f) return
    val theme = LocalAppTheme.current
    val way = switch.direction
    Canvas(Modifier.fillMaxSize()) {
        when (switch.style) {
            UiStyle.Shinya -> crt(v)
            UiStyle.Tankobon -> inkPanel(v, way, theme.ink, theme.background)
            UiStyle.Jrpg -> battleBars(v)
            UiStyle.Eki -> flaps(v)
            UiStyle.Stamp -> ink(v, cut, switch.origin, theme.pencil)
            UiStyle.Konbini -> printing(v, theme.paper, theme.paperInk)
            else -> Unit
        }
    }
}

/** Shin'ya: an old set cutting over, the picture folding to a line and the line to a dot. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.crt(v: Float) {
    val line = 3.dp.toPx()
    val dark = Color.Black.copy(alpha = 0.72f)
    if (v < 0.5f) {
        val t = v / 0.5f
        val height = size.height * (1f - t) * (1f - t) + line
        val top = (size.height - height) / 2
        drawRect(dark, Offset.Zero, Size(size.width, top))
        drawRect(dark, Offset(0f, top + height), Size(size.width, size.height - top - height))
        drawRect(Color.White.copy(alpha = 0.15f + 0.8f * t), Offset(0f, top), Size(size.width, height))
    } else {
        val t = (v - 0.5f) / 0.5f
        val width = size.width * (1f - t) * (1f - t)
        drawRect(dark.copy(alpha = 0.72f * (1f - t)))
        drawRect(Color.White.copy(alpha = 0.95f * (1f - t)), Offset((size.width - width) / 2, (size.height - line) / 2), Size(width, line))
    }
}

/**
 * Tankōbon: an ink panel with a slanted edge sweeps across like the cut to the next panel, its edge lined with the
 * white gutter, and screentone in the ink.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.inkPanel(v: Float, way: Int, ink: Color, paper: Color) {
    val w = size.width
    val h = size.height
    val lean = w * 0.38f
    val ease = FastOutSlowInEasing
    // In the direction of travel the panel comes in from the far side; mirrored for going back.
    val covering = v < 0.5f
    val t = ease.transform(if (covering) v / 0.5f else (v - 0.5f) / 0.5f)
    val edge = lerpF(w, -lean, t)
    fun x(at: Float) = if (way > 0) at else w - at
    val path = Path().apply {
        if (covering) {
            moveTo(x(edge), 0f)
            lineTo(x(w + lean), 0f)
            lineTo(x(w + lean), h)
            lineTo(x(edge + lean), h)
        } else {
            moveTo(x(-lean), 0f)
            lineTo(x(edge), 0f)
            lineTo(x(edge + lean), h)
            lineTo(x(-lean), h)
        }
        close()
    }
    drawPath(path, ink)
    clipPath(path) {
        val step = 9.dp.toPx()
        val dot = 1.4.dp.toPx()
        var y = 0f
        var row = 0
        while (y < h) {
            var px = if (row % 2 == 0) 0f else step / 2
            while (px < w) {
                drawCircle(paper.copy(alpha = 0.16f), dot, Offset(px, y))
                px += step
            }
            y += step
            row++
        }
    }
    drawLine(paper, Offset(x(edge), 0f), Offset(x(edge + lean), h), 7.dp.toPx())
    drawLine(ink, Offset(x(edge) + (if (way > 0) -6 else 6).dp.toPx(), 0f), Offset(x(edge + lean) + (if (way > 0) -6 else 6).dp.toPx(), h), 2.dp.toPx())
}

/** JRPG: bars sweep in from both sides like the start of a random battle, then sweep out again. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.battleBars(v: Float) {
    val bars = 12
    val height = size.height / bars
    val navy = Color(0xFF0E1446)
    val edge = Color.White.copy(alpha = 0.85f)
    for (i in 0 until bars) {
        val lag = i * 0.012f
        val fromLeft = i % 2 == 0
        val covering = v < 0.5f
        val raw = if (covering) (v - lag) / (0.5f - 0.13f) else (v - 0.5f - lag) / (0.5f - 0.13f)
        val t = FastOutSlowInEasing.transform(raw.coerceIn(0f, 1f))
        val y = i * height
        val (left, right) = if (covering) {
            if (fromLeft) 0f to size.width * t else size.width * (1f - t) to size.width
        } else {
            if (fromLeft) size.width * t to size.width else 0f to size.width * (1f - t)
        }
        if (right <= left) continue
        drawRect(navy, Offset(left, y), Size(right - left, height + 1f))
        val tip = if (covering == fromLeft) right else left
        if (tip > 0f && tip < size.width) drawLine(edge, Offset(tip, y), Offset(tip, y + height), 2.dp.toPx())
    }
}

/** Eki: the screen turns over in flaps, top to bottom, like the departure board changing. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.flaps(v: Float) {
    val strips = 14
    val height = size.height / strips
    val board = Color(0xFF1A1C21)
    val shade = Color(0xFF0C0D10)
    val hinge = Color.Black
    for (i in 0 until strips) {
        val lag = i / (strips - 1f) * 0.2f
        val y = i * height
        if (v < 0.5f) {
            // The flap falls from the top of its slot until the slot is shut.
            val t = ((v - lag) / 0.3f).coerceIn(0f, 1f)
            if (t <= 0f) continue
            drawRect(board, Offset(0f, y), Size(size.width, height * t + 1f))
            drawRect(shade, Offset(0f, y + height * t - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
        } else {
            // Then it lifts away from the top, uncovering the new line.
            val t = ((v - 0.5f - lag) / 0.3f).coerceIn(0f, 1f)
            if (t >= 1f) continue
            drawRect(board, Offset(0f, y + height * t), Size(size.width, height * (1f - t) + 1f))
        }
        drawLine(hinge, Offset(0f, y + height / 2), Offset(size.width, y + height / 2), 1.dp.toPx())
    }
}

/** Stamp: ink spreads out from the tab like a stamp coming down, then lifts, leaving a ring behind for a moment. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.ink(v: Float, cut: Float, origin: Offset?, red: Color) {
    val from = origin ?: Offset(size.width / 2, size.height)
    val far = max(
        max(hypot(from.x, from.y), hypot(size.width - from.x, from.y)),
        max(hypot(from.x, size.height - from.y), hypot(size.width - from.x, size.height - from.y)),
    )
    if (v < cut) {
        val t = FastOutSlowInEasing.transform(v / cut)
        drawCircle(red, far * t, from)
        drawCircle(red.copy(alpha = 0.5f), far * t + 10.dp.toPx(), from, style = Stroke(4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 9f))))
    } else {
        val t = (v - cut) / (1f - cut)
        drawRect(red.copy(alpha = 0.9f * (1f - t) * (1f - t) * (1f - t)))
        val ring = min(size.width, size.height) * (0.28f + 0.1f * t)
        val center = Offset(size.width / 2, size.height * 0.42f)
        drawCircle(red.copy(alpha = 0.8f * (1f - t)), ring, center, style = Stroke(7.dp.toPx()))
        drawCircle(red.copy(alpha = 0.6f * (1f - t)), ring - 12.dp.toPx(), center, style = Stroke(2.dp.toPx()))
    }
}

/** Konbini: the new tab prints out top to bottom, a line at a time, the print head running along the bottom of it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.printing(v: Float, paper: Color, ink: Color) {
    val line = 18.dp.toPx()
    val printed = (floor(v * size.height / line) * line).coerceAtMost(size.height)
    drawRect(paper, Offset(0f, printed), Size(size.width, size.height - printed))
    drawLine(
        ink.copy(alpha = 0.55f),
        Offset(0f, printed),
        Offset(size.width, printed),
        2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
    )
}

/** A small buzz in the look's own rhythm. */
internal object LookHaptics {
    private fun kick(view: View, constant: Int) {
        view.performHapticFeedback(constant)
    }

    private fun repeat(view: View, constant: Int, times: Int, gapMs: Long) {
        for (i in 0 until times) view.postDelayed({ kick(view, constant) }, i * gapMs)
    }

    private val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY

    /** Switching tabs. */
    fun tab(view: View, style: UiStyle) {
        if (!LookPrefs.haptics) return
        when (style) {
            UiStyle.Tankobon -> kick(view, HapticFeedbackConstants.CONTEXT_CLICK)
            UiStyle.Modern -> kick(view, HapticFeedbackConstants.KEYBOARD_TAP)
            UiStyle.Genga -> repeat(view, HapticFeedbackConstants.CLOCK_TICK, 2, 83)
            UiStyle.Bunko -> view.postDelayed({ kick(view, HapticFeedbackConstants.CLOCK_TICK) }, Motion.ms(300).toLong())
            UiStyle.Shinya -> kick(view, HapticFeedbackConstants.VIRTUAL_KEY)
            UiStyle.Jrpg -> repeat(view, HapticFeedbackConstants.KEYBOARD_TAP, 2, 70)
            UiStyle.Eki -> repeat(view, HapticFeedbackConstants.CLOCK_TICK, 4, 45)
            UiStyle.Konbini -> repeat(view, HapticFeedbackConstants.TEXT_HANDLE_MOVE, 5, 55)
            UiStyle.Stamp -> view.postDelayed({ kick(view, HapticFeedbackConstants.LONG_PRESS) }, Motion.ms(210).toLong())
            UiStyle.Glass -> kick(view, confirm)
        }
    }

    /** An episode or chapter marked as seen. */
    fun moment(view: View, style: UiStyle) {
        if (!LookPrefs.haptics) return
        when (style) {
            UiStyle.Stamp, UiStyle.Tankobon -> kick(view, HapticFeedbackConstants.LONG_PRESS)
            UiStyle.Eki -> repeat(view, HapticFeedbackConstants.CLOCK_TICK, 6, 45)
            UiStyle.Konbini -> repeat(view, HapticFeedbackConstants.TEXT_HANDLE_MOVE, 6, 55)
            UiStyle.Jrpg -> repeat(view, HapticFeedbackConstants.KEYBOARD_TAP, 3, 90)
            UiStyle.Genga -> repeat(view, HapticFeedbackConstants.CLOCK_TICK, 3, 83)
            else -> kick(view, confirm)
        }
    }
}
