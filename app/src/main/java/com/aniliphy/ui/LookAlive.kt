package com.aniliphy.ui

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The interface's own small motion in each look, beyond the background scene: what the JRPG's blinking cursor does,
 * given to every look in its own way and put in the same kinds of places everywhere (the selected tab, section
 * headings, the main card of a block, stats, big buttons, badges, the profile card, today's activity bar, the
 * readers' bars, the search field).
 *
 * Kept cheap on purpose: one shared clock that only runs while something on screen uses it and the app is in front;
 * values are read while drawing, never in composition; what is drawn over a block sits on its own layer so the block
 * underneath isn't redrawn; and lists only animate their first or selected item. Appearance › Look animations turns
 * it all off (and so does the phone's "remove animations"), leaving everything in its still, final state.
 */

/** The kind of place being brought to life; each look moves each kind its own way. */
internal enum class Alive {
    /** The selected tab in the bottom bar. */
    Tab,

    /** A section or day heading. */
    Heading,

    /** The main card of a block: Up next, the airing block, the profile card. */
    Block,

    /** The stats under a media page's title: like a block, but over the page rather than a card of its own. */
    Stats,

    /** A small sticker: NEW, the countdown on a cover. */
    Badge,

    /** A big button: Watch or Read. */
    Button,

    /** Today's bar in the activity chart. */
    Bar,

    /** The little label in front of the search field. */
    Lead,
}

/** The shared clock: milliseconds of look time, scaled by the animation speed, ticking only while someone looks. */
internal object LookClock {
    val millis = mutableLongStateOf(0L)
    var users by mutableIntStateOf(0)

    /** Nothing touched for a while (with "Pause when idle" on): the clock rests until the next touch. */
    var idle by mutableStateOf(false)

    /** When the screen was last touched (uptime ms). */
    @Volatile var lastTouch = SystemClock.uptimeMillis()

    val seconds: Float get() = millis.longValue / 1000f

    /** Called for every touch on the app's window. */
    fun touched() {
        lastTouch = SystemClock.uptimeMillis()
        if (idle) idle = false
    }
}

/** How long without a touch before the interface's motion rests, when "Pause when idle" is on. */
private const val IDLE_AFTER_MS = 20_000L

/** Runs [LookClock] while the interface motion is on, something on screen uses it and the app is in front. */
@Composable
internal fun LookClockDriver() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    val run = LookPrefs.uiMotion && LookClock.users > 0 && !LookClock.idle && state.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(run) {
        if (!run) return@LaunchedEffect
        // Coming back to the front counts as a touch, so it doesn't fall straight asleep.
        LookClock.lastTouch = SystemClock.uptimeMillis()
        var last = -1L
        var total = LookClock.millis.longValue.toDouble()
        while (true) {
            withFrameMillis { now ->
                if (last >= 0) total += (now - last) * Motion.speed * LookPrefs.uiPace
                last = now
                LookClock.millis.longValue = total.toLong()
            }
            if (LookPrefs.uiIdlePause && SystemClock.uptimeMillis() - LookClock.lastTouch > IDLE_AFTER_MS) {
                LookClock.idle = true
            }
        }
    }
}

/** Whether the phone itself asks for less motion: animations removed in the accessibility or developer options. */
@Composable
internal fun rememberPhoneWantsStill(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state by lifecycle.currentStateFlow.collectAsState()
    // Read again each time the app comes back to the front, where these settings would have been changed.
    return remember(state.isAtLeast(Lifecycle.State.RESUMED)) { phoneWantsStill(context) }
}

private fun phoneWantsStill(context: Context): Boolean {
    // Battery saver doesn't stop it: the motion is small and only runs while someone is looking at the screen.
    val scale = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale == 0f
}

/** Takes part in the clock while on screen; false (and nothing ticks) when the interface motion is off. */
@Composable
internal fun usesClock(on: Boolean = LookPrefs.uiMotion): Boolean {
    if (on) {
        DisposableEffect(Unit) {
            LookClock.users++
            onDispose { LookClock.users-- }
        }
    }
    return on
}

/** Whether this kind of place moves, by the switches under Appearance › Look animations. */
private fun placeOn(kind: Alive): Boolean = when (kind) {
    Alive.Tab -> LookPrefs.uiTabs
    Alive.Heading -> LookPrefs.uiHeadings
    Alive.Block, Alive.Stats, Alive.Button, Alive.Bar -> LookPrefs.uiCards
    Alive.Badge, Alive.Lead -> LookPrefs.uiSmall
}

/**
 * Brings this element to life the look's way, for the [place] it is. With [active] false (a tab that isn't
 * selected, a row that isn't the first), the motion switched off, or that kind of place switched off, it stays
 * exactly as it is.
 */
internal fun Modifier.lookAlive(place: Alive, active: Boolean = true): Modifier = composed {
    if (!usesClock(active && LookPrefs.uiMotion && placeOn(place))) return@composed this
    val style = Look.style
    // Stats move like a block, except where a block's motion needs a card under it (Bunko's page corner).
    val kind = when {
        place != Alive.Stats -> place
        style == UiStyle.Bunko -> Alive.Heading
        else -> Alive.Block
    }
    val theme = LocalAppTheme.current
    // Everything but the selected tab starts at its own point, so a screen of them doesn't move in step.
    val offset = remember { if (kind == Alive.Tab) 0f else (Math.random() * 9.0).toFloat() }
    val time = { LookClock.seconds + offset }
    val move = transform(style, kind)
    // Glass: the light on a card also follows where the card is on screen, so it slides as you scroll.
    val glassScroll = style == UiStyle.Glass && kind == Alive.Block
    val windowY = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * androidx.compose.ui.platform.LocalDensity.current.density
    val paint: (DrawScope.(Float) -> Unit)? = if (glassScroll) {
        { t -> scrollGlare(t, windowY.floatValue / screen.coerceAtLeast(1f), theme) }
    } else {
        overlay(style, kind, theme)
    }
    if (move == null && paint == null) return@composed this
    val k = LookPrefs.uiIntensity
    // Always a layer of its own on the outside: what is drawn on top is recorded there, so a frame redoes only this
    // element and never the screen around it.
    var out = if (move != null) {
        this.graphicsLayer {
            move(time())
            if (k != 1f) {
                // Subtle or lively: the same motion, smaller or bigger.
                translationX *= k
                translationY *= k
                rotationZ *= k
                scaleX = 1f + (scaleX - 1f) * k
                scaleY = 1f + (scaleY - 1f) * k
                alpha = (1f - (1f - alpha) * k).coerceIn(0f, 1f)
            }
        }
    } else {
        this.graphicsLayer()
    }
    if (glassScroll) out = out.onGloballyPositioned { windowY.floatValue = it.positionInWindow().y }
    if (paint != null) {
        // And the content on a layer inside, so only the drawing on top is redone each frame, not the element.
        out = out.drawWithContent {
            drawContent()
            if (k < 1f) {
                // Subtle: what is drawn on top is drawn fainter.
                drawContext.canvas.saveLayer(
                    androidx.compose.ui.geometry.Rect(-size.width, -size.height, size.width * 2, size.height * 2),
                    androidx.compose.ui.graphics.Paint().apply { alpha = 0.55f },
                )
                paint(time())
                drawContext.canvas.restore()
            } else {
                paint(time())
            }
        }.graphicsLayer()
    }
    out
}

// ——— Moving the element itself ——————————————————————————————————————————————————————————————————————————————————

private fun transform(style: UiStyle, kind: Alive): (GraphicsLayerScope.(Float) -> Unit)? = when (style) {
    UiStyle.Tankobon -> when (kind) {
        Alive.Heading, Alive.Lead -> { t -> translationX = jolt(t, 4.5f) * 2.dp.toPx() }
        Alive.Badge -> { t -> val k = 1f + 0.12f * pop(t, 3f); scaleX = k; scaleY = k }
        else -> null
    }
    UiStyle.Modern -> when (kind) {
        Alive.Badge -> { t -> rotationZ = 5f * wiggle(t, 3.2f) }
        Alive.Tab -> { t -> val k = 1f + 0.025f * (0.5f + 0.5f * sin(t * 2.4f)); scaleX = k; scaleY = k }
        else -> null
    }
    UiStyle.Genga -> when (kind) {
        // Boil: the drawing is redrawn every two frames, never quite in the same place.
        Alive.Badge, Alive.Lead -> { t -> val f = twos(t); translationX = (noise(f, 1) - 0.5f) * 1.4f.dp.toPx(); translationY = (noise(f, 2) - 0.5f) * 1.4f.dp.toPx() }
        else -> null
    }
    UiStyle.Bunko -> when (kind) {
        Alive.Tab, Alive.Badge, Alive.Lead -> { t ->
            transformOrigin = TransformOrigin(0.5f, 0f)
            rotationZ = 3.5f * sin(t * 1.9f)
        }
        else -> null
    }
    UiStyle.Shinya -> when (kind) {
        Alive.Badge, Alive.Bar, Alive.Lead -> { t -> alpha = if (noise(floor(t * 12f), 3) > 0.9f) 0.72f else 1f }
        else -> null
    }
    UiStyle.Jrpg -> when (kind) {
        // Idle bob, in two positions like a sprite.
        Alive.Tab, Alive.Badge -> { t -> translationY = if ((t * 2.2f).toInt() % 2 == 0) 0f else -2.dp.toPx() }
        else -> null
    }
    UiStyle.Eki -> null
    UiStyle.Konbini -> null
    UiStyle.Stamp -> when (kind) {
        Alive.Tab, Alive.Bar -> { t -> val k = 1f + 0.04f * (0.5f + 0.5f * sin(t * 2.6f)); scaleX = k; scaleY = k }
        Alive.Badge, Alive.Lead -> { t -> rotationZ = 4f * wiggle(t, 3.6f) }
        else -> null
    }
    UiStyle.Glass -> null
}

// ——— Drawing over it ——————————————————————————————————————————————————————————————————————————————————————————————

private fun overlay(style: UiStyle, kind: Alive, theme: AppTheme): (DrawScope.(Float) -> Unit)? = when (style) {
    UiStyle.Tankobon -> when (kind) {
        Alive.Tab -> { t -> actionLines(t, theme.primary, ring = true) }
        Alive.Block, Alive.Button -> { t -> speedLines(t, theme.ink.copy(alpha = 0.22f)) }
        Alive.Heading -> { t -> impact(t, 4.5f, theme.primary) }
        Alive.Bar -> { t -> actionLines(t, theme.primary, ring = false) }
        else -> null
    }
    UiStyle.Modern -> when (kind) {
        Alive.Block, Alive.Button, Alive.Bar -> { t -> sweep(t, 3.6f, Color.White.copy(alpha = if (theme.dark) 0.12f else 0.35f), 0.18f) }
        Alive.Heading -> { t -> pulseUnderline(t, theme.primary) }
        Alive.Lead -> { t -> pulseDot(t, theme.primary, Offset(size.width, size.height * 0.2f)) }
        else -> null
    }
    UiStyle.Genga -> when (kind) {
        Alive.Tab -> { t -> boilRing(t, theme.pencil) }
        Alive.Heading -> { t -> boilUnderline(t, theme.pencil) }
        Alive.Block -> { t -> playhead(t, theme.pencil) }
        Alive.Button, Alive.Bar -> { t -> boilFrame(t, theme.pencil) }
        else -> null
    }
    UiStyle.Bunko -> when (kind) {
        Alive.Heading -> { t -> gildedRule(t, theme.accent) }
        Alive.Button, Alive.Bar -> { t -> sweep(t, 5.5f, theme.accent.copy(alpha = 0.28f), 0.12f) }
        Alive.Block -> { t -> pageCorner(t, theme) }
        else -> null
    }
    UiStyle.Shinya -> when (kind) {
        Alive.Tab -> { t -> pulseDot(t, Color(0xFFFF3B3B), Offset(size.width * 0.82f, size.height * 0.18f)) }
        Alive.Heading, Alive.Button -> { t -> rollingBand(t, 3.2f) }
        Alive.Block -> { t ->
            rollingBand(t, 4f)
            pulseDot(t, Color(0xFFFF3B3B), Offset(size.width - 12.dp.toPx(), 12.dp.toPx()))
        }
        else -> null
    }
    UiStyle.Jrpg -> when (kind) {
        // A glint running round the window's frame, like a menu waiting for its turn.
        Alive.Block -> { t -> frameGlint(t, Color.White) }
        // The button already has its ▶; it gets the sparkle of a full gauge instead.
        Alive.Button -> { t -> twinkle(t, Color.White) }
        Alive.Bar -> { t -> twinkle(t, Color.White) }
        else -> null
    }
    UiStyle.Eki -> when (kind) {
        Alive.Tab, Alive.Heading, Alive.Bar -> { t -> train(t, EkiFlap, kind == Alive.Heading) }
        Alive.Block, Alive.Stats, Alive.Button -> { t -> crossingLamps(t) }
        Alive.Badge, Alive.Lead -> { t -> lamp(t, EkiFlap) }
    }
    UiStyle.Konbini -> when (kind) {
        Alive.Tab, Alive.Block, Alive.Stats, Alive.Button, Alive.Bar, Alive.Badge -> { t -> scanLine(t, kind == Alive.Tab) }
        Alive.Heading -> { t -> openSign(t) }
        Alive.Lead -> null
    }
    UiStyle.Stamp -> when (kind) {
        Alive.Heading, Alive.Button -> { t -> sweep(t, 4.2f, Color.White.copy(alpha = 0.3f), 0.1f) }
        Alive.Block -> { t -> nextStamp(t, theme.pencil) }
        else -> null
    }
    UiStyle.Glass -> when (kind) {
        // Put on the glass pill itself, which already clips it round.
        Alive.Tab -> { t -> sweep(t, 3.5f, Color.White.copy(alpha = 0.4f), 0.3f) }
        else -> { t -> sweep(t, 4.5f, Color.White.copy(alpha = if (theme.dark) 0.16f else 0.4f), 0.16f) }
    }
}

// ——— Timing helpers ———————————————————————————————————————————————————————————————————————————————————————————————

/** Genga's frame count on twos. */
private fun twos(t: Float): Float = floor(t * 12f)

/** A repeatable number in 0..1 for frame [f]. */
private fun noise(f: Float, salt: Int): Float {
    val x = sin(f * 12.9898f + salt * 78.233f) * 43758.547f
    return x - floor(x)
}

/** A short shake once every [period] seconds: 0 most of the time. */
private fun jolt(t: Float, period: Float): Float {
    val p = t % period
    if (p > 0.28f) return 0f
    return sin(p * 70f) * (1f - p / 0.28f)
}

/** A quick pop once every [period] seconds, 0..1. */
private fun pop(t: Float, period: Float): Float {
    val p = t % period
    return if (p > 0.3f) 0f else sin(p / 0.3f * PI.toFloat())
}

/** A little wiggle once every [period] seconds, -1..1. */
private fun wiggle(t: Float, period: Float): Float {
    val p = t % period
    return if (p > 0.5f) 0f else sin(p * 38f) * (1f - p / 0.5f)
}

// ——— What each look draws —————————————————————————————————————————————————————————————————————————————————————————

/** A band of light crossing the element diagonally every [period] seconds. */
private fun DrawScope.sweep(t: Float, period: Float, color: Color, width: Float) {
    val p = (t % period) / 1.1f
    if (p > 1f) return
    val span = size.width + size.height
    val x = -size.height + p * (span + size.width * width)
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, color, Color.Transparent),
            start = Offset(x, size.height),
            end = Offset(x + size.width * width + size.height * 0.5f, 0f),
        ),
    )
}

/** Glass: a soft glare whose place follows the card's place on screen ([at] 0 = top, 1 = bottom), plus the sweep. */
private fun DrawScope.scrollGlare(t: Float, at: Float, theme: AppTheme) {
    val x = size.width * (1.15f - at.coerceIn(-0.2f, 1.2f) * 1.1f)
    val w = size.width * 0.22f
    drawRect(
        Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = if (theme.dark) 0.13f else 0.32f), Color.Transparent),
            start = Offset(x - w, size.height),
            end = Offset(x + w, 0f),
        ),
    )
    sweep(t, 6f, Color.White.copy(alpha = if (theme.dark) 0.1f else 0.25f), 0.12f)
}

/** Tankōbon: short action lines flickering round the tab, or along the top of a bar. */
private fun DrawScope.actionLines(t: Float, color: Color, ring: Boolean) {
    val f = floor(t * 10f)
    val c = Offset(size.width / 2, size.height / 2)
    val count = if (ring) 10 else 5
    for (i in 0 until count) {
        val n = noise(f, i)
        if (n < 0.35f) continue
        if (ring) {
            val a = i / count.toFloat() * 2 * PI.toFloat()
            val r0 = min(size.width, size.height) * 0.48f
            val r1 = r0 + (4 + 6 * n).dp.toPx()
            drawLine(color, c + Offset(cos(a) * r0, sin(a) * r0), c + Offset(cos(a) * r1, sin(a) * r1), 1.5.dp.toPx())
        } else {
            val x = size.width * (i + 0.5f) / count
            drawLine(color, Offset(x, 0f), Offset(x + 2.dp.toPx(), -(3 + 5 * n).dp.toPx()), 1.5.dp.toPx())
        }
    }
}

/** Tankōbon: speed lines streaming past the top corner of a card. */
private fun DrawScope.speedLines(t: Float, color: Color) {
    val lines = 7
    val h = min(size.height * 0.35f, 60.dp.toPx())
    for (i in 0 until lines) {
        val speed = 0.6f + noise(i.toFloat(), 4) * 0.8f
        val p = ((t * speed + noise(i.toFloat(), 5)) % 1f)
        val y = 6.dp.toPx() + h * i / lines
        val len = size.width * (0.12f + 0.12f * noise(i.toFloat(), 6))
        val x = size.width * (1.1f - p * 1.4f)
        drawLine(color, Offset(x, y), Offset(x + len, y), 1.2.dp.toPx())
    }
}

/** Tankōbon: three impact lines by the heading as it jolts. */
private fun DrawScope.impact(t: Float, period: Float, color: Color) {
    val p = t % period
    if (p > 0.35f) return
    val a = 1f - p / 0.35f
    val x = -4.dp.toPx()
    val cy = size.height / 2
    for (i in -1..1) {
        drawLine(color.copy(alpha = a), Offset(x, cy + i * 6.dp.toPx()), Offset(x - 8.dp.toPx(), cy + i * 9.dp.toPx()), 2.dp.toPx())
    }
}

/** Modern: a short underline under the heading, softly breathing. */
private fun DrawScope.pulseUnderline(t: Float, color: Color) {
    val a = 0.35f + 0.45f * (0.5f + 0.5f * sin(t * 2.2f))
    val w = 28.dp.toPx() * (0.85f + 0.15f * sin(t * 2.2f))
    drawRoundRect(color.copy(alpha = a), Offset(0f, size.height - 3.dp.toPx()), Size(w, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
}

/** A small lamp pulsing: Shin'ya's ON AIR, Modern's dot. */
private fun DrawScope.pulseDot(t: Float, color: Color, at: Offset) {
    val k = 0.5f + 0.5f * sin(t * 4f)
    drawCircle(color.copy(alpha = 0.25f * k), (6 + 3 * k).dp.toPx(), at)
    drawCircle(color.copy(alpha = 0.6f + 0.4f * k), 3.dp.toPx(), at)
}

/** Genga: the red pencil circle round the tab, redrawn on twos. */
private fun DrawScope.boilRing(t: Float, color: Color) {
    val f = twos(t)
    val c = Offset(size.width / 2 + (noise(f, 1) - 0.5f) * 2.dp.toPx(), size.height / 2 + (noise(f, 2) - 0.5f) * 2.dp.toPx())
    val rx = size.width * 0.42f + (noise(f, 3) - 0.5f) * 3.dp.toPx()
    val ry = size.height * 0.44f + (noise(f, 4) - 0.5f) * 3.dp.toPx()
    val path = Path()
    val steps = 28
    // Not quite closed, like a quick pencil loop.
    for (i in 0..steps + 3) {
        val a = i / steps.toFloat() * 2 * PI.toFloat() - 0.6f
        val wobble = 1f + (noise(f + i, 7) - 0.5f) * 0.06f
        val p = c + Offset(cos(a) * rx * wobble, sin(a) * ry * wobble)
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color.copy(alpha = 0.85f), style = Stroke(1.6.dp.toPx()))
}

/** Genga: a pencil underline that boils. */
private fun DrawScope.boilUnderline(t: Float, color: Color) {
    val f = twos(t)
    val y = size.height - 1.dp.toPx()
    val path = Path()
    val steps = 10
    val w = min(size.width, 120.dp.toPx())
    for (i in 0..steps) {
        val x = w * i / steps
        val p = Offset(x, y + (noise(f + i, 9) - 0.5f) * 2.5.dp.toPx())
        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }
    drawPath(path, color.copy(alpha = 0.8f), style = Stroke(1.6.dp.toPx()))
}

/** Genga: a frame drawn round the element, boiling. */
private fun DrawScope.boilFrame(t: Float, color: Color) {
    val f = twos(t)
    fun j(salt: Int) = (noise(f, salt) - 0.5f) * 2.dp.toPx()
    val path = Path().apply {
        moveTo(j(1), j(2))
        lineTo(size.width + j(3), j(4))
        lineTo(size.width + j(5), size.height + j(6))
        lineTo(j(7), size.height + j(8))
        close()
    }
    drawPath(path, color.copy(alpha = 0.6f), style = Stroke(1.2.dp.toPx()))
}

/** Genga: the playhead running across the sheet, a frame at a time. */
private fun DrawScope.playhead(t: Float, color: Color) {
    val period = 5f
    val p = floor((t % period) / period * 60f) / 60f
    val x = size.width * p
    drawLine(color.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
    val tri = Path().apply { moveTo(x - 4.dp.toPx(), 0f); lineTo(x + 4.dp.toPx(), 0f); lineTo(x, 6.dp.toPx()); close() }
    drawPath(tri, color.copy(alpha = 0.8f))
}

/** Bunko: the corner of the page lifting a little, now and then. */
private fun DrawScope.pageCorner(t: Float, theme: AppTheme) {
    val p = (t % 6f) / 1.6f
    if (p > 1f) return
    val k = sin(p * PI.toFloat())
    val s = 18.dp.toPx() * k
    if (s < 1f) return
    val corner = Offset(size.width, 0f)
    val fold = Path().apply { moveTo(corner.x - s, 0f); lineTo(corner.x, s); lineTo(corner.x - s, s); close() }
    drawPath(Path().apply { moveTo(corner.x - s, 0f); lineTo(corner.x, 0f); lineTo(corner.x, s); close() }, theme.background)
    drawPath(fold, theme.paper)
    drawPath(fold, Color.Black.copy(alpha = 0.18f), style = Stroke(1.dp.toPx()))
}

/** Bunko: a glint running along the gilt line under the heading, like the light catching a book's edge. */
private fun DrawScope.gildedRule(t: Float, color: Color) {
    val p = (t % 5.5f) / 1.4f
    if (p > 1f) return
    val y = size.height - 1.dp.toPx()
    val x = size.width * p
    val w = 60.dp.toPx()
    drawLine(
        Brush.horizontalGradient(listOf(Color.Transparent, color.copy(alpha = 0.9f), Color.Transparent), startX = x - w, endX = x + w),
        Offset(max(0f, x - w), y),
        Offset(min(size.width, x + w), y),
        1.5.dp.toPx(),
    )
}

/** Shin'ya: a lighter band rolling down the picture, like an old set's hum bar. */
private fun DrawScope.rollingBand(t: Float, period: Float) {
    val p = (t % period) / period
    val h = max(8.dp.toPx(), size.height * 0.25f)
    val y = -h + p * (size.height + h)
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent), startY = y, endY = y + h),
        Offset(0f, max(0f, y)),
        Size(size.width, min(h, size.height - max(0f, y)).coerceAtLeast(0f)),
    )
}

/** JRPG: a small glint running round the window's frame. */
private fun DrawScope.frameGlint(t: Float, color: Color) {
    val inset = 3.dp.toPx()
    val w = size.width - 2 * inset
    val h = size.height - 2 * inset
    val perimeter = 2 * (w + h)
    val d = ((t % 4f) / 4f) * perimeter
    val p = when {
        d < w -> Offset(inset + d, inset)
        d < w + h -> Offset(inset + w, inset + d - w)
        d < 2 * w + h -> Offset(inset + w - (d - w - h), inset + h)
        else -> Offset(inset, inset + h - (d - 2 * w - h))
    }
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.9f), Color.Transparent), center = p, radius = 9.dp.toPx()), 9.dp.toPx(), p)
    drawCircle(color, 1.8.dp.toPx(), p)
}

/** JRPG: a sparkle on top of the bar, like a full gauge. */
private fun DrawScope.twinkle(t: Float, color: Color) {
    val p = t % 2.2f
    if (p > 0.6f) return
    val k = sin(p / 0.6f * PI.toFloat())
    val c = Offset(size.width / 2, 0f)
    val r = 5.dp.toPx() * k
    drawLine(color.copy(alpha = k), c - Offset(r, 0f), c + Offset(r, 0f), 1.5.dp.toPx())
    drawLine(color.copy(alpha = k), c - Offset(0f, r), c + Offset(0f, r), 1.5.dp.toPx())
}

/** Eki: a train running along the line under the element. */
private fun DrawScope.train(t: Float, color: Color, underline: Boolean) {
    val y = size.height + (if (underline) 2 else 3).dp.toPx()
    val w = if (underline) min(size.width, 160.dp.toPx()) else size.width
    drawLine(color.copy(alpha = 0.3f), Offset(0f, y), Offset(w, y), 2.dp.toPx())
    val p = (t % 3f) / 3f
    val x = w * p
    drawRoundRect(color, Offset(x - 5.dp.toPx(), y - 2.dp.toPx()), Size(10.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
}

/** Eki: two lamps by the corner taking turns, like a level crossing. */
private fun DrawScope.crossingLamps(t: Float) {
    val on = (t * 1.6f).toInt() % 2
    val y = 10.dp.toPx()
    for (i in 0..1) {
        val c = Offset(size.width - (22 - i * 11).dp.toPx(), y)
        drawCircle(Color.Black.copy(alpha = 0.5f), 4.5.dp.toPx(), c)
        if (i == on) {
            drawCircle(Color(0xFFFF4A3D).copy(alpha = 0.3f), 7.dp.toPx(), c)
            drawCircle(Color(0xFFFF4A3D), 3.5.dp.toPx(), c)
        }
    }
}

/** Eki: one lamp blinking. */
private fun DrawScope.lamp(t: Float, color: Color) {
    if ((t * 1.4f).toInt() % 2 == 1) return
    drawCircle(color, 2.5.dp.toPx(), Offset(size.width + 3.dp.toPx(), 3.dp.toPx()))
}

/** Konbini: the scanner's red line passing over, top to bottom (or across a tab). */
private fun DrawScope.scanLine(t: Float, small: Boolean) {
    val period = if (small) 2.4f else 3.2f
    val p = (t % period) / 1.1f
    if (p > 1f) return
    val y = size.height * p
    val red = Color(0xFFFF2D2D)
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, red.copy(alpha = 0.18f)), startY = y - 10.dp.toPx(), endY = y), Offset(0f, max(0f, y - 10.dp.toPx())), Size(size.width, min(y, 10.dp.toPx())))
    drawLine(red.copy(alpha = 0.9f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
}

/** Konbini: a little OPEN sign by the heading, its light flickering on and off. */
private fun DrawScope.openSign(t: Float) {
    val f = floor(t * 3f)
    val lit = noise(f, 11) > 0.15f
    val c = Offset(size.width - 6.dp.toPx(), size.height / 2)
    val green = Color(0xFF2FD36B)
    if (lit) drawCircle(green.copy(alpha = 0.3f), 7.dp.toPx(), c)
    drawCircle(if (lit) green else green.copy(alpha = 0.25f), 3.5.dp.toPx(), c)
}

/** Stamp: the next stamp to collect, a dashed ring in the corner, wobbling as if waiting for ink. */
private fun DrawScope.nextStamp(t: Float, color: Color) {
    val c = Offset(size.width - 22.dp.toPx(), 22.dp.toPx())
    val r = 14.dp.toPx()
    rotate(6f * sin(t * 2f), c) {
        drawCircle(color.copy(alpha = 0.55f), r, c, style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
        drawCircle(color.copy(alpha = 0.25f + 0.2f * abs(sin(t * 1.5f))), r - 5.dp.toPx(), c)
    }
}
