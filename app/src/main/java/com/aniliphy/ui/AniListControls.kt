package com.aniliphy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

internal data class CoverStyle(val corners: CoverCorners, val layout: CoverLayout)

internal val LocalCoverStyle = compositionLocalOf { CoverStyle(CoverCorners.Rounded, CoverLayout.Grid) }

@Composable
internal fun ProvideCoverStyle(appearance: AppearanceState, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCoverStyle provides CoverStyle(appearance.coverCorners, appearance.coverLayout),
        content = content,
    )
}

internal fun Context.showToast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

internal fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        showToast("No browser found")
    }
}

@Composable
internal fun Cover(
    url: String?,
    modifier: Modifier = Modifier,
    /** Whether this cover flies between the tapped card and the media page (Tankōbon). */
    shared: Boolean = false,
) {
    val shape = when {
        LocalCoverStyle.current.corners != CoverCorners.Rounded -> RectangleShape
        else -> coverShape()
    }
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.sharedCover(shared).clip(shape).background(LocalAppTheme.current.panel),
    )
}

@Composable
internal fun CoverCard(
    title: String,
    subtitle: String?,
    url: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = LocalCoverStyle.current.layout == CoverLayout.List,
    detail: String? = null,
    repeats: Int = 0,
    /** Holding the card, when it does something else than tapping it. */
    onLongClick: (() -> Unit)? = null,
    /** A short notice in the cover's corner, like "New volume". */
    badge: String? = null,
    /** Time until the next episode. Tankōbon prints it on the cover; Classic shows it as [detail]. */
    countdown: String? = null,
    /** Something under the text, like Genga's timing-sheet strip. */
    footer: (@Composable () -> Unit)? = null,
) {
    val theme = LocalAppTheme.current
    val titleStyle = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val subtitleStyle = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp)
    val detailStyle = NumberStyle(color = theme.primary, fontSize = 11.sp, bold = true)
    val card = remember { Any() }
    val flies = SharedCover.card === card
    val insidePages = LocalPageScope.current != null
    val tap = {
        SharedCover.take(card, url, acrossTabs = false)
        onClick()
    }
    // Holding a card on the Anime, Manga and Light Novel tabs opens it on AniList, so the cover flies there too.
    val hold = onLongClick?.let { open ->
        {
            SharedCover.take(card, url, acrossTabs = !insidePages)
            open()
        }
    }
    val shownDetail = if (Look.countdownOnCover || countdown == null) detail else listOfNotNull(detail, countdown).joinToString(" · ")
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .pressableClipped(lookShape(10.dp), tap, hold)
                .background(theme.surface.copy(alpha = 0.72f), lookShape(10.dp))
                .padding(8.dp),
        ) {
            Box(Modifier.width(56.dp).height(80.dp)) {
                Cover(url, Modifier.fillMaxSize(), shared = flies)
                if (repeats > 0) RepeatBadge(repeats, Modifier.align(Alignment.TopStart).padding(3.dp), 18.dp)
                badge?.let { NewBadge(it, Modifier.align(Alignment.TopEnd).padding(3.dp), fontSize = 8.sp) }
                if (Look.countdownOnCover && countdown != null) CoverCountdown(countdown, compact = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = titleStyle)
                if (!subtitle.isNullOrEmpty()) BasicText(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = subtitleStyle)
                if (!shownDetail.isNullOrEmpty()) BasicText(shownDetail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = detailStyle)
            }
        }
    } else {
        Column(modifier.pressableClipped(RoundedCornerShape(14.dp), tap, hold)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                Cover(url, Modifier.fillMaxSize(), shared = flies)
                if (repeats > 0) RepeatBadge(repeats, Modifier.align(Alignment.TopStart).padding(5.dp))
                badge?.let { NewBadge(it, Modifier.align(Alignment.TopEnd).padding(5.dp)) }
                if (Look.countdownOnCover && countdown != null) CoverCountdown(countdown, compact = false)
            }
            Spacer(Modifier.height(5.dp))
            BasicText(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = titleStyle)
            if (!subtitle.isNullOrEmpty()) BasicText(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = subtitleStyle)
            if (!shownDetail.isNullOrEmpty()) BasicText(shownDetail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = detailStyle)
            footer?.invoke()
        }
    }
}

/** A cover on the Anime and Manga tabs: where you are, how far through, and whether something new came out. */
@Composable
internal fun ProgressCard(
    title: String,
    cover: String?,
    place: String,
    percent: Int?,
    newLabel: String?,
    repeats: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val tankobon = Look.tankobon
    val card = remember { Any() }
    val hold = {
        SharedCover.take(card, cover, acrossTabs = true)
        onLongClick()
    }
    Column(Modifier.pressableClipped(RoundedCornerShape(14.dp), onClick, hold)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
            Cover(cover, Modifier.fillMaxSize(), shared = SharedCover.card === card)
            if (repeats > 0) RepeatBadge(repeats, Modifier.align(Alignment.TopStart).padding(5.dp))
            val badgeStart = tankobon && repeats == 0
            newLabel?.let { NewBadge(it, Modifier.align(if (badgeStart) Alignment.TopStart else Alignment.TopEnd).padding(if (badgeStart) 7.dp else 5.dp)) }
            if (percent != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(if (tankobon) 8.dp else 4.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    val fill = fillOnShow(percent / 100f)
                    Box(
                        Modifier
                            .fillMaxWidth(fill)
                            .fillMaxHeight()
                            .background(theme.primary)
                            .then(if (tankobon) Modifier.screentone(theme.background.copy(alpha = 0.55f), spacing = 4.dp, radius = 0.8.dp) else Modifier),
                    )
                }
            }
        }
        Spacer(Modifier.height(if (tankobon) 6.dp else 5.dp))
        BasicText(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = if (tankobon) theme.ink else theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        if (!Look.modern) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                BasicText(place, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp), modifier = Modifier.weight(1f))
                if (percent != null) BasicText("$percent%", maxLines = 1, style = NumberStyle(color = theme.primary, fontSize = 11.sp, bold = true))
            }
        } else {
            if (place.isNotEmpty()) {
                BasicText(place, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp))
            }
            if (percent != null) {
                BasicText("$percent% complete", maxLines = 1, style = TextStyle(color = theme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}

/** Tankōbon's countdown printed on a cover's lower edge, like a caption box. */
@Composable
internal fun CountdownTag(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 11.sp) {
    val theme = LocalAppTheme.current
    // Shrinks rather than cutting the hours off when the text size is Large.
    BasicText(
        text,
        maxLines = 1,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = fontSize),
        style = NumberStyle(color = theme.background, fontSize = fontSize, bold = true),
        modifier = modifier
            .clip(CutCornerShape(topEnd = 0.dp, bottomEnd = 6.dp))
            .background(theme.primary)
            .padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
    )
}

/**
 * The notice in a cover's corner, like "New chapter" on the manga tab: a tilted red label under Tankōbon, a slanted
 * sticker in the theme's accent under Modern.
 */
@Composable
internal fun NewBadge(text: String, modifier: Modifier = Modifier, fontSize: TextUnit = 10.sp) {
    val theme = LocalAppTheme.current
    if (Look.tankobon) {
        BasicText(
            text.uppercase(),
            style = NumberStyle(color = Color.White, fontSize = fontSize * 0.9f, bold = true),
            modifier = modifier
                .rotate(-4f)
                .background(theme.error)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    } else if (Look.modern) {
        BasicText(
            text.uppercase(),
            style = TitleStyle(color = theme.background, fontSize = fontSize * 0.95f),
            modifier = modifier
                .clip(SkewShape)
                .background(theme.accent)
                .padding(horizontal = 9.dp, vertical = 2.dp),
        )
    } else {
        StyledNewBadge(text, modifier, fontSize)
    }
}

@Composable
internal fun RepeatBadge(count: Int, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(start = size * 0.12f, end = size * 0.3f)
            .semantics { contentDescription = if (count == 1) "Repeated once" else "Repeated $count times" },
    ) {
        Canvas(Modifier.size(size * 0.72f)) {
            val stroke = this.size.minDimension * 0.14f
            val radius = this.size.minDimension / 2f - stroke * 1.4f
            val center = this.center
            drawArc(
                color = Color.White,
                startAngle = -60f,
                sweepAngle = 300f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round),
            )
            val angle = Math.toRadians(240.0)
            val out = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            val along = Offset(-out.y, out.x)
            val end = center + out * radius
            val head = stroke * 1.9f
            val arrow = androidx.compose.ui.graphics.Path().apply {
                moveTo((end + along * head).x, (end + along * head).y)
                lineTo((end + out * head).x, (end + out * head).y)
                lineTo((end - out * head).x, (end - out * head).y)
                close()
            }
            drawPath(arrow, Color.White)
        }
        Spacer(Modifier.width(size * 0.08f))
        BasicText(
            count.toString(),
            style = TextStyle(color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
internal fun <T> CoverRow(
    title: String,
    items: List<T>,
    key: (T) -> Any,
    state: LazyListState = rememberLazyListState(),
    number: Int? = null,
    card: @Composable (T) -> Unit,
) {
    SectionTitle(title, number)
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(items.distinctBy(key), key = { _, item -> key(item) }) { index, item ->
            Box(Modifier.width(RowCardWidth).enterOnce(title to key(item), index)) { card(item) }
        }
    }
}

internal val RowCardWidth: Dp = 112.dp

@Composable
internal fun MenuButton(onClick: () -> Unit) {
    val color = LocalAppTheme.current.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(buttonShape())
            .background(LocalAppTheme.current.surface)
            .clickable(role = Role.Button, onClickLabel = "Open sections", onClick = onClick),
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.minDimension / 24f
            listOf(6f, 12f, 18f).forEach { y ->
                drawLine(color, Offset(3.5f * u, y * u), Offset(20.5f * u, y * u), 2f * u, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun SearchField(text: String, onText: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    InputBox(text, onText, hint, modifier.height(44.dp), trailing = {
        if (text.isNotEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "Clear search") { onText("") },
            ) {
                Canvas(Modifier.size(14.dp)) {
                    val end = size.minDimension
                    drawLine(theme.foreground, Offset.Zero, Offset(end, end), 1.8.dp.toPx(), StrokeCap.Round)
                    drawLine(theme.foreground, Offset(end, 0f), Offset(0f, end), 1.8.dp.toPx(), StrokeCap.Round)
                }
            }
        }
    })
}

@Composable
internal fun InputBox(
    text: String,
    onText: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    background: Color = LocalAppTheme.current.surface,
    trailing: @Composable () -> Unit = {},
) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(lookShape(12.dp))
            .background(background)
            .padding(start = 14.dp, end = 4.dp),
    ) {
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                BasicText(hint, maxLines = 1, style = TextStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 14.sp))
            }
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = visualTransformation,
                textStyle = TextStyle(color = theme.foreground, fontSize = 14.sp),
                cursorBrush = SolidColor(theme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing()
    }
}

@Composable
internal fun PillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
    /** A number after the label, like "ep 13" on Watch, in the number face. */
    extra: String? = null,
    /** The button's colour; the theme's primary when not given. */
    color: Color = LocalAppTheme.current.primary,
) {
    val theme = LocalAppTheme.current
    val style = Look.style
    // Shin'ya puts white captions on a darkened primary, so they always read.
    val fill = if (style == UiStyle.Shinya && color == theme.primary) theme.deepPrimary else color
    val outlined = !filled && style in OUTLINED_BUTTONS
    val background = when {
        filled -> fill
        outlined -> theme.background
        style == UiStyle.Glass -> theme.glassPanel
        else -> androidx.compose.ui.graphics.lerp(theme.surface, color, 0.18f)
    }
    val text = when {
        filled && style == UiStyle.Shinya -> Color.White
        filled -> theme.background
        style == UiStyle.Glass -> theme.ink
        else -> color
    }
    val shape = if (Look.tankobon) lookShape(10.dp) else buttonShape()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (style == UiStyle.Jrpg && !filled) Modifier.rpgWindow(theme) else Modifier.clip(shape).background(background))
            .then(if (outlined) Modifier.border(1.5.dp, color, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        if (style == UiStyle.Jrpg && filled) {
            BasicText("▶", style = NumberStyle(color = text, fontSize = 12.sp))
            Spacer(Modifier.width(8.dp))
        }
        BasicText(
            if (style == UiStyle.Konbini) "[ ${label.uppercase()} ]" else label,
            maxLines = 1,
            style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        if (extra != null) {
            Spacer(Modifier.width(8.dp))
            BasicText(extra, maxLines = 1, style = NumberStyle(color = text.copy(alpha = 0.8f), fontSize = 12.sp))
        }
    }
}

/** Looks whose unfilled buttons are an outline on the page rather than a tinted fill. */
private val OUTLINED_BUTTONS = setOf(UiStyle.Genga, UiStyle.Bunko, UiStyle.Konbini)

@Composable
internal fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        Modifier
            .clip(lookShape(10.dp))
            .background(if (selected) theme.primary else theme.surface)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        BasicText(
            label,
            maxLines = 1,
            style = TextStyle(
                color = if (selected) theme.background else theme.foreground,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            ),
        )
    }
}

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier, color: Color = LocalAppTheme.current.foreground.copy(alpha = 0.7f)) {
    BasicText(text, style = TextStyle(color = color, fontSize = 14.sp), modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp))
}

/** Something still loading: ghost covers with a light sweeping across, in screentone under Tankōbon. */
@Composable
internal fun Loading(modifier: Modifier = Modifier) {
    if (!Look.tankobon && !Look.modern) {
        StyledLoading(modifier)
        return
    }
    val tankobon = Look.tankobon
    val theme = LocalAppTheme.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics { contentDescription = "Loading" },
    ) {
        repeat(3) {
            Column(Modifier.width(RowCardWidth)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(if (tankobon) CutCornerShape(bottomEnd = 14.dp) else RoundedCornerShape(14.dp))
                        .background(theme.surface)
                        .then(if (tankobon) Modifier.screentone(theme.rule) else Modifier)
                        .shimmer(theme.foreground.copy(alpha = 0.08f)),
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(10.dp).clip(RoundedCornerShape(if (tankobon) 0.dp else 5.dp)).background(theme.surface))
            }
        }
    }
}

@Composable
internal fun ErrorRow(message: String, onRetry: () -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        BasicText(message, style = TextStyle(color = theme.error, fontSize = 14.sp))
        Spacer(Modifier.height(8.dp))
        PillButton("Try again", onRetry, filled = false)
    }
}

@Composable
internal fun StaleNotice(stale: Boolean) {
    if (stale) {
        Hint("Can't reach AniList, so this is a saved copy", color = LocalAppTheme.current.warning)
    }
}

@Composable
internal fun LoadMoreEffect(state: LazyGridState, paged: PagedState<*>) {
    LaunchedEffect(state, paged) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= info.totalItemsCount - LOAD_AHEAD) to paged.items.size
        }
            .distinctUntilChanged()
            .filter { it.first }
            .collect { paged.loadMore() }
    }
}

@Composable
internal fun LoadMoreEffect(state: LazyListState, paged: PagedState<*>) {
    LaunchedEffect(state, paged) {
        snapshotFlow {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            (last >= info.totalItemsCount - LOAD_AHEAD) to paged.items.size
        }
            .distinctUntilChanged()
            .filter { it.first }
            .collect { paged.loadMore() }
    }
}

private const val LOAD_AHEAD = 6

/**
 * The first time a row's card shows it rises into place, a beat after the one before it. Cards scrolled back into
 * view don't animate again.
 */
private fun Modifier.enterOnce(key: Any, index: Int): Modifier = composed {
    if (key in EnteredCards) return@composed this
    if (index >= ENTER_LIMIT) {
        EnteredCards += key
        return@composed this
    }
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        kotlinx.coroutines.delay(Motion.ms(45 * minOf(index, 6)).toLong())
        progress.animateTo(1f, androidx.compose.animation.core.tween(Motion.ms(420), easing = lookEasing(androidx.compose.animation.core.FastOutSlowInEasing, Motion.ms(420))))
        EnteredCards += key
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 24.dp.toPx()
    }
}

/**
 * A card's tap and hold. The card isn't clipped: a cut or rounded corner would bite into the last line of text, and
 * the cover already carries the shape. [rounded] is kept for the ripple's outline.
 */
private fun Modifier.pressableClipped(rounded: androidx.compose.ui.graphics.Shape, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    pressable(onClick, onLongClick)

/** Cards that already rose into place this session, by row title and card. */
private val EnteredCards = mutableSetOf<Any>()

/** Only the cards on screen when a row first shows rise in; ones reached by scrolling just appear. */
private const val ENTER_LIMIT = 4
