package com.aniliphy.anilist

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Something saved on this phone while AniList couldn't be reached, sent in order once it can be.
 * (Episodes and chapters watched or read have their own queue, which only ever goes up: PendingProgress.)
 */
@Serializable
sealed class PendingWrite {
    /** When it was made (ms). */
    abstract val at: Long

    /** Writes to the same thing: a newer one replaces the older. */
    abstract val target: String

    /** A list entry: status, progress, score, rewatches, notes, your own lists ("+ Planning" too). */
    @Serializable
    @SerialName("entry")
    data class Entry(
        val mediaId: Int,
        val title: String,
        val anime: Boolean,
        val status: String,
        val progress: Int? = null,
        val volumes: Int? = null,
        val score: Double? = null,
        val repeat: Int? = null,
        val notes: String? = null,
        val customLists: List<String>? = null,
        /** The entry's last change on AniList when it was edited here (seconds); a newer one there is a conflict. */
        val seenUpdatedAt: Long? = null,
        /** What AniList had when this was edited, to show next to it if there's a conflict. */
        val before: ListProgress? = null,
        override val at: Long = System.currentTimeMillis(),
    ) : PendingWrite() {
        override val target: String get() = "entry:$mediaId"
    }

    /** A like on an activity, a forum thread or a forum comment, as it should end up. */
    @Serializable
    @SerialName("like")
    data class Like(
        val id: Int,
        val liked: Boolean,
        /** Whose post, or the thread's title: for the list of what's waiting. */
        val about: String,
        val kind: LikeKind = LikeKind.Activity,
        override val at: Long = System.currentTimeMillis(),
    ) : PendingWrite() {
        override val target: String get() = "like:${kind.name}:$id"
    }

    /** A reply to an activity, or a comment in a forum thread ([parentId] when it answers a comment). */
    @Serializable
    @SerialName("reply")
    data class Reply(
        val id: Int,
        val text: String,
        val about: String,
        val forum: Boolean = false,
        val parentId: Int? = null,
        override val at: Long = System.currentTimeMillis(),
    ) : PendingWrite() {
        // Every reply is sent; none replaces another.
        override val target: String get() = "reply:$at:${text.hashCode()}"
    }
}

@Serializable
enum class LikeKind { Activity, Thread, Comment }

/** An entry edited here that also changed on AniList before it could be sent. */
@Serializable
data class WriteConflict(val mine: PendingWrite.Entry, val theirs: ListProgress)

@Serializable
private data class PendingFile(val writes: List<PendingWrite> = emptyList(), val conflicts: List<WriteConflict> = emptyList())

class PendingWrites(private val file: File) {
    // Every field is written (the time too): with defaults left out, a write saved in the same millisecond it was made
    // came back with a new time, and was never taken off the queue.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private fun read(): PendingFile =
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<PendingFile>(it) }.getOrNull() } ?: PendingFile()

    private fun write(data: PendingFile) {
        if (data.writes.isEmpty() && data.conflicts.isEmpty()) file.delete()
        else {
            file.parentFile?.mkdirs()
            file.writeTextAtomically(json.encodeToString(data))
        }
    }

    fun all(): List<PendingWrite> = synchronized(lock) { read().writes }

    fun conflicts(): List<WriteConflict> = synchronized(lock) { read().conflicts }

    /** Queues [item]. A newer edit of the same entry keeps the first edit's view of AniList, so a conflict still shows. */
    fun add(item: PendingWrite) = synchronized(lock) {
        val data = read()
        val old = data.writes.firstOrNull { it.target == item.target }
        val merged = if (old is PendingWrite.Entry && item is PendingWrite.Entry) {
            item.copy(seenUpdatedAt = old.seenUpdatedAt ?: item.seenUpdatedAt, before = old.before ?: item.before)
        } else {
            item
        }
        // An entry edited again drops a conflict it had: the newer edit is the answer.
        write(data.copy(writes = data.writes.filterNot { it.target == item.target } + merged, conflicts = data.conflicts.filterNot { it.mine.target == item.target }))
    }

    fun remove(item: PendingWrite) = synchronized(lock) {
        val data = read()
        write(data.copy(writes = data.writes.filterNot { it == item }))
    }

    /** "Keep mine": back in the queue, sent without asking again. "Keep AniList's": dropped. */
    fun settle(conflict: WriteConflict, keepMine: Boolean) = synchronized(lock) {
        val data = read()
        val writes = if (keepMine) data.writes + conflict.mine.copy(seenUpdatedAt = null) else data.writes
        write(data.copy(writes = writes, conflicts = data.conflicts.filterNot { it.mine.target == conflict.mine.target }))
    }

    private fun moveToConflicts(item: PendingWrite.Entry, theirs: ListProgress) = synchronized(lock) {
        val data = read()
        write(data.copy(writes = data.writes.filterNot { it == item }, conflicts = data.conflicts.filterNot { it.mine.target == item.target } + WriteConflict(item, theirs)))
    }

    /** What happened in one [flush]. */
    data class Sent(val sent: List<PendingWrite>, val conflicts: List<WriteConflict>, val dropped: List<PendingWrite>, val left: Int)

    /**
     * Sends everything in order. Stops at the first write AniList can't take right now (offline, logged out), so the
     * order is kept; a write AniList refuses for good (the post was deleted, say) is dropped.
     */
    suspend fun flush(api: AniListApi): Sent {
        // Waits while incognito is on, rather than sending what was saved before it.
        if (com.aniliphy.core.Incognito.on) return Sent(emptyList(), emptyList(), emptyList(), all().size)
        val sent = mutableListOf<PendingWrite>()
        val conflicts = mutableListOf<WriteConflict>()
        val dropped = mutableListOf<PendingWrite>()
        for (item in all()) {
            try {
                when (item) {
                    is PendingWrite.Entry -> {
                        val theirs = api.progress(item.mediaId)
                        if (isConflict(item, theirs)) {
                            moveToConflicts(item, theirs)
                            conflicts += WriteConflict(item, theirs)
                            continue
                        }
                        api.saveEntry(item.mediaId, item.status, item.progress, item.volumes, item.score, item.repeat, item.notes, item.customLists)
                    }
                    is PendingWrite.Like -> {
                        val toggle: suspend () -> Liked = when (item.kind) {
                            LikeKind.Activity -> { { api.toggleLike(item.id) } }
                            LikeKind.Thread -> { { api.toggleThreadLike(item.id, comment = false) } }
                            LikeKind.Comment -> { { api.toggleThreadLike(item.id, comment = true) } }
                        }
                        // A toggle: when it lands on the other side, it was already that way, so toggle back.
                        if (toggle().isLiked != item.liked) toggle()
                    }
                    is PendingWrite.Reply ->
                        if (item.forum) api.comment(item.id, item.text, item.parentId) else api.reply(item.id, item.text)
                }
                remove(item)
                sent += item
            } catch (e: AniListException) {
                if (e.temporary || e is InvalidTokenException || e is NotLoggedInException || e is IncognitoBlocked) break
                remove(item)
                dropped += item
            }
        }
        return Sent(sent, conflicts, dropped, all().size)
    }

    companion object {
        const val FILE = "anilist_pending.json"

        /** What a flush did, for a toast; null when nothing happened. */
        fun describe(result: Sent): String? = buildList {
            if (result.sent.isNotEmpty()) add(if (result.sent.size == 1) "Sent to AniList" else "${result.sent.size} changes sent to AniList")
            if (result.conflicts.isNotEmpty()) add("${result.conflicts.size} changed on AniList too: check Settings › Activity")
            if (result.dropped.isNotEmpty()) add("${result.dropped.size} AniList wouldn't take")
        }.takeIf { it.isNotEmpty() }?.joinToString(" · ")

        /** Changed on AniList after it was edited here, and not to the same values. */
        fun isConflict(mine: PendingWrite.Entry, theirs: ListProgress): Boolean {
            val seen = mine.seenUpdatedAt ?: return false
            val changed = theirs.updatedAt ?: return false
            if (changed <= seen) return false
            val same = theirs.status == mine.status && (mine.progress == null || theirs.progress == mine.progress) &&
                (mine.score == null || (theirs.score ?: 0.0) == mine.score)
            return !same
        }
    }
}
