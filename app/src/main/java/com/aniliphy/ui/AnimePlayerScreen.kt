@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.aniliphy.ui

import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Rational
import android.util.TypedValue
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.aniliphy.anime.EpisodeKind
import com.aniliphy.anime.FillerPrefs
import com.aniliphy.anime.Fillers
import com.aniliphy.anime.TitleFillers
import com.aniliphy.anime.videoMimeOf
import com.aniliphy.anime.withAudio
import com.aniliphy.anime.ExtraAudio
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
import com.aniliphy.anime.SubtitleColor
import com.aniliphy.anime.SubtitleSize
import android.content.Intent
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
        /** For casting: the stream and its subtitles (null for downloads). */
        val cast: com.aniliphy.anime.CastMedia? = null,
    ) : EpisodeVideo
}

private enum class PlayerSheet { Episodes, Download, Source, Options, Frame, OpenIn, Jimaku, Cast }

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
            outcome == null -> LookLoading("Opening ${request.title}...", Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
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
            anime.takeJustRead(request.mediaId) ?: anime.aniList.api.progress(request.mediaId)
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

    // A scene found by picture starts at its episode and time, instead of where you left off.
    val asked = remember {
        request.startEpisode?.let { n -> episodes.indexOfFirst { AnimeRules.aniListEpisode(it, request.totalEpisodes) == n }.takeIf { it >= 0 } }
    }
    var index by rememberSaveable { mutableIntStateOf((asked ?: opened.start.episode).coerceIn(episodes.indices)) }
    var startAt by rememberSaveable { mutableLongStateOf(if (asked != null) request.startMs else opened.start.positionMs) }
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
    // The forum thread for the episode just watched, found when it counts as watched.
    var discussion by remember { mutableStateOf<Pair<Int, com.aniliphy.anilist.ForumThread>?>(null) }
    var autoplay by remember { mutableStateOf<Job?>(null) }
    // The frame taken with the camera button, and when in the episode.
    val playerViewHolder = remember { arrayOfNulls<PlayerView>(1) }
    var frame by remember { mutableStateOf<Pair<android.graphics.Bitmap, Long>?>(null) }
    var frameFlash by remember { mutableStateOf(false) }
    // Subtitles kept for this title (delay, own style) and files loaded for an episode.
    val titleSubs = remember { com.aniliphy.anime.TitleSubtitles(context.filesDir) }
    var subsChoice by remember { mutableStateOf(titleSubs.get(request.mediaId)) }
    var subsVersion by remember { mutableIntStateOf(0) }
    // A reload for a subtitle change keeps playing only if it was playing.
    var playAfterLoad by remember { mutableStateOf(true) }
    var delayDraft by remember { mutableLongStateOf(subsChoice.delayMs) }
    // The sleep timer: when it runs out (elapsedRealtime), or at the end of the episode.
    var sleep by remember { mutableStateOf(com.aniliphy.anime.SleepChoice.Off) }
    val handoff = LocalSync.current?.handoff
    var sendToPc by remember { mutableStateOf(false) }
    var sleepEnd by remember { mutableStateOf<Long?>(null) }
    var sleepLeft by remember { mutableStateOf<Long?>(null) }
    var swipe by remember { mutableStateOf<Swipe?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var height by remember { mutableStateOf<Int?>(null) }
    var subtitle by remember { mutableStateOf<String?>(null) }
    // A Japanese file for this episode, drawn by the app so each word can be tapped (Settings › Japanese).
    val japanese = remember { com.aniliphy.japanese.JapaneseSettings.get(context) }
    val counted = remember { mutableStateMapOf<String, Boolean>() }
    val skipped = remember { mutableStateMapOf<String, Boolean>() }
    val downloaded by produceState(emptySet<String>(), AnimeDownloads.version) {
        value = withContext(Dispatchers.IO) { anime.offline.downloadedIds(request.mediaId) }
    }
    // The theme radio waits while an episode plays.
    LaunchedEffect(Unit) { ThemeRadio.pause() }
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

    // Sharpening on the GPU: the title's own level or the default; stepped down for this session if frames drop.
    val sharpenTitles = remember { com.aniliphy.anime.SharpenTitles(context) }
    var titleSharpen by remember { mutableStateOf(sharpenTitles.get(request.mediaId)) }
    var sharpenCap by remember { mutableStateOf<com.aniliphy.anime.SharpenLevel?>(null) }
    val sharpenChosen = titleSharpen ?: settings.sharpen
    val sharpen = sharpenCap?.takeIf { it < sharpenChosen } ?: sharpenChosen
    val sharpenGuard = remember { com.aniliphy.anime.SharpenGuard() }
    LaunchedEffect(sharpen) {
        sharpenGuard.reset()
        player.setVideoEffects(if (sharpen == com.aniliphy.anime.SharpenLevel.Off) emptyList() else listOf(com.aniliphy.anime.SharpenEffect(sharpen)))
    }
    DisposableEffect(player) {
        val drops = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                val now = sharpenCap?.takeIf { it < (titleSharpen ?: settings.sharpen) } ?: (titleSharpen ?: settings.sharpen)
                if (!settings.sharpenAuto || now == com.aniliphy.anime.SharpenLevel.Off) return
                if (sharpenGuard.dropped(droppedFrames, android.os.SystemClock.elapsedRealtime())) {
                    sharpenCap = now.lower()
                    context.showToast("Sharpen turned down to ${now.lower().label}: the phone was dropping frames")
                }
            }
        }
        player.addAnalyticsListener(drops)
        onDispose { player.removeAnalyticsListener(drops) }
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
    /** Posts the paused-episode notification; set further down, where the opening and ending are known. */
    val pausedNotice = remember { arrayOfNulls<() -> Unit>(1) }
    var hidden by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                hidden = true
                if (!settings.backgroundAudio && !PlayerWindow.inPictureInPicture) {
                    player.pause()
                    // Playing in the background has its own media notification; this is for the episode left paused.
                    if (settings.pausedNotice && player.duration > 0) pausedNotice[0]?.invoke()
                }
            }
            if (event == Lifecycle.Event.ON_START) {
                hidden = false
                com.aniliphy.work.LiveNotifications.clearPaused(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            com.aniliphy.work.LiveNotifications.clearPaused(context)
        }
    }
    // "Close" on the paused notification.
    val closeAsked = com.aniliphy.work.LiveNotifications.closePlayer
    val closeSeen = remember { intArrayOf(closeAsked) }
    LaunchedEffect(closeAsked) {
        if (closeAsked != closeSeen[0]) onClose()
    }
    DisposableEffect(settings.pictureInPicture, playing) {
        PlayerWindow.pictureInPicture = settings.pictureInPicture && playing
        onDispose { PlayerWindow.pictureInPicture = false }
    }

    val video by produceState<EpisodeVideo>(EpisodeVideo.Loading, index, audio, loadAttempt, dataAllowed, subsVersion, subsChoice.delayMs) {
        value = EpisodeVideo.Loading
        playError = null
        val extra = withContext(Dispatchers.IO) { titleSubs.tracks(request.mediaId, episode.id) }
        value = loadVideo(
            context, anime, request, episode, audio, dataAllowed, fresh = loadAttempt > 0, skip = failedServers.toSet(),
            extra = extra, delayMs = subsChoice.delayMs,
        )
    }
    val japaneseLines by produceState<Pair<String, List<com.aniliphy.japanese.Cue>>?>(null, episode.id, subsVersion, japanese.dual) {
        value = null
        if (japanese.dual) value = withContext(Dispatchers.IO) { japaneseFile(titleSubs.tracks(request.mediaId, episode.id)) }
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
        player.playWhenReady = playAfterLoad
        playAfterLoad = true
    }
    // Casting: the TV plays through the phone's relay; the phone's player waits paused and takes over where the TV stopped.
    val cast = rememberCastControl(context) { at ->
        if (at > 0) player.seekTo(at)
        player.pause()
    }
    fun subtitleShown(): String? = subtitle?.takeIf { it.isNotEmpty() && it != japaneseLines?.first }
        ?: if (subtitle == "") null else player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.label
    LaunchedEffect(cast.session, ready) {
        val session = cast.session ?: return@LaunchedEffect
        val media = ready?.cast ?: return@LaunchedEffect
        if (cast.loadedEpisode == episode.id || media.separateAudio) return@LaunchedEffect
        val host = com.aniliphy.anime.Casting.localAddress(context)
        if (host == null) {
            context.showToast("Casting needs the phone on Wi-Fi")
            return@LaunchedEffect
        }
        val relay = withContext(Dispatchers.IO) {
            com.aniliphy.anime.Casting.startRelay(media, host) { url ->
                if (url.startsWith("file:")) java.io.File(Uri.parse(url).path!!).readText() else SourceHttp.fetcher.get(url, media.headers)
            }
        }
        val shown = subtitleShown()
        val request = com.aniliphy.anime.Casting.request(
            media, relay, request.title, episodeLabel(episode), request.cover, player.currentPosition, shown,
        ) { AnimeRules.languageOf(it.label, it.language) }
        player.pause()
        cast.finished = false
        cast.loadedEpisode = episode.id
        cast.activeSubtitle = shown?.takeIf { label -> media.subtitles.any { it.label == label } }
        session.remoteMediaClient?.load(request)
    }
    LaunchedEffect(cast.casting, playing) { if (cast.casting && playing) player.pause() }
    LaunchedEffect(cast.finished) {
        if (!cast.finished) return@LaunchedEffect
        // The TV reached the end: the phone's player jumps to the end too, so the episode counts and the next one comes.
        val end = player.duration.takeIf { it > 0 } ?: return@LaunchedEffect
        player.seekTo(end)
    }
    val castActivity = context as? android.app.Activity
    DisposableEffect(Unit) {
        onDispose {
            // Leaving the player ends casting (the relay lives with it), unless the screen is only turning.
            if (castActivity?.isChangingConfigurations != true && cast.casting) cast.stop()
        }
    }

    LaunchedEffect(settings.speed) { player.setPlaybackSpeed(settings.speed.factor) }
    val soundOnly = hidden && settings.backgroundAudio && !inPip
    LaunchedEffect(tracks, height, subtitle, settings.quality, settings.mobileQuality, settings.subtitleLanguage, soundOnly, audio, japaneseLines?.first, japanese.layout) {
        val quality = AnimeRules.quality(settings.quality, settings.mobileQuality, context.onMeteredNetwork())
        val builder = player.trackSelectionParameters.buildUpon()
            .clearVideoSizeConstraints()
            .setForceHighestSupportedBitrate(false)
            .setForceLowestBitrate(false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, soundOnly)
            // A stream with many spoken languages, as DASH ones can be, plays the one of the Sub or Dub picked.
            .setPreferredAudioLanguage(AnimeRules.audioLanguageOf(audio))
        val picked = height
        when {
            picked != null -> builder.setMaxVideoSize(Int.MAX_VALUE, picked).setMinVideoSize(0, picked)
            quality == VideoQuality.Best -> builder.setForceHighestSupportedBitrate(true)
            quality == VideoQuality.Worst -> builder.setForceLowestBitrate(true)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, AnimeRules.maxHeight(quality)!!)
        }
        // With the Japanese line drawn by the app, the player shows the other track (or none, for "JP only").
        val drawn = japaneseLines?.first
        val chosen = subtitle.takeIf { drawn == null || it != drawn }
        val language = AnimeRules.languageOf(settings.subtitleLanguage)
        when {
            drawn != null && japanese.layout == com.aniliphy.japanese.DualLayout.JapaneseOnly -> builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
        if (watched.id in downloaded) anime.watchedDownload(request.mediaId, watched.id)
        val number = AnimeRules.aniListEpisode(watched, request.totalEpisodes) ?: return
        if (com.aniliphy.core.Incognito.on) {
            context.showToast("Incognito: episode $number not marked anywhere")
            return
        }
        LookMoment.fire(episode = true, number = number, total = request.totalEpisodes)
        scope.launch {
            episodeThread(anime.aniList.api, request.mediaId, number, chapter = false)?.let { discussion = number to it }
        }
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
    fun saveSubs(value: com.aniliphy.anime.TitleSubs) {
        subsChoice = value
        scope.launch(Dispatchers.IO) { titleSubs.set(request.mediaId, value) }
    }
    /** Loads the episode again where it is, with what changed (a subtitle file, the delay). */
    fun reloadHere() {
        playAfterLoad = player.playWhenReady
        startAt = player.currentPosition
        subsVersion++
    }
    fun setSleep(choice: com.aniliphy.anime.SleepChoice) {
        sleep = choice
        player.volume = 1f
        sleepEnd = choice.minutes?.let { android.os.SystemClock.elapsedRealtime() + it * 60_000L }
        sleepLeft = null
    }
    val fromExternal = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (position, length) = com.aniliphy.anime.ExternalPlayer.result(result.data) ?: return@rememberLauncherForActivityResult
        val total = length?.takeIf { it > 0 } ?: duration
        savePlace(episode, position, total)
        if (total > 0 && AnimeRules.watched(position, total, settings.watchedPercent)) markWatched(episode)
        player.seekTo(position)
        context.showToast("Back at ${clock(position)}")
    }
    /** Hands the episode to another player: [choice], or Android's list when null. */
    fun openExternally(choice: com.aniliphy.anime.ExternalPlayer?) {
        if (ready?.local == true) {
            context.showToast("Downloaded episodes only play here")
            return
        }
        scope.launch {
            val stream = try {
                anime.resolve(request, episode, audio, skip = failedServers.toSet()).stream
            } catch (e: IOException) {
                context.showToast(e.message ?: "Couldn't get the episode from $siteLabel")
                return@launch
            }
            val subs = withContext(Dispatchers.IO) {
                shifted(context, stream.subtitles + titleSubs.tracks(request.mediaId, episode.id), stream.headers, subsChoice.delayMs)
            }.map { track ->
                // Files on this phone go through the share provider, so the other app may read them.
                if (track.url.startsWith("file:")) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".share", java.io.File(Uri.parse(track.url).path!!))
                    track.copy(url = uri.toString())
                } else {
                    track
                }
            }
            val at = player.currentPosition
            player.pause()
            savePlace(episode, at, duration)
            val name = "${request.title} · Episode ${episodeLabel(episode)}"
            if (choice == null) {
                val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(stream.url), com.aniliphy.anime.ExternalPlayer.mimeOf(stream.url))
                runCatching { context.startActivity(Intent.createChooser(view, "Open episode ${episodeLabel(episode)} in")) }
                    .onFailure { context.showToast("No app can play it") }
                return@launch
            }
            val pkg = choice.installed(context) ?: run {
                context.showToast("${choice.label} isn't installed")
                return@launch
            }
            val intent = choice.intent(pkg, stream.url, name, stream.headers, subs, at)
            subs.filter { it.url.startsWith("content:") }.forEach { track ->
                val uri = Uri.parse(track.url)
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { fromExternal.launch(intent) }.onFailure { context.showToast("${choice.label} couldn't open it") }
        }
    }
    fun openIn() {
        val fixed = com.aniliphy.anime.ExternalPlayer.entries.firstOrNull { it.name == settings.externalPlayer && it.installed(context) != null }
        if (fixed != null) openExternally(fixed) else sheet = PlayerSheet.OpenIn
    }
    val pickSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: "subtitles.srt"
            if (SUBTITLE_FILES.none { name.lowercase().endsWith(it) }) {
                context.showToast("Pick a .srt, .ass or .vtt file")
                return@launch
            }
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    titleSubs.add(request.mediaId, episode.id, name, bytes)
                }.getOrNull()
            }
            if (saved == null) {
                context.showToast("Couldn't read $name")
                return@launch
            }
            subtitle = saved.nameWithoutExtension.take(40)
            reloadHere()
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
        anime.sweepWatchedDownloads(playing = AnimeDownloads.key(request.mediaId, episodes[to].id))
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
    var framesTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(index, ready) {
        while (ready != null) {
            delay(SAVE_EVERY_MS)
            if (player.isPlaying) {
                savePlace(episode, player.currentPosition, duration)
                // Every 30 s, the frame for Up next and the widget (not when there's no picture, in the background).
                framesTick++
                if (settings.frameInUpNext && framesTick % 6 == 0 && !inPip) {
                    FrameGrab.copy(playerViewHolder[0], width = 960)?.let { FrameGrab.keepPlace(context, request.mediaId, it) }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.aniliphy.core.ActivityLog.watched(context, SAVE_EVERY_MS) }
            }
        }
    }
    val latest by rememberUpdatedState(Triple(episode, position, duration))
    DisposableEffect(Unit) {
        onDispose {
            val (last, at, length) = latest
            savePlace(last, at, length)
            anime.sweepWatchedDownloads()
        }
    }

    // Fillers for this title, when its filler list is known (from its series page, or matched when Watch was pressed).
    val fillers by produceState<TitleFillers?>(null, request.mediaId) {
        value = withContext(Dispatchers.IO) {
            try {
                Fillers.shared(context).known(request.mediaId, request.totalEpisodes)
            } catch (_: IOException) {
                null
            }
        }
    }
    val skipFillers = remember { FillerPrefs.skips(context, request.mediaId) }
    fun kindAt(i: Int): EpisodeKind? =
        episodes.getOrNull(i)?.let { AnimeRules.aniListEpisode(it, request.totalEpisodes) }?.let { fillers?.kindOf(it) }
    /** The first episode from [from] on that isn't a filler, when fillers are skipped for this title. */
    fun storyFrom(from: Int): Int {
        var i = from
        if (skipFillers && fillers != null) while (i <= episodes.lastIndex && kindAt(i)?.skippable == true) i++
        return i
    }
    fun numbers(from: Int, until: Int): String =
        Fillers.runs((from until until).mapNotNull { episodes.getOrNull(it)?.let { e -> AnimeRules.aniListEpisode(e, request.totalEpisodes) } })
            .joinToString { if (it.first == it.last) "${it.first}" else "${it.first}–${it.last}" }
    // Opening on a filler, with fillers skipped for this title: on to the story.
    var startChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(fillers) {
        if (fillers == null || startChecked) return@LaunchedEffect
        startChecked = true
        if (!skipFillers || startAt > 0 || kindAt(index)?.skippable != true) return@LaunchedEffect
        val to = storyFrom(index)
        if (to in episodes.indices) {
            context.showToast("Skipped fillers ${numbers(index, to)}")
            goEpisode(to)
        }
    }
    // The delay applies once it stops changing, so stepping through values doesn't reload each time.
    LaunchedEffect(delayDraft) {
        if (delayDraft == subsChoice.delayMs) return@LaunchedEffect
        delay(DELAY_SETTLE_MS)
        playAfterLoad = player.playWhenReady
        startAt = player.currentPosition
        saveSubs(subsChoice.copy(delayMs = delayDraft))
    }
    LaunchedEffect(sleepEnd) {
        val end = sleepEnd ?: return@LaunchedEffect
        while (true) {
            val left = end - android.os.SystemClock.elapsedRealtime()
            sleepLeft = left
            player.volume = com.aniliphy.anime.SleepTimer.volume(left)
            if (left <= 0) {
                player.pause()
                player.volume = 1f
                sleep = com.aniliphy.anime.SleepChoice.Off
                sleepEnd = null
                sleepLeft = null
                controls = true
                context.showToast("Sleep timer: paused")
                break
            }
            delay(250)
        }
    }
    // Where autoplay goes: the next episode, or past the fillers after it.
    var autoTarget by remember { mutableIntStateOf(-1) }

    LaunchedEffect(endedTimes) {
        if (endedTimes == 0) return@LaunchedEffect
        markWatched(episode)
        if (sleep == com.aniliphy.anime.SleepChoice.EndOfEpisode) {
            setSleep(com.aniliphy.anime.SleepChoice.Off)
            controls = true
            context.showToast("Sleep timer: stopped at the end of the episode")
            return@LaunchedEffect
        }
        val target = storyFrom(index + 1)
        if (!hasNext || target > episodes.lastIndex) {
            controls = true
            context.showToast(if (hasNext) "Only fillers left on $siteLabel" else "That was the last episode on $siteLabel")
            return@LaunchedEffect
        }
        autoTarget = target
        autoplay = scope.launch {
            for (left in settings.autoplayDelay downTo 1) {
                countdown = left
                delay(1_000)
            }
            countdown = null
            goEpisode(target)
        }
    }

    // When the site doesn't say where the opening and ending are, AniSkip may (by MAL id, episode and length).
    val skipTimes = remember { com.aniliphy.anime.SkipTimes.shared(context) }
    var fromAniSkip by remember { mutableStateOf<Pair<String, com.aniliphy.anime.EpisodeSkips>?>(null) }
    LaunchedEffect(ready?.episodeId, duration > 0, settings.aniSkip) {
        val shown = ready ?: return@LaunchedEffect
        if (!settings.aniSkip || duration <= 0 || (shown.intro != null && shown.outro != null)) return@LaunchedEffect
        val mal = request.mal ?: return@LaunchedEffect
        val number = AnimeRules.aniListEpisode(episode, request.totalEpisodes) ?: return@LaunchedEffect
        val id = episode.id
        val length = duration / 1000.0
        val found = withContext(Dispatchers.IO) { runCatching { skipTimes.find(mal, number, length) }.getOrNull() } ?: return@LaunchedEffect
        fromAniSkip = id to found
    }
    val extraSkips = fromAniSkip?.takeIf { it.first == episode.id }?.second
    val segments = listOfNotNull(
        (ready?.intro ?: extraSkips?.intro)?.let { "Skip intro" to it },
        extraSkips?.recap?.let { "Skip recap" to it },
        (ready?.outro ?: extraSkips?.outro)?.let { "Skip outro" to it },
    )
    pausedNotice[0] = {
        val intro = (ready?.intro ?: extraSkips?.intro)?.let { it.start..it.end }
        val outro = (ready?.outro ?: extraSkips?.outro)?.let { it.start..it.end }
        com.aniliphy.work.LiveNotifications.paused(
            context, request.mediaId, request.title, "Episode ${episodeLabel(episode)}",
            player.currentPosition / 1000.0, player.duration / 1000.0, intro, outro,
        )
    }
    val inSegment = segments.firstOrNull { (_, segment) -> position / 1000.0 >= segment.start && position / 1000.0 < segment.end - 1 }
    LaunchedEffect(inSegment, settings.skipSegments) {
        val (label, segment) = inSegment ?: return@LaunchedEffect
        val key = "${episode.id}/$label"
        if (settings.skipSegments == SkipSegments.Automatic && skipped[key] != true) {
            skipped[key] = true
            player.seekTo((segment.end * 1000).toLong())
            context.showToast(
                when (label) {
                    "Skip intro" -> "Skipped the intro"
                    "Skip recap" -> "Skipped the recap"
                    else -> "Skipped the outro"
                },
            )
        }
    }

    // Any key from a remote or keyboard keeps the controls up while moving between them.
    var keyTick by remember { mutableIntStateOf(0) }
    val keyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Controls brought up by the remote start with Play in focus, so the arrows have somewhere to go from.
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var focusPlay by remember { mutableStateOf(false) }
    LaunchedEffect(controls, sheet, focusPlay) {
        if (!controls && sheet == null) runCatching { keyFocus.requestFocus() }
        if (controls && focusPlay) {
            delay(60)
            runCatching { playFocus.requestFocus() }
            focusPlay = false
        }
    }
    LaunchedEffect(controls, playing, sheet, swipe, keyTick) {
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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                keyTick++
                when (event.key) {
                    Key.MediaPlayPause -> { if (player.isPlaying) player.pause() else player.play(); true }
                    Key.MediaPlay -> { player.play(); true }
                    Key.MediaPause -> { player.pause(); true }
                    Key.MediaFastForward -> { seekBy(10_000); flash = "+10 s"; true }
                    Key.MediaRewind -> { seekBy(-10_000); flash = "-10 s"; true }
                    Key.MediaNext -> { nextEpisode(); true }
                    Key.MediaPrevious -> { previousEpisode(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).also { playerViewHolder[0] = it }.apply {
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
                    val size = subsChoice.size ?: settings.subtitleSize
                    setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, size.sp.toFloat() * if (inPip) 0.5f else 1f)
                    val box = (subsChoice.background ?: settings.subtitleBackground) == SubtitleBackground.Box
                    setStyle(
                        CaptionStyleCompat(
                            (subsChoice.color ?: settings.subtitleColor).rgb.toInt(),
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
                // With the controls hidden, the remote talks to the picture: OK plays or pauses and shows them,
                // left and right skip 10 seconds, up and down bring the controls back.
                .focusRequester(keyFocus)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || controls || sheet != null) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
                            if (player.isPlaying) player.pause() else player.play()
                            controls = true
                            focusPlay = true
                            true
                        }
                        Key.DirectionLeft -> { seekBy(-10_000); flash = "-10 s"; true }
                        Key.DirectionRight -> { seekBy(10_000); flash = "+10 s"; true }
                        Key.DirectionUp, Key.DirectionDown -> { controls = true; focusPlay = true; true }
                        else -> false
                    }
                }
                .focusable()
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
            EpisodeVideo.Loading -> LookLoading("Loading episode ${episodeLabel(episode)}...", Modifier.align(Alignment.Center), color = Color.White)
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

        if (ready != null && playError == null) japaneseLines?.let { (_, cues) ->
            JapaneseOverlay(
                player = player,
                cues = cues,
                delayMs = subsChoice.delayMs,
                sizeSp = (subsChoice.size ?: settings.subtitleSize).sp,
                layout = japanese.layout,
                controlsShown = controls,
                view = { playerViewHolder[0] },
                mediaId = request.mediaId,
                title = request.title,
                episode = episodeLabel(episode),
            )
        }

        swipe?.let { SwipeLabel(it, Modifier.align(Alignment.Center)) }
        flash?.let { OverlayText(it, Modifier.align(Alignment.Center)) }

        SizedControls(settings.controlsSize) {
        if (settings.skipSegments == SkipSegments.Button && inSegment != null && sheet == null) {
            PillButton(
                if (inSegment.first == "Skip recap") LookWords.skipRecap(wordStyle) else LookWords.skip(wordStyle, intro = inSegment.first == "Skip intro"),
                onClick = { player.seekTo((inSegment.second.end * 1000).toLong()) },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 24.dp, bottom = if (controls) 110.dp else 32.dp),
            )
        }

        if (frameFlash) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.55f)))
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
                val target = autoTarget.takeIf { it in episodes.indices } ?: (index + 1)
                val next = episodes.getOrNull(target)
                if (target > index + 1) {
                    val count = target - index - 1
                    BasicText(
                        "Skipping ${numbers(index + 1, target)} · $count filler${if (count == 1) "" else "s"}",
                        style = TextStyle(color = theme.error, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    )
                }
                BasicText(
                    "Episode ${next?.let(::episodeLabel) ?: ""} in $left s",
                    style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
                discussion?.let { (n, thread) -> DiscussPill(n, thread, chapter = false, onOpen = onClose) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Play now", onClick = { goEpisode(target) })
                    if (target > index + 1) {
                        PillButton("Watch ${episodes.getOrNull(index + 1)?.let(::episodeLabel) ?: ""}", onClick = ::nextEpisode, filled = false)
                    }
                    PillButton("Cancel", onClick = {
                        autoplay?.cancel()
                        countdown = null
                        controls = true
                    }, filled = false)
                }
            }
        }

        if (sendToPc && handoff != null) {
            SendToPcDialog(
                handoff, "anime", request.mediaId.toString(), request.title,
                save = {
                    val duration = player.duration.takeIf { it > 0 } ?: 0L
                    if (duration > 0) anime.history.savePlace(
                        request.mediaId, episode, player.currentPosition, duration, request.title, request.cover,
                        total = request.totalEpisodes, titles = request.titles, mal = request.mal,
                    )
                },
                pause = { player.pause() },
                onDone = { sendToPc = false },
            )
        }
        if (cast.casting && sheet == null) {
            val labels = ready?.cast?.subtitles?.map { it.label }.orEmpty()
            CastPanel(
                cast, request.title, episodeLabel(episode), labels,
                onSubtitle = { i -> cast.showSubtitle(i?.let { it + 1L }, i?.let { labels[it] }) },
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            )
        }
        if (controls && sheet == null) {
            PlayerTopBar(
                title = request.title,
                subtitle = episodeTitle(episode, spoilerSeenUpTo(request.mediaId, episode)),
                onClose = onClose,
                onEpisodes = { sheet = PlayerSheet.Episodes },
                onDownload = { sheet = PlayerSheet.Download },
                onSource = { sheet = PlayerSheet.Source },
                sleepLabel = when {
                    sleep == com.aniliphy.anime.SleepChoice.EndOfEpisode -> "☾ end"
                    sleepLeft != null -> "☾ " + com.aniliphy.anime.SleepTimer.label(sleepLeft!!)
                    else -> null
                },
                onSleep = { sheet = PlayerSheet.Options },
                onOpenIn = { openIn() },
                onSendToPc = if (handoff?.usable == true) ({ sendToPc = true }) else null,
                onCast = if (cast.available && LocalWindowLayout.current != WindowLayout.Tv) ({
                    when {
                        ready?.cast == null -> context.showToast("Downloaded episodes can't be cast yet; stream it to cast it")
                        ready.cast.separateAudio -> context.showToast("This server keeps the sound apart from the picture, which a Chromecast can't join. Try another server in Source")
                        else -> sheet = PlayerSheet.Cast
                    }
                }) else null,
                onFrame = {
                    scope.launch {
                        val at = player.currentPosition
                        // Paused, so the moment isn't lost while choosing what to do with it.
                        player.pause()
                        val grabbed = FrameGrab.copy(playerViewHolder[0], subtitles = subtitle != null)
                        if (grabbed == null) {
                            context.showToast("Couldn't take the frame")
                        } else {
                            frameFlash = true
                            frame = grabbed to at
                            sheet = PlayerSheet.Frame
                            delay(180)
                            frameFlash = false
                        }
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
            if (ready != null && playError == null) Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center),
            ) {
                PillButton("Previous", onClick = ::previousEpisode, filled = false, enabled = index > 0)
                PillButton(if (playing) "Pause" else "Play", onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.focusRequester(playFocus))
                PillButton("Next", onClick = ::nextEpisode, filled = false, enabled = hasNext)
            }
            discussion?.takeIf { countdown == null }?.let { (n, thread) ->
                DiscussPill(n, thread, chapter = false, onOpen = onClose, modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 72.dp, end = 16.dp))
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
                    kindAt = if (fillers != null) ::kindAt else null,
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
                val drawnLabel = japaneseLines?.first
                val shownSubtitle = subtitle?.takeIf { it != drawnLabel } ?: tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.label ?: ""
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SegmentedRow(
                        "Sleep timer", com.aniliphy.anime.SleepChoice.entries, sleep, { it.label }, { setSleep(it) },
                        description = "Pauses (and stops background sound) when it runs out. The last 10 seconds fade the sound down",
                    )
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
                    SegmentedRow(
                        "Sharpen", com.aniliphy.anime.SharpenLevel.entries, sharpenChosen, { it.label }, { picked ->
                            sharpenCap = null
                            if (titleSharpen != null) {
                                titleSharpen = picked
                                sharpenTitles.set(request.mediaId, picked)
                            } else {
                                settings.sharpen = picked
                            }
                        },
                        description = if (sharpen != sharpenChosen) "Turned down to ${sharpen.label} for now: the phone was dropping frames"
                        else "Light: crisper edges. Strong: crisper edges and darker, thinner lines. Best on 720p and lower",
                    )
                    SwitchRow("Sharpen: only for this title", titleSharpen != null, { on ->
                        titleSharpen = if (on) sharpenChosen else null
                        sharpenTitles.set(request.mediaId, titleSharpen)
                    })
                    OptionLabel("Subtitles")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        if (labels.isNotEmpty()) PillButton("Off", onClick = { subtitle = "" }, filled = shownSubtitle == "")
                        labels.forEach { label ->
                            if (label == drawnLabel) PillButton("$label · on top", onClick = {}, filled = true, color = theme.warning)
                            else PillButton(label, onClick = { subtitle = label }, filled = shownSubtitle == label)
                        }
                        PillButton("From the phone…", onClick = { pickSubtitles.launch(arrayOf("*/*")) }, filled = false)
                        if (com.aniliphy.anime.JimakuKey.get(context).key.isNotEmpty()) {
                            PillButton("Japanese (Jimaku)", onClick = { sheet = PlayerSheet.Jimaku }, filled = false)
                        }
                    }
                    Hint(".srt, .ass or .vtt. A file you load stays with this episode", Modifier.padding(horizontal = 20.dp, vertical = 2.dp), color = Color.White.copy(alpha = 0.6f))
                    OptionLabel("Delay · " + (if (delayDraft > 0) "+" else "") + "%.1f s".format(java.util.Locale.ROOT, delayDraft / 1000f))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    ) {
                        listOf(-1000L, -100L, 100L, 1000L).forEach { step ->
                            PillButton((if (step > 0) "+" else "−") + "%.1f".format(java.util.Locale.ROOT, kotlin.math.abs(step) / 1000f), onClick = {
                                delayDraft = (delayDraft + step).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
                            }, filled = false)
                        }
                        if (delayDraft != 0L) PillButton("Reset", onClick = { delayDraft = 0 }, filled = false)
                    }
                    Hint("Later with +, earlier with −. Kept for this title", Modifier.padding(horizontal = 20.dp, vertical = 2.dp), color = Color.White.copy(alpha = 0.6f))
                    SwitchRow(
                        "Style for this title only", subsChoice.ownStyle,
                        {
                            saveSubs(
                                if (it) subsChoice.copy(size = settings.subtitleSize, color = settings.subtitleColor, background = settings.subtitleBackground)
                                else subsChoice.copy(size = null, color = null, background = null),
                            )
                        },
                        description = "Size, colour and background from Anime settings, changed just for this title",
                    )
                    if (subsChoice.ownStyle) {
                        SegmentedRow("Size", SubtitleSize.entries, subsChoice.size ?: settings.subtitleSize, { it.label }, { saveSubs(subsChoice.copy(size = it)) })
                        SegmentedRow("Color", SubtitleColor.entries, subsChoice.color ?: settings.subtitleColor, { it.label }, { saveSubs(subsChoice.copy(color = it)) })
                        SegmentedRow("Background", SubtitleBackground.entries, subsChoice.background ?: settings.subtitleBackground, { it.label }, { saveSubs(subsChoice.copy(background = it)) })
                    }
                    SegmentedRow("Video", VideoFit.entries, settings.fit, { it.label }, { settings.fit = it })
                    SegmentedRow("Skip intro and outro", SkipSegments.entries, settings.skipSegments, { it.label }, { settings.skipSegments = it })
                    SwitchRow("Skip times from AniSkip", settings.aniSkip, { settings.aniSkip = it }, description = "When the site doesn't say where the opening is")
                    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
                    if (anime.loggedIn) {
                        SegmentedRow("Update AniList", AniListSync.entries, settings.aniListSync, { it.label }, { settings.aniListSync = it })
                    }
                }
            }
            PlayerSheet.OpenIn -> ReaderSheetFrame("Open episode ${episodeLabel(episode)} in", { sheet = null }, sheetModifier) {
                var remember by remember { mutableStateOf(false) }
                val installed = com.aniliphy.anime.ExternalPlayer.entries.mapNotNull { p -> p.installed(context)?.let { p } }
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    if (installed.isEmpty()) {
                        Hint("None of mpv, VLC, Just Player or MX Player is installed. Android's list below may still have one.", Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = Color.White.copy(alpha = 0.7f))
                    }
                    installed.forEach { p ->
                        ActionRow(label = p.label, description = p.detail, onClick = {
                            sheet = null
                            if (remember) settings.externalPlayer = p.name
                            openExternally(p)
                        })
                    }
                    ActionRow(label = "Other app…", description = "Android's list; only the video address is sent", onClick = {
                        sheet = null
                        openExternally(null)
                    })
                    if (installed.isNotEmpty()) {
                        SwitchRow("Always use the one I pick", remember, { remember = it }, description = "Skips this list (change it in Anime settings › Playback)")
                    }
                }
            }
            PlayerSheet.Cast -> ReaderSheetFrame("Cast to", { sheet = null }, sheetModifier) {
                CastDevices(context, cast, onPicked = { sheet = null })
            }
            PlayerSheet.Jimaku -> ReaderSheetFrame("Japanese subtitles · episode ${episodeLabel(episode)}", { sheet = null }, sheetModifier) {
                val number = AnimeRules.aniListEpisode(episode, request.totalEpisodes)
                val found by produceState<Result<List<com.aniliphy.anime.Jimaku.SubFile>>?>(null, episode.id) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { com.aniliphy.anime.Jimaku(com.aniliphy.core.Web, com.aniliphy.anime.JimakuKey.get(context).key).files(request.mediaId, number) }
                    }
                }
                var getting by remember { mutableStateOf<String?>(null) }
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    val result = found
                    when {
                        result == null -> Hint("Looking on Jimaku…", Modifier.padding(20.dp), color = Color.White.copy(alpha = 0.7f))
                        result.isFailure -> Hint(result.exceptionOrNull()?.message ?: "Jimaku couldn't be reached", Modifier.padding(20.dp), color = Color.White.copy(alpha = 0.7f))
                        result.getOrThrow().isEmpty() -> Hint("Jimaku has no subtitle files for this episode", Modifier.padding(20.dp), color = Color.White.copy(alpha = 0.7f))
                        else -> result.getOrThrow().forEach { file ->
                            ActionRow(
                                label = file.name,
                                description = if (getting == file.name) "Downloading…" else "jimaku · " + android.text.format.Formatter.formatShortFileSize(context, file.size),
                                enabled = getting == null,
                                onClick = {
                                    getting = file.name
                                    scope.launch {
                                        val saved = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val bytes = com.aniliphy.anime.Jimaku(com.aniliphy.core.Web, com.aniliphy.anime.JimakuKey.get(context).key).download(file)
                                                titleSubs.add(request.mediaId, episode.id, file.name, bytes)
                                            }
                                        }
                                        getting = null
                                        saved.onSuccess {
                                            sheet = null
                                            subtitle = it.nameWithoutExtension.take(40)
                                            reloadHere()
                                        }.onFailure { context.showToast(it.message ?: "Couldn't download ${file.name}") }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            PlayerSheet.Frame -> frame?.let { (picture, at) ->
                ReaderSheetFrame("Frame · ${clock(at)}", { sheet = null }, sheetModifier) {
                    val label = "Episode ${episodeLabel(episode)} · ${clock(at)}"
                    val ownName = episode.name.substringAfter(": ", episode.name).trim()
                        .takeIf { it.isNotBlank() && !Regex("(?i)episode \\d+").matches(it) && spoilerSeenUpTo(request.mediaId, episode) == null }
                    androidx.compose.foundation.Image(
                        picture.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(max = 220.dp).clip(lookShape(8.dp)),
                    )
                    BasicText(
                        "${request.title} · $label",
                        style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
                        PillButton("Share card", onClick = {
                            scope.launch {
                                ShareImage.share(
                                    context, theme,
                                    ShareCard(
                                        kicker = "EP ${episodeLabel(episode)} · ${clock(at)}",
                                        title = request.title,
                                        // The episode's own name, when the site gives one beyond its number (and spoilers allow it).
                                        lines = listOfNotNull(ownName),
                                        frame = picture,
                                    ),
                                    "${request.title} · $label",
                                )
                            }
                        })
                        PillButton("Share frame only", onClick = { FrameGrab.share(context, picture, "${request.title} · $label") }, filled = false)
                        PillButton("Save to gallery", onClick = {
                            scope.launch {
                                val name = "${request.title.take(40)}-ep${episodeLabel(episode)}-${clock(at).replace(':', '.')}".replace(Regex("[^A-Za-z0-9._ -]"), "")
                                val saved = FrameGrab.saveToGallery(context, picture, name)
                                context.showToast(if (saved) "Saved to Pictures/aniliphy" else "Saving needs Android 10 or newer. Share it instead")
                            }
                        }, filled = false)
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
    /** Subtitle files loaded for this episode (from the phone or Jimaku). */
    extra: List<SubtitleTrack> = emptyList(),
    /** The title's subtitle delay. */
    delayMs: Long = 0,
): EpisodeVideo {
    val about = MediaMetadata.Builder()
        .setTitle("Episode ${episodeLabel(episode)}")
        .setArtist(request.title)
        .setArtworkUri(request.cover?.let(Uri::parse))
        .build()
    val saved = withContext(Dispatchers.IO) { anime.offline.saved(request.mediaId, episode) }
    if (saved != null) {
        val subs = withContext(Dispatchers.IO) { shifted(context, saved.subtitles + extra, emptyMap(), delayMs) }
        val item = anime.offline.mediaItem(saved, subtitleConfigs(subs, anime.settings.subtitleLanguage))
            .buildUpon().setMediaMetadata(about).build()
        val factory = DefaultMediaSourceFactory(anime.offline.dataSourceFactory(emptyMap()))
        val source = withAudio(factory, factory.createMediaSource(item), saved.audioUrl)
        return EpisodeVideo.Ready(episode.id, source, saved.intro, saved.outro, local = true, note = null)
    }
    if (!dataAllowed && context.onMeteredNetwork()) return EpisodeVideo.NeedsData
    return try {
        val resolved = anime.resolve(request, episode, audio, fresh, skip)
        val stream = resolved.stream
        val http = OkHttpDataSource.Factory(SourceHttp.client).setUserAgent(SourceHttp.USER_AGENT).setDefaultRequestProperties(stream.headers)
        val subtitleTracks = withContext(Dispatchers.IO) { shifted(context, stream.subtitles + extra, stream.headers, delayMs) }
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMimeType(videoMimeOf(stream.url))
            .setSubtitleConfigurations(subtitleConfigs(subtitleTracks, anime.settings.subtitleLanguage))
            .setMediaMetadata(about)
            .build()
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(STREAM_RETRIES))
        // Some sources serve the sound apart from the picture; the one in the language picked plays with it.
        val source = withAudio(factory, factory.createMediaSource(item), ExtraAudio.pick(stream.url, stream.audio)?.url)
        val separateAudio = ExtraAudio.pick(stream.url, stream.audio) != null
        val cast = com.aniliphy.anime.CastMedia(stream.url, stream.headers, subtitleTracks, separateAudio)
        EpisodeVideo.Ready(episode.id, source, stream.intro, stream.outro, local = false, note = resolved.note, server = resolved.server, cast = cast)
    } catch (e: IOException) {
        EpisodeVideo.Failed(e.message ?: "Episode ${episodeLabel(episode)} couldn't be played")
    }
}

/** The subtitles moved by the title's delay (read from the site or the phone). */
private fun shifted(context: Context, tracks: List<SubtitleTrack>, headers: Map<String, String>, delayMs: Long): List<SubtitleTrack> =
    com.aniliphy.anime.ShiftedSubtitles.prepare(context.cacheDir, tracks, delayMs) { url ->
        if (url.startsWith("file:")) java.io.File(Uri.parse(url).path!!).readText() else SourceHttp.fetcher.get(url, headers)
    }

private fun subtitleMimeOf(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        path.endsWith(".ass") || path.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        path.endsWith(".ttml") || path.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.TEXT_VTT
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
            .setMimeType(subtitleMimeOf(track.url))
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
    LookLabel(text, modifier, large = true)
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
    onFrame: () -> Unit,
    modifier: Modifier,
    sleepLabel: String? = null,
    onSleep: () -> Unit = {},
    onOpenIn: () -> Unit = {},
    onSendToPc: (() -> Unit)? = null,
    onCast: (() -> Unit)? = null,
) {
    val ink = readerBarInk(video = true)
    ReaderBar(top = true, insets = Modifier.safeDrawingPadding(), modifier = modifier, video = true) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val heading = @Composable { rowModifier: Modifier ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
                PillButton("Back", onClick = onClose, filled = false)
                Spacer(Modifier.width(10.dp))
                ReaderHeading(title, subtitle, ink, Modifier.weight(1f))
            }
        }
        val buttons = @Composable {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Episodes", onClick = onEpisodes, filled = false)
                PillButton("Download", onClick = onDownload, filled = false)
                PillButton("Source", onClick = onSource, filled = false)
                PillButton("Open in…", onClick = onOpenIn, filled = false)
                onSendToPc?.let { PillButton("⇄ PC", onClick = it, filled = false) }
                onCast?.let { PillButton("⎚ Cast", onClick = it, filled = false) }
                PillButton("📷", onClick = onFrame, filled = false)
                sleepLabel?.let { PillButton(it, onClick = onSleep, filled = false, color = LocalAppTheme.current.warning) }
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
}

@Composable
private fun PlayerBottomBar(position: Long, duration: Long, buffered: Long, onSeek: (Long) -> Unit, onOptions: () -> Unit, modifier: Modifier) {
    val ink = readerBarInk(video = true)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val length = duration.coerceAtLeast(1)
    val shown = if (dragging) dragValue else position.toFloat() / length
    ReaderBar(top = false, insets = Modifier.safeDrawingPadding(), modifier = modifier, video = true) {
        LookSlider(
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
            ink = ink,
            inactive = Color.White.copy(alpha = if (buffered > position) 0.45f else 0.3f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            ReaderBarText(
                "${clock(if (dragging) (dragValue * length).toLong() else position)} / ${clock(duration)}",
                ink,
                Modifier.weight(1f),
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

/**
 * With spoiler-free on for [mediaId], the furthest episode you've reached (AniList progress or the one playing), past
 * which names are hidden; null when it's off.
 */
@Composable
internal fun spoilerSeenUpTo(mediaId: Int, playing: SourceEpisode?): Double? {
    val context = LocalContext.current
    if (!SpoilerPrefs.on(context, mediaId)) return null
    val progress = LocalAnime.current.aniList.entryFor(mediaId)?.progress ?: 0
    return maxOf(progress.toDouble(), playing?.number ?: 0.0)
}

private fun episodeTitle(episode: SourceEpisode, seenUpTo: Double? = null): String {
    val label = "Episode ${episodeLabel(episode)}"
    if (seenUpTo != null && episode.number > seenUpTo) return label
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
    /** What each episode is (story, filler...), when the title's filler list is known. */
    kindAt: ((Int) -> EpisodeKind?)?,
    onGo: (Int) -> Unit,
    onWatchedUpTo: (Int) -> Unit,
) {
    val theme = LocalAppTheme.current
    val seen = spoilerSeenUpTo(mediaId, episodes.getOrNull(current))
    var filter by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<Int?>(null) }
    var hideFillers by rememberSaveable { mutableStateOf(false) }
    val shown = remember(filter, episodes, seen, hideFillers, kindAt != null) {
        episodes.indices.filter { filter.isBlank() || episodeTitle(episodes[it], seen).contains(filter.trim(), ignoreCase = true) }
            .filter { !hideFillers || kindAt?.invoke(it)?.skippable != true || it == current }
    }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Column {
        SearchField(filter, { filter = it }, "Find an episode", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
        if (kindAt != null) {
            val count = episodes.indices.count { kindAt(it)?.skippable == true }
            if (count > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    FilterChip("Hide fillers $count", hideFillers) { hideFillers = !hideFillers }
                }
            }
        }
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
                        episodeTitle(episodes[index], seen),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (index == current) theme.primary else theme.foreground,
                            fontSize = 14.sp,
                            fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    kindAt?.invoke(index)?.takeIf { it == EpisodeKind.Filler || it == EpisodeKind.Mixed }?.let { kind ->
                        val color = kindColor(kind, theme)
                        BasicText(
                            if (kind == EpisodeKind.Filler) "FILLER" else "MIXED",
                            style = NumberStyle(color = color, fontSize = 9.5.sp, bold = true),
                            modifier = Modifier.padding(start = 8.dp).border(1.dp, color, lookShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    downloadNote(mediaId, episodes[index], downloaded)?.let {
                        BasicText(it, style = TextStyle(color = theme.secondary, fontSize = 12.sp), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        Hint("Hold an episode to mark it and the ones before as watched", Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    asking?.let { index ->
        ConfirmDialog(
            title = "Watched up to here?",
            message = "Marks ${episodeTitle(episodes[index], seen)} and everything before it as watched on AniList, and opens the next episode.",
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
internal fun EpisodeDownloads(
    episodes: List<SourceEpisode>,
    current: Int,
    downloaded: Set<String>,
    mediaId: Int,
    onDownload: (List<SourceEpisode>) -> Unit,
    onDelete: (SourceEpisode) -> Unit,
) {
    val theme = LocalAppTheme.current
    val seen = spoilerSeenUpTo(mediaId, episodes.getOrNull(current))
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
                    BasicText(episodeTitle(episode, seen), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.weight(1f))
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
private const val DELAY_SETTLE_MS = 900L
private const val MAX_DELAY_MS = 30_000L
private val SUBTITLE_FILES = listOf(".srt", ".ass", ".ssa", ".vtt")
private const val SAVE_EVERY_MS = 5_000L
private const val HIDE_CONTROLS_MS = 3_500L
private const val SWIPE_MS_PER_WIDTH = 120_000f
