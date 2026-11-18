package com.aniliphy.anilist

import android.content.Context
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.core.PrefStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

enum class DiscoverFeedKind(val label: String) { Anime("Anime"), Manga("Manga"), Both("Both") }

class DiscoverSettings(context: Context) : PrefStore(context, "discover") {
    var useFollowed by boolPref("use_followed", true)
    var videoOnData by boolPref("video_on_data", false)
    var feed by enumPref("feed", DiscoverFeedKind.Anime, DiscoverFeedKind.entries)

    companion object {
        @Volatile private var instance: DiscoverSettings? = null

        fun get(context: Context): DiscoverSettings = instance ?: synchronized(this) {
            instance ?: DiscoverSettings(context.applicationContext).also { instance = it }
        }
    }
}

/** What Discover keeps on the phone: what it learned and the last suggestions, per kind. */
@Serializable
data class DiscoverSaved(
    val learned: DiscoverLearned = DiscoverLearned(),
    val userId: Int = 0,
    val anime: List<Suggestion> = emptyList(),
    val animeAt: Long = 0,
    val manga: List<Suggestion> = emptyList(),
    val mangaAt: Long = 0,
)

class DiscoverFile(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    @Synchronized
    fun read(): DiscoverSaved = runCatching { json.decodeFromString<DiscoverSaved>(file.readText()) }.getOrDefault(DiscoverSaved())

    @Synchronized
    fun write(saved: DiscoverSaved) = file.writeTextAtomically(json.encodeToString(saved))

    @Synchronized
    fun update(change: (DiscoverSaved) -> DiscoverSaved): DiscoverSaved = change(read()).also(::write)

    companion object {
        const val NAME = "discover.json"
        fun of(context: Context) = DiscoverFile(File(context.filesDir, NAME))
    }
}

/**
 * Builds the suggestions: your list (with genres, tags, studios and staff), AniList's "if you liked X" for the titles
 * you liked most, more of your favourite tags and genres, and what people you follow loved. A handful of requests,
 * then kept for a day.
 */
class DiscoverBuilder(private val api: AniListApi, private val file: DiscoverFile, private val settings: DiscoverSettings) {
    fun saved(): DiscoverSaved = file.read()

    /** The kept suggestions when recent enough, else worked out again. */
    suspend fun suggestions(userId: Int, kind: MediaKind, refresh: Boolean = false): List<Suggestion> = withContext(Dispatchers.IO) {
        val saved = file.read()
        if (!refresh) fresh(saved, userId, kind)?.let { kept -> return@withContext filtered(kept, saved.learned) }
        val built = build(userId, kind, saved.learned, refresh)
        file.update { now ->
            val base = if (now.userId != userId) DiscoverSaved(learned = now.learned, userId = userId) else now
            if (kind == MediaKind.Anime) base.copy(anime = built, animeAt = System.currentTimeMillis()) else base.copy(manga = built, mangaAt = System.currentTimeMillis())
        }
        built
    }

    private suspend fun build(userId: Int, kind: MediaKind, learned: DiscoverLearned, refresh: Boolean): List<Suggestion> {
        val mine = api.taste(userId, kind, refresh).value
        if (mine.size < MIN_LIST) return emptyList()
        val onList = mine.map { it.media.id }.toSet()
        val pool = LinkedHashMap<Int, DiscoverMedia>()
        val links = HashMap<Int, List<Pair<Int, Int>>>()
        Recommender.seeds(mine, SEEDS).chunked(SEEDS_PER_REQUEST).forEach { group ->
            runCatching { api.discoverLinks(group, refresh) }.getOrNull()?.forEach { (seed, found) ->
                found.forEach { (media, _) -> pool.putIfAbsent(media.id, media) }
                links[seed] = found.map { it.first.id to it.second }
            }
        }
        val tags = Recommender.favourites(mine, "t:", 2)
        val genres = Recommender.favourites(mine, "g:", 2)
        if (tags.isNotEmpty()) runCatching { api.discoverPool(kind, null, tags, "SCORE_DESC", refresh) }.getOrNull()?.forEach { pool.putIfAbsent(it.id, it) }
        if (genres.isNotEmpty()) runCatching { api.discoverPool(kind, genres, null, "POPULARITY_DESC", refresh) }.getOrNull()?.forEach { pool.putIfAbsent(it.id, it) }
        val friends = if (settings.useFollowed) followed(userId, kind, refresh) else emptyList()
        val wanted = friends.flatMap { it.loved.keys }.groupingBy { it }.eachCount().entries
            .filter { it.key !in onList && it.key !in pool }
            .sortedByDescending { it.value }
            .take(BY_ID_MAX)
            .map { it.key }
        if (wanted.isNotEmpty()) runCatching { api.discoverByIds(wanted, refresh) }.getOrNull()?.forEach { pool.putIfAbsent(it.id, it) }
        val right = pool.values.filter { kindOf(it) == kind }
        return Recommender.rank(mine, right, links, friends, learned, LIMIT)
    }

    private suspend fun followed(userId: Int, kind: MediaKind, refresh: Boolean): List<FriendTaste> {
        val people = runCatching { api.following(userId, 1, refresh).value.items }.getOrDefault(emptyList()).take(FOLLOWED_MAX)
        return people.mapNotNull { person ->
            runCatching {
                val list = if (kind == MediaKind.Anime) api.animeList(person.id, refresh).value else api.mangaList(person.id, refresh).value
                FriendTaste(person.name, Recommender.loved(list))
            }.getOrNull()
        }
    }

    /** "Not for me": remembered, and the kept suggestions drop it (and what it asked less of). */
    fun learn(change: (DiscoverLearned) -> DiscoverLearned): DiscoverSaved = file.update { it.copy(learned = change(it.learned)) }

    fun forget(): DiscoverSaved = file.update { it.copy(learned = DiscoverLearned(), animeAt = 0, mangaAt = 0) }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val MIN_LIST = 5
        const val SEEDS = 10
        const val SEEDS_PER_REQUEST = 5
        const val FOLLOWED_MAX = 10
        const val BY_ID_MAX = 50
        const val LIMIT = 40

        /** The kept suggestions for [userId] and [kind], when made less than a day ago. */
        fun fresh(saved: DiscoverSaved, userId: Int, kind: MediaKind, now: Long = System.currentTimeMillis()): List<Suggestion>? {
            if (saved.userId != userId) return null
            val (list, at) = if (kind == MediaKind.Anime) saved.anime to saved.animeAt else saved.manga to saved.mangaAt
            return list.takeIf { at > 0 && now - at < DAY_MS }
        }

        fun kindOf(media: DiscoverMedia): MediaKind = if (media.type == "ANIME") MediaKind.Anime else MediaKind.Manga

        /** The kept list without what was turned down since it was made. */
        fun filtered(list: List<Suggestion>, learned: DiscoverLearned): List<Suggestion> = list.filter { s ->
            s.media.id !in learned.hidden &&
                s.media.tags.none { (it.rank ?: 0) >= 40 && it.name in learned.lessTags } &&
                s.media.studios.nodes.none { it.name in learned.lessStudios }
        }
    }
}
