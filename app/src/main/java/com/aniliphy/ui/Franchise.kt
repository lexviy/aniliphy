package com.aniliphy.ui

import android.content.Context
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.FranchiseNode
import com.aniliphy.anilist.Franchises
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import kotlinx.coroutines.launch

/** Whether lists group franchises, and whether side stories join them. Kept between launches. */
internal object FranchisePrefs {
    private const val FILE = "lists"
    var grouped by mutableStateOf(false)
        private set
    var sideStories by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        grouped = prefs.getBoolean("group_franchises", false)
        sideStories = prefs.getBoolean("franchise_side_stories", false)
    }

    fun setGrouped(context: Context, value: Boolean) {
        grouped = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("group_franchises", value).apply()
    }

    fun setSideStories(context: Context, value: Boolean) {
        sideStories = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("franchise_side_stories", value).apply()
    }

    val relations: Set<String> get() = Franchises.relations(sideStories)
}

/** What a list shows: one title, or a franchise of several. */
internal sealed interface ListItem {
    val key: String

    data class Single(val entry: ListEntry) : ListItem {
        override val key get() = "m${entry.media.id}"
    }

    data class Group(val entries: List<ListEntry>) : ListItem {
        val lead: ListEntry get() = Franchises.lead(entries)
        override val key get() = "f${lead.media.id}"
    }
}

/** Progress over the whole franchise, like "36/48 ep" (totals only when every part's length is known). */
internal fun groupProgress(entries: List<ListEntry>, kind: MediaKind): String {
    val unit = when (kind) {
        MediaKind.Anime -> "ep"
        MediaKind.Manga -> "ch"
        MediaKind.LightNovel -> "vol"
    }
    fun done(e: ListEntry) = if (kind == MediaKind.LightNovel) e.progressVolumes.takeIf { it > 0 } ?: e.progress else e.progress
    fun total(e: ListEntry) = when (kind) {
        MediaKind.Anime -> e.media.episodes
        MediaKind.Manga -> e.media.chapters
        MediaKind.LightNovel -> e.media.volumes
    }
    val seen = entries.sumOf(::done)
    val totals = entries.map(::total)
    return if (totals.all { it != null }) "$seen/${totals.sumOf { it!! }} $unit" else "$seen $unit"
}

@Composable
internal fun FranchiseCard(group: ListItem.Group, kind: MediaKind, showStatus: Boolean) {
    val ui = LocalAniList.current
    val lead = group.lead
    val status = Franchises.combinedStatus(group.entries)
    CoverCard(
        title = lead.media.title.display,
        subtitle = groupProgress(group.entries, kind),
        detail = listOfNotNull(
            "⧉ ${group.entries.size} titles",
            ListStatus.label(status, kind).takeIf { showStatus && status != null },
        ).joinToString(" · "),
        url = lead.media.coverImage.best,
        onClick = { ui.navigator.open(Route.Franchise(lead.media.id)) },
    )
}

/** A franchise in watch order: what you've seen, what's next, and what isn't on your list yet. */
@Composable
internal fun FranchisePage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val sideStories = FranchisePrefs.sideStories
    val load = retained.get("franchise$sideStories") {
        ui.load { _ -> Fetched(ui.api.franchise(listOf(id), Franchises.relations(sideStories)), false) }
    }
    val list = retained.get("list") { LazyListState() }
    val adding = remember { mutableStateMapOf<Int, String>() }
    val nodes = load.value

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Franchise") { ui.navigator.back() }
        when {
            nodes == null && load.error != null -> ErrorRow(load.error!!) { load.load() }
            nodes == null -> Loading()
            else -> {
                val next = nodes.firstOrNull { node ->
                    val status = ui.entryFor(node.id)?.status
                    status != ListStatus.COMPLETED && status != ListStatus.DROPPED
                }
                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "head") {
                        val onList = nodes.count { ui.entryFor(it.id) != null }
                        val seen = nodes.count { ui.entryFor(it.id)?.status == ListStatus.COMPLETED }
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                            BasicText(
                                nodes.firstOrNull()?.title?.display.orEmpty(),
                                style = TitleStyle(color = theme.foreground, fontSize = 21.sp),
                            )
                            BasicText(
                                "${nodes.size} titles in watch order · $seen finished · ${nodes.size - onList} not on your list",
                                style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp),
                            )
                            if (nodes.size >= Franchises.WALK_LIMIT) {
                                Hint("A long franchise: only the first ${Franchises.WALK_LIMIT} titles are shown")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)) {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            FilterChip("With side stories", sideStories) { FranchisePrefs.setSideStories(context, !sideStories) }
                        }
                    }
                    itemsIndexed(nodes, key = { _, node -> node.id }) { index, node ->
                        FranchiseRow(
                            index = index + 1,
                            node = node,
                            next = node == next,
                            saving = adding[node.id],
                            onAdd = {
                                adding[node.id] = "Adding..."
                                ui.scope.launch {
                                    try {
                                        ui.api.saveEntry(node.id, ListStatus.PLANNING)
                                        adding.remove(node.id)
                                        ui.navigator.listsChanged()
                                    } catch (e: AniListException) {
                                        adding[node.id] = ui.describe(e)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FranchiseRow(index: Int, node: FranchiseNode, next: Boolean, saving: String?, onAdd: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val entry = ui.entryFor(node.id)
    val kind = if (node.type == "ANIME") MediaKind.Anime else if (node.format == "NOVEL") MediaKind.LightNovel else MediaKind.Manga
    val length = when (kind) {
        MediaKind.Anime -> node.episodes?.let { "$it ep" }
        MediaKind.Manga -> node.chapters?.let { "$it ch" }
        MediaKind.LightNovel -> node.volumes?.let { "$it vol" }
    }
    val meta = listOfNotNull(Formatting.format(node.format), node.startDate?.year?.toString(), length).joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(if (next) theme.primary.copy(alpha = 0.16f) else theme.surface.copy(alpha = if (entry == null) 0.45f else 0.82f))
            .clickable { ui.navigator.open(Route.Media(node.id)) }
            .padding(8.dp),
    ) {
        BasicText("%02d".format(index), style = NumberStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 12.sp, bold = true), modifier = Modifier.width(26.dp))
        Box(Modifier.size(width = 44.dp, height = 62.dp)) {
            Cover(node.coverImage.best, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                node.title.display,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground.copy(alpha = if (entry == null) 0.75f else 1f), fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(meta, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
            val line = when {
                entry != null -> listOfNotNull(
                    ListStatus.label(entry.status, kind),
                    Formatting.listProgress(entry).takeIf { entry.status != ListStatus.COMPLETED },
                ).joinToString(" · ")
                node.status == "NOT_YET_RELEASED" -> "Not out yet · not on your list"
                else -> "Not on your list"
            }
            BasicText(
                (if (next) "Next · " else "") + line,
                style = NumberStyle(color = if (next) theme.primary else theme.foreground.copy(alpha = 0.75f), fontSize = 12.sp, bold = next),
            )
        }
        if (entry == null) {
            Spacer(Modifier.width(8.dp))
            if (saving != null) {
                BasicText(saving, maxLines = 2, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp), modifier = Modifier.width(80.dp))
            } else {
                FilterChip("+ Planning", false, onAdd)
            }
        }
    }
}
