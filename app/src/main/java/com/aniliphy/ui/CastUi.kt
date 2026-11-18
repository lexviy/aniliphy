package com.aniliphy.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouter
import com.aniliphy.anime.Casting
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.delay

/** The cast session as the player sees it: who it's on and what the TV is doing. */
class CastControl(context: Context) {
    val castContext = Casting.context(context)
    var session by mutableStateOf<CastSession?>(castContext?.sessionManager?.currentCastSession?.takeIf { it.isConnected })
        internal set
    var connecting by mutableStateOf(false)
        internal set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    var playing by mutableStateOf(false)
        internal set
    /** Set once the TV reports the episode finished. */
    var finished by mutableStateOf(false)
        internal set
    /** The episode loaded on the TV (so the next one gets loaded when it changes). */
    var loadedEpisode by mutableStateOf<String?>(null)
    var activeSubtitle by mutableStateOf<String?>(null)

    val available: Boolean get() = castContext != null
    val casting: Boolean get() = session != null
    val device: String get() = session?.castDevice?.friendlyName ?: "the TV"

    private val client get() = session?.remoteMediaClient

    fun togglePlay() {
        client?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun seekTo(ms: Long) {
        val to = ms.coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
        client?.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(to).build())
        positionMs = to
    }

    fun showSubtitle(trackId: Long?, label: String?) {
        client?.setActiveMediaTracks(if (trackId == null) LongArray(0) else longArrayOf(trackId))
        activeSubtitle = label
    }

    fun stop() {
        castContext?.sessionManager?.endCurrentSession(true)
    }

    internal fun poll() {
        val c = client ?: return
        positionMs = c.approximateStreamPosition
        durationMs = c.streamDuration.takeIf { it > 0 } ?: durationMs
        playing = c.isPlaying || c.isBuffering
        val status = c.mediaStatus
        if (status?.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_FINISHED && loadedEpisode != null) finished = true
    }
}

@Composable
internal fun rememberCastControl(context: Context, onEnded: (positionMs: Long) -> Unit): CastControl {
    val control = remember { CastControl(context) }
    val manager = control.castContext?.sessionManager
    DisposableEffect(manager) {
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) { control.connecting = true }
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                control.connecting = false
                control.finished = false
                control.loadedEpisode = null
                control.session = session
            }
            override fun onSessionStartFailed(session: CastSession, error: Int) { control.connecting = false }
            override fun onSessionEnding(session: CastSession) {
                control.poll()
            }
            override fun onSessionEnded(session: CastSession, error: Int) {
                val at = control.positionMs
                val had = control.loadedEpisode != null
                control.session = null
                control.connecting = false
                control.loadedEpisode = null
                Casting.stopRelay()
                if (had) onEnded(at)
            }
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) { control.session = session }
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }
        manager?.addSessionManagerListener(listener, CastSession::class.java)
        onDispose { manager?.removeSessionManagerListener(listener, CastSession::class.java) }
    }
    LaunchedEffect(control.session) {
        while (control.session != null) {
            control.poll()
            delay(500)
        }
    }
    return control
}

/** The TVs and speakers with video on this Wi-Fi; picking one starts the session (the player then loads the episode). */
@Composable
internal fun CastDevices(context: Context, control: CastControl, onPicked: () -> Unit) {
    val theme = LocalAppTheme.current
    val router = remember { MediaRouter.getInstance(context) }
    var routes by remember { mutableStateOf(listOf<MediaRouter.RouteInfo>()) }
    var searchedLong by remember { mutableStateOf(false) }
    DisposableEffect(router) {
        fun refresh() {
            routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(Casting.selector) }
        }
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(Casting.selector, callback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        onDispose { router.removeCallback(callback) }
    }
    LaunchedEffect(Unit) {
        delay(8_000)
        searchedLong = true
    }
    val body = TextStyle(color = theme.foreground, fontSize = 16.sp)
    val small = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 13.sp)
    Column(Modifier.padding(vertical = 4.dp)) {
        if (!control.available) {
            BasicText("Casting needs Google Play services, which this phone doesn't have.", style = small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            return@Column
        }
        if (routes.isEmpty()) {
            BasicText(
                if (searchedLong) "Nothing found. The phone and the TV need to be on the same Wi-Fi, and the TV switched on." else "Looking for Chromecasts and Google TVs on this Wi-Fi…",
                style = small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        routes.forEach { route ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    router.selectRoute(route)
                    onPicked()
                }.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                BasicText(route.name, style = body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                route.description?.takeIf { it.isNotBlank() }?.let { BasicText(it, style = small, maxLines = 1) }
            }
        }
        BasicText(
            "The phone passes the video on itself (the sites need their own headers), so keep the app open on the same Wi-Fi. Subtitles go as WebVTT.",
            style = small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

/** What's on the TV, with its controls; drawn over the paused phone player. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CastPanel(
    control: CastControl,
    title: String,
    episode: String,
    subtitles: List<String>,
    onSubtitle: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalAppTheme.current
    var pickSubs by remember { mutableStateOf(false) }
    Column(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .background(theme.surface.copy(alpha = 0.96f), lookShape(10.dp))
            .border(1.5.dp, theme.primary.copy(alpha = 0.6f), lookShape(10.dp))
            .padding(14.dp),
    ) {
        val heading = if (control.loadedEpisode == null) "Connecting to ${control.device}…" else "Casting to ${control.device}"
        BasicText(heading, style = TextStyle(color = theme.foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold))
        BasicText(
            "$title · Episode $episode · subtitles ${control.activeSubtitle ?: "off"}",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = JetBrainsMono),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        val duration = control.durationMs
        val fraction = if (duration > 0) (control.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).height(18.dp)
                .pointerInput(duration) {
                    detectTapGestures(onTap = { at ->
                        if (duration > 0) control.seekTo((at.x / size.width * duration).toLong())
                    })
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(theme.foreground.copy(alpha = 0.15f)))
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(theme.secondary))
        }
        BasicText(
            formatClock(control.positionMs) + " / " + formatClock(duration),
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = JetBrainsMono),
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("⏪ 10", onClick = { control.seekBy(-10_000) }, filled = false)
            PillButton(if (control.playing) "❚❚" else "▶", onClick = { control.togglePlay() }, filled = true)
            PillButton("10 ⏩", onClick = { control.seekBy(10_000) }, filled = false)
            if (subtitles.isNotEmpty()) PillButton("Subtitles", onClick = { pickSubs = !pickSubs }, filled = pickSubs)
            PillButton("Stop casting", onClick = { control.stop() }, filled = false)
        }
        if (pickSubs) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                PillButton("Off", onClick = { onSubtitle(null); pickSubs = false }, filled = control.activeSubtitle == null)
                subtitles.forEachIndexed { i, label ->
                    PillButton(label, onClick = { onSubtitle(i); pickSubs = false }, filled = control.activeSubtitle == label)
                }
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
