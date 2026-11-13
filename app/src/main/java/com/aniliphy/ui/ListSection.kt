package com.aniliphy.ui

import com.aniliphy.anilist.Franchises
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ofKind
import com.aniliphy.anilist.asProgress

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
    val ownList: MutableState<String?> = retained.get("ownList") { mutableStateOf(null) }

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
        val context = LocalContext.current
        remember { FranchisePrefs.load(context) }
        remember { ListSortPrefs.load(context) }
        val sort = ListSortPrefs.sort
        val descending = ListSortPrefs.descending
        var sorting by retained.get("sorting") { mutableStateOf(false) }
        val grouped = FranchisePrefs.grouped
        // How the titles relate, fetched only once grouping is on.
        val links = retained.get("links") {
            ui.load(autoStart = false) { refresh -> ui.api.listLinks(userId, kind, refresh) }
        }
        LaunchedEffect(grouped) { if (grouped && links.value == null && !links.loading) links.load() }
        val grid = retained.get("grid") { LazyGridState() }
        val chips = retained.get("chips") { LazyListState() }
        val theme = LocalAppTheme.current
        BackHandler(enabled = text.value.isNotEmpty()) { text.value = "" }
        val pull = rememberPullToRefreshState()
        val refreshing = load.loading && load.value != null

        var menuFor by remember { mutableStateOf<ListEntry?>(null) }
        var editFor by remember { mutableStateOf<ListEntry?>(null) }
        menuFor?.let { entry ->
            QuickEntryMenu(
                media = entry.media,
                current = entry.asProgress(),
                detail = Formatting.listProgress(entry),
                onSaved = {
                    menuFor = null
                    ui.navigator.listsChanged()
                },
                onEdit = {
                    menuFor = null
                    editFor = entry
                },
                onRemove = { entryId ->
                    menuFor = null
                    ui.removals.remove(entryId, entry.media.id, entry.media.title.display)
                },
                onDismiss = { menuFor = null },
            )
        }
        editFor?.let { entry ->
            ListEditorDialog(
                media = entry.media,
                current = entry.asProgress(),
                onSaved = {
                    editFor = null
                    ui.removals.keep(entry.media.id)
                    ui.navigator.listsChanged()
                },
                onDismiss = { editFor = null },
                onRemove = { entryId ->
                    editFor = null
                    ui.removals.remove(entryId, entry.media.id, entry.media.title.display)
                },
            )
        }
        val hidden = ui.removals.hidden
        val entries = load.value.orEmpty().filter { it.media.id !in hidden }
        val matching = entries.filter { Formatting.matches(it.media, text.value) }
        val byStatus = status.value?.let { wanted -> matching.filter { it.status == wanted } } ?: matching
        // Your own lists, as AniList keeps them on each entry.
        val ownLists = remember(matching) {
            matching.flatMap { e -> e.customLists.filter { it.enabled }.map { it.name } }.groupingBy { it }.eachCount()
        }
        val picked = ownList.value?.let { name -> byStatus.filter { e -> e.customLists.any { it.enabled && it.name == name } } } ?: byStatus
        val shown = remember(picked, sort, descending) { ListSort.apply(picked, sort, descending) }
        // Back to the top when the order changes, but not when coming back to the list.
        val lastOrder = retained.get("lastOrder") { arrayOf<Any>(sort, descending) }
        LaunchedEffect(sort, descending) {
            if (lastOrder[0] != sort || lastOrder[1] != descending) {
                lastOrder[0] = sort
                lastOrder[1] = descending
                grid.scrollToItem(0)
            }
        }
        val linkMap = links.value
        val sideStories = FranchisePrefs.sideStories
        val items: List<ListItem> = remember(shown, grouped, linkMap, sideStories) {
            if (!grouped || linkMap == null) {
                shown.map { ListItem.Single(it) }
            } else {
                Franchises.group(shown, linkMap, FranchisePrefs.relations).map { group ->
                    if (group.size == 1) ListItem.Single(group.single()) else ListItem.Group(group)
                }
            }
        }

        // When the list comes back after a change, stay at the same place. The grid otherwise follows the first card
        // on screen by its key, and the title just changed moves to the top when sorted by last updated, taking you
        // back to the start of the list with it.
        val seenData = retained.get("seenData") { arrayOfNulls<Any>(1) }
        val data = load.value
        if (data != null && seenData[0] != null && seenData[0] !== data) {
            grid.requestScrollToItem(grid.firstVisibleItemIndex, grid.firstVisibleItemScrollOffset)
        }
        if (data != null) seenData[0] = data
        if (entries.isNotEmpty()) ListCount(shown.size, kind == MediaKind.Anime)
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp),
        ) {
            item(key = "sort") {
                val arrow = if (descending) "↓" else "↑"
                FilterChip("⇅ ${sort.label(kind)} $arrow", sorting) { sorting = !sorting }
            }
            item(key = "all") {
                FilterChip("All ${matching.size}", status.value == null) { status.value = null }
            }
            items(ListStatus.ALL, key = { it }) { option ->
                val count = matching.count { it.status == option }
                FilterChip("${ListStatus.label(option, kind)} $count", status.value == option) {
                    status.value = option
                }
            }
            if (status.value == ListStatus.PLANNING && matching.any { it.status == ListStatus.PLANNING }) item(key = "roulette") {
                FilterChip("\uD83C\uDFB2 Pick one", false) { ui.navigator.open(Route.Roulette(kind.ordinal)) }
            }
            item(key = "franchises") {
                FilterChip(if (grouped && linkMap == null && links.loading) "⧉ Franchises…" else "⧉ Franchises", grouped) {
                    FranchisePrefs.setGrouped(context, !grouped)
                }
            }
            items(ownLists.keys.sorted(), key = { "own:$it" }) { name ->
                FilterChip("☰ $name ${ownLists[name]}", ownList.value == name) {
                    ownList.value = if (ownList.value == name) null else name
                }
            }
        }

        if (sorting) {
            val sortRow = retained.get("sortRow") { LazyListState() }
            LazyRow(
                state = sortRow,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                items(ListSort.entries, key = { "sort:${it.name}" }) { option ->
                    val label = if (option == sort) "${option.label(kind)} ${if (descending) "↓" else "↑"}" else option.label(kind)
                    FilterChip(label, option == sort) { ListSortPrefs.pick(context, option) }
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
                    items(items, key = { it.key }) { item ->
                        if (item is ListItem.Group) {
                            FranchiseCard(item, kind, showStatus = status.value == null)
                        } else {
                        val entry = (item as ListItem.Single).entry
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
                            onLongClick = { menuFor = entry },
                            holdOpensPage = false,
                            favouriteOf = entry.media.id,
                        )
                        }
                    }
                    if (shown.isNotEmpty()) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { EndOfList() }
                    }
                }
            }
        }
    }
}
