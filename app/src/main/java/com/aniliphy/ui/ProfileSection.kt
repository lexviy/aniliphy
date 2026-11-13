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
import com.aniliphy.anilist.AchievementResult
import com.aniliphy.anilist.Achievements
import com.aniliphy.anilist.ActivityCalendar
import com.aniliphy.anilist.ProfileExtras
import com.aniliphy.anilist.Viewer
import com.aniliphy.anilist.plainBio
import java.time.LocalDate

private enum class ProfileTab(val label: String) {
    Overview("Overview"), Stats("Stats"), Activity("Activity"), Achievements("Achievements"), Goals("Goals"), Favorites("Favorites"),
}

private const val PROFILE_URL = "https://anilist.co/user/"

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
internal fun ProfileSection(retained: Retained, onMenu: (() -> Unit)?) {
    val ui = LocalAniList.current
    val viewer = (ui.session.state as? SessionState.LoggedIn)?.viewer
    val wrapped: MutableState<Int?> = retained.get("wrapped") { mutableStateOf(null) }
    Box(Modifier.fillMaxSize()) {
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
        val more = retained.get("profileExtras") { ui.load<ProfileExtras> { ui.api.profileExtras(viewer.id, it) } }
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
                // Only what a list change can change: the lists themselves (shared with the other screens, so usually a
                // single request), the stats and recent activity, and the activity history. Favourites have their own
                // signal, and the genres, tags and people below are fetched again only if the titles themselves changed.
                anime.load()
                paper.load()
                overview.load()
                more.load()
            }
        }
        // Which titles are on the lists, and as what: the details behind the stats only depend on this, not on progress.
        val titlesOnLists = remember(anime.value, paper.value) {
            if (anime.value == null || paper.value == null) null
            else (anime.value.orEmpty() + paper.value.orEmpty()).map { it.media.id to it.status }.toSet().hashCode()
        }
        val seenTitles: MutableState<Int?> = retained.get("titlesOnLists") { mutableStateOf(null) }
        LaunchedEffect(titlesOnLists) {
            if (titlesOnLists == null) return@LaunchedEffect
            val before = seenTitles.value
            seenTitles.value = titlesOnLists
            if (before != null && before != titlesOnLists) extras.reload()
        }
        LaunchedEffect(favourites.value) {
            favourites.value?.let { found -> ui.onList.knownFavourites((found.anime.nodes + found.manga.nodes).map { it.id }.toSet()) }
        }
        val seenFavourites: MutableState<Int> = retained.get("favouritesVersion") { mutableStateOf(ui.navigator.favouritesVersion) }
        LaunchedEffect(ui.navigator.favouritesVersion) {
            if (seenFavourites.value != ui.navigator.favouritesVersion) {
                seenFavourites.value = ui.navigator.favouritesVersion
                favourites.load()
            }
        }

        val novels = paper.value?.ofKind(MediaKind.LightNovel)?.size
        val mangaCount = paper.value?.ofKind(MediaKind.Manga)?.size
        val counts = listOfNotNull(
            anime.value?.size?.let { "$it anime" },
            mangaCount?.let { "$it manga" },
            novels?.let { "$it light novels" },
        ).joinToString(" · ")
        val statistics = overview.value?.viewer?.statistics ?: viewer.statistics
        val bio = remember(more.value?.about) { plainBio(more.value?.about) }
        // The card only on the overview, so the other tabs get the screen.
        if (tab.value == ProfileTab.Overview) ProfileCard(
            viewer.name, viewer.avatar?.best, counts, statistics.anime.episodesWatched, statistics.manga.chaptersRead,
            banner = more.value?.bannerImage, bio = bio,
        )
        val tabsRow = retained.get("tabsRow") { LazyListState() }
        LazyRow(
            state = tabsRow,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
        ) {
            items(ProfileTab.entries, key = { it.name }) { option ->
                FilterChip(option.label, tab.value == option) { tab.value = option }
            }
        }
        val today = remember { LocalDate.now() }
        val achievements = remember(anime.value, paper.value) {
            val a = anime.value
            val p = paper.value
            if (a == null || p == null) null else Achievements.of(a, p, today) + Achievements.seasons(a)
        }
        val aniListDays = remember(more.value) { ActivityCalendar.aniListDays(more.value?.stats?.activityHistory.orEmpty()) }
        val context = LocalContext.current
        LaunchedEffect(aniListDays) {
            if (aniListDays.isNotEmpty()) {
                val days = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aniliphy.core.ActivityLog.all(context) }
                ProfileWatch.streak(ProfileLocal.get(context), ActivityCalendar.merge(aniListDays, days.mapValues { it.value.amount() }), today)
            }
        }

        val pull = rememberPullToRefreshState()
        val refreshing = when (tab.value) {
            ProfileTab.Overview -> overview.loading && overview.value != null
            ProfileTab.Stats -> (anime.loading && anime.value != null) || (paper.loading && paper.value != null)
            ProfileTab.Favorites -> favourites.loading && favourites.value != null
            ProfileTab.Activity -> more.loading && more.value != null
            ProfileTab.Achievements, ProfileTab.Goals -> (anime.loading && anime.value != null) || (paper.loading && paper.value != null)
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
                    ProfileTab.Activity -> {
                        more.load(refresh = true)
                        retained.get<ActivityFeed?>("activityFeed") { null }?.refresh()
                    }
                    ProfileTab.Achievements, ProfileTab.Goals -> {
                        anime.load(refresh = true)
                        paper.load(refresh = true)
                    }
                }
            },
            state = pull,
            indicator = {
                LookRefreshIndicator(
                state = pull,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (tab.value) {
                ProfileTab.Overview -> OverviewTab(
                    retained, overview, viewer, achievements,
                    onAchievements = { tab.value = ProfileTab.Achievements },
                    onWrapped = { wrapped.value = it },
                )
                ProfileTab.Stats -> StatsTab(retained, anime, paper, extras)
                ProfileTab.Activity -> ActivityTab(retained, viewer.id, aniListDays, more)
                ProfileTab.Achievements -> AchievementsTab(retained, achievements, anime.error ?: paper.error) { anime.load(); paper.load() }
                ProfileTab.Goals -> GoalsTab(anime.value, paper.value)
                ProfileTab.Favorites -> FavoritesTab(retained, favourites)
            }
        }
    }
    wrapped.value?.let { year ->
        val a = retained.get<LoadState<List<ListEntry>>?>("anime") { null }?.value
        val p = retained.get<LoadState<List<ListEntry>>?>("paper") { null }?.value
        if (a != null && p != null) WrappedScreen(year, a, p) { wrapped.value = null } else wrapped.value = null
    }
    }
}

@Composable
private fun OverviewTab(
    retained: Retained,
    load: LoadState<Overview>,
    viewer: Viewer,
    achievements: List<AchievementResult>?,
    onAchievements: () -> Unit,
    onWrapped: (Int) -> Unit,
) {
    val name = viewer.name
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    val scroll = retained.get("overviewScroll") { ScrollState(0) }
    var confirmLogout by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        StaleNotice(load.stale)
        Showcase(achievements, onAchievements)
        OnThisDay(retained, viewer.id)
        WrappedCard(onWrapped)
        SectionTitle("Recent activity")
        val activities = load.value?.activities
        when {
            activities == null && load.error != null -> ErrorRow(load.error!!) { load.load() }
            activities == null -> Hint("Loading...")
            activities.isEmpty() -> Empty("Nothing here yet")
            else -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                activities.forEach { activity -> ActivityRow(activity) }
            }
        }
        SectionTitle("Account")
        ActionRow("Open profile on AniList", onClick = { context.openLink(PROFILE_URL + Uri.encode(name)) })
        val shareScope = rememberCoroutineScope()
        ActionRow("Share profile picture", onClick = {
            val stats = load.value?.viewer?.statistics ?: viewer.statistics
            shareScope.launch {
                ShareImage.share(
                    context, theme,
                    ShareCard(
                        kicker = "On AniList",
                        title = name,
                        lines = listOf(
                            "${stats.anime.count} anime · %,d episodes".format(stats.anime.episodesWatched),
                            "${stats.manga.count} manga · %,d chapters".format(stats.manga.chaptersRead),
                        ),
                        cover = viewer.avatar?.best,
                        link = PROFILE_URL + Uri.encode(name),
                    ),
                    PROFILE_URL + Uri.encode(name),
                )
            }
        })
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
internal fun ActivityRow(activity: Activity) {
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
internal fun FavoritesTab(retained: Retained, load: LoadState<Favourites>) {
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
                shown.isEmpty() -> item(span = fullWidth) { Empty("No favorites here yet") }
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
