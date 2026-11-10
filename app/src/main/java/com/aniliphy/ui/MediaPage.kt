package com.aniliphy.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.FavouriteTarget
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun MediaPage(id: Int, retained: Retained) {
    val ui = LocalAniList.current
    val loggedIn = ui.session.userId != null
    val load = retained.get("media") {
        ui.load<Media> {
            coroutineScope {
                val summary = async { ui.api.media(id) }
                val details = async { ui.api.details(id) }
                val a = summary.await()
                val b = details.await()
                Fetched(
                    b.value.copy(
                        description = a.value.description,
                        coverImage = a.value.coverImage,
                        averageScore = a.value.averageScore,
                        episodes = a.value.episodes,
                        chapters = a.value.chapters,
                        volumes = a.value.volumes,
                        genres = a.value.genres,
                        siteUrl = a.value.siteUrl,
                        status = a.value.status,
                        countryOfOrigin = a.value.countryOfOrigin,
                        idMal = a.value.idMal,
                        title = b.value.title.copy(native = b.value.title.native ?: a.value.title.native),
                    ),
                    a.stale || b.stale,
                )
            }
        }
    }
    val progress = retained.get("progress") {
        ui.load<ListProgress>(autoStart = loggedIn) { Fetched(ui.api.progress(id)) }
    }
    val scroll = retained.get("scroll") { ScrollState(0) }
    var editing by remember { mutableStateOf(false) }
    val media = load.value

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(media?.let { Formatting.kindLabel(it.kind) } ?: "Details") { ui.navigator.back() }
        when {
            media != null -> Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
                StaleNotice(load.stale)
                MediaHeader(media, progress.value, loggedIn, retained, onEdit = { editing = true })
                DetailsBody(media)
            }
            load.error != null -> ErrorRow(load.error!!) { load.load() }
            else -> Hint("Loading...")
        }
    }

    if (editing && media != null) {
        ListEditorDialog(
            media = media,
            current = progress.value,
            onSaved = {
                editing = false
                progress.load()
                ui.navigator.listsChanged()
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun MediaHeader(
    media: Media,
    progress: ListProgress?,
    loggedIn: Boolean,
    retained: Retained,
    onEdit: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val anime = media.kind == MediaKind.Anime
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Cover(media.coverImage.best, Modifier.width(128.dp).height(184.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                media.title.display,
                style = TextStyle(color = theme.foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            val facts = listOfNotNull(
                Formatting.format(media.format).takeIf { media.format != null },
                media.averageScore?.takeIf { it > 0 }?.let { "★ $it%" },
                if (anime) media.episodes?.let { "$it eps" } else media.chapters?.let { "$it ch" },
                if (anime) null else media.volumes?.let { "$it vols" },
            ).joinToString(" · ")
            BasicText(facts, style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp))
            if (loggedIn && progress != null) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    ListStatus.label(progress.status, media.kind) + if (progress.status != null) yourProgress(media, progress) else "",
                    style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                )
                Formatting.score(progress.score)?.let {
                    BasicText("Your score: $it/10", style = TextStyle(color = theme.accent, fontSize = 13.sp))
                }
            }
            Formatting.nextEpisode(media)?.takeIf { anime }?.let {
                Spacer(Modifier.height(8.dp))
                BasicText(it, style = TextStyle(color = theme.secondary, fontSize = 12.sp))
            }
        }
        if (loggedIn) FavouriteStar(media, retained)
    }
    Spacer(Modifier.height(14.dp))
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        val novels = LocalNovels.current
        val manga = LocalManga.current
        val watcher = LocalAnime.current
        PillButton(
            if (anime) "Watch" else "Read",
            onClick = {
                when {
                    anime -> watcher.watchMedia(media)
                    media.kind == MediaKind.LightNovel -> novels.readMedia(media)
                    else -> manga.readMedia(media)
                }
            },
        )
        if (loggedIn) {
            PillButton(
                if (progress?.status == null) "Add to list" else "Edit entry",
                onEdit,
                filled = false,
                enabled = progress != null,
            )
        }
    }
}

private fun yourProgress(media: Media, progress: ListProgress): String {
    val total = if (media.kind == MediaKind.Anime) media.episodes else media.chapters
    return " · ${progress.progress} / ${total?.takeIf { it > 0 } ?: "?"}"
}

@Composable
private fun FavouriteStar(media: Media, retained: Retained) {
    val target = if (media.kind == MediaKind.Anime) FavouriteTarget.Anime(media.id) else FavouriteTarget.Manga(media.id)
    Star(retained, media.isFavourite, target)
}

@Composable
internal fun Star(retained: Retained, initial: Boolean, target: FavouriteTarget) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val favourite = retained.get("favourite") { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = if (favourite.value) "Remove from favourites" else "Add to favourites") {
                busy = true
                scope.launch {
                    try {
                        ui.api.toggleFavourite(target)
                        favourite.value = !favourite.value
                        ui.navigator.listsChanged()
                        context.showToast(if (favourite.value) "Added to favourites" else "Removed from favourites")
                    } catch (e: AniListException) {
                        context.showToast(ui.describe(e))
                    } finally {
                        busy = false
                    }
                }
            },
    ) {
        BasicText(
            if (favourite.value) "★" else "☆",
            style = TextStyle(color = if (favourite.value) theme.accent else theme.foreground, fontSize = 24.sp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsBody(media: Media) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val open = ui.navigator::open
    val body = TextStyle(color = theme.foreground, fontSize = 14.sp, lineHeight = 20.sp)

    SectionTitle("Synopsis")
    BasicText(Formatting.cleanDescription(media.description), style = body, modifier = Modifier.padding(horizontal = 20.dp))

    if (media.genres.isNotEmpty()) {
        SectionTitle("Genres")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            media.genres.forEach { genre ->
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    BasicText(genre, style = TextStyle(color = theme.foreground, fontSize = 12.sp))
                }
            }
        }
    }

    SectionTitle("Information")
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Formatting.infoRows(media).forEach { (label, value) ->
            Row {
                BasicText(
                    label,
                    style = TextStyle(color = theme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.width(92.dp),
                )
                BasicText(value, style = TextStyle(color = theme.foreground, fontSize = 13.sp))
            }
        }
    }

    val (studios, producers) = Formatting.splitStudios(media)
    if (studios.isNotEmpty()) {
        SectionTitle("Studios")
        NamedLinks(studios.map { (it.name ?: "?") to it.id }) { open(Route.Studio(it)) }
    }
    if (producers.isNotEmpty()) {
        SectionTitle("Producers")
        NamedLinks(producers.map { (it.name ?: "?") to it.id }) { open(Route.Studio(it)) }
    }

    val characters = media.characters.edges.filter { it.node != null }
    if (characters.isNotEmpty()) {
        CoverRow("Characters", characters, { it.node!!.id }) { edge ->
            val node = edge.node!!
            CoverCard(
                title = node.name.full ?: "?",
                subtitle = listOfNotNull(
                    Formatting.nice(edge.role),
                    edge.voiceActors.firstOrNull()?.name?.full?.let { "VA: $it" },
                ).joinToString("\n"),
                url = node.image?.best,
                onClick = { open(Route.Character(node.id)) },
                wide = false,
            )
        }
    }
    val staff = media.staff.edges.filter { it.node != null }
    if (staff.isNotEmpty()) {
        CoverRow("Staff", staff, { "${it.node!!.id}:${it.role}" }) { edge ->
            val node = edge.node!!
            CoverCard(
                title = node.name.full ?: "?",
                subtitle = edge.role,
                url = node.image?.best,
                onClick = { open(Route.Staff(node.id)) },
                wide = false,
            )
        }
    }
    val relations = Formatting.relations(media)
    if (relations.isNotEmpty()) {
        CoverRow("Related", relations, { it.id }) { item ->
            CoverCard(item.title, item.kind, item.cover, { open(Route.Media(item.id)) }, wide = false)
        }
    }
    val recommendations = Formatting.recommendations(media)
    if (recommendations.isNotEmpty()) {
        CoverRow("Recommendations", recommendations, { it.id }) { item ->
            CoverCard(item.title, item.kind, item.cover, { open(Route.Media(item.id)) }, wide = false)
        }
    }
    media.siteUrl?.let { url ->
        Spacer(Modifier.height(8.dp))
        ActionRow("Open on AniList", onClick = { context.openLink(url) }, color = theme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamedLinks(items: List<Pair<String, Int>>, open: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
        items.forEach { (name, id) ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.surface)
                    .clickable { open(id) }
                    .padding(horizontal = 11.dp, vertical = 8.dp),
            ) {
                BasicText(name, style = TextStyle(color = theme.foreground, fontSize = 13.sp))
            }
        }
    }
}
