package com.aniliphy.anilist

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A genre or tag in a filter: wanted, or left out. */
@Serializable
enum class Pick { Want, LeaveOut }

/** What the advanced search looks for. Saved as a preset by name. */
@Serializable
data class SearchFilters(
    val kind: MediaKind = MediaKind.Anime,
    val genres: Map<String, Pick> = emptyMap(),
    val tags: Map<String, Pick> = emptyMap(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    /** WINTER, SPRING, SUMMER, FALL (anime only). */
    val season: String? = null,
    val formats: Set<String> = emptySet(),
    val statuses: Set<String> = emptySet(),
    /** JP, KR, CN, TW. */
    val country: String? = null,
    val sort: SearchSort = SearchSort.Popularity,
    val hideMine: Boolean = false,
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && tags.isEmpty() && yearFrom == null && yearTo == null && season == null &&
            formats.isEmpty() && statuses.isEmpty() && country == null && !hideMine

    /** Short labels for what's set, for the chips over the results. */
    fun summary(): List<String> = buildList {
        genres.forEach { (g, p) -> add(if (p == Pick.Want) "+ $g" else "− $g") }
        tags.forEach { (t, p) -> add(if (p == Pick.Want) "+ $t" else "− $t") }
        yearLabel()?.let(::add)
        season?.let { add(seasonLabel(it)) }
        formats.forEach { add(formatLabel(it)) }
        statuses.forEach { add(statusLabel(it)) }
        country?.let { add(countryLabel(it)) }
        if (hideMine) add("Not on my list")
    }

    fun yearLabel(): String? = when {
        yearFrom != null && yearTo != null && yearFrom == yearTo -> "$yearFrom"
        yearFrom != null && yearTo != null -> "$yearFrom–$yearTo"
        yearFrom != null -> "From $yearFrom"
        yearTo != null -> "Up to $yearTo"
        else -> null
    }

    /** The variables for [Queries.BROWSE]. [loggedIn] decides whether "hide mine" can be asked. */
    fun variables(text: String?, page: Int, loggedIn: Boolean): JsonObject = buildJsonObject {
        put("page", page)
        put("type", if (kind == MediaKind.Anime) "ANIME" else "MANGA")
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { put("search", it) }
        fun list(name: String, values: Collection<String>) {
            if (values.isNotEmpty()) put(name, JsonArray(values.map(::JsonPrimitive)))
        }
        list("genreIn", genres.filterValues { it == Pick.Want }.keys)
        list("genreOut", genres.filterValues { it == Pick.LeaveOut }.keys)
        list("tagIn", tags.filterValues { it == Pick.Want }.keys)
        list("tagOut", tags.filterValues { it == Pick.LeaveOut }.keys)
        // FuzzyDateInt: before Jan 1 of the year after, after Dec 31 of the year before.
        yearFrom?.let { put("from", (it - 1) * 10000 + 1231) }
        yearTo?.let { put("to", (it + 1) * 10000 + 101) }
        if (kind == MediaKind.Anime) season?.let { put("season", it) }
        val wantedFormats = formats.ifEmpty {
            when (kind) {
                MediaKind.Anime -> emptySet()
                MediaKind.Manga -> setOf("MANGA", "ONE_SHOT")
                MediaKind.LightNovel -> setOf("NOVEL")
            }
        }
        list("formats", wantedFormats)
        list("statuses", statuses)
        country?.let { put("country", it) }
        val sorts = if (text.isNullOrBlank()) listOf(sort.key) else listOf("SEARCH_MATCH", sort.key)
        put("sort", JsonArray(sorts.map(::JsonPrimitive)))
        if (hideMine && loggedIn) put("onList", false)
    }

    companion object {
        val ANIME_FORMATS = listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
        val MANGA_FORMATS = listOf("MANGA", "ONE_SHOT")
        val STATUSES = listOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "HIATUS", "CANCELLED")
        val SEASONS = listOf("WINTER", "SPRING", "SUMMER", "FALL")
        val COUNTRIES = listOf("JP", "KR", "CN", "TW")

        fun formatLabel(f: String) = when (f) {
            "TV" -> "TV"
            "TV_SHORT" -> "TV short"
            "MOVIE" -> "Movie"
            "SPECIAL" -> "Special"
            "OVA" -> "OVA"
            "ONA" -> "ONA"
            "MUSIC" -> "Music"
            "MANGA" -> "Manga"
            "ONE_SHOT" -> "One-shot"
            "NOVEL" -> "Novel"
            else -> f
        }

        fun statusLabel(s: String) = when (s) {
            "RELEASING" -> "Releasing"
            "FINISHED" -> "Finished"
            "NOT_YET_RELEASED" -> "Not out yet"
            "HIATUS" -> "On hiatus"
            "CANCELLED" -> "Cancelled"
            else -> s
        }

        fun seasonLabel(s: String) = s.lowercase().replaceFirstChar { it.uppercase() }

        fun countryLabel(c: String) = when (c) {
            "JP" -> "Japan"
            "KR" -> "Korea"
            "CN" -> "China"
            "TW" -> "Taiwan"
            else -> c
        }
    }
}

@Serializable
enum class SearchSort(val label: String, val key: String) {
    Popularity("Popular", "POPULARITY_DESC"),
    Score("Score", "SCORE_DESC"),
    Trending("Trending", "TRENDING_DESC"),
    Newest("Newest", "START_DATE_DESC"),
    Favourites("Favourites", "FAVOURITES_DESC"),
    Title("Title", "TITLE_ROMAJI"),
}

/** A filter kept with a name. */
@Serializable
data class SavedFilter(val name: String, val filters: SearchFilters)

@Serializable
data class TagInfo(val name: String = "", val category: String? = null, val isAdult: Boolean = false)

/** Genres and the tags AniList knows (adult tags left out). */
data class FilterChoices(val genres: List<String>, val tags: List<TagInfo>)

data class BrowsePage(val items: List<Media>, val hasNext: Boolean, val total: Int?)

@Serializable
data class Trailer(val id: String? = null, val site: String? = null, val thumbnail: String? = null) {
    /** The address to open, or null for a site this doesn't know. */
    val url: String?
        get() = when {
            id.isNullOrBlank() -> null
            site.equals("youtube", true) -> "https://www.youtube.com/watch?v=$id"
            site.equals("dailymotion", true) -> "https://www.dailymotion.com/video/$id"
            else -> null
        }
}

@Serializable
data class ExternalLink(
    val url: String = "",
    val site: String = "",
    /** INFO, STREAMING, SOCIAL. */
    val type: String? = null,
    val color: String? = null,
    val language: String? = null,
)

@Serializable
data class StreamingEpisode(val title: String? = null, val url: String? = null, val site: String? = null)

@Serializable
data class ReviewUser(val name: String = "")

@Serializable
data class Review(
    val id: Int = 0,
    val summary: String? = null,
    val body: String? = null,
    val score: Int? = null,
    val rating: Int? = null,
    val ratingAmount: Int? = null,
    val user: ReviewUser = ReviewUser(),
)

/** What the title page loads below the details: trailer, links and the top reviews. */
data class MediaExtras(
    val trailer: Trailer?,
    val links: List<ExternalLink>,
    val episodes: List<StreamingEpisode>,
    val reviews: List<Review>,
    val moreReviews: Boolean,
) {
    /** Streaming sites first, then the rest; same-site duplicates (other languages) once. */
    val sortedLinks: List<ExternalLink>
        // Streaming sites once per language; the rest (socials in several languages) once.
        get() = links.distinctBy { if (it.type == "STREAMING") it.site + it.language.orEmpty() else it.site }
            .sortedWith(compareBy({ if (it.type == "STREAMING") 0 else if (it.type == "INFO") 1 else 2 }, { it.site.lowercase() }))
}

/** Splits AniList review/comment text into plain parts and ~!spoiler!~ parts. */
object SpoilerText {
    data class Part(val text: String, val spoiler: Boolean)

    private val marker = Regex("""~!(.*?)!~""", RegexOption.DOT_MATCHES_ALL)

    fun parts(text: String): List<Part> {
        val out = mutableListOf<Part>()
        var at = 0
        marker.findAll(text).forEach { m ->
            if (m.range.first > at) out += Part(text.substring(at, m.range.first), false)
            out += Part(m.groupValues[1], true)
            at = m.range.last + 1
        }
        if (at < text.length) out += Part(text.substring(at), false)
        return out.filter { it.text.isNotEmpty() }
    }

    /** Plain enough to read: images, html tags, markdown marks and AniList's own __bold__ / ~~~center~~~ stripped. */
    fun clean(text: String): String = text
        .replace(Regex("""(?i)img\d*%?\((.*?)\)"""), "")
        .replace(Regex("""(?i)(youtube|webm)\((.*?)\)"""), "")
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""<[^>]+>"""), "")
        .replace(Regex("""\[([^\]]*)]\((https?://[^)]+)\)"""), "$1")
        .replace("~~~", "")
        .replace("__", "")
        .replace(Regex("""(?<![\w*])[_*]([^_*\n]+)[_*](?![\w*])"""), "$1")
        .replace(Regex("""(?m)^#+\s*"""), "")
        .replace(Regex("""\*\*|(?<![~])~~(?![~])"""), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}

/** The text of a post to the feed, with the title attached when it is. */
object FeedPost {
    const val MAX = 2000

    fun text(body: String, attached: Attached?): String {
        val main = body.trim()
        val tail = attached?.let { "\n\n${it.line()}" }.orEmpty()
        return (main + tail).trim()
    }

    data class Attached(val mediaId: Int, val title: String, val anime: Boolean, val status: String?, val progress: Int?) {
        fun line(): String {
            val where = when {
                progress != null && progress > 0 -> (if (anime) "episode " else "chapter ") + progress
                else -> null
            }
            val doing = when (status) {
                "CURRENT" -> if (anime) "Watching" else "Reading"
                "REPEATING" -> if (anime) "Rewatching" else "Rereading"
                "COMPLETED" -> "Finished"
                "PAUSED" -> "Paused"
                "PLANNING" -> "Planning"
                "DROPPED" -> "Dropped"
                else -> null
            }
            val about = listOfNotNull(doing, title, where).joinToString(" · ")
            return "— $about\nhttps://anilist.co/${if (anime) "anime" else "manga"}/$mediaId/"
        }
    }
}
