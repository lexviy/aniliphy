package com.aniliphy.source

import kotlinx.serialization.Serializable
import java.io.IOException

/** A series found on the site. [url] is saved with progress, so its format must stay the same. */
data class SourceSeries(val title: String, val url: String, val cover: String? = null)

/** [id] is saved with progress, so its format must stay the same. */
@Serializable
data class SourceChapter(
    val id: String,
    val name: String,
    val url: String,
    val number: Double? = null,
)

interface MangaSite : Site {
    /** Sent with every page image. */
    val imageHeaders: Map<String, String>

    @Throws(IOException::class)
    fun search(query: String): List<SourceSeries>

    @Throws(IOException::class)
    fun chapters(seriesUrl: String): List<SourceChapter>

    /** Image addresses, in reading order. */
    @Throws(IOException::class)
    fun pages(chapter: SourceChapter): List<String>

    /** For the progress synced with anilixy (the desktop app): how it names the chapter with [id]. */
    fun syncedChapterId(id: String): String = id

    /** The opposite of [syncedChapterId]. */
    fun chapterIdFromSync(id: String): String = id
}
