package com.aniliphy.anime

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.json.Json
import java.io.File

/** When to delete a downloaded episode once it counts as watched. */
enum class DeleteWatched(val label: String, val afterMs: Long?) {
    Never("Never", null),
    RightAway("Right after", 0L),
    Day("After a day", 24 * 60 * 60 * 1000L),
    Week("After a week", 7 * 24 * 60 * 60 * 1000L),
}

/** When each downloaded episode was watched ("mediaId/episodeId" to ms), so it can be deleted later. */
class WatchedDownloads(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun read(): Map<String, Long> =
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<Map<String, Long>>(it) }.getOrNull() }.orEmpty()

    private fun write(map: Map<String, Long>) {
        if (map.isEmpty()) file.delete() else runCatching { file.writeTextAtomically(json.encodeToString(map)) }
    }

    @Synchronized
    fun mark(mediaId: Int, episodeId: String, at: Long = System.currentTimeMillis()) {
        val map = read()
        val key = "$mediaId/$episodeId"
        if (key !in map) write(map + (key to at))
    }

    /**
     * The episodes to delete now with [rule], as (mediaId, episodeId); forgets them, and forgets the ones no longer
     * downloaded ([stillSaved] says). [keep] is the one playing, never touched.
     */
    @Synchronized
    fun takeDue(rule: DeleteWatched, now: Long, stillSaved: (Int, String) -> Boolean, keep: String? = null): List<Pair<Int, String>> {
        val map = read()
        if (map.isEmpty()) return emptyList()
        val after = rule.afterMs
        val due = mutableListOf<Pair<Int, String>>()
        val left = map.filter { (key, at) ->
            val mediaId = key.substringBefore('/').toIntOrNull() ?: return@filter false
            val episodeId = key.substringAfter('/')
            if (!stillSaved(mediaId, episodeId)) return@filter false
            if (key != keep && after != null && now - at >= after) {
                due += mediaId to episodeId
                false
            } else {
                true
            }
        }
        if (left.size != map.size) write(left)
        return due
    }
}
