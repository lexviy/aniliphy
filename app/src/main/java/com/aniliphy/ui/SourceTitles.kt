package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.aniliphy.WidgetOpen
import com.aniliphy.WidgetOpenRequest
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.core.BrowseItem
import com.aniliphy.core.Sources
import com.aniliphy.core.TachiSite
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.source.AnimeSite
import com.aniliphy.source.MangaSite
import com.aniliphy.source.Site
import com.aniliphy.source.SourceChapter
import com.aniliphy.source.SourceEpisode
import com.aniliphy.widget.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** The AniList entries that already open with some source: place → AniList id, and a way to read it again. */
internal class Linked(val places: Map<String, Int>, val refresh: () -> Unit) {
    fun of(site: Site, item: BrowseItem): Int? = places[Sources.place(site.id, item.url)]
}

@Composable
internal fun rememberLinked(isAnime: Boolean): Linked {
    val anime = LocalAnime.current
    val manga = LocalManga.current
    var version by remember { mutableIntStateOf(0) }
    val places by produceState(emptyMap<String, Int>(), isAnime, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                Sources.linked(if (isAnime) anime.history.all().mapValues { it.value.source } else manga.history.all().mapValues { it.value.source })
            }.getOrDefault(emptyMap())
        }
    }
    return Linked(places) { version++ }
}

/** A title in a source's list. [linked] marks one that already opens an AniList entry. */
@Composable
internal fun BrowseCard(site: Site, item: BrowseItem, linked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    Column(modifier.pressable(onClick = onClick)) {
        val cover = item.cover?.takeIf { it.isNotBlank() }
        Box {
            AsyncImage(
                model = cover?.let {
                    if (site is TachiSite) {
                        ImageRequest.Builder(context).data(SiteCover(site, it)).fetcherFactory(SiteCoverFetcher.Factory()).memoryCacheKey(it).build()
                    } else {
                        it
                    }
                },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(coverShape()).background(theme.panel),
            )
            if (linked) NewBadge("On your list", Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        Spacer(Modifier.height(4.dp))
        BasicText(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
    }
}

/**
 * What a tap on a title does: one not on the list yet asks which AniList entry it is; one already linked offers its
 * page, downloads, or linking it to another entry.
 */
@Composable
internal fun TitleActions(site: Site, isAnime: Boolean, item: BrowseItem, linked: Linked, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val mediaId = linked.of(site, item)
    var relinking by remember { mutableStateOf(mediaId == null) }
    var downloading by remember { mutableStateOf(false) }
    when {
        relinking -> LinkToAniList(site, isAnime, item, onLinked = linked.refresh, onDismiss = onDismiss)
        downloading && mediaId != null -> DownloadFromSource(site, isAnime, item, mediaId, onDismiss)
        mediaId != null -> DialogFrame(onDismiss) {
            BasicText(item.title, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
            BasicText(
                "On your list, opening with ${site.name}.",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Open its AniList page", onClick = {
                    onDismiss()
                    WidgetOpenRequest.open = WidgetOpen(WidgetKind.Recommendation, mediaId.toString())
                })
                PillButton(if (isAnime) "Download episodes" else "Download chapters", onClick = { downloading = true }, filled = false)
                PillButton("Link it to another entry", onClick = { relinking = true }, filled = false)
                PillButton("Cancel", onClick = onDismiss, filled = false)
            }
        }
    }
}

/** The episodes or chapters of a linked title on [site], to pick several and download them at once. */
@Composable
private fun DownloadFromSource(site: Site, isAnime: Boolean, item: BrowseItem, mediaId: Int, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val anime = LocalAnime.current
    val manga = LocalManga.current
    val place = Sources.place(site.id, item.url)
    var version by remember { mutableIntStateOf(0) }
    val loaded by produceState<Result<DownloadChoice>?>(null, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (isAnime) {
                    val record = anime.history.get(mediaId)
                    val episodes = anime.episodes(place)
                    DownloadChoice.Episodes(
                        AnimePlayerRequest(mediaId, record?.title ?: item.title, place, cover = record?.cover ?: item.cover),
                        episodes,
                        episodes.indexOfFirst { it.id == record?.episodeId }.coerceAtLeast(0),
                        anime.offline.downloadedIds(mediaId),
                    )
                } else {
                    val record = manga.history.get(mediaId)
                    val chapters = (site as MangaSite).chapters(item.url)
                    DownloadChoice.Chapters(
                        MangaReaderRequest(mediaId, record?.title ?: item.title, place, cover = record?.cover ?: item.cover),
                        chapters,
                        chapters.indexOfFirst { it.id == record?.chapterId }.coerceAtLeast(0),
                        manga.offline.downloadedIds(mediaId),
                    )
                }
            }
        }
    }
    DialogFrame(onDismiss) {
        BasicText(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        val result = loaded
        when {
            result == null -> LookLoading("Loading ${site.name}…", Modifier.fillMaxWidth(), theme.foreground.copy(alpha = 0.7f))
            result.isFailure -> {
                Hint((result.exceptionOrNull() as? IOException)?.message ?: "Could not reach ${site.name}", color = theme.error)
                PillButton("Try again", onClick = { version++ }, filled = false)
            }
            else -> when (val choice = result.getOrThrow()) {
                is DownloadChoice.Episodes -> EpisodeDownloads(choice.episodes, choice.current, choice.downloaded, mediaId, onDownload = { chosen ->
                    onDismiss()
                    anime.download(choice.request, chosen, anime.settings.audio)
                }, onDelete = {
                    AnimeDownloads.delete(anime.offline, mediaId, it.id)
                    version++
                })
                is DownloadChoice.Chapters -> DownloadList(choice.chapters, choice.current, choice.downloaded, mediaId, onDownload = { chosen ->
                    onDismiss()
                    manga.download(choice.request, chosen)
                }, onDelete = {
                    MangaDownloads.delete(manga.offline, mediaId, it.id)
                    version++
                })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton("Close", onClick = onDismiss, filled = false)
        }
    }
}

private sealed class DownloadChoice {
    class Episodes(val request: AnimePlayerRequest, val episodes: List<SourceEpisode>, val current: Int, val downloaded: Set<String>) : DownloadChoice()

    class Chapters(val request: MangaReaderRequest, val chapters: List<SourceChapter>, val current: Int, val downloaded: Set<String>) : DownloadChoice()
}

// ——— Searching every source ———

private sealed class SourceFind {
    data object Searching : SourceFind()

    class Found(val items: List<BrowseItem>) : SourceFind()

    class Failed(val message: String) : SourceFind()
}

/** One search on every installed anime and manga source, each in its own row, so one that fails doesn't stop the rest. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
internal fun SearchAllSources(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val theme = LocalAppTheme.current
    var query by remember { mutableStateOf("") }
    val sites = rememberSources { (AnimeSites.all() + MangaSites.all()) }
    val found = remember { mutableStateMapOf<String, SourceFind>() }
    val animeLinked = rememberLinked(isAnime = true)
    val mangaLinked = rememberLinked(isAnime = false)
    var tapped by remember { mutableStateOf<Pair<Site, BrowseItem>?>(null) }

    // A new search cancels the one before; each source answers on its own.
    LaunchedEffect(query, sites) {
        found.clear()
        val words = query.trim()
        if (words.length < 2) return@LaunchedEffect
        delay(700)
        sites.forEach { found[it.id] = SourceFind.Searching }
        val io = Dispatchers.IO.limitedParallelism(4)
        coroutineScope {
            sites.forEach { site ->
                launch {
                    val result = withContext(io) {
                        runCatching {
                            when (site) {
                                is AnimeSite -> site.search(words).map { BrowseItem(it.title, it.url, it.cover) }
                                is MangaSite -> site.search(words).map { BrowseItem(it.title, it.url, it.cover) }
                                else -> emptyList()
                            }
                        }
                    }
                    found[site.id] = result.fold(
                        { SourceFind.Found(it) },
                        { SourceFind.Failed((it as? IOException)?.message ?: "Could not reach ${site.name}") },
                    )
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(title = "Search all sources", onBack = onBack)
        InputBox(
            query,
            { query = it },
            "Search every source",
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            leading = { SearchLead(query.isNotEmpty()) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (sites.isEmpty()) item { Empty("No source is installed. Add one in Settings → Extensions.", Modifier.padding(20.dp)) }
            items(sites, key = { it.id }) { site ->
                val isAnime = site is AnimeSite
                val state = found[site.id] ?: return@items
                Column(Modifier.padding(vertical = 6.dp)) {
                    BasicText(
                        "${site.name}  ·  ${if (isAnime) "Anime" else "Manga"}",
                        style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    when (state) {
                        SourceFind.Searching -> LookLoading("Searching…", Modifier.padding(horizontal = 20.dp), theme.foreground.copy(alpha = 0.6f))
                        is SourceFind.Failed -> Hint(state.message, Modifier.padding(horizontal = 20.dp), color = theme.error)
                        is SourceFind.Found -> if (state.items.isEmpty()) {
                            Hint(LookWords.nothingFound(momentStyle), Modifier.padding(horizontal = 20.dp))
                        } else {
                            val linked = if (isAnime) animeLinked else mangaLinked
                            LazyRow(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(state.items, key = { it.url }) { item ->
                                    BrowseCard(site, item, linked.of(site, item) != null, Modifier.width(108.dp)) { tapped = site to item }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    tapped?.let { (site, item) ->
        val isAnime = site is AnimeSite
        TitleActions(site, isAnime, item, if (isAnime) animeLinked else mangaLinked, onDismiss = { tapped = null })
    }
}

@Composable
internal fun LinkToAniList(site: Site, isAnime: Boolean, item: BrowseItem, onLinked: () -> Unit = {}, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val aniList = LocalAniList.current
    val anime = LocalAnime.current
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(item.title) }
    var results by remember { mutableStateOf<List<Media>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<Media?>(null) }
    val place = Sources.place(site.id, item.url)

    fun link(media: Media) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (isAnime) {
                    anime.history.setSource(media.id, place, media.title.display, media.coverImage.large)
                } else {
                    manga.history.setSource(media.id, place, media.title.display, media.coverImage.large)
                }
            }
            onLinked()
            onDismiss()
            WidgetOpenRequest.open = WidgetOpen(WidgetKind.Recommendation, media.id.toString())
        }
    }

    fun pick(media: Media) {
        scope.launch {
            val current = withContext(Dispatchers.IO) {
                if (isAnime) anime.history.get(media.id)?.source else manga.history.get(media.id)?.source
            }
            if (current != null && current != place) replacing = media else link(media)
        }
    }

    LaunchedEffect(text) {
        searched = false
        failed = false
        delay(350)
        if (text.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        try {
            results = aniList.api.search(text, if (isAnime) MediaKind.Anime else MediaKind.Manga).value.items
            searched = true
        } catch (_: AniListException) {
            failed = true
        }
    }
    DialogFrame(onDismiss) {
        BasicText("Which one on AniList?", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        BasicText(
            "${item.title} will open with ${site.name} from its AniList page.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp),
        )
        InputBox(text, { text = it }, "Search AniList", Modifier.fillMaxWidth(), background = theme.panel)
        when {
            failed -> Hint("Could not reach AniList")
            searched && results.isEmpty() -> Empty(LookWords.nothingFound(momentStyle) + ". Try fewer words.")
            !searched && results.isEmpty() && text.isNotBlank() -> Hint("Searching...")
        }
        LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
            items(results, key = { it.id }) { media ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { pick(media) }.padding(vertical = 6.dp),
                ) {
                    Cover(media.coverImage.large, Modifier.width(36.dp).height(52.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicText(
                        media.title.display,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton("Cancel", onClick = onDismiss, filled = false)
        }
    }
    replacing?.let { media ->
        ConfirmDialog(
            title = "Use ${site.name} for ${media.title.display}?",
            message = "It opens with another source now. Switching forgets where you were on that one, " +
                "and it opens with ${site.name} from now on.",
            confirmLabel = "Use ${site.name}",
            onConfirm = {
                replacing = null
                link(media)
            },
            onDismiss = { replacing = null },
        )
    }
}

