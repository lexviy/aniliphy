package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AnimeSeason
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.SessionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** A season Home asked the explorer to open on. */
internal object SeasonsRequest {
    var season by mutableStateOf<AnimeSeason?>(null)
}

private enum class SeasonFormat(val label: String, val formats: Set<String>?) {
    All("All", null), Tv("TV", setOf("TV", "TV_SHORT")), Movie("Movie", setOf("MOVIE")), Ona("ONA", setOf("ONA")), Other("OVA & specials", setOf("OVA", "SPECIAL", "MUSIC")),
}

private enum class SeasonSort(val label: String) { Popular("Popular"), Score("Score"), Start("Start date"), Sequels("Sequels of yours") }

/** How long "+ Planning" waits for an Undo before it's sent. */
private const val ADD_UNDO_MS = 4_000L

/** Most a season loads (AniList pages of 50). */
private const val SEASON_PAGES = 5

private fun AnimeSeason.previous(): AnimeSeason {
    val names = listOf("WINTER", "SPRING", "SUMMER", "FALL")
    val i = names.indexOf(name)
    return if (i == 0) AnimeSeason(names.last(), year - 1) else AnimeSeason(names[i - 1], year)
}

/** A whole season, to pick what to watch next. */
@Composable
internal fun SeasonsSection(retained: Retained, onMenu: (() -> Unit)?) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val loggedIn = ui.session.state is SessionState.LoggedIn
    val season: MutableState<AnimeSeason> = retained.get("season") { mutableStateOf(AnimeSeason.of(LocalDate.now()).next()) }
    SeasonsRequest.season?.let {
        season.value = it
        SeasonsRequest.season = null
    }
    val format: MutableState<SeasonFormat> = retained.get("format") { mutableStateOf(SeasonFormat.All) }
    val sort: MutableState<SeasonSort> = retained.get("sort") { mutableStateOf(SeasonSort.Popular) }
    val genre: MutableState<String?> = retained.get("genre") { mutableStateOf(null) }
    val hideOnList: MutableState<Boolean> = retained.get("hide") { mutableStateOf(false) }
    val s = season.value
    val load = retained.get("load${s.name}${s.year}") {
        ui.load<List<Media>> { refresh ->
            val all = mutableListOf<Media>()
            var page = 1
            var stale = false
            while (page <= SEASON_PAGES) {
                val got = ui.api.seasonExplore(s.name, s.year, page, refresh = refresh)
                stale = stale || got.stale
                all += got.value.items
                if (!got.value.hasNext) break
                page++
            }
            Fetched(all.distinctBy { it.id }, stale)
        }
    }
    var genresOpen by remember { mutableStateOf(false) }
    val pending = remember { mutableStateMapOf<Int, Job>() }
    val failed = remember { mutableStateMapOf<Int, String>() }

    fun sequelOfYours(m: Media) = m.relations.edges.any { e ->
        e.relationType == "PREQUEL" && e.node?.let { ui.entryFor(it.id) }?.status.let { it == ListStatus.COMPLETED || it == ListStatus.CURRENT }
    }

    val shown = remember(load.value, format.value, sort.value, genre.value, hideOnList.value, ui.onList.entries.size) {
        load.value.orEmpty()
            .filter { m -> format.value.formats?.let { m.format in it } ?: true }
            .filter { m -> genre.value?.let { it in m.genres } ?: true }
            .filter { m -> !hideOnList.value || ui.entryFor(m.id) == null }
            .filter { m -> sort.value != SeasonSort.Sequels || sequelOfYours(m) }
            .let { list ->
                when (sort.value) {
                    SeasonSort.Popular, SeasonSort.Sequels -> list
                    SeasonSort.Score -> list.sortedByDescending { it.averageScore ?: 0 }
                    SeasonSort.Start -> list.sortedWith(compareBy({ it.startDate?.year ?: 9999 }, { it.startDate?.month ?: 13 }, { it.startDate?.day ?: 32 }))
                }
            }
    }

    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Seasons, onMenu)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            FilterChip("◂", false) { season.value = s.previous() }
            BasicText(s.label, modifier = Modifier.weight(1f), style = TitleStyle(color = theme.foreground, fontSize = 19.sp).copy(textAlign = TextAlign.Center))
            FilterChip("▸", false) { season.value = s.next() }
        }
        val formatRow = retained.get("formatRow") { LazyListState() }
        LazyRow(state = formatRow, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            items(SeasonFormat.entries, key = { it.name }) { f -> FilterChip(f.label, format.value == f) { format.value = f } }
            item { FilterChip(genre.value?.let { "$it ▾" } ?: "Genre ▾", genre.value != null) { genresOpen = true } }
            if (loggedIn) item { FilterChip("Hide on my list", hideOnList.value) { hideOnList.value = !hideOnList.value } }
        }
        val sortRow = retained.get("sortRow") { LazyListState() }
        LazyRow(state = sortRow, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            items(SeasonSort.entries.filter { loggedIn || it != SeasonSort.Sequels }, key = { it.name }) { o -> FilterChip(o.label, sort.value == o) { sort.value = o } }
        }
        val grid = retained.get("grid${s.name}${s.year}") { LazyGridState() }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (load.value == null) {
                item(span = { GridItemSpan(maxLineSpan) }) { if (load.error != null) ErrorRow(load.error!!) { load.load() } else Loading() }
            } else if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Empty("Nothing matches") }
            }
            items(shown, key = { it.id }) { m ->
                SeasonCard(
                    media = m,
                    sequel = loggedIn && sequelOfYours(m),
                    adding = m.id in pending,
                    failed = failed[m.id],
                    canAdd = loggedIn,
                    onAdd = {
                        failed.remove(m.id)
                        pending[m.id] = ui.scope.launch {
                            delay(ADD_UNDO_MS)
                            try {
                                ui.api.saveEntry(m.id, ListStatus.PLANNING)
                                ui.navigator.listsChanged()
                            } catch (e: AniListException) {
                                failed[m.id] = ui.describe(e)
                            } finally {
                                pending.remove(m.id)
                            }
                        }
                    },
                    onUndo = { pending.remove(m.id)?.cancel() },
                )
            }
        }
    }
    if (genresOpen) {
        val genres = load.value.orEmpty().flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        androidx.compose.ui.window.Dialog(onDismissRequest = { genresOpen = false }) {
            Column(Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).padding(vertical = 10.dp)) {
                (listOf<String?>(null) + genres).forEach { g ->
                    BasicText(
                        g ?: "Any genre",
                        style = TextStyle(color = if (genre.value == g) theme.primary else theme.foreground, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().clickable { genre.value = g; genresOpen = false }.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonCard(media: Media, sequel: Boolean, adding: Boolean, failed: String?, canAdd: Boolean, onAdd: () -> Unit, onUndo: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val entry = ui.entryFor(media.id)
    Column(
        Modifier
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.88f))
            .clickable { ui.navigator.open(Route.Media(media.id)) },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            Cover(media.coverImage.best, Modifier.fillMaxSize())
            entry?.let { e ->
                BasicText(
                    (ListStatus.label(e.status, MediaKind.Anime) ?: "On list").uppercase(),
                    style = NumberStyle(color = theme.background, fontSize = 9.sp, bold = true),
                    modifier = Modifier.padding(6.dp).clip(lookShape(4.dp)).background(theme.primary).padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Column(Modifier.padding(7.dp)) {
            BasicText(media.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 15.sp))
            val line = listOfNotNull(
                media.format?.let { Formatting.format(it) },
                media.startDate?.let { d -> d.month?.let { m -> java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + (d.day?.let { " $it" } ?: "") } },
                media.studios.nodes.firstOrNull()?.name,
            ).joinToString(" · ")
            BasicText(line, maxLines = 1, overflow = TextOverflow.Ellipsis, style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 10.sp))
            BasicText(
                if (sequel) "Your sequel" else media.genres.take(2).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = NumberStyle(color = if (sequel) theme.accent else theme.foreground.copy(alpha = 0.6f), fontSize = 10.sp, bold = sequel),
            )
            if (canAdd) {
                val (label, filled, action) = when {
                    entry != null -> Triple("✓ On list", true, null)
                    adding -> Triple("Adding… Undo", false, onUndo)
                    failed != null -> Triple("Failed · retry", false, onAdd)
                    else -> Triple("+ Planning", false, onAdd)
                }
                BasicText(
                    label,
                    style = NumberStyle(color = if (filled) theme.background else theme.primary, fontSize = 11.sp, bold = true).copy(textAlign = TextAlign.Center),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .clip(lookShape(6.dp))
                        .then(if (filled) Modifier.background(theme.primary) else Modifier.border(1.dp, theme.primary, lookShape(6.dp)))
                        .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
                        .padding(vertical = 5.dp),
                )
            }
        }
    }
}
