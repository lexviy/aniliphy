package com.aniliphy.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A title where this phone and the computer had different places at the last sync. The newer one was kept, as
 * always; this keeps the other so you can go back to it.
 */
@Serializable
data class SyncDifference(
    val kind: Kind,
    val key: String,
    val title: String,
    val phone: String,
    val computer: String,
    /** True when the computer's place was kept, so this phone's old one is [other]. */
    val keptComputer: Boolean,
    /** The place that wasn't kept, as it is in the sync file. */
    val other: String,
    val at: Long,
) {
    enum class Kind { Anime, Manga, Novel }
}

object SyncDifferences {
    const val KEEP = 30

    /** The titles where [local] and [remote] disagree on the place, not only on when it was saved. */
    fun find(local: SyncPayload, remote: SyncPayload, now: Long): List<SyncDifference> {
        val json = SyncPayload.json
        val found = mutableListOf<SyncDifference>()
        fun add(kind: SyncDifference.Kind, key: String, title: String?, phone: String, computer: String, keptComputer: Boolean, other: String) {
            if (phone != computer) found += SyncDifference(kind, key, title ?: "?", phone, computer, keptComputer, other, now)
        }
        local.anime.forEach { (key, l) ->
            val r = remote.anime[key] ?: return@forEach
            val keptComputer = r.updated > l.updated
            add(SyncDifference.Kind.Anime, key, l.title ?: r.title, describe(l), describe(r), keptComputer, json.encodeToString(if (keptComputer) l else r))
        }
        local.manga.forEach { (key, l) ->
            val r = remote.manga[key] ?: return@forEach
            val keptComputer = r.updated > l.updated
            add(SyncDifference.Kind.Manga, key, l.title ?: r.title, describe(l), describe(r), keptComputer, json.encodeToString(if (keptComputer) l else r))
        }
        local.novels.forEach { (key, l) ->
            val r = remote.novels[key] ?: return@forEach
            val keptComputer = updated(r) > updated(l)
            add(SyncDifference.Kind.Novel, key, text(l, "title") ?: text(r, "title"), describe(l), describe(r), keptComputer, (if (keptComputer) l else r).toString())
        }
        return found
    }

    /** Newest first, a difference per title, at most [KEEP]. */
    fun remember(old: List<SyncDifference>, found: List<SyncDifference>): List<SyncDifference> =
        (found + old).distinctBy { it.kind to it.key }.sortedByDescending { it.at }.take(KEEP)

    fun describe(anime: SyncedAnime): String {
        val episode = anime.episode ?: return "Not started"
        val number = if (episode % 1.0 == 0.0) episode.toLong().toString() else episode.toString()
        val at = anime.position?.takeIf { it > 0 }?.let { " · " + clock(it) }.orEmpty()
        return "Ep $number$at"
    }

    fun describe(manga: SyncedManga): String {
        val chapter = manga.number?.let { if (it % 1.0 == 0.0) "Ch ${it.toLong()}" else "Ch $it" } ?: manga.chapter ?: return "Not started"
        return if (manga.page > 0) "$chapter · p. ${manga.page + 1}" else chapter
    }

    fun describe(novel: JsonObject): String {
        val last = text(novel, "last") ?: return "Not started"
        val chapter = (novel["books"] as? JsonObject)?.get(last)?.jsonObject?.get("chapter")?.jsonPrimitive?.intOrNull
        return last.substringBeforeLast('.') + (chapter?.let { " · chapter ${it + 1}" } ?: "")
    }

    private fun updated(novel: JsonObject) = (novel["updated"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0

    private fun text(novel: JsonObject, name: String) = (novel[name] as? JsonPrimitive)?.contentOrNull

    private fun clock(seconds: Double): String {
        val total = seconds.toLong()
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
