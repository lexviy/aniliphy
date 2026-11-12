package com.aniliphy.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.FavouriteTarget
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Paged
import com.aniliphy.anilist.PersonName

private class PersonView(
    val name: PersonName,
    val image: String?,
    val description: String?,
    val staff: Boolean,
    val facts: List<Pair<String, String>>,
    val isFavourite: Boolean,
    val target: FavouriteTarget,
    val media: List<Pair<Media, String?>>,
)

@Composable
internal fun CharacterPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val load = retained.get("person") {
        ui.load<PersonView> {
            ui.api.character(id).map { c ->
                PersonView(
                    name = c.name,
                    image = c.image?.best,
                    description = c.description,
                    staff = false,
                    facts = listOfNotNull(
                        Formatting.birthday(c.dateOfBirth)?.let { "Birthday" to it },
                        c.age?.let { "Age" to it },
                        c.gender?.let { "Gender" to it },
                        c.bloodType?.let { "Blood type" to it },
                    ),
                    isFavourite = c.isFavourite,
                    target = FavouriteTarget.Character(c.id),
                    media = c.media.mapNotNull { edge -> edge.node?.let { it to edge.characterRole } },
                )
            }
        }
    }
    PersonPage("Character", load, retained)
}

@Composable
internal fun StaffPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val load = retained.get("person") {
        ui.load<PersonView> {
            ui.api.staff(id).map { s ->
                PersonView(
                    name = s.name,
                    image = s.image?.best,
                    description = s.description,
                    staff = true,
                    facts = listOfNotNull(
                        Formatting.birthday(s.dateOfBirth)?.let { "Birthday" to it },
                        s.age?.let { "Age" to it.toString() },
                        s.gender?.let { "Gender" to it },
                        s.bloodType?.let { "Blood type" to it },
                        s.primaryOccupations.takeIf { it.isNotEmpty() }?.let { "Occupation" to it.joinToString(", ") },
                        s.homeTown?.let { "Hometown" to it },
                        Formatting.yearsActive(s.yearsActive)?.let { "Years active" to it },
                    ),
                    isFavourite = s.isFavourite,
                    target = FavouriteTarget.Staff(s.id),
                    media = s.media.mapNotNull { edge -> edge.node?.let { it to edge.staffRole } },
                )
            }
        }
    }
    PersonPage("Staff", load, retained)
}

@Composable
private fun PersonPage(header: String, load: LoadState<PersonView>, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val scroll = retained.get("scroll") { ScrollState(0) }
    val spoilers: MutableState<Boolean> = retained.get("spoilers") { mutableStateOf(false) }
    val person = load.value

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(header) { ui.navigator.back() }
        when {
            person != null -> Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
                StaleNotice(load.stale)
                val text = Formatting.personText(person.description, person.staff, spoilers.value)
                val facts = person.facts + listOfNotNull(text.height?.let { "Height" to it })
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Cover(person.image, Modifier.width(128.dp).height(184.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        BasicText(
                            person.name.full ?: "?",
                            style = TextStyle(color = theme.foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold),
                        )
                        person.name.native?.let {
                            BasicText(it, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp))
                        }
                        Spacer(Modifier.height(4.dp))
                        facts.forEach { (label, value) ->
                            BasicText(
                                "$label: $value",
                                style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.sp),
                            )
                        }
                    }
                    if (ui.session.userId != null) Star(retained, person.isFavourite, person.target)
                }

                SectionTitle("About")
                BasicText(
                    text.description,
                    style = TextStyle(color = theme.foreground, fontSize = 14.sp, lineHeight = 20.sp),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                val hasSpoilerNames = person.name.alternativeSpoiler.any { it.isNotBlank() }
                if (text.hasSpoilers || hasSpoilerNames) {
                    ActionRow(
                        if (spoilers.value) "Hide spoilers" else "Show spoilers",
                        onClick = { spoilers.value = !spoilers.value },
                        color = theme.primary,
                    )
                }

                val alternatives = person.name.alternative.filter { it.isNotBlank() } +
                    if (spoilers.value) person.name.alternativeSpoiler.filter { it.isNotBlank() } else emptyList()
                if (alternatives.isNotEmpty()) {
                    SectionTitle("Also known as")
                    BasicText(
                        alternatives.joinToString(", "),
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.sp),
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }

                if (person.media.isNotEmpty()) {
                    CoverRow(
                        "Media · ${person.media.size}",
                        person.media,
                        { (media, role) -> "${media.id}:$role" },
                    ) { (media, role) ->
                        CoverCard(
                            title = media.title.display,
                            subtitle = kindText(media, role),
                            url = media.coverImage.best,
                            onClick = { ui.navigator.open(Route.Media(media.id)) },
                            wide = false,
                        )
                    }
                }
            }
            load.error != null -> ErrorRow(load.error!!) { load.load() }
            else -> Hint("Loading...")
        }
    }
}

private fun kindText(media: Media, role: String?): String {
    val kind = when (media.kind) {
        MediaKind.Anime -> "Anime"
        MediaKind.LightNovel -> "Light novel"
        MediaKind.Manga -> "Manga"
    }
    return listOfNotNull(kind, role?.let(Formatting::nice)).joinToString("\n")
}

@Composable
internal fun StudioPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val name: MutableState<String?> = retained.get("name") { mutableStateOf(null) }
    val favourite: MutableState<Boolean?> = retained.get("isFavourite") { mutableStateOf(null) }
    val paged = retained.get("paged") {
        ui.paged<Media>(Media::id) { page ->
            val fetched = ui.api.studio(id, page)
            name.value = fetched.value.name
            favourite.value = fetched.value.isFavourite
            Fetched(Paged(fetched.value.animes, fetched.value.hasNext), fetched.stale)
        }
    }
    val grid = retained.get("grid") { LazyGridState() }
    LoadMoreEffect(grid, paged)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Studio") { ui.navigator.back() }
        LazyVerticalGrid(
            state = grid,
            columns = coverColumns(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row {
                    BasicText(
                        name.value ?: "",
                        style = TextStyle(color = theme.foreground, fontSize = 21.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f).padding(start = 4.dp, top = 6.dp),
                    )
                    val isFavourite = favourite.value
                    if (ui.session.userId != null && isFavourite != null) {
                        Star(retained, isFavourite, FavouriteTarget.Studio(id))
                    }
                }
            }
            if (paged.stale) item(span = { GridItemSpan(maxLineSpan) }) { StaleNotice(true) }
            Formatting.byYear(paged.items).forEach { (year, animes) ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "year$year${animes.first().id}") {
                    BasicText(
                        year?.toString() ?: "Unknown year",
                        style = TextStyle(color = theme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                items(animes, key = { it.id }) { anime ->
                    CoverCard(
                        title = anime.title.display,
                        subtitle = Formatting.animeDetails(anime),
                        url = anime.coverImage.best,
                        onClick = { ui.navigator.open(Route.Media(anime.id)) },
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    paged.error != null -> ErrorRow(paged.error!!) { paged.retry() }
                    paged.loading -> Loading()
                    paged.items.isEmpty() -> Hint("Nothing found")
                }
            }
        }
    }
}
