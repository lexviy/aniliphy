package com.aniliphy.sync

import android.content.Context
import com.aniliphy.anime.AnimeHistory
import com.aniliphy.core.PrefStore
import com.aniliphy.manga.MangaHistory
import com.aniliphy.novels.NovelProgressStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SyncSettings private constructor(context: Context) : PrefStore(context, "sync") {
    var token by stringPref("token", "")
    var gistId by stringPref("gist_id", "")
    var onOpen by boolPref("on_open", true)
    var lastSynced by stringPref("last_synced", "")

    val ready: Boolean get() = token.isNotBlank()

    companion object {
        @Volatile private var instance: SyncSettings? = null

        fun get(context: Context): SyncSettings = instance ?: synchronized(this) {
            instance ?: SyncSettings(context.applicationContext).also { instance = it }
        }

        fun forget() {
            instance = null
        }
    }
}

data class SyncResult(
    val gistId: String,
    val anime: Int,
    val manga: Int,
    val novels: Int,
    val sent: Boolean,
    /** Titles where the phone and the computer had different places; the newer was kept. */
    val differences: List<SyncDifference> = emptyList(),
) {
    val changed: Int get() = anime + manga + novels
}

class ProgressSync(
    private val anime: AnimeHistory,
    private val manga: MangaHistory,
    private val novels: NovelProgressStore,
    private val gists: GistStore = GistStore(),
) {
    fun local(): SyncPayload = SyncPayload(
        anime = anime.all()
            .filter { (_, record) -> record.hasPlace || SyncRules.showOf(record.source) != null }
            .map { (id, record) -> id.toString() to SyncRules.anime(record) }.toMap(),
        manga = manga.all()
            .filter { (_, record) -> record.hasPlace || record.source != null }
            .map { (id, record) -> id.toString() to SyncRules.manga(record) }.toMap(),
        novels = novels.raw(),
    )

    fun run(token: String, gistId: String?, importNovels: (String) -> Unit): SyncResult {
        var id = gistId?.takeIf { it.isNotBlank() } ?: gists.find(token)
        val remote = try {
            id?.let { gists.read(token, it) }
        } catch (_: GistMissingException) {
            id = gists.find(token)
            id?.let { gists.read(token, it) }
        } ?: SyncPayload()
        val local = local()
        val merged = SyncRules.merge(local, remote)
        val differences = SyncDifferences.find(local, remote, System.currentTimeMillis())

        var animeChanged = 0
        merged.anime.forEach { (key, synced) ->
            val mediaId = key.toIntOrNull() ?: return@forEach
            if (synced == local.anime[key]) return@forEach
            anime.edit(mediaId) { SyncRules.applyAnime(it, synced) }
            animeChanged++
        }
        var mangaChanged = 0
        merged.manga.forEach { (key, synced) ->
            val mediaId = key.toIntOrNull() ?: return@forEach
            if (synced == local.manga[key]) return@forEach
            manga.edit(mediaId) { SyncRules.applyManga(it, synced) }
            mangaChanged++
        }
        val newNovels = merged.novels.filter { (key, value) -> value != local.novels[key] }
        if (newNovels.isNotEmpty()) importNovels(JsonObject(newNovels).toString())

        val sent = id == null || merged != remote
        val usedId = when {
            id == null -> gists.create(token, merged)
            sent -> id.also { gists.write(token, it, merged) }
            else -> id
        }
        return SyncResult(usedId, animeChanged, mangaChanged, newNovels.size, sent, differences)
    }

    /**
     * Goes back to the place that wasn't kept: it's saved here as just now, so the next sync takes it everywhere.
     */
    fun useOther(difference: SyncDifference, importNovels: (String) -> Unit, now: Double = System.currentTimeMillis() / 1000.0) {
        val json = SyncPayload.json
        when (difference.kind) {
            SyncDifference.Kind.Anime -> {
                val synced = json.decodeFromString<SyncedAnime>(difference.other).copy(updated = now)
                difference.key.toIntOrNull()?.let { id -> anime.edit(id) { SyncRules.applyAnime(it, synced) } }
            }
            SyncDifference.Kind.Manga -> {
                val synced = json.decodeFromString<SyncedManga>(difference.other).copy(updated = now)
                difference.key.toIntOrNull()?.let { id -> manga.edit(id) { SyncRules.applyManga(it, synced) } }
            }
            SyncDifference.Kind.Novel -> {
                val novel = json.parseToJsonElement(difference.other) as? JsonObject ?: return
                importNovels(JsonObject(mapOf(difference.key to JsonObject(novel + ("updated" to JsonPrimitive(now))))).toString())
            }
        }
    }
}
