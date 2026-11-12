@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aniliphy.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.util.TypedValue
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.aniliphy.PlayerWindow
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListSync
import com.aniliphy.anime.AnimeRules
import com.aniliphy.source.AudioVersion
import com.aniliphy.anime.BackgroundAudio
import com.aniliphy.anime.PlaybackSpeed
import com.aniliphy.anime.PlayerOrientation
import com.aniliphy.anime.SkipSegments
import com.aniliphy.anime.StartEpisode
import com.aniliphy.anime.SubtitleBackground
import com.aniliphy.anime.SubtitleLanguage
import com.aniliphy.anime.VideoFit
import com.aniliphy.anime.VideoQuality
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.core.MissingSourceException
import com.aniliphy.source.Segment
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.SubtitleTrack
import com.aniliphy.manga.sources.SourceHttp
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class OpenedAnime(val episodes: List<SourceEpisode>, val start: StartEpisode, val offlineOnly: Boolean)

private val NETWORK_ERRORS = 2000..2999

private const val STREAM_RETRIES = 2

private sealed interface EpisodeVideo {
    data object Loading : EpisodeVideo
    data object NeedsData : EpisodeVideo
    data class Failed(val message: String) : EpisodeVideo

    @OptIn(UnstableApi::class)
    class Ready(
        val episodeId: String,
        val source: MediaSource,
        val intro: Segment?,
        val outro: Segment?,
        val local: Boolean,
        val note: String?,
        val server: String? = null,
    ) : EpisodeVideo
}

private enum class PlayerSheet { Episodes, Download, Source, Options }

private sealed interface Swipe {
    data class Brightness(val level: Float) : Swipe
    data class Volume(val level: Float) : Swipe
    data class Seek(val toMs: Long, val byMs: Long) : Swipe
}

@Composable
internal fun AnimePlayerScreen(request: AnimePlayerRequest, onClose: () -> Unit) {
    val anime = LocalAnime.current
    var attempt by remember { mutableIntStateOf(0) }
    val label = AnimeSites.name(AnimeSites.parse(request.source)?.first)
    val opened by produceState<Result<OpenedAnime>?>(null, attempt) {
        value = null
        value = runCatching { openAnime(anime, request, label) }
    }
    PlayerWindowRules(anime.settings.orientation)
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val outcome = opened
        val ready = outcome?.getOrNull()
        when {
            outcome == null -> Hint("Opening ${request.title}...", Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
            ready == null -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Hint(outcome.exceptionOrNull()?.message ?: "Could not open ${request.title}", color = Color.White)
                PillButton("Try again", onClick = { attempt++ })
                PillButton("Change source", onClick = { anime.search(anime.titleOf(request)) }, filled = false)
                PillButton("Close", onClick = onClose, filled = false)
            }
            else -> key(ready) { AnimePlayerBody(request, ready, label, onClose) }
        }
    }
}

private suspend fun openAnime(anime: AnimeUi, request: AnimePlayerRequest, label: String): OpenedAnime = withContext(Dispatchers.IO) {
    val record = anime.history.get(request.mediaId)
    val progress = if (anime.loggedIn) {
        try {
            anime.aniList.api.progress(request.mediaId)
        } catch (_: AniListException) {
            null
        }
    } else {
        null
    }
    val episodes = try {
        anime.episodes(request.source)
    } catch (e: IOException) {
        val saved = anime.offline.savedEpisodes(request.mediaId, request.source)
        if (saved.isEmpty()) throw if (e is MissingSourceException) e else IOException("$label can't be reached", e)
        return@withContext OpenedAnime(saved, AnimeRules.start(saved, progress, record), offlineOnly = true)
    }
    if (episodes.isEmpty()) throw IOException("No episodes on $label yet")
    OpenedAnime(episodes, AnimeRules.start(episodes, progress, record), offlineOnly = false)
}

@Composable
private fun PlayerWindowRules(orientation: PlayerOrientation) {
    val activity = LocalActivity.current
    DisposableEffect(orientation) {
        val before = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = when (orientation) {
            PlayerOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            PlayerOrientation.FollowPhone -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = before }
    }
    SystemBars(false)
    DisposableEffect(Unit) {
        onDispose {
            activity?.window?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AnimePlayerBody(request: AnimePlayerRequest, opened: OpenedAnime, siteLabel: String, onClose: () -> Unit) {
    val anime = LocalAnime.current
    val settings = anime.settings
    val context = LocalContext.current
    val activity = LocalActivity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val theme = LocalAppTheme.current
    val episodes = opened.episodes

    var index by rememberSaveable { mutableIntStateOf(opened.start.episode.coerceIn(episodes.indices)) }
    var startAt by rememberSaveable { mutableLongStateOf(opened.start.positionMs) }
    var audio by rememberSaveable { mutableStateOf(settings.audio) }
    var dataAllowed by remember { mutableStateOf(!settings.askOnMobileData) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var controls by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var askAniList by remember { mutableStateOf<Int?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    val failedServers = remember { mutableStateListOf<String>() }
    var playingFrom by remember { mutableStateOf<String?>(null) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var autoplay by remember { mutableStateOf<Job?>(null) }
    var swipe by remember { mutableStateOf<Swipe?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var height by remember { mutableStateOf<Int?>(null) }
    var subtitle by remember { mutableStateOf<String?>(null) }
    val counted = remember { mutableStateMapOf<String, Boolean>() }
    val skipped = remember { mutableStateMapOf<String, Boolean>() }
    val downloaded by produceState(emptySet<String>(), AnimeDownloads.version) {
        value = withContext(Dispatchers.IO) { anime.offline.downloadedIds(request.mediaId) }
    }
    val episode = episodes[index]
    val hasNext = index < episodes.lastIndex
    val inPip = PlayerWindow.inPictureInPicture

    LaunchedEffect(opened) {
        if (opened.offlineOnly) context.showToast("$siteLabel can't be reached. Showing downloaded episodes")
    }

    val player = remember {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    DisposableEffect(player, settings.backgroundAudio) {
        if (settings.backgroundAudio) BackgroundAudio.start(context, player)
        onDispose { BackgroundAudio.stop(context) }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var endedTimes by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) endedTimes++
            }

            override fun onPlayerError(error: PlaybackException) {
                val server = playingFrom
                if (server != null && error.errorCode in NETWORK_ERRORS && server !in failedServers) {
                    failedServers.add(server)
                    context.showToast("${server.substringAfter(':')} stopped. Trying another server")
                    startAt = player.currentPosition
                    loadAttempt++
                    return
                }
                playError = "Episode ${episodeLabel(episodes[index])} stopped: ${error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}"
            }

            override fun onTracksChanged(changed: Tracks) {
                tracks = changed
            }

            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    PlayerWindow.aspect = Rational(size.width, size.height).coerceForPip()
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0L
            buffered = player.bufferedPosition.coerceAtLeast(0)
            playing = player.isPlaying
            delay(POLL_MS)
        }
    }
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var hidden by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                hidden = true
                if (!settings.backgroundAudio && !PlayerWindow.inPictureInPicture) player.pause()
            }
            if (event == Lifecycle.Event.ON_START) hidden = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(settings.pictureInPicture, playing) {
        PlayerWindow.pictureInPicture = settings.pictureInPicture && playing
        onDispose { PlayerWindow.pictureInPicture = false }
    }

    val video by produceState<EpisodeVideo>(EpisodeVideo.Loading, index, audio, loadAttempt, dataAllowed) {
        value = EpisodeVideo.Loading
        playError = null
        value = loadVideo(context, anime, request, episode, audio, dataAllowed, fresh = loadAttempt > 0, skip = failedServers.toSet())
    }
    val ready = (video as? EpisodeVideo.Ready)?.takeIf { it.episodeId == episode.id }
    LaunchedEffect(ready) {
        ready ?: return@LaunchedEffect
        playingFrom = ready.server
        ready.note?.let { context.showToast(it) }
        player.setMediaSource(ready.source)
        player.prepare()
        if (startAt > 0) player.seekTo(startAt)
        player.setPlaybackSpeed(settings.speed.factor)
        player.playWhenReady = true
    }
    LaunchedEffect(settings.speed) { player.setPlaybackSpeed(settings.speed.factor) }
    val soundOnly = hidden && settings.backgroundAudio && !inPip
    LaunchedEffect(tracks, height, subtitle, settings.quality, settings.mobileQuality, settings.subtitleLanguage, soundOnly) {
        val quality = AnimeRules.quality(settings.quality, settings.mobileQuality, context.onMeteredNetwork())
        val builder = player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
            .setForceLowestBitrate(false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, soundOnly)
        val picked = height
        when {
            picked != null -> builder.setMaxVideoSize(Int.MAX_VALUE, picked).setMinVideoSize(0, picked)
            quality == VideoQuality.Best -> builder.setForceHighestSupportedBitrate(true)
            quality == VideoQuality.Worst -> builder.setForceLowestBitrate(true)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, AnimeRules.maxHeight(quality)!!)
        }
        val chosen = subtitle
        val language = AnimeRules.languageOf(settings.subtitleLanguage)
        when {
            chosen == "" || (chosen == null && settings.subtitleLanguage == SubtitleLanguage.Off) -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            chosen != null -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.length > 0 && it.getTrackFormat(0).label == chosen }
                    ?.let { builder.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
            }
            else -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguage(language)
        }
        player.trackSelectionParameters = builder.build()
    }

    fun markWatched(watched: SourceEpisode) {
        if (counted[watched.id] == true) return
        counted[watched.id] = true
        val number = AnimeRules.aniListEpisode(watched, request.totalEpisodes) ?: return
        if (!anime.loggedIn) {
            anime.watchedHere(request.mediaId, number)
            return
        }
        if (settings.aniListSync == AniListSync.Automatic) {
            anime.sendProgress(request.mediaId, number)
        } else {
            scope.launch {
                val known = try {
                    anime.aniList.api.progress(request.mediaId).progress
                } catch (_: AniListException) {
                    null
                }
                if (known == null || known < number) askAniList = maxOf(askAniList ?: 0, number)
            }
        }
    }
    fun savePlace(at: SourceEpisode, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        AnimeDownloads.background {
            anime.history.savePlace(
                request.mediaId, at, positionMs, durationMs, request.title, request.cover,
                total = request.totalEpisodes, titles = request.titles, mal = request.mal,
            )
        }
    }
    fun goEpisode(to: Int, at: Long = 0) {
        if (to !in episodes.indices) return
        autoplay?.cancel()
        countdown = null
        savePlace(episode, player.currentPosition, duration)
        player.stop()
        startAt = at
        index = to
    }
    fun nextEpisode() {
        if (hasNext) goEpisode(index + 1) else context.showToast("That's the latest episode on $siteLabel")
    }
    fun previousEpisode() {
        if (index > 0) goEpisode(index - 1) else context.showToast("That's the first episode")
    }
    fun seekBy(ms: Long) {
        val target = (player.currentPosition + ms).coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target)
    }

    LaunchedEffect(position, duration) {
        if (ready != null && AnimeRules.watched(position, duration, settings.watchedPercent)) markWatched(episode)
    }
    LaunchedEffect(index, ready) {
        while (ready != null) {
            delay(SAVE_EVERY_MS)
            if (player.isPlaying) {
                savePlace(episode, player.currentPosition, duration)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aniliphy.core.ActivityLog.watched(context, SAVE_EVERY_MS) }
            }
        }
    }
    val latest by rememberUpdatedState(Triple(episode, position, duration))
    DisposableEffect(Unit) {
        onDispose {
            val (last, at, length) = latest
            savePlace(last, at, length)
        }
    }

    LaunchedEffect(endedTimes) {
        if (endedTimes == 0) return@LaunchedEffect
        markWatched(episode)
        if (!hasNext) {
            controls = true
            context.showToast("That was the last episode on $siteLabel")
            return@LaunchedEffect
        }
        autoplay = scope.launch {
            for (left in settings.autoplayDelay downTo 1) {
                countdown = left
                delay(1_000)
            }
            countdown = null
            nextEpisode()
        }
    }

    val segments = listOfNotNull(ready?.intro?.let { "Skip intro" to it }, ready?.outro?.let { "Skip outro" to it })
    val inSegment = segments.firstOrNull { (_, segment) -> position / 1000.0 >= segment.start && position / 1000.0 < segment.end - 1 }
    LaunchedEffect(inSegment, settings.skipSegments) {
        val (label, segment) = inSegment ?: return@LaunchedEffect
        val key = "${episode.id}/$label"
        if (settings.skipSegments == SkipSegments.Automatic && skipped[key] != true) {
            skipped[key] = true
            player.seekTo((segment.end * 1000).toLong())
            context.showToast(if (label == "Skip intro") "Skipped the intro" else "Skipped the outro")
        }
    }

    LaunchedEffect(controls, playing, sheet, swipe) {
        if (controls && playing && sheet == null && swipe == null) {
            delay(HIDE_CONTROLS_MS)
            controls = false
        }
    }
    LaunchedEffect(flash) {
        if (flash != null) {
            delay(700)
            flash = null
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    this.player = player
                }
            },
            update = { playerView ->
                playerView.resizeMode = if (settings.fit == VideoFit.Fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                playerView.subtitleView?.apply {
                    setApplyEmbeddedStyles(false)
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, settings.subtitleSize.sp.toFloat() * if (inPip) 0.5f else 1f)
                    val box = settings.subtitleBackground == SubtitleBackground.Box
                    setStyle(
                        CaptionStyleCompat(
                            settings.subtitleColor.rgb.toInt(),
                            if (box) 0xAA000000.toInt() else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            if (box) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (inPip) return@Box

        val seekStep = settings.doubleTapSeek.seconds * 1000L
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(seekStep) {
                    detectTapGestures(
                        onTap = {
                            if (sheet != null) sheet = null else controls = !controls
                        },
                        onDoubleTap = { offset ->
                            val third = size.width / 3f
                            when {
                                offset.x < third -> {
                                    seekBy(-seekStep)
                                    flash = "-${settings.doubleTapSeek.seconds} s"
                                }
                                offset.x > third * 2 -> {
                                    seekBy(seekStep)
                                    flash = "+${settings.doubleTapSeek.seconds} s"
                                }
                                else -> if (player.isPlaying) player.pause() else player.play()
                            }
                        },
                    )
                }
                .then(if (settings.gestures) gestureModifier(context, activity, player, { duration }, { swipe = it }) else Modifier),
        )

        when (val state = video) {
            EpisodeVideo.Loading -> Hint("Loading episode ${episodeLabel(episode)}...", Modifier.align(Alignment.Center), color = Color.White)
            EpisodeVideo.NeedsData -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            ) {
                Hint("You're on mobile data. Episode ${episodeLabel(episode)} isn't downloaded.", color = Color.White)
                PillButton("Watch anyway", onClick = { dataAllowed = true })
                PillButton("Close", onClick = onClose, filled = false)
            }
            is EpisodeVideo.Failed -> FailedPanel(state.message, Modifier.align(Alignment.Center), onRetry = {
                failedServers.clear()
                loadAttempt++
            }, onEpisodes = { sheet = PlayerSheet.Episodes }, onSource = { anime.search(anime.titleOf(request)) }, onClose = onClose)
            is EpisodeVideo.Ready -> playError?.let { message ->
                FailedPanel(message, Modifier.align(Alignment.Center), onRetry = {
                    startAt = player.currentPosition
                    failedServers.clear()
                    loadAttempt++
                }, onEpisodes = { sheet = PlayerSheet.Episodes }, onSource = { anime.search(anime.titleOf(request)) }, onClose = onClose)
            }
        }

        swipe?.let { SwipeLabel(it, Modifier.align(Alignment.Center)) }
        flash?.let { OverlayText(it, Modifier.align(Alignment.Center)) }

        SizedControls(settings.controlsSize) {
        if (settings.skipSegments == SkipSegments.Button && inSegment != null && sheet == null) {
            PillButton(
                inSegment.first,
                onClick = { player.seekTo((inSegment.second.end * 1000).toLong()) },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 24.dp, bottom = if (controls) 110.dp else 32.dp),
            )
        }

        countdown?.let { left ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(20.dp),
            ) {
                val next = episodes.getOrNull(index + 1)
                BasicText(
                    "Episode ${next?.let(::episodeLabel) ?: ""} in $left s",
                    style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play now", onClick = ::nextEpisode)
                    PillButton("Cancel", onClick = {
                        autoplay?.cancel()
                        countdown = null
                        controls = true
                    }, filled = false)
                }
            }
        }

        if (controls && sheet == null) {
            PlayerTopBar(
                title = request.title,
                subtitle = episodeTitle(episode),
                onClose = onClose,
                onEpisodes = { sheet = PlayerSheet.Episodes },
                onDownload = { sheet = PlayerSheet.Download },
                onSource = { sheet = PlayerSheet.Source },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            if (ready != null && playError == null) Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center),
            ) {
                PillButton("Previous", onClick = ::previousEpisode, filled = false, enabled = index > 0)
                PillButton(if (playing) "Pause" else "Play", onClick = { if (player.isPlaying) player.pause() else player.play() })
                PillButton("Next", onClick = ::nextEpisode, filled = false, enabled = hasNext)
            }
            PlayerBottomBar(
                position = position,
                duration = duration,
                buffered = buffered,
                onSeek = { player.seekTo(it) },
                onOptions = { sheet = PlayerSheet.Options },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        val sheetModifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        when (sheet) {
            PlayerSheet.Episodes -> ReaderSheetFrame("Episodes", { sheet = null }, sheetModifier) {
                EpisodeList(
                    episodes,
                    index,
                    downloaded,
                    request.mediaId,
                    onGo = {
                        sheet = null
                        if (it != index) goEpisode(it)
                    },
                    onWatchedUpTo = { upTo ->
                        sheet = null
                        // AniList only ever moves forward, so marking an earlier episode changes nothing there.
                        val number = AnimeRules.aniListEpisode(episodes[upTo], request.totalEpisodes)
                        if (number != null && anime.loggedIn) anime.sendProgress(request.mediaId, number)
                        if (upTo + 1 < episodes.size) goEpisode(upTo + 1) else context.showToast("Marked as watched")
                    },
                )
            }
            PlayerSheet.Download -> ReaderSheetFrame("Download", { sheet = null }, sheetModifier) {
                EpisodeDownloads(episodes, index, downloaded, request.mediaId, onDownload = { chosen ->
                    sheet = null
                    anime.download(request, chosen, audio)
                }, onDelete = { AnimeDownloads.delete(anime.offline, request.mediaId, it.id) })
            }
            PlayerSheet.Source -> ReaderSheetFrame("Source", { sheet = null }, sheetModifier) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicText("Watching from $siteLabel", style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    BasicText(
                        AnimeSites.parse(request.source)?.second.orEmpty(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
                    )
                    BasicText(
                        "If this is the wrong show, or episodes are missing, search again to choose another one. " +
                            "When an episode won't play here, the backup site is tried.",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Search again", onClick = {
                            sheet = null
                            anime.search(anime.titleOf(request))
                        })
                        PillButton("Forget source", onClick = { anime.forgetSource(request) }, filled = false)
                    }
                }
            }
            PlayerSheet.Options -> ReaderSheetFrame("Options", { sheet = null }, sheetModifier) {
                val heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it).height } }
                    .filter { it > 0 }.distinct().sortedDescending()
                val labels = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
                    .mapNotNull { it.getTrackFormat(0).label }.distinct()
                val shownSubtitle = subtitle ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.label ?: ""
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SegmentedRow("Speed", PlaybackSpeed.entries, settings.speed, { it.label }, { settings.speed = it })
                    if (heights.isNotEmpty()) {
                        SegmentedRow("Quality", listOf<Int?>(null) + heights, height, { it?.let { h -> "${h}p" } ?: "Auto" }, { height = it })
                    }
                    if (ready?.local != true) {
                        SegmentedRow("Version", AudioVersion.entries, audio, { it.label }, { picked ->
                            if (picked != audio) {
                                startAt = player.currentPosition
                                audio = picked
                                settings.audio = picked
                            }
                        })
                    }
                    if (labels.isNotEmpty()) {
                        OptionLabel("Subtitles")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        ) {
                            PillButton("Off", onClick = { subtitle = "" }, filled = shownSubtitle == "")
                            labels.forEach { label -> PillButton(label, onClick = { subtitle = label }, filled = shownSubtitle == label) }
                        }
                    }
                    SegmentedRow("Video", VideoFit.entries, settings.fit, { it.label }, { settings.fit = it })
                    SegmentedRow("Skip intro and outro", SkipSegments.entries, settings.skipSegments, { it.label }, { settings.skipSegments = it })
                    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
                    if (anime.loggedIn) {
                        SegmentedRow("Update AniList", AniListSync.entries, settings.aniListSync, { it.label }, { settings.aniListSync = it })
                    }
                }
            }
            null -> Unit
        }
        }
    }
    askAniList?.let { number ->
        ConfirmDialog(
            title = "Update AniList?",
            message = "Set ${request.title} to episode $number watched.",
            confirmLabel = "Update",
            onConfirm = {
                askAniList = null
                anime.sendProgress(request.mediaId, number)
            },
            onDismiss = { askAniList = null },
        )
    }
}

@OptIn(UnstableApi::class)
private suspend fun loadVideo(
    context: Context,
    anime: AnimeUi,
    request: AnimePlayerRequest,
    episode: SourceEpisode,
    audio: AudioVersion,
    dataAllowed: Boolean,
    fresh: Boolean,
    skip: Set<String>,
): EpisodeVideo {
    val about = MediaMetadata.Builder()
        .setTitle("Episode ${episodeLabel(episode)}")
        .setArtist(request.title)
        .setArtworkUri(request.cover?.let(Uri::parse))
        .build()
    val saved = withContext(Dispatchers.IO) { anime.offline.saved(request.mediaId, episode) }
    if (saved != null) {
        val item = anime.offline.mediaItem(saved, subtitleConfigs(saved.subtitles, anime.settings.subtitleLanguage))
            .buildUpon().setMediaMetadata(about).build()
        val source = DefaultMediaSourceFactory(anime.offline.dataSourceFactory(emptyMap())).createMediaSource(item)
        return EpisodeVideo.Ready(episode.id, source, saved.intro, saved.outro, local = true, note = null)
    }
    if (!dataAllowed && context.onMeteredNetwork()) return EpisodeVideo.NeedsData
    return try {
        val resolved = anime.resolve(request, episode, audio, fresh, skip)
        val stream = resolved.stream
        val http = OkHttpDataSource.Factory(SourceHttp.client).setUserAgent(SourceHttp.USER_AGENT).setDefaultRequestProperties(stream.headers)
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setSubtitleConfigurations(subtitleConfigs(stream.subtitles, anime.settings.subtitleLanguage))
            .setMediaMetadata(about)
            .build()
        val source = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(STREAM_RETRIES))
            .createMediaSource(item)
        EpisodeVideo.Ready(episode.id, source, stream.intro, stream.outro, local = false, note = resolved.note, server = resolved.server)
    } catch (e: IOException) {
        EpisodeVideo.Failed(e.message ?: "Episode ${episodeLabel(episode)} couldn't be played")
    }
}

private fun subtitleConfigs(tracks: List<SubtitleTrack>, language: SubtitleLanguage): List<MediaItem.SubtitleConfiguration> {
    val wanted = AnimeRules.languageOf(language)
    var marked = false
    return tracks.map { track ->
        val code = AnimeRules.languageOf(track.label, track.language)
        val default = !marked && wanted != null && code == wanted
        if (default) marked = true
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
            .setMimeType(MimeTypes.TEXT_VTT)
            .setLanguage(code)
            .setLabel(track.label)
            .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
            .build()
    }
}

private fun gestureModifier(
    context: Context,
    activity: android.app.Activity?,
    player: Player,
    duration: () -> Long,
    show: (Swipe?) -> Unit,
): Modifier {
    val audio = context.getSystemService(AudioManager::class.java)
    return Modifier
        .pointerInput(Unit) {
            var left = true
            var level = 0f
            detectVerticalDragGestures(
                onDragStart = { start ->
                    left = start.x < size.width / 2
                    level = if (left) {
                        activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f
                    } else {
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    }
                },
                onDragEnd = { show(null) },
                onDragCancel = { show(null) },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    level = (level - amount / (size.height * 0.8f)).coerceIn(0f, 1f)
                    if (left) {
                        activity?.window?.let { window ->
                            window.attributes = window.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) }
                        }
                        show(Swipe.Brightness(level))
                    } else {
                        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                        show(Swipe.Volume(level))
                    }
                },
            )
        }
        .pointerInput(Unit) {
            var from = 0L
            var by = 0f
            detectHorizontalDragGestures(
                onDragStart = {
                    from = player.currentPosition
                    by = 0f
                },
                onDragEnd = {
                    val length = duration()
                    if (length > 0) player.seekTo((from + (by * SWIPE_MS_PER_WIDTH / size.width).toLong()).coerceIn(0, length))
                    show(null)
                },
                onDragCancel = { show(null) },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    by += amount
                    val delta = (by * SWIPE_MS_PER_WIDTH / size.width).toLong()
                    show(Swipe.Seek((from + delta).coerceIn(0, duration().takeIf { it > 0 } ?: Long.MAX_VALUE), delta))
                },
            )
        }
}

@Composable
private fun FailedPanel(message: String, modifier: Modifier, onRetry: () -> Unit, onEpisodes: () -> Unit, onSource: () -> Unit, onClose: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(20.dp),
    ) {
        Hint(message, color = Color.White)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Try again", onClick = onRetry)
            PillButton("Episodes", onClick = onEpisodes, filled = false)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Change source", onClick = onSource, filled = false)
            PillButton("Close", onClick = onClose, filled = false)
        }
    }
}

@Composable
private fun OverlayText(text: String, modifier: Modifier) {
    BasicText(
        text,
        style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun SwipeLabel(swipe: Swipe, modifier: Modifier) {
    val text = when (swipe) {
        is Swipe.Brightness -> "Brightness ${(swipe.level * 100).toInt()}%"
        is Swipe.Volume -> "Volume ${(swipe.level * 100).toInt()}%"
        is Swipe.Seek -> "${clock(swipe.toMs)} (${if (swipe.byMs >= 0) "+" else "-"}${clock(kotlin.math.abs(swipe.byMs))})"
    }
    OverlayText(text, modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerTopBar(
    title: String,
    subtitle: String,
    onClose: () -> Unit,
    onEpisodes: () -> Unit,
    onDownload: () -> Unit,
    onSource: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val heading = @Composable { rowModifier: Modifier ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
                PillButton("Back", onClick = onClose, filled = false)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    BasicText(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold))
                    BasicText(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp))
                }
            }
        }
        val buttons = @Composable {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Episodes", onClick = onEpisodes, filled = false)
                PillButton("Download", onClick = onDownload, filled = false)
                PillButton("Source", onClick = onSource, filled = false)
            }
        }
        if (maxWidth < WIDE_TOP_BAR) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                heading(Modifier.fillMaxWidth())
                buttons()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                heading(Modifier.weight(1f))
                buttons()
            }
        }
    }
}

@Composable
private fun PlayerBottomBar(position: Long, duration: Long, buffered: Long, onSeek: (Long) -> Unit, onOptions: () -> Unit, modifier: Modifier) {
    val theme = LocalAppTheme.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val length = duration.coerceAtLeast(1)
    val shown = if (dragging) dragValue else position.toFloat() / length
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .safeDrawingPadding()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Slider(
            value = shown.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * length).toLong())
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = theme.primary,
                activeTrackColor = theme.primary,
                inactiveTrackColor = Color.White.copy(alpha = if (buffered > position) 0.45f else 0.3f),
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "${clock(if (dragging) (dragValue * length).toLong() else position)} / ${clock(duration)}",
                style = TextStyle(color = Color.White, fontSize = 12.sp),
                modifier = Modifier.weight(1f),
            )
            PillButton("Options", onClick = onOptions)
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    val theme = LocalAppTheme.current
    BasicText(text, style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
}

private fun downloadNote(mediaId: Int, episode: SourceEpisode, downloaded: Set<String>): String? {
    AnimeDownloads.active[AnimeDownloads.key(mediaId, episode.id)]?.let { percent ->
        return if (percent < 0) "Waiting" else "$percent%"
    }
    return if (episode.id in downloaded) "Saved" else null
}

private fun episodeTitle(episode: SourceEpisode): String {
    val label = "Episode ${episodeLabel(episode)}"
    val name = episode.name.trim()
    val plain = name.isBlank() || name.equals(label, ignoreCase = true) ||
        Regex("""(?i)(ep(isode)?\.?\s*)?${Regex.escape(episodeLabel(episode))}""").matches(name)
    return if (plain) label else "$label · $name"
}

@Composable
private fun EpisodeList(
    episodes: List<SourceEpisode>,
    current: Int,
    downloaded: Set<String>,
    mediaId: Int,
    onGo: (Int) -> Unit,
    onWatchedUpTo: (Int) -> Unit,
) {
    val theme = LocalAppTheme.current
    var filter by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Int?>(null) }
    val shown = remember(filter, episodes) {
        episodes.indices.filter { filter.isBlank() || episodeTitle(episodes[it]).contains(filter.trim(), ignoreCase = true) }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Column {
        SearchField(filter, { filter = it }, "Find an episode", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        LazyColumn(state = list, modifier = Modifier.heightIn(max = 300.dp)) {
            itemsIndexed(shown, key = { _, index -> episodes[index].id }) { _, index ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onGo(index) }, onLongClick = { asking = index })
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                ) {
                    BasicText(
                        episodeTitle(episodes[index]),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (index == current) theme.primary else theme.foreground,
                            fontSize = 14.sp,
                            fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    downloadNote(mediaId, episodes[index], downloaded)?.let {
                        BasicText(it, style = TextStyle(color = theme.secondary, fontSize = 12.sp))
                    }
                }
            }
        }
        Hint("Hold an episode to mark it and the ones before as watched", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    asking?.let { index ->
        ConfirmDialog(
            title = "Watched up to here?",
            message = "Marks ${episodeTitle(episodes[index])} and everything before it as watched on AniList, and opens the next episode.",
            confirmLabel = "Mark as watched",
            onConfirm = {
                asking = null
                onWatchedUpTo(index)
            },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun EpisodeDownloads(
    episodes: List<SourceEpisode>,
    current: Int,
    downloaded: Set<String>,
    mediaId: Int,
    onDownload: (List<SourceEpisode>) -> Unit,
    onDelete: (SourceEpisode) -> Unit,
) {
    val theme = LocalAppTheme.current
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0))
    val notSaved = { episode: SourceEpisode -> episode.id !in downloaded }
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            PillButton("This episode", onClick = { onDownload(listOf(episodes[current]).filter(notSaved)) }, filled = false)
            PillButton("Next 3", onClick = { onDownload(episodes.drop(current + 1).filter(notSaved).take(3)) }, filled = false)
        }
        LazyColumn(state = list, modifier = Modifier.heightIn(max = 260.dp)) {
            itemsIndexed(episodes, key = { _, episode -> episode.id }) { _, episode ->
                val note = downloadNote(mediaId, episode, downloaded)
                val selected = chosen[episode.id] == true
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = note == null) { chosen[episode.id] = !selected }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    BasicText(
                        if (note == null) (if (selected) "☑" else "☐") else "✓",
                        style = TextStyle(color = theme.primary, fontSize = 16.sp),
                        modifier = Modifier.width(28.dp),
                    )
                    BasicText(episodeTitle(episode), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.weight(1f))
                    when (note) {
                        null -> Unit
                        "Saved" -> PillButton("Delete", onClick = { onDelete(episode) }, filled = false)
                        else -> BasicText(note, style = TextStyle(color = theme.secondary, fontSize = 12.sp))
                    }
                }
            }
        }
        val picked = episodes.filter { chosen[it.id] == true && it.id !in downloaded }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            PillButton(if (picked.isEmpty()) "Choose episodes" else "Download ${picked.size}", onClick = { onDownload(picked) }, enabled = picked.isNotEmpty())
        }
    }
}

private val WIDE_TOP_BAR = 560.dp

internal fun clock(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = seconds % 3600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

private fun Rational.coerceForPip(): Rational {
    val value = toFloat()
    return when {
        value > 2.39f -> Rational(239, 100)
        value < 1 / 2.39f -> Rational(100, 239)
        else -> this
    }
}

private const val POLL_MS = 250L
private const val SAVE_EVERY_MS = 5_000L
private const val HIDE_CONTROLS_MS = 3_500L
private const val SWIPE_MS_PER_WIDTH = 120_000f
