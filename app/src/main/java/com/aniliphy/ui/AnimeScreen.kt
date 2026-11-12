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
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ofKind
import com.aniliphy.anime.AnimeRecord
import com.aniliphy.anime.AnimeRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class WatchedAnime(
    val mediaId: Int,
    val title: String,
    val cover: String?,
    val updated: Long,
    val record: AnimeRecord?,
    val entry: ListEntry?,
    val newOut: Int?,
) {
    private val aniListAhead: Boolean
        get() = entry != null && (record == null || entry.progress >= (record.episodeNumber ?: 0.0) && entry.progress > 0)

    val place: String
        get() = when {
            aniListAhead -> if (entry!!.progress > 0) "Episode ${entry.progress} watched" else "Not started"
            record?.episodeNumber != null -> listOfNotNull(
                "Episode ${record.episodeNumber.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}",
                if (record.durationMs > 0) "${clock(record.positionMs)} / ${clock(record.durationMs)}" else null,
            ).joinToString(" · ")
            entry != null -> "Not started"
            else -> ""
        }

    val percent: Int?
        get() = if (aniListAhead || record?.episodeNumber == null) {
            val total = entry?.media?.episodes?.takeIf { it > 0 }?.toDouble()
                ?: entry?.media?.nextAiringEpisode?.episode?.let { it - 1 }?.takeIf { it > 0 }?.toDouble()
            total?.let { (entry!!.progress / it * 100).toInt().coerceIn(0, 100) }
        } else {
            AnimeRules.percent(record, entry?.media?.nextAiringEpisode?.episode?.let { it - 1.0 })
        }

    val isNew: Boolean get() = newOut != null
}

private fun merge(records: Map<Int, AnimeRecord>, entries: List<ListEntry>, newOut: Map<Int, Int>): List<WatchedAnime> {
    val listed = entries.associateBy { it.media.id }
    val ids = records.filterValues { it.hasPlace && it.title != null }.keys + listed.keys
    return ids.map { id ->
        val record = records[id]?.takeIf { it.hasPlace && it.title != null }
        val entry = listed[id]
        WatchedAnime(
            mediaId = id,
            title = record?.title ?: entry!!.media.title.display,
            cover = record?.cover ?: entry?.media?.coverImage?.best,
            updated = maxOf(record?.updated ?: 0L, (entry?.updatedAt ?: 0L) * 1000),
            record = record,
            entry = entry,
            newOut = newOut[id],
        )
    }.sortedByDescending { it.updated }
}

@Composable
internal fun AnimeScreen(appearance: AppearanceState, anime: AnimeUi) {
    val theme = LocalAppTheme.current
    var text by remember { mutableStateOf("") }
    val userId = anime.aniList.session.userId
    val watching by produceState<List<ListEntry>>(emptyList(), userId, anime.aniList.navigator.listsVersion) {
        value = if (userId == null) {
            emptyList()
        } else {
            try {
                anime.aniList.api.watching(userId).value.ofKind(MediaKind.Anime)
            } catch (_: AniListException) {
                value
            }
        }
    }
    val local by produceState<Map<Int, AnimeRecord>?>(null, anime.historyVersion) {
        value = withContext(Dispatchers.IO) { anime.history.all() }
    }
    val newOut = anime.newOut
    val known = remember(local, watching, newOut) { local?.let { merge(it, watching, newOut) } }
    LaunchedEffect(watching) {
        if (watching.isNotEmpty()) anime.checkNewEpisodes(watching)
    }

    ProvideCoverStyle(appearance) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                SearchField(text, { text = it }, "Search your anime", Modifier.weight(1f))
            }
            BackHandler(enabled = text.isNotEmpty()) { text = "" }
            val wanted = text.trim().lowercase()
            val shown = known?.filter { wanted in it.title.lowercase() }
            when {
                shown == null -> Unit
                known.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Hint("Anime you're watching shows here, by day. Open an anime from the AniList tab and tap Watch.", color = theme.foreground)
                }
                shown.isEmpty() -> Hint("Nothing found", Modifier.padding(20.dp), color = theme.foreground)
                else -> {
                    val days = remember(shown) { shown.groupBy { dayOf(it.updated) }.toList() }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
                        days.forEach { (day, items) ->
                            item(key = "day$day") { SectionTitle(dayLabel(day)) }
                            item(key = "row$day") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(items, key = { it.mediaId }) { item ->
                                        Box(Modifier.width(RowCardWidth)) {
                                            AnimeCard(item, onLongClick = { anime.showOnAniList(item.mediaId) }, onClick = {
                                                val entry = item.entry
                                                if (entry != null) anime.watchMedia(entry.media) else anime.continueWatching(item.mediaId, item.record!!)
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeCard(item: WatchedAnime, onLongClick: () -> Unit, onClick: () -> Unit) {
    ProgressCard(
        title = item.title,
        cover = item.cover,
        place = item.place,
        percent = item.percent,
        newLabel = if (item.isNew) "New episode" else null,
        repeats = item.entry?.repeat ?: 0,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}
