package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniliphy.anilist.AniListApi
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.EpisodeThreads
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.ForumCategories
import com.aniliphy.anilist.ForumText
import com.aniliphy.anilist.ForumThread
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Paged
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.ThreadComment
import com.aniliphy.anilist.ThreadQuery
import kotlinx.coroutines.launch

/** What the forum tab lists. */
private sealed interface ForumFilter {
    val label: String

    data object Recent : ForumFilter { override val label = "Recent" }
    data object YourTitles : ForumFilter { override val label = "Your titles" }
    data object Subscribed : ForumFilter { override val label = "Subscribed" }
    data class Category(val id: Int, override val label: String) : ForumFilter
}

private val QUICK_CATEGORIES = listOf(
    ForumFilter.Category(ForumCategories.RELEASE_DISCUSSION, "Release Discussion"),
    ForumFilter.Category(ForumCategories.ANIME, "Anime"),
    ForumFilter.Category(ForumCategories.MANGA, "Manga"),
)

/** "Your titles" looks through this many pages of recent threads for ones about your list. */
private const val YOUR_TITLES_PAGES = 6

@Composable
internal fun ForumSection(retained: Retained) {
    val ui = LocalAniList.current
    val loggedIn = ui.session.state is SessionState.LoggedIn
    val filter: MutableState<ForumFilter> = retained.get("filter") { mutableStateOf(ForumFilter.Recent) }
    val search: MutableState<String> = retained.get("search") { mutableStateOf("") }
    var moreOpen by remember { mutableStateOf(false) }

    fun block(f: ForumFilter, text: String): suspend (Int) -> Fetched<Paged<ForumThread>> {
        val words = text.trim().takeIf { it.length >= 2 }
        return when (f) {
            ForumFilter.Recent -> { page -> ui.api.threads(page, ThreadQuery(search = words)) }
            ForumFilter.Subscribed -> { page -> ui.api.threads(page, ThreadQuery(search = words, subscribed = true)) }
            is ForumFilter.Category -> { page -> ui.api.threads(page, ThreadQuery(category = f.id, search = words)) }
            ForumFilter.YourTitles -> { page ->
                ui.api.threads(page, ThreadQuery(search = words)).map { p ->
                    p.copy(items = p.items.filter { t -> t.mediaCategories.orEmpty().any { ui.entryFor(it.id) != null } })
                }
            }
        }
    }
    val threads = retained.get("threads") { ui.paged<ForumThread>({ it.id }, block(filter.value, search.value)) }
    LaunchedEffect(filter.value, search.value) {
        if (search.value.isNotEmpty()) kotlinx.coroutines.delay(400)
        val key = filter.value to search.value.trim()
        if (retained.get<MutableState<Any?>>("shown") { mutableStateOf(ForumFilter.Recent to "") }.value != key) {
            retained.get<MutableState<Any?>>("shown") { mutableStateOf(null) }.value = key
            threads.restart(block(filter.value, search.value))
        }
    }

    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Forum, null)
        val chips = retained.get("chips") { LazyListState() }
        val options = buildList {
            add(ForumFilter.Recent)
            if (loggedIn) add(ForumFilter.YourTitles)
            addAll(QUICK_CATEGORIES)
            (filter.value as? ForumFilter.Category)?.takeIf { it !in QUICK_CATEGORIES }?.let(::add)
            if (loggedIn) add(ForumFilter.Subscribed)
        }
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            items(options, key = { it.label }) { option ->
                FilterChip(option.label, filter.value == option) { filter.value = option }
            }
            item(key = "more") { FilterChip("More…", false) { moreOpen = true } }
            if (loggedIn) item(key = "new") { FilterChip("+ New thread", false) { ui.navigator.open(Route.NewThread()) } }
        }
        InputBox(search.value, { search.value = it }, "Search threads", Modifier.padding(horizontal = 20.dp).fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        ThreadList(retained, threads, topUp = filter.value == ForumFilter.YourTitles)
    }
    if (moreOpen) {
        CategoryPicker(onDismiss = { moreOpen = false }) { id, name ->
            moreOpen = false
            filter.value = QUICK_CATEGORIES.firstOrNull { it.id == id } ?: ForumFilter.Category(id, name)
        }
    }
}

@Composable
private fun CategoryPicker(onDismiss: () -> Unit, onPick: (Int, String) -> Unit) {
    val theme = LocalAppTheme.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).padding(vertical = 12.dp).verticalScroll(rememberScrollState())) {
            BasicText("Categories", style = TitleStyle(color = theme.foreground, fontSize = 18.sp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            ForumCategories.all.forEach { c ->
                BasicText(
                    c.name,
                    style = TextStyle(color = theme.foreground, fontSize = 15.sp),
                    modifier = Modifier.fillMaxWidth().clickable { onPick(c.id, c.name) }.padding(horizontal = 20.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun ThreadList(retained: Retained, threads: PagedState<ForumThread>, topUp: Boolean = false, listKey: String = "list") {
    val list = retained.get(listKey) { LazyListState() }
    LoadMoreList(list, threads)
    if (topUp) {
        var pages by remember(threads) { mutableIntStateOf(0) }
        LaunchedEffect(threads.items.size, threads.loading) {
            if (!threads.loading && threads.error == null && threads.hasNext && threads.items.size < 10 && pages < YOUR_TITLES_PAGES) {
                pages++
                threads.loadMore()
            }
        }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        items(threads.items, key = { it.id }) { ThreadRow(it) }
        item {
            when {
                threads.error != null -> ErrorRow(threads.error!!) { threads.retry() }
                threads.loading -> Loading()
                threads.items.isEmpty() -> Empty("No threads here")
            }
        }
    }
}

/** How far you are with a title, as a short tag: "You: ep 6". */
@Composable
private fun progressTag(media: Media): String? {
    val entry = LocalAniList.current.entryFor(media.id) ?: return null
    return when {
        entry.status == ListStatus.COMPLETED -> "You: finished"
        entry.status == ListStatus.PLANNING -> "You: planning"
        media.kind == MediaKind.Anime -> "You: ep ${entry.progress}"
        else -> "You: ch ${entry.progress}"
    }
}

@Composable
internal fun ThreadRow(thread: ForumThread) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { ui.navigator.open(Route.Thread(thread.id)) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        BasicText(
            thread.title.trim(), maxLines = 3, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp),
        )
        val tags = buildList {
            if (thread.isSticky) add(Tag("PINNED", theme.error))
            if (thread.isLocked) add(Tag("LOCKED", theme.foreground.copy(alpha = 0.6f)))
            thread.categories.orEmpty().take(2).forEach { add(Tag(it.name.uppercase(), theme.foreground.copy(alpha = 0.6f))) }
            thread.mediaCategories.orEmpty().take(2).forEach { m ->
                add(Tag(m.title.display, theme.primary))
                progressTag(m)?.let { add(Tag(it, theme.primary)) }
            }
        }
        if (tags.isNotEmpty()) TagRow(tags)
        val meta = listOfNotNull(
            "↩︎ ${thread.replyCount}",
            "♡︎ ${thread.likeCount}",
            Formatting.relativeTime(thread.repliedAt ?: thread.createdAt).takeIf { it.isNotEmpty() }?.let { "last reply $it" },
        ).joinToString("   ")
        BasicText(meta, style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp))
    }
}

private data class Tag(val text: String, val color: androidx.compose.ui.graphics.Color)

@Composable
private fun TagRow(tags: List<Tag>) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 5.dp),
    ) {
        tags.forEach { tag ->
            BasicText(
                tag.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = NumberStyle(color = tag.color, fontSize = 10.sp, bold = true),
                modifier = Modifier.border(1.dp, tag.color.copy(alpha = 0.7f), lookShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** One comment, flattened out of the reply tree with how deep it sits. */
private data class Shown(val comment: ThreadComment, val depth: Int)

private fun flatten(comments: List<ThreadComment>, depth: Int = 0): List<Shown> =
    comments.flatMap { listOf(Shown(it, depth)) + flatten(it.childComments.orEmpty(), depth + 1) }

/** Deepest a reply is drawn indented; deeper ones line up with it. */
private const val MAX_INDENT = 4

@Composable
internal fun ThreadPage(route: Route.Thread, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val loggedIn = ui.session.state is SessionState.LoggedIn
    val thread = retained.get("thread") { ui.load<ForumThread> { ui.api.thread(route.id, it) } }
    val comments = retained.get("comments") { ui.paged<ThreadComment>({ it.id }) { page -> ui.api.threadComments(route.id, page) } }
    val list = retained.get("list") { LazyListState() }
    val likes = remember { mutableStateMapOf<Int, Pair<Boolean, Int>>() }
    var subscribed by remember { mutableStateOf<Boolean?>(null) }
    var text by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<ThreadComment?>(null) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    LoadMoreList(list, comments)

    fun like(id: Int, isComment: Boolean, now: Pair<Boolean, Int>) {
        likes[id] = !now.first to (now.second + if (now.first) -1 else 1)
        ui.scope.launch {
            try {
                val r = ui.api.toggleThreadLike(id, isComment)
                likes[id] = r.isLiked to r.likeCount
            } catch (e: AniListException) {
                likes[id] = now
                context.showToast(ui.describe(e))
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        BackHeader("Thread") { ui.navigator.back() }
        val t = thread.value
        if (t == null) {
            if (thread.error != null) ErrorRow(thread.error!!) { thread.load() } else Loading()
            return@Column
        }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f)) {
            item(key = "op") {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .clip(lookShape(14.dp))
                        .background(theme.surface.copy(alpha = 0.9f))
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        t.user?.let { u -> Avatar(u.avatar, 28, Modifier.clickable { ui.navigator.open(Route.User(u.id)) }) }
                        Spacer(Modifier.width(8.dp))
                        BasicText(
                            listOfNotNull(t.user?.name, Formatting.relativeTime(t.createdAt), "%,d views".format(t.viewCount)).joinToString(" · "),
                            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    BasicText(t.title.trim(), style = TitleStyle(color = theme.foreground, fontSize = 18.sp, lineHeight = 23.sp))
                    if (!t.mediaCategories.isNullOrEmpty()) {
                        TagRow(t.mediaCategories.map { Tag(it.title.display, theme.primary) })
                    }
                    Spacer(Modifier.height(6.dp))
                    ForumBody(t.body)
                    Spacer(Modifier.height(10.dp))
                    val like = likes[-t.id] ?: (t.isLiked to t.likeCount)
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ActionText((if (like.first) "♥︎ " else "♡︎ ") + like.second, like.first, loggedIn) {
                            // Thread likes are kept under the negative id, so they never mix with a comment's.
                            likes[-t.id] = like
                            likes[-t.id] = !like.first to (like.second + if (like.first) -1 else 1)
                            ui.scope.launch {
                                try {
                                    val r = ui.api.toggleThreadLike(t.id, comment = false)
                                    likes[-t.id] = r.isLiked to r.likeCount
                                } catch (e: AniListException) {
                                    likes[-t.id] = like
                                    context.showToast(ui.describe(e))
                                }
                            }
                        }
                        val on = subscribed ?: t.isSubscribed
                        ActionText(if (on) "Subscribed" else "Subscribe", on, loggedIn) {
                            ui.scope.launch {
                                try {
                                    subscribed = ui.api.subscribe(t.id, !on)
                                } catch (e: AniListException) {
                                    context.showToast(ui.describe(e))
                                }
                            }
                        }
                        ActionText("⇪ Share", false, true) { context.shareText("${t.title.trim()}\nhttps://anilist.co/forum/thread/${t.id}") }
                        ActionText("AniList ↗", false, true) { context.openLink("https://anilist.co/forum/thread/${t.id}") }
                    }
                }
            }
            item(key = "count") {
                SectionTitle("Comments", t.replyCount.takeIf { it > 0 })
            }
            val shown = flatten(comments.items)
            items(shown, key = { it.comment.id }) { (c, depth) ->
                val like = likes[c.id] ?: (c.isLiked to c.likeCount)
                CommentRow(
                    comment = c,
                    depth = depth,
                    highlighted = c.id == route.focusComment,
                    liked = like.first,
                    likes = like.second,
                    canAct = loggedIn && !t.isLocked,
                    onLike = { like(c.id, true, like) },
                    onReply = { replyTo = c },
                )
            }
            item(key = "end") {
                when {
                    comments.error != null -> ErrorRow(comments.error!!) { comments.retry() }
                    comments.loading -> Loading()
                    comments.items.isEmpty() -> Empty("No comments yet")
                }
            }
        }
        when {
            t.isLocked -> Hint("This thread is locked", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            !loggedIn -> Hint("Log in to comment", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            else -> Column(Modifier.background(theme.background.copy(alpha = 0.92f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                replyTo?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(
                            "Replying to ${r.user?.name.orEmpty()}", modifier = Modifier.weight(1f),
                            style = TextStyle(color = theme.primary, fontSize = 12.sp),
                        )
                        BasicText("✕", style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.clickable { replyTo = null }.padding(6.dp))
                    }
                }
                sendError?.let { BasicText(it, style = TextStyle(color = theme.error, fontSize = 12.sp)) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InputBox(text, { text = it }, "Write a comment", Modifier.weight(1f), maxLines = 5)
                    FilterChip("||", false) { text = wrapSpoiler(text) }
                    FilterChip(if (sending) "…" else "Send", text.isNotBlank()) {
                        if (text.isBlank() || sending) return@FilterChip
                        sending = true
                        sendError = null
                        ui.scope.launch {
                            try {
                                ui.api.comment(t.id, text.trim(), replyTo?.id)
                                text = ""
                                replyTo = null
                                comments.restart { page -> ui.api.threadComments(t.id, page, refresh = true) }
                                thread.load(refresh = true)
                            } catch (e: AniListException) {
                                sendError = ui.describe(e)
                            } finally {
                                sending = false
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The `||` button: hides the whole text as a spoiler, or the last line when there are several. */
internal fun wrapSpoiler(text: String): String {
    if (text.isBlank()) return "~! !~"
    val lines = text.trimEnd().split("\n")
    val last = lines.last()
    if (last.startsWith("~!") && last.endsWith("!~")) return text
    return (lines.dropLast(1) + "~!$last!~").joinToString("\n")
}

@Composable
private fun ActionText(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    BasicText(
        text,
        style = NumberStyle(color = if (on) theme.accent else theme.foreground.copy(alpha = if (enabled) 0.7f else 0.4f), fontSize = 12.sp, bold = true),
        modifier = Modifier.clip(lookShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 2.dp, vertical = 4.dp),
    )
}

@Composable
private fun CommentRow(
    comment: ThreadComment,
    depth: Int,
    highlighted: Boolean,
    liked: Boolean,
    likes: Int,
    canAct: Boolean,
    onLike: () -> Unit,
    onReply: () -> Unit,
) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val indent = (minOf(depth, MAX_INDENT) * 14).dp
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (highlighted) theme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(start = 16.dp + indent, end = 16.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (depth > 0) {
            Box(Modifier.width(2.dp).heightIn(min = 40.dp).background(theme.foreground.copy(alpha = 0.18f)))
            Spacer(Modifier.width(8.dp))
        }
        comment.user?.let { u -> Avatar(u.avatar, 26, Modifier.clickable { ui.navigator.open(Route.User(u.id)) }) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(comment.user?.name.orEmpty(), style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                BasicText("  " + Formatting.relativeTime(comment.createdAt), style = TextStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 11.sp))
            }
            ForumBody(comment.comment, small = true)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionText((if (liked) "♥︎ " else "♡︎ ") + likes, liked, canAct, onLike)
                if (canAct) ActionText("Reply", false, true, onReply)
            }
        }
    }
}

/** Forum text: paragraphs, pictures, quotes, and spoilers that open when tapped. */
@Composable
internal fun ForumBody(source: String?, small: Boolean = false) {
    val blocks = remember(source) { ForumText.parse(source) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { ForumBlock(it, small) }
    }
}

@Composable
private fun ForumBlock(block: ForumText.Block, small: Boolean) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val size = if (small) 13.sp else 14.sp
    when (block) {
        is ForumText.Block.Text -> BasicText(
            spansText(block.spans, theme.primary),
            style = (if (block.heading) TitleStyle(color = theme.foreground, fontSize = 16.sp) else TextStyle(color = theme.foreground, fontSize = size, lineHeight = size * 1.42f))
                .copy(textAlign = if (block.centered) TextAlign.Center else TextAlign.Start),
            modifier = Modifier.fillMaxWidth(),
        )
        is ForumText.Block.Image -> AsyncImage(
            model = block.url,
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).clip(lookShape(8.dp)).clickable { context.openLink(block.url) },
        )
        is ForumText.Block.Video -> BasicText(
            "▶ Video",
            style = NumberStyle(color = theme.primary, fontSize = 13.sp, bold = true),
            modifier = Modifier.clip(lookShape(8.dp)).border(1.dp, theme.primary.copy(alpha = 0.6f), lookShape(8.dp)).clickable { context.openLink(block.url) }.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        is ForumText.Block.Spoiler -> {
            var open by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(lookShape(8.dp))
                    .border(1.dp, theme.accent.copy(alpha = 0.7f), lookShape(8.dp))
                    .background(theme.surface.copy(alpha = 0.6f))
                    .clickable { open = !open }
                    .padding(10.dp),
            ) {
                BasicText(
                    if (open) "▾ Spoiler" else "▸ Spoiler · tap to show",
                    style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true),
                )
                if (open) {
                    Spacer(Modifier.height(6.dp))
                    block.blocks.forEach { ForumBlock(it, small) }
                }
            }
        }
        is ForumText.Block.Quote -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(3.dp).heightIn(min = 20.dp).background(theme.foreground.copy(alpha = 0.3f)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.blocks.forEach { ForumBlock(it, small = true) }
            }
        }
        ForumText.Block.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(theme.foreground.copy(alpha = 0.2f)))
    }
}

private fun spansText(spans: List<ForumText.Span>, link: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    spans.forEach { s ->
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.Bold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            textDecoration = if (s.strike) TextDecoration.LineThrough else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
        )
        if (s.link != null) {
            withLink(LinkAnnotation.Url(s.link, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(s.text) }
            }
        } else {
            withStyle(style) { append(s.text) }
        }
    }
}

/** All of a title's threads, newest replies first. */
@Composable
internal fun MediaThreadsPage(mediaId: Int, retained: Retained) {
    val ui = LocalAniList.current
    val loggedIn = ui.session.state is SessionState.LoggedIn
    val threads = retained.get("threads") { ui.paged<ForumThread>({ it.id }) { page -> ui.api.threads(page, ThreadQuery(mediaId = mediaId)) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Discussions") { ui.navigator.back() }
        if (loggedIn) {
            Box(Modifier.padding(start = 20.dp, bottom = 6.dp)) {
                FilterChip("+ New thread", false) { ui.navigator.open(Route.NewThread(mediaId)) }
            }
        }
        ThreadList(retained, threads)
    }
}

/** A title's release threads, read for the episode or chapter each one is about. */
internal suspend fun releaseThreads(api: AniListApi, mediaId: Int, refresh: Boolean = false): List<ForumThread> =
    api.threads(1, ThreadQuery(category = ForumCategories.RELEASE_DISCUSSION, mediaId = mediaId, sort = "ID_DESC"), refresh).value.items

/** The discussion thread for one episode or chapter, if anyone started one. */
internal suspend fun episodeThread(api: AniListApi, mediaId: Int, number: Int, chapter: Boolean): ForumThread? {
    val recent = runCatching { releaseThreads(api, mediaId) }.getOrNull().orEmpty()
    EpisodeThreads.find(recent, number, chapter)?.let { return it }
    val word = if (chapter) "Chapter" else "Episode"
    val searched = runCatching {
        api.threads(1, ThreadQuery(mediaId = mediaId, search = "$word $number")).value.items
    }.getOrNull().orEmpty()
    return EpisodeThreads.find(searched, number, chapter)
}

/** On a title's page: its episode (or chapter) threads, then a way to all of them. */
@Composable
internal fun MediaDiscussions(media: Media) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val chapter = media.kind != MediaKind.Anime
    val load = remember(media.id) { ui.load<List<ForumThread>> { refresh -> Fetched(releaseThreads(ui.api, media.id, refresh), false) } }
    val entry = ui.entryFor(media.id)
    val seen = when {
        entry == null -> null
        entry.status == ListStatus.COMPLETED -> Int.MAX_VALUE
        else -> entry.progress
    }
    var warn by remember { mutableStateOf<Pair<ForumThread, Int>?>(null) }
    SectionTitle("Discussions")
    val numbered = load.value.orEmpty()
        .mapNotNull { t -> EpisodeThreads.numbers(t.title, chapter)?.let { t to it.first } }
        .distinctBy { it.second }
        .sortedByDescending { it.second }
    // The newest you've seen first, with the one just past it (dimmed), then older ones.
    val shown = if (seen == null) numbered.take(3) else {
        val next = numbered.filter { it.second == seen + 1 }
        val upTo = numbered.filter { it.second <= seen }.take(3)
        next + upTo
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { (t, n) ->
            val ahead = seen != null && n > seen
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(lookShape(10.dp))
                    .background(theme.surface.copy(alpha = if (ahead) 0.45f else 0.85f))
                    .clickable { if (ahead) warn = t to (seen ?: 0) else ui.navigator.open(Route.Thread(t.id)) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicText(
                    (if (chapter) "Ch " else "Ep ") + n,
                    style = NumberStyle(color = theme.primary.copy(alpha = if (ahead) 0.6f else 1f), fontSize = 13.sp, bold = true),
                    modifier = Modifier.width(62.dp),
                )
                BasicText(
                    "${if (chapter) "Chapter" else "Episode"} $n discussion", modifier = Modifier.weight(1f),
                    style = TextStyle(color = theme.foreground.copy(alpha = if (ahead) 0.6f else 1f), fontSize = 13.sp),
                )
                BasicText(
                    if (ahead) "ahead of you" else "↩︎ ${t.replyCount}",
                    style = NumberStyle(color = if (ahead) theme.error else theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp),
                )
            }
        }
        if (load.value != null && shown.isEmpty()) Hint("No ${if (chapter) "chapter" else "episode"} threads yet")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("All threads", false) { ui.navigator.open(Route.MediaThreads(media.id)) }
            if (ui.session.state is SessionState.LoggedIn) FilterChip("+ New thread", false) { ui.navigator.open(Route.NewThread(media.id)) }
        }
    }
    warn?.let { (t, at) ->
        ConfirmDialog(
            title = "Ahead of you",
            message = "You're on ${if (chapter) "chapter" else "episode"} $at. This thread is about a later one and will have spoilers.",
            confirmLabel = "Open anyway",
            onConfirm = {
                warn = null
                ui.navigator.open(Route.Thread(t.id))
            },
            onDismiss = { warn = null },
        )
    }
}

/** "💬 Discuss ep 7 · 132": opens that episode's (or chapter's) thread in the Forum tab, closing what's open. */
@Composable
internal fun DiscussPill(number: Int, thread: ForumThread, chapter: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(
        "\uD83D\uDCAC Discuss ${if (chapter) "ch" else "ep"} $number · ${thread.replyCount}",
        onClick = {
            onOpen()
            AniListLinkRequest.route = Route.Thread(thread.id)
        },
        modifier = modifier,
    )
}

/** How recent the last episode or chapter must be for Home to offer its discussion. */
private const val DISCUSS_RECENT_MS = 48L * 60 * 60 * 1000

/** Under Home's up next: the thread about the episode or chapter you just finished, when there is one. */
@Composable
internal fun RecentDiscussion(items: List<com.aniliphy.widget.WidgetItem>) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val now = remember { System.currentTimeMillis() }
    val item = items.firstOrNull {
        (it.kind == com.aniliphy.widget.WidgetKind.Anime || it.kind == com.aniliphy.widget.WidgetKind.Manga) && now - it.updated < DISCUSS_RECENT_MS
    } ?: return
    val mediaId = item.key.toIntOrNull() ?: return
    val number = ui.entryFor(mediaId)?.progress?.takeIf { it > 0 } ?: return
    val chapter = item.kind == com.aniliphy.widget.WidgetKind.Manga
    var thread by remember(mediaId, number) { mutableStateOf<ForumThread?>(null) }
    LaunchedEffect(mediaId, number) { thread = episodeThread(ui.api, mediaId, number, chapter) }
    val t = thread ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.85f))
            .clickable { ui.navigator.open(Route.Thread(t.id)) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicText(
            "${if (chapter) "Ch" else "Ep"} $number", modifier = Modifier.width(58.dp),
            style = NumberStyle(color = theme.primary, fontSize = 13.sp, bold = true),
        )
        BasicText(
            item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            style = TextStyle(color = theme.foreground, fontSize = 13.sp),
        )
        BasicText(
            "\uD83D\uDCAC Discuss · ${t.replyCount}",
            style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true),
        )
    }
}

