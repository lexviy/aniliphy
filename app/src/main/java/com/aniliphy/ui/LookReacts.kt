package com.aniliphy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * The looks' motion reacting to what is going on, rather than just decorating: news on a tab, an episode about to
 * air, a departure under ten minutes away, totals counting up, long titles running like a TV caption, and a small
 * burst where a card is tapped. All of it follows Appearance › Look animations; switched off, each shows its still,
 * final state (the news mark is still there, it just doesn't move).
 */

/** What is going on that the looks react to. Set by the screens that know it. */
internal object LookSignals {
    /** Manga with a chapter out past the one read. */
    var newChapters by mutableStateOf(false)

    /** Light novels with a volume not opened yet. */
    var newVolumes by mutableStateOf(false)

    /** When the next episode from the list airs (epoch seconds), once the home page has loaded the airing list. */
    var nextAiring by mutableStateOf<Long?>(null)

    /** Unread AniList notifications. */
    var unread by mutableStateOf(0)

    /** [onAir], checked again every minute (for the Anime tab's mark and Shin'ya's ON AIR). */
    var airingSoon by mutableStateOf(false)

    /** An episode from the list airs within the hour: Shin'ya is "on air". */
    fun onAir(now: Long = System.currentTimeMillis() / 1000): Boolean = nextAiring?.let { it - now in 0..3600 } == true

    /** Within ten minutes: Eki's board starts blinking. */
    fun departing(airingAt: Long?, now: Long = System.currentTimeMillis() / 1000): Boolean = airingAt != null && airingAt - now in 0..600
}

/** Plays the look's cue when something starts happening while the app is open (not when it's already so at start). */
@Composable
internal fun LookSignalCues() {
    val style = Look.style
    val chapters = LookSignals.newChapters
    val volumes = LookSignals.newVolumes
    val next = LookSignals.nextAiring
    val seen = remember { mutableStateOf<Triple<Boolean, Boolean, Boolean>?>(null) }
    LaunchedEffect(chapters, volumes, next) {
        // Checked again every minute, for an episode coming within the hour or ten minutes.
        while (true) {
            val now = System.currentTimeMillis() / 1000
            val soon = LookSignals.onAir(now)
            LookSignals.airingSoon = soon
            val state = Triple(chapters, volumes, soon)
            val before = seen.value
            if (before != null && ((chapters && !before.first) || (volumes && !before.second) || (soon && !before.third))) {
                LookSounds.cue(style)
            }
            seen.value = state
            kotlinx.coroutines.delay(60_000)
        }
    }
}

/**
 * A mark on a tab with news (Manga: new chapters; Light Novel: new volumes; Anime: an episode within the hour), in
 * the look: a "!" for the JRPG, a lamp for Eki, a red dot for Konbini, a ring for Stamp, a pencil circle for Genga,
 * an ON AIR light for Shin'ya, a ribbon tip for Bunko, a burst for Tankōbon and a glowing dot for Modern and Glass.
 * It moves while the look animations are on and sits still otherwise.
 */
internal fun Modifier.tabNews(has: Boolean): Modifier = composed {
    if (!has) return@composed this
    val moving = usesClock(LookPrefs.uiMotion && LookPrefs.uiSmall)
    val style = Look.style
    val theme = LocalAppTheme.current
    this.drawWithContent {
        drawContent()
        val t = if (moving) LookClock.seconds else 0f
        val at = Offset(size.width * 0.78f, size.height * 0.16f)
        newsMark(style, theme, t, at, moving)
    }
}

private fun DrawScope.newsMark(style: UiStyle, theme: AppTheme, t: Float, at: Offset, moving: Boolean) {
    val pulse = if (moving) 0.5f + 0.5f * sin(t * 4f) else 1f
    val red = Color(0xFFFF3B3B)
    when (style) {
        UiStyle.Jrpg -> {
            val bob = if (moving && (t * 2.2f).toInt() % 2 == 1) -2.dp.toPx() else 0f
            val w = 3.5.dp.toPx()
            drawRoundRect(theme.accent, Offset(at.x - w / 2, at.y - 8.dp.toPx() + bob), Size(w, 8.dp.toPx()), CornerRadius(1.dp.toPx()))
            drawCircle(theme.accent, w / 2 + 0.5f, Offset(at.x, at.y + 3.dp.toPx() + bob))
        }
        UiStyle.Eki -> {
            val on = !moving || (t * 1.6f).toInt() % 2 == 0
            drawCircle(Color.Black.copy(alpha = 0.6f), 4.5.dp.toPx(), at)
            if (on) {
                drawCircle(EkiFlap.copy(alpha = 0.35f), 7.dp.toPx(), at)
                drawCircle(EkiFlap, 3.5.dp.toPx(), at)
            }
        }
        UiStyle.Konbini -> drawCircle(red, (3 + pulse).dp.toPx(), at)
        UiStyle.Stamp -> drawCircle(theme.pencil, (4.5f + 1.5f * pulse).dp.toPx(), at, style = Stroke(1.6.dp.toPx()))
        UiStyle.Genga -> {
            val f = if (moving) floor(t * 12f) else 0f
            val j = (sin(f * 12.9f) * 0.8f).dp.toPx()
            drawCircle(theme.pencil, 5.dp.toPx() + j, at, style = Stroke(1.5.dp.toPx()))
        }
        UiStyle.Shinya -> {
            drawCircle(red.copy(alpha = 0.3f * pulse), (6 + 3 * pulse).dp.toPx(), at)
            drawCircle(red, 3.dp.toPx(), at)
        }
        UiStyle.Bunko -> {
            val sway = if (moving) sin(t * 1.9f) * 2.dp.toPx() else 0f
            val p = Path().apply {
                moveTo(at.x - 3.dp.toPx() + sway, at.y - 6.dp.toPx()); lineTo(at.x + 3.dp.toPx() + sway, at.y - 6.dp.toPx())
                lineTo(at.x + 3.dp.toPx() + sway, at.y + 5.dp.toPx()); lineTo(at.x + sway, at.y + 2.dp.toPx())
                lineTo(at.x - 3.dp.toPx() + sway, at.y + 5.dp.toPx()); close()
            }
            drawPath(p, theme.accent)
        }
        UiStyle.Tankobon -> {
            for (i in 0 until 6) {
                val a = i / 6f * 2 * PI.toFloat() + (if (moving) floor(t * 8f) * 0.3f else 0f)
                val r0 = 3.dp.toPx()
                val r1 = (5 + 2 * pulse).dp.toPx()
                drawLine(theme.primary, at + Offset(cos(a) * r0, sin(a) * r0), at + Offset(cos(a) * r1, sin(a) * r1), 1.5.dp.toPx())
            }
        }
        UiStyle.Modern, UiStyle.Glass -> {
            drawCircle(theme.primary.copy(alpha = 0.3f * pulse), (5 + 3 * pulse).dp.toPx(), at)
            drawCircle(theme.primary, 3.dp.toPx(), at)
        }
    }
}

/** Blinks while [active] (Eki's departure under ten minutes away); still and fully shown with the motion off. */
internal fun Modifier.lookBlink(active: Boolean): Modifier = composed {
    if (!usesClock(active && LookPrefs.uiMotion && LookPrefs.uiCards)) return@composed this
    graphicsLayer { alpha = if ((LookClock.seconds * 1.6f).toInt() % 2 == 0) 1f else 0.35f }
}

/** A long title running across like a TV caption (Shin'ya), while the look animations are on. */
internal fun Modifier.lookMarquee(): Modifier = composed {
    if (Look.style != UiStyle.Shinya || !LookPrefs.uiMotion || !LookPrefs.uiCards) return@composed this
    basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1800, repeatDelayMillis = 2600)
}

/** Clipped while [lookMarquee] runs (an ellipsis would scroll along with the title), an ellipsis otherwise. */
internal fun marqueeOverflow(): androidx.compose.ui.text.style.TextOverflow =
    if (Look.style == UiStyle.Shinya && LookPrefs.uiMotion && LookPrefs.uiCards) androidx.compose.ui.text.style.TextOverflow.Clip
    else androidx.compose.ui.text.style.TextOverflow.Ellipsis

/** Totals already counted up this session, so they don't count again on every return. */
private val Counted = mutableSetOf<String>()

/** [value], counting up from zero the first time it shows (Konbini's register display); just [value] when still. */
@Composable
internal fun countUp(value: Int, key: String): Int {
    if (!LookPrefs.uiMotion || !LookPrefs.uiCards || key in Counted) return value
    val shown = remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, value) {
        val steps = min(18, max(1, value))
        for (i in 1..steps) {
            shown.intValue = value * i / steps
            kotlinx.coroutines.delay(Motion.ms(40).toLong())
        }
        shown.intValue = value
        Counted += key
    }
    return shown.intValue
}

/**
 * A small burst where a card is tapped, the look's way: action lines, a ripple, a pencil circle, a glint, a glitch,
 * a sparkle, a flap, a scan, a stamp's ring or a ring of light. Nothing when the motion is off.
 */
internal fun Modifier.tapBurst(source: InteractionSource): Modifier = composed {
    if (!LookPrefs.uiMotion) return@composed this
    val burst = remember { Animatable(1f) }
    var at by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(source) {
        source.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                at = interaction.press.pressPosition
                scope.launch {
                    burst.snapTo(0f)
                    burst.animateTo(1f, tween(Motion.ms(460), easing = LinearEasing))
                }
            }
        }
    }
    val style = Look.style
    val theme = LocalAppTheme.current
    drawWithContent {
        drawContent()
        val p = burst.value
        if (p < 1f) burstMark(style, theme, p, at)
    }
}

private fun DrawScope.burstMark(style: UiStyle, theme: AppTheme, p: Float, at: Offset) {
    val fade = 1f - p
    val ease = FastOutSlowInEasing.transform(p)
    when (style) {
        UiStyle.Tankobon -> for (i in 0 until 10) {
            val a = i / 10f * 2 * PI.toFloat()
            val r0 = (8 + 26 * ease).dp.toPx()
            val r1 = r0 + 10.dp.toPx() * fade
            drawLine(theme.ink.copy(alpha = fade), at + Offset(cos(a) * r0, sin(a) * r0), at + Offset(cos(a) * r1, sin(a) * r1), 2.dp.toPx())
        }
        UiStyle.Modern -> drawCircle(theme.primary.copy(alpha = 0.25f * fade), (10 + 50 * ease).dp.toPx(), at)
        UiStyle.Genga -> {
            // The pencil circles the spot, a drawing at a time.
            val sweep = floor(p * 6f) / 6f * 360f
            drawArc(theme.pencil.copy(alpha = min(1f, fade * 2f)), -90f, sweep, false, at - Offset(18.dp.toPx(), 16.dp.toPx()), Size(36.dp.toPx(), 32.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
        UiStyle.Bunko -> {
            val r = 10.dp.toPx() * sin(p * PI.toFloat())
            drawLine(theme.accent, at - Offset(r, 0f), at + Offset(r, 0f), 1.5.dp.toPx())
            drawLine(theme.accent, at - Offset(0f, r), at + Offset(0f, r), 1.5.dp.toPx())
        }
        UiStyle.Shinya -> for (i in 0 until 4) {
            val y = at.y + (i - 1.5f) * 7.dp.toPx() + (sin(p * 40f + i) * 3).dp.toPx()
            drawRect(Color.White.copy(alpha = 0.35f * fade), Offset(0f, y), Size(size.width, 2.dp.toPx()))
        }
        UiStyle.Jrpg -> {
            val r = (6 + 14 * ease).dp.toPx()
            for (k in 0 until 4) {
                val a = k * PI.toFloat() / 2 + PI.toFloat() / 4
                drawCircle(Color.White.copy(alpha = fade), 2.dp.toPx(), at + Offset(cos(a) * r, sin(a) * r))
            }
        }
        UiStyle.Eki -> {
            // A flap closing over the row and opening again.
            val h = 22.dp.toPx() * sin(p * PI.toFloat())
            drawRect(EkiBoard.copy(alpha = 0.55f), Offset(0f, at.y - h / 2), Size(size.width, h))
            drawLine(Color.Black, Offset(0f, at.y), Offset(size.width, at.y), 1.dp.toPx())
        }
        UiStyle.Konbini -> {
            val y = size.height * p
            drawLine(Color(0xFFFF2D2D).copy(alpha = 0.85f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx())
        }
        UiStyle.Stamp -> {
            val r = (34 - 14 * ease).dp.toPx()
            drawCircle(theme.pencil.copy(alpha = 0.8f * fade), r, at, style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 3.dp.toPx()))))
        }
        UiStyle.Glass -> {
            drawCircle(Color.White.copy(alpha = 0.18f * fade), (12 + 44 * ease).dp.toPx(), at)
            drawCircle(Color.White.copy(alpha = 0.6f * fade), (12 + 44 * ease).dp.toPx(), at, style = Stroke(1.dp.toPx()))
        }
    }
}

/** A small sample of the look's motion, under the switch in Appearance: a tab, a heading, a card and a sticker. */
@Composable
internal fun LookAnimationPreview(modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(lookShape(14.dp))
            .background(theme.background)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(52.dp, 40.dp).lookAlive(Alive.Tab).tabNews(true),
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                    HouseIcon(this, IconPaint(theme.primary, cutout = theme.surface, filled = true))
                }
            }
            Spacer(Modifier.width(12.dp))
            BasicText(
                "Preview",
                style = TitleStyle(color = theme.ink, fontSize = 18.sp),
                modifier = Modifier.lookAlive(Alive.Heading).padding(bottom = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            StyledNewBadge("New", Modifier, 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(lookShape(12.dp))
                .background(theme.surface)
                .lookAlive(Alive.Block),
        ) {
            BasicText(
                "Up next · Ep 7",
                style = NumberStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 13.sp),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
            )
        }
    }
}

/** Keeps [LookSignals] up to date from the manga and novel states (the airing time comes from the home page). */
@Composable
internal fun LookSignalSources(manga: MangaUi, novels: NovelsUi) {
    LaunchedEffect(manga.historyVersion) {
        LookSignals.newChapters = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val records = manga.history.all()
                val latest = manga.newChapters.all()
                records.any { (id, record) -> record.title != null && com.aniliphy.manga.NewChapters.isNew(latest[id], record) }
            }.getOrDefault(false)
        }
    }
    LookSignals.newVolumes = novels.newVolumes.isNotEmpty()
    LookSignalCues()
}
