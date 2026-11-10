package com.aniliphy.anilist

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.math.roundToInt

interface AniListApi {
    suspend fun viewer(token: String): Viewer

    suspend fun overview(userId: Int, refresh: Boolean = false): Fetched<Overview>
    suspend fun animeList(userId: Int, refresh: Boolean = false): Fetched<List<ListEntry>>

    suspend fun mangaList(userId: Int, refresh: Boolean = false): Fetched<List<ListEntry>>
    suspend fun animeDetails(userId: Int, refresh: Boolean = false): Fetched<Map<Int, Media>>
    suspend fun mangaDetails(userId: Int, refresh: Boolean = false): Fetched<Map<Int, Media>>
    suspend fun animePeople(userId: Int, refresh: Boolean = false): Fetched<AnimePeople>
    suspend fun watching(userId: Int, refresh: Boolean = false): Fetched<List<ListEntry>>
    suspend fun reading(userId: Int, refresh: Boolean = false): Fetched<List<ListEntry>>

    suspend fun airingFromList(userId: Int, refresh: Boolean = false): Fetched<List<ListEntry>>
    suspend fun favourites(userId: Int, refresh: Boolean = false): Fetched<Favourites>

    suspend fun season(season: AnimeSeason, page: Int = 1): Fetched<Paged<Media>>
    suspend fun search(text: String, kind: MediaKind, page: Int = 1): Fetched<Paged<Media>>
    suspend fun searchEverything(text: String): Fetched<SearchResults>

    suspend fun characters(text: String? = null, page: Int = 1): Fetched<Paged<PersonRef>>
    suspend fun staffMembers(text: String? = null, page: Int = 1): Fetched<Paged<PersonRef>>
    suspend fun studios(text: String? = null, page: Int = 1): Fetched<Paged<StudioRef>>

    suspend fun details(mediaId: Int): Fetched<Media>
    suspend fun media(mediaId: Int): Fetched<Media>
    suspend fun character(id: Int): Fetched<Character>
    suspend fun staff(id: Int): Fetched<Staff>
    suspend fun studio(id: Int, page: Int = 1): Fetched<StudioPage>

    suspend fun progress(mediaId: Int): ListProgress

    suspend fun toggleFavourite(target: FavouriteTarget)

    suspend fun saveEntry(
        mediaId: Int,
        status: String,
        progress: Int? = null,
        progressVolumes: Int? = null,
        score: Double? = null,
        repeat: Int? = null,
    ): SavedEntry

    suspend fun startMedia(mediaId: Int): SavedEntry?
    suspend fun saveEpisode(mediaId: Int, episode: Int, totalEpisodes: Int?): SavedEntry?
    suspend fun saveChapter(mediaId: Int, chapter: Int? = null, volume: Int? = null): SavedEntry?
    suspend fun completeChapters(mediaId: Int, read: Int): SavedEntry?
}

class GraphQlAniListApi(
    private val client: GraphQlClient,
    private val token: () -> String?,
) : AniListApi {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    override suspend fun viewer(token: String): Viewer =
        decode(client.read(Queries.VIEWER, vars(), token).value["Viewer"])

    override suspend fun overview(userId: Int, refresh: Boolean): Fetched<Overview> =
        account(Queries.OVERVIEW, vars("userId" to userId), refresh).map { data ->
            Overview(
                viewer = decode(data["Viewer"]),
                activities = decodeList<Activity>(data.page()["activities"]).filter { it.media != null },
            )
        }

    override suspend fun animeList(userId: Int, refresh: Boolean) =
        account(Queries.ALL_ANIME, vars("userId" to userId), refresh).map(::entries)

    override suspend fun mangaList(userId: Int, refresh: Boolean) =
        account(Queries.ALL_MANGA, vars("userId" to userId), refresh).map(::entries)

    override suspend fun animeDetails(userId: Int, refresh: Boolean) = details(Queries.ANIME_DETAILS, userId, refresh)

    override suspend fun mangaDetails(userId: Int, refresh: Boolean) = details(Queries.MANGA_DETAILS, userId, refresh)

    private suspend fun details(query: String, userId: Int, refresh: Boolean) =
        account(query, vars("userId" to userId), refresh).map { data -> entries(data).associate { it.media.id to it.media } }

    override suspend fun animePeople(userId: Int, refresh: Boolean) =
        account(Queries.ANIME_PEOPLE, vars("userId" to userId), refresh).map { data ->
            val anime = data["User"]?.objectOrNull()?.get("statistics")?.objectOrNull()?.get("anime")?.objectOrNull()
            AnimePeople(
                studios = decodeList<StudioStat>(anime?.get("studios")).mapNotNull { stat ->
                    stat.studio?.let { RankedPerson(it.id, it.name ?: "?", stat.count) }
                },
                staff = decodeList<StaffStat>(anime?.get("staff")).mapNotNull { stat ->
                    stat.staff?.let { RankedPerson(it.id, it.name.full ?: "?", stat.count) }
                },
                voiceActors = decodeList<VoiceActorStat>(anime?.get("voiceActors")).mapNotNull { stat ->
                    stat.voiceActor?.let { RankedPerson(it.id, it.name.full ?: "?", stat.count) }
                },
            )
        }

    override suspend fun watching(userId: Int, refresh: Boolean) =
        account(Queries.WATCHING, vars("userId" to userId), refresh).map { recentFirst(entries(it)) }

    override suspend fun reading(userId: Int, refresh: Boolean) =
        account(Queries.READING, vars("userId" to userId), refresh).map { recentFirst(entries(it)) }

    override suspend fun airingFromList(userId: Int, refresh: Boolean) =
        account(Queries.AIRING_IN_LIST, vars("userId" to userId), refresh).map { data ->
            entries(data)
                .filter { it.media.nextAiringEpisode?.airingAt != null || it.media.status == "RELEASING" }
                .sortedBy { it.media.nextAiringEpisode?.airingAt ?: Long.MAX_VALUE }
        }

    override suspend fun favourites(userId: Int, refresh: Boolean): Fetched<Favourites> {
        var all = Favourites()
        var stale = false
        var page = 1
        while (true) {
            val fetched = account(Queries.FAVOURITES, vars("userId" to userId, "page" to page), refresh)
            stale = stale || fetched.stale
            val root = fetched.value["User"]?.objectOrNull()?.get("favourites")?.objectOrNull()
            val current = decode<Favourites>(root)
            all = Favourites(
                anime = Nodes(all.anime.nodes + current.anime.nodes),
                manga = Nodes(all.manga.nodes + current.manga.nodes),
                characters = Nodes(all.characters.nodes + current.characters.nodes),
                staff = Nodes(all.staff.nodes + current.staff.nodes),
                studios = Nodes(all.studios.nodes + current.studios.nodes),
            )
            val more = root?.values?.any { it.objectOrNull().hasNext() } == true
            if (!more) return Fetched(all, stale)
            page++
        }
    }

    override suspend fun season(season: AnimeSeason, page: Int) =
        public(Queries.SEASON, vars("season" to season.name, "year" to season.year, "page" to page))
            .map { paged<Media>(it, "media") }

    override suspend fun search(text: String, kind: MediaKind, page: Int): Fetched<Paged<Media>> {
        val type = if (kind == MediaKind.Anime) "ANIME" else "MANGA"
        val formats = when (kind) {
            MediaKind.Anime -> null
            MediaKind.Manga -> listOf("MANGA", "ONE_SHOT")
            MediaKind.LightNovel -> listOf("NOVEL")
        }
        val variables = vars("search" to text, "page" to page, "type" to type, "formats" to formats)
        return public(Queries.MEDIA_SEARCH, variables).map { paged<Media>(it, "media") }
    }

    override suspend fun searchEverything(text: String) =
        public(Queries.SEARCH_EVERYTHING, vars("search" to text)).map { data ->
            SearchResults(
                anime = decodeList(data.group("anime", "media")),
                manga = decodeList(data.group("manga", "media")),
                lightNovels = decodeList(data.group("novels", "media")),
                characters = decodeList(data.group("characters", "characters")),
                staff = decodeList(data.group("staff", "staff")),
                studios = decodeList(data.group("studios", "studios")),
            )
        }

    override suspend fun characters(text: String?, page: Int) =
        public(Queries.CHARACTERS, browseVars(text, page)).map { paged<PersonRef>(it, "characters") }

    override suspend fun staffMembers(text: String?, page: Int) =
        public(Queries.STAFF_LIST, browseVars(text, page)).map { paged<PersonRef>(it, "staff") }

    override suspend fun studios(text: String?, page: Int) =
        public(Queries.STUDIOS, browseVars(text, page)).map { paged<StudioRef>(it, "studios") }

    override suspend fun details(mediaId: Int) =
        personal(Queries.DETAILS, vars("id" to mediaId)).map { decode<Media>(it["Media"]) }

    override suspend fun media(mediaId: Int) =
        public(Queries.MEDIA, vars("id" to mediaId)).map { decode<Media>(it["Media"]) }

    override suspend fun character(id: Int): Fetched<Character> = allPages(
        fetch = { page -> personal(Queries.CHARACTER, vars("id" to id, "page" to page)) },
        field = "Character",
        connection = "media",
    ) { root, edges -> decode<Character>(root).copy(media = decodeList(edges)) }

    override suspend fun staff(id: Int): Fetched<Staff> = allPages(
        fetch = { page -> personal(Queries.STAFF, vars("id" to id, "page" to page)) },
        field = "Staff",
        connection = "staffMedia",
    ) { root, edges -> decode<Staff>(root).copy(media = decodeList(edges)) }

    override suspend fun studio(id: Int, page: Int) =
        personal(Queries.STUDIO, vars("id" to id, "page" to page)).map { data ->
            val studio = data["Studio"]?.objectOrNull() ?: throw NotFoundException()
            val media = studio["media"]?.objectOrNull()
            StudioPage(
                name = (studio["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                isFavourite = (studio["isFavourite"] as? JsonPrimitive)?.booleanOrNull == true,
                animes = decodeList(media?.get("nodes")),
                hasNext = media.hasNext(),
            )
        }

    override suspend fun progress(mediaId: Int): ListProgress {
        val media = decode<ProgressMedia>(
            client.read(Queries.PROGRESS, vars("id" to mediaId), requireToken()).value["Media"],
        )
        val entry = media.mediaListEntry
        return ListProgress(
            progress = entry?.progress ?: 0,
            progressVolumes = entry?.progressVolumes ?: 0,
            status = entry?.status,
            score = entry?.score,
            repeat = entry?.repeat ?: 0,
            episodes = media.episodes,
            chapters = media.chapters,
            volumes = media.volumes,
        )
    }

    override suspend fun toggleFavourite(target: FavouriteTarget) {
        val name = when (target) {
            is FavouriteTarget.Anime -> "animeId"
            is FavouriteTarget.Manga -> "mangaId"
            is FavouriteTarget.Character -> "characterId"
            is FavouriteTarget.Staff -> "staffId"
            is FavouriteTarget.Studio -> "studioId"
        }
        client.mutate(Queries.TOGGLE_FAVOURITE, vars(name to target.id), requireToken())
    }

    override suspend fun saveEntry(
        mediaId: Int,
        status: String,
        progress: Int?,
        progressVolumes: Int?,
        score: Double?,
        repeat: Int?,
    ): SavedEntry = saved(
        client.mutate(
            Queries.SAVE_ENTRY,
            vars(
                "mediaId" to mediaId,
                "status" to status,
                "progress" to progress,
                "progressVolumes" to progressVolumes,
                "scoreRaw" to score?.let { (it * 10).roundToInt() },
                "repeat" to repeat,
            ),
            requireToken(),
        ),
    )

    override suspend fun startMedia(mediaId: Int) =
        apply(mediaId, ProgressRules.start(progress(mediaId)))

    override suspend fun saveEpisode(mediaId: Int, episode: Int, totalEpisodes: Int?) =
        apply(mediaId, ProgressRules.episode(progress(mediaId), episode, totalEpisodes))

    override suspend fun saveChapter(mediaId: Int, chapter: Int?, volume: Int?) =
        apply(mediaId, ProgressRules.chapter(progress(mediaId), chapter, volume))

    override suspend fun completeChapters(mediaId: Int, read: Int) =
        apply(mediaId, ProgressRules.complete(progress(mediaId), read))

    private suspend fun apply(mediaId: Int, change: ProgressChange?): SavedEntry? {
        change ?: return null
        val data = client.mutate(
            Queries.SAVE_PROGRESS,
            vars(
                "mediaId" to mediaId,
                "progress" to change.progress,
                "progressVolumes" to change.progressVolumes,
                "status" to change.status,
                "repeat" to change.repeat,
            ),
            requireToken(),
        )
        return saved(data)
    }

    private fun saved(data: JsonObject): SavedEntry = decode(data["SaveMediaListEntry"])

    private fun requireToken(): String = token() ?: throw NotLoggedInException()

    private suspend fun public(query: String, variables: JsonObject) =
        client.read(query, variables, ttlMillis = PUBLIC_TTL)

    private suspend fun personal(query: String, variables: JsonObject): Fetched<JsonObject> {
        val current = token()
        return client.read(query, variables, current, if (current != null) USER_TTL else PUBLIC_TTL)
    }

    private suspend fun account(query: String, variables: JsonObject, refresh: Boolean) =
        client.read(query, variables, requireToken(), USER_TTL, refresh)

    private fun browseVars(text: String?, page: Int): JsonObject {
        val search = text?.trim()?.takeIf { it.isNotEmpty() }
        return vars(
            "search" to search,
            "page" to page,
            "sort" to listOf(if (search != null) "SEARCH_MATCH" else "FAVOURITES_DESC"),
        )
    }

    private fun entries(data: JsonObject): List<ListEntry> =
        data["MediaListCollection"]?.objectOrNull()?.get("lists")?.jsonArray.orEmpty()
            .flatMap { group -> decodeList<ListEntry>(group.jsonObject["entries"]) }
            .distinctBy { it.media.id }

    private fun recentFirst(entries: List<ListEntry>) = entries.sortedByDescending { it.updatedAt ?: 0 }

    private inline fun <reified T> paged(data: JsonObject, field: String): Paged<T> {
        val page = data.page()
        return Paged(decodeList(page[field]), page.hasNext())
    }

    private fun JsonObject.page(): JsonObject = this["Page"]?.objectOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.group(alias: String, field: String) = this[alias]?.objectOrNull()?.get(field)

    private fun JsonObject?.hasNext(): Boolean {
        val info = this?.get("pageInfo")?.objectOrNull()
        return (info?.get("hasNextPage") as? JsonPrimitive)?.booleanOrNull == true
    }

    private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

    private suspend fun <T> allPages(
        fetch: suspend (Int) -> Fetched<JsonObject>,
        field: String,
        connection: String,
        build: (JsonObject, JsonElement) -> T,
    ): Fetched<T> {
        var first: JsonObject? = null
        val edges = mutableListOf<JsonElement>()
        var stale = false
        var page = 1
        while (true) {
            val fetched = fetch(page)
            stale = stale || fetched.stale
            val current = fetched.value[field]?.objectOrNull() ?: throw NotFoundException()
            if (first == null) first = current
            val media = current[connection]?.objectOrNull()
            edges += media?.get("edges")?.jsonArray.orEmpty()
            if (!media.hasNext()) break
            page++
        }
        val root = JsonObject(first.filterKeys { it != connection })
        return Fetched(build(root, JsonArray(edges)), stale)
    }

    private inline fun <reified T> decode(element: JsonElement?): T {
        if (element == null || element is JsonNull) throw NotFoundException()
        return decodeWith(serializer<T>(), element)
    }

    private inline fun <reified T> decodeList(element: JsonElement?): List<T> {
        if (element == null || element is JsonNull) return emptyList()
        return decodeWith(ListSerializer(serializer<T>()), element)
    }

    private fun <T> decodeWith(strategy: DeserializationStrategy<T>, element: JsonElement): T = try {
        json.decodeFromJsonElement(strategy, element)
    } catch (e: SerializationException) {
        throw ServerException(200, "Unexpected answer from AniList: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw ServerException(200, "Unexpected answer from AniList: ${e.message}")
    }

    @Serializable
    private data class ProgressEntry(
        val progress: Int = 0,
        val progressVolumes: Int = 0,
        val status: String? = null,
        val score: Double? = null,
        val repeat: Int = 0,
    )

    @Serializable
    private data class ProgressMedia(
        val episodes: Int? = null,
        val chapters: Int? = null,
        val volumes: Int? = null,
        val mediaListEntry: ProgressEntry? = null,
    )

    private companion object {
        const val PUBLIC_TTL = 6L * 60 * 60 * 1000
        const val USER_TTL = 10L * 60 * 1000
    }
}

internal fun vars(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((name, value) in pairs) {
        when (value) {
            null -> Unit
            is Int -> put(name, value)
            is String -> put(name, value)
            is List<*> -> put(name, JsonArray(value.map { JsonPrimitive(it as String) }))
            else -> error("Unsupported variable $name")
        }
    }
}
