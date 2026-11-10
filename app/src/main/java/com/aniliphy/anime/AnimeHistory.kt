package com.aniliphy.anime

import com.aniliphy.source.SourceEpisode
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AnimeRecord(
    val source: String? = null,
    val backup: String? = null,
    val episodeId: String? = null,
    val episodeName: String? = null,
    val episodeNumber: Double? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String? = null,
    val cover: String? = null,
    val updated: Long = 0,
    val total: Int? = null,
    val titles: List<String> = emptyList(),
    val mal: Int? = null,
    val watched: Int = 0,
) {
    val hasPlace: Boolean get() = episodeNumber != null
}

class AnimeHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    fun all(): Map<Int, AnimeRecord> = synchronized(lock) {
        runCatching {
            file.readTextOrNull()?.let { json.decodeFromString<Map<String, AnimeRecord>>(it) }
        }.getOrNull().orEmpty().mapNotNull { (id, record) -> id.toIntOrNull()?.let { it to record } }.toMap()
    }

    fun get(mediaId: Int): AnimeRecord? = all()[mediaId]

    private fun change(mediaId: Int, update: (AnimeRecord) -> AnimeRecord?) = synchronized(lock) {
        val all = all().toMutableMap()
        val next = update(all[mediaId] ?: AnimeRecord())
        if (next == null) all.remove(mediaId) else all[mediaId] = next
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(all.mapKeys { it.key.toString() }))
    }

    fun edit(mediaId: Int, update: (AnimeRecord) -> AnimeRecord?) = change(mediaId, update)

    fun setSource(mediaId: Int, source: String?, title: String? = null, cover: String? = null) = change(mediaId) { old ->
        val sameSource = old.source == source
        old.copy(
            source = source,
            backup = if (sameSource) old.backup else null,
            episodeId = if (sameSource) old.episodeId else null,
            title = title ?: old.title,
            cover = cover ?: old.cover,
        ).takeIf { it.source != null || it.hasPlace }
    }

    fun setBackup(mediaId: Int, backup: String?) = change(mediaId) { it.copy(backup = backup) }

    fun savePlace(
        mediaId: Int,
        episode: SourceEpisode,
        positionMs: Long,
        durationMs: Long,
        title: String?,
        cover: String?,
        now: Long = System.currentTimeMillis(),
        total: Int? = null,
        titles: List<String> = emptyList(),
        mal: Int? = null,
    ) = change(mediaId) { old ->
        old.copy(
            episodeId = episode.id,
            episodeName = episode.name.ifEmpty { null },
            episodeNumber = episode.number,
            positionMs = positionMs.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0),
            title = title ?: old.title,
            cover = cover ?: old.cover,
            updated = now,
            total = total ?: old.total,
            titles = titles.ifEmpty { old.titles },
            mal = mal ?: old.mal,
        )
    }

    fun markWatched(mediaId: Int, episode: Int) = change(mediaId) { it.copy(watched = maxOf(it.watched, episode)) }

    fun clearPlace(mediaId: Int) = change(mediaId) { old ->
        old.copy(episodeId = null, episodeName = null, episodeNumber = null, positionMs = 0, durationMs = 0, watched = 0)
            .takeIf { it.source != null }
    }
}

class NewEpisodes(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = LOCK

    fun seen(): Map<Int, Int> = synchronized(lock) {
        runCatching {
            file.readTextOrNull()?.let { json.decodeFromString<Map<String, Int>>(it) }
        }.getOrNull().orEmpty().mapNotNull { (id, count) -> id.toIntOrNull()?.let { it to count } }.toMap()
    }

    private fun write(all: Map<Int, Int>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(all.mapKeys { it.key.toString() }))
    }

    fun check(items: List<Airing>): Map<Int, Int> = synchronized(lock) {
        val known = seen().toMutableMap()
        var changed = false
        val found = mutableMapOf<Int, Int>()
        for (item in items) {
            val released = item.released
            if (released < 1) continue
            val seen = known[item.mediaId]
            when {
                seen == null -> {
                    known[item.mediaId] = released
                    changed = true
                }
                released > seen && released > item.watched -> found[item.mediaId] = released
                released > seen -> {
                    known[item.mediaId] = released
                    changed = true
                }
            }
        }
        if (changed) write(known)
        found
    }

    fun opened(mediaId: Int, released: Int) = synchronized(lock) {
        if (released < 1) return@synchronized
        val all = seen().toMutableMap()
        if (all[mediaId] != released) {
            all[mediaId] = released
            write(all)
        }
    }

    private companion object {
        val LOCK = Any()
    }

    data class Airing(val mediaId: Int, val nextEpisode: Int?, val watched: Int) {
        val released: Int get() = (nextEpisode ?: 0) - 1
    }
}
