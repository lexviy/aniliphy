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
    /** The cover's main colour as "#rrggbb", when AniList has one. */
    val color: String? = null,
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
    val bannerImage: String? = null,
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
    /** The list entry's own id (not the media's), which deleting needs. */
    val id: Int = 0,
    val status: String? = null,
    val updatedAt: Long? = null,
    /** When it went on your list (seconds). */
    val createdAt: Long? = null,
    val startedAt: FuzzyDate? = null,
    val completedAt: FuzzyDate? = null,
    val progress: Int = 0,
    val progressVolumes: Int = 0,
    val score: Double = 0.0,
    val repeat: Int = 0,
    val notes: String? = null,
    val customLists: List<CustomListFlag> = emptyList(),
    val media: Media,
)

/** One of your own lists and whether the entry is on it. */
@Serializable
data class CustomListFlag(val name: String = "", val enabled: Boolean = false)

/** The names of your own lists, for anime and for manga (light novels share the manga ones). */
data class CustomLists(val anime: List<String> = emptyList(), val manga: List<String> = emptyList()) {
    fun of(kind: MediaKind) = if (kind == MediaKind.Anime) anime else manga
}

fun List<ListEntry>.ofKind(kind: MediaKind) = filter { it.media.kind == kind }

/** The entry as the list editor sees it. */
fun ListEntry.asProgress() = ListProgress(
    entryId = id.takeIf { it > 0 },
    progress = progress,
    progressVolumes = progressVolumes,
    status = status,
    score = score.takeIf { it > 0 },
    repeat = repeat,
    notes = notes,
    customLists = customLists.filter { it.enabled }.map { it.name },
    episodes = media.episodes,
    chapters = media.chapters,
    volumes = media.volumes,
)

data class ListProgress(
    /** The list entry's own id, null when the title isn't on the list. */
    val entryId: Int? = null,
    val progress: Int = 0,
    val progressVolumes: Int = 0,
    val status: String? = null,
    val score: Double? = null,
    val repeat: Int = 0,
    val notes: String? = null,
    /** The names of your own lists the entry is on; null when not known. */
    val customLists: List<String>? = null,
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
    val meanScore: Double = 0.0,
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
    /** "ANIME_LIST", "MANGA_LIST" or "TEXT" (a status post). */
    val type: String? = null,
    val text: String? = null,
    val likeCount: Int = 0,
    val replyCount: Int = 0,
    val isLiked: Boolean = false,
    val user: UserCard? = null,
    /** A message sent to you: its text and who sent it. */
    val message: String? = null,
    val messenger: UserCard? = null,
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

/** One day of AniList's activity history: [date] is that day's midnight (seconds), [amount] how many things you did. */
@Serializable
data class ActivityHistoryDay(val date: Long = 0, val amount: Int = 0, val level: Int = 0)

@Serializable
data class UserStatsOld(val activityHistory: List<ActivityHistoryDay> = emptyList())

/** What the profile card and the activity heatmap need beyond the lists. */
@Serializable
data class ProfileExtras(
    val bannerImage: String? = null,
    val about: String? = null,
    val stats: UserStatsOld = UserStatsOld(),
)

/** A title another one points to, and how ("SEQUEL", "PREQUEL", "SIDE_STORY"...). */
data class MediaLink(val id: Int, val type: String?, val relation: String)

@Serializable
data class LinkNode(val id: Int = 0, val type: String? = null)

@Serializable
data class LinkEdge(val relationType: String? = null, val node: LinkNode? = null) {
    fun toLink(): MediaLink? = node?.takeIf { relationType != null && it.id > 0 }?.let { MediaLink(it.id, it.type, relationType!!) }
}

@Serializable
data class LinkMedia(val id: Int = 0, val relations: Edges<LinkEdge> = Edges())

@Serializable
data class LinkEntry(val media: LinkMedia = LinkMedia())

/** One title of a franchise, with its own relations so the walk can go on from it. */
@Serializable
data class FranchiseNode(
    val id: Int = 0,
    val type: String? = null,
    val format: String? = null,
    val status: String? = null,
    val title: MediaTitle = MediaTitle(),
    val coverImage: CoverImage = CoverImage(),
    val episodes: Int? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    val startDate: FuzzyDate? = null,
    val relations: Edges<LinkEdge> = Edges(),
) {
    val links: List<MediaLink> get() = relations.edges.mapNotNull { it.toLink() }
}

/** Someone on AniList, as a list row shows them. */
@Serializable
data class UserCard(val id: Int = 0, val name: String = "", val avatar: PersonImage? = null)

/** Someone's profile. */
@Serializable
data class UserProfile(
    val id: Int = 0,
    val name: String = "",
    val avatar: PersonImage? = null,
    val bannerImage: String? = null,
    val about: String? = null,
    val isFollowing: Boolean = false,
    val isFollower: Boolean = false,
    val statistics: UserStatistics = UserStatistics(),
    val stats: UserStatsOld = UserStatsOld(),
)

@Serializable
data class ActivityReply(
    val id: Int = 0,
    val text: String? = null,
    val createdAt: Long? = null,
    val likeCount: Int = 0,
    val user: UserCard? = null,
)

/** Someone you follow and where they are with a title. */
@Serializable
data class FriendEntry(
    val status: String? = null,
    val progress: Int = 0,
    val progressVolumes: Int = 0,
    val score: Double = 0.0,
    val user: UserCard = UserCard(),
)

/** Whether a like went on or off, and the count after. */
data class Liked(val isLiked: Boolean, val likeCount: Int)

@Serializable
data class ThreadCategory(val id: Int = 0, val name: String = "")

/** A forum thread. [body] only comes with the thread itself, not in lists. */
@Serializable
data class ForumThread(
    val id: Int = 0,
    val title: String = "",
    val body: String? = null,
    val replyCount: Int = 0,
    val viewCount: Int = 0,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val isSticky: Boolean = false,
    val isLocked: Boolean = false,
    val isSubscribed: Boolean = false,
    val repliedAt: Long? = null,
    val createdAt: Long? = null,
    val user: UserCard? = null,
    val replyUser: UserCard? = null,
    val categories: List<ThreadCategory>? = null,
    val mediaCategories: List<Media>? = null,
)

/** A comment, with the replies under it. */
@Serializable
data class ThreadComment(
    val id: Int = 0,
    val comment: String? = null,
    val likeCount: Int = 0,
    val isLiked: Boolean = false,
    val createdAt: Long? = null,
    val user: UserCard? = null,
    val childComments: List<ThreadComment>? = null,
)

@Serializable
data class ThreadRef(val id: Int = 0, val title: String = "")

/** One AniList notification. Each kind fills only some of these. */
@Serializable
data class AniNotification(
    val id: Int = 0,
    val type: String? = null,
    val createdAt: Long? = null,
    val context: String? = null,
    val contexts: List<String>? = null,
    val episode: Int? = null,
    val activityId: Int? = null,
    val threadId: Int? = null,
    val commentId: Int? = null,
    val reason: String? = null,
    val deletedMediaTitle: String? = null,
    val deletedMediaTitles: List<String>? = null,
    val user: UserCard? = null,
    val media: Media? = null,
    val thread: ThreadRef? = null,
)

/** An episode that airs at [airingAt] (seconds). */
@Serializable
data class AiringSlot(val id: Int = 0, val episode: Int = 0, val airingAt: Long = 0, val media: Media? = null)

