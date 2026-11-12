package com.aniliphy.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SavedItem(val id: String, val label: String, val bytes: Long)

private data class SavedTitle(val anime: Boolean, val mediaId: Int, val title: String, val cover: String?, val items: List<SavedItem>) {
    val key get() = (if (anime) "a" else "m") + mediaId
    val bytes get() = items.sumOf { it.bytes }
}

private data class Deletion(val title: SavedTitle, val item: SavedItem?)

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val anime = LocalAnime.current
    val manga = LocalManga.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by remember { mutableStateOf<Deletion?>(null) }

    val titles by produceState<List<SavedTitle>?>(null, AnimeDownloads.version, MangaDownloads.version) {
        value = withContext(Dispatchers.IO) {
            val shows = anime.offline.titles().map { (id, show) ->
                SavedTitle(
                    anime = true,
                    mediaId = id,
                    title = show.title ?: "Anime $id",
                    cover = show.cover,
                    items = show.episodes.sortedBy { it.episode.number }.map {
                        SavedItem(it.episode.id, "Episode ${episodeLabel(it.episode)} · ${it.audio.name}", it.bytes)
                    },
                )
            }
            val series = manga.offline.titles().map { (id, meta) ->
                SavedTitle(
                    anime = false,
                    mediaId = id,
                    title = meta.title ?: "Manga $id",
                    cover = null,
                    items = meta.downloaded.entries.sortedBy { it.value.number ?: Double.MAX_VALUE }.map { (chapterId, chapter) ->
                        SavedItem(chapterId, chapter.name, manga.offline.chapterBytes(id, chapterId))
                    },
                )
            }
            (shows + series).filter { it.items.isNotEmpty() }.sortedBy { it.title.lowercase() }
        }
    }

    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    fun delete(deletion: Deletion) {
        val title = deletion.title
        val ids = deletion.item?.let { listOf(it.id) } ?: title.items.map { it.id }
        ids.forEach { id ->
            if (title.anime) AnimeDownloads.delete(anime.offline, title.mediaId, id) else MangaDownloads.delete(manga.offline, title.mediaId, id)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(title = "Downloads", onBack = onBack)
        val list = titles
        when {
            list == null -> Hint("Loading…", Modifier.padding(20.dp))
            list.isEmpty() -> Empty("Nothing saved. Download in the player or the reader to watch and read without a connection.", Modifier.padding(20.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.key }) { title ->
                    val expanded = open == title.key
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { open = if (expanded) null else title.key }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            BasicText(
                                title.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            )
                            val what = if (title.anime) "episode" else "chapter"
                            val count = title.items.size
                            BasicText(
                                "${if (title.anime) "Anime" else "Manga"} · $count $what${if (count == 1) "" else "s"} · ${size(title.bytes)}",
                                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        PillButton("Delete", onClick = { asking = Deletion(title, null) }, filled = false)
                    }
                    if (expanded) {
                        title.items.forEach { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(start = 36.dp, end = 20.dp, top = 2.dp, bottom = 2.dp),
                            ) {
                                BasicText(
                                    item.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 14.sp),
                                    modifier = Modifier.weight(1f),
                                )
                                BasicText(
                                    size(item.bytes),
                                    style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 13.sp),
                                )
                                Spacer(Modifier.width(8.dp))
                                BasicText(
                                    "✕",
                                    style = TextStyle(color = theme.error, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable(role = Role.Button) { asking = Deletion(title, item) }
                                        .padding(10.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    asking?.let { deletion ->
        val what = deletion.item?.label ?: "everything saved of ${deletion.title.title}"
        ConfirmDialog(
            title = "Delete download?",
            message = "This removes $what from the phone.",
            confirmLabel = "Delete",
            onConfirm = {
                asking = null
                delete(deletion)
            },
            onDismiss = { asking = null },
        )
    }
}
