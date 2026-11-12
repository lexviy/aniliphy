package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ofKind

@Composable
internal fun coverColumns(): GridCells =
    if (LocalCoverStyle.current.layout == CoverLayout.List) GridCells.Fixed(1) else GridCells.Adaptive(106.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListSection(kind: MediaKind, section: Section, retained: Retained, onMenu: () -> Unit) {
    val ui = LocalAniList.current
    val userId = ui.session.userId
    val text: MutableState<String> = retained.get("text") { mutableStateOf("") }
    val status: MutableState<String?> = retained.get("status") { mutableStateOf(null) }

    Column(Modifier.fillMaxSize()) {
        SectionBar(section, onMenu, text.value, { text.value = it })
        if (userId == null) {
            LoginInvite("Log in to see your ${Formatting.kindLabel(kind).lowercase()} list")
            return@Column
        }
        val load = retained.get("list") {
            ui.load<List<ListEntry>> { refresh ->
                val fetched = if (kind == MediaKind.Anime) {
                    ui.api.animeList(userId, refresh)
                } else {
                    ui.api.mangaList(userId, refresh)
                }
                fetched.map { list -> list.ofKind(kind).sortedByDescending { it.updatedAt ?: 0 } }
            }
        }
        val grid = retained.get("grid") { LazyGridState() }
        val chips = retained.get("chips") { LazyListState() }
        val theme = LocalAppTheme.current
        BackHandler(enabled = text.value.isNotEmpty()) { text.value = "" }
        val pull = rememberPullToRefreshState()
        val refreshing = load.loading && load.value != null

        val entries = load.value.orEmpty()
        val matching = entries.filter { Formatting.matches(it.media, text.value) }
        val shown = status.value?.let { wanted -> matching.filter { it.status == wanted } } ?: matching

        if (entries.isNotEmpty()) ListCount(shown.size, kind == MediaKind.Anime)
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            item(key = "all") {
                FilterChip("All ${matching.size}", status.value == null) { status.value = null }
            }
            items(ListStatus.ALL, key = { it }) { option ->
                val count = matching.count { it.status == option }
                FilterChip("${ListStatus.label(option, kind)} $count", status.value == option) {
                    status.value = option
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { load.load(refresh = true) },
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
            when {
                load.value == null && load.error != null -> ErrorRow(load.error!!) { load.load() }
                load.value == null -> Loading()
                else -> LazyVerticalGrid(
                    state = grid,
                    columns = coverColumns(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (load.stale) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            StaleNotice(true)
                        }
                    }
                    if (shown.isEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            if (entries.isEmpty()) Empty("Nothing on this list yet") else NothingFound()
                        }
                    }
                    items(shown, key = { it.media.id }) { entry ->
                        CoverCard(
                            title = entry.media.title.display,
                            subtitle = Formatting.listProgress(entry),
                            detail = listOfNotNull(
                                ListStatus.label(entry.status, kind).takeIf { status.value == null },
                                Formatting.score(entry.score)?.let { "★ $it" },
                            ).joinToString(" · "),
                            url = entry.media.coverImage.best,
                            onClick = { ui.navigator.open(Route.Media(entry.media.id)) },
                            repeats = entry.repeat,
                        )
                    }
                    if (shown.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { EndOfList() }
                    }
                }
            }
        }
    }
}
