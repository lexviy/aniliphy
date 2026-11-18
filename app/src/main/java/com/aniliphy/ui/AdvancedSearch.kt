package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.FilterChoices
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Paged
import com.aniliphy.anilist.Pick
import com.aniliphy.anilist.SavedFilters
import com.aniliphy.anilist.SearchFilters
import com.aniliphy.anilist.SearchSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The filters the Home search uses; null while none are applied (it then searches everything, as before). */
internal object AdvancedSearch {
    var applied by mutableStateOf<SearchFilters?>(null)
    /** What the filters page is editing. */
    var draft by mutableStateOf(SearchFilters())
    var total by mutableStateOf<Int?>(null)
}

/** The results with filters on: the chips of what's set, then the covers. */
@Composable
internal fun FilteredResults(filters: SearchFilters, text: String, retained: Retained) {
    val ui = LocalAniList.current
    var total by remember { mutableStateOf<Int?>(null) }
    val paged = retained.get("browse") {
        ui.paged<Media>(Media::id) { Fetched(Paged(emptyList(), false)) }
    }
    LaunchedEffect(filters, text) {
        if (text.isNotBlank()) delay(SEARCH_DELAY)
        total = null
        paged.restart { page ->
            ui.api.browse(filters, text, page).also { if (page == 1) total = it.value.total }.map { Paged(it.items, it.hasNext) }
        }
    }
    val grid = retained.get("browseGrid") { LazyGridState() }
    LoadMoreEffect(grid, paged)
    LazyVerticalGrid(
        state = grid,
        columns = coverColumns(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    item { FilterChip(Formatting.kindLabel(filters.kind), true) { ui.navigator.open(Route.Filters) } }
                    items(filters.summary()) { label -> FilterChip(label, false) { ui.navigator.open(Route.Filters) } }
                    item { FilterChip("✕ Clear", false) { AdvancedSearch.applied = null } }
                }
                total?.let { Hint(if (it == 1) "1 title" else if (it >= ANILIST_MAX_TOTAL) "Over $it titles" else "$it titles", Modifier.padding(horizontal = 0.dp)) }
                if (paged.stale) StaleNotice(true)
            }
        }
        items(paged.items, key = Media::id) { m ->
            CoverCard(
                title = m.title.display,
                subtitle = listOfNotNull(Formatting.format(m.format), m.seasonYear?.toString()).joinToString(" · "),
                url = m.coverImage.best,
                onClick = { ui.navigator.open(Route.Media(m.id)) },
                detail = m.averageScore?.let { "★ $it%" },
                mediaId = m.id,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            when {
                paged.error != null -> ErrorRow(paged.error!!) { paged.retry() }
                paged.loading -> Loading()
                paged.items.isEmpty() -> NothingFound()
                !paged.hasNext -> EndOfList()
            }
        }
    }
}

/** The filters page, opened from "Filters" by the Home search. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FiltersPage(retained: Retained) {
    val ui = LocalAniList.current
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    val saved = remember { SavedFilters.of(context) }
    var presets by remember { mutableStateOf(saved.all()) }
    var naming by remember { mutableStateOf(false) }
    var allGenres by remember { mutableStateOf(false) }
    var tagSearch by remember { mutableStateOf("") }
    val f = AdvancedSearch.draft
    fun set(value: SearchFilters) { AdvancedSearch.draft = value }

    val choices by produceState<FilterChoices?>(null) {
        value = try {
            withContext(Dispatchers.Default) { ui.api.filterChoices().value }
        } catch (_: AniListException) {
            FilterChoices(FALLBACK_GENRES, emptyList())
        }
    }
    // How many titles match, asked once the filters stop changing.
    LaunchedEffect(f) {
        AdvancedSearch.total = null
        delay(600)
        AdvancedSearch.total = try {
            withContext(Dispatchers.Default) { ui.api.browse(f, null, 1).value.total }
        } catch (_: AniListException) {
            null
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Filters") { ui.navigator.back() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
            Label("LOOK FOR")
            ChipRow {
                MediaKind.entries.forEach { k ->
                    FilterChip(Formatting.kindLabel(k), f.kind == k) {
                        if (k != f.kind) set(f.copy(kind = k, formats = emptySet(), season = null))
                    }
                }
            }

            Label("GENRES · TAP ONCE TO WANT, TWICE TO LEAVE OUT")
            val genres = choices?.genres ?: FALLBACK_GENRES
            // The order never changes under your finger: picked ones past the first few are added at the end.
            val shownGenres = if (allGenres) genres else genres.take(GENRES_SHORT) + genres.drop(GENRES_SHORT).filter { it in f.genres }
            ChipRow {
                shownGenres.forEach { g -> PickChip(g, f.genres[g]) { set(f.copy(genres = cycle(f.genres, g))) } }
                if (!allGenres && genres.size > shownGenres.size) FilterChip("+ ${genres.size - shownGenres.size} more", false) { allGenres = true }
            }

            Label("TAGS")
            ChipRow {
                f.tags.forEach { (t, p) -> PickChip(t, p) { set(f.copy(tags = cycle(f.tags, t))) } }
            }
            InputBox(tagSearch, { tagSearch = it }, "Find a tag…", Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth())
            if (tagSearch.isNotBlank()) {
                val found = choices?.tags.orEmpty().filter { it.name.contains(tagSearch.trim(), true) && it.name !in f.tags }.take(12)
                ChipRow {
                    if (choices == null) Hint("Loading tags…")
                    found.forEach { t ->
                        FilterChip(t.name, false) {
                            set(f.copy(tags = f.tags + (t.name to Pick.Want)))
                            tagSearch = ""
                        }
                    }
                    if (choices != null && found.isEmpty()) Hint("No tag called that")
                }
            }

            Label("YEAR")
            YearRow(f.yearFrom, f.yearTo) { from, to -> set(f.copy(yearFrom = from, yearTo = to)) }

            if (f.kind == MediaKind.Anime) {
                Label("SEASON")
                ChipRow {
                    FilterChip("Any", f.season == null) { set(f.copy(season = null)) }
                    SearchFilters.SEASONS.forEach { s -> FilterChip(SearchFilters.seasonLabel(s), f.season == s) { set(f.copy(season = if (f.season == s) null else s)) } }
                }
            }
            if (f.kind != MediaKind.LightNovel) {
                Label("FORMAT")
                ChipRow {
                    FilterChip("Any", f.formats.isEmpty()) { set(f.copy(formats = emptySet())) }
                    val formats = if (f.kind == MediaKind.Anime) SearchFilters.ANIME_FORMATS else SearchFilters.MANGA_FORMATS
                    formats.forEach { x -> FilterChip(SearchFilters.formatLabel(x), x in f.formats) { set(f.copy(formats = toggle(f.formats, x))) } }
                }
            }
            Label("STATUS")
            ChipRow {
                FilterChip("Any", f.statuses.isEmpty()) { set(f.copy(statuses = emptySet())) }
                SearchFilters.STATUSES.forEach { x -> FilterChip(SearchFilters.statusLabel(x), x in f.statuses) { set(f.copy(statuses = toggle(f.statuses, x))) } }
            }
            Label("COUNTRY")
            ChipRow {
                FilterChip("Any", f.country == null) { set(f.copy(country = null)) }
                SearchFilters.COUNTRIES.forEach { c -> FilterChip(SearchFilters.countryLabel(c), f.country == c) { set(f.copy(country = if (f.country == c) null else c)) } }
            }
            Label("SORT")
            ChipRow {
                SearchSort.entries.forEach { s -> FilterChip(s.label, f.sort == s) { set(f.copy(sort = s)) } }
            }
            if (ui.session.userId != null) {
                SwitchRow("Hide what's on my list", f.hideMine, { set(f.copy(hideMine = it)) })
            }

            Label("SAVED")
            ChipRow {
                presets.forEach { p ->
                    FilterChip(p.name, p.filters == f) { set(p.filters) }
                }
                FilterChip("＋ Save these", false) { naming = true }
            }
            if (presets.isNotEmpty()) {
                ChipRow {
                    presets.forEach { p ->
                        FilterChip("✕ ${p.name}", false) {
                            scope.launch(Dispatchers.IO) { saved.remove(p.name) }
                            presets = presets.filter { it.name != p.name }
                        }
                    }
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            PillButton("Clear", onClick = { set(SearchFilters(kind = f.kind)) }, filled = false)
            val total = AdvancedSearch.total
            PillButton(
                when {
                    total == null -> "Show"
                    total >= ANILIST_MAX_TOTAL -> "Show $total+"
                    else -> "Show $total"
                },
                onClick = {
                    AdvancedSearch.applied = f
                    ui.navigator.back()
                },
                alive = true,
            )
        }
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        DialogFrame(onDismiss = { naming = false }) {
            BasicText("Save these filters", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            InputBox(name, { name = it.take(40) }, "A name, like Cosy fantasy")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                PillButton("Cancel", onClick = { naming = false }, filled = false)
                PillButton("Save", enabled = name.isNotBlank(), onClick = {
                    val n = name.trim()
                    scope.launch(Dispatchers.IO) { saved.save(n, f) }
                    presets = presets.filter { it.name != n } + com.aniliphy.anilist.SavedFilter(n, f)
                    naming = false
                })
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    BasicText(
        text,
        style = NumberStyle(color = LocalAppTheme.current.foreground.copy(alpha = 0.6f), fontSize = 11.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 20.dp),
    ) { content() }
}

/** A genre or tag: plain, wanted (+) or left out (struck through). */
@Composable
private fun PickChip(label: String, pick: Pick?, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    val color = when (pick) {
        Pick.Want -> theme.primary
        Pick.LeaveOut -> theme.error
        null -> theme.foreground.copy(alpha = 0.35f)
    }
    BasicText(
        when (pick) {
            Pick.Want -> "+ $label"
            Pick.LeaveOut -> "− $label"
            null -> label
        },
        maxLines = 1,
        style = TextStyle(
            color = if (pick == null) theme.foreground else color,
            fontSize = 13.sp,
            fontWeight = if (pick != null) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (pick == Pick.LeaveOut) TextDecoration.LineThrough else null,
        ),
        modifier = Modifier
            .clip(lookShape(10.dp))
            .background(theme.surface)
            .border(1.5.dp, color, lookShape(10.dp))
            .lookAlive(Alive.Button, active = pick != null)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** From and to, a year at a time; tapping the middle clears it. */
@Composable
private fun YearRow(from: Int?, to: Int?, onChange: (Int?, Int?) -> Unit) {
    val now = java.time.Year.now().value
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp)) {
        YearStepper("From", from, { onChange(it, to?.let { t -> if (it != null && t < it) it else t }) }, now)
        Spacer(Modifier.width(12.dp))
        YearStepper("To", to, { onChange(from?.let { f -> if (it != null && f > it) it else f }, it) }, now + 1)
    }
}

@Composable
private fun YearStepper(label: String, value: Int?, onChange: (Int?) -> Unit, start: Int) {
    val theme = LocalAppTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText("$label ", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp))
        FilterChip("−", false) { onChange((value ?: start) - 1) }
        BasicText(
            value?.toString() ?: "Any",
            style = NumberStyle(color = if (value != null) theme.primary else theme.foreground, fontSize = 14.sp, bold = true),
            modifier = Modifier.clickable { onChange(null) }.padding(horizontal = 10.dp, vertical = 6.dp),
        )
        FilterChip("+", false) { onChange(((value ?: start) + 1).coerceAtMost(start + 2)) }
    }
}

private fun cycle(map: Map<String, Pick>, key: String): Map<String, Pick> = when (map[key]) {
    null -> map + (key to Pick.Want)
    Pick.Want -> map + (key to Pick.LeaveOut)
    Pick.LeaveOut -> map - key
}

private fun toggle(set: Set<String>, x: String) = if (x in set) set - x else set + x

private const val GENRES_SHORT = 8
/** AniList stops counting here. */
private const val ANILIST_MAX_TOTAL = 5000
private val FALLBACK_GENRES = listOf(
    "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music",
    "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
)
