package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind

/** The colour that stands for a list status, from the theme. */
internal fun AppTheme.statusColor(status: String?): Color = when (status) {
    ListStatus.COMPLETED -> success
    ListStatus.CURRENT -> primary
    ListStatus.REPEATING -> accent
    ListStatus.PAUSED -> warning
    ListStatus.DROPPED -> error
    else -> foreground.copy(alpha = 0.75f)
}

/** How far through the entry is, 0 to 1, or null when the total isn't known. Completed is always full. */
internal fun ListEntry.share(): Float? {
    if (status == ListStatus.COMPLETED) return 1f
    val total = (if (media.kind == MediaKind.Anime) media.episodes else media.chapters)?.takeIf { it > 0 } ?: return null
    return (progress.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * On a cover, a small mark saying the title is on your list and how (Watching, Completed...), with a thin bar along
 * the bottom for how far along you are. Each look marks it its own way: a cut-corner label for Tankōbon, a menu
 * window for JRPG, a line-coloured plate for Eki, a tilted stamp for Stamp...
 */
@Composable
internal fun BoxScope.ListMark(entry: ListEntry, compact: Boolean) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val colour = theme.statusColor(entry.status)
    val label = ListStatus.label(entry.status, entry.media.kind)
    val size = if (compact) 8.sp else 10.sp
    entry.share()?.takeIf { it > 0f }?.let { share ->
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(share)
                .height(if (compact) 2.dp else 3.dp)
                .background(colour),
        )
    }
    val inset = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = if (compact) 5.dp else 7.dp)
    val upper = label.uppercase()
    when (style) {
        UiStyle.Tankobon -> MarkText(
            label,
            TextStyle(color = theme.ink, fontSize = size, fontWeight = FontWeight.Bold),
            inset.clip(CutCornerShape(bottomEnd = 5.dp)).background(theme.surface).border(1.dp, theme.ink, CutCornerShape(bottomEnd = 5.dp)),
        )
        UiStyle.Modern -> MarkText(
            label,
            TextStyle(color = theme.background, fontSize = size, fontWeight = FontWeight.SemiBold),
            inset.clip(RoundedCornerShape(50)).background(colour),
        )
        UiStyle.Glass -> MarkText(
            label,
            TextStyle(color = theme.ink, fontSize = size, fontWeight = FontWeight.SemiBold),
            inset.glass(theme, RoundedCornerShape(50)),
        )
        UiStyle.Genga -> MarkText(
            upper,
            NumberStyle(color = colour, fontSize = size, bold = true),
            inset.background(theme.surface).border(1.dp, theme.hairline, RectangleShape),
        )
        UiStyle.Bunko -> MarkText(label, NumberStyle(color = colour, fontSize = size, bold = true), inset.clip(RoundedCornerShape(2.dp)).background(theme.surface))
        UiStyle.Shinya -> MarkText(upper, NumberStyle(color = Color.White, fontSize = size), inset.background(Color.Black.copy(alpha = 0.8f)).border(1.dp, colour, RectangleShape))
        UiStyle.Jrpg -> MarkText(label, NumberStyle(color = theme.foreground, fontSize = size), inset.rpgWindow(theme, radius = 6.dp))
        UiStyle.Eki -> MarkText(upper, NumberStyle(color = Color.White, fontSize = size, bold = true), inset.clip(RoundedCornerShape(3.dp)).background(colour))
        UiStyle.Konbini -> MarkText(upper, NumberStyle(color = theme.ink, fontSize = size, bold = true), inset.background(theme.surface).border(1.dp, theme.ink, RectangleShape))
        UiStyle.Stamp -> MarkText(
            upper,
            TextStyle(color = colour, fontSize = size, fontWeight = FontWeight.Black),
            inset.rotate(-8f).background(theme.surface.copy(alpha = 0.9f)).border(1.5.dp, colour, RoundedCornerShape(3.dp)),
        )
    }
}

@Composable
private fun MarkText(text: String, style: TextStyle, modifier: Modifier) {
    BasicText(text, maxLines = 1, style = style, modifier = modifier.padding(horizontal = 5.dp, vertical = 1.dp))
}
