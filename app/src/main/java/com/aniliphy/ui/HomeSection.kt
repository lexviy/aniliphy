package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AnimeSeason
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.PersonRef
import com.aniliphy.anilist.SearchResults
import com.aniliphy.anilist.StudioRef
import kotlinx.coroutines.delay
import java.time.LocalDate
import com.aniliphy.manga.LatestChapter
import com.aniliphy.manga.MangaRecord
import com.aniliphy.manga.NewChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun HomeSection(retained: Retained, onMenu: () -> Unit) {
    val ui = LocalAniList.current
    val text: MutableState<String> = retained.get("text") { mutableStateOf("") }
    val shown: MutableState<String> = retained.get("shownText") { mutableStateOf("") }
    val results = retained.get("results") { ui.load<SearchResults>(autoStart = false) { Fetched(SearchResults()) } }

    LaunchedEffect(text.value) {
        val typed = text.value.trim()
        if (typed == shown.value) return@LaunchedEffect
        if (typed.isNotEmpty()) delay(SEARCH_DELAY)
        shown.value = typed
        if (typed.isNotEmpty()) results.restart { ui.api.searchEverything(typed) }
    }

    BackHandler(enabled = text.value.isNotEmpty()) { text.value = "" }

    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Home, onMenu, text.value, { text.value = it })
        if (text.value.isBlank()) {
            HomeLists(retained)
        } else {
            SearchResultsList(results, retained)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeLists(retained: Retained) {
    val ui = LocalAniList.current
    val userId = ui.session.userId
    val thisSeason = remember { AnimeSeason.of(LocalDate.now()) }
    val nextSeason = remember { thisSeason.next() }

    val airing = retained.get("airing") {
        ui.load<List<ListEntry>> { refresh ->
            if (userId != null) ui.api.airingFromList(userId, refresh) else Fetched(emptyList())
        }
    }
    val current = retained.get("current") { ui.paged<Media>(Media::id) { page -> ui.api.season(thisSeason, page) } }
    val coming = retained.get("coming") { ui.paged<Media>(Media::id) { page -> ui.api.season(nextSeason, page) } }
    val airingRow = retained.get("airingRow") { LazyListState() }
    val currentRow = retained.get("currentRow") { LazyListState() }
    val comingRow = retained.get("comingRow") { LazyListState() }
    val scroll = retained.get("scroll") { androidx.compose.foundation.ScrollState(0) }

    val now by produceState(Formatting.nowSeconds()) {
        while (true) {
            delay(COUNTDOWN_TICK)
            value = Formatting.nowSeconds()
        }
    }
    val theme = LocalAppTheme.current
    val pull = rememberPullToRefreshState()

    LoadMoreEffect(currentRow, current)
    LoadMoreEffect(comingRow, coming)

    PullToRefreshBox(
        isRefreshing = airing.loading && airing.value != null,
        onRefresh = {
            airing.load(refresh = true)
            if (current.error != null) current.retry()
            if (coming.error != null) coming.retry()
        },
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = airing.loading && airing.value != null,
                containerColor = theme.surface,
                color = theme.primary,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 20.dp)) {
            StaleNotice(airing.stale || current.stale || coming.stale)
            if (userId != null) {
                val entries = airing.value.orEmpty()
                when {
                    entries.isNotEmpty() -> CoverRow(
                        title = "Airing from your list",
                        items = entries,
                        key = { it.media.id },
                        state = airingRow,
                    ) { entry ->
                        CoverCard(
                            title = entry.media.title.display,
                            subtitle = ListStatus.label(entry.status, entry.media.kind),
                            detail = Formatting.airingLine(entry.media, now),
                            url = entry.media.coverImage.best,
                            onClick = { ui.navigator.open(Route.Media(entry.media.id)) },
                            wide = false,
                            repeats = entry.repeat,
                        )
                    }
                    airing.error != null -> {
                        SectionTitle("Airing from your list")
                        ErrorRow(airing.error!!) { airing.load() }
                    }
                    airing.loading -> {
                        SectionTitle("Airing from your list")
                        Hint("Loading...")
                    }
                    else -> {
                        SectionTitle("Airing from your list")
                        Hint("Nothing on your list is airing right now")
                    }
                }
            }
            NewChaptersRow(retained)
            SeasonRow("${thisSeason.label} season", current, currentRow) { Formatting.animeDetails(it) }
            SeasonRow("Coming in ${nextSeason.label}", coming, comingRow) { media ->
                media.startDate?.takeIf { it.year != null }?.let(Formatting::date) ?: Formatting.animeDetails(media)
            }
        }
    }
}

@Composable
private fun NewChaptersRow(retained: Retained) {
    val ui = LocalAniList.current
    val manga = LocalManga.current
    val row = retained.get("newChaptersRow") { LazyListState() }
    val local by produceState<Pair<Map<Int, MangaRecord>, Map<Int, LatestChapter>>?>(null, manga.historyVersion) {
        value = withContext(Dispatchers.IO) { manga.history.all() to manga.newChapters.all() }
    }
    LaunchedEffect(local != null) {
        local?.let { (records, _) -> manga.checkNewChapters(records.filterValues { it.hasPlace }) }
    }
    val items = remember(local) {
        val (records, latest) = local ?: return@remember emptyList()
        records.mapNotNull { (id, record) ->
            val item = latest[id]
            if (record.title != null && NewChapters.isNew(item, record)) Triple(id, record, item!!.latest) else null
        }.sortedByDescending { it.second.updated }
    }
    if (items.isEmpty()) return
    CoverRow(title = "New chapters", items = items, key = { it.first }, state = row) { (id, record, latest) ->
        CoverCard(
            title = record.title.orEmpty(),
            subtitle = record.chapterNumber?.let { "Read up to chapter ${chapterNumber(it)}" },
            detail = "Chapter ${chapterNumber(latest)} out",
            url = record.cover,
            onClick = { ui.navigator.open(Route.Media(id)) },
            wide = false,
        )
    }
}

private fun chapterNumber(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

@Composable
private fun SeasonRow(
    title: String,
    paged: PagedState<Media>,
    state: LazyListState,
    subtitle: (Media) -> String,
) {
    val ui = LocalAniList.current
    when {
        paged.items.isNotEmpty() -> CoverRow(title, paged.items, { it.id }, state) { media ->
            CoverCard(
                title = media.title.display,
                subtitle = subtitle(media),
                url = media.coverImage.best,
                onClick = { ui.navigator.open(Route.Media(media.id)) },
                wide = false,
            )
        }
        paged.error != null -> {
            SectionTitle(title)
            ErrorRow(paged.error!!) { paged.retry() }
        }
        paged.loading -> {
            SectionTitle(title)
            Hint("Loading...")
        }
    }
}

@Composable
private fun SearchResultsList(results: LoadState<SearchResults>, retained: Retained) {
    val ui = LocalAniList.current
    val found = results.value
    val scroll = retained.get("resultsScroll") { LazyListState() }
    val open = ui.navigator::open
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            StaleNotice(results.stale)
            results.error?.let { message -> ErrorRow(message) { results.load() } }
            if (found == null && results.error == null) Hint("Searching...")
            if (found != null && !results.loading && found.isEmpty()) Hint("Nothing found")
        }
        if (found == null) return@LazyColumn
        mediaGroup("Anime", found.anime, open)
        mediaGroup("Manga", found.manga, open)
        mediaGroup("Light Novels", found.lightNovels, open)
        peopleGroup("Characters", found.characters) { open(Route.Character(it)) }
        peopleGroup("Staff", found.staff) { open(Route.Staff(it)) }
        if (found.studios.isNotEmpty()) {
            item(key = "studios") {
                SectionTitle("Studios")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(found.studios, key = StudioRef::id) { studio ->
                        StudioChip(studio) { open(Route.Studio(studio.id)) }
                    }
                }
            }
        }
    }
}

private fun SearchResults.isEmpty() =
    anime.isEmpty() && manga.isEmpty() && lightNovels.isEmpty() &&
        characters.isEmpty() && staff.isEmpty() && studios.isEmpty()

private fun androidx.compose.foundation.lazy.LazyListScope.mediaGroup(
    title: String,
    media: List<Media>,
    open: (Route) -> Unit,
) {
    if (media.isEmpty()) return
    item(key = title) {
        CoverRow(title, media, { it.id }) { item ->
            CoverCard(
                title = item.title.display,
                subtitle = Formatting.format(item.format),
                url = item.coverImage.best,
                onClick = { open(Route.Media(item.id)) },
                wide = false,
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.peopleGroup(
    title: String,
    people: List<PersonRef>,
    open: (Int) -> Unit,
) {
    if (people.isEmpty()) return
    item(key = title) {
        CoverRow(title, people, { it.id }) { person ->
            CoverCard(
                title = person.name.full ?: "?",
                subtitle = person.name.native,
                url = person.image?.best,
                onClick = { open(person.id) },
                wide = false,
            )
        }
    }
}

@Composable
internal fun StudioChip(studio: StudioRef, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(theme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicText(studio.name ?: "?", maxLines = 1, style = TextStyle(color = theme.foreground, fontSize = 14.sp))
    }
}

internal const val SEARCH_DELAY = 400L
private const val COUNTDOWN_TICK = 30_000L
