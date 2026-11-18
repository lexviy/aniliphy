package com.aniliphy.anime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.NotificationOptions
import com.google.android.gms.common.images.WebImage
import com.aniliphy.manga.sources.SourceHttp
import com.aniliphy.source.SubtitleTrack
import java.net.Inet4Address

/** Read by the Cast framework (named in the manifest): the plain Google receiver, which plays HLS, MP4 and WebVTT. */
class CastSetup : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setStopReceiverApplicationWhenEndingSession(true)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(NotificationOptions.Builder().setTargetActivityClassName("com.aniliphy.MainActivity").build())
                .build(),
        )
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** What the player knows about a streamed episode, enough to hand it to a TV. */
data class CastMedia(
    val url: String,
    val headers: Map<String, String>,
    /** Already moved by the title's delay. */
    val subtitles: List<SubtitleTrack>,
    /** The sound comes apart from the picture: a cast receiver can't join them. */
    val separateAudio: Boolean,
)

object Casting {
    val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder().addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)).build()
    }

    /** Null when Google Play services can't cast here. Main thread only. */
    fun context(context: Context): CastContext? = runCatching {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(context.applicationContext)
    }.getOrNull()

    /** The phone's address on the Wi-Fi (or Ethernet), where the TV can reach it. */
    fun localAddress(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val caps = connectivity.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    /** The relay for the episode on the TV; one at a time, kept while casting even if the player closes. */
    @Volatile var relay: CastRelay? = null
        private set

    fun startRelay(media: CastMedia, host: String, readText: (String) -> String): CastRelay {
        relay?.stop()
        return CastRelay(SourceHttp.client, SourceHttp.USER_AGENT, media.headers, readText).also {
            it.start(host)
            relay = it
        }
    }

    fun stopRelay() {
        relay?.stop()
        relay = null
    }

    /** The load request for [media] through [relay], starting at [positionMs] with the [subtitle] label shown. */
    fun request(
        media: CastMedia,
        relay: CastRelay,
        title: String,
        episode: String,
        cover: String?,
        positionMs: Long,
        subtitle: String?,
        language: (SubtitleTrack) -> String?,
    ): MediaLoadRequestData {
        val tracks = media.subtitles.mapIndexed { i, track ->
            MediaTrack.Builder(i + 1L, MediaTrack.TYPE_TEXT)
                .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                .setContentId(relay.subtitleLink(track.url, track.label))
                .setContentType("text/vtt")
                .setName(track.label)
                .setLanguage(language(track))
                .build()
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_TV_SHOW).apply {
            putString(MediaMetadata.KEY_SERIES_TITLE, title)
            putString(MediaMetadata.KEY_TITLE, "Episode $episode")
            cover?.let { addImage(WebImage(android.net.Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(relay.link(media.url))
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(CastRelay.mimeOf(media.url).takeIf { it != "application/octet-stream" } ?: "application/vnd.apple.mpegurl")
            .setMetadata(metadata)
            .setMediaTracks(tracks)
            .setTextTrackStyle(textStyle())
            .build()
        val active = media.subtitles.indexOfFirst { it.label == subtitle }.takeIf { it >= 0 }?.let { longArrayOf(it + 1L) }
        return MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(positionMs.coerceAtLeast(0))
            .apply { if (active != null) setActiveTrackIds(active) }
            .build()
    }

    /** White with a black edge, like the phone's. */
    fun textStyle(): TextTrackStyle = TextTrackStyle().apply {
        foregroundColor = 0xFFFFFFFF.toInt()
        backgroundColor = 0x00000000
        edgeType = TextTrackStyle.EDGE_TYPE_OUTLINE
        edgeColor = 0xFF000000.toInt()
        fontScale = 1.0f
    }
}
