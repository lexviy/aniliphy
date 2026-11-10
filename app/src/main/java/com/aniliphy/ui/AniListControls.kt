package com.aniliphy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
internal fun Cover(url: String?, modifier: Modifier = Modifier) {
    val shape = if (LocalCoverStyle.current.corners == CoverCorners.Rounded) RoundedCornerShape(8.dp) else RectangleShape
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape).background(LocalAppTheme.current.panel),
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
) {
    val theme = LocalAppTheme.current
    val titleStyle = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    val subtitleStyle = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp)
    val detailStyle = TextStyle(color = theme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(theme.surface.copy(alpha = 0.72f))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(8.dp),
        ) {
            Box(Modifier.width(56.dp).height(80.dp)) {
                Cover(url, Modifier.fillMaxSize())
                if (repeats > 0) RepeatBadge(repeats, Modifier.align(Alignment.TopStart).padding(3.dp), 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = titleStyle)
                if (!subtitle.isNullOrEmpty()) BasicText(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = subtitleStyle)
                if (!detail.isNullOrEmpty()) BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = detailStyle)
            }
        }
    } else {
        Column(modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
                Cover(url, Modifier.fillMaxSize())
                if (repeats > 0) RepeatBadge(repeats, Modifier.align(Alignment.TopStart).padding(5.dp))
            }
            Spacer(Modifier.height(5.dp))
            BasicText(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = titleStyle)
            if (!subtitle.isNullOrEmpty()) BasicText(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis, style = subtitleStyle)
            if (!detail.isNullOrEmpty()) BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = detailStyle)
        }
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
    card: @Composable (T) -> Unit,
) {
    SectionTitle(title)
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items.distinctBy(key), key = key) { item -> Box(Modifier.width(RowCardWidth)) { card(item) } }
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
            .clip(CircleShape)
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
            .clip(RoundedCornerShape(12.dp))
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
) {
    val theme = LocalAppTheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) theme.primary else theme.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        BasicText(
            label,
            maxLines = 1,
            style = TextStyle(
                color = if (filled) theme.background else theme.foreground,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
internal fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
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
