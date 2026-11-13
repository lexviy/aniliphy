package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ofKind
import com.aniliphy.manga.LatestChapter
import com.aniliphy.manga.MangaRecord
import com.aniliphy.manga.MangaRules
import com.aniliphy.manga.NewChapters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

private data class ReadManga(
    val mediaId: Int,
    val title: String,
    val cover: String?,
    val updated: Long,
    val record: MangaRecord?,
    val entry: ListEntry?,
    val latest: LatestChapter?,
) {
    private val aniListAhead: Boolean
        get() = entry != null && (record == null || entry.progress > floor(record.chapterNumber ?: 0.0))

    val place: String
        get() = when {
            aniListAhead -> if (entry!!.progress > 0) "Chapter ${entry.progress}" else "Not started"
            record != null -> listOfNotNull(record.chapterName, if (record.pages > 0) "p. ${record.page + 1}/${record.pages}" else null).joinToString(" · ")
            else -> ""
        }

    val percent: Int?
        get() = if (aniListAhead) {
            val total = entry!!.media.chapters?.takeIf { it > 0 }?.toDouble() ?: latest?.latest?.takeIf { it > 0 }
            total?.let { (entry.progress / it * 100).toInt().coerceIn(0, 100) }
        } else {
            record?.let { MangaRules.percent(it, latest?.latest) }
        }

    val isNew: Boolean get() = record != null && NewChapters.isNew(latest, record)
}

private fun merge(records: Map<Int, MangaRecord>, entries: List<ListEntry>, latest: Map<Int, LatestChapter>): List<ReadManga> {
    val listed = entries.associateBy { it.media.id }
    val ids = records.filterValues { it.hasPlace && it.title != null }.keys + listed.keys
    return ids.map { id ->
        val record = records[id]?.takeIf { it.hasPlace && it.title != null }
        val entry = listed[id]
        ReadManga(
            mediaId = id,
            title = record?.title ?: entry!!.media.title.display,
            cover = record?.cover ?: entry?.media?.coverImage?.best,
            updated = maxOf(record?.updated ?: 0L, (entry?.updatedAt ?: 0L) * 1000),
            record = record,
            entry = entry,
            latest = latest[id],
        )
    }.sortedByDescending { it.updated }
}

@Composable
internal fun MangaScreen(appearance: AppearanceState, manga: MangaUi) {
    val theme = LocalAppTheme.current
    var text by remember { mutableStateOf("") }
    val userId = manga.aniList.session.userId
    val reading by produceState<List<ListEntry>>(emptyList(), userId, manga.aniList.navigator.listsVersion) {
        value = if (userId == null) {
            emptyList()
        } else {
            try {
                manga.aniList.api.reading(userId).value.ofKind(MediaKind.Manga)
            } catch (_: AniListException) {
                value
            }
        }
    }
    // What's on Planning, below the days. From the whole list the AniList tab also uses, so it's usually cached.
    val planning by produceState<List<ListEntry>>(emptyList(), userId, manga.aniList.navigator.listsVersion) {
        value = if (userId == null) {
            emptyList()
        } else {
            try {
                manga.aniList.api.mangaList(userId).value.ofKind(MediaKind.Manga)
                    .filter { it.status == ListStatus.PLANNING }
                    .sortedByDescending { it.updatedAt ?: 0 }
            } catch (_: AniListException) {
                value
            }
        }
    }
    val local by produceState<Pair<Map<Int, MangaRecord>, Map<Int, LatestChapter>>?>(null, manga.historyVersion) {
        value = withContext(Dispatchers.IO) { manga.history.all() to manga.newChapters.all() }
    }
    val known = remember(local, reading) { local?.let { (records, latest) -> merge(records, reading, latest) } }
    LaunchedEffect(local != null) {
        local?.let { (records, _) -> manga.checkNewChapters(records.filterValues { it.hasPlace }) }
    }

    ProvideCoverStyle(appearance) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                SearchField(text, { text = it }, "Search your manga", Modifier.weight(1f))
            }
            BackHandler(enabled = text.isNotEmpty()) { text = "" }
            val wanted = text.trim().lowercase()
            val shown = known?.filter { wanted in it.title.lowercase() }
            val going = known.orEmpty().map { it.mediaId }.toSet()
            val planned = planning.filter { it.media.id !in going && wanted in it.media.title.display.lowercase() }
            when {
                shown == null -> Unit
                known.isEmpty() && planned.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Hint("Manga you're reading shows here, by day. Open a manga from the AniList tab and tap Read.", color = theme.foreground)
                }
                shown.isEmpty() && planned.isEmpty() -> NothingFound(Modifier.padding(20.dp), color = theme.foreground)
                else -> {
                    val days = remember(shown) { shown.groupBy { dayOf(it.updated) }.toList() }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        days.forEach { (day, items) ->
                            item(key = "day$day") { DayTitle(day) }
                            item(key = "row$day") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(items, key = { it.mediaId }) { item ->
                                        Box(Modifier.width(RowCardWidth)) {
                                            MangaCard(item, onLongClick = { manga.showOnAniList(item.mediaId) }, onClick = {
                                                val entry = item.entry
                                                if (entry != null) manga.readMedia(entry.media) else manga.continueReading(item.mediaId, item.record!!)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        if (planned.isNotEmpty()) {
                            item(key = "planningTitle") { SectionTitle(ListStatus.label(ListStatus.PLANNING, MediaKind.Manga)) }
                            item(key = "planningRow") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(planned, key = { "plan" + it.media.id }) { entry ->
                                        Box(Modifier.width(RowCardWidth)) {
                                            PlannedCard(entry, onLongClick = { manga.showOnAniList(entry.media.id) }, onClick = { manga.readMedia(entry.media) })
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "end") { EndOfList() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MangaCard(item: ReadManga, onLongClick: () -> Unit, onClick: () -> Unit) {
    ProgressCard(
        title = item.title,
        cover = item.cover,
        place = item.place,
        percent = item.percent,
        newLabel = if (item.isNew) "New chapter" else null,
        repeats = item.entry?.repeat ?: 0,
        onClick = onClick,
        onLongClick = onLongClick,
        mediaId = item.mediaId,
    )
}

/** A title on Planning in the Anime or Manga tab: not started, so no place or bar; a tap starts it. */
@Composable
internal fun PlannedCard(entry: ListEntry, onLongClick: () -> Unit, onClick: () -> Unit) {
    val length = entry.media.episodes?.let { "$it ep" } ?: entry.media.chapters?.let { "$it ch" }
    ProgressCard(
        title = entry.media.title.display,
        cover = entry.media.coverImage.best,
        place = listOfNotNull("Not started", length).joinToString(" · "),
        percent = null,
        newLabel = null,
        repeats = entry.repeat,
        onClick = onClick,
        onLongClick = onLongClick,
        mediaId = entry.media.id,
    )
}

internal fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

internal fun dayLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.year == today.year -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH))
    else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
}
