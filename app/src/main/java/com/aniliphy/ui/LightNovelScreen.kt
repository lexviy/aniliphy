package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.novels.ChapterRules
import com.aniliphy.novels.NovelSeries
import com.aniliphy.novels.VolumeFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun LightNovelScreen(appearance: AppearanceState, novels: NovelsUi) {
    val settings = novels.settings
    val session = novels.aniList.session.state
    LaunchedEffect(settings.folderUri, settings.preferredFormat, session) {
        if (settings.folderUri.isNotEmpty()) novels.refresh()
    }
    ProvideCoverStyle(appearance) {
        val open = novels.openSeries?.let { key -> novels.series.firstOrNull { it.key == key } }
        if (open != null) {
            BackHandler { novels.openSeries = null }
            key(open.key) { SeriesPage(open, novels) }
        } else {
            Library(novels)
        }
    }
}

@Composable
private fun FolderInvite(novels: NovelsUi) {
    val resolver = LocalContext.current.contentResolver
    val settings = novels.settings
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            keepFolderAccess(resolver, uri, previous = settings.folderUri)
            settings.folderUri = uri.toString()
        }
    }
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxSize().padding(24.dp),
    ) {
        Hint("Choose the folder with your EPUB and PDF books")
        PillButton("Choose folder", onClick = { picker.launch(null) })
    }
}

@Composable
private fun Library(novels: NovelsUi) {
    val theme = LocalAppTheme.current
    var text by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val version = novels.progressVersion

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            SearchField(text, { text = it }, "Search your books", Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            PillButton("Add", onClick = { adding = true }, filled = false)
        }
        BackHandler(enabled = text.isNotEmpty()) { text = "" }
        if (novels.settings.folderUri.isEmpty() && novels.series.isEmpty()) {
            FolderInvite(novels)
        } else {
            val wanted = text.trim().lowercase()
            val shown = novels.series.filter { wanted in it.title.lowercase() }
            val recent = remember(novels.series, version) {
                novels.series.mapNotNull { novel -> novels.progress.lastRead(novel.key)?.let { novel to it } }
                    .sortedByDescending { it.second.updated }
                    .take(RECENT)
                    .map { it.first }
            }
            val missing = novels.missing.filter { media ->
                wanted in media.title.display.lowercase()
            }
            LazyVerticalGrid(
                columns = coverColumns(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val wide: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }
                novels.error?.let { message ->
                    item(span = wide) { ErrorRow(message) { novels.refresh() } }
                }
                if (wanted.isEmpty() && recent.isNotEmpty()) {
                    item(span = wide) { LibraryTitle("Continue reading") }
                    items(recent, key = { "recent" + it.key }) { novel ->
                        NovelCard(novel, novels, version, onClick = { novels.open(novel) })
                    }
                }
                if (shown.isNotEmpty()) {
                    item(span = wide) { LibraryTitle("Your books") }
                    items(shown, key = { it.key }) { novel ->
                        NovelCard(novel, novels, version, onClick = { novels.openSeries = novel.key })
                    }
                }
                if (shown.isEmpty() && missing.isEmpty()) {
                    item(span = wide) {
                        Hint(
                            when {
                                novels.loading -> "Looking in the folder..."
                                wanted.isNotEmpty() -> "Nothing found"
                                else -> "No EPUB or PDF books in this folder"
                            },
                            color = theme.foreground,
                        )
                    }
                }
                if (missing.isNotEmpty()) {
                    item(span = wide) { LibraryTitle("On your list, not in the folder") }
                    items(missing, key = { "missing${it.id}" }) { media ->
                        CoverCard(
                            title = media.title.display,
                            subtitle = "Find it",
                            url = media.coverImage.best,
                            onClick = {
                                novels.searchVolumes(
                                    listOfNotNull(media.title.english, media.title.romaji).filter { it.isNotBlank() },
                                    media.title.display,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
    if (adding) {
        CustomNovelDialog(novels, existing = null, onDismiss = { adding = false })
    }
}

@Composable
private fun LibraryTitle(text: String) {
    val theme = LocalAppTheme.current
    if (Look.modern) {
        BasicText(
            text,
            maxLines = 1,
            style = TitleStyle(color = theme.ink, fontSize = 21.sp),
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        )
        return
    }
    if (!Look.tankobon) {
        StyledHeading(text, null, Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp))
        return
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    ) {
        BasicText(text, maxLines = 1, style = TextStyle(color = theme.ink, fontSize = 20.sp))
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(2.dp).background(theme.rule))
    }
}

private const val RECENT = 6

@Composable
private fun rememberCover(novels: NovelsUi, novel: NovelSeries): String? {
    val online = novel.media?.coverImage?.best
    // The volume you're reading (the one Continue opens), so the cover follows you from book to book.
    val version = novels.progressVersion
    val reading = remember(novel.key, novel.volumes, version) { novels.readingVolume(novel) }
    val local by produceState<String?>(null, reading) {
        value = reading?.let { volumeCover(novels, it) }
    }
    return local ?: online
}

private suspend fun volumeCover(novels: NovelsUi, file: VolumeFile): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        novels.files.cover(file)?.let { "file://${it.absolutePath}" }
    }

@Composable
private fun NovelCard(novel: NovelSeries, novels: NovelsUi, version: Int, onClick: () -> Unit) {
    val last = remember(novel.key, version) { novels.progress.lastRead(novel.key) }
    val count = novel.volumes.size
    CoverCard(
        title = novel.title,
        subtitle = if (count == 1) "1 volume" else "$count volumes",
        url = rememberCover(novels, novel),
        onClick = onClick,
        detail = last?.label?.substringBefore(" of "),
        repeats = novel.mediaId?.let { novels.repeats[it] } ?: 0,
        onLongClick = novel.mediaId?.let { id -> { novels.aniList.navigator.open(Route.Media(id)); novels.aniListRequested() } },
        badge = if (novel.key in novels.newVolumes) "New volume" else null,
    )
}

@Composable
private fun SeriesPage(novel: NovelSeries, novels: NovelsUi) {
    val theme = LocalAppTheme.current
    val ui = novels.aniList
    val version = novels.progressVersion
    val last = remember(novel.key, version) { novels.progress.lastRead(novel.key) }
    var editing by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val names = remember(novel) { listOfNotNull(novel.media?.title?.english, novel.media?.title?.romaji, novel.title).filter { it.isNotBlank() }.distinct() }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { BackHeader(novel.title, onBack = { novels.openSeries = null }) }
        item {
            Row(Modifier.padding(horizontal = 20.dp)) {
                Box(Modifier.width(110.dp).height(158.dp)) {
                    Cover(rememberCover(novels, novel), Modifier.fillMaxSize())
                    novel.mediaId?.let { novels.repeats[it] }?.takeIf { it > 0 }?.let {
                        RepeatBadge(it, Modifier.align(Alignment.TopStart).padding(5.dp))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        last?.let { "${it.book.substringBeforeLast('.')} · ${it.label}" } ?: "Not started",
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                    )
                    PillButton(if (last != null) "Continue" else "Start", onClick = { novels.open(novel) }, enabled = novel.volumes.isNotEmpty())
                    val id = novel.mediaId
                    if (id != null) {
                        PillButton("AniList page", onClick = {
                            ui.navigator.open(Route.Media(id))
                            novels.aniListRequested()
                        }, filled = false)
                        novels.linkedFileTitle(id)?.let { fileTitle ->
                            PillButton("Unlink", onClick = { novels.link(novel, null, fileTitle) }, filled = false)
                        }
                    } else if (novels.loggedIn && !novel.custom) {
                        PillButton("Link to AniList", onClick = { linking = true }, filled = false)
                    }
                }
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            ) {
                PillButton("Find more volumes", onClick = { novels.searchVolumes(names, novel.title) }, filled = false)
                if (novel.custom) {
                    PillButton("Edit", onClick = { editing = true }, filled = false)
                    PillButton("Remove", onClick = { removing = true }, filled = false)
                }
            }
        }
        item { SectionTitle("Volumes") }
        items(novel.volumes, key = { it.uri }) { file ->
            VolumeRow(file, novel, novels, version, onClick = { novels.open(novel, novel.volumes.indexOf(file)) })
        }
        if (novel.volumes.isEmpty()) {
            item { Empty("No files found for this title") }
        }
    }
    if (editing) CustomNovelDialog(novels, existing = novel, onDismiss = { editing = false })
    if (linking) LinkDialog(novel, novels, onDismiss = { linking = false })
    if (removing) {
        ConfirmDialog(
            title = "Remove ${novel.title}?",
            message = "Your books stay in the folder. Only this entry and its reading progress go.",
            confirmLabel = "Remove",
            onConfirm = {
                removing = false
                novels.customs.remove(novel.key)
                novels.progress.forget(novel.key)
                novels.openSeries = null
                novels.refresh()
            },
            onDismiss = { removing = false },
        )
    }
}

@Composable
private fun VolumeRow(file: VolumeFile, novel: NovelSeries, novels: NovelsUi, version: Int, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    val state = remember(novel.key, file.name, version) { novels.progress.state(novel.key, file.name) }
    val cover by produceState<String?>(null, file) { value = volumeCover(novels, file) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(theme.surface.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Cover(cover, Modifier.width(44.dp).height(62.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                file.stem,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            BasicText(
                buildString {
                    append(if (file.isEpub) "EPUB" else "PDF")
                    append(" · ")
                    append(
                        if (state.length == 0 || (state.chapter == 0 && state.position == 0.0)) {
                            "Not started"
                        } else {
                            ChapterRules.label(state.segments, state.length, state.chapter, state.starts)
                        },
                    )
                },
                style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun CustomNovelDialog(novels: NovelsUi, existing: NovelSeries?, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    val saved = remember(existing) { existing?.let { novel -> novels.customs.customs().firstOrNull { it.id == novel.key }?.files } }
    var chosen by remember { mutableStateOf(saved.orEmpty().toSet()) }
    DialogFrame(onDismiss) {
        BasicText(
            if (existing == null) "Add a novel" else "Edit novel",
            style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
        InputBox(title, { title = it }, "Title", Modifier.fillMaxWidth(), background = theme.panel)
        BasicText(
            "Pick its files below. With none picked, files whose name contains the title are used.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
        )
        LazyColumn(Modifier.heightIn(max = 280.dp).fillMaxWidth()) {
            items(novels.folderFiles, key = { it.uri }) { file ->
                val on = file.uri in chosen
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chosen = if (on) chosen - file.uri else chosen + file.uri }
                        .padding(vertical = 8.dp),
                ) {
                    BasicText(
                        if (on) "✓" else "○",
                        style = TextStyle(color = if (on) theme.primary else theme.foreground, fontSize = 15.sp),
                        modifier = Modifier.width(24.dp),
                    )
                    BasicText(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.sp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Cancel", onClick = onDismiss, filled = false)
            PillButton("Save", enabled = title.isNotBlank(), onClick = {
                val files = chosen.toList()
                if (existing == null) {
                    if (!novels.customs.add(title, files)) context.showToast("That title already exists")
                } else {
                    novels.customs.update(existing.key, title, files)
                }
                novels.refresh()
                onDismiss()
            })
        }
    }
}

@Composable
private fun LinkDialog(novel: NovelSeries, novels: NovelsUi, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val api = novels.aniList.api
    var text by remember { mutableStateOf(novel.title) }
    var results by remember { mutableStateOf<List<Media>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    LaunchedEffect(text) {
        searched = false
        failed = false
        delay(350)
        if (text.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        try {
            results = api.search(text, MediaKind.LightNovel).value.items
            searched = true
        } catch (_: AniListException) {
            failed = true
        }
    }
    DialogFrame(onDismiss) {
        BasicText("Link to AniList", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
        InputBox(text, { text = it }, "Search light novels", Modifier.fillMaxWidth(), background = theme.panel)
        when {
            failed -> Hint("Could not reach AniList")
            searched && results.isEmpty() -> Empty(LookWords.nothingFound(momentStyle) + ". Try fewer words.")
            !searched && results.isEmpty() && text.isNotBlank() -> Hint("Searching...")
        }
        LazyColumn(Modifier.heightIn(max = 320.dp).fillMaxWidth()) {
            items(results, key = { it.id }) { media ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            novels.link(novel, media.id, novel.title)
                            onDismiss()
                        }
                        .padding(vertical = 6.dp),
                ) {
                    Cover(media.coverImage.large, Modifier.width(36.dp).height(52.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicText(media.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.sp))
                }
            }
        }
        PillButton("Cancel", onClick = onDismiss, filled = false)
    }
}

@Composable
internal fun DialogFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val theme = LocalAppTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(theme.surface)
                .padding(18.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun NovelDialogs(novels: NovelsUi) {
    if (novels.finding) {
        DialogFrame(onDismiss = {}) { Hint("Finding your place on AniList...") }
    }
    val state = novels.volumeSearch ?: return
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    DialogFrame(onDismiss = { novels.volumeSearch = null }) {
        BasicText(
            state.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
        when (state) {
            is VolumeSearch.Searching -> Hint("Searching for more volumes...")
            is VolumeSearch.Nothing -> Hint(
                when {
                    state.noSources -> "No light novel source is installed. Add one in Settings → Extensions."
                    state.failed -> "Could not reach the light novel sources"
                    else -> "Not found on the light novel sources"
                },
            )
            is VolumeSearch.Found -> {
                BasicText(
                    "This isn't in your novels folder. A link opens in the browser: save the book to that folder and it appears here.",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
                )
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    ActionRow("Open the ${state.site} page", onClick = { context.openLink(state.work.page) }, color = theme.primary)
                    state.work.volumes.forEach { link ->
                        ActionRow(link.title, onClick = { context.openLink(link.url) })
                    }
                }
            }
        }
        PillButton("Close", onClick = { novels.volumeSearch = null }, filled = false)
    }
}
