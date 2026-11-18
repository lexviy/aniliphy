package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.LikeKind
import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.PendingWrite
import com.aniliphy.anilist.WriteConflict
import java.text.DateFormat
import java.util.Date

/** The two lines of a queued write in Settings › Activity. */
internal fun PendingWrite.lines(): Pair<String, String> = when (this) {
    is PendingWrite.Entry -> {
        val kind = if (anime) MediaKind.Anime else MediaKind.Manga
        val unit = if (anime) "episode" else "chapter"
        val onlyPlanning = status == ListStatus.PLANNING && progress == null && score == null
        title to if (onlyPlanning) "Added to Planning" else listOfNotNull(
            "Status → ${ListStatus.label(status, kind)}",
            score?.takeIf { it > 0 }?.let { "score ${number(it)}" },
            progress?.takeIf { it > 0 }?.let { "$unit $it" },
            notes?.let { "notes" },
        ).joinToString(" · ")
    }
    is PendingWrite.Like -> {
        val verb = if (liked) "♥︎ liked" else "♡︎ unliked"
        when (kind) {
            LikeKind.Activity -> "$verb $about's activity" to "Social"
            LikeKind.Thread -> "$verb a thread" to "Forum · $about"
            LikeKind.Comment -> "$verb a comment" to "Forum"
        }
    }
    is PendingWrite.Reply -> "\"${text.take(80)}\"" to if (forum) "Comment · $about" else "Reply to $about · Social"
}

private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
internal fun PendingWriteRow(item: PendingWrite, onRemove: () -> Unit) {
    val theme = LocalAppTheme.current
    val (title, detail) = item.lines()
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium))
            BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
        }
        BasicText(
            "✕",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 18.sp),
            modifier = Modifier
                .clip(newLookShape(8.dp))
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Don't send" }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
internal fun ConflictRow(conflict: WriteConflict, onOpen: () -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "CHECK",
                style = TextStyle(color = theme.background, fontSize = 10.sp, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
                modifier = Modifier.background(theme.error).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicText(conflict.mine.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        }
        BasicText("Changed on AniList too · tap to choose", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
    }
}

/** An entry edited here that also changed on AniList: which one stays. */
@Composable
internal fun ConflictDialog(conflict: WriteConflict, onKeep: (mine: Boolean) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val mine = conflict.mine
    val theirs = conflict.theirs
    val kind = if (mine.anime) MediaKind.Anime else MediaKind.Manga
    val mineRows = listOf(
        "Status" to ListStatus.label(mine.status, kind),
        (if (mine.anime) "Episode" else "Chapter") to (mine.progress ?: theirs.progress).toString(),
        "Score" to (mine.score ?: theirs.score)?.takeIf { it > 0 }?.let(::number).orDash(),
    )
    val theirRows = listOf(
        "Status" to ListStatus.label(theirs.status, kind),
        (if (mine.anime) "Episode" else "Chapter") to theirs.progress.toString(),
        "Score" to theirs.score?.takeIf { it > 0 }?.let(::number).orDash(),
    )
    val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    DialogFrame(onDismiss) {
        BasicText(mine.title, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        BasicText(
            "Changed on AniList (maybe on the computer) after you edited it here. Which one stays?",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp),
        )
        Row(Modifier.fillMaxWidth()) {
            ConflictSide("THIS PHONE", theme.primary, mineRows, theirRows, format.format(Date(mine.at)), true, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            ConflictSide("ANILIST NOW", theme.error, theirRows, mineRows, theirs.updatedAt?.let { format.format(Date(it * 1000)) }, false, Modifier.weight(1f))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            PillButton("Keep AniList's", { onKeep(false) }, filled = false)
            Spacer(Modifier.width(10.dp))
            PillButton("Keep mine", { onKeep(true) })
        }
    }
}

private fun String?.orDash() = this ?: "—"

@Composable
private fun ConflictSide(
    who: String,
    color: Color,
    rows: List<Pair<String, String>>,
    other: List<Pair<String, String>>,
    time: String?,
    highlight: Boolean,
    modifier: Modifier,
) {
    val theme = LocalAppTheme.current
    Column(
        modifier
            .clip(newLookShape(8.dp))
            .background(if (highlight) theme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.5.dp, if (highlight) theme.primary else theme.foreground.copy(alpha = 0.25f), newLookShape(8.dp))
            .padding(10.dp),
    ) {
        BasicText(who, style = TextStyle(color = color, fontSize = 11.sp, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        rows.forEachIndexed { i, (label, value) ->
            val differs = other.getOrNull(i)?.second != value
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                BasicText(label, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp), modifier = Modifier.weight(1f))
                BasicText(
                    value,
                    style = TextStyle(
                        color = if (differs) theme.accent else theme.foreground,
                        fontSize = 13.sp,
                        fontWeight = if (differs) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
        }
        time?.let {
            Spacer(Modifier.height(6.dp))
            BasicText(it, style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = JetBrainsMono))
        }
    }
}
