package com.aniliphy.anime

import com.aniliphy.core.Web
import com.aniliphy.core.WebCall
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.source.Segment
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/** Where an episode's opening, ending and recap are, so they can be skipped. */
@Serializable
data class EpisodeSkips(val intro: Segment? = null, val outro: Segment? = null, val recap: Segment? = null) {
    val empty: Boolean get() = intro == null && outro == null && recap == null
}

/**
 * Skip times from AniSkip (api.aniskip.com), shared by its users, for when the site an episode comes from doesn't say
 * where the opening and ending are. It goes by MyAnimeList id and episode number.
 */
class SkipTimes(private val cacheFile: File?, private val web: WebCall = Web) {
    /** For a [VideoStream] that doesn't say where its opening and ending are: fills them in, when AniSkip knows. */
    fun fill(stream: com.aniliphy.source.VideoStream, mal: Int?, episode: Int?, lengthSeconds: Double = 0.0): com.aniliphy.source.VideoStream {
        if (mal == null || episode == null || (stream.intro != null && stream.outro != null)) return stream
        val found = runCatching { find(mal, episode, lengthSeconds) }.getOrNull() ?: return stream
        return stream.copy(intro = stream.intro ?: found.intro, outro = stream.outro ?: found.outro)
    }

    @Serializable
    private data class Cached(val at: Long, val length: Double, val skips: EpisodeSkips)

    @Serializable
    private data class Interval(val startTime: Double = 0.0, val endTime: Double = 0.0)

    @Serializable
    private data class Result(val interval: Interval = Interval(), val skipType: String = "", val episodeLength: Double = 0.0)

    @Serializable
    private data class Answer(val found: Boolean = false, val results: List<Result> = emptyList())

    private val cache: MutableMap<String, Cached> by lazy {
        cacheFile?.readTextOrNull()?.let { text ->
            runCatching { JSON.decodeFromString<Map<String, Cached>>(text).toMutableMap() }.getOrNull()
        } ?: mutableMapOf()
    }

    /**
     * The skips for episode [episode] of MAL title [mal], whose video is [lengthSeconds] long. Answers already asked
     * (found or not) are kept for a while, so playing an episode again doesn't ask again.
     */
    @Throws(IOException::class)
    fun find(mal: Int, episode: Int, lengthSeconds: Double, now: Long = System.currentTimeMillis()): EpisodeSkips {
        val key = "$mal:$episode"
        synchronized(cache) {
            cache[key]?.takeIf { now - it.at < (if (it.skips.empty) EMPTY_KEEP_MS else KEEP_MS) && abs(it.length - lengthSeconds) < 3 }
                ?.let { return it.skips }
        }
        val url = url(mal, episode, lengthSeconds)
        val reply = web.get(url, mapOf("Accept" to "application/json"))
        val skips = when {
            reply.code == 404 -> EpisodeSkips()
            !reply.ok || !reply.looksLikeJson -> throw IOException("AniSkip answered ${reply.code}")
            else -> parse(reply.text, lengthSeconds)
        }
        synchronized(cache) {
            cache[key] = Cached(now, lengthSeconds, skips)
            cacheFile?.writeTextAtomically(JSON.encodeToString(cache.toMap()))
        }
        return skips
    }

    companion object {
        @Volatile
        private var shared: SkipTimes? = null

        /** The app's one, keeping its answers in the app's files. */
        fun shared(context: android.content.Context): SkipTimes = shared ?: synchronized(this) {
            shared ?: SkipTimes(File(context.filesDir, "skip_times.json")).also { shared = it }
        }

        private val JSON = Json { ignoreUnknownKeys = true }
        private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private const val EMPTY_KEEP_MS = 3L * 24 * 60 * 60 * 1000

        fun url(mal: Int, episode: Int, lengthSeconds: Double): String {
            val types = listOf("op", "ed", "mixed-op", "mixed-ed", "recap").joinToString("&") { "types[]=$it" }
            return "https://api.aniskip.com/v2/skip-times/$mal/$episode?$types&episodeLength=${String.format(Locale.ROOT, "%.1f", lengthSeconds)}"
        }

        /**
         * Picks, for each kind, the submission made on a video closest in length to ours, and moves it by the
         * difference when that video was a little longer or shorter (an extra logo at the start, usually).
         */
        fun parse(text: String, lengthSeconds: Double): EpisodeSkips {
            val answer = runCatching { JSON.decodeFromString<Answer>(text) }.getOrNull() ?: return EpisodeSkips()
            if (!answer.found) return EpisodeSkips()
            fun best(vararg types: String): Segment? = answer.results
                .filter { it.skipType in types && it.interval.endTime > it.interval.startTime }
                .minByOrNull { if (lengthSeconds > 0 && it.episodeLength > 0) abs(it.episodeLength - lengthSeconds) else 0.0 }
                ?.let { result ->
                    val shift = if (lengthSeconds > 0 && result.episodeLength > 0) lengthSeconds - result.episodeLength else 0.0
                    // Endings sit relative to the end of the video, openings to its start.
                    val moved = if (result.skipType.endsWith("ed") && abs(shift) < 30) shift else 0.0
                    val start = (result.interval.startTime + moved).coerceAtLeast(0.0)
                    val end = (result.interval.endTime + moved).let { if (lengthSeconds > 0) it.coerceAtMost(lengthSeconds) else it }
                    Segment(start, end).takeIf { it.valid }
                }
            return EpisodeSkips(intro = best("op", "mixed-op"), outro = best("ed", "mixed-ed"), recap = best("recap"))
        }
    }
}
