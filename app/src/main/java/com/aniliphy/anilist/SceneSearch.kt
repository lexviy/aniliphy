package com.aniliphy.anilist

import com.aniliphy.core.Web
import com.aniliphy.core.WebCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.io.IOException

/** One place a picture was found: which anime, which episode, and when in it. */
data class SceneMatch(
    val mediaId: Int,
    val mal: Int?,
    val title: MediaTitle,
    val cover: String?,
    val banner: String?,
    val format: String?,
    val episodes: Int?,
    val adult: Boolean,
    /** Null for a movie, or when trace.moe couldn't tell. */
    val episode: Int?,
    val atSeconds: Double,
    val fromSeconds: Double,
    val toSeconds: Double,
    val similarity: Double,
    /** A few seconds of the scene, muted, and a still of it, both from trace.moe. */
    val clipUrl: String?,
    val imageUrl: String?,
) {
    /** Below this, trace.moe's best guess is often another show drawn alike. */
    val sure: Boolean get() = similarity >= SceneSearch.SURE
}

/** How many searches are left this month from this connection. */
data class SceneQuota(val used: Int, val total: Int) {
    val left: Int get() = (total - used).coerceAtLeast(0)
}

class SceneSearchException(message: String) : IOException(message)

/**
 * Finds the anime, episode and minute a screenshot comes from, with trace.moe. The picture is sent to trace.moe only
 * when asked; nothing else about the account goes with it.
 */
class SceneSearch(private val web: WebCall = Web) {
    @Throws(IOException::class)
    fun search(image: ByteArray, contentType: String = "image/jpeg"): List<SceneMatch> {
        val reply = web.post(SEARCH_URL, image, contentType)
        if (!reply.looksLikeJson) throw SceneSearchException("trace.moe can't be reached right now (${reply.code})")
        return parse(reply.text, reply.code)
    }

    @Throws(IOException::class)
    fun quota(): SceneQuota? {
        val reply = web.get(ME_URL)
        if (!reply.ok || !reply.looksLikeJson) return null
        val me = runCatching { Json.parseToJsonElement(reply.text) as? JsonObject }.getOrNull() ?: return null
        val total = me.int("quota") ?: return null
        return SceneQuota(me.int("quotaUsed") ?: 0, total)
    }

    companion object {
        const val SEARCH_URL = "https://api.trace.moe/search?anilistInfo&cutBorders"
        const val ME_URL = "https://api.trace.moe/me"
        const val SURE = 0.9

        /** Matches below this are left out: they're seldom right. */
        private const val SHOWN = 0.8

        @Throws(SceneSearchException::class)
        fun parse(text: String, code: Int = 200): List<SceneMatch> {
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw SceneSearchException("trace.moe sent something unexpected")
            val error = root.string("error")
            if (!error.isNullOrBlank() || code !in 200..299) throw SceneSearchException(explain(code, error))
            val results = root["result"] as? JsonArray ?: return emptyList()
            val matches = results.mapNotNull { (it as? JsonObject)?.let(::match) }
            // trace.moe lists the same scene several times, a fraction of a second apart: keep the best of each.
            return matches
                .filter { it.similarity >= SHOWN }
                .groupBy { it.mediaId to it.episode }
                .map { (_, same) -> same.maxBy { it.similarity } }
                .sortedByDescending { it.similarity }
        }

        private fun match(item: JsonObject): SceneMatch? {
            val anilist = item["anilist"] as? JsonObject ?: return null
            val id = anilist.int("id") ?: return null
            val title = anilist["title"] as? JsonObject
            val cover = anilist["coverImage"] as? JsonObject
            return SceneMatch(
                mediaId = id,
                mal = anilist.int("idMal"),
                title = MediaTitle(
                    romaji = title?.string("romaji"),
                    english = title?.string("english"),
                    native = title?.string("native"),
                ),
                cover = cover?.string("extraLarge") ?: cover?.string("large"),
                banner = anilist.string("bannerImage"),
                format = anilist.string("format"),
                episodes = anilist.int("episodes"),
                adult = (anilist["isAdult"] as? JsonPrimitive)?.booleanOrNull == true,
                episode = episodeOf(item["episode"]),
                atSeconds = item.double("at") ?: item.double("from") ?: 0.0,
                fromSeconds = item.double("from") ?: 0.0,
                toSeconds = item.double("to") ?: 0.0,
                similarity = item.double("similarity") ?: 0.0,
                clipUrl = item.string("video"),
                imageUrl = item.string("image"),
            )
        }

        /** trace.moe gives a number, a string like "12", a list for a scene shared by episodes, or nothing. */
        fun episodeOf(value: JsonElement?): Int? = when (value) {
            is JsonPrimitive -> value.intOrNull ?: value.contentOrNull?.trim()?.toDoubleOrNull()?.toInt()
                ?: value.contentOrNull?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
            is JsonArray -> value.firstNotNullOfOrNull { episodeOf(it) }
            else -> null
        }

        private fun explain(code: Int, error: String?): String = when (code) {
            402 -> "trace.moe's searches for this month are used up"
            413 -> "That picture is too big for trace.moe"
            429 -> "trace.moe is busy with another search. Try again in a moment"
            503 -> "trace.moe is busy right now. Try again soon"
            else -> error?.takeIf { it.isNotBlank() } ?: "trace.moe answered $code"
        }

        private fun JsonObject.string(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.double(name: String) = (this[name] as? JsonPrimitive)?.doubleOrNull
    }
}
