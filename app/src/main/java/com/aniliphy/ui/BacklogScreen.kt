package com.aniliphy.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Backlog
import com.aniliphy.anilist.BacklogPrefs
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.PendingWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/** Changes an entry's status, through the same queue as the list editor. */
private suspend fun AniListUi.setStatus(entry: ListEntry, status: String): Boolean = try {
    val m = entry.media
    val before = com.aniliphy.anilist.ListProgress(
        entryId = entry.id, progress = entry.progress, progressVolumes = entry.progressVolumes, status = entry.status,
        score = entry.score.takeIf { it > 0 }, repeat = entry.repeat, updatedAt = entry.updatedAt,
    )
    sendOrQueue({
        PendingWrite.Entry(m.id, m.title.display, m.kind == MediaKind.Anime, status, entry.progress, entry.progressVolumes, null, entry.repeat, seenUpdatedAt = entry.updatedAt, before = before)
    }) { api.saveEntry(m.id, status, entry.progress, entry.progressVolumes) }
    LookMoment.status(ListStatus.label(status, m.kind), false, m.title.display)
    navigator.listsChanged()
    true
} catch (e: AniListException) {
    context?.showToast(describe(e))
    false
}

/** On Home: the title that's been still the longest, with what to do about it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StalledHomeCard() {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { BacklogPrefs.get(context) }
    if (ui.session.userId == null || prefs.weeks <= 0) return
    var skipped by remember { mutableStateOf(setOf<Int>()) }
    val now = Formatting.nowSeconds()
    val stalled = Backlog.stalled(ui.onList.entries.values.toList(), now, prefs.weeks, prefs.notNow)
        .filter { it.media.id !in skipped && it.media.id !in ui.removals.hidden }
    val e = stalled.firstOrNull() ?: return
    val m = e.media
    val unit = if (m.kind == MediaKind.Anime) "Episode" else "Chapter"
    val total = if (m.kind == MediaKind.Anime) m.episodes else m.chapters
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.warning.copy(alpha = 0.08f))
            .border(1.5.dp, theme.warning, lookShape(12.dp))
            .padding(12.dp),
    ) {
        BasicText(
            "${m.title.display} hasn't moved in ${Backlog.weeksIdle(Backlog.lastMoved(e) ?: now, now)} weeks",
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.clickable { ui.navigator.open(Route.Media(m.id)) },
        )
        BasicText(
            "$unit ${e.progress}" + (total?.let { " of $it" } ?: "") + (if (stalled.size > 1) " · ${stalled.size - 1} more stalled" else ""),
            style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Continue", onClick = { ui.navigator.open(Route.Media(m.id)) })
            PillButton("Paused", filled = false, onClick = { scope.launch { if (ui.setStatus(e, ListStatus.PAUSED)) skipped = skipped + m.id } })
            PillButton("Dropped", filled = false, onClick = { scope.launch { if (ui.setStatus(e, ListStatus.DROPPED)) skipped = skipped + m.id } })
            PillButton("Not now", filled = false, onClick = {
                prefs.notNow(m.id, now)
                skipped = skipped + m.id
            })
        }
        if (stalled.size > 1) {
            BasicText("See the backlog ›", style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 8.dp).clickable { ui.navigator.open(Route.Backlog) })
        }
    }
}

/** Stalled titles, tidying Planning by swiping, and when what's going will be finished. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BacklogPage(retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { BacklogPrefs.get(context) }
    val kind: MutableState<MediaKind> = retained.get("kind") { mutableStateOf(MediaKind.Anime) }
    val kept: MutableState<Set<Int>> = retained.get("kept") { mutableStateOf(emptySet()) }
    val removed: MutableState<Int> = retained.get("removed") { mutableStateOf(0) }
    val now = Formatting.nowSeconds()
    val userId = ui.session.userId

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Backlog") { ui.navigator.back() }
        if (userId == null) {
            LoginInvite("Log in to sort out your backlog")
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
            MediaKind.entries.forEach { k -> FilterChip(Formatting.kindLabel(k), kind.value == k) { kind.value = k } }
        }
        val all = ui.onList.entries.values.filter { it.media.kind == kind.value && it.media.id !in ui.removals.hidden }

        // Stalled
        val stalled = Backlog.stalled(all, now, prefs.weeks)
        SectionTitle("Stalled")
        if (prefs.weeks <= 0) Hint("Off. Choose after how long below.")
        else if (stalled.isEmpty()) Empty("Nothing has stood still for ${prefs.weeks} weeks")
        stalled.forEach { e ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { ui.navigator.open(Route.Media(e.media.id)) }.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Cover(e.media.coverImage.best, Modifier.size(width = 40.dp, height = 56.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(e.media.title.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    BasicText("${Backlog.weeksIdle(Backlog.lastMoved(e) ?: now, now)} weeks still · at ${e.progress}", style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
                }
                FilterChip("Paused", false) { scope.launch { ui.setStatus(e, ListStatus.PAUSED) } }
                Spacer(Modifier.width(6.dp))
                FilterChip("Dropped", false) { scope.launch { ui.setStatus(e, ListStatus.DROPPED) } }
            }
        }
        SectionTitle("Stalled after")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
            BacklogPrefs.WEEKS.forEach { w -> FilterChip(if (w == 0) "Never" else "$w weeks", prefs.weeks == w) { prefs.weeks = w } }
        }
        SwitchRow(
            "\"Where you left off\" card", prefs.recap, { prefs.recap = it },
            description = "On the title's page when you open one that's stalled: characters, synopsis and the last threads you saw",
        )

        // Tidy Planning
        val planning = all.filter { it.status == ListStatus.PLANNING && it.media.id !in kept.value }.sortedBy { it.createdAt ?: 0 }
        SectionTitle("Tidy Planning")
        val planningTotal = all.count { it.status == ListStatus.PLANNING }
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Counter(planningTotal, "in Planning")
            Counter(kept.value.size, "kept")
            Counter(removed.value, "removed")
        }
        val top = planning.firstOrNull()
        if (top == null) {
            Empty(if (planningTotal == 0) "Planning is empty" else "You've been through all of Planning")
            if (kept.value.isNotEmpty()) Box(Modifier.padding(horizontal = 20.dp)) { FilterChip("Start over", false) { kept.value = emptySet() } }
        } else {
            fun keep() { kept.value = kept.value + top.media.id }
            fun remove() {
                ui.removals.remove(top.id, top.media.id, top.media.title.display)
                removed.value++
            }
            SwipeCard(top, planning.getOrNull(1), onLeft = ::remove, onRight = ::keep)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                PillButton("← Remove", onClick = ::remove, filled = false, color = theme.error)
                PillButton(if (kind.value == MediaKind.Anime) "Watch now" else "Read now", onClick = { ui.navigator.open(Route.Media(top.media.id)) })
                PillButton("Keep →", onClick = ::keep, filled = false)
            }
            Hint("Swipe left to remove (with Undo), right to keep. Oldest on Planning first.")
        }

        // When you'll finish
        SectionTitle("When you'll finish")
        val pace by produceState<Map<Int, Double>?>(null, userId) {
            value = withContext(Dispatchers.Default) {
                val from = now - Backlog.PACE_DAYS * Backlog.DAY_S
                val acts = mutableListOf<com.aniliphy.anilist.Activity>()
                try {
                    for (page in 1..PACE_PAGES) {
                        val p = ui.api.activities(userId, page, from = from).value
                        acts += p.items
                        if (!p.hasNext) break
                    }
                } catch (_: AniListException) {
                }
                Backlog.pace(acts, now)
            }
        }
        val going = Backlog.finishing(all, pace.orEmpty())
        when {
            pace == null -> Loading()
            going.isEmpty() -> Empty("Nothing going right now")
            else -> going.forEach { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { ui.navigator.open(Route.Media(f.entry.media.id)) }.padding(horizontal = 20.dp, vertical = 7.dp),
                ) {
                    val unit = if (f.entry.media.kind == MediaKind.Anime) "eps" else "ch"
                    BasicText(
                        f.entry.media.title.display + (f.left?.let { " · $it $unit left" } ?: ""),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        style = TextStyle(color = theme.foreground, fontSize = 14.sp),
                    )
                    BasicText(
                        when {
                            f.left == 0 -> "done"
                            f.days == null -> if (f.left == null) "no end yet" else "no pace yet"
                            else -> java.time.LocalDate.now().plusDays(f.days.toLong()).let { d ->
                                "~ " + d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == java.time.Year.now().value) "d MMM" else "d MMM yyyy", java.util.Locale.ENGLISH))
                            }
                        },
                        style = NumberStyle(color = if (f.days != null) theme.primary else theme.foreground.copy(alpha = 0.55f), fontSize = 12.sp, bold = f.days != null),
                    )
                }
            }
        }
        Hint("From your pace on each title over the last ${Backlog.PACE_DAYS} days.")
    }
}

@Composable
private fun Counter(n: Int, label: String) {
    val theme = LocalAppTheme.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("$n", style = NumberStyle(color = theme.foreground, fontSize = 18.sp, bold = true))
        BasicText(label, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
    }
}

/** The top Planning title as a card to throw left (remove) or right (keep), the next one under it. */
@Composable
private fun SwipeCard(top: ListEntry, next: ListEntry?, onLeft: () -> Unit, onRight: () -> Unit) {
    val theme = LocalAppTheme.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dx = remember(top.media.id) { Animatable(0f) }
    Box(Modifier.fillMaxWidth().height(260.dp).padding(horizontal = 40.dp, vertical = 8.dp)) {
        next?.let { n ->
            Box(Modifier.fillMaxSize().padding(top = 10.dp, start = 10.dp).clip(lookShape(12.dp)).background(theme.surface)) {
                Cover(n.media.coverImage.best, Modifier.fillMaxSize().graphicsLayer { alpha = 0.35f })
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .offset { IntOffset(dx.value.roundToInt(), 0) }
                .graphicsLayer { rotationZ = dx.value / 40f }
                .clip(lookShape(12.dp))
                .background(theme.surface)
                .border(1.5.dp, theme.primary, lookShape(12.dp))
                .pointerInput(top.media.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val limit = size.width * 0.3f
                            scope.launch {
                                when {
                                    dx.value < -limit -> { dx.animateTo(-size.width * 1.5f); LookHaptics.tab(view, Look.style); LookSounds.tap(); onLeft() }
                                    dx.value > limit -> { dx.animateTo(size.width * 1.5f); LookHaptics.tab(view, Look.style); LookSounds.tap(); onRight() }
                                    else -> dx.animateTo(0f)
                                }
                            }
                        },
                    ) { _, amount -> scope.launch { dx.snapTo(dx.value + amount) } }
                },
        ) {
            Cover(top.media.coverImage.best, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(theme.background.copy(alpha = 0.82f)).padding(10.dp)) {
                val m = top.media
                BasicText(m.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold))
                val since = top.createdAt?.takeIf { it > 0 }?.let {
                    "added " + java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", java.util.Locale.ENGLISH))
                }
                BasicText(
                    listOfNotNull(Formatting.format(m.format), m.episodes?.let { "$it eps" } ?: m.chapters?.let { "$it ch" }, since, m.averageScore?.let { "★ $it%" }).joinToString(" · "),
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp),
                )
            }
            val tag = when {
                dx.value < -40f -> "REMOVE" to theme.error
                dx.value > 40f -> "KEEP" to theme.primary
                else -> null
            }
            tag?.let { (label, color) ->
                BasicText(
                    label,
                    style = NumberStyle(color = color, fontSize = 16.sp, bold = true),
                    modifier = Modifier.align(if (label == "KEEP") Alignment.TopStart else Alignment.TopEnd).padding(12.dp)
                        .graphicsLayer { rotationZ = if (label == "KEEP") -10f else 10f; alpha = (abs(dx.value) / 160f).coerceIn(0f, 1f) }
                        .border(2.dp, color, lookShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private const val PACE_PAGES = 3
