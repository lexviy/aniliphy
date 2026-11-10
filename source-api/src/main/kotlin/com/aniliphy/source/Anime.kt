package com.aniliphy.source

import kotlinx.serialization.Serializable
import java.io.IOException

enum class AudioVersion(val label: String) { Sub("Sub"), Dub("Dub") }

/** A show found on the site. [url] is saved with progress, so its format must stay the same. */
data class SourceShow(val title: String, val url: String, val cover: String? = null, val otherTitle: String? = null)

@Serializable
data class SourceEpisode(
    val id: String,
    val number: Double,
    val name: String = "",
    val mal: Int? = null,
)

data class ServerLink(val name: String, val audio: AudioVersion, val link: String)

/** In seconds. */
@Serializable
data class Segment(val start: Double, val end: Double) {
    val valid: Boolean get() = end > start && end > 0
}

@Serializable
data class SubtitleTrack(val label: String, val url: String, val language: String? = null)

@Serializable
data class VideoStream(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val intro: Segment? = null,
    val outro: Segment? = null,
    val server: String = "",
    val audio: AudioVersion = AudioVersion.Sub,
)

interface AnimeSite : Site {
    @Throws(IOException::class)
    fun search(query: String): List<SourceShow>

    /** Sorted by number. */
    @Throws(IOException::class)
    fun episodes(showUrl: String): List<SourceEpisode>

    /** Every server, sub and dub, best first. */
    @Throws(IOException::class)
    fun servers(episode: SourceEpisode): List<ServerLink>

    /** The playable video behind a server. */
    @Throws(IOException::class)
    fun stream(server: ServerLink): VideoStream

    /**
     * For the progress synced with anilixy (the desktop app): how it names the show at [showUrl],
     * or null when anilixy doesn't use this site.
     */
    fun syncedShow(showUrl: String): String? = null

    /** The opposite of [syncedShow]: the show address for a name from anilixy, or null when it isn't this site's. */
    fun showFromSync(show: String): String? = null
}
