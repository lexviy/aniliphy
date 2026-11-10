package com.aniliphy.anilist

import kotlinx.serialization.Serializable

enum class MediaKind { Anime, Manga, LightNovel }

object ListStatus {
    const val CURRENT = "CURRENT"
    const val PLANNING = "PLANNING"
    const val COMPLETED = "COMPLETED"
    const val DROPPED = "DROPPED"
    const val PAUSED = "PAUSED"
    const val REPEATING = "REPEATING"

    val ALL = listOf(CURRENT, PLANNING, COMPLETED, DROPPED, PAUSED, REPEATING)

    fun label(status: String?, kind: MediaKind): String = when (status) {
        CURRENT -> if (kind == MediaKind.Anime) "Watching" else "Reading"
        PLANNING -> "Planning"
        COMPLETED -> "Completed"
        DROPPED -> "Dropped"
        PAUSED -> "Paused"
        REPEATING -> if (kind == MediaKind.Anime) "Rewatching" else "Rereading"
        else -> "Not in list"
    }
}

@Serializable
data class MediaTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
) {
    val display: String get() = english.orBlankNull() ?: romaji.orBlankNull() ?: "?"
}

private fun String?.orBlankNull() = this?.takeIf { it.isNotBlank() }

@Serializable
data class CoverImage(
    val extraLarge: String? = null,
    val large: String? = null,
    val medium: String? = null,
) {
    val best: String? get() = extraLarge ?: large ?: medium
}

@Serializable
data class FuzzyDate(val year: Int? = null, val month: Int? = null, val day: Int? = null)

@Serializable
data class NextAiring(val episode: Int? = null, val airingAt: Long? = null)

@Serializable
data class Named(val id: Int = 0, val name: String? = null, val isAnimationStudio: Boolean = false)

@Serializable
data class Nodes<T>(val nodes: List<T> = emptyList())

@Serializable
data class PersonName(
    val full: String? = null,
    val native: String? = null,
    val alternative: List<String> = emptyList(),
    val alternativeSpoiler: List<String> = emptyList(),
)

@Serializable
data class PersonImage(val large: String? = null, val medium: String? = null) {
    val best: String? get() = large ?: medium
}

@Serializable
data class PersonRef(val id: Int = 0, val name: PersonName = PersonName(), val image: PersonImage? = null)

@Serializable
data class StaffEdge(val role: String? = null, val node: PersonRef? = null)

@Serializable
data class VoiceActor(val name: PersonName = PersonName())

@Serializable
data class CharacterEdge(
    val role: String? = null,
    val node: PersonRef? = null,
    val voiceActors: List<VoiceActor> = emptyList(),
)

@Serializable
data class Edges<T>(val edges: List<T> = emptyList())

@Serializable
data class RelationEdge(val relationType: String? = null, val node: Media? = null)

@Serializable
data class Recommendation(val rating: Int? = null, val mediaRecommendation: Media? = null)

@Serializable
data class MediaTag(
    val name: String = "",
    val rank: Int? = null,
    val isMediaSpoiler: Boolean = false,
    val isGeneralSpoiler: Boolean = false,
)

@Serializable
data class Media(
    val id: Int,
    val idMal: Int? = null,
    val type: String? = null,
    val format: String? = null,
    val status: String? = null,
    val countryOfOrigin: String? = null,
    val title: MediaTitle = MediaTitle(),
    val description: String? = null,
    val coverImage: CoverImage = CoverImage(),
    val averageScore: Int? = null,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    val duration: Int? = null,
    val genres: List<String> = emptyList(),
    val tags: List<MediaTag> = emptyList(),
    val siteUrl: String? = null,
    val season: String? = null,
    val seasonYear: Int? = null,
    val source: String? = null,
    val synonyms: List<String> = emptyList(),
    val isFavourite: Boolean = false,
    val startDate: FuzzyDate? = null,
    val endDate: FuzzyDate? = null,
    val nextAiringEpisode: NextAiring? = null,
    val studios: Nodes<Named> = Nodes(),
    val staff: Edges<StaffEdge> = Edges(),
    val characters: Edges<CharacterEdge> = Edges(),
    val relations: Edges<RelationEdge> = Edges(),
    val recommendations: Nodes<Recommendation> = Nodes(),
) {
    val kind: MediaKind
        get() = when {
            type == "ANIME" -> MediaKind.Anime
            format == "NOVEL" -> MediaKind.LightNovel
            type == "MANGA" || format == "MANGA" || format == "ONE_SHOT" -> MediaKind.Manga
            else -> MediaKind.Anime
        }
}

@Serializable
data class ListEntry(
    val status: String? = null,
    val updatedAt: Long? = null,
    val progress: Int = 0,
    val progressVolumes: Int = 0,
    val score: Double = 0.0,
    val repeat: Int = 0,
    val media: Media,
)

fun List<ListEntry>.ofKind(kind: MediaKind) = filter { it.media.kind == kind }

data class ListProgress(
    val progress: Int = 0,
    val progressVolumes: Int = 0,
    val status: String? = null,
    val score: Double? = null,
    val repeat: Int = 0,
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
)

@Serializable
data class SavedEntry(
    val id: Int = 0,
    val status: String? = null,
    val progress: Int = 0,
    val progressVolumes: Int = 0,
    val repeat: Int = 0,
)

@Serializable
data class GenreCount(val genre: String = "", val count: Int = 0)

@Serializable
data class TypeStatistics(
    val count: Int = 0,
    val episodesWatched: Int = 0,
    val chaptersRead: Int = 0,
    val genres: List<GenreCount> = emptyList(),
)

@Serializable
data class UserStatistics(
    val anime: TypeStatistics = TypeStatistics(),
    val manga: TypeStatistics = TypeStatistics(),
)

@Serializable
data class Viewer(
    val id: Int,
    val name: String = "",
    val avatar: PersonImage? = null,
    val statistics: UserStatistics = UserStatistics(),
)

@Serializable
data class Activity(
    val id: Int = 0,
    val status: String? = null,
    val progress: String? = null,
    val createdAt: Long? = null,
    val media: Media? = null,
)

@Serializable
data class StudioStat(val count: Int = 0, val studio: StudioRef? = null)

@Serializable
data class StaffStat(val count: Int = 0, val staff: PersonRef? = null)

@Serializable
data class VoiceActorStat(val count: Int = 0, val voiceActor: PersonRef? = null)

data class Overview(val viewer: Viewer, val activities: List<Activity>)

@Serializable
data class StudioRef(val id: Int = 0, val name: String? = null, val favourites: Int? = null)

@Serializable
data class Favourites(
    val anime: Nodes<Media> = Nodes(),
    val manga: Nodes<Media> = Nodes(),
    val characters: Nodes<PersonRef> = Nodes(),
    val staff: Nodes<PersonRef> = Nodes(),
    val studios: Nodes<StudioRef> = Nodes(),
)

@Serializable
data class CharacterMediaEdge(val characterRole: String? = null, val node: Media? = null)

@Serializable
data class StaffMediaEdge(val staffRole: String? = null, val node: Media? = null)

@Serializable
data class Character(
    val id: Int,
    val isFavourite: Boolean = false,
    val name: PersonName = PersonName(),
    val image: PersonImage? = null,
    val description: String? = null,
    val age: String? = null,
    val gender: String? = null,
    val dateOfBirth: FuzzyDate? = null,
    val bloodType: String? = null,
    val favourites: Int? = null,
    val media: List<CharacterMediaEdge> = emptyList(),
)

@Serializable
data class Staff(
    val id: Int,
    val isFavourite: Boolean = false,
    val name: PersonName = PersonName(),
    val image: PersonImage? = null,
    val description: String? = null,
    val age: Int? = null,
    val gender: String? = null,
    val dateOfBirth: FuzzyDate? = null,
    val bloodType: String? = null,
    val favourites: Int? = null,
    val primaryOccupations: List<String> = emptyList(),
    val yearsActive: List<Int> = emptyList(),
    val homeTown: String? = null,
    val media: List<StaffMediaEdge> = emptyList(),
)

data class StudioPage(val name: String?, val isFavourite: Boolean, val animes: List<Media>, val hasNext: Boolean)

data class Paged<T>(val items: List<T>, val hasNext: Boolean)

data class SearchResults(
    val anime: List<Media> = emptyList(),
    val manga: List<Media> = emptyList(),
    val lightNovels: List<Media> = emptyList(),
    val characters: List<PersonRef> = emptyList(),
    val staff: List<PersonRef> = emptyList(),
    val studios: List<StudioRef> = emptyList(),
)

sealed interface FavouriteTarget {
    val id: Int

    data class Anime(override val id: Int) : FavouriteTarget
    data class Manga(override val id: Int) : FavouriteTarget
    data class Character(override val id: Int) : FavouriteTarget
    data class Staff(override val id: Int) : FavouriteTarget
    data class Studio(override val id: Int) : FavouriteTarget
}
