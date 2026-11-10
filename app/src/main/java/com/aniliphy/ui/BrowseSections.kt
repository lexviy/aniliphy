package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Paged
import com.aniliphy.anilist.PersonRef
import com.aniliphy.anilist.StudioRef
import kotlinx.coroutines.delay

@Composable
private fun <T> BrowseSection(
    section: Section,
    retained: Retained,
    onMenu: () -> Unit,
    columns: GridCells,
    fetch: suspend (text: String?, page: Int) -> Fetched<Paged<T>>,
    key: (T) -> Any,
    card: @Composable (T) -> Unit,
) {
    val text: MutableState<String> = retained.get("text") { mutableStateOf("") }
    val shown: MutableState<String> = retained.get("shownText") { mutableStateOf("") }
    val ui = LocalAniList.current
    val paged = retained.get("paged") { ui.paged<T>(key) { page -> fetch(shown.value.ifEmpty { null }, page) } }
    val grid = retained.get("grid") { LazyGridState() }

    LaunchedEffect(text.value) {
        val typed = text.value.trim()
        if (typed == shown.value) return@LaunchedEffect
        delay(SEARCH_DELAY)
        shown.value = typed
        grid.scrollToItem(0)
        paged.restart { page -> fetch(typed.ifEmpty { null }, page) }
    }
    LoadMoreEffect(grid, paged)
    BackHandler(enabled = text.value.isNotEmpty()) { text.value = "" }

    Column(Modifier.fillMaxSize()) {
        SectionBar(section, onMenu, text.value, { text.value = it })
        LazyVerticalGrid(
            state = grid,
            columns = columns,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (paged.stale) item(span = { GridItemSpan(maxLineSpan) }) { StaleNotice(true) }
            items(paged.items, key = key) { card(it) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    paged.error != null -> ErrorRow(paged.error!!) { paged.retry() }
                    paged.loading -> Hint("Loading...")
                    paged.items.isEmpty() -> Hint("Nothing found")
                }
            }
        }
    }
}

@Composable
internal fun CharactersSection(retained: Retained, onMenu: () -> Unit) {
    val ui = LocalAniList.current
    PeopleSection(Section.Characters, retained, onMenu, { text, page -> ui.api.characters(text, page) }) {
        ui.navigator.open(Route.Character(it))
    }
}

@Composable
internal fun StaffSection(retained: Retained, onMenu: () -> Unit) {
    val ui = LocalAniList.current
    PeopleSection(Section.Staff, retained, onMenu, { text, page -> ui.api.staffMembers(text, page) }) {
        ui.navigator.open(Route.Staff(it))
    }
}

@Composable
private fun PeopleSection(
    section: Section,
    retained: Retained,
    onMenu: () -> Unit,
    fetch: suspend (String?, Int) -> Fetched<Paged<PersonRef>>,
    open: (Int) -> Unit,
) {
    BrowseSection(
        section = section,
        retained = retained,
        onMenu = onMenu,
        columns = coverColumns(),
        fetch = fetch,
        key = PersonRef::id,
    ) { person ->
        CoverCard(
            title = person.name.full ?: "?",
            subtitle = person.name.native,
            url = person.image?.best,
            onClick = { open(person.id) },
        )
    }
}

@Composable
internal fun StudiosSection(retained: Retained, onMenu: () -> Unit) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    BrowseSection(
        section = Section.Studios,
        retained = retained,
        onMenu = onMenu,
        columns = GridCells.Fixed(1),
        fetch = { text, page -> ui.api.studios(text, page) },
        key = StudioRef::id,
    ) { studio ->
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(theme.surface.copy(alpha = 0.72f))
                .clickable { ui.navigator.open(Route.Studio(studio.id)) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            BasicText(studio.name ?: "?", style = TextStyle(color = theme.foreground, fontSize = 15.sp))
            studio.favourites?.takeIf { it > 0 }?.let {
                BasicText("♥ $it", style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
            }
        }
    }
}
