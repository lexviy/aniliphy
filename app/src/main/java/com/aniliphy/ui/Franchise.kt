package com.aniliphy.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.FranchiseNode
import com.aniliphy.anilist.FranchisePart
import com.aniliphy.anilist.FranchiseRole
import com.aniliphy.anilist.Franchises
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.FuzzyDate
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anime.EpisodeKind
import com.aniliphy.anime.FillerPrefs
import com.aniliphy.anime.FillerShow
import com.aniliphy.anime.FillerTitle
import com.aniliphy.anime.Fillers
import com.aniliphy.anime.TitleFillers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate

/** Whether lists group franchises, and whether side stories join them. Kept between launches. */
internal object FranchisePrefs {
    private const val FILE = "lists"
    var grouped by mutableStateOf(false)
        private set
    /** On unless turned off: side stories and specials (Koyomimonogatari, The Last) belong to their series. */
    var sideStories by mutableStateOf(true)
        private set
    /** A series page in the story's own order instead of release order (only where the two differ). */
    var storyOrder by mutableStateOf(false)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        grouped = prefs.getBoolean("group_franchises", false)
        sideStories = prefs.getBoolean("franchise_side_stories", true)
        storyOrder = prefs.getBoolean("series_story_order", false)
    }

    fun setStoryOrder(context: Context, value: Boolean) {
        storyOrder = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("series_story_order", value).apply()
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

/** Your own calls on which titles are canon, where AniList's source gets it wrong. Kept on this phone, by title id. */
internal object CanonPrefs {
    private const val FILE = "canon"

    fun all(context: Context): Map<Int, Boolean> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            val id = key.toIntOrNull() ?: return@mapNotNull null
            (value as? Boolean)?.let { id to it }
        }.toMap()

    /** Sets [id] canon (true) or anime-only (false); null goes back to what AniList says. */
    fun set(context: Context, id: Int, canon: Boolean?) {
        val edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (canon == null) edit.remove(id.toString()) else edit.putBoolean(id.toString(), canon)
        edit.apply()
    }
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
    val unit = unitOf(kind)
    fun done(e: ListEntry) = if (kind == MediaKind.LightNovel) e.progressVolumes.takeIf { it > 0 } ?: e.progress else e.progress
    val seen = entries.sumOf(::done)
    val totals = entries.map { totalOf(it, kind) }
    return if (totals.all { it != null }) "$seen/${totals.sumOf { it!! }} $unit" else "$seen $unit"
}

private fun unitOf(kind: MediaKind) = when (kind) {
    MediaKind.Anime -> "ep"
    MediaKind.Manga -> "ch"
    MediaKind.LightNovel -> "vol"
}

private fun totalOf(e: ListEntry, kind: MediaKind) = when (kind) {
    MediaKind.Anime -> e.media.episodes
    MediaKind.Manga -> e.media.chapters
    MediaKind.LightNovel -> e.media.volumes
}

/** How much of the franchise is done, from 0 to 1, when its length is known. */
private fun groupFraction(entries: List<ListEntry>, kind: MediaKind): Float? {
    val totals = entries.map { totalOf(it, kind) }
    if (totals.any { it == null || it <= 0 }) return null
    val all = totals.sumOf { it!! }
    val done = entries.sumOf { e -> (if (e.status == ListStatus.COMPLETED) totalOf(e, kind)!! else e.progress).coerceAtMost(totalOf(e, kind)!!) }
    return (done.toFloat() / all).coerceIn(0f, 1f)
}

/**
 * The switch at the top of a list: every title on its own, or the seasons, films and extras of each show together as
 * one series.
 */
@Composable
internal fun SeriesSwitch(grouped: Boolean, titles: Int, series: Int?, loading: Boolean, onPick: (Boolean) -> Unit) {
    val theme = LocalAppTheme.current
    val shape = lookShape(12.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .border(1.5.dp, theme.primary, shape),
    ) {
        listOf(false, true).forEach { option ->
            val on = option == grouped
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) theme.primary else Color.Transparent)
                    .pressable({ if (!on) onPick(option) }),
            ) {
                val color = if (on) theme.background else theme.foreground.copy(alpha = 0.7f)
                BasicText(
                    if (option) "⧉ Series" else "▦ Titles",
                    style = TextStyle(color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                )
                val count = when {
                    !option -> titles.toString()
                    loading && series == null -> "…"
                    else -> series?.toString()
                }
                count?.let {
                    Spacer(Modifier.width(7.dp))
                    BasicText(it, style = NumberStyle(color = color.copy(alpha = 0.8f), fontSize = 11.sp))
                }
            }
        }
    }
}

/** A franchise on a list: its covers stacked, how many parts, and what comes next. */
@Composable
internal fun FranchiseCard(group: ListItem.Group, kind: MediaKind, showStatus: Boolean) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val lead = group.lead
    val status = Franchises.combinedStatus(group.entries)
    val byAge = group.entries.sortedBy { it.media.id }
    // Next up: what you're on, or else the first part not finished.
    val next = byAge.firstOrNull { it.status == ListStatus.CURRENT || it.status == ListStatus.REPEATING }
        ?: byAge.firstOrNull { it.status != ListStatus.COMPLETED && it.status != ListStatus.DROPPED }
    val unit = unitOf(kind)
    val nextLine = when {
        next == null -> "✓ All done"
        next.status == ListStatus.CURRENT || next.status == ListStatus.REPEATING -> "▶ ${next.media.title.display} · $unit ${next.progress + 1}"
        else -> "▶ ${next.media.title.display}"
    }
    val fraction = groupFraction(group.entries, kind)
    val progressLine = listOfNotNull(groupProgress(group.entries, kind), ListStatus.label(status, kind).takeIf { showStatus && status != null }).joinToString(" · ")
    if (LocalCoverStyle.current.layout == CoverLayout.List) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(lookShape(10.dp)).pressable({ ui.navigator.open(Route.Franchise(lead.media.id)) })
                .background(theme.surface.copy(alpha = 0.72f), lookShape(10.dp))
                .padding(8.dp),
        ) {
            Box(Modifier.width(66.dp).height(84.dp)) {
                byAge.filter { it.media.id != lead.media.id }.take(2).forEachIndexed { i, entry ->
                    Box(Modifier.size(48.dp, 70.dp).offset(x = (10 + 6 * i).dp, y = (2 * i).dp).rotate(4f + 3f * i).alpha(0.7f - 0.2f * i)) {
                        Cover(entry.media.coverImage.best, Modifier.fillMaxSize())
                    }
                }
                Box(Modifier.size(56.dp, 80.dp).align(Alignment.BottomStart)) { Cover(lead.media.coverImage.best, Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                BasicText(
                    "${group.entries.size} PARTS",
                    style = NumberStyle(color = theme.background, fontSize = 10.sp, bold = true),
                    modifier = Modifier.background(theme.accent).padding(horizontal = 5.dp, vertical = 1.dp),
                )
                BasicText(lead.media.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium))
                BasicText(progressLine, maxLines = 1, style = NumberStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp))
                BasicText(nextLine, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = if (next == null) theme.success else theme.primary, fontSize = 11.sp))
                if (fraction != null) {
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.fillMaxWidth().height(3.dp).background(theme.foreground.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(if (fraction >= 1f) theme.success else theme.primary))
                    }
                }
            }
        }
        return
    }
    Column(Modifier.pressable({ ui.navigator.open(Route.Franchise(lead.media.id)) })) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).padding(top = 6.dp, end = 8.dp)) {
            val behind = byAge.filter { it.media.id != lead.media.id }.take(2)
            behind.reversed().forEachIndexed { i, entry ->
                val depth = behind.size - i
                Box(
                    Modifier
                        .matchParentSize()
                        .offset(x = (4 * depth).dp, y = (-3 * depth).dp)
                        .rotate(2.5f * depth)
                        .alpha(1f - 0.22f * depth),
                ) { Cover(entry.media.coverImage.best, Modifier.fillMaxSize()) }
            }
            Cover(lead.media.coverImage.best, Modifier.matchParentSize())
            BasicText(
                "${group.entries.size} PARTS",
                style = NumberStyle(color = theme.background, fontSize = 10.sp, bold = true),
                modifier = Modifier.align(Alignment.TopStart).padding(top = 8.dp).background(theme.accent).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            BasicText(
                nextLine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = if (next == null) theme.success else theme.primary, fontSize = 10.5.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(theme.background.copy(alpha = 0.86f)).padding(horizontal = 5.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        BasicText(
            lead.media.title.display,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        BasicText(
            progressLine,
            maxLines = 1,
            style = NumberStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 11.sp),
        )
        if (fraction != null) {
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(theme.foreground.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(if (fraction >= 1f) theme.success else theme.primary))
            }
        }
    }
}

private enum class PartFilter(val label: String) { All("Everything"), Story("Story only"), NotOnList("Not on your list") }

private fun FuzzyDate?.toLocalDate(): LocalDate? {
    val d = this ?: return null
    return runCatching { LocalDate.of(d.year ?: return null, d.month ?: return null, d.day ?: return null) }.getOrNull()
}

private fun FranchiseNode.fillerTitle() = FillerTitle(
    mediaId = id,
    titles = (listOfNotNull(title.english, title.romaji) + synonyms).filter { it.isNotBlank() && it.any(Char::isLetter) && it.all { c -> c.code < 0x3000 } },
    format = format,
    episodes = episodes,
    start = startDate.toLocalDate(),
)

/** A franchise in watch order: what each part is, what you've seen, what's next, and where the fillers are. */
@Composable
internal fun FranchisePage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val anime = LocalAnime.current
    val load = retained.get("franchise") {
        ui.load { _ -> Fetched(ui.api.franchise(listOf(id), Franchises.PAGE), false) }
    }
    val list = retained.get("list") { LazyListState() }
    val adding = remember { mutableStateMapOf<Int, String>() }
    var filter by retained.get("filter") { mutableStateOf(PartFilter.All) }
    val fillers = retained.get("fillers") { mutableStateMapOf<Int, TitleFillers?>() }
    val skips = remember { mutableStateMapOf<Int, Boolean>() }
    var open by remember { mutableStateOf<Int?>(null) }
    var picking by remember { mutableStateOf<FranchiseNode?>(null) }
    FranchisePrefs.load(context)
    val corrections = remember { mutableStateMapOf<Int, Boolean>().apply { putAll(CanonPrefs.all(context)) } }
    var correcting by remember { mutableStateOf<FranchisePart?>(null) }
    val nodes = load.value
    val corrected = corrections.toMap()
    val parts = remember(nodes, corrected) { nodes?.let { Franchises.describe(it, corrected) }.orEmpty() }
    // What AniList's source alone says, for "As AniList says".
    val fromAniList = remember(nodes) { nodes?.let { Franchises.describe(it).associate { p -> p.node.id to p.canon } }.orEmpty() }
    val storyIds = remember(nodes) { nodes?.let { Franchises.storyOrder(it).map { n -> n.id } }.orEmpty() }
    val orderDiffers = nodes != null && storyIds != nodes.map { it.id }
    val inStoryOrder = orderDiffers && FranchisePrefs.storyOrder
    val ordered = remember(parts, storyIds, inStoryOrder) {
        if (inStoryOrder) parts.associateBy { it.node.id }.let { byId -> storyIds.mapNotNull(byId::get) } else parts
    }
    val canonKnown = parts.any { it.canon != null }

    // Fillers for each series, one after the other (the list of shows is fetched once a month, each show's once a week).
    LaunchedEffect(nodes) {
        val series = parts.map { it.node }.filter { it.type == "ANIME" && it.format in Franchises.EPISODIC && it.id !in fillers }
        series.forEach { node ->
            val found = withContext(Dispatchers.IO) {
                try {
                    Fillers.shared(context).forTitle(node.fillerTitle())
                } catch (_: IOException) {
                    null
                }
            }
            fillers[node.id] = found
            skips[node.id] = FillerPrefs.skips(context, node.id)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Series") { ui.navigator.back() }
        when {
            nodes == null && load.error != null -> ErrorRow(load.error!!) { load.load() }
            nodes == null -> Loading()
            else -> {
                val story = ordered.filter { it.inStory }
                // What you're on first; otherwise the first part of the story not finished.
                val next = story.firstOrNull { part ->
                    val status = ui.entryFor(part.node.id)?.status
                    status == ListStatus.CURRENT || status == ListStatus.REPEATING
                } ?: story.firstOrNull { part ->
                    val status = ui.entryFor(part.node.id)?.status
                    status != ListStatus.COMPLETED && status != ListStatus.DROPPED && part.node.status != "NOT_YET_RELEASED"
                }
                val shown = when (filter) {
                    PartFilter.All -> ordered
                    PartFilter.Story -> story
                    PartFilter.NotOnList -> ordered.filter { ui.entryFor(it.node.id) == null }
                }
                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 28.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "hero") {
                        FranchiseHero(
                            parts,
                            when {
                                inStoryOrder -> "⧉ SERIES · STORY ORDER"
                                orderDiffers -> "⧉ SERIES · RELEASE ORDER"
                                else -> "⧉ SERIES · RELEASE ORDER · SAME AS THE STORY"
                            },
                        )
                    }
                    item(key = "stats") { FranchiseStats(parts, fillers) }
                    if (orderDiffers) item(key = "order") { OrderSwitch(inStoryOrder) { FranchisePrefs.setStoryOrder(context, it) } }
                    item(key = "continue") {
                        next?.let { part ->
                            ContinueButton(part, fillers[part.node.id], skips[part.node.id] == true) {
                                if (part.node.type == "ANIME") anime.watchMedia(part.node.asMedia()) else ui.navigator.open(Route.Media(part.node.id))
                            }
                        }
                    }
                    item(key = "filters") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp)) {
                            PartFilter.entries.forEach { option ->
                                val count = when (option) {
                                    PartFilter.All -> parts.size
                                    PartFilter.Story -> story.size
                                    PartFilter.NotOnList -> parts.count { ui.entryFor(it.node.id) == null }
                                }
                                val label = if (option == PartFilter.Story && canonKnown) "Canon only" else option.label
                                FilterChip("$label $count", filter == option) { filter = option }
                            }
                        }
                        if (canonKnown) CanonHint(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                        if (fillers.values.any { it != null }) FillerLegend(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        if (nodes.size >= Franchises.WALK_LIMIT) {
                            Hint("A long franchise: only the first ${Franchises.WALK_LIMIT} titles are shown")
                        }
                    }
                    itemsIndexed(shown, key = { _, part -> part.node.id }) { index, part ->
                        PartRow(
                            number = index + 1,
                            part = part,
                            last = index == shown.lastIndex,
                            next = part == next,
                            fillers = fillers[part.node.id],
                            fillersOpen = open == part.node.id,
                            skipFillers = skips[part.node.id] == true,
                            saving = adding[part.node.id],
                            onToggleFillers = { open = if (open == part.node.id) null else part.node.id },
                            onSkipFillers = { on ->
                                skips[part.node.id] = on
                                FillerPrefs.setSkips(context, part.node.id, on)
                            },
                            onPickShow = { picking = part.node },
                            onCanon = if (part.canon != null) ({ correcting = part }) else null,
                            onAdd = {
                                adding[part.node.id] = "Adding..."
                                ui.scope.launch {
                                    try {
                                        ui.sendOrQueue({ com.aniliphy.anilist.PendingWrite.Entry(part.node.id, part.node.title.display, part.node.type == "ANIME", ListStatus.PLANNING) }) {
                                            ui.api.saveEntry(part.node.id, ListStatus.PLANNING)
                                        }
                                        adding.remove(part.node.id)
                                        ui.navigator.listsChanged()
                                    } catch (e: AniListException) {
                                        adding[part.node.id] = ui.describe(e)
                                    }
                                }
                            },
                        )
                    }
                    item(key = "related") { AlsoRelated(nodes) }
                }
            }
        }
    }
    correcting?.let { part ->
        CanonDialog(
            part = part,
            fromAniList = fromAniList[part.node.id],
            onPick = { value ->
                correcting = null
                if (value == null) corrections.remove(part.node.id) else corrections[part.node.id] = value
                CanonPrefs.set(context, part.node.id, value)
            },
            onDismiss = { correcting = null },
        )
    }
    picking?.let { node ->
        FillerShowPicker(
            node = node,
            onPick = { slug ->
                picking = null
                ui.scope.launch {
                    val found = withContext(Dispatchers.IO) {
                        try {
                            Fillers.shared(context).pick(node.fillerTitle(), slug)
                            Fillers.shared(context).forTitle(node.fillerTitle())
                        } catch (_: IOException) {
                            null
                        }
                    }
                    fillers[node.id] = found
                }
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun FranchiseHero(parts: List<FranchisePart>, kicker: String) {
    val theme = LocalAppTheme.current
    val lead = parts.firstOrNull { it.role == FranchiseRole.Main }?.node ?: parts.firstOrNull()?.node ?: return
    val banner = parts.firstNotNullOfOrNull { it.node.bannerImage.takeIf { _ -> it.role == FranchiseRole.Main } } ?: lead.bannerImage
    val years = parts.mapNotNull { it.node.startDate?.year }
    Box(Modifier.fillMaxWidth().height(150.dp)) {
        Box(Modifier.matchParentSize().background(Brush.linearGradient(listOf(theme.secondary.copy(alpha = 0.55f), theme.primary.copy(alpha = 0.25f), theme.background))))
        banner?.let {
            AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().alpha(0.75f))
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to theme.background.copy(alpha = 0.2f), 1f to theme.background)))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 8.dp)) {
            BasicText(kicker, style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 10.sp, bold = true))
            BasicText(
                lead.title.display,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TitleStyle(color = theme.foreground, fontSize = 26.sp, lineHeight = 28.sp),
                modifier = Modifier.lookAlive(Alive.Heading),
            )
            if (years.isNotEmpty()) {
                val from = years.min()
                val to = years.max()
                BasicText(
                    if (from == to) "$from" else "$from – $to",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp),
                )
            }
        }
    }
}

@Composable
private fun FranchiseStats(parts: List<FranchisePart>, fillers: Map<Int, TitleFillers?>) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val anime = parts.firstOrNull()?.node?.type == "ANIME"
    val finished = parts.count { ui.entryFor(it.node.id)?.status == ListStatus.COMPLETED }
    // What's left of the story, from each part's length and what's watched of it.
    val minutesLeft = parts.filter { it.inStory && ui.entryFor(it.node.id)?.status != ListStatus.COMPLETED }.sumOf { part ->
        val total = part.node.episodes ?: return@sumOf 0
        val done = ui.entryFor(part.node.id)?.progress ?: 0
        (total - done).coerceAtLeast(0) * (part.node.duration ?: 0)
    }
    val known = fillers.values.filterNotNull()
    val fillerShare = known.sumOf { it.fillerCount }.takeIf { known.isNotEmpty() }?.let { f ->
        val all = known.sumOf { it.episodes.size }
        if (all > 0) (f * 100 / all) else null
    }
    val cells = listOfNotNull(
        "${parts.size}" to "parts",
        "$finished" to "finished",
        (if (anime && minutesLeft > 0) (if (minutesLeft >= 60) "~${(minutesLeft + 30) / 60}h" else "${minutesLeft}m") to "left" else null),
        fillerShare?.let { "$it%" to "filler" },
    )
    val shape = lookShape(10.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .border(1.5.dp, theme.foreground.copy(alpha = 0.18f), shape)
            .lookAlive(Alive.Stats),
    ) {
        cells.forEachIndexed { i, (value, label) ->
            Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
                BasicText(value, style = NumberStyle(color = if (label == "filler" && value != "0%") theme.error else theme.primary, fontSize = 17.sp, bold = true))
                BasicText(label, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 10.sp))
            }
            if (i < cells.lastIndex) Box(Modifier.width(1.5.dp).height(44.dp).background(theme.foreground.copy(alpha = 0.18f)))
        }
    }
}

@Composable
private fun ContinueButton(part: FranchisePart, fillers: TitleFillers?, skipFillers: Boolean, onClick: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val entry = ui.entryFor(part.node.id)
    val anime = part.node.type == "ANIME"
    val unit = if (anime) "ep" else "ch"
    val progress = entry?.progress ?: 0
    val plainNext = progress + 1
    val storyNext = if (anime && skipFillers && fillers != null) fillers.nextStory(progress) ?: plainNext else plainNext
    val label = when {
        entry == null || entry.status == ListStatus.PLANNING -> "Start · ${part.node.title.display}"
        else -> "Continue · ${part.node.title.display} $unit $storyNext"
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        PillButton(if (anime) "▶ $label" else label, onClick = onClick, modifier = Modifier.fillMaxWidth().lookAlive(Alive.Button))
        if (storyNext != plainNext) {
            val skipped = Fillers.runs((plainNext until storyNext).toList()).joinToString { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
            BasicText(
                "You're on $unit $progress · $skipped " + (if (storyNext - plainNext == 1) "is a filler, skipped" else "are fillers, skipped"),
                style = TextStyle(color = theme.error.copy(alpha = 0.9f), fontSize = 12.sp),
                modifier = Modifier.padding(top = 5.dp, start = 4.dp),
            )
        }
    }
}

@Composable
private fun FillerLegend(modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier) {
        listOf(EpisodeKind.MangaCanon, EpisodeKind.AnimeCanon, EpisodeKind.Mixed, EpisodeKind.Filler).forEach { kind ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(kindColor(kind, theme)))
                Spacer(Modifier.width(4.dp))
                BasicText(kind.short, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
            }
        }
    }
}

internal fun kindColor(kind: EpisodeKind, theme: AppTheme): Color = when (kind) {
    EpisodeKind.MangaCanon -> theme.primary
    EpisodeKind.AnimeCanon -> theme.primary.copy(alpha = 0.45f)
    EpisodeKind.Mixed -> theme.warning
    EpisodeKind.Filler -> theme.error
}

/** Every episode as a thin mark, coloured by what it is: the shape of a show's fillers at a glance. */
@Composable
private fun FillerStrip(fillers: TitleFillers, total: Int?, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val count = maxOf(total ?: 0, fillers.episodes.keys.maxOrNull() ?: 0)
    if (count <= 0) return
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val w = size.width / count
        val gap = if (w > 3f) 1f else 0f
        for (n in 1..count) {
            val kind = fillers.kindOf(n)
            drawRect(
                color = kind?.let { kindColor(it, theme) } ?: theme.foreground.copy(alpha = 0.12f),
                topLeft = Offset((n - 1) * w, 0f),
                size = Size((w - gap).coerceAtLeast(0.6f), size.height),
            )
        }
    }
}

@Composable
private fun TypeTag(text: String, color: Color, modifier: Modifier = Modifier) {
    BasicText(
        text,
        maxLines = 1,
        style = NumberStyle(color = color, fontSize = 9.5.sp, bold = true),
        modifier = modifier.border(1.dp, color.copy(alpha = 0.85f), lookShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private fun formatTag(format: String?): String? = when (format) {
    null -> null
    "TV", "TV_SHORT" -> "TV"
    "MOVIE" -> "MOVIE"
    "OVA" -> "OVA"
    "ONA" -> "ONA"
    "SPECIAL" -> "SPECIAL"
    "MUSIC" -> "MUSIC"
    "NOVEL" -> "NOVEL"
    "ONE_SHOT" -> "ONE SHOT"
    else -> format.replace('_', ' ')
}

@Composable
private fun PartRow(
    number: Int,
    part: FranchisePart,
    last: Boolean,
    next: Boolean,
    fillers: TitleFillers?,
    fillersOpen: Boolean,
    skipFillers: Boolean,
    saving: String?,
    onToggleFillers: () -> Unit,
    onSkipFillers: (Boolean) -> Unit,
    onPickShow: () -> Unit,
    onCanon: (() -> Unit)?,
    onAdd: () -> Unit,
) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val node = part.node
    val entry = ui.entryFor(node.id)
    val kind = if (node.type == "ANIME") MediaKind.Anime else if (node.format == "NOVEL") MediaKind.LightNovel else MediaKind.Manga
    val done = entry?.status == ListStatus.COMPLETED
    val dimmed = part.role.skippable
    val railColor = theme.foreground.copy(alpha = 0.18f)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // The rail on to the next step, under the number.
            .drawBehind {
                if (!last) {
                    val x = 16.dp.toPx()
                    drawLine(railColor, Offset(x, 38.dp.toPx()), Offset(x, size.height + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            }
            .alpha(if (dimmed && !next) 0.62f else 1f),
    ) {
        // The rail: the step's number, and the line on to the next one.
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(32.dp)) {
            Spacer(Modifier.height(8.dp))
            val ring = when {
                next -> theme.primary
                done -> theme.success
                else -> theme.foreground.copy(alpha = 0.25f)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(lookShape(6.dp))
                    .background(if (next) theme.primary else theme.background)
                    .border(1.5.dp, ring, lookShape(6.dp)),
            ) {
                BasicText(
                    if (done) "✓" else "%02d".format(number),
                    style = NumberStyle(color = if (next) theme.background else ring.copy(alpha = 1f), fontSize = 11.sp, bold = true),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(lookShape(10.dp))
                .background(if (next) theme.primary.copy(alpha = 0.10f) else Color.Transparent)
                .clickable { ui.navigator.open(Route.Media(node.id)) }
                .padding(vertical = 8.dp, horizontal = 6.dp),
        ) {
            Row {
                Box(Modifier.size(width = 48.dp, height = 68.dp)) { Cover(node.coverImage.best, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        val tagRoom = Modifier.padding(vertical = 4.dp)
                        formatTag(node.format)?.let { TypeTag(it, if (it == "TV") theme.primary else if (it == "MOVIE") theme.accent else theme.secondary, tagRoom) }
                        // The ✎ shows a mark you set yourself; canon marks open the dialog to correct them.
                        val yours = if (part.corrected) " ✎" else ""
                        if (part.role != FranchiseRole.Main) {
                            val color = when (part.role) {
                                FranchiseRole.Recap -> theme.foreground.copy(alpha = 0.7f)
                                FranchiseRole.AnimeOnly -> theme.error
                                FranchiseRole.Alternative -> theme.warning
                                else -> theme.secondary
                            }
                            if (part.role == FranchiseRole.AnimeOnly && onCanon != null) {
                                TypeTag(part.role.tag + yours, color, Modifier.clip(lookShape(4.dp)).pressable(onCanon).then(tagRoom))
                            } else {
                                TypeTag(part.role.tag, color, tagRoom)
                            }
                        }
                        if (part.canon == true) {
                            val tap = onCanon?.let { Modifier.clip(lookShape(4.dp)).pressable(it) } ?: Modifier
                            TypeTag("CANON$yours", theme.success, tap.then(tagRoom))
                        }
                        if (next) BasicText("next ▸", style = NumberStyle(color = theme.secondary, fontSize = 11.sp, bold = true), modifier = Modifier.padding(top = 4.dp))
                    }
                    BasicText(
                        node.title.display,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground, fontSize = 14.5.sp, fontWeight = FontWeight.Bold),
                    )
                    val length = when (kind) {
                        MediaKind.Anime -> node.episodes?.let { if (it == 1 && node.duration != null && node.duration >= 40) "${node.duration / 60}h ${node.duration % 60}m" else "$it ep" }
                        MediaKind.Manga -> node.chapters?.let { "$it ch" }
                        MediaKind.LightNovel -> node.volumes?.let { "$it vol" }
                    }
                    val note = when {
                        part.corrected -> if (part.canon == true) "you marked it canon" else "you marked it anime-only"
                        else -> part.role.note
                    }
                    val meta = listOfNotNull(node.startDate?.year?.toString(), length, note?.let { if (part.role.skippable) "$it · skippable" else it })
                    BasicText(meta.joinToString(" · "), style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.5.sp))
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val line = when {
                            entry != null -> listOfNotNull(
                                ListStatus.label(entry.status, kind),
                                Formatting.listProgress(entry).takeIf { entry.status != ListStatus.COMPLETED },
                            ).joinToString(" · ")
                            node.status == "NOT_YET_RELEASED" -> "Not out yet · not on your list"
                            else -> "Not on your list"
                        }
                        BasicText(
                            line,
                            style = NumberStyle(
                                color = when {
                                    done -> theme.success
                                    entry?.status == ListStatus.CURRENT || entry?.status == ListStatus.REPEATING -> theme.primary
                                    entry?.status == ListStatus.PLANNING -> theme.accent
                                    else -> theme.foreground.copy(alpha = 0.65f)
                                },
                                fontSize = 11.5.sp,
                            ),
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (entry == null) {
                            Spacer(Modifier.width(8.dp))
                            if (saving != null) {
                                BasicText(saving, maxLines = 2, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp))
                            } else {
                                FilterChip("+ Planning", false, onAdd)
                            }
                        }
                    }
                }
            }
            if (fillers != null) {
                Spacer(Modifier.height(8.dp))
                FillerBox(fillers, node.episodes, fillersOpen, skipFillers, onToggleFillers, onSkipFillers, onPickShow)
            }
        }
    }
}

@Composable
private fun FillerBox(
    fillers: TitleFillers,
    total: Int?,
    open: Boolean,
    skip: Boolean,
    onToggle: () -> Unit,
    onSkip: (Boolean) -> Unit,
    onPickShow: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val counted = fillers.episodes.size
    val share = if (counted > 0) fillers.fillerCount * 100 / counted else 0
    if (fillers.fillerCount == 0 && fillers.mixedCount == 0) {
        // Nothing to skip: one quiet line, still with the way to say it's the wrong show.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onPickShow).padding(vertical = 2.dp)) {
            BasicText("✓ No fillers", style = NumberStyle(color = theme.success, fontSize = 12.sp, bold = true))
            BasicText(" · all $counted from the story", style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.5.sp))
        }
        return
    }
    val shape = lookShape(6.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, theme.foreground.copy(alpha = if (open) 0.3f else 0.12f), shape)
            .background(theme.surface.copy(alpha = if (open) 0.7f else 0.35f))
            .clickable(onClick = onToggle)
            .padding(8.dp),
    ) {
        FillerStrip(fillers, total)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("$share% filler", style = NumberStyle(color = theme.error, fontSize = 12.sp, bold = true))
            BasicText(
                " · ${fillers.fillerCount} of $counted" + (if (fillers.mixedCount > 0) " · ${fillers.mixedCount} mixed" else "") + (if (skip) " · skipping" else ""),
                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.5.sp),
                modifier = Modifier.weight(1f),
            )
            BasicText(if (open) "▾" else "▸", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp))
        }
        if (!open) return@Column
        val runs = fillers.runs(EpisodeKind.Filler)
        if (runs.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                "Fillers: " + runs.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" },
                style = NumberStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 12.sp),
            )
        }
        val mixed = fillers.runs(EpisodeKind.Mixed)
        if (mixed.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            BasicText(
                "Mixed: " + mixed.joinToString(", ") { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" },
                style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp),
            )
        }
        Spacer(Modifier.height(4.dp))
        SwitchRowCompact("Skip fillers when playing", skip, onSkip)
        BasicText(
            "From animefillerlist.com (${fillers.link.name}) · Wrong show? Change",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp),
            modifier = Modifier.clickable(onClick = onPickShow).padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SwitchRowCompact(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
    ) {
        BasicText(label, style = TextStyle(color = theme.foreground, fontSize = 13.sp), modifier = Modifier.weight(1f))
        val shape = lookShape(10.dp)
        Box(
            Modifier
                .size(width = 38.dp, height = 20.dp)
                .clip(shape)
                .border(1.5.dp, if (checked) theme.primary else theme.foreground.copy(alpha = 0.3f), shape)
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(14.dp).clip(lookShape(7.dp)).background(if (checked) theme.primary else theme.foreground.copy(alpha = 0.45f)))
        }
    }
}

@Composable
private fun AlsoRelated(nodes: List<FranchiseNode>) {
    val theme = LocalAppTheme.current
    val related = remember(nodes) { Franchises.alsoRelated(nodes) }
    if (related.isEmpty()) return
    val names = mapOf(
        "SPIN_OFF" to ("spin-off" to "spin-offs"),
        "ALTERNATIVE" to ("other version" to "other versions"),
        "SIDE_STORY" to ("side story" to "side stories"),
        "SUMMARY" to ("recap" to "recaps"),
        "COMPILATION" to ("compilation" to "compilations"),
        "OTHER" to ("other title" to "other titles"),
    )
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        BasicText("ALSO RELATED · NOT IN THE ORDER", style = NumberStyle(color = theme.accent, fontSize = 11.sp, bold = true))
        Spacer(Modifier.height(4.dp))
        BasicText(
            related.entries.sortedByDescending { it.value }.joinToString(" · ") { (relation, n) ->
                val (one, many) = names[relation] ?: (relation.lowercase() to relation.lowercase())
                "$n ${if (n == 1) one else many}"
            } + " · on each title's page",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
        )
    }
}

/** Choosing by hand which Anime Filler List show a title is, when the name match got it wrong. */
/** Release order or the story's own, on a series page where the two differ. */
@Composable
private fun OrderSwitch(story: Boolean, onPick: (Boolean) -> Unit) {
    val theme = LocalAppTheme.current
    val shape = lookShape(10.dp)
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().height(38.dp).clip(shape).border(1.5.dp, theme.primary, shape)) {
            listOf(false to "Release order", true to "Story order").forEach { (option, label) ->
                val on = option == story
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (on) theme.primary else Color.Transparent)
                        .pressable({ if (!on) onPick(option) }),
                ) {
                    BasicText(
                        label,
                        style = TextStyle(color = if (on) theme.background else theme.foreground.copy(alpha = 0.7f), fontSize = 13.5.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
        if (story) {
            BasicText(
                "Where it happens in the story, from AniList's prequel and sequel links. Titles they don't place go by release.",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.5.sp),
                modifier = Modifier.padding(top = 6.dp, start = 2.dp),
            )
        }
    }
}

@Composable
private fun CanonHint(modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        TypeTag("CANON", theme.success)
        Spacer(Modifier.width(6.dp))
        BasicText(
            "going by where AniList says each title comes from · tap the tag to correct it",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.5.sp),
        )
    }
}

/** Where AniList says a title comes from, in words. */
private fun sourceSays(source: String?): String = when (source) {
    null -> "AniList doesn't say where it comes from."
    "ORIGINAL" -> "AniList says it was made for the anime (source: Original)."
    "MANGA" -> "AniList says it comes from the manga."
    "LIGHT_NOVEL" -> "AniList says it comes from the light novels."
    "WEB_NOVEL" -> "AniList says it comes from the web novel."
    "VISUAL_NOVEL" -> "AniList says it comes from the visual novel."
    "VIDEO_GAME", "GAME" -> "AniList says it comes from the game."
    else -> "AniList says it comes from: ${source.lowercase().replace('_', ' ')}."
}

/** Canon, anime-only, or back to what AniList's source says, for one title. */
@Composable
private fun CanonDialog(part: FranchisePart, fromAniList: Boolean?, onPick: (Boolean?) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(newLookShape(16.dp))
                .background(theme.surface)
                .padding(16.dp),
        ) {
            BasicText("Is it canon?", style = TitleStyle(color = theme.foreground, fontSize = 19.sp))
            BasicText(
                part.node.title.display,
                style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp),
                modifier = Modifier.padding(top = 2.dp),
            )
            BasicText(
                sourceSays(part.node.source),
                style = TextStyle(color = theme.foreground, fontSize = 13.5.sp),
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
            CanonOption("Canon", "Part of the story: shown in Canon only, never skipped", part.corrected && part.canon == true) { onPick(true) }
            CanonOption("Anime-only", "Not in the original story: marked skippable", part.corrected && part.canon == false) { onPick(false) }
            CanonOption(
                "As AniList says",
                when (fromAniList) {
                    true -> "Canon, going by its source"
                    false -> "Anime-only, going by its source"
                    null -> "No mark"
                },
                !part.corrected,
            ) { onPick(null) }
            BasicText(
                "Kept on this phone, for this title.",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.5.sp),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun CanonOption(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    val shape = lookShape(8.dp)
    val ring = if (selected) theme.primary else theme.foreground.copy(alpha = 0.5f)
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) theme.primary.copy(alpha = 0.10f) else Color.Transparent)
            .border(1.5.dp, if (selected) theme.primary else theme.foreground.copy(alpha = 0.18f), shape)
            .pressable(onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(top = 2.dp).size(15.dp).border(1.5.dp, ring, CircleShape)) {
            if (selected) Box(Modifier.size(7.dp).background(theme.primary, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            BasicText(label, style = TextStyle(color = theme.foreground, fontSize = 14.5.sp, fontWeight = FontWeight.Bold))
            BasicText(detail, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
        }
    }
}

@Composable
private fun FillerShowPicker(node: FranchiseNode, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    var text by remember { mutableStateOf(node.title.romaji ?: node.title.display) }
    val shows by androidx.compose.runtime.produceState<List<FillerShow>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { Fillers.shared(context).shows() }.getOrNull() }.orEmpty()
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(newLookShape(16.dp))
                .background(theme.surface)
                .padding(16.dp),
        ) {
            BasicText("Which show is it?", style = TitleStyle(color = theme.foreground, fontSize = 19.sp))
            BasicText(
                "On animefillerlist.com, for ${node.title.display}",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp),
                modifier = Modifier.padding(vertical = 6.dp),
            )
            SearchField(text, { text = it }, "Show name", Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            val all = shows
            if (all == null) {
                Loading()
            } else {
                val words = Fillers.normalize(text).split(' ').filter { it.isNotEmpty() }
                val matching = all.filter { show -> words.all { w -> show.names.any { Fillers.normalize(it).contains(w) } } }.take(40)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(matching, key = { it.slug }) { show ->
                        BasicText(
                            show.names.joinToString(" · "),
                            style = TextStyle(color = theme.foreground, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth().clickable { onPick(show.slug) }.padding(vertical = 9.dp),
                        )
                    }
                }
            }
            BasicText(
                "It has no filler list",
                style = TextStyle(color = theme.error, fontSize = 14.sp),
                modifier = Modifier.clickable { onPick(null) }.padding(vertical = 10.dp),
            )
        }
    }
}
