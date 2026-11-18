package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Formatting
import coil3.compose.AsyncImage
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Backlog
import com.aniliphy.anilist.BacklogPrefs
import com.aniliphy.anilist.FeedPost
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.ForumThread
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaExtras
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Review
import com.aniliphy.anilist.SpoilerText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Trailer and where to watch or read it officially, under the synopsis. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaTrailerAndLinks(media: Media, extras: MediaExtras?) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    extras ?: return
    extras.trailer?.let { trailer ->
        val url = trailer.url ?: return@let
        SectionTitle("Trailer")
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(lookShape(10.dp))
                .background(theme.surface)
                .clickable { context.openLink(url) },
        ) {
            trailer.thumbnail?.let { AsyncImage(it, contentDescription = "Trailer", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(58.dp).clip(lookShape(10.dp)).background(theme.background.copy(alpha = 0.75f)).border(2.dp, theme.primary, lookShape(10.dp)),
            ) {
                BasicText("▶", style = TextStyle(color = theme.primary, fontSize = 24.sp))
            }
            BasicText(
                (trailer.site ?: "Video").replaceFirstChar { it.uppercase() } + " · opens outside the app",
                style = TextStyle(color = Color.White, fontSize = 12.sp),
                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    val links = extras.sortedLinks
    if (links.isNotEmpty()) {
        SectionTitle(if (media.kind == MediaKind.Anime) "Where to watch" else "Where to read")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            links.forEach { link ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(lookShape(8.dp))
                        .border(1.dp, theme.foreground.copy(alpha = 0.3f), lookShape(8.dp))
                        .clickable { context.openLink(link.url) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Box(Modifier.size(9.dp).background(parseColor(link.color) ?: theme.primary))
                    Spacer(Modifier.width(6.dp))
                    BasicText(
                        link.site + (link.language?.takeIf { it != "English" && it != "Japanese" }?.let { " · $it" } ?: ""),
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                    )
                }
            }
        }
    }
    val free = extras.episodes.filter { !it.url.isNullOrBlank() }
    if (free.isNotEmpty()) {
        val site = free.first().site ?: "the site"
        Hint("Free to watch on $site: ${free.size} episode${if (free.size == 1) "" else "s"}", Modifier.padding(top = 2.dp))
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(free.size) { i ->
                val e = free[i]
                FilterChip(e.title?.substringBefore(" - ")?.take(24) ?: "Episode ${i + 1}", false) { e.url?.let(context::openLink) }
            }
        }
    }
}

/** The top reviews, with spoiler parts hidden until tapped. */
@Composable
internal fun MediaReviews(media: Media, extras: MediaExtras?) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val reviews = extras?.reviews.orEmpty()
    if (reviews.isEmpty()) return
    SectionTitle("Reviews")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        reviews.forEach { r ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(lookShape(10.dp))
                    .background(theme.surface.copy(alpha = 0.85f))
                    .clickable { ui.navigator.open(com.aniliphy.ui.Route.Review(r.id)) }
                    .padding(12.dp),
            ) {
                ReviewHead(r)
                r.summary?.takeIf { it.isNotBlank() }?.let {
                    SpoilerParagraph(it, Modifier.padding(top = 6.dp), maxLines = 4)
                }
                BasicText("Read the whole review ›", style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    Hint("Reviews are written by AniList users. Spoiler parts stay hidden until you tap them.")
}

@Composable
private fun ReviewHead(r: Review) {
    val theme = LocalAppTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("by ${r.user.name}", style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
        val useful = r.rating?.let { liked -> r.ratingAmount?.takeIf { it > 0 }?.let { "$liked/$it found it useful" } }
        BasicText(
            listOfNotNull(r.score?.let { "$it/100" }, useful).joinToString(" · "),
            style = NumberStyle(color = theme.primary, fontSize = 11.sp),
        )
    }
}

/** Text with ~!spoiler!~ parts covered; tapping uncovers them all. */
@Composable
internal fun SpoilerParagraph(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val theme = LocalAppTheme.current
    var shown by remember(text) { mutableStateOf(false) }
    val parts = remember(text) { SpoilerText.parts(SpoilerText.clean(text)) }
    val hasSpoiler = parts.any { it.spoiler }
    val styled = buildAnnotatedString {
        parts.forEach { p ->
            if (p.spoiler && !shown) {
                withStyle(SpanStyle(color = theme.foreground.copy(alpha = 0f), background = theme.foreground.copy(alpha = 0.22f))) { append(p.text) }
            } else {
                append(p.text)
            }
        }
    }
    Column(modifier) {
        BasicText(
            styled,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 14.sp, lineHeight = 20.sp),
            modifier = if (hasSpoiler) Modifier.clickable { shown = !shown } else Modifier,
        )
        if (hasSpoiler && !shown) {
            BasicText("Spoilers hidden · tap to show", style = NumberStyle(color = theme.warning, fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** One review, whole. */
@Composable
internal fun ReviewPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val load = retained.get("review") { ui.load<Review> { ui.api.review(id) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Review") { ui.navigator.back() }
        val r = load.value
        when {
            r != null -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                ReviewHead(r)
                r.summary?.let { BasicText(it, style = TextStyle(color = LocalAppTheme.current.foreground.copy(alpha = 0.75f), fontSize = 14.sp, fontWeight = FontWeight.Medium), modifier = Modifier.padding(vertical = 8.dp)) }
                // Paragraph by paragraph, so a spoiler shown in one doesn't open all of them.
                r.body.orEmpty().split(Regex("""\n\s*\n""")).filter { it.isNotBlank() }.forEach { para ->
                    SpoilerParagraph(para, Modifier.padding(bottom = 10.dp))
                }
            }
            load.error != null -> ErrorRow(load.error!!) { load.load() }
            else -> Loading()
        }
    }
}

/** Loads a title's trailer, links and reviews once per page. */
@Composable
internal fun rememberExtras(mediaId: Int): MediaExtras? {
    val ui = LocalAniList.current
    val load = remember(mediaId) { ui.load<MediaExtras> { ui.api.extras(mediaId) } }
    return load.value
}

/**
 * "Where you left off": opening a title you're watching or reading that hasn't moved in the backlog's weeks.
 * The characters, the synopsis (already spoiler-free when it should be) and the threads of the last ones you saw.
 */
@Composable
internal fun RecapCard(media: Media, synopsis: String?, onContinue: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val prefs = remember { BacklogPrefs.get(context) }
    val entry = ui.entryFor(media.id) ?: return
    var closed by remember(media.id) { mutableStateOf(false) }
    val now = Formatting.nowSeconds()
    val at = Backlog.lastMoved(entry) ?: return
    val weeks = prefs.weeks
    if (closed || !prefs.recap || weeks <= 0 || entry.progress <= 0) return
    if (entry.status != ListStatus.CURRENT && entry.status != ListStatus.PAUSED) return
    if (now - at < weeks * 7 * Backlog.DAY_S) return
    val chapter = media.kind != MediaKind.Anime
    val unit = if (chapter) "chapter" else "episode"
    val threads by produceState<List<Pair<Int, ForumThread>>>(emptyList(), media.id, entry.progress) {
        value = withContext(Dispatchers.Default) {
            val recent = runCatching { releaseThreads(ui.api, media.id) }.getOrNull().orEmpty()
            listOf(entry.progress - 1, entry.progress).filter { it > 0 }.mapNotNull { n ->
                com.aniliphy.anilist.EpisodeThreads.find(recent, n, chapter)?.let { n to it }
            }
        }
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.surface)
            .border(1.5.dp, theme.accent, lookShape(12.dp))
            .padding(14.dp),
    ) {
        BasicText("Where you left off", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        val ago = Backlog.weeksIdle(at, now).let { w -> if (w >= 8) "${w / 4} months ago" else "$w weeks ago" }
        BasicText("${unit.replaceFirstChar { it.uppercase() }} ${entry.progress} · $ago", style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
        val people = media.characters.edges.filter { it.node != null && it.role == "MAIN" }.take(5)
            .ifEmpty { media.characters.edges.filter { it.node != null }.take(5) }
        if (people.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                people.forEach { edge ->
                    val node = edge.node!!
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(54.dp).clickable { ui.navigator.open(Route.Character(node.id)) }) {
                        AsyncImage(node.image?.best, contentDescription = node.name.full, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.size(46.dp).clip(CircleShape).background(theme.background))
                        BasicText(node.name.full?.substringBefore(' ') ?: "?", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 11.sp))
                    }
                }
            }
        }
        synopsis?.takeIf { it.isNotBlank() }?.let {
            BasicText(it, maxLines = 5, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp, lineHeight = 20.sp))
        }
        threads.forEach { (n, t) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { ui.navigator.open(Route.Thread(t.id)) }.padding(vertical = 6.dp),
            ) {
                BasicText((if (chapter) "CH " else "EP ") + n, style = NumberStyle(color = theme.primary, fontSize = 12.sp, bold = true), modifier = Modifier.width(52.dp))
                BasicText("${unit.replaceFirstChar { it.uppercase() }} $n discussion · ${t.replyCount} comments", style = TextStyle(color = theme.foreground, fontSize = 13.sp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            PillButton("Not now", onClick = { closed = true }, filled = false)
            PillButton("Continue ${if (chapter) "ch" else "ep"} ${entry.progress + 1}", onClick = onContinue, alive = true)
        }
    }
}

/**
 * Writing a post to your AniList feed, with a title attached when given. It asks once more before sending:
 * everyone who follows you sees it.
 */
@Composable
internal fun PostDialog(attach: FeedPost.Attached?, onDone: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var attached by remember { mutableStateOf(attach) }
    var confirming by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    val full = FeedPost.text(text, attached)
    DialogFrame(onDismiss = { if (!sending) onDone() }) {
        BasicText("Post to your AniList feed", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        InputBox(text, { text = it.take(FeedPost.MAX) }, "What's on your mind?", Modifier.fillMaxWidth().height(110.dp))
        attached?.let { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(lookShape(8.dp)).border(1.dp, theme.primary, lookShape(8.dp)).padding(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(a.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                    BasicText("Attached: " + a.line().lines().first().removePrefix("— "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
                }
                BasicText("✕", style = TextStyle(color = theme.error, fontSize = 16.sp), modifier = Modifier.clickable { attached = null }.padding(8.dp))
            }
        }
        if (confirming) {
            BasicText(
                "Post this? Everyone who follows you will see it.",
                style = TextStyle(color = theme.warning, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
        } else {
            Hint("Everyone who follows you sees it. It asks once more before sending.", Modifier.padding(0.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            PillButton(if (confirming) "Back" else "Cancel", onClick = { if (confirming) confirming = false else onDone() }, filled = false, enabled = !sending)
            PillButton(
                when {
                    sending -> "Posting…"
                    confirming -> "Yes, post it"
                    else -> "Post…"
                },
                enabled = text.isNotBlank() && !sending,
                onClick = {
                    if (!confirming) {
                        confirming = true
                        return@PillButton
                    }
                    sending = true
                    scope.launch {
                        try {
                            ui.api.postText(full)
                            context.showToast("Posted to your feed")
                            onDone()
                        } catch (e: AniListException) {
                            context.showToast(ui.describe(e))
                            sending = false
                        }
                    }
                },
            )
        }
    }
}

private fun parseColor(hex: String?): Color? = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
