package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.aniliphy.anilist.Activity
import com.aniliphy.anilist.ActivityReply
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniNotification
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.Inbox
import com.aniliphy.anilist.NoteGroup
import com.aniliphy.anilist.NoteTarget
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.plainBio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often the unread count is checked while the app is open. */
private const val UNREAD_CHECK_MS = 5 * 60_000L

/** Keeps [LookSignals.unread] fresh while the app is on screen and you're logged in. */
@Composable
internal fun UnreadWatch(ui: AniListUi) {
    val loggedIn = ui.session.state is SessionState.LoggedIn
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(loggedIn) {
        if (!loggedIn) {
            LookSignals.unread = 0
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                runCatching { ui.api.unreadCount() }.getOrNull()?.let { LookSignals.unread = it }
                delay(UNREAD_CHECK_MS)
            }
        }
    }
}

/** A notification, or several likes on the same thing shown as one. */
private data class NoteRow(val notes: List<AniNotification>) {
    val first get() = notes.first()
}

private val LIKES = setOf("ACTIVITY_LIKE", "ACTIVITY_REPLY_LIKE", "THREAD_LIKE", "THREAD_COMMENT_LIKE")

/** Likes in a row on the same activity, thread or comment become one row. */
private fun bundle(notes: List<AniNotification>): List<NoteRow> {
    val out = mutableListOf<MutableList<AniNotification>>()
    for (n in notes) {
        val last = out.lastOrNull()?.first()
        if (last != null && n.type in LIKES && last.type == n.type && Inbox.target(last) == Inbox.target(n)) out.last() += n
        else out += mutableListOf(n)
    }
    return out.map { NoteRow(it) }
}

@Composable
internal fun InboxSection(retained: Retained) {
    val ui = LocalAniList.current
    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Inbox, null)
        if (ui.session.state !is SessionState.LoggedIn) {
            LoginInvite("Log in to see your AniList notifications")
            return@Column
        }
        val group: MutableState<NoteGroup> = retained.get("group") { mutableStateOf(NoteGroup.All) }
        // How many were unread when you opened it: those get tinted. Opening the whole inbox marks them read.
        val unreadAtOpen: MutableState<Int> = retained.get("unreadAtOpen") { mutableStateOf(LookSignals.unread) }
        val notes = retained.get("notes") {
            ui.paged<AniNotification>({ it.id }) { page -> ui.api.notifications(page, markRead = page == 1, refresh = page == 1) }
        }
        LaunchedEffect(notes.items.isNotEmpty()) { if (notes.items.isNotEmpty()) LookSignals.unread = 0 }
        val chips = retained.get("chips") { LazyListState() }
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(NoteGroup.entries, key = { it.name }) { g ->
                FilterChip(g.label, group.value == g) {
                    if (group.value == g) return@FilterChip
                    group.value = g
                    notes.restart { page -> ui.api.notifications(page, markRead = false, types = g.types, refresh = page == 1) }
                }
            }
        }
        val list = retained.get("list") { LazyListState() }
        LoadMoreList(list, notes)
        val rows = remember(notes.items) { bundle(notes.items) }
        val unreadIds = remember(notes.items, unreadAtOpen.value, group.value) {
            if (group.value == NoteGroup.All) notes.items.take(unreadAtOpen.value).map { it.id }.toSet() else emptySet()
        }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.first.id }) { row -> NoteItem(row, unread = row.notes.any { it.id in unreadIds }) }
            item {
                when {
                    notes.error != null -> ErrorRow(notes.error!!) { notes.retry() }
                    notes.loading -> Loading()
                    notes.items.isEmpty() -> Empty("Nothing here")
                }
            }
        }
    }
}

@Composable
private fun NoteItem(row: NoteRow, unread: Boolean) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val n = row.first
    val text = if (row.notes.size > 1) {
        val names = row.notes.mapNotNull { it.user?.name }.distinct()
        "${names.first()} and ${names.size - 1} other${if (names.size > 2) "s" else ""}${Inbox.text(n).removePrefix(n.user?.name.orEmpty())}"
    } else {
        Inbox.text(n)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(lookShape(10.dp))
            .background(if (unread) theme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable {
                when (val t = Inbox.target(n)) {
                    is NoteTarget.User -> ui.navigator.open(Route.User(t.id))
                    is NoteTarget.MediaPage -> ui.navigator.open(Route.Media(t.id))
                    is NoteTarget.Thread -> ui.navigator.open(Route.Thread(t.id, t.comment))
                    is NoteTarget.Activity -> ui.navigator.open(Route.Activity(t.id))
                    null -> Unit
                }
            }
            .padding(horizontal = 8.dp, vertical = 9.dp),
    ) {
        when {
            n.user != null -> Avatar(n.user.avatar, 34)
            n.media != null -> Cover(n.media.coverImage.best, Modifier.size(width = 30.dp, height = 42.dp))
            else -> Box(Modifier.size(34.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(text, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.sp, lineHeight = 18.sp))
            BasicText(Formatting.relativeTime(n.createdAt), style = NumberStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 11.sp))
        }
    }
}

/** One activity with its replies, opened from a notification. */
@Composable
internal fun ActivityPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = retained.get("activity") { ui.load<Activity> { ui.api.activity(id, it) } }
    val replies = retained.get("replies") { ui.paged<ActivityReply>({ it.id }) { page -> ui.api.replies(id, page, refresh = page == 1) } }
    var liked by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        BackHeader("Activity") { ui.navigator.back() }
        val a = activity.value
        if (a == null) {
            if (activity.error != null) ErrorRow(activity.error!!) { activity.load() } else Loading()
            return@Column
        }
        val list = retained.get("list") { LazyListState() }
        LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                val like = liked ?: (a.isLiked to a.likeCount)
                FeedRow(
                    activity = a,
                    liked = like.first,
                    likes = like.second,
                    onLike = {
                        liked = !like.first to (like.second + if (like.first) -1 else 1)
                        ui.scope.launch {
                            try {
                                val r = ui.api.toggleLike(a.id)
                                liked = r.isLiked to r.likeCount
                            } catch (e: AniListException) {
                                liked = like
                                context.showToast(ui.describe(e))
                            }
                        }
                    },
                    onReplies = {},
                )
            }
            item { SectionTitle("Replies", a.replyCount.takeIf { it > 0 }) }
            items(replies.items, key = { it.id }) { r ->
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    r.user?.let { u -> Avatar(u.avatar, 28, Modifier.clickable { ui.navigator.open(Route.User(u.id)) }) }
                    Spacer(Modifier.width(8.dp))
                    Column {
                        BasicText(
                            "${r.user?.name.orEmpty()} · ${Formatting.relativeTime(r.createdAt)}",
                            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp),
                        )
                        BasicText(plainBio(r.text).orEmpty(), style = TextStyle(color = theme.foreground, fontSize = 13.sp))
                    }
                }
            }
            item {
                when {
                    replies.error != null -> ErrorRow(replies.error!!) { replies.retry() }
                    replies.loading -> Loading()
                    replies.items.isEmpty() -> Hint("No replies yet", Modifier.padding(horizontal = 20.dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.background(theme.background.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            InputBox(text, { text = it }, "Write a reply", Modifier.weight(1f), maxLines = 4)
            FilterChip(if (sending) "…" else "Reply", text.isNotBlank()) {
                if (text.isBlank() || sending) return@FilterChip
                sending = true
                ui.scope.launch {
                    try {
                        ui.api.reply(a.id, text.trim())
                        text = ""
                        replies.restart { page -> ui.api.replies(a.id, page, refresh = true) }
                    } catch (e: AniListException) {
                        context.showToast(ui.describe(e))
                    } finally {
                        sending = false
                    }
                }
            }
        }
    }
}
