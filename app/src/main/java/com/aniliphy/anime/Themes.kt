package com.aniliphy.anime

import com.aniliphy.core.Web
import com.aniliphy.core.WebCall
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/** An opening or ending of an anime, from AnimeThemes. */
@Serializable
data class AnimeTheme(
    val mediaId: Int,
    val anime: String,
    /** "OP", "ED" or "IN" (an insert song). */
    val type: String,
    val sequence: Int? = null,
    /** Like "OP2". */
    val slug: String,
    val song: String? = null,
    val artists: List<String> = emptyList(),
    /** Like "26-53", or "517-541, 543-589", as AnimeThemes writes it. */
    val episodes: String? = null,
    /** AnimeThemes' own mark: the video shows something that happens late in the show. */
    val spoiler: Boolean = false,
    val nsfw: Boolean = false,
    val videoUrl: String? = null,
    val audioUrl: String? = null,
) {
    val label: String get() = when (type) {
        "OP" -> "Opening ${sequence ?: 1}"
        "ED" -> "Ending ${sequence ?: 1}"
        else -> "Insert song"
    }

    /** The first episode it plays in, when known. */
    val firstEpisode: Int? get() = Themes.ranges(episodes).minOfOrNull { it.first }

    /** Whether it gives something away to someone who has seen up to episode [seen]: marked so, or it starts later on. */
    fun spoils(seen: Int?): Boolean {
        if (spoiler) return true
        val first = firstEpisode ?: return false
        return seen != null && first > seen + 1
    }
}

/**
 * Openings and endings from AnimeThemes (animethemes.moe), through its GraphQL API (its REST one is often behind a bot
 * check). Many titles are asked in one request, so a jukebox of a whole list stays cheap. Answers are kept a week.
 */
class Themes(private val cacheFile: File?, private val web: WebCall = Web) {
    @Serializable
    private data class Cached(val at: Long, val themes: List<AnimeTheme>)

    private val cache: MutableMap<Int, Cached> by lazy {
        cacheFile?.readTextOrNull()?.let { text ->
            runCatching { JSON.decodeFromString<Map<Int, Cached>>(text).toMutableMap() }.getOrNull()
        } ?: mutableMapOf()
    }

    /** The themes of each of [mediaIds] (AniList ids); a title AnimeThemes doesn't know comes back empty. */
    @Throws(IOException::class)
    fun forMedia(mediaIds: List<Int>, now: Long = System.currentTimeMillis()): Map<Int, List<AnimeTheme>> {
        val wanted = mediaIds.distinct()
        val found = HashMap<Int, List<AnimeTheme>>()
        val missing = mutableListOf<Int>()
        synchronized(cache) {
            wanted.forEach { id ->
                val kept = cache[id]?.takeIf { now - it.at < KEEP_MS }
                if (kept != null) found[id] = kept.themes else missing += id
            }
        }
        missing.chunked(BATCH).forEach { batch ->
            val body = buildJsonObject { put("query", query(batch)) }.toString().encodeToByteArray()
            val reply = web.post(URL, body, "application/json", mapOf("Accept" to "application/json"))
            if (!reply.ok || !reply.looksLikeJson) throw IOException("AnimeThemes can't be reached right now (${reply.code})")
            val parsed = parse(reply.text)
            synchronized(cache) {
                batch.forEach { id ->
                    val themes = parsed[id].orEmpty()
                    found[id] = themes
                    cache[id] = Cached(now, themes)
                }
            }
        }
        if (missing.isNotEmpty()) synchronized(cache) { cacheFile?.writeTextAtomically(JSON.encodeToString(cache.toMap())) }
        return wanted.associateWith { found[it].orEmpty() }
    }

    companion object {
        @Volatile
        private var shared: Themes? = null

        /** The app's one, keeping what it found in the app's files. */
        fun shared(context: android.content.Context): Themes = shared ?: synchronized(this) {
            shared ?: Themes(File(context.filesDir, "themes.json")).also { shared = it }
        }

        const val URL = "https://graphql.animethemes.moe/"
        private const val BATCH = 25
        private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        private val JSON = Json { ignoreUnknownKeys = true }

        fun query(ids: List<Int>): String =
            "{ findAnimeByExternalSite(site: ANILIST, id: [${ids.joinToString(",")}]) { title { romaji english } " +
                "resources(first: 40) { edges { node { site externalId } } } " +
                "animethemes { type sequence slug song { title { romaji } performances { as artist { ... on Artist { name { main } } } } } " +
                "animethemeentries { episodes spoiler nsfw version videos(first: 4) { edges { node { link resolution nc overlap audio { link } } } } } } } }"

        /** The themes in an answer, by AniList id. */
        fun parse(text: String): Map<Int, List<AnimeTheme>> {
            val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
            val list = ((root["data"] as? JsonObject)?.get("findAnimeByExternalSite") as? JsonArray) ?: return emptyMap()
            val out = HashMap<Int, MutableList<AnimeTheme>>()
            list.forEach { element ->
                val anime = element as? JsonObject ?: return@forEach
                val title = anime.obj("title")
                val name = title?.str("english") ?: title?.str("romaji") ?: return@forEach
                val ids = anime.obj("resources")?.arr("edges").orEmpty().mapNotNull { edge ->
                    val node = (edge as? JsonObject)?.obj("node") ?: return@mapNotNull null
                    if (node.str("site") == "ANILIST") node.int("externalId") else null
                }
                val themes = anime.arr("animethemes").orEmpty().mapNotNull { (it as? JsonObject)?.let { theme -> theme(theme, name) } }
                ids.forEach { id -> out.getOrPut(id) { mutableListOf() } += themes.map { it.copy(mediaId = id) } }
            }
            return out.mapValues { (_, themes) -> themes.sortedWith(compareBy({ typeOrder(it.type) }, { it.sequence ?: 0 })) }
        }

        private fun typeOrder(type: String) = when (type) {
            "OP" -> 0
            "ED" -> 1
            else -> 2
        }

        private fun theme(theme: JsonObject, anime: String): AnimeTheme? {
            val type = theme.str("type") ?: return null
            val song = theme.obj("song")
            val artists = song?.arr("performances").orEmpty().mapNotNull { performance ->
                val p = performance as? JsonObject ?: return@mapNotNull null
                p.obj("artist")?.obj("name")?.str("main")?.let { name -> p.str("as")?.let { "$it ($name)" } ?: name }
            }
            // The first version is the one that aired; later ones are alternate cuts.
            val entries = theme.arr("animethemeentries").orEmpty().mapNotNull { it as? JsonObject }
            val entry = entries.filter { it.bool("nsfw") != true }.minByOrNull { it.int("version") ?: 1 } ?: entries.firstOrNull()
            val videos = entry?.obj("videos")?.arr("edges").orEmpty().mapNotNull { (it as? JsonObject)?.obj("node") }
            // A clean one (no credits) without overlapping footage, and not too big for a phone.
            val video = videos.sortedWith(
                compareBy<JsonObject>(
                    { if (it.str("overlap") == "NONE") 0 else 1 },
                    { if (it.bool("nc") == true) 0 else 1 },
                    { if ((it.int("resolution") ?: 720) > 720) 1 else 0 },
                    { -(it.int("resolution") ?: 0) },
                ),
            ).firstOrNull()
            val episodes = entry?.str("episodes")?.takeIf { it.isNotBlank() }
                ?: entries.firstNotNullOfOrNull { it.str("episodes")?.takeIf { e -> e.isNotBlank() } }
            return AnimeTheme(
                mediaId = 0,
                anime = anime,
                type = type,
                sequence = theme.int("sequence"),
                slug = theme.str("slug") ?: type,
                song = song?.obj("title")?.str("romaji"),
                artists = artists,
                episodes = episodes,
                spoiler = entry?.bool("spoiler") == true,
                nsfw = entry?.bool("nsfw") == true,
                videoUrl = video?.str("link"),
                audioUrl = video?.obj("audio")?.str("link") ?: videos.firstNotNullOfOrNull { it.obj("audio")?.str("link") },
            )
        }

        /** "517-541, 543-589" as ranges; a single "128" is a range of one. */
        fun ranges(episodes: String?): List<IntRange> = episodes.orEmpty().split(',').mapNotNull { part ->
            val bits = part.trim().split('-').map { it.trim().toIntOrNull() }
            when {
                bits.size == 1 && bits[0] != null -> bits[0]!!..bits[0]!!
                bits.size == 2 && bits[0] != null && bits[1] != null -> bits[0]!!..bits[1]!!
                bits.size == 2 && bits[0] != null -> bits[0]!!..bits[0]!!
                else -> null
            }
        }

        private fun JsonObject.obj(name: String) = this[name] as? JsonObject
        private fun JsonObject.arr(name: String) = this[name] as? JsonArray
        private fun JsonObject.str(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.int(name: String) = (this[name] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.bool(name: String) = (this[name] as? JsonPrimitive)?.booleanOrNull
    }
}
