package com.aniliphy.work

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A new episode or chapter of something you follow, waiting to be downloaded by itself: everything after
 * [from] up to [to] (AniList numbers), from the source linked to the title.
 */
@Serializable
data class AutoDownload(
    val anime: Boolean,
    val mediaId: Int,
    val title: String,
    val source: String,
    val from: Double,
    val to: Double,
    val cover: String? = null,
    val mal: Int? = null,
    val total: Int? = null,
    val since: Long = 0,
) {
    val key: String get() = (if (anime) "a" else "m") + mediaId
}

class AutoDownloads(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun all(): List<AutoDownload> =
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<List<AutoDownload>>(it) }.getOrNull() }.orEmpty()

    /** One per title: a newer release widens the one already waiting instead of adding another. */
    @Synchronized
    fun add(item: AutoDownload) {
        val old = all()
        val same = old.firstOrNull { it.key == item.key }
        val merged = if (same == null) item else same.copy(to = maxOf(same.to, item.to), from = minOf(same.from, item.from), source = item.source)
        write(old.filterNot { it.key == item.key } + merged)
    }

    @Synchronized
    fun remove(key: String) {
        val old = all()
        if (old.any { it.key == key }) write(old.filterNot { it.key == key })
    }

    private fun write(items: List<AutoDownload>) {
        if (items.isEmpty()) file.delete() else runCatching { file.writeTextAtomically(json.encodeToString(items)) }
    }

    companion object {
        const val FILE = "auto_downloads.json"

        /** A source that doesn't have the episode yet gets tried again for this long after it aired. */
        const val GIVE_UP_AFTER_MS = 24 * 60 * 60 * 1000L

        const val MAX_EPISODES = 2
        const val MAX_CHAPTERS = 5

        /** Which numbers to fetch: after [from], up to [to], the newest [cap] at most, in order. */
        fun wanted(numbers: List<Double>, from: Double, to: Double, cap: Int): List<Double> =
            numbers.filter { it > from && it <= to }.distinct().sorted().takeLast(cap)
    }
}
