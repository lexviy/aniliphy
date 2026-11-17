package com.aniliphy.anime

import com.aniliphy.core.Web
import com.aniliphy.core.WebCall
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** What an episode is to the story, as Anime Filler List sorts them. */
@Serializable
enum class EpisodeKind(val label: String, val short: String) {
    MangaCanon("From the manga", "Canon"),
    AnimeCanon("Anime-only, but part of the story", "Anime canon"),
    Mixed("Part story, part filler", "Mixed"),
    Filler("Filler", "Filler"),
    ;

    /** Worth skipping when skipping fillers: whole fillers only, mixed ones still move the story. */
    val skippable: Boolean get() = this == Filler
}

/** A show on Anime Filler List: its address there and the names it goes by. */
@Serializable
data class FillerShow(val slug: String, val names: List<String>)

/** One episode of a show's filler list. */
@Serializable
data class FillerEpisode(val number: Int, val kind: EpisodeKind, val title: String? = null, val aired: String? = null)

/** Where a title's episodes sit in a show's filler list: its episode 1 is the list's episode [offset] + 1. */
@Serializable
data class FillerLink(val slug: String, val name: String, val offset: Int, val byHand: Boolean = false)

/** A title's fillers, numbered as the title numbers its own episodes. */
data class TitleFillers(val link: FillerLink, val episodes: Map<Int, FillerEpisode>) {
    fun kindOf(episode: Int): EpisodeKind? = episodes[episode]?.kind

    val fillerCount: Int get() = episodes.values.count { it.kind == EpisodeKind.Filler }
    val mixedCount: Int get() = episodes.values.count { it.kind == EpisodeKind.Mixed }

    /** "57–71, 91–112": the runs of episodes of [kind]. */
    fun runs(kind: EpisodeKind): List<IntRange> = Fillers.runs(episodes.values.filter { it.kind == kind }.map { it.number })

    /** The next episode after [after] that isn't a whole filler, or null when there's none known. */
    fun nextStory(after: Int): Int? {
        val last = episodes.keys.maxOrNull() ?: return null
        return ((after + 1)..last).firstOrNull { episodes[it]?.kind?.skippable != true }
    }
}

/** Which titles skip their fillers when played, kept between launches. */
object FillerPrefs {
    private const val FILE = "fillers"

    fun skips(context: android.content.Context, mediaId: Int): Boolean =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getBoolean("skip_$mediaId", false)

    fun setSkips(context: android.content.Context, mediaId: Int, skip: Boolean) {
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putBoolean("skip_$mediaId", skip).apply()
    }
}

/** What's known about one title when matching it to Anime Filler List. */
data class FillerTitle(
    val mediaId: Int,
    val titles: List<String>,
    val format: String?,
    val episodes: Int?,
    val start: LocalDate?,
)

/**
 * Filler lists from animefillerlist.com, which has no API and no AniList ids: shows are matched by name, and a title
 * AniList splits from a long show (Fairy Tail 2014, Gintama°) finds its place in the show's numbering by air dates.
 */
class Fillers(private val folder: File?, private val web: WebCall = Web) {
    @Serializable
    private data class CachedShows(val at: Long, val shows: List<FillerShow>)

    @Serializable
    private data class CachedList(val at: Long, val episodes: List<FillerEpisode>)

    @Serializable
    private data class Links(val links: Map<Int, FillerLink?> = emptyMap(), val at: Map<Int, Long> = emptyMap())

    private val showsFile get() = folder?.let { File(it, "shows.json") }
    private val linksFile get() = folder?.let { File(it, "links.json") }
    private fun listFile(slug: String) = folder?.let { File(it, "list-${slug.filter { c -> c.isLetterOrDigit() || c == '-' }}.json") }

    private val lists = HashMap<String, CachedList>()
    private var shows: CachedShows? = null
    private val links: MutableMap<Int, FillerLink?> = mutableMapOf()
    private val linkedAt: MutableMap<Int, Long> = mutableMapOf()
    private var linksRead = false

    @Throws(IOException::class)
    fun shows(now: Long = System.currentTimeMillis()): List<FillerShow> = synchronized(this) {
        val kept = shows ?: showsFile?.readTextOrNull()?.let { runCatching { JSON.decodeFromString<CachedShows>(it) }.getOrNull() }
        if (kept != null && now - kept.at < SHOWS_KEEP_MS) return kept.shows.also { shows = kept }
        val fresh = try {
            parseShows(fetch(SHOWS_URL))
        } catch (e: IOException) {
            if (kept != null) return kept.shows
            throw e
        }
        if (fresh.isEmpty()) return kept?.shows.orEmpty()
        CachedShows(now, fresh).also {
            shows = it
            showsFile?.writeTextAtomically(JSON.encodeToString(it))
        }.shows
    }

    @Throws(IOException::class)
    fun episodes(slug: String, now: Long = System.currentTimeMillis()): List<FillerEpisode> = synchronized(this) {
        val kept = lists[slug] ?: listFile(slug)?.readTextOrNull()?.let { runCatching { JSON.decodeFromString<CachedList>(it) }.getOrNull() }
        if (kept != null && now - kept.at < LIST_KEEP_MS) return kept.episodes.also { lists[slug] = kept }
        val fresh = try {
            parseEpisodes(fetch("$SHOW_URL$slug"))
        } catch (e: IOException) {
            if (kept != null) return kept.episodes
            throw e
        }
        CachedList(now, fresh).also {
            lists[slug] = it
            listFile(slug)?.writeTextAtomically(JSON.encodeToString(it))
        }.episodes
    }

    private fun readLinks() {
        if (linksRead) return
        linksRead = true
        linksFile?.readTextOrNull()?.let { runCatching { JSON.decodeFromString<Links>(it) }.getOrNull() }?.let {
            links.putAll(it.links)
            linkedAt.putAll(it.at)
        }
    }

    private fun saveLinks() {
        linksFile?.writeTextAtomically(JSON.encodeToString(Links(links.toMap(), linkedAt.toMap())))
    }

    /** Sets by hand which show (and so which numbering) a title follows; null [slug] says it has no filler list. */
    @Throws(IOException::class)
    fun pick(title: FillerTitle, slug: String?) = synchronized(this) {
        readLinks()
        val link = slug?.let { s ->
            val show = shows().firstOrNull { it.slug == s }
            val list = episodes(s)
            FillerLink(s, show?.names?.firstOrNull() ?: s, offsetByDate(title, list) ?: 0, byHand = true)
        }
        links[title.mediaId] = link ?: FillerLink("", "", 0, byHand = true)
        linkedAt[title.mediaId] = System.currentTimeMillis()
        saveLinks()
    }

    /**
     * The fillers of [title], or null when it has none known (a movie, a show Anime Filler List doesn't have, or one
     * that can't be placed). Each match is remembered, and a title with none is asked about again after a while.
     */
    @Throws(IOException::class)
    fun forTitle(title: FillerTitle, now: Long = System.currentTimeMillis()): TitleFillers? = synchronized(this) {
        if (title.format != null && title.format !in EPISODIC) return null
        readLinks()
        // A match found is kept; "none found" is asked again after a while, in case the site adds the show.
        val known = title.mediaId in links && (links[title.mediaId] != null || now - (linkedAt[title.mediaId] ?: 0) < NO_MATCH_KEEP_MS)
        val link = if (known) {
            links[title.mediaId]
        } else {
            val found = match(title, shows())?.let { (show, exact) ->
                val list = episodes(show.slug)
                place(title, show, list, exact)
            }
            // Without a start date a title split from a longer show can't be placed: "none" is only kept when it had one.
            if (found != null || title.start != null) {
                links[title.mediaId] = found
                linkedAt[title.mediaId] = now
                saveLinks()
            }
            found
        }
        return withLink(link, title.episodes)
    }

    /** The fillers of a title already matched (on its series page, or when Watch was pressed); never matches anew. */
    fun known(mediaId: Int, episodes: Int?): TitleFillers? = synchronized(this) {
        readLinks()
        withLink(links[mediaId], episodes)
    }

    private fun withLink(link: FillerLink?, count: Int?): TitleFillers? {
        if (link == null || link.slug.isEmpty()) return null
        val list = episodes(link.slug)
        val mine = list.mapNotNull { e -> (e.number - link.offset).takeIf { it >= 1 && (count == null || it <= count) }?.let { it to e } }
            .associate { (n, e) -> n to e.copy(number = n) }
        if (mine.isEmpty()) return null
        return TitleFillers(link, mine)
    }

    private fun fetch(url: String): String {
        val reply = web.get(url, mapOf("Accept" to "text/html"))
        if (!reply.ok) throw IOException("Anime Filler List answered ${reply.code}")
        return reply.text
    }

    companion object {
        @Volatile
        private var shared: Fillers? = null

        /** The app's one, keeping what it found in the app's files. */
        fun shared(context: android.content.Context): Fillers = shared ?: synchronized(this) {
            shared ?: Fillers(File(context.filesDir, "fillers").apply { mkdirs() }).also { shared = it }
        }

        const val SHOWS_URL = "https://www.animefillerlist.com/shows"
        const val SHOW_URL = "https://www.animefillerlist.com/shows/"
        private const val SHOWS_KEEP_MS = 30L * 24 * 60 * 60 * 1000
        private const val LIST_KEEP_MS = 7L * 24 * 60 * 60 * 1000
        private const val NO_MATCH_KEEP_MS = 14L * 24 * 60 * 60 * 1000

        /** Only shows told in episodes have filler lists; a movie or a music video doesn't. */
        val EPISODIC = setOf("TV", "TV_SHORT", "ONA")

        /** Words that only say which part of a show a title is, after the show's own name. */
        private val PART_WORDS = setOf(
            "season", "series", "part", "cour", "final", "the", "new", "2nd", "3rd", "4th", "5th", "6th", "7th",
            "ii", "iii", "iv", "v", "tv", "arc", "hen", "shou", "dai", "ki", "sono",
        )

        private val JSON = Json { ignoreUnknownKeys = true }

        fun parseShows(html: String): List<FillerShow> {
            val doc = Jsoup.parse(html)
            return doc.select("a[href^=/shows/]").mapNotNull { a ->
                val slug = a.attr("href").removePrefix("/shows/").substringBefore('/').substringBefore('?')
                val text = a.text().trim()
                if (slug.isBlank() || text.isBlank()) return@mapNotNull null
                // "Films", "OVAs" and "Specials" pages list films, not numbered episodes (and some link to the wrong place).
                if (Regex("\\b(films?|ovas?|specials?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text.substringBefore('('))) return@mapNotNull null
                val main = text.substringBefore(" (").trim()
                val other = Regex("\\(([^)]*)\\)\\s*$").find(text)?.groupValues?.get(1)?.trim()
                FillerShow(java.net.URLDecoder.decode(slug, "UTF-8"), listOfNotNull(main, other).distinct())
            }.distinctBy { it.slug }
        }

        fun parseEpisodes(html: String): List<FillerEpisode> {
            val doc = Jsoup.parse(html)
            return doc.select("table.EpisodeList tr[id^=eps-]").mapNotNull { row ->
                val number = row.id().removePrefix("eps-").toIntOrNull() ?: row.selectFirst("td.Number")?.text()?.trim()?.toIntOrNull()
                    ?: return@mapNotNull null
                val classes = row.className().lowercase()
                val type = row.selectFirst("td.Type")?.text()?.lowercase().orEmpty()
                val kind = when {
                    "mixed" in classes || "mixed" in type -> EpisodeKind.Mixed
                    "filler" in classes || "filler" in type -> EpisodeKind.Filler
                    "anime_canon" in classes || "anime canon" in type -> EpisodeKind.AnimeCanon
                    else -> EpisodeKind.MangaCanon
                }
                FillerEpisode(
                    number = number,
                    kind = kind,
                    title = row.selectFirst("td.Title")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                    aired = row.selectFirst("td.Date")?.text()?.trim()?.takeIf { it.isNotEmpty() },
                )
            }.distinctBy { it.number }.sortedBy { it.number }
        }

        /** Lower case, accents and punctuation gone, "shippuuden" and "shippuden" alike. */
        fun normalize(name: String): String {
            val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return plain.lowercase()
                .replace("&", " and ")
                .replace(Regex("[^a-z0-9]+"), " ")
                .replace(Regex("(?<=[a-z])uu"), "u")
                .replace(Regex("(?<=[a-z])ou(?=[^a-z]|$)"), "o")
                .trim()
                .replace(Regex("\\s+"), " ")
        }

        /**
         * The show [title] belongs to, and whether the name matched exactly. Otherwise a show whose name starts the
         * title's, when what's left only says which season or part it is ("Fairy Tail (2014)", "Fairy Tail Final Series").
         */
        fun match(title: FillerTitle, shows: List<FillerShow>): Pair<FillerShow, Boolean>? {
            val names = title.titles.map(::normalize).filter { it.isNotEmpty() }.distinct()
            shows.firstOrNull { show -> show.names.any { normalize(it) in names } }?.let { return it to true }
            return shows.mapNotNull { show ->
                val best = show.names.map(::normalize).filter { it.length >= 4 }.mapNotNull { name ->
                    names.firstOrNull { title ->
                        title.startsWith("$name ") && title.removePrefix("$name ").split(' ').all { it in PART_WORDS || it.all(Char::isDigit) }
                    }?.let { name.length }
                }.maxOrNull()
                best?.let { show to it }
            }.maxByOrNull { it.second }?.first?.let { it to false }
        }

        /**
         * Where [title] starts in [list]: by the air date of its first episode when the list has dates (needed for a
         * title split from a longer show), else at the start for an exact name match. Null when it can't be placed.
         */
        fun place(title: FillerTitle, show: FillerShow, list: List<FillerEpisode>, exact: Boolean): FillerLink? {
            if (list.isEmpty()) return null
            val offset = offsetByDate(title, list)
                ?: (if (exact && (title.start == null || list.none { it.aired != null })) 0 else null)
                ?: return null
            // A title placed past the end of the list, or much longer than what's left of it, isn't this show.
            val left = list.count { it.number > offset }
            val episodes = title.episodes
            if (left == 0 || (episodes != null && !exact && episodes > left + 2)) return null
            return FillerLink(show.slug, show.names.first(), offset)
        }

        /** The list episode aired closest to [title]'s start (within three weeks), minus one; null without dates. */
        fun offsetByDate(title: FillerTitle, list: List<FillerEpisode>): Int? {
            val start = title.start ?: return null
            val dated = list.mapNotNull { e -> e.aired?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { e.number to it } }
            if (dated.isEmpty()) return null
            val (number, day) = dated.minBy { (_, d) -> kotlin.math.abs(ChronoUnit.DAYS.between(start, d)) }
            if (kotlin.math.abs(ChronoUnit.DAYS.between(start, day)) > 21) return null
            // Two episodes on the same day (a double premiere): the first of them.
            val first = dated.filter { it.second == day }.minOf { it.first }
            return minOf(number, first) - 1
        }

        /** Consecutive numbers as runs: 1, 2, 3, 7 → 1..3, 7..7. */
        fun runs(numbers: List<Int>): List<IntRange> {
            val sorted = numbers.distinct().sorted()
            val out = mutableListOf<IntRange>()
            var start: Int? = null
            var last = 0
            sorted.forEach { n ->
                if (start == null) {
                    start = n
                } else if (n != last + 1) {
                    out += start!!..last
                    start = n
                }
                last = n
            }
            start?.let { out += it..last }
            return out
        }
    }
}
