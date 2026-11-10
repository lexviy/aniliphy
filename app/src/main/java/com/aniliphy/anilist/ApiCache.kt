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
        return entry.readTextOrNull()
    }

    fun readAny(entry: File): String? = entry.readTextOrNull()

    fun currentGeneration(): Int = synchronized(lock) { generation }

    fun write(entry: File, startedAt: Int, text: String) = synchronized(lock) {
        if (startedAt != generation) return@synchronized
        try {
            entry.writeTextAtomically(text)
            entry.setLastModified(now())
        } catch (_: IOException) {
        }
    }

    fun invalidateUser() = synchronized(lock) {
        generation++
        File(root, USER).deleteRecursively()
        Unit
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
        const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
    }
}
