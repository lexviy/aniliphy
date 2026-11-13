package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aniliphy.anilist.FriendEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.SessionState
import kotlinx.coroutines.launch

/** The people you follow who have this title on their list, and where they are with it. */
@Composable
internal fun FriendsWithThis(media: Media) {
    val ui = LocalAniList.current
    if (ui.session.state !is SessionState.LoggedIn) return
    val load = remember(media.id) { ui.load<List<FriendEntry>> { ui.api.friendsOn(media.id, it) } }
    // AniList's "following" filter lets your own entry through.
    val friends = load.value?.filter { it.user.id != ui.session.userId } ?: return
    if (friends.isEmpty()) return
    val theme = LocalAppTheme.current
    SectionTitle("Friends with this", friends.size)
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(friends, key = { it.user.id }) { friend ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(76.dp).clip(lookShape(10.dp)).clickable { ui.navigator.open(Route.User(friend.user.id)) }.padding(4.dp),
            ) {
                Avatar(friend.user.avatar, 52)
                BasicText(
                    friend.user.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 4.dp),
                )
                BasicText(friendLine(friend, media.kind), maxLines = 2, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 10.sp, lineHeight = 13.sp))
            }
        }
    }
}

private fun friendLine(f: FriendEntry, kind: MediaKind): String {
    val status = ListStatus.label(f.status, kind) ?: "On list"
    val score = if (f.score > 0) " · ★${if (f.score % 1.0 == 0.0) f.score.toInt().toString() else "%.1f".format(f.score)}" else ""
    val progress = when {
        f.status == ListStatus.COMPLETED || f.status == ListStatus.PLANNING -> ""
        kind == MediaKind.LightNovel && f.progressVolumes > 0 -> " · vol ${f.progressVolumes}"
        f.progress > 0 -> " · ${f.progress}"
        else -> ""
    }
    return status + progress + score
}

/** Share a title: its link with a note, or a picture of it drawn in the look. */
@Composable
internal fun MediaShareDialog(media: Media, onDismiss: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var toFriend by remember { mutableStateOf(false) }
    val kind = if (media.kind == MediaKind.Anime) "anime" else "manga"
    val link = "https://anilist.co/$kind/${media.id}"
    val entry = ui.entryFor(media.id)
    fun text() = listOf(note.trim(), "${media.title.display} — $link").filter { it.isNotEmpty() }.joinToString("\n")
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).padding(16.dp)) {
            BasicText("Share", style = TitleStyle(color = theme.foreground, fontSize = 18.sp))
            BasicText(media.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp))
            Spacer(Modifier.size(10.dp))
            InputBox(note, { note = it }, "Add a note (optional)", Modifier.fillMaxWidth(), maxLines = 3)
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Link", true) {
                    context.shareText(text())
                    onDismiss()
                }
                FilterChip(if (busy) "Drawing…" else "Picture", true) {
                    if (busy) return@FilterChip
                    busy = true
                    scope.launch {
                        try {
                            ShareImage.share(context, theme, mediaCard(media, entry, note.trim(), link), text())
                            onDismiss()
                        } finally {
                            busy = false
                        }
                    }
                }
                if (ui.session.state is SessionState.LoggedIn) FilterChip("To a friend", toFriend) { toFriend = !toFriend }
            }
            if (toFriend) {
                Spacer(Modifier.size(10.dp))
                RecommendPanel(media, link, onDismiss)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                FilterChip("Close", false, onDismiss)
            }
        }
    }
}

private fun mediaCard(media: Media, entry: com.aniliphy.anilist.ListEntry?, note: String, link: String): ShareCard {
    val kicker = when (entry?.status) {
        ListStatus.CURRENT -> if (media.kind == MediaKind.Anime) "Now watching" else "Now reading"
        ListStatus.COMPLETED -> "Finished"
        ListStatus.PLANNING -> "Up next"
        ListStatus.REPEATING -> "Again"
        ListStatus.DROPPED -> "Dropped"
        ListStatus.PAUSED -> "On hold"
        else -> "Recommended"
    }
    val lines = buildList {
        if (note.isNotEmpty()) add("“$note”")
        entry?.let { e ->
            val progress = com.aniliphy.anilist.Formatting.listProgress(e)
            if (e.status != ListStatus.COMPLETED && progress.isNotBlank()) add(progress)
        }
        listOfNotNull(com.aniliphy.anilist.Formatting.format(media.format), media.seasonYear?.toString() ?: media.startDate?.year?.toString())
            .joinToString(" · ").takeIf { it.isNotEmpty() }?.let(::add)
        media.genres.take(3).takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
    }
    val score = entry?.score?.takeIf { it > 0 }
    return ShareCard(
        kicker = kicker,
        title = media.title.display,
        lines = lines,
        big = score?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) },
        bigLabel = score?.let { "/ 10" },
        cover = media.coverImage.best,
        link = link,
    )
}
