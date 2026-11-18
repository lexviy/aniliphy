package com.aniliphy.anime

import com.aniliphy.core.WebCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URLEncoder

/**
 * Japanese subtitles from jimaku.cc. Each person makes their own API key there; without one nothing is asked.
 * Only plain subtitle files are offered (archives are left out).
 */
class Jimaku(private val web: WebCall, private val key: String) {
    @Serializable
    data class Entry(val id: Int = 0, val name: String = "", val anilist_id: Int? = null)

    @Serializable
    data class SubFile(val url: String = "", val name: String = "", val size: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }

    @Throws(IOException::class)
    private fun get(path: String): String {
        val reply = web.get("$BASE$path", mapOf("Authorization" to key, "Accept" to "application/json"))
        when {
            reply.code == 401 || reply.code == 403 -> throw IOException("Jimaku didn't accept the API key")
            reply.code == 429 -> throw IOException("Too many requests to Jimaku. Try again in a minute")
            !reply.ok || !reply.looksLikeJson -> throw IOException("Jimaku answered ${reply.code}")
        }
        return reply.text
    }

    /** The files for one episode of the title with [anilistId]; empty when Jimaku has none. */
    @Throws(IOException::class)
    fun files(anilistId: Int, episode: Int?): List<SubFile> {
        val entries = json.decodeFromString<List<Entry>>(get("/entries/search?anilist_id=$anilistId&anime=true"))
        val entry = entries.firstOrNull { it.anilist_id == anilistId } ?: entries.firstOrNull() ?: return emptyList()
        val query = episode?.let { "?episode=" + URLEncoder.encode(it.toString(), "UTF-8") }.orEmpty()
        return usable(json.decodeFromString<List<SubFile>>(get("/entries/${entry.id}/files$query")))
    }

    @Throws(IOException::class)
    fun download(file: SubFile): ByteArray {
        val reply = web.get(file.url, mapOf("Authorization" to key))
        if (!reply.ok) throw IOException("Jimaku answered ${reply.code}")
        return reply.body
    }

    companion object {
        const val BASE = "https://jimaku.cc/api"
        private val SUBTITLES = listOf(".srt", ".ass", ".ssa", ".vtt")

        fun usable(files: List<SubFile>): List<SubFile> =
            files.filter { f -> SUBTITLES.any { f.name.lowercase().endsWith(it) } }.sortedBy { it.name.lowercase() }
    }
}
