package com.aniliphy.anilist

import android.content.Context
import com.aniliphy.core.PrefStore
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.json.Json
import java.io.File

/** Search filters kept with a name (search_filters.json). */
class SavedFilters(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun all(): List<SavedFilter> =
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<List<SavedFilter>>(it) }.getOrNull() }.orEmpty()

    /** Keeps [filters] as [name]; one with the same name is replaced. */
    @Synchronized
    fun save(name: String, filters: SearchFilters) = write(all().filter { it.name != name } + SavedFilter(name, filters))

    @Synchronized
    fun remove(name: String) = write(all().filter { it.name != name })

    private fun write(list: List<SavedFilter>) {
        if (list.isEmpty()) file.delete() else runCatching { file.writeTextAtomically(json.encodeToString(list)) }
    }

    companion object {
        const val FILE = "search_filters.json"
        fun of(context: Context) = SavedFilters(File(context.filesDir, FILE))
    }
}

/** The backlog's choices: after how many weeks a title counts as stalled, the recap card, and "Not now" taps. */
class BacklogPrefs(context: Context) : PrefStore(context, "backlog") {
    /** 0 is never. */
    var weeks by intPref("weeks", 3)
    var recap by boolPref("recap", true)
    private var notNowText by stringPref("not_now", "")

    /** Media id to when "Not now" was tapped (seconds). */
    val notNow: Map<Int, Long>
        get() = notNowText.split(',').mapNotNull { part ->
            val (id, at) = part.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toIntOrNull()?.let { i -> at.toLongOrNull()?.let { i to it } }
        }.toMap()

    fun notNow(mediaId: Int, nowS: Long) {
        notNowText = (notNow + (mediaId to nowS)).entries.joinToString(",") { "${it.key}:${it.value}" }
    }

    companion object {
        val WEEKS = listOf(2, 3, 4, 8, 0)

        @Volatile private var instance: BacklogPrefs? = null
        fun get(context: Context): BacklogPrefs = instance ?: synchronized(this) {
            instance ?: BacklogPrefs(context.applicationContext).also { instance = it }
        }
    }
}
