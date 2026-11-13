package com.aniliphy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How the tabs that don't fit on the bar are reached. */
enum class BarStyle(val label: String) {
    /** A button in the middle of the bar opens a panel with them. */
    Hub("Tap"),

    /** Holding the middle button opens an arc of them; slide to one and let go. */
    HoldHub("Hold"),

    /** Pulling the bar up shows them in a second row. */
    Pull("Pull up"),
}

/** The tabs pinned on the bar when nothing was chosen. */
internal val DefaultPinned = listOf(Tab.AniList, Tab.Anime, Tab.Manga, Tab.LightNovel)

/** Reads the pinned tabs saved in Appearance: two to four, in their usual order. */
internal fun pinnedTabs(saved: String): List<Tab> {
    val names = saved.split(',').map { it.trim() }.toSet()
    val tabs = Tab.entries.filter { it.name in names }
    return if (tabs.size in MIN_PINNED..MAX_PINNED) tabs else DefaultPinned
}

internal const val MIN_PINNED = 2
internal const val MAX_PINNED = 4

/** The extra tabs' menu (panel or arc) and where its pieces are on screen, shared by the bar and the overlay above it. */
internal class ExtrasMenu {
    var open by mutableStateOf(false)
    /** The finger, in root coordinates, while holding the middle button. */
    var finger by mutableStateOf<Offset?>(null)
    /** The middle button's centre, in root coordinates. */
    var hubCenter by mutableStateOf(Offset.Zero)
    val itemBounds = mutableStateMapOf<Tab, Rect>()

    /** The arc item under the finger, if any. */
    val hot: Tab?
        get() {
            val at = finger ?: return null
            return itemBounds.entries.firstOrNull { (_, r) -> r.inflate(14f).contains(at) }?.key
        }
}

private fun hubShape(style: UiStyle): Shape = when (style) {
    UiStyle.Tankobon, UiStyle.Stamp, UiStyle.Eki, UiStyle.Bunko -> CircleShape
    UiStyle.Genga, UiStyle.Konbini -> RectangleShape
    UiStyle.Modern -> RoundedCornerShape(18.dp)
    UiStyle.Shinya, UiStyle.Jrpg -> RoundedCornerShape(8.dp)
    UiStyle.Glass -> CircleShape
}

/** Looks whose bar is a closed box: the button stays inside it instead of rising above. */
private fun raised(style: UiStyle) = style != UiStyle.Modern && style != UiStyle.Glass && style != UiStyle.Jrpg

/** The middle slot of the bar, where the button goes. */
@Composable
internal fun RowScope.HubSlot(hub: @Composable () -> Unit) {
    Box(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) { hub() }
}

/**
 * The middle button. It shows a plus, or the extra tab you're on. [hold] decides what a press does: tap to open the
 * panel, or hold to open the arc (a quick tap then goes to the last extra used).
 */
@Composable
internal fun BarHub(
    menu: ExtrasMenu,
    selected: Tab,
    extras: List<Tab>,
    hold: Boolean,
    lastExtra: Tab,
    onSelect: (Tab) -> Unit,
) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val view = LocalView.current
    val shape = hubShape(style)
    val onExtra = selected in extras
    val news = extras.any { it != selected && hasNews(it) }
    val turn by animateFloatAsState(if (menu.open && !onExtra) 45f else 0f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "hubTurn")
    var pressed by remember { mutableStateOf(false) }
    val squeeze by animateFloatAsState(if (pressed) 0.9f else 1f, label = "hubSqueeze")
    val fill = if (onExtra) theme.surface else if (style == UiStyle.Shinya) theme.deepPrimary else theme.primary
    val ink = if (onExtra) theme.primary else if (style == UiStyle.Shinya) Color.White else theme.background
    val hubSize = 52.dp
    val lift = if (raised(style)) (-16).dp else 0.dp
    val frame = when (style) {
        UiStyle.Tankobon -> Modifier.offset(3.dp, 3.dp)
        else -> Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .offset(y = lift)
            .size(hubSize)
            .onGloballyPositioned { menu.hubCenter = it.boundsInRoot().center }
            .semantics { contentDescription = "More tabs" },
    ) {
        // The look's shadow under the button.
        when (style) {
            UiStyle.Tankobon -> Box(frame.size(hubSize).clip(shape).background(theme.ink))
            UiStyle.Genga -> Box(Modifier.offset(4.dp, 4.dp).size(hubSize).background(theme.accent))
            UiStyle.Stamp -> Box(Modifier.offset(2.dp, 2.dp).size(hubSize).clip(shape).background(theme.pencil.copy(alpha = 0.4f)))
            else -> Unit
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(hubSize)
                .graphicsLayer { scaleX = squeeze; scaleY = squeeze }
                .then(if (style == UiStyle.Modern || style == UiStyle.Glass) Modifier.shadow(12.dp, shape, ambientColor = theme.primary, spotColor = theme.primary) else Modifier)
                .clip(shape)
                .background(fill)
                .then(
                    when (style) {
                        UiStyle.Tankobon -> Modifier.border(3.dp, theme.ink, shape)
                        UiStyle.Genga -> Modifier.border(2.dp, theme.ink, shape)
                        UiStyle.Jrpg -> Modifier.rpgWindow(theme, radius = 8.dp)
                        UiStyle.Eki -> Modifier.border(4.dp, EkiPlate, shape)
                        UiStyle.Konbini -> Modifier.border(2.dp, theme.foreground, shape)
                        UiStyle.Stamp -> Modifier.border(2.5.dp, theme.pencil, shape)
                        UiStyle.Bunko -> Modifier.border(1.dp, theme.hairline, shape)
                        else -> Modifier
                    },
                )
                .pointerInput(hold, extras, lastExtra, selected) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        pressed = true
                        val origin = menu.hubCenter - Offset(hubSize.toPx() / 2f, hubSize.toPx() / 2f)
                        if (!hold) {
                            val up = waitForUpOrCancellation()
                            pressed = false
                            if (up != null) {
                                LookHaptics.hold(view)
                                LookSounds.tap()
                                menu.open = !menu.open
                            }
                            return@awaitEachGesture
                        }
                        val longPress = withTimeoutOrNull(HOLD_MILLIS) { waitForUpOrCancellation() }
                        if (longPress != null) {
                            // A quick tap: straight to the last extra used.
                            pressed = false
                            LookSounds.tap()
                            onSelect(if (lastExtra in extras) lastExtra else extras.first())
                            return@awaitEachGesture
                        }
                        LookHaptics.hold(view)
                        menu.open = true
                        menu.finger = origin + down.position
                        var last: Tab? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: break
                            menu.finger = origin + change.position
                            val hot = menu.hot
                            if (hot != null && hot != last) LookHaptics.hold(view)
                            last = hot
                            if (!change.pressed) break
                        }
                        pressed = false
                        val chosen = menu.hot
                        menu.open = false
                        menu.finger = null
                        if (chosen != null) {
                            LookSounds.tap()
                            onSelect(chosen)
                        }
                    }
                },
        ) {
            if (onExtra) {
                Canvas(Modifier.size(24.dp)) { selected.icon(this, IconPaint(ink, cutout = fill, filled = true)) }
            } else {
                Canvas(Modifier.size(22.dp).graphicsLayer { rotationZ = turn }) {
                    val w = 2.4.dp.toPx()
                    drawLine(ink, Offset(size.width / 2f, 2f), Offset(size.width / 2f, size.height - 2f), w, StrokeCap.Round)
                    drawLine(ink, Offset(2f, size.height / 2f), Offset(size.width - 2f, size.height / 2f), w, StrokeCap.Round)
                }
            }
        }
        if (news && !menu.open) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(2.dp, (-2).dp)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(theme.error)
                    .border(2.dp, theme.surface, CircleShape),
            )
        }
    }
}

/** The look's frame for the panel and the arc's items. */
@Composable
private fun Modifier.extrasFrame(shape: Shape): Modifier {
    val theme = LocalAppTheme.current
    return when (Look.style) {
        UiStyle.Tankobon -> clip(shape).background(theme.surface).border(2.dp, theme.ink, shape)
        UiStyle.Glass -> glass(theme, shape)
        UiStyle.Genga -> background(theme.surface).border(1.dp, theme.hairline, RectangleShape)
        UiStyle.Jrpg -> rpgWindow(theme)
        UiStyle.Konbini -> background(theme.surface).border(1.dp, theme.foreground, RectangleShape)
        UiStyle.Stamp -> clip(shape).background(theme.surface).border(2.dp, theme.pencil, shape)
        UiStyle.Shinya -> clip(shape).background(Color.Black.copy(alpha = 0.9f)).border(1.dp, theme.primary, shape)
        UiStyle.Eki -> clip(shape).background(theme.surface).border(3.dp, theme.primary, shape)
        else -> shadow(16.dp, shape).clip(shape).background(theme.surface)
    }
}

private fun panelShape(style: UiStyle): Shape = when (style) {
    UiStyle.Genga, UiStyle.Konbini -> RectangleShape
    UiStyle.Tankobon -> RoundedCornerShape(22.dp)
    UiStyle.Bunko -> RoundedCornerShape(4.dp)
    UiStyle.Shinya -> RoundedCornerShape(6.dp)
    else -> RoundedCornerShape(24.dp)
}

/** One extra tab in the panel or arc: its icon and name, marked when it's the tab you're on. */
@Composable
private fun ExtraItem(tab: Tab, current: Boolean, hot: Boolean, modifier: Modifier, onClick: (() -> Unit)?) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val lit = current || hot
    val ink = when {
        hot -> if (style == UiStyle.Shinya) Color.White else theme.background
        current -> if (style == UiStyle.Glass || style == UiStyle.Eki) theme.ink else theme.primary
        style == UiStyle.Shinya -> Color.White
        else -> theme.foreground
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (hot) theme.primary else if (current) theme.primary.copy(alpha = 0.16f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(role = Role.Tab, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .semantics { contentDescription = tab.label },
    ) {
        Box(Modifier.tabNews(!current && hasNews(tab))) {
            Canvas(Modifier.size(24.dp)) { tab.icon(this, IconPaint(ink, cutout = theme.surface, filled = lit)) }
        }
        Spacer(Modifier.height(4.dp))
        val text = if (style == UiStyle.Genga || style == UiStyle.Konbini || style == UiStyle.Shinya) tab.label.uppercase() else tab.label
        BasicText(
            text,
            maxLines = 1,
            style = when (style) {
                UiStyle.Genga, UiStyle.Konbini, UiStyle.Shinya, UiStyle.Jrpg -> NumberStyle(color = ink, fontSize = 10.sp, bold = lit)
                UiStyle.Tankobon -> TitleStyle(color = ink, fontSize = 11.sp)
                else -> TextStyle(color = ink, fontSize = 11.sp, fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium)
            },
        )
    }
}

/**
 * Above the bar, the extra tabs: a panel in a grid (tap style) or an arc around the middle button (hold style).
 * Tapping outside the panel closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.ExtrasOverlay(menu: ExtrasMenu, style: BarStyle, extras: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    if (style == BarStyle.Pull) return
    AnimatedVisibility(visible = menu.open, enter = fadeIn(tween(Motion.ms(160))), exit = fadeOut(tween(Motion.ms(160)))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (style == BarStyle.HoldHub) 0.35f else 0.18f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu.open = false },
        )
    }
    if (style == BarStyle.Hub) {
        AnimatedVisibility(
            visible = menu.open,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 600f), initialScale = 0.9f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)) + fadeIn(),
            exit = scaleOut(targetScale = 0.94f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = if (raised(Look.style)) 26.dp else 10.dp),
        ) {
            FlowRow(
                maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().extrasFrame(panelShape(Look.style)).padding(10.dp),
            ) {
                extras.forEach { tab ->
                    ExtraItem(tab, current = tab == selected, hot = false, modifier = Modifier.weight(1f)) {
                        menu.open = false
                        onSelect(tab)
                    }
                }
                // Keep the last row's items the same width as the rows above.
                repeat((3 - extras.size % 3) % 3) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        ExtrasArc(menu, extras, selected)
    }
}

@Composable
private fun BoxScope.ExtrasArc(menu: ExtrasMenu, extras: List<Tab>, selected: Tab) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val item = 66.dp
    val radius = with(density) { 118.dp.toPx() }
    val itemPx = with(density) { item.toPx() }
    Box(Modifier.matchParentSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        extras.forEachIndexed { index, tab -> androidx.compose.runtime.key(tab) {
            val grow = remember { Animatable(0f) }
            LaunchedEffect(menu.open) {
                if (menu.open) {
                    kotlinx.coroutines.delay(index * 22L)
                    grow.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 520f))
                } else {
                    grow.snapTo(0f)
                    menu.itemBounds.remove(tab)
                }
            }
            if (grow.value > 0.01f || menu.open) {
            // Spread over an upper half-circle, a little narrower than flat.
            val spread = if (extras.size == 1) 0f else index.toFloat() / (extras.size - 1)
            val angle = Math.PI * (0.92 - spread * 0.84)
            val cx = menu.hubCenter.x + (cos(angle) * radius).toFloat() * grow.value
            val cy = menu.hubCenter.y - (sin(angle) * radius).toFloat() * grow.value - with(density) { 20.dp.toPx() } * grow.value
            val hot = menu.hot == tab
            Box(
                Modifier
                    .offset { IntOffset((cx - origin.x - itemPx / 2f).roundToInt(), (cy - origin.y - itemPx / 2f).roundToInt()) }
                    .size(item)
                    .graphicsLayer {
                        val s = grow.value * if (hot) 1.14f else 1f
                        scaleX = s
                        scaleY = s
                        alpha = grow.value.coerceIn(0f, 1f)
                    }
                    .onGloballyPositioned { if (menu.open) menu.itemBounds[tab] = it.boundsInRoot() }
                    .extrasFrame(if (Look.style == UiStyle.Genga || Look.style == UiStyle.Konbini) RectangleShape else if (Look.style == UiStyle.Tankobon || Look.style == UiStyle.Stamp) CircleShape else RoundedCornerShape(18.dp)),
            ) {
                ExtraItem(tab, current = tab == selected, hot = hot, modifier = Modifier.fillMaxSize(), onClick = null)
            }
            }
        } }
    }
}

/**
 * The pull-up style: a second row of tabs above the bar that a drag on the bar (or a tap on the handle) brings out
 * and puts away. [row] draws a row of tabs the look's way, [bar] the bar itself.
 */
@Composable
internal fun PullUpBar(
    pinned: List<Tab>,
    extras: List<Tab>,
    selected: Tab,
    row: @Composable (tabs: List<Tab>, edge: Boolean) -> Unit,
) {
    val theme = LocalAppTheme.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var rowHeight by remember { mutableStateOf(0f) }
    val shown = remember { Animatable(0f) }
    // Going to one of the extra tabs puts the row away again.
    LaunchedEffect(selected) { if (selected in extras && shown.value > 0f) shown.animateTo(0f, tween(Motion.ms(200))) }
    fun settle(open: Boolean) = scope.launch {
        if (open && shown.value < 1f) LookHaptics.hold(view)
        shown.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f))
    }
    Column(
        Modifier.pointerInput(extras) {
            detectVerticalDragGestures(
                onDragEnd = { settle(shown.value > 0.4f) },
                onDragCancel = { settle(shown.value > 0.4f) },
            ) { change, dragAmount ->
                if (rowHeight <= 0f) return@detectVerticalDragGestures
                change.consume()
                scope.launch { shown.snapTo((shown.value - dragAmount / rowHeight).coerceIn(0f, 1f)) }
            }
        },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(shown.value < 0.5f) }
                .semantics { contentDescription = if (shown.value > 0.5f) "Hide more tabs" else "Show more tabs" },
        ) {
            val handleNews = extras.any { it != selected && hasNews(it) } && shown.value < 0.5f
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (handleNews) theme.error else theme.foreground.copy(alpha = 0.35f)),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(density) { (rowHeight * shown.value).toDp() })
                .clip(RectangleShape),
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .wrapContentHeight(align = Alignment.Bottom, unbounded = true)
                    .onGloballyPositioned { rowHeight = it.size.height.toFloat() }
                    .graphicsLayer { alpha = shown.value },
            ) { row(extras, false) }
        }
        row(pinned, true)
    }
}

private const val HOLD_MILLIS = 280L
