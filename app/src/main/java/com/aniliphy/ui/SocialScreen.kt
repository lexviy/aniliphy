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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.aniliphy.anilist.Activity
import com.aniliphy.anilist.ActivityReply
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.PersonImage
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.UserCard
import com.aniliphy.anilist.plainBio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SocialPart { Feed, Following, Followers, Find }

/** How often the feed quietly checks for new activity while it's on screen. */
private const val FEED_CHECK_MS = 90_000L

/** Social: what the people you follow are doing, and who you follow. */
@Composable
internal fun SocialSection(retained: Retained) {
    val ui = LocalAniList.current
    val viewer = (ui.session.state as? SessionState.LoggedIn)?.viewer
    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Social, null)
        if (viewer == null) {
            LoginInvite("Log in to see the people you follow")
            return@Column
        }
        val part: MutableState<SocialPart> = retained.get("part") { mutableStateOf(SocialPart.Feed) }
        val following = retained.get("following") { ui.paged<UserCard>({ it.id }) { page -> ui.api.following(viewer.id, page) } }
        val followers = retained.get("followers") { ui.paged<UserCard>({ it.id }) { page -> ui.api.followers(viewer.id, page) } }
        val chips = retained.get("chips") { LazyListState() }
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            item { FilterChip("Feed", part.value == SocialPart.Feed) { part.value = SocialPart.Feed } }
            item {
                val n = following.items.size.takeIf { it > 0 }?.let { if (following.hasNext) " $it+" else " $it" }.orEmpty()
                FilterChip("Following$n", part.value == SocialPart.Following) { part.value = SocialPart.Following }
            }
            item {
                val n = followers.items.size.takeIf { it > 0 }?.let { if (followers.hasNext) " $it+" else " $it" }.orEmpty()
                FilterChip("Followers$n", part.value == SocialPart.Followers) { part.value = SocialPart.Followers }
            }
            item { FilterChip("Find people", part.value == SocialPart.Find) { part.value = SocialPart.Find } }
        }
        val followingIds = following.items.map { it.id }.toSet()
        val followerIds = followers.items.map { it.id }.toSet()
        when (part.value) {
            SocialPart.Feed -> Feed(retained)
            SocialPart.Following -> PeopleList(retained, "followingList", following, followingIds, followerIds, "You don't follow anyone yet")
            SocialPart.Followers -> PeopleList(retained, "followersList", followers, followingIds, followerIds, "Nobody follows you yet")
            SocialPart.Find -> FindPeople(retained, followingIds, followerIds)
        }
    }
}

@Composable
private fun Feed(retained: Retained) {
    val ui = LocalAniList.current
    val me = ui.session.userId
    // AniList's following feed includes your own activity; this one is only the people you follow.
    val friendsOnly = { p: com.aniliphy.anilist.Paged<Activity> -> p.copy(items = p.items.filter { it.user?.id != me }) }
    val feed = retained.get("feed") { ui.paged<Activity>({ it.id }) { page -> ui.api.feed(page).map(friendsOnly) } }
    val list = retained.get("feedList") { LazyListState() }
    var waiting by remember { mutableIntStateOf(0) }
    val liked = remember { mutableStateMapOf<Int, Pair<Boolean, Int>>() }
    var repliesFor by remember { mutableStateOf<Activity?>(null) }
    LoadMoreList(list, feed)
    // Pages of only your own activity come back empty; look a few pages further before calling it quiet.
    var topUps by remember { mutableIntStateOf(0) }
    LaunchedEffect(feed.items.size, feed.loading) {
        if (!feed.loading && feed.error == null && feed.hasNext && feed.items.size < 8 && topUps < 4) {
            topUps++
            feed.loadMore()
        }
    }
    // New activity while you look: counted, shown when you tap the notice.
    LaunchedEffect(feed) {
        while (true) {
            delay(FEED_CHECK_MS)
            val top = feed.items.firstOrNull()?.id ?: continue
            runCatching { ui.api.feed(1, refresh = true) }.getOrNull()?.let { fresh ->
                waiting = fresh.value.items.count { it.id > top && it.user?.id != me }
            }
        }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        if (waiting > 0) {
            item(key = "new") {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    FilterChip("$waiting new · tap to show", true) {
                        waiting = 0
                        feed.restart { page -> ui.api.feed(page, refresh = page == 1).map(friendsOnly) }
                    }
                }
            }
        }
        items(feed.items, key = { it.id }) { activity ->
            val like = liked[activity.id] ?: (activity.isLiked to activity.likeCount)
            FeedRow(
                activity = activity,
                liked = like.first,
                likes = like.second,
                onLike = {
                    liked[activity.id] = !like.first to (like.second + if (like.first) -1 else 1)
                    ui.scope.launch {
                        try {
                            val result = ui.api.toggleLike(activity.id)
                            liked[activity.id] = result.isLiked to result.likeCount
                        } catch (_: AniListException) {
                            liked[activity.id] = like
                        }
                    }
                },
                onReplies = { repliesFor = activity },
            )
        }
        item {
            val error = feed.error
            when {
                error != null -> ErrorRow(error) { feed.retry() }
                feed.loading -> Loading()
                feed.items.isEmpty() -> Empty("Nothing from the people you follow yet")
            }
        }
    }
    repliesFor?.let { activity -> RepliesDialog(activity) { repliesFor = null } }
}

/** One thing someone you follow did. */
@Composable
internal fun FeedRow(activity: Activity, liked: Boolean, likes: Int, onLike: () -> Unit, onReplies: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val user = activity.user ?: return
    val media = activity.media
    val mine = media?.let { ui.entryFor(it.id) }
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .clickable { media?.let { ui.navigator.open(Route.Media(it.id)) } ?: ui.navigator.open(Route.User(user.id)) }
            .padding(10.dp),
    ) {
        Avatar(user.avatar, 36, Modifier.clickable { ui.navigator.open(Route.User(user.id)) })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            val what = if (media == null) {
                "“${plainBio(activity.text).orEmpty()}”"
            } else {
                "${Formatting.activityDetail(activity).replaceFirstChar { it.lowercase() }} · ${media?.title?.display.orEmpty()}"
            }
            BasicText(user.name, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            BasicText(what, maxLines = 4, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.sp, lineHeight = 18.sp))
            val meta = listOfNotNull(
                Formatting.relativeTime(activity.createdAt).takeIf { it.isNotEmpty() },
                mine?.let { "on your list: ${ListStatus.label(it.status, it.media.kind)} ${Formatting.listProgress(it)}".trim() },
            ).joinToString(" · ")
            BasicText(meta, style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
                BasicText(
                    (if (liked) "\u2665\uFE0E " else "\u2661\uFE0E ") + likes,
                    style = NumberStyle(color = if (liked) theme.accent else theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp, bold = true),
                    modifier = Modifier.clip(lookShape(6.dp)).clickable(onClick = onLike).padding(horizontal = 4.dp, vertical = 2.dp),
                )
                BasicText(
                    "\u21A9\uFE0E ${activity.replyCount}",
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp, bold = true),
                    modifier = Modifier.clip(lookShape(6.dp)).clickable(onClick = onReplies).padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        if (media != null) {
            Spacer(Modifier.width(8.dp))
            Cover(media.coverImage.best, Modifier.size(width = 38.dp, height = 54.dp))
        }
    }
}

@Composable
private fun RepliesDialog(activity: Activity, onDismiss: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val replies = remember(activity.id) { ui.paged<ActivityReply>({ it.id }) { page -> ui.api.replies(activity.id, page, refresh = page == 1) } }
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).padding(16.dp)) {
            BasicText("Replies", style = TitleStyle(color = theme.foreground, fontSize = 18.sp))
            Spacer(Modifier.size(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(replies.items, key = { it.id }) { reply ->
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Avatar(reply.user?.avatar, 28)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            BasicText(
                                "${reply.user?.name.orEmpty()} · ${Formatting.relativeTime(reply.createdAt)}",
                                style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp),
                            )
                            BasicText(plainBio(reply.text).orEmpty(), style = TextStyle(color = theme.foreground, fontSize = 13.sp))
                        }
                    }
                }
                item {
                    when {
                        replies.error != null -> ErrorRow(replies.error!!) { replies.retry() }
                        replies.loading -> Loading()
                        replies.items.isEmpty() -> Hint("No replies yet")
                    }
                }
            }
            InputBox(text, { text = it }, "Write a reply", Modifier.fillMaxWidth(), maxLines = 4)
            error?.let { BasicText(it, style = TextStyle(color = theme.error, fontSize = 12.sp)) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                FilterChip("Close", false, onDismiss)
                Spacer(Modifier.width(8.dp))
                FilterChip(if (sending) "Sending…" else "Reply", text.isNotBlank()) {
                    if (text.isBlank() || sending) return@FilterChip
                    sending = true
                    error = null
                    ui.scope.launch {
                        try {
                            ui.api.reply(activity.id, text.trim())
                            text = ""
                            replies.restart { page -> ui.api.replies(activity.id, page, refresh = true) }
                        } catch (e: AniListException) {
                            error = ui.describe(e)
                        } finally {
                            sending = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeopleList(
    retained: Retained,
    key: String,
    people: PagedState<UserCard>,
    following: Set<Int>,
    followers: Set<Int>,
    empty: String,
) {
    val list = retained.get(key) { LazyListState() }
    LoadMoreList(list, people)
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        items(people.items, key = { it.id }) { user -> UserRow(user, user.id in following, user.id in followers) }
        item {
            when {
                people.error != null -> ErrorRow(people.error!!) { people.retry() }
                people.loading -> Loading()
                people.items.isEmpty() -> Empty(empty)
            }
        }
    }
}

@Composable
private fun FindPeople(retained: Retained, following: Set<Int>, followers: Set<Int>) {
    val ui = LocalAniList.current
    val text: MutableState<String> = retained.get("findText") { mutableStateOf("") }
    val results = retained.get("findResults") { ui.paged<UserCard>({ it.id }) { _ -> com.aniliphy.anilist.Fetched(com.aniliphy.anilist.Paged(emptyList(), false), false) } }
    LaunchedEffect(text.value) {
        val query = text.value.trim()
        if (query.length < 2) return@LaunchedEffect
        delay(400)
        results.restart { page -> ui.api.searchUsers(query, page) }
    }
    Column(Modifier.fillMaxSize()) {
        InputBox(text.value, { text.value = it }, "Search AniList users", Modifier.padding(horizontal = 20.dp).fillMaxWidth())
        val list = retained.get("findList") { LazyListState() }
        LoadMoreList(list, results)
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            items(results.items, key = { it.id }) { user -> UserRow(user, user.id in following, user.id in followers) }
            item {
                when {
                    results.error != null -> ErrorRow(results.error!!) { results.retry() }
                    results.loading -> Loading()
                    text.value.trim().length >= 2 && results.items.isEmpty() -> NothingFound()
                }
            }
        }
    }
}

/** Someone in a list of people, with a follow button. */
@Composable
internal fun UserRow(user: UserCard, following: Boolean, followsYou: Boolean) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { ui.navigator.open(Route.User(user.id)) }.padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Avatar(user.avatar, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold))
            if (followsYou) BasicText("follows you", style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp))
        }
        FollowButton(user.id, following, followsYou)
    }
}

/** Follow / Following / Follow back, sent to AniList when tapped. */
@Composable
internal fun FollowButton(userId: Int, following: Boolean, followsYou: Boolean) {
    val ui = LocalAniList.current
    val viewerId = ui.session.userId
    if (viewerId == userId) return
    var now by remember(userId, following) { mutableStateOf(following) }
    var busy by remember { mutableStateOf(false) }
    FilterChip(
        when {
            busy -> "…"
            now -> "Following"
            followsYou -> "Follow back"
            else -> "Follow"
        },
        now,
    ) {
        if (busy) return@FilterChip
        busy = true
        ui.scope.launch {
            try {
                now = ui.api.toggleFollow(userId)
            } catch (_: AniListException) {
            } finally {
                busy = false
            }
        }
    }
}

@Composable
internal fun Avatar(image: PersonImage?, size: Int, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val shape = when (Look.style) {
        UiStyle.Tankobon, UiStyle.Genga, UiStyle.Konbini, UiStyle.Bunko -> lookShape(4.dp, 4.dp)
        else -> CircleShape
    }
    AsyncImage(
        model = image?.best,
        contentDescription = null,
        modifier = modifier.size(size.dp).clip(shape).background(theme.panel),
    )
}

/** Loads the next page when the end of [list] comes near. */
@Composable
internal fun <T> LoadMoreList(list: LazyListState, paged: PagedState<T>) {
    LaunchedEffect(list, paged) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= list.layoutInfo.totalItemsCount - 4) paged.loadMore()
        }
    }
}
