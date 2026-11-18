package com.aniliphy.mal

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A change waiting to reach MAL. */
@Serializable
sealed class MalWrite {
    abstract val malId: Int
    abstract val anime: Boolean
    abstract val title: String
    abstract val at: Long
    val target: String get() = (if (anime) "anime:" else "manga:") + malId

    @Serializable
    @SerialName("update")
    data class Update(val entry: MalEntry, override val title: String, override val at: Long = System.currentTimeMillis()) : MalWrite() {
        override val malId: Int get() = entry.malId
        override val anime: Boolean get() = entry.anime
    }

    @Serializable
    @SerialName("delete")
    data class Delete(override val malId: Int, override val anime: Boolean, override val title: String, override val at: Long = System.currentTimeMillis()) : MalWrite()
}

/** MAL answered "no" for good (not found, bad value): the write is dropped instead of tried forever. */
class MalRefusedException(message: String) : IOException(message)

/** MAL wants a new login (the refresh token ran out too). */
class MalLoggedOutException : IOException("Log in to MyAnimeList again")

/** What [MalQueue.flush] needs from MAL. */
interface MalWriter {
    suspend fun update(entry: MalEntry)
    suspend fun delete(malId: Int, anime: Boolean)
}

@Serializable
private data class MalQueueFile(val writes: List<MalWrite> = emptyList())

/**
 * Changes for MAL, kept on the phone until they're through. A newer change to the same title replaces the older one,
 * except that an update keeps the score/rewatches the older one had and the newer one leaves alone.
 */
class MalQueue(private val file: File) {
    // Every field is written (the time too), so a write read back is the same write.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private fun read(): MalQueueFile =
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<MalQueueFile>(it) }.getOrNull() } ?: MalQueueFile()

    private fun write(data: MalQueueFile) {
        if (data.writes.isEmpty()) file.delete() else {
            file.parentFile?.mkdirs()
            file.writeTextAtomically(json.encodeToString(data))
        }
    }

    fun all(): List<MalWrite> = synchronized(lock) { read().writes }

    fun add(item: MalWrite) = synchronized(lock) {
        val data = read()
        val old = data.writes.firstOrNull { it.target == item.target }
        val merged = if (old is MalWrite.Update && item is MalWrite.Update) {
            item.copy(
                entry = item.entry.copy(
                    progress = item.entry.progress ?: old.entry.progress,
                    volumes = item.entry.volumes ?: old.entry.volumes,
                    score = item.entry.score ?: old.entry.score,
                    repeat = item.entry.repeat ?: old.entry.repeat,
                    repeating = item.entry.repeating ?: old.entry.repeating,
                ),
            )
        } else {
            item
        }
        write(MalQueueFile(data.writes.filterNot { it.target == item.target } + merged))
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun remove(item: MalWrite) = synchronized(lock) {
        val data = read()
        write(MalQueueFile(data.writes.filterNot { it == item }))
    }

    /** [stoppedBy] is why it stopped early (no network, MAL busy, logged out); null when it got through. */
    data class Sent(val sent: Int, val dropped: List<Pair<MalWrite, String>>, val left: Int, val stoppedBy: IOException? = null)

    /** Sends in order; stops at the first one MAL can't take now (no network, logged out), drops the ones it refuses. */
    suspend fun flush(writer: MalWriter, gapMs: Long = 0): Sent {
        var sent = 0
        var stoppedBy: IOException? = null
        val dropped = ArrayList<Pair<MalWrite, String>>()
        for ((index, item) in all().withIndex()) {
            // A short gap between writes: MAL turns away a quick burst.
            if (index > 0 && gapMs > 0) kotlinx.coroutines.delay(gapMs)
            try {
                when (item) {
                    is MalWrite.Update -> writer.update(item.entry)
                    is MalWrite.Delete -> writer.delete(item.malId, item.anime)
                }
                remove(item)
                sent++
            } catch (e: MalRefusedException) {
                remove(item)
                dropped += item to (e.message ?: "refused")
            } catch (e: IOException) {
                stoppedBy = e
                break
            }
        }
        return Sent(sent, dropped, all().size, stoppedBy)
    }

    companion object {
        const val FILE = "mal_pending.json"
    }
}
