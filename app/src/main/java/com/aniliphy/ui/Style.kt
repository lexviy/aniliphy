package com.aniliphy.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import com.aniliphy.R

/**
 * The look of the whole app, picked in Appearance. Tankōbon is printed manga (ink, screentone, cut corners); Modern
 * is soft and rounded (Outfit and Inter, pills, a floating tab bar). The rest each borrow one thing from the world
 * around anime: an animator's key-frame sheet, a paperback shelf, a late-night broadcast, an RPG menu, a station
 * board, a konbini receipt, a stamp-rally booklet and frosted glass. All of them move the same way.
 */
enum class UiStyle(val label: String, val blurb: String) {
    Tankobon("Tankōbon", "Ink, screentone, panel cuts"),
    Modern("Modern", "Rounded, soft, floating bar"),
    Genga("Genga", "An animator's key-frame sheet"),
    Bunko("Bunko", "Paperbacks with obi bands"),
    Shinya("Shin'ya", "A late-night TV broadcast"),
    Jrpg("JRPG", "Your list as a party menu"),
    Eki("Eki", "A station departure board"),
    Konbini("Konbini", "Printed on a receipt"),
    Stamp("Stamp rally", "A book of station stamps"),
    Glass("Glass", "Frosted glass lit by covers"),
}

/**
 * The style in use, kept outside composition so the plain [TextStyle] builder below can read it. It is snapshot
 * state, so text recomposes when it changes.
 */
internal object Look {
    var style by mutableStateOf(UiStyle.Tankobon)
    val tankobon: Boolean get() = style == UiStyle.Tankobon
    val modern: Boolean get() = style == UiStyle.Modern
}

internal val DelaGothic = FontFamily(Font(R.font.dela_gothic_one))

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.jetbrains_mono, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

internal val Outfit = FontFamily(variable(R.font.outfit, 500), variable(R.font.outfit, 600), variable(R.font.outfit, 700), variable(R.font.outfit, 800))

internal val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600), variable(R.font.inter, 700))

// The other looks' faces. They're cut down to Latin letters, so Japanese falls back to the system font.
internal val PlexCondensed = FontFamily(Font(R.font.ibm_plex_condensed_regular, FontWeight.Normal), Font(R.font.ibm_plex_condensed_bold, FontWeight.Bold))
internal val Kalam = FontFamily(Font(R.font.kalam_bold, FontWeight.Bold))
internal val Mincho = FontFamily(Font(R.font.shippori_mincho_medium, FontWeight.Medium), Font(R.font.shippori_mincho_extrabold, FontWeight.ExtraBold))
internal val MPlus = FontFamily(Font(R.font.mplus1p_medium, FontWeight.Medium), Font(R.font.mplus1p_bold, FontWeight.Bold), Font(R.font.mplus1p_black, FontWeight.Black))
internal val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
internal val MPlusRounded = FontFamily(Font(R.font.mplus_rounded_medium, FontWeight.Medium), Font(R.font.mplus_rounded_extrabold, FontWeight.ExtraBold))
internal val DotGothic = FontFamily(Font(R.font.dotgothic16, FontWeight.Normal))
internal val Barlow = FontFamily(Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium), Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold))
internal val SpaceMono = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))
internal val ZenMaru = FontFamily(Font(R.font.zen_maru_medium, FontWeight.Medium), Font(R.font.zen_maru_black, FontWeight.Black))
internal val YujiSyuku = FontFamily(Font(R.font.yuji_syuku, FontWeight.Normal))
internal val Manrope = FontFamily(variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700), variable(R.font.manrope, 800))

/** The face for running text in each look other than Tankōbon. */
private fun bodyFamily(style: UiStyle): FontFamily = when (style) {
    UiStyle.Tankobon -> DelaGothic
    UiStyle.Modern -> Inter
    UiStyle.Genga -> PlexCondensed
    UiStyle.Bunko -> Mincho
    UiStyle.Shinya -> MPlus
    UiStyle.Jrpg -> MPlusRounded
    UiStyle.Eki -> Barlow
    UiStyle.Konbini -> SpaceMono
    UiStyle.Stamp -> ZenMaru
    UiStyle.Glass -> Manrope
}

/** Dela Gothic One draws larger than the system font at the same size, so body text is scaled down to keep layouts. */
private const val DELA_SCALE = 0.9f

/**
 * Every text style in the app is built here, so the Tankōbon look can set the font in one place. Tankōbon sets
 * Dela Gothic One, which has a single weight: asking it for bold would only smear it, so the weight is dropped.
 * A style that names its own font (the novel reader's) keeps it.
 */
internal fun TextStyle(
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign = TextAlign.Unspecified,
): androidx.compose.ui.text.TextStyle {
    if (!Look.tankobon) {
        return androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            fontFamily = fontFamily ?: bodyFamily(Look.style),
            lineHeight = lineHeight,
            textAlign = textAlign,
        )
    }
    val dela = fontFamily == null
    return androidx.compose.ui.text.TextStyle(
        color = color,
        fontSize = if (dela && fontSize != TextUnit.Unspecified) fontSize * DELA_SCALE else fontSize,
        fontWeight = if (dela) null else fontWeight,
        fontFamily = fontFamily ?: if (dela) DelaGothic else null,
        fontSynthesis = if (dela) FontSynthesis.None else null,
        lineHeight = lineHeight,
        textAlign = textAlign,
    )
}

/** Headings, in each look's display face. */
internal fun TitleStyle(color: Color, fontSize: TextUnit, lineHeight: TextUnit = TextUnit.Unspecified): androidx.compose.ui.text.TextStyle {
    fun face(family: FontFamily, weight: FontWeight) =
        androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontFamily = family, fontWeight = weight, lineHeight = lineHeight)
    return when (Look.style) {
        UiStyle.Tankobon -> TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight)
        UiStyle.Modern -> face(Outfit, FontWeight.Bold)
        UiStyle.Genga -> face(PlexCondensed, FontWeight.Bold)
        UiStyle.Bunko -> face(Mincho, FontWeight.ExtraBold)
        UiStyle.Shinya -> face(MPlus, FontWeight.Black)
        UiStyle.Jrpg -> face(MPlusRounded, FontWeight.ExtraBold)
        UiStyle.Eki -> face(Barlow, FontWeight.Bold)
        UiStyle.Konbini -> face(SpaceMono, FontWeight.Bold)
        UiStyle.Stamp -> face(ZenMaru, FontWeight.Black)
        UiStyle.Glass -> face(Manrope, FontWeight.ExtraBold)
    }
}

/** Numbers (episodes, chapters, countdowns, percentages) in a monospaced face under Tankōbon. */
internal fun NumberStyle(
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    bold: Boolean = false,
): androidx.compose.ui.text.TextStyle =
    when (Look.style) {
        UiStyle.Tankobon, UiStyle.Genga, UiStyle.Eki -> androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = fontSize,
            fontFamily = JetBrainsMono,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
        )
        UiStyle.Shinya -> androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize * 1.08f, fontFamily = ShareTechMono)
        UiStyle.Jrpg -> androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontFamily = DotGothic)
        UiStyle.Konbini -> androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontFamily = SpaceMono, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        UiStyle.Bunko -> androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontFamily = Mincho, fontWeight = if (bold) FontWeight.ExtraBold else FontWeight.Medium)
        UiStyle.Stamp -> androidx.compose.ui.text.TextStyle(color = color, fontSize = fontSize, fontFamily = ZenMaru, fontWeight = if (bold) FontWeight.Black else FontWeight.Medium)
        UiStyle.Modern, UiStyle.Glass ->
            TextStyle(color = color, fontSize = fontSize, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium)
    }

/**
 * The corner every panel, field and chip takes: a manga panel's cut under Tankōbon, square paper under Genga, Bunko
 * and Konbini, and rounding of different softness elsewhere ([rounded] is the old, plain rounding).
 */
internal fun lookShape(rounded: Dp, cut: Dp = 8.dp): Shape = when (Look.style) {
    UiStyle.Tankobon -> CutCornerShape(bottomEnd = cut)
    UiStyle.Modern -> RoundedCornerShape(rounded + 4.dp)
    UiStyle.Genga, UiStyle.Konbini -> RectangleShape
    UiStyle.Bunko -> RoundedCornerShape(2.dp)
    UiStyle.Shinya -> RoundedCornerShape(rounded + 2.dp)
    UiStyle.Jrpg -> RoundedCornerShape(10.dp)
    UiStyle.Eki -> RoundedCornerShape(8.dp)
    UiStyle.Stamp -> RoundedCornerShape(rounded + 6.dp)
    UiStyle.Glass -> RoundedCornerShape(rounded + 10.dp)
}

/** A cover's corners in each look. */
internal fun coverShape(): Shape = when (Look.style) {
    UiStyle.Tankobon -> CutCornerShape(bottomEnd = 14.dp)
    UiStyle.Modern, UiStyle.Shinya -> RoundedCornerShape(14.dp)
    UiStyle.Genga, UiStyle.Konbini -> RectangleShape
    UiStyle.Bunko -> RoundedCornerShape(2.dp)
    UiStyle.Jrpg, UiStyle.Eki -> RoundedCornerShape(8.dp)
    UiStyle.Stamp -> RoundedCornerShape(12.dp)
    UiStyle.Glass -> RoundedCornerShape(18.dp)
}

/** Buttons and the round controls (menu, back). */
internal fun buttonShape(): Shape = when (Look.style) {
    UiStyle.Tankobon -> CutCornerShape(bottomEnd = 8.dp)
    UiStyle.Modern, UiStyle.Stamp, UiStyle.Glass -> CircleShape
    UiStyle.Genga, UiStyle.Konbini -> RectangleShape
    UiStyle.Bunko -> RoundedCornerShape(2.dp)
    UiStyle.Shinya -> RoundedCornerShape(14.dp)
    UiStyle.Jrpg -> RoundedCornerShape(10.dp)
    UiStyle.Eki -> RoundedCornerShape(10.dp)
}

/** Slants a box like the edge of a manga panel, or a sticker. */
internal val SkewShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val lean = size.height * 0.21f
    moveTo(lean, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width - lean, size.height)
    lineTo(0f, size.height)
    close()
}

/** Text drawn on top of the theme's surfaces, stronger than the foreground. */
internal val AppTheme.ink: Color
    get() = androidx.compose.ui.graphics.lerp(foreground, if (dark) Color.White else Color.Black, 0.45f)

/** Thin lines and empty tracks. */
internal val AppTheme.rule: Color
    get() = foreground.copy(alpha = if (dark) 0.16f else 0.14f)

/** Screentone: a grid of dots in [color], like the printed shading in manga. */
internal fun Modifier.screentone(color: Color, spacing: Dp = 6.dp, radius: Dp = 1.1.dp): Modifier =
    drawWithCache {
        val step = spacing.toPx().coerceAtLeast(2f)
        val tile = ImageBitmap(step.toInt().coerceAtLeast(2), step.toInt().coerceAtLeast(2))
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, GraphicsCanvas(tile), Size(tile.width.toFloat(), tile.height.toFloat())) {
            drawCircle(color, radius.toPx(), Offset(tile.width / 2f, tile.height / 2f))
        }
        val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind { drawRect(brush) }
    }

/** A light band sweeping across, for things still loading. */
internal fun Modifier.shimmer(color: Color): Modifier = composed {
    val sweep by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )
    drawBehind {
        val width = size.width
        val x = sweep * width
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, color, Color.Transparent),
                start = Offset(x - width * 0.4f, 0f),
                end = Offset(x + width * 0.4f, size.height * 0.3f),
            ),
        )
    }
}

/** "#rrggbb" from AniList, or null. */
internal fun parseHexColor(hex: String?): Color? {
    val digits = hex?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    return digits.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/**
 * The theme with its primary colour pulled toward [cover]: blended, not replaced, so the page still belongs to the
 * theme. Kept bright enough on dark themes and dark enough on light ones to read as text.
 */
internal fun AppTheme.tintedBy(cover: Color?): AppTheme {
    if (cover == null) return this
    var tint = if (dark) androidx.compose.ui.graphics.lerp(primary, cover, 0.6f) else androidx.compose.ui.graphics.lerp(cover, Color.Black, 0.55f)
    repeat(6) {
        val luminance = tint.luminance()
        tint = when {
            dark && luminance < 0.35f -> androidx.compose.ui.graphics.lerp(tint, Color.White, 0.25f)
            !dark && luminance > 0.18f -> androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.25f)
            else -> return copy(primary = tint)
        }
    }
    return copy(primary = tint)
}

/** How fast the interface moves, from Appearance › Animation speed. */
internal object Motion {
    var speed by androidx.compose.runtime.mutableFloatStateOf(1f)

    /** [millis] at Normal speed, scaled by the setting. */
    fun ms(millis: Int): Int = (millis / speed).toInt().coerceAtLeast(1)
}

/**
 * The card that was just tapped. Only its cover takes part in the flight into the media page: the same cover can show
 * in several rows at once, and a shared element needs a single match on each side.
 */
internal object SharedCover {
    var card by mutableStateOf<Any?>(null)
    var url by mutableStateOf<String?>(null)

    /** The flight goes with the tab switch (a card held on another tab) rather than a page opening inside AniList. */
    var acrossTabs by mutableStateOf(false)

    fun take(card: Any, url: String?, acrossTabs: Boolean) {
        this.card = card
        this.url = url
        this.acrossTabs = acrossTabs
    }
}

internal val LocalSharedScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.SharedTransitionScope?> { null }
internal val LocalPageScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/** The tab switch's scope, for a cover that flies from one tab to another (holding a card opens it on AniList). */
internal val LocalTabScope = androidx.compose.runtime.compositionLocalOf<androidx.compose.animation.AnimatedVisibilityScope?> { null }

/** Takes part in the cover flight when [active]: the tapped card's cover, or the media page's header cover. */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
internal fun Modifier.sharedCover(active: Boolean): Modifier {
    val shared = LocalSharedScope.current
    // The transition that carries the flight, picked when the card was touched: a page opening inside the AniList
    // tab, or the switch from another tab.
    val page = if (SharedCover.acrossTabs) LocalTabScope.current else LocalPageScope.current ?: LocalTabScope.current
    if (!active || shared == null || page == null) return this
    return with(shared) {
        this@sharedCover.sharedElement(rememberSharedContentState(SHARED_COVER_KEY), page)
    }
}

private const val SHARED_COVER_KEY = "tapped-cover"

/**
 * Tap and hold for cards: the card sinks a little while pressed, and holding it gives a buzz.
 */
internal fun Modifier.pressable(onClick: () -> Unit, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "press",
    )
    val view = androidx.compose.ui.platform.LocalView.current
    val style = Look.style.takeIf { LookPrefs.moments }
    // Each look's card gives way differently under the finger: an ink panel pushed down, a page lifting, a flap
    // tipping, a stamp pressed in... The plain look just sinks a little.
    graphicsLayer {
        val p = press
        when (style) {
            UiStyle.Tankobon -> { translationX = 3.dp.toPx() * p; translationY = 3.dp.toPx() * p }
            UiStyle.Genga -> { translationX = if (p > 0.5f) 2.dp.toPx() else 0f; translationY = if (p > 0.5f) 2.dp.toPx() else 0f }
            UiStyle.Bunko -> { rotationY = -7f * p; cameraDistance = 14f * density; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            UiStyle.Shinya -> { alpha = 1f - 0.25f * p; scaleX = 1f - 0.03f * p; scaleY = 1f - 0.03f * p }
            UiStyle.Jrpg -> { translationY = 2.dp.toPx() * p }
            UiStyle.Eki -> { rotationX = 10f * p; cameraDistance = 14f * density }
            UiStyle.Konbini -> { translationY = 2.dp.toPx() * p; scaleY = 1f - 0.02f * p }
            UiStyle.Stamp -> { val k = 1f - 0.08f * p; scaleX = k; scaleY = k; rotationZ = -2f * p }
            UiStyle.Glass -> { val k = 1f - 0.03f * p; scaleX = k; scaleY = k }
            else -> { val k = 1f - 0.05f * p; scaleX = k; scaleY = k }
        }
    }
        .tapBurst(source)
        .combinedClickable(
            interactionSource = source,
            indication = LocalIndication.current,
            onClick = onClick,
            onLongClick = onLongClick?.let { hold ->
                {
                    LookHaptics.hold(view)
                    hold()
                }
            },
        )
}

/** Fills from empty to [target] the first time it shows. */
@Composable
internal fun fillOnShow(target: Float): Float {
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    val value by animateFloatAsState(
        targetValue = if (shown) target else 0f,
        animationSpec = tween(Motion.ms(700), easing = lookEasing(androidx.compose.animation.core.FastOutSlowInEasing, Motion.ms(700))),
        label = "fill",
    )
    return value
}
