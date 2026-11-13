package com.aniliphy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.MediaKind

/** How a list in the AniList tab can be ordered. [descending] is the way it goes when first picked. */
enum class ListSort(val descending: Boolean) {
    Updated(true),
    Title(false),
    Release(true),
    Length(true),
    Format(false),
    Score(true),
    Average(true),
    Progress(true),
    Added(true),
    ;

    fun label(kind: MediaKind): String = when (this) {
        Updated -> "Last updated"
        Title -> "Title"
        Release -> "Release date"
        Length -> if (kind == MediaKind.Anime) "Episodes" else "Chapters"
        Format -> "Type"
        Score -> "My score"
        Average -> "Average score"
        Progress -> "Progress"
        Added -> "Added"
    }

    companion object {
        /**
         * Orders [entries] by [sort], the way [descending] says. Titles missing the value (no release date, no episode
         * count yet, not scored) always go last, whichever way; ties keep the most recently updated first.
         */
        fun apply(entries: List<ListEntry>, sort: ListSort, descending: Boolean): List<ListEntry> {
            if (sort == Title) {
                val byTitle = compareBy<ListEntry> { it.media.title.display.lowercase() }
                return entries.sortedWith(if (descending) byTitle.reversed() else byTitle)
            }
            val recent = compareByDescending<ListEntry> { it.updatedAt ?: 0 }
            val (known, unknown) = entries.partition { key(it, sort) != null }
            val byKey = Comparator<ListEntry> { a, b -> compareKeys(key(a, sort)!!, key(b, sort)!!) }
            val ordered = known.sortedWith((if (descending) byKey.reversed() else byKey).then(recent))
            return ordered + unknown.sortedWith(recent)
        }

        private fun compareKeys(a: Comparable<*>, b: Comparable<*>): Int {
            @Suppress("UNCHECKED_CAST")
            return (a as Comparable<Any>).compareTo(b)
        }

        /** The value [entry] is ordered by, or null when AniList doesn't have it. */
        private fun key(entry: ListEntry, sort: ListSort): Comparable<*>? {
            val media = entry.media
            return when (sort) {
                Updated -> entry.updatedAt ?: 0L
                Title -> media.title.display.lowercase()
                Release -> media.startDate?.year?.let { year ->
                    year * 10_000 + (media.startDate.month ?: 0) * 100 + (media.startDate.day ?: 0)
                } ?: media.seasonYear?.let { it * 10_000 }
                Length -> (media.episodes ?: media.chapters)?.takeIf { it > 0 }
                Format -> media.format?.let { Formatting.format(it) }
                Score -> entry.score.takeIf { it > 0 }
                Average -> media.averageScore
                Progress -> entry.progress
                Added -> entry.createdAt
            }
        }
    }
}

/** The order picked for the lists, kept between launches. Anime and manga lists share it. */
internal object ListSortPrefs {
    private const val FILE = "lists"
    var sort by mutableStateOf(ListSort.Updated)
        private set
    var descending by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        sort = ListSort.entries.firstOrNull { it.name == prefs.getString("list_sort", null) } ?: ListSort.Updated
        descending = prefs.getBoolean("list_sort_descending", sort.descending)
    }

    /** Picking the order already in use turns it the other way. */
    fun pick(context: Context, value: ListSort) {
        descending = if (value == sort) !descending else value.descending
        sort = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("list_sort", value.name)
            .putBoolean("list_sort_descending", descending)
            .apply()
    }
}
