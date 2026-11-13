package com.aniliphy.anilist

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatting {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val RELATION_ORDER = listOf(
        "PREQUEL", "SEQUEL", "PARENT", "SIDE_STORY", "SPIN_OFF", "ALTERNATIVE", "SUMMARY", "SOURCE", "ADAPTATION",
    )

    private val AIRING_TIME = DateTimeFormatter.ofPattern("EEE, MMM dd, HH:mm", Locale.ENGLISH)
    private val TAG = Regex("<[^>]+>")
    private val BLANK_LINES = Regex("(\\s*\\n){2,}\\s*")
    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘",
        "rdquo" to "”", "ldquo" to "“",
    )

    fun nice(value: String?): String {
        if (value.isNullOrEmpty()) return "?"
        return value.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
    }

    fun format(value: String?): String = when (value) {
        "TV" -> "TV"
        "TV_SHORT" -> "TV Short"
        "OVA" -> "OVA"
        "ONA" -> "ONA"
        "NOVEL" -> "Light novel"
        else -> nice(value)
    }

    fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    fun duration(minutes: Long): String {
        val days = minutes / (60 * 24)
        val hours = minutes / 60 % 24
        val rest = minutes % 60
        return when {
            days > 0 -> "$days d $hours h"
            hours > 0 -> "$hours h $rest min"
            else -> "$rest min"
        }
    }

    fun activityDetail(activity: Activity): String {
        val status = activity.status?.trim().orEmpty()
        if (status.isEmpty()) return "Activity"
        val media = activity.media
        val total = if (media?.type == "ANIME") media.episodes else media?.chapters
        val progress = activity.progress?.takeIf { it.isNotBlank() } ?: return status.replaceFirstChar(Char::uppercase)
        return if (" - " in progress) {
            val plural = status.replace("episode", "episodes").replace("chapter", "chapters")
            val base = "${plural.replaceFirstChar(Char::uppercase)} $progress"
            if (total != null) "$base of $total" else base
        } else {
            val base = "${status.replaceFirstChar(Char::uppercase)} $progress"
            if (total != null) "$base/$total" else base
        }
    }

    fun relativeTime(timestamp: Long?, now: Long = nowSeconds()): String {
        if (timestamp == null || timestamp == 0L) return ""
        val seconds = maxOf(0L, now - timestamp)
        if (seconds < 60) return "just now"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m ago"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ago"
        val days = hours / 24
        if (days < 30) return "${days}d ago"
        val months = days / 30
        if (months < 12) return "${months}mo ago"
        return "${days / 365}y ago"
    }

    fun date(date: FuzzyDate?): String {
        val year = date?.year ?: return "?"
        val month = date.month?.let { MONTHS.getOrNull(it - 1) } ?: return year.toString()
        val day = date.day ?: return "$month $year"
        return "$month $day, $year"
    }

    fun countdown(seconds: Long): String {
        val total = maxOf(0L, seconds)
        val days = total / 86400
        val hours = total % 86400 / 3600
        val minutes = total % 3600 / 60
        return buildString {
            if (days > 0) append("${days}d ")
            if (days > 0 || hours > 0) append("${hours}h ")
            append("${minutes}m")
        }
    }

    fun nextEpisode(media: Media, now: Long = nowSeconds(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val next = media.nextAiringEpisode ?: return null
        val airingAt = next.airingAt ?: return null
        val moment = AIRING_TIME.format(Instant.ofEpochSecond(airingAt).atZone(zone))
        return "Ep ${next.episode} in ${countdown(airingAt - now)} ($moment)"
    }

    fun cleanDescription(text: String?): String {
        if (text.isNullOrEmpty()) return "No synopsis."
        val plain = unescape(text.replace(BREAK, "\n").replace(TAG, ""))
        return plain.replace(BLANK_LINES, "\n\n").trim()
    }

    private fun unescape(text: String): String = ENTITY.replace(text) { match ->
        val entity = match.groupValues[1]
        val decoded = when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.codePoint()
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.codePoint()
            else -> NAMED_ENTITIES[entity]
        }
        decoded ?: match.value
    }

    private fun Int.codePoint(): String? =
        if (java.lang.Character.isValidCodePoint(this)) String(java.lang.Character.toChars(this)) else null

    fun infoRows(media: Media, now: Long = nowSeconds(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> {
        val anime = media.kind == MediaKind.Anime
        return buildList {
            if (anime) {
                nextEpisode(media, now, zone)?.let { add("Next ep" to it) }
                add("Duration" to (media.duration?.takeIf { it > 0 }?.let { "$it min" } ?: "?"))
            }
            add("Start date" to date(media.startDate))
            add("End date" to date(media.endDate))
            if (anime) {
                val season = media.season
                val year = media.seasonYear
                add("Season" to if (season != null && year != null) "${nice(season)} $year" else "?")
            }
            add("Source" to nice(media.source))
            add("Romaji" to (media.title.romaji ?: "?"))
            add("English" to (media.title.english ?: "?"))
            add("Native" to (media.title.native ?: "?"))
            add("Synonyms" to media.synonyms.joinToString(", ").ifEmpty { "?" })
        }
    }

    fun splitStudios(media: Media): Pair<List<Named>, List<Named>> =
        media.studios.nodes.partition { it.isAnimationStudio }

    fun animeDetails(media: Media): String = listOfNotNull(
        media.format?.let(::format),
        media.episodes?.takeIf { it > 0 }?.let { "$it eps" },
        media.averageScore?.takeIf { it > 0 }?.let { "$it%" },
    ).joinToString(" · ")

    data class RelatedItem(val id: Int, val isAnime: Boolean, val title: String, val kind: String, val cover: String?)

    fun relations(media: Media): List<RelatedItem> =
        media.relations.edges
            .filter { it.node != null }
            .sortedBy { edge ->
                RELATION_ORDER.indexOf(edge.relationType).let { if (it < 0) RELATION_ORDER.size else it }
            }
            .map { edge ->
                val node = edge.node!!
                val anime = node.type == "ANIME"
                val format = format(node.format)
                RelatedItem(
                    id = node.id,
                    isAnime = anime,
                    title = node.title.display,
                    kind = if (anime) nice(edge.relationType) else "${nice(edge.relationType)}\n$format",
                    cover = node.coverImage.best,
                )
            }

    fun recommendations(media: Media): List<RelatedItem> =
        media.recommendations.nodes.mapNotNull { it.mediaRecommendation }.map { item ->
            val kind = when (item.kind) {
                MediaKind.Anime -> "Anime"
                MediaKind.LightNovel -> "Light novel"
                MediaKind.Manga -> "Manga"
            }
            val score = item.averageScore?.takeIf { it > 0 }
            RelatedItem(
                id = item.id,
                isAnime = item.kind == MediaKind.Anime,
                title = item.title.display,
                kind = if (score != null) "$kind\n★ $score%" else kind,
                cover = item.coverImage.best,
            )
        }

    fun byYear(animes: List<Media>): List<Pair<Int?, List<Media>>> {
        val runs = mutableListOf<Pair<Int?, MutableList<Media>>>()
        for (anime in animes) {
            val year = anime.startDate?.year
            if (runs.isNotEmpty() && runs.last().first == year) {
                runs.last().second += anime
            } else {
                runs += year to mutableListOf(anime)
            }
        }
        return runs
    }

    fun airingLine(media: Media, now: Long = nowSeconds()): String {
        val next = media.nextAiringEpisode
        val airingAt = next?.airingAt ?: return "Date unknown"
        val left = airingAt - now
        return if (left <= 0) "Ep ${next.episode} out now" else "Ep ${next.episode} in ${countdown(left)}"
    }

    fun listProgress(entry: ListEntry): String {
        val media = entry.media
        fun of(done: Int, total: Int?, unit: String) = "$done / ${total?.takeIf { it > 0 } ?: "?"} $unit"
        return when {
            media.kind == MediaKind.Anime -> of(entry.progress, media.episodes, "eps")
            entry.progressVolumes > 0 && entry.progress == 0 -> of(entry.progressVolumes, media.volumes, "vols")
            entry.progressVolumes > 0 ->
                "${of(entry.progress, media.chapters, "ch")} · ${of(entry.progressVolumes, media.volumes, "vols")}"
            else -> of(entry.progress, media.chapters, "ch")
        }
    }

    fun score(score: Double?): String? {
        val value = score?.takeIf { it > 0 } ?: return null
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    fun matches(media: Media, text: String): Boolean {
        val wanted = text.trim()
        if (wanted.isEmpty()) return true
        return listOfNotNull(media.title.english, media.title.romaji, media.title.native)
            .any { it.contains(wanted, ignoreCase = true) }
    }

    /** A count in a few characters, like 950, 1.2k, 34k or 1.5M. */
    fun compactCount(value: Int): String {
        fun cut(number: Double, unit: String): String {
            val text = if (number < 10) String.format(java.util.Locale.ROOT, "%.1f", number).removeSuffix(".0") else number.toLong().toString()
            return text + unit
        }
        return when {
            value < 1_000 -> value.toString()
            value < 1_000_000 -> cut(value / 1_000.0, "k")
            else -> cut(value / 1_000_000.0, "M")
        }
    }

    fun kindLabel(kind: MediaKind) = when (kind) {
        MediaKind.Anime -> "Anime"
        MediaKind.Manga -> "Manga"
        MediaKind.LightNovel -> "Light Novel"
    }

    data class PersonText(val description: String, val height: String?, val hasSpoilers: Boolean)

    private val HEIGHT = Regex("__Height:__\\s*([^\\n\\r]+)\\s*")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]+\\)")
    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")
    private val UNDERLINE = Regex("__([^_]+)__")
    private val ITALIC = Regex("\\*([^*]+)\\*")
    private val STRIKE = Regex("~~([^~]+)~~")
    private val SPOILER = Regex("~!(.*?)!~", RegexOption.DOT_MATCHES_ALL)

    fun personText(raw: String?, staff: Boolean, showSpoilers: Boolean): PersonText {
        var text = raw.orEmpty().ifEmpty { "No description." }
        val height = HEIGHT.find(text)?.groupValues?.get(1)?.trim()
        text = text.replace(HEIGHT, "")
        if (staff) {
            text = text.lines().filterNot { line ->
                line.isNotBlank() && line.replace(LINK, "").trim(' ', '|').isEmpty()
            }.joinToString("\n")
        }
        val hasSpoilers = SPOILER.containsMatchIn(text)
        text = SPOILER.replace(text) { if (showSpoilers) it.groupValues[1] else "[Spoiler]" }
        var clean = cleanDescription(text)
        clean = clean.replace(LINK, "$1").replace(BOLD, "$1").replace(UNDERLINE, "$1")
        clean = clean.replace(ITALIC, "$1").replace(STRIKE, "$1")
        return PersonText(clean, height, hasSpoilers)
    }

    fun birthday(date: FuzzyDate?): String? {
        val month = date?.month?.let { MONTHS.getOrNull(it - 1) } ?: return null
        val day = date.day ?: return null
        return if (date.year != null) "$month $day, ${date.year}" else "$month $day"
    }

    fun yearsActive(years: List<Int>): String? {
        val start = years.getOrNull(0)?.takeIf { it > 0 }
        val end = years.getOrNull(1)?.takeIf { it > 0 }
        return when {
            start != null && end != null -> "$start-$end"
            start != null -> "$start-present"
            else -> end?.toString()
        }
    }
}
