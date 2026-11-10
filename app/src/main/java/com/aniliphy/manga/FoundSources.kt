package com.aniliphy.manga

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.source.SourceSeries
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

class FoundSources(private val file: File) {
    @Serializable
    data class Found(val source: String? = null, val looked: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }

    fun all(): Map<Int, Found> = synchronized(LOCK) {
        runCatching { file.readTextOrNull()?.let { json.decodeFromString<Map<String, Found>>(it) } }
            .getOrNull().orEmpty().mapNotNull { (id, found) -> id.toIntOrNull()?.let { it to found } }.toMap()
    }

    fun put(mediaId: Int, found: Found) = synchronized(LOCK) {
        val all = all().toMutableMap()
        all[mediaId] = found
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(all.mapKeys { it.key.toString() }))
    }

    companion object {
        private val LOCK = Any()

        const val LOOK_AGAIN_MS = 7L * 24 * 60 * 60 * 1000

        const val SEARCHES_PER_CHECK = 4

        fun normalized(title: String): String =
            title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

        fun exact(results: List<SourceSeries>, titles: List<String>): SourceSeries? {
            val wanted = titles.map(::normalized).filter { it.isNotEmpty() }.toSet()
            return results.filter { normalized(it.title) in wanted }.distinctBy { it.url }.singleOrNull()
        }

        @Throws(IOException::class)
        fun look(titles: List<String>, order: List<String>, search: (String, String) -> List<SourceSeries>): String? {
            for (source in order) {
                for (query in titles.filter { it.isNotBlank() }.distinct()) {
                    exact(search(source, query), titles)?.let { return MangaSites.sourceId(source, it.url) }
                }
            }
            return null
        }
    }
}
