package com.aniliphy.anilist

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class ApiCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var generation = 0

    fun entry(query: String, variables: String, token: String?): File {
        val scope = if (token != null) USER else PUBLIC
        return File(File(root, scope), sha256("$query\u0000$variables\u0000${token.orEmpty()}") + ".json")
    }

    fun readFresh(entry: File, ttlMillis: Long): String? {
        val modified = entry.lastModified()
        if (modified == 0L || now() - modified >= ttlMillis) return null
        if (entry.parentFile?.name == USER && modified <= changedAt()) return null
        return entry.readTextOrNull()
    }

    fun readAny(entry: File): String? = entry.readTextOrNull()

    fun currentGeneration(): Int = synchronized(lock) { generation }

    fun write(entry: File, startedAt: Int, text: String) = synchronized(lock) {
        if (startedAt != generation) return@synchronized
        try {
            entry.writeTextAtomically(text)
            // Written after the last change, so it counts as fresh even within the same millisecond.
            entry.setLastModified(maxOf(now(), changedAt() + 1))
        } catch (_: IOException) {
        }
    }

    /**
     * After a change to the account, its answers no longer count as fresh. They are kept, not deleted, so that when
     * AniList can't be reached (offline, or the rate limit) a screen can still show the last copy instead of nothing.
     */
    fun invalidateUser() = synchronized(lock) {
        generation++
        val at = maxOf(now(), changedAt() + 1)
        changed = at
        try {
            root.mkdirs()
            File(root, CHANGED).writeTextAtomically(at.toString())
        } catch (_: IOException) {
        }
        Unit
    }

    /** Deletes everything about the account, for logging out. */
    fun clearUser() = synchronized(lock) {
        invalidateUser()
        File(root, USER).deleteRecursively()
        Unit
    }

    private var changed: Long? = null

    /** When the account last changed; kept on disk so it holds after the app restarts. */
    private fun changedAt(): Long = synchronized(lock) {
        changed ?: (File(root, CHANGED).readTextOrNull()?.trim()?.toLongOrNull() ?: 0L).also { changed = it }
    }

    fun prune(maxAgeMillis: Long = MAX_AGE) = synchronized(lock) {
        root.walkBottomUp()
            .filter { it.isFile && now() - it.lastModified() >= maxAgeMillis }
            .forEach { it.delete() }
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PUBLIC = "public"
        const val USER = "user"
        const val CHANGED = "user-changed"
        const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
    }
}
