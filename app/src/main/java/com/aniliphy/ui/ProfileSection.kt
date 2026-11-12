package com.aniliphy.ui

import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniliphy.anilist.Activity
import com.aniliphy.anilist.AnimePeople
import com.aniliphy.anilist.Favourites
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListInsights
import com.aniliphy.anilist.ListStats
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Overview
import com.aniliphy.anilist.Remaining
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.ofKind
import kotlinx.coroutines.launch

private enum class ProfileTab(val label: String) { Overview("Overview"), Stats("Stats"), Favorites("Favorites") }

private const val PROFILE_URL = "https://anilist.co/user/"

private class StatsData(
    val animeDetails: LoadState<Map<Int, Media>>,
    val paperDetails: LoadState<Map<Int, Media>>,
    val people: LoadState<AnimePeople>,
) {
    fun refresh() {
        animeDetails.load(refresh = true)
        paperDetails.load(refresh = true)
        people.load(refresh = true)
    }

    fun reload() {
        listOf(animeDetails, paperDetails, people).forEach { if (it.value != null) it.load() }
    }
}

private enum class FavouriteFilter(val label: String) {
    All("All"),
    Anime("Anime"),
    Manga("Manga"),
    LightNovel("Light Novel"),
    Characters("Characters"),
    Staff("Staff"),
    Studios("Studios"),
}

private class FavouriteItem(
    val key: String,
    val filter: FavouriteFilter,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val route: Route,
)

private fun Favourites.items(): List<FavouriteItem> = buildList {
    anime.nodes.forEach { media ->
        add(
            FavouriteItem(
                "media${media.id}", FavouriteFilter.Anime, media.title.display,
                Formatting.format(media.format), media.coverImage.best, Route.Media(media.id),
            ),
        )
    }
    manga.nodes.forEach { media ->
        val filter = if (media.kind == MediaKind.LightNovel) FavouriteFilter.LightNovel else FavouriteFilter.Manga
        add(
            FavouriteItem(
                "media${media.id}", filter, media.title.display,
                Formatting.format(media.format), media.coverImage.best, Route.Media(media.id),
            ),
        )
    }
    characters.nodes.forEach { person ->
        add(
            FavouriteItem(
                "character${person.id}", FavouriteFilter.Characters, person.name.full ?: "?",
                "Character", person.image?.best, Route.Character(person.id),
            ),
        )
    }
    staff.nodes.forEach { person ->
        add(
            FavouriteItem(
                "staff${person.id}", FavouriteFilter.Staff, person.name.full ?: "?",
                "Staff", person.image?.best, Route.Staff(person.id),
            ),
        )
    }
    studios.nodes.forEach { studio ->
        add(
            FavouriteItem(
                "studio${studio.id}", FavouriteFilter.Studios, studio.name ?: "?",
                null, null, Route.Studio(studio.id),
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileSection(retained: Retained, onMenu: () -> Unit) {
    val ui = LocalAniList.current
    val viewer = (ui.session.state as? SessionState.LoggedIn)?.viewer
    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Profile, onMenu)
        if (viewer == null) {
            LoginInvite("Log in to see your profile")
            return@Column
        }
        val tab: MutableState<ProfileTab> = retained.get("tab") { mutableStateOf(ProfileTab.Overview) }
        val anime = retained.get("anime") { ui.load<List<ListEntry>> { ui.api.animeList(viewer.id, it) } }
        val paper = retained.get("paper") { ui.load<List<ListEntry>> { ui.api.mangaList(viewer.id, it) } }
        val favourites = retained.get("favourites") { ui.load<Favourites> { ui.api.favourites(viewer.id, it) } }
        val overview = retained.get("overview") { ui.load<Overview> { ui.api.overview(viewer.id, it) } }
        val extras = retained.get("extras") {
            StatsData(
                animeDetails = ui.load(autoStart = false) { ui.api.animeDetails(viewer.id, it) },
                paperDetails = ui.load(autoStart = false) { ui.api.mangaDetails(viewer.id, it) },
                people = ui.load(autoStart = false) { ui.api.animePeople(viewer.id, it) },
            )
        }
        val theme = LocalAppTheme.current
        val seenVersion: MutableState<Int> = retained.get("listsVersion") { mutableStateOf(ui.navigator.listsVersion) }
        LaunchedEffect(ui.navigator.listsVersion) {
            if (seenVersion.value != ui.navigator.listsVersion) {
                seenVersion.value = ui.navigator.listsVersion
                anime.load()
                paper.load()
                favourites.load()
                overview.load()
                extras.reload()
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            AsyncImage(
                model = viewer.avatar?.best,
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(CircleShape).background(theme.panel),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                BasicText(
                    viewer.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
                val novels = paper.value?.ofKind(MediaKind.LightNovel)?.size
                val manga = paper.value?.ofKind(MediaKind.Manga)?.size
                val counts = listOfNotNull(
                    anime.value?.size?.let { "$it anime" },
                    manga?.let { "$it manga" },
                    novels?.let { "$it light novels" },
                ).joinToString(" · ")
                BasicText(counts, style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 13.sp))
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 4.dp),
        ) {
            ProfileTab.entries.forEach { option ->
                FilterChip(option.label, tab.value == option) { tab.value = option }
            }
        }

        val pull = rememberPullToRefreshState()
        val refreshing = when (tab.value) {
            ProfileTab.Overview -> overview.loading && overview.value != null
            ProfileTab.Stats -> (anime.loading && anime.value != null) || (paper.loading && paper.value != null)
            ProfileTab.Favorites -> favourites.loading && favourites.value != null
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                when (tab.value) {
                    ProfileTab.Overview -> overview.load(refresh = true)
                    ProfileTab.Stats -> {
                        anime.load(refresh = true)
                        paper.load(refresh = true)
                        extras.refresh()
                    }
                    ProfileTab.Favorites -> favourites.load(refresh = true)
                }
            },
            state = pull,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull,
                    isRefreshing = refreshing,
                    containerColor = theme.surface,
                    color = theme.primary,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab.value) {
                ProfileTab.Overview -> OverviewTab(retained, overview, viewer.name)
                ProfileTab.Stats -> StatsTab(retained, anime, paper, extras)
                ProfileTab.Favorites -> FavoritesTab(retained, favourites)
            }
        }
    }
}

@Composable
private fun OverviewTab(retained: Retained, load: LoadState<Overview>, name: String) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    val scroll = retained.get("overviewScroll") { ScrollState(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        StaleNotice(load.stale)
        SectionTitle("Recent activity")
        val activities = load.value?.activities
        when {
            activities == null && load.error != null -> ErrorRow(load.error!!) { load.load() }
            activities == null -> Hint("Loading...")
            activities.isEmpty() -> Hint("Nothing here yet")
            else -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                activities.forEach { activity -> ActivityRow(activity) }
            }
        }
        SectionTitle("Account")
        ActionRow("Open profile on AniList", onClick = { context.openLink(PROFILE_URL + Uri.encode(name)) })
        ActionRow("Log out", onClick = { confirmLogout = true }, color = theme.error)
    }

    if (confirmLogout) {
        ConfirmDialog(
            title = "Log out?",
            message = "This removes your AniList token from this phone. Your lists stay on AniList.",
            confirmLabel = "Log out",
            onConfirm = {
                confirmLogout = false
                scope.launch { ui.session.logout() }
            },
            onDismiss = { confirmLogout = false },
        )
    }
}

@Composable
private fun ActivityRow(activity: Activity) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val media = activity.media ?: return
    val meta = listOf(Formatting.kindLabel(media.kind), Formatting.relativeTime(activity.createdAt))
        .filter { it.isNotEmpty() }
        .joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .clickable { ui.navigator.open(Route.Media(media.id)) }
            .padding(8.dp),
    ) {
        Cover(media.coverImage.best, Modifier.size(width = 46.dp, height = 66.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                media.title.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                Formatting.activityDetail(activity),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 13.sp),
            )
            BasicText(meta, style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp))
        }
    }
}

@Composable
private fun StatsTab(
    retained: Retained,
    anime: LoadState<List<ListEntry>>,
    paper: LoadState<List<ListEntry>>,
    extras: StatsData,
) {
    val kind: MutableState<MediaKind> = retained.get("statsKind") { mutableStateOf(MediaKind.Anime) }
    val scroll = retained.get("statsScroll") { ScrollState(0) }
    val load = if (kind.value == MediaKind.Anime) anime else paper
    val details = if (kind.value == MediaKind.Anime) extras.animeDetails else extras.paperDetails
    val entries = load.value

    LaunchedEffect(kind.value) {
        if (details.value == null && !details.loading) details.load()
        if (kind.value == MediaKind.Anime && extras.people.value == null && !extras.people.loading) extras.people.load()
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        ) {
            MediaKind.entries.forEach { option ->
                FilterChip(Formatting.kindLabel(option), kind.value == option) { kind.value = option }
            }
        }
        StaleNotice(load.stale)
        when {
            entries != null -> {
                val ofKind = remember(entries, kind.value) { entries.ofKind(kind.value) }
                StatsBody(retained, ListStats.of(ofKind, kind.value), ofKind, kind.value, details, extras.people)
            }
            load.error != null -> ErrorRow(load.error!!) { load.load() }
            else -> Hint("Loading...")
        }
    }
}

@Composable
private fun StatsBody(
    retained: Retained,
    stats: ListStats,
    entries: List<ListEntry>,
    kind: MediaKind,
    details: LoadState<Map<Int, Media>>,
    people: LoadState<AnimePeople>,
) {
    if (entries.isEmpty()) {
        Hint("Nothing on this list yet")
        return
    }
    val ui = LocalAniList.current
    val anime = kind == MediaKind.Anime
    val insights = remember(entries, kind, details.value) {
        ListInsights.of(entries, kind, details.value.orEmpty())
    }
    val mean = if (stats.meanScore > 0) "%.1f".format(java.util.Locale.ENGLISH, stats.meanScore) else "–"
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Total", stats.count.toString(), Modifier.weight(1f))
            StatTile("Mean score", mean, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (anime) {
                StatTile("Episodes watched", stats.progress.toString(), Modifier.weight(1f))
                StatTile("Days watched", "%.1f".format(java.util.Locale.ENGLISH, stats.daysWatched), Modifier.weight(1f))
            } else {
                StatTile("Chapters read", stats.progress.toString(), Modifier.weight(1f))
                StatTile("Volumes read", stats.volumes.toString(), Modifier.weight(1f))
            }
        }
    }
    LeftToFinish(insights, kind)

    details.error?.let { message -> if (details.value == null) ErrorRow(message) { details.load() } }
    if (details.value == null && details.error == null) Hint("Loading more...")
    RankedBars("Top genres", insights.genres.map { RankedRow(it.name, it.count, it.meanScore, null) })
    RankedBars("Top tags", insights.tags.map { RankedRow(it.name, it.count, it.meanScore, null) })
    if (anime) {
        people.error?.let { message -> if (people.value == null) ErrorRow(message) { people.load() } }
        val found = people.value ?: AnimePeople()
        RankedBars("Top studios", found.studios.take(TOP_PEOPLE).map { RankedRow(it.name, it.count, null, Route.Studio(it.id)) })
        RankedBars("Top staff", found.staff.take(TOP_PEOPLE).map { RankedRow(it.name, it.count, null, Route.Staff(it.id)) })
        RankedBars("Top voice actors", found.voiceActors.take(TOP_PEOPLE).map { RankedRow(it.name, it.count, null, Route.Staff(it.id)) })
    } else {
        RankedBars("Top staff", insights.staff.map { RankedRow(it.name, it.count, null, Route.Staff(it.id)) })
    }
    StatBars("Scores", insights.scores, keepEmpty = true)
    val lengthTitle = when (kind) {
        MediaKind.Anime -> "Episode count"
        MediaKind.Manga -> "Chapter count"
        MediaKind.LightNovel -> "Volume count"
    }
    StatBars(lengthTitle, stats.lengths)
    StatBars(
        "Status",
        stats.statuses.map { (status, count) ->
            (if (status in ListStatus.ALL) ListStatus.label(status, kind) else status) to count
        },
    )
    StatBars("Format", stats.formats.map { (format, count) -> Formatting.format(format) to count })

    val byYear: MutableState<Boolean> = retained.get("statsByYear") { mutableStateOf(false) }
    StatBars(
        if (byYear.value) "Release year" else "Release decade",
        if (byYear.value) insights.years else insights.decades,
        action = (if (byYear.value) "By decade" else "By year") to { byYear.value = !byYear.value },
    )
    if (insights.mostRepeated.isNotEmpty()) {
        val rowState = retained.get("statsRepeated$kind") { LazyListState() }
        CoverRow(
            title = if (anime) "Most rewatched" else "Most reread",
            items = insights.mostRepeated,
            key = { it.media.id },
            state = rowState,
        ) { entry ->
            CoverCard(
                title = entry.media.title.display,
                subtitle = "${entry.repeat}x",
                url = entry.media.coverImage.best,
                onClick = { ui.navigator.open(Route.Media(entry.media.id)) },
                wide = false,
            )
        }
    }
}

private const val TOP_PEOPLE = 10

@Composable
private fun LeftToFinish(insights: ListInsights, kind: MediaKind) {
    val theme = LocalAppTheme.current
    fun amount(left: Remaining) = when (kind) {
        MediaKind.Anime -> Formatting.duration(left.minutes)
        MediaKind.Manga -> "${left.units} ch"
        MediaKind.LightNovel -> "${left.units} vols"
    }
    StatPanel("Left to finish") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LeftAmount(amount(insights.inProgress), "In progress · ${insights.inProgress.titles}", Modifier.weight(1f))
            LeftAmount(amount(insights.planning), "Planning · ${insights.planning.titles}", Modifier.weight(1f))
        }
        val unknown = insights.inProgress.unknown + insights.planning.unknown
        if (unknown > 0) {
            BasicText(
                "Not counted: $unknown with no known length",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun LeftAmount(value: String, label: String, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier) {
        BasicText(
            value,
            maxLines = 1,
            style = TextStyle(color = theme.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicText(
            value,
            maxLines = 1,
            style = TextStyle(color = theme.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
        )
    }
}

@Composable
private fun Bar(share: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(8.dp)) {
        if (share > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(share.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(LocalAppTheme.current.primary),
            )
        }
    }
}

@Composable
private fun StatPanel(title: String, action: Pair<String, () -> Unit>? = null, content: @Composable () -> Unit) {
    val theme = LocalAppTheme.current
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (action != null) FilterChip(action.first, false, action.second)
        }
        content()
    }
}

@Composable
private fun StatBars(
    title: String,
    rows: List<Pair<String, Int>>,
    keepEmpty: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    val shown = if (keepEmpty) rows else rows.filter { it.second > 0 }
    if (shown.isEmpty()) return
    val theme = LocalAppTheme.current
    val total = shown.sumOf { it.second }
    val largest = shown.maxOf { it.second }
    StatPanel(title, action) {
        shown.forEach { (label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                    modifier = Modifier.width(80.dp),
                )
                Bar(count.toFloat() / largest, Modifier.weight(1f))
                BasicText(
                    if (count == 0) "0" else "$count · ${percent(count, total)}",
                    maxLines = 1,
                    style = TextStyle(color = theme.foreground, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 10.dp).width(68.dp),
                )
            }
        }
    }
}

private class RankedRow(val name: String, val count: Int, val meanScore: Double?, val route: Route?)

@Composable
private fun RankedBars(title: String, rows: List<RankedRow>) {
    if (rows.isEmpty()) return
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val largest = rows.maxOf { it.count }
    StatPanel(title) {
        rows.forEach { row ->
            val route = row.route
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (route != null) Modifier.clickable { ui.navigator.open(route) } else Modifier)
                    .padding(vertical = 2.dp),
            ) {
                Row {
                    BasicText(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                    )
                    val mean = row.meanScore?.let { " · avg %.1f".format(java.util.Locale.ENGLISH, it) }.orEmpty()
                    BasicText(
                        "${row.count}$mean",
                        maxLines = 1,
                        style = TextStyle(color = theme.foreground, fontSize = 12.sp),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Bar(row.count.toFloat() / largest, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun percent(count: Int, total: Int): String {
    val share = count * 100 / total
    return if (share == 0) "<1%" else "$share%"
}

@Composable
private fun FavoritesTab(retained: Retained, load: LoadState<Favourites>) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val filter: MutableState<FavouriteFilter> = retained.get("favFilter") { mutableStateOf(FavouriteFilter.All) }
    val grid = retained.get("favGrid") { LazyGridState() }
    val chips = retained.get("favChips") { LazyListState() }
    val all = load.value?.items().orEmpty()
    val shown = if (filter.value == FavouriteFilter.All) all else all.filter { it.filter == filter.value }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
        ) {
            items(FavouriteFilter.entries, key = { it.name }) { option ->
                val count = if (option == FavouriteFilter.All) all.size else all.count { it.filter == option }
                FilterChip("${option.label} $count", filter.value == option) { filter.value = option }
            }
        }
        LazyVerticalGrid(
            state = grid,
            columns = coverColumns(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val fullWidth: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan =
                { GridItemSpan(maxLineSpan) }
            if (load.stale) item(span = fullWidth) { StaleNotice(true) }
            when {
                load.value == null && load.error != null -> item(span = fullWidth) {
                    ErrorRow(load.error!!) { load.load() }
                }
                load.value == null -> item(span = fullWidth) { Loading() }
                shown.isEmpty() -> item(span = fullWidth) { Hint("No favorites here yet") }
            }
            items(
                shown,
                key = { it.key },
                span = { item -> if (item.filter == FavouriteFilter.Studios) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            ) { item ->
                if (item.filter == FavouriteFilter.Studios) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(theme.surface.copy(alpha = 0.72f)),
                    ) {
                        ActionRow(item.title, onClick = { ui.navigator.open(item.route) }, description = "Studio")
                    }
                } else {
                    CoverCard(
                        title = item.title,
                        subtitle = item.subtitle,
                        url = item.image,
                        onClick = { ui.navigator.open(item.route) },
                    )
                }
            }
        }
    }
}
