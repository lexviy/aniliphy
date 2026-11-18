package com.aniliphy.manga

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** How one series is read, when it's not the way set for all series. */
@Serializable
data class SeriesChoice(val mode: ReadingMode? = null, val direction: ReadingDirection? = null) {
    val empty: Boolean get() = mode == null && direction == null
}

/** Reading mode and direction kept per series (AniList id). */
class SeriesReading(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun all(): Map<Int, SeriesChoice> =
        file.readTextOrNull()?.let { text ->
            runCatching { json.decodeFromString<Map<String, SeriesChoice>>(text) }.getOrNull()
        }.orEmpty().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()

    fun get(mediaId: Int): SeriesChoice = all()[mediaId] ?: SeriesChoice()

    @Synchronized
    fun set(mediaId: Int, choice: SeriesChoice) {
        val map = all().toMutableMap()
        if (choice.empty) map.remove(mediaId) else map[mediaId] = choice
        if (map.isEmpty()) file.delete() else runCatching { file.writeTextAtomically(json.encodeToString(map.mapKeys { it.key.toString() })) }
    }

    companion object {
        const val FILE = "manga_series_reading.json"
    }
}
