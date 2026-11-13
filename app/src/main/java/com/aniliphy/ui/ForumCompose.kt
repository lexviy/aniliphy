package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.ForumCategories
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.UserCard
import kotlinx.coroutines.launch

/** Categories anyone can post in (announcements and the like are left out). */
private val POSTABLE = ForumCategories.all.filter { it.id !in setOf(11, 12, 13) }

/** Starting a thread: title, categories, the title it's about, and the text with a preview. */
@Composable
internal fun NewThreadPage(mediaId: Int?, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val title: MutableState<String> = retained.get("title") { mutableStateOf("") }
    val body: MutableState<String> = retained.get("body") { mutableStateOf("") }
    val categories: MutableState<Set<Int>> = retained.get("categories") { mutableStateOf(emptySet()) }
    val about: MutableState<Int?> = retained.get("about") { mutableStateOf(mediaId) }
    val media = retained.get("media") { ui.load<Media?>(autoStart = mediaId != null) { r -> mediaId?.let { id -> ui.api.media(id).map<Media?> { it } } ?: com.aniliphy.anilist.Fetched(null, false) } }
    var preview by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A title's page starts the thread in its kind's category.
    val m = media.value
    if (m != null && categories.value.isEmpty() && retained.get("seeded") { mutableStateOf(false) }.value.not()) {
        retained.get("seeded") { mutableStateOf(false) }.value = true
        categories.value = setOf(if (m.kind == MediaKind.Anime) ForumCategories.ANIME else ForumCategories.MANGA)
    }
    val ready = title.value.isNotBlank() && body.value.isNotBlank() && categories.value.isNotEmpty()

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        BackHeader("New thread") { ui.navigator.back() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FieldLabel("Title")
            InputBox(title.value, { title.value = it }, "What's it about?", Modifier.fillMaxWidth())
            FieldLabel("Categories")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                POSTABLE.forEach { c ->
                    FilterChip(c.name, c.id in categories.value) {
                        categories.value = if (c.id in categories.value) categories.value - c.id else categories.value + c.id
                    }
                }
            }
            if (m != null) {
                FieldLabel("About")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(m.title.display, about.value != null) { about.value = if (about.value == null) m.id else null }
                }
            }
            FieldLabel("Text")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip("B", false) { body.value += "**bold**" }
                FilterChip("I", false) { body.value += "_italic_" }
                FilterChip("|| spoiler", false) { body.value = wrapSpoiler(body.value) }
                FilterChip("Image", false) { body.value += "\nimg(https://)" }
                FilterChip("Preview", preview) { preview = !preview }
            }
            if (preview) {
                Column(Modifier.fillMaxWidth().clip(lookShape(12.dp)).background(theme.surface.copy(alpha = 0.85f)).padding(12.dp)) {
                    ForumBody(body.value)
                }
            } else {
                InputBox(body.value, { body.value = it }, "Write here. ~!text!~ hides a spoiler.", Modifier.fillMaxWidth(), maxLines = 14)
            }
            error?.let { BasicText(it, style = TextStyle(color = theme.error, fontSize = 13.sp)) }
            Spacer(Modifier.height(16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.End) {
            FilterChip("Cancel", false) { ui.navigator.back() }
            Spacer(Modifier.width(8.dp))
            FilterChip(if (posting) "Posting…" else "Post", ready) { if (ready && !posting) confirm = true }
        }
    }
    if (confirm) {
        val names = POSTABLE.filter { it.id in categories.value }.joinToString { it.name }
        ConfirmDialog(
            title = "Post this thread?",
            message = "“${title.value.trim()}” in $names" + (m?.takeIf { about.value != null }?.let { ", about ${it.title.display}" } ?: "") +
                ". It goes up on AniList's forum under your name.",
            confirmLabel = "Post",
            onConfirm = {
                confirm = false
                posting = true
                error = null
                ui.scope.launch {
                    try {
                        val id = ui.api.newThread(title.value.trim(), body.value.trim(), categories.value.toList(), listOfNotNull(about.value))
                        ui.navigator.back()
                        ui.navigator.open(Route.Thread(id))
                    } catch (e: AniListException) {
                        error = ui.describe(e)
                    } finally {
                        posting = false
                    }
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) {
    val theme = LocalAppTheme.current
    BasicText(text.uppercase(), style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp, bold = true))
}

/** Pick someone you follow and send them a title with a note, as a private AniList message. */
@Composable
internal fun RecommendPanel(media: Media, link: String, onDone: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewer = ui.session.userId ?: return Hint("Log in to send to a friend")
    val following = remember { ui.paged<UserCard>({ it.id }) { page -> ui.api.following(viewer, page) } }
    var picked by remember { mutableStateOf<UserCard?>(null) }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            following.items.isEmpty() && following.loading -> Loading()
            following.items.isEmpty() -> Hint("You don't follow anyone yet. Find people in the Social tab.")
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(following.items, key = { it.id }) { u ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(64.dp)
                            .clip(lookShape(10.dp))
                            .background(if (picked?.id == u.id) theme.primary.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { picked = u }
                            .padding(4.dp),
                    ) {
                        Avatar(u.avatar, 44)
                        BasicText(
                            u.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = theme.foreground, fontSize = 11.sp, fontWeight = if (picked?.id == u.id) FontWeight.Bold else null),
                        )
                    }
                }
            }
        }
        picked?.let { p ->
            InputBox(note, { note = it }, "A note for ${p.name}", Modifier.fillMaxWidth(), maxLines = 3)
            Hint("Sent as a private AniList message, with the title's link. Only ${p.name} can see it.")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilterChip(if (sending) "Sending…" else "Send", true) { if (!sending) confirm = true }
            }
            if (confirm) {
                val message = recommendText(media, note, link)
                ConfirmDialog(
                    title = "Send to ${p.name}?",
                    message = message,
                    confirmLabel = "Send",
                    onConfirm = {
                        confirm = false
                        sending = true
                        ui.scope.launch {
                            try {
                                ui.api.sendMessage(p.id, message)
                                context.showToast("Sent to ${p.name}")
                                onDone()
                            } catch (e: AniListException) {
                                context.showToast(ui.describe(e))
                            } finally {
                                sending = false
                            }
                        }
                    },
                    onDismiss = { confirm = false },
                )
            }
        }
    }
}

internal fun recommendText(media: Media, note: String, link: String): String =
    listOf(note.trim(), "Recommended: ${media.title.display}", link).filter { it.isNotEmpty() }.joinToString("\n")
