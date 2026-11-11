package com.aniliphy.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/** What you did on one day: time spent watching anime, and manga and light novel chapters read. */
@Serializable
data class ActivityDay(val animeMs: Long = 0, val mangaChapters: Int = 0, val novelChapters: Int = 0) {
    operator fun plus(other: ActivityDay) =
        ActivityDay(animeMs + other.animeMs, mangaChapters + other.mangaChapters, novelChapters + other.novelChapters)

    val isEmpty: Boolean get() = animeMs == 0L && mangaChapters == 0 && novelChapters == 0
}

/**
 * Counts, day by day, how long you watch and how many chapters you read, for the statistics in Settings → Activity.
 * Only kept on this phone, for the last [KEEP_DAYS] days.
 */
object ActivityLog {
    private const val FILE = "activity_log.json"
    const val KEEP_DAYS = 400L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    fun watched(context: Context, ms: Long) = add(context, ActivityDay(animeMs = ms))

    fun mangaChapter(context: Context) = add(context, ActivityDay(mangaChapters = 1))

    fun novelChapters(context: Context, count: Int) {
        if (count > 0) add(context, ActivityDay(novelChapters = count))
    }

    fun all(context: Context): Map<LocalDate, ActivityDay> = synchronized(lock) { read(file(context)) }

    private fun add(context: Context, more: ActivityDay, today: LocalDate = LocalDate.now()) = synchronized(lock) {
        val file = file(context)
        val days = read(file).toMutableMap()
        days[today] = (days[today] ?: ActivityDay()) + more
        val oldest = today.minusDays(KEEP_DAYS)
        runCatching {
            file.writeTextAtomically(json.encodeToString(days.filterKeys { it > oldest }.mapKeys { it.key.toString() }))
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun read(file: File): Map<LocalDate, ActivityDay> =
        runCatching { file.readTextOrNull()?.let { json.decodeFromString<Map<String, ActivityDay>>(it) } }.getOrNull().orEmpty()
            .mapNotNull { (day, value) -> runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to value } }
            .toMap()
}

/** Totals for the statistics screen. Pure, so it can be tested. */
object ActivityStats {
    data class Summary(val week: ActivityDay, val month: ActivityDay, val all: ActivityDay, val lastWeek: List<Pair<LocalDate, ActivityDay>>, val streak: Int)

    fun summary(days: Map<LocalDate, ActivityDay>, today: LocalDate): Summary {
        fun since(first: LocalDate) = days.filterKeys { it >= first && it <= today }.values.fold(ActivityDay()) { a, b -> a + b }
        val lastWeek = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (days[it] ?: ActivityDay()) }
        // Days in a row with something done, counting today only once something has been done today.
        var streak = 0
        var day = if (days[today]?.isEmpty == false) today else today.minusDays(1)
        while (days[day]?.isEmpty == false) {
            streak++
            day = day.minusDays(1)
        }
        return Summary(
            week = since(today.minusDays(6)),
            month = since(today.minusDays(29)),
            all = since(LocalDate.MIN),
            lastWeek = lastWeek,
            streak = streak,
        )
    }

    /** Like "3 h 20 min" or "45 min". */
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
