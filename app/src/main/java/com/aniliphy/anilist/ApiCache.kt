package com.aniliphy.anilist

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * What an account answer is about, so a change only makes stale what it can have changed. Adding a title to Planning
 * used to make every saved answer stale (both lists, the profile, the forum, every title page), and fetching them all
 * again after each one ran into AniList's rate limit after a handful of titles.
 */
enum class CacheGroup {
    /** The anime list and what is worked out from it (stats details, people, links). */
    AnimeList,
    /** The manga and light novel list, likewise. */
    MangaList,
    /** Statistics, recent activity and the activity history. */
    Profile,
    /** Title, character, staff and studio pages and season lists: they show your list entry and favourites. */
    Media,
    Favourites,
    /** Users, follows, the feed, replies, notifications. */
    Social,
    Forum,
    /** Anything else; only a change of unknown reach (or logging out) makes it stale. */
    Other,
}

class ApiCache(
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var generation = 0
    private val groupGenerations = IntArray(CacheGroup.entries.size)

    fun entry(query: String, variables: String, token: String?, group: CacheGroup = CacheGroup.Other): File {
        val folder = if (token != null) File(File(root, USER), group.folder) else File(root, PUBLIC)
        return File(folder, sha256("$query\u0000$variables\u0000${token.orEmpty()}") + ".json")
    }

    fun readFresh(entry: File, ttlMillis: Long): String? {
        val modified = entry.lastModified()
        if (modified == 0L || now() - modified >= ttlMillis) return null
        val group = userGroup(entry)
        if (group != null && modified <= changedAt(group)) return null
        return entry.readTextOrNull()
    }

    fun readAny(entry: File): String? = entry.readTextOrNull()

    /** Goes up whenever answers about [group] stop counting as fresh. */
    fun currentGeneration(group: CacheGroup = CacheGroup.Other): Int = synchronized(lock) {
        generation + groupGenerations[group.ordinal]
    }

    fun write(entry: File, startedAt: Int, text: String) = synchronized(lock) {
        val group = userGroup(entry) ?: CacheGroup.Other
        if (startedAt != currentGeneration(group)) return@synchronized
        try {
            entry.writeTextAtomically(text)
            // Written after the last change, so it counts as fresh even within the same millisecond.
            entry.setLastModified(maxOf(now(), changedAt(group) + 1))
        } catch (_: IOException) {
        }
    }

    /**
     * After a change to the account, its answers about [groups] (all of them when null) no longer count as fresh. They
     * are kept, not deleted, so that when AniList can't be reached (offline, or the rate limit) a screen can still show
     * the last copy instead of nothing.
     */
    fun invalidateUser(groups: Set<CacheGroup>? = null) = synchronized(lock) {
        if (groups == null) {
            generation++
            val at = maxOf(now(), changedAt(null) + 1)
            changed[ALL] = at
            save(CHANGED, at)
        } else {
            for (group in groups) {
                groupGenerations[group.ordinal]++
                val at = maxOf(now(), changedAt(group) + 1)
                changed[group.folder] = at
                save("$CHANGED-${group.folder}", at)
            }
        }
        Unit
    }

    /** Deletes everything about the account, for logging out. */
    fun clearUser() = synchronized(lock) {
        invalidateUser()
        File(root, USER).deleteRecursively()
        Unit
    }

    private val changed = HashMap<String, Long>()

    /** When answers about [group] last went stale; kept on disk so it holds after the app restarts. */
    private fun changedAt(group: CacheGroup?): Long = synchronized(lock) {
        val all = stamp(ALL, CHANGED)
        if (group == null) all else maxOf(all, stamp(group.folder, "$CHANGED-${group.folder}"))
    }

    private fun stamp(key: String, file: String): Long =
        changed.getOrPut(key) { File(root, file).readTextOrNull()?.trim()?.toLongOrNull() ?: 0L }

    private fun save(file: String, at: Long) {
        try {
            root.mkdirs()
            File(root, file).writeTextAtomically(at.toString())
        } catch (_: IOException) {
        }
    }

    /** The group of an account answer, or null for a public one. */
    private fun userGroup(entry: File): CacheGroup? {
        val folder = entry.parentFile ?: return null
        if (folder.parentFile?.name != USER) return null
        return CacheGroup.entries.firstOrNull { it.folder == folder.name } ?: CacheGroup.Other
    }

    fun prune(maxAgeMillis: Long = MAX_AGE) = synchronized(lock) {
        root.walkBottomUp()
            .filter { it.isFile && now() - it.lastModified() >= maxAgeMillis }
            .forEach { it.delete() }
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private val CacheGroup.folder get() = name.lowercase()

    private companion object {
        const val PUBLIC = "public"
        const val USER = "user"
        const val CHANGED = "user-changed"
        const val ALL = "\u0000all"
        const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
    }
}
