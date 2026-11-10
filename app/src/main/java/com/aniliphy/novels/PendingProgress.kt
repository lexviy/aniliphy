package com.aniliphy.novels

import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.InvalidTokenException
import com.aniliphy.anilist.NotLoggedInException
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.json.Json
import java.io.File

class PendingProgress(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun all(): Map<Int, Int> = synchronized(lock) {
        runCatching {
            file.readTextOrNull()?.let { json.decodeFromString<Map<String, Int>>(it) }
        }.getOrNull().orEmpty().mapNotNull { (id, count) -> id.toIntOrNull()?.let { it to count } }.toMap()
    }

    fun add(mediaId: Int, chapters: Int) = synchronized(lock) {
        val all = all().toMutableMap()
        all[mediaId] = maxOf(all[mediaId] ?: 0, chapters)
        write(all)
    }

    fun sent(mediaId: Int, chapters: Int) = synchronized(lock) {
        val all = all().toMutableMap()
        if ((all[mediaId] ?: 0) <= chapters) all.remove(mediaId)
        write(all)
    }

    private fun write(all: Map<Int, Int>) {
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(all.mapKeys { it.key.toString() }))
    }

    suspend fun flush(save: suspend (mediaId: Int, chapters: Int) -> Unit): Map<Int, Int> {
        val done = LinkedHashMap<Int, Int>()
        for ((mediaId, chapters) in all()) {
            try {
                save(mediaId, chapters)
                done[mediaId] = chapters
            } catch (e: AniListException) {
                if (e.temporary || e is InvalidTokenException || e is NotLoggedInException) break
            }
            sent(mediaId, chapters)
        }
        return done
    }
}
