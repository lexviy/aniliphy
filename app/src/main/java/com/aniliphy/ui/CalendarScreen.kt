package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AiringSlot
import com.aniliphy.anilist.AnimeSeason
import com.aniliphy.anilist.CalendarItem
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.ReleaseDays
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.WeekCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as DayStyle
import java.util.Locale

/** The week ahead: what airs from your list each day, and the manga usually out that day. */
@Composable
internal fun CalendarSection(retained: Retained, onMenu: (() -> Unit)?) {
    val ui = LocalAniList.current
    val manga = LocalManga.current
    val loggedIn = ui.session.state is SessionState.LoggedIn
    val withPlanning: MutableState<Boolean> = retained.get("planning") { mutableStateOf(false) }
    val withManga: MutableState<Boolean> = retained.get("manga") { mutableStateOf(true) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val from = remember { today.atStartOfDay(zone).toEpochSecond() - 1 }
    val to = remember { today.plusDays(7).atStartOfDay(zone).toEpochSecond() }
    val entries = ui.onList.entries.values
    val ids = remember(entries.size, withPlanning.value, loggedIn) {
        entries.filter { e ->
            e.media.type == "ANIME" && (e.status == ListStatus.CURRENT || e.status == ListStatus.REPEATING || (withPlanning.value && e.status == ListStatus.PLANNING))
        }.map { it.media.id }.sorted()
    }
    val airing = retained.get("airing$ids") {
        ui.load<List<AiringSlot>> { refresh ->
            // A guest has no list: this season's airing shows instead.
            val wanted = if (loggedIn) ids else ui.api.season(AnimeSeason.of(today)).value.items.map { it.id }
            Fetched(ui.api.airing(wanted, from, to, refresh), false)
        }
    }
    val mangaDays by produceState<Map<Media, DayOfWeek>>(emptyMap(), manga.historyVersion, entries.size) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                manga.newChapters.all().mapNotNull { (id, latest) ->
                    val media = ui.entryFor(id)?.media ?: return@mapNotNull null
                    ReleaseDays.guess(latest.sightings, zone)?.let { media to it }
                }.toMap()
            }.getOrDefault(emptyMap())
        }
    }
    val now by produceState(Formatting.nowSeconds()) {
        while (true) {
            delay(30_000)
            value = Formatting.nowSeconds()
        }
    }
    val days = remember(airing.value, mangaDays, withManga.value) {
        WeekCalendar.build(today, airing.value.orEmpty(), if (withManga.value) mangaDays else emptyMap(), zone)
    }
    val nextId = airing.value.orEmpty().firstOrNull { it.airingAt >= now }?.id

    Column(Modifier.fillMaxSize()) {
        SectionBar(Section.Calendar, onMenu)
        val chips = retained.get("chips") { LazyListState() }
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            if (loggedIn) {
                item { FilterChip("Watching", true) {} }
                item { FilterChip("+ Planning", withPlanning.value) { withPlanning.value = !withPlanning.value } }
                item { FilterChip("Manga", withManga.value) { withManga.value = !withManga.value } }
            } else {
                item { FilterChip("This season", true) {} }
            }
        }
        val list = retained.get("list") { LazyListState() }
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            if (airing.value == null) {
                item { if (airing.error != null) ErrorRow(airing.error!!) { airing.load() } else Loading() }
            } else {
                days.forEachIndexed { index, day ->
                    item(key = day.date.toString()) { DayHeader(day.date, index == 0) }
                    if (day.items.isEmpty()) {
                        item(key = "${day.date}-none") { Hint("Nothing airing", Modifier.padding(horizontal = 20.dp)) }
                    }
                    items(day.items, key = { "${day.date}-${it.media.id}-${it.episode}-${it.guessed}" }) { item ->
                        SlotRow(item, now, next = item.airingAt != null && airing.value?.firstOrNull { s -> s.media?.id == item.media.id && s.episode == item.episode }?.id == nextId)
                    }
                }
                if (loggedIn && withManga.value && mangaDays.isEmpty()) {
                    item(key = "mangaHint") {
                        Hint(
                            "Manga days show up after a few weeks of new-chapter checks, once a pattern is clear.",
                            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, today: Boolean) {
    val theme = LocalAppTheme.current
    val name = if (today) "Today" else date.dayOfWeek.getDisplayName(DayStyle.FULL, Locale.ENGLISH)
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)) {
        BasicText(name, style = TitleStyle(color = if (today) theme.primary else theme.foreground, fontSize = 17.sp))
        Spacer(Modifier.width(8.dp))
        BasicText(
            date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)),
            style = NumberStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 11.sp),
        )
    }
}

@Composable
private fun SlotRow(item: CalendarItem, now: Long, next: Boolean) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val entry = ui.entryFor(item.media.id)
    val time = item.airingAt?.let {
        Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    } ?: "usually"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(lookShape(10.dp))
            .then(
                if (item.guessed) Modifier.border(1.dp, theme.foreground.copy(alpha = 0.35f), lookShape(10.dp))
                else Modifier.background(if (next) theme.primary.copy(alpha = 0.16f) else theme.surface.copy(alpha = 0.85f)),
            )
            .clickable { ui.navigator.open(Route.Media(item.media.id)) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        BasicText(
            time, modifier = Modifier.width(54.dp),
            style = NumberStyle(color = if (item.guessed) theme.foreground.copy(alpha = 0.55f) else theme.primary, fontSize = 12.sp, bold = true),
        )
        Cover(item.media.coverImage.best, Modifier.size(width = 26.dp, height = 36.dp))
        Spacer(Modifier.width(10.dp))
        val what = when {
            item.episode != null -> "${item.media.title.display} · ep ${item.episode}"
            item.guessed -> "${item.media.title.display} · new chapter"
            else -> item.media.title.display
        }
        BasicText(what, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), style = TextStyle(color = theme.foreground, fontSize = 13.sp))
        Spacer(Modifier.width(8.dp))
        val right = listOfNotNull(
            item.airingAt?.takeIf { it > now }?.let { "in ${Formatting.countdown(it - now)}" }
                ?: if (item.airingAt != null) "aired" else "guessed",
            when {
                entry == null -> null
                entry.status == ListStatus.PLANNING -> "planning"
                item.guessed -> "you: ch ${entry.progress}"
                else -> "you: ep ${entry.progress}"
            },
        ).joinToString("\n")
        BasicText(right, style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 10.sp).copy(textAlign = TextAlign.End))
    }
}
