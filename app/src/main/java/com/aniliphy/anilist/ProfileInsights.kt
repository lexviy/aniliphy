package com.aniliphy.anilist

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** The day, or the first of the month when AniList only knows the month. Null without a year and month. */
fun FuzzyDate.toLocalDate(): LocalDate? {
    val y = year ?: return null
    val m = month ?: return null
    return runCatching { LocalDate.of(y, m, (day ?: 1).coerceAtLeast(1)) }.getOrNull()
}

/** When a title was finished, and whether that's a guess (from when the entry was last changed). */
data class Finish(val entry: ListEntry, val on: LocalDate, val guessed: Boolean)

object Finishes {
    private val DONE = setOf(ListStatus.COMPLETED, ListStatus.REPEATING)

    /**
     * Finished titles with a date: AniList's "completed" date, or for completed entries without one the day the entry
     * was last changed (marked [Finish.guessed]).
     */
    fun of(entries: List<ListEntry>, zone: ZoneId = ZoneId.systemDefault()): List<Finish> = entries.mapNotNull { entry ->
        if (entry.status !in DONE && entry.completedAt?.toLocalDate() == null) return@mapNotNull null
        entry.completedAt?.toLocalDate()?.let { return@mapNotNull Finish(entry, it, guessed = false) }
        entry.updatedAt?.let { Finish(entry, Instant.ofEpochSecond(it).atZone(zone).toLocalDate(), guessed = true) }
    }
}

/** Titles finished in a stretch of time, how many episodes or chapters they had and (anime) how long they took. */
data class PeriodTotals(val titles: Int = 0, val units: Int = 0, val minutes: Long = 0)

data class PeriodComparison(
    val thisMonth: PeriodTotals,
    val lastMonth: PeriodTotals,
    val thisYear: PeriodTotals,
    val lastYear: PeriodTotals,
    /** How many of the counted dates are guesses. */
    val guessed: Int,
)

/** "At your pace you'd finish your Planning in about [months] months." */
data class PlanningForecast(val planning: Int, val perMonth: Double, val months: Double?)

/** A title you scored far from AniList's average: [difference] is your score (out of 100) minus theirs. */
data class HotTake(val entry: ListEntry, val difference: Int)

object ProfileInsights {
    private const val ASSUMED_EPISODE_MINUTES = 24
    private const val PACE_MONTHS = 6L

    fun totals(finishes: List<Finish>, kind: MediaKind): PeriodTotals = PeriodTotals(
        titles = finishes.size,
        units = finishes.sumOf { units(it.entry, kind) },
        minutes = if (kind == MediaKind.Anime) {
            finishes.sumOf { units(it.entry, kind).toLong() * (it.entry.media.duration ?: ASSUMED_EPISODE_MINUTES) }
        } else {
            0L
        },
    )

    fun compare(entries: List<ListEntry>, kind: MediaKind, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): PeriodComparison {
        val finishes = Finishes.of(entries, zone)
        val month = YearMonth.from(today)
        fun inMonth(m: YearMonth) = finishes.filter { YearMonth.from(it.on) == m }
        fun inYear(y: Int) = finishes.filter { it.on.year == y }
        val counted = inYear(today.year) + inYear(today.year - 1)
        return PeriodComparison(
            thisMonth = totals(inMonth(month), kind),
            lastMonth = totals(inMonth(month.minusMonths(1)), kind),
            thisYear = totals(inYear(today.year), kind),
            lastYear = totals(inYear(today.year - 1), kind),
            guessed = counted.count { it.guessed },
        )
    }

    /** Your pace over the last six months against what's on Planning. */
    fun forecast(entries: List<ListEntry>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): PlanningForecast {
        val planning = entries.count { it.status == ListStatus.PLANNING }
        val since = today.minusMonths(PACE_MONTHS)
        val recent = Finishes.of(entries, zone).count { it.on > since && it.on <= today }
        val perMonth = recent / PACE_MONTHS.toDouble()
        return PlanningForecast(planning, perMonth, if (perMonth > 0) planning / perMonth else null)
    }

    /** The scores furthest from AniList's average, both ways: [above] are titles you liked more, [below] less. */
    fun hotTakes(entries: List<ListEntry>, limit: Int = 5): Pair<List<HotTake>, List<HotTake>> {
        val takes = entries.mapNotNull { entry ->
            val average = entry.media.averageScore ?: return@mapNotNull null
            if (entry.score <= 0) return@mapNotNull null
            HotTake(entry, (entry.score * 10).toInt() - average)
        }.filter { abs(it.difference) >= 10 }
        return takes.filter { it.difference > 0 }.sortedByDescending { it.difference }.take(limit) to
            takes.filter { it.difference < 0 }.sortedBy { it.difference }.take(limit)
    }

    /** Episodes or chapters (volumes for light novels) a finished title counts for, rewatches included. */
    fun units(entry: ListEntry, kind: MediaKind): Int {
        val total = when (kind) {
            MediaKind.Anime -> entry.media.episodes
            MediaKind.Manga -> entry.media.chapters
            MediaKind.LightNovel -> entry.media.volumes
        }
        val one = total ?: if (kind == MediaKind.LightNovel) entry.progressVolumes.takeIf { it > 0 } ?: entry.progress else entry.progress
        return one * (1 + entry.repeat)
    }
}

/** An AniList bio as plain text: images, spoilers markup, HTML and Markdown taken out, links kept as their words. */
fun plainBio(about: String?): String? {
    if (about.isNullOrBlank()) return null
    val text = about
        .replace(Regex("""(?i)(img|webm|youtube)\d*%?\(([^)]*)\)"""), " ")
        .replace(Regex("""~!.*?!~""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace("~~~", " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""https?://\S+"""), " ")
        .replace(Regex("""(\*\*|__|~~|`)"""), "")
        .replace(Regex("""(?m)^\s*(#{1,6}|>|[-*+])\s*"""), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return text.takeIf { it.isNotEmpty() }
}
