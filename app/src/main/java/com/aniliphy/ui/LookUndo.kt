package com.aniliphy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The bar that offers to undo taking a title off the list, at the bottom of the screen above the tab bar, dressed in
 * the look: a speech balloon for Tankōbon, a menu window for JRPG, a voided receipt line for Konbini... A thin line
 * under the text runs out with the undo time. When the removal fails, the same bar says why for a moment.
 */
@Composable
internal fun BoxScope.UndoRemovalBar(removals: ListRemovals) {
    val pending = removals.pending
    val failed = removals.failed
    LaunchedEffect(failed) {
        if (failed != null) {
            delay(FAILURE_MILLIS)
            removals.dismissFailure()
        }
    }
    AnimatedVisibility(
        visible = pending != null || failed != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Keep showing the last text while sliding away.
        val last = remember { arrayOfNulls<Pair<Int?, String>>(1) }
        when {
            failed != null -> last[0] = null to failed
            pending != null -> last[0] = pending.id to LookWords.removed(words(), pending.title)
        }
        last[0]?.let { (id, text) -> UndoBody(id, text, onUndo = if (id != null) removals::undo else null) }
    }
}

private fun words(): UiStyle? = Look.style.takeIf { LookPrefs.moments }

@Composable
private fun UndoBody(id: Int?, text: String, onUndo: (() -> Unit)?) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val view = LocalView.current
    val left = remember(id) { Animatable(1f) }
    LaunchedEffect(id) {
        if (id != null) left.animateTo(0f, tween(ListRemovals.UNDO_MILLIS.toInt(), easing = LinearEasing))
    }
    val ink = when (style) {
        UiStyle.Tankobon, UiStyle.Konbini, UiStyle.Stamp -> theme.ink
        else -> theme.foreground
    }
    val accent = if (onUndo == null) theme.error else theme.primary
    val textStyle = when (style) {
        UiStyle.Konbini, UiStyle.Shinya, UiStyle.Jrpg, UiStyle.Eki -> NumberStyle(color = ink, fontSize = 13.sp)
        UiStyle.Bunko -> NumberStyle(color = ink, fontSize = 14.sp)
        else -> TextStyle(color = ink, fontSize = 14.sp)
    }
    val shape = when (style) {
        UiStyle.Tankobon, UiStyle.Stamp -> RoundedCornerShape(50)
        UiStyle.Genga, UiStyle.Konbini, UiStyle.Shinya -> RoundedCornerShape(2.dp)
        UiStyle.Eki -> RoundedCornerShape(6.dp)
        else -> RoundedCornerShape(16.dp)
    }
    val frame = when (style) {
        UiStyle.Tankobon -> Modifier.clip(shape).background(theme.surface).border(1.5.dp, theme.ink, shape)
        UiStyle.Glass -> Modifier.glass(theme, shape)
        UiStyle.Genga -> Modifier.clip(shape).background(theme.surface).border(1.dp, theme.hairline, shape)
        UiStyle.Jrpg -> Modifier.rpgWindow(theme)
        UiStyle.Konbini -> Modifier.clip(shape).background(theme.surface).drawBehind {
            // Torn receipt edges, dashed.
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            drawLine(theme.ink, Offset(0f, 1f), Offset(size.width, 1f), 1.dp.toPx(), pathEffect = dash)
            drawLine(theme.ink, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1.dp.toPx(), pathEffect = dash)
        }
        UiStyle.Stamp -> Modifier.clip(shape).background(theme.surface).border(2.dp, accent, shape)
        UiStyle.Shinya -> Modifier.clip(shape).background(Color.Black.copy(alpha = 0.86f)).border(1.dp, accent, shape)
        UiStyle.Eki -> Modifier.clip(shape).background(theme.surface).drawBehind {
            drawRect(theme.ekiLine(1), size = androidx.compose.ui.geometry.Size(5.dp.toPx(), size.height))
        }
        else -> Modifier.clip(shape).background(theme.surface)
    }
    Column(Modifier.fillMaxWidth().then(frame)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = if (style == UiStyle.Eki) 16.dp else 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            if (style == UiStyle.Stamp && onUndo != null) {
                StampMark("消", accent, 30.dp, tilt = -12f)
                Spacer(Modifier.width(10.dp))
            }
            BasicText(
                if (style == UiStyle.Shinya) text.uppercase() else text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = if (style == UiStyle.Shinya) textStyle.copy(color = Color.White) else textStyle,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            if (onUndo != null) {
                val label = LookWords.undo(words())
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(role = Role.Button) {
                            LookHaptics.hold(view)
                            LookSounds.tap()
                            onUndo()
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicText(
                        when (style) {
                            UiStyle.Jrpg -> "▶ $label"
                            UiStyle.Shinya, UiStyle.Konbini, UiStyle.Eki -> label.uppercase()
                            else -> label
                        },
                        style = textStyle.copy(color = accent, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        if (id != null) {
            // The time left to undo, running out.
            Box(
                Modifier
                    .padding(horizontal = if (style == UiStyle.Tankobon || style == UiStyle.Stamp) 20.dp else 0.dp)
                    .fillMaxWidth(left.value)
                    .height(2.dp)
                    .background(accent.copy(alpha = 0.7f)),
            )
        }
    }
}

private const val FAILURE_MILLIS = 4_000L
