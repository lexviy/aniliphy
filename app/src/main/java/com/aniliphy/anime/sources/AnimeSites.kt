package com.aniliphy.anime.sources

import com.aniliphy.core.Sources
import com.aniliphy.source.AnimeSite
import com.aniliphy.source.AudioVersion
import com.aniliphy.source.ServerLink
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.VideoStream
import java.io.IOException

object AnimeSites {
    /** No source chosen yet: the first one installed is tried first. */
    const val DEFAULT = ""

    fun all(): List<AnimeSite> = Sources.anime

    fun find(id: String?): AnimeSite? = Sources.find(all(), id)

    @Throws(IOException::class)
    fun site(id: String): AnimeSite = Sources.need(all(), id)

    /** The name to show for a source id, even when it isn't installed. */
    fun name(id: String?): String = find(id)?.name ?: id ?: "the site"

    fun order(preferred: String?): List<AnimeSite> = Sources.ordered(all(), preferred)

    fun sourceId(id: String, showUrl: String) = Sources.place(id, showUrl)

    fun parse(sourceId: String?): Pair<String, String>? = Sources.parse(sourceId)

    fun ordered(servers: List<ServerLink>, audio: AudioVersion): List<ServerLink> = servers.filter { it.audio == audio }

    @Throws(IOException::class)
    fun stream(
        site: AnimeSite,
        episode: SourceEpisode,
        audio: AudioVersion,
        skip: Set<String> = emptySet(),
    ): VideoStream? {
        val all = ordered(site.servers(episode), audio)
        if (all.isEmpty()) return null
        val servers = all.filter { it.name !in skip }
        if (servers.isEmpty()) throw IOException("No other server for this episode")
        var failure: IOException? = null
        for (server in servers) {
            try {
                return site.stream(server).copy(server = server.name, audio = audio)
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure ?: IOException("No server answered")
    }
}
