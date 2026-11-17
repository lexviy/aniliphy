package com.aniliphy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.aniliphy.anilist.CoverImage
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.SceneMatch
import com.aniliphy.anilist.SceneQuota
import com.aniliphy.anilist.SceneSearch
import com.aniliphy.anilist.SceneSearchException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Finding the anime a picture comes from: a screenshot shared to "Find this anime", or picked with the picture button
 * on Home. The picture is made smaller and sent to trace.moe only; nothing about the account goes with it.
 */
internal object SceneFinder {
    var open by mutableStateOf(false)
        private set
    var picture by mutableStateOf<ImageBitmap?>(null)
        private set
    var searching by mutableStateOf(false)
        private set
    var matches by mutableStateOf<List<SceneMatch>?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var quota by mutableStateOf<SceneQuota?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    fun find(context: Context, uri: Uri) {
        job?.cancel()
        open = true
        picture = null
        matches = null
        problem = null
        searching = true
        // Read now, while the app sharing the picture still lets us.
        val resolver = context.applicationContext.contentResolver
        job = scope.launch {
            try {
                val (bitmap, jpeg) = withContext(Dispatchers.IO) { prepare(resolver, uri) }
                picture = bitmap.asImageBitmap()
                val search = SceneSearch()
                matches = withContext(Dispatchers.IO) { search.search(jpeg) }.filter { !it.adult }
                quota = withContext(Dispatchers.IO) { runCatching { search.quota() }.getOrNull() }
            } catch (e: SceneSearchException) {
                problem = e.message
            } catch (_: IOException) {
                problem = "trace.moe can't be reached. Check the connection and try again"
            } catch (_: SecurityException) {
                problem = "aniliphy wasn't allowed to open that picture"
            } finally {
                searching = false
            }
        }
    }

    fun close() {
        job?.cancel()
        open = false
        picture = null
        matches = null
        problem = null
    }

    /** At most 1280 px on the long side, as JPEG: small to send, and enough for trace.moe. */
    private fun prepare(resolver: android.content.ContentResolver, uri: Uri): Pair<Bitmap, ByteArray> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Only reads the size (decodeStream answers null when asked just for that).
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit } ?: throw IOException("No picture")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw SceneSearchException("That isn't a picture aniliphy can read")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw SceneSearchException("That isn't a picture aniliphy can read")
        val long = maxOf(decoded.width, decoded.height)
        val bitmap = if (long > 1280) {
            val scale = 1280f / long
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else {
            decoded
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return bitmap to out.toByteArray()
    }
}

/** The picture button at the end of Home's search: pick a screenshot to find its anime. */
@Composable
internal fun PictureSearchButton() {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) SceneFinder.find(context, uri)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Find an anime by a screenshot") {
                pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            .semantics { contentDescription = "Find an anime by a screenshot" },
    ) {
        // A small picture frame with a mountain and a sun.
        Canvas(Modifier.size(20.dp)) {
            val u = size.minDimension / 20f
            val line = theme.primary
            drawRoundRect(line, Offset(2f * u, 4f * u), androidx.compose.ui.geometry.Size(16f * u, 12f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = Stroke(1.6f * u))
            drawCircle(line, 1.6f * u, Offset(13.5f * u, 8f * u))
            drawLine(line, Offset(4f * u, 14f * u), Offset(8f * u, 9.5f * u), 1.6f * u, StrokeCap.Round)
            drawLine(line, Offset(8f * u, 9.5f * u), Offset(11f * u, 12.5f * u), 1.6f * u, StrokeCap.Round)
            drawLine(line, Offset(11f * u, 12.5f * u), Offset(13f * u, 11f * u), 1.6f * u, StrokeCap.Round)
            drawLine(line, Offset(13f * u, 11f * u), Offset(16f * u, 14f * u), 1.6f * u, StrokeCap.Round)
        }
    }
}

private fun minutes(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun SceneMatch.asMedia() = Media(
    id = mediaId,
    idMal = mal,
    type = "ANIME",
    format = format,
    title = title,
    coverImage = CoverImage(large = cover),
    bannerImage = banner,
    episodes = episodes,
)

/** The episode to open: the one found, or the only one of a movie. */
private val SceneMatch.playEpisode: Int?
    get() = episode ?: if (format == "MOVIE" || episodes == 1) 1 else null

/** What was found for a picture: the scene, the anime, the episode and minute, and a way to watch from there. */
@Composable
internal fun SceneSheet() {
    if (!SceneFinder.open) return
    val theme = LocalAppTheme.current
    Dialog(
        onDismissRequest = SceneFinder::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(theme.background)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp)) {
                    val title = when {
                        SceneFinder.searching -> "Looking..."
                        SceneFinder.problem != null -> "Couldn't look"
                        SceneFinder.matches.isNullOrEmpty() -> "Not found"
                        else -> "Found it"
                    }
                    BasicText(title, style = TitleStyle(color = theme.foreground, fontSize = 26.sp), modifier = Modifier.weight(1f).lookAlive(Alive.Heading))
                    BasicText(
                        "✕",
                        style = TextStyle(color = theme.foreground, fontSize = 20.sp),
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = SceneFinder::close).padding(12.dp),
                    )
                }
                val matches = SceneFinder.matches
                when {
                    SceneFinder.searching -> {
                        SceneFinder.picture?.let { PictureBox(it) }
                        LookLoading("Asking trace.moe where this scene is from...", Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = theme.foreground.copy(alpha = 0.75f))
                    }
                    SceneFinder.problem != null -> {
                        SceneFinder.picture?.let { PictureBox(it) }
                        Hint(SceneFinder.problem!!, color = theme.error)
                    }
                    matches.isNullOrEmpty() -> {
                        SceneFinder.picture?.let { PictureBox(it) }
                        Hint("trace.moe didn't find this scene. A frame from the episode itself works best: no subtitles, borders or cropping.")
                    }
                    else -> SceneResults(matches)
                }
                Spacer(Modifier.height(8.dp))
                val left = SceneFinder.quota?.let { " · ${it.left} searches left this month" }.orEmpty()
                BasicText(
                    "By trace.moe$left · the picture goes only to trace.moe",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 11.5.sp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PictureBox(picture: ImageBitmap) {
    val theme = LocalAppTheme.current
    Image(
        picture,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(lookShape(8.dp))
            .border(1.5.dp, theme.foreground.copy(alpha = 0.6f), lookShape(8.dp)),
    )
}

@Composable
private fun SceneResults(matches: List<SceneMatch>) {
    val ui = LocalAniList.current
    val anime = LocalAnime.current
    val theme = LocalAppTheme.current
    var picked by remember(matches) { mutableIntStateOf(0) }
    val match = matches[picked.coerceIn(matches.indices)]
    val percent = (match.similarity * 100).toInt()
    val shape = lookShape(8.dp)
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .border(1.5.dp, theme.foreground.copy(alpha = 0.6f), shape)
            .background(Color.Black),
    ) {
        val clip = match.clipUrl
        if (clip != null) SceneClip(clip, match.imageUrl) else AsyncImage(match.imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        BasicText(
            if (match.sure) "$percent% MATCH" else "$percent% · NOT SURE",
            style = NumberStyle(color = theme.background, fontSize = 11.sp, bold = true),
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(if (match.sure) theme.success else theme.warning).padding(horizontal = 6.dp, vertical = 2.dp),
        )
        BasicText(
            "▶ ${minutes(match.fromSeconds)} – ${minutes(match.toSeconds)}",
            style = NumberStyle(color = Color.White, fontSize = 11.sp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
    if (!match.sure) {
        BasicText(
            "Could be another show drawn alike. A frame without subtitles or borders works best.",
            style = TextStyle(color = theme.warning, fontSize = 12.5.sp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
    }
    val entry = ui.entryFor(match.mediaId)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Box(Modifier.size(width = 62.dp, height = 88.dp)) { Cover(match.cover, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                match.title.display,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 21.sp),
            )
            Spacer(Modifier.height(3.dp))
            BasicText(
                listOfNotNull(match.episode?.let { "Episode $it" } ?: if (match.format == "MOVIE") "Movie" else null, minutes(match.atSeconds)).joinToString(" · "),
                style = NumberStyle(color = theme.primary, fontSize = 14.sp, bold = true),
            )
            BasicText(
                listOfNotNull(
                    match.format?.replace('_', ' '),
                    entry?.let { "on your list: ${ListStatus.label(it.status, MediaKind.Anime)}" + (if (it.progress > 0) ", ep ${it.progress}" else "") } ?: "not on your list",
                ).joinToString(" · "),
                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
            )
        }
    }
    val episode = match.playEpisode
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (episode != null) {
            PillButton("▶ Watch from ${minutes(match.atSeconds)}", onClick = {
                SceneFinder.close()
                anime.watchFrom(match.asMedia(), episode, (match.atSeconds * 1000).toLong())
            }, modifier = Modifier.lookAlive(Alive.Button))
        }
        PillButton("Open page", onClick = {
            SceneFinder.close()
            AniListLinkRequest.route = Route.Media(match.mediaId)
        }, filled = false)
    }
    if (episode != null && entry != null && episode > entry.progress + 1) {
        BasicText(
            "Episode $episode is past your progress (${entry.progress}): AniList only moves when you finish it, as always.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
    val others = matches.indices.filter { it != picked }
    if (others.isNotEmpty()) {
        SectionTitle("Other matches")
        others.forEach { i ->
            val other = matches[i]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { picked = i }.padding(horizontal = 20.dp, vertical = 6.dp),
            ) {
                Box(Modifier.size(width = 36.dp, height = 50.dp)) { Cover(other.cover, Modifier.fillMaxSize()) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(other.title.display, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.5.sp, fontWeight = FontWeight.Bold))
                    BasicText(
                        listOfNotNull(other.episode?.let { "Episode $it" }, minutes(other.atSeconds)).joinToString(" · "),
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp),
                    )
                }
                BasicText(
                    "${(other.similarity * 100).toInt()}%",
                    style = NumberStyle(color = if (other.sure) theme.success else theme.warning, fontSize = 13.sp, bold = true),
                )
            }
        }
    }
}

/** trace.moe's few seconds of the scene, muted and looping; its still while it loads. */
@OptIn(UnstableApi::class)
@Composable
private fun SceneClip(url: String, still: String?) {
    val context = LocalContext.current
    var started by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    started = true
                }
            })
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!started && still != null) AsyncImage(still, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
