package com.aniliphy.manga

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.source.SourceChapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MangaRecord(
    val source: String? = null,
    val chapterId: String? = null,
    val chapterName: String? = null,
    val chapterNumber: Double? = null,
    val page: Int = 0,
    val pages: Int = 0,
    val title: String? = null,
    val cover: String? = null,
    val updated: Long = 0,
    val total: Int? = null,
    val titles: List<String> = emptyList(),
    val webtoon: Boolean = false,
) {
    val hasPlace: Boolean get() = chapterId != null || chapterNumber != null
}

class MangaHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    fun all(): Map<Int, MangaRecord> = synchronized(lock) {
        runCatching {
            file.readTextOrNull()?.let { json.decodeFromString<Map<String, MangaRecord>>(it) }
        }.getOrNull().orEmpty().mapNotNull { (id, record) -> id.toIntOrNull()?.let { it to record } }.toMap()
    }

    fun get(mediaId: Int): MangaRecord? = all()[mediaId]

    private fun change(mediaId: Int, update: (MangaRecord) -> MangaRecord?) = synchronized(lock) {
        val all = all().toMutableMap()
        val next = update(all[mediaId] ?: MangaRecord())
        if (next == null) all.remove(mediaId) else all[mediaId] = next
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(all.mapKeys { it.key.toString() }))
    }

    fun edit(mediaId: Int, update: (MangaRecord) -> MangaRecord?) = change(mediaId, update)

    fun setSource(mediaId: Int, source: String?, title: String? = null, cover: String? = null) = change(mediaId) { old ->
        val sameSource = old.source == source
        old.copy(
            source = source,
            chapterId = if (sameSource) old.chapterId else null,
            title = title ?: old.title,
            cover = cover ?: old.cover,
        ).takeIf { it.source != null || it.hasPlace }
    }

    fun savePlace(
        mediaId: Int,
        chapter: SourceChapter,
        page: Int,
        pages: Int,
        title: String?,
        cover: String?,
        now: Long = System.currentTimeMillis(),
        total: Int? = null,
        titles: List<String> = emptyList(),
        webtoon: Boolean = false,
    ) = change(mediaId) { old ->
        old.copy(
            chapterId = chapter.id,
            chapterName = chapter.name,
            chapterNumber = chapter.number,
            page = page,
            pages = pages,
            title = title ?: old.title,
            cover = cover ?: old.cover,
            updated = now,
            total = total ?: old.total,
            titles = titles.ifEmpty { old.titles },
            webtoon = webtoon || old.webtoon,
        )
    }

    fun clearPlace(mediaId: Int) = change(mediaId) { old ->
        old.copy(chapterId = null, chapterName = null, chapterNumber = null, page = 0, pages = 0).takeIf { it.source != null }
    }
}

@Serializable
data class LatestChapter(
    val source: String,
    val latest: Double,
    val seen: Double,
    val checked: Long = 0,
    /** When each new chapter was first seen (millis, newest last), for guessing its release day. */
    val sightings: List<Long> = emptyList(),
)

class NewChapters(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = LOCK

    fun all(): Map<Int, LatestChapter> = synchronized(lock) {
        runCatching {
            file.readTextOrNull()?.let { json.decodeFromString<Map<String, LatestChapter>>(it) }
        }.getOrNull().orEmpty().mapNotNull { (id, item) -> id.toIntOrNull()?.let { it to item } }.toMap()
    }

    private fun change(mediaId: Int, update: (LatestChapter?) -> LatestChapter) = synchronized(lock) {
        val all = all().toMutableMap()
        all[mediaId] = update(all[mediaId])
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(all.mapKeys { it.key.toString() }))
    }

    fun checked(mediaId: Int, source: String, latest: Double, now: Long = System.currentTimeMillis()) = change(mediaId) { old ->
        if (old == null || old.source != source) {
            LatestChapter(source, latest, latest, now)
        } else {
            // A higher number than before is a release; remember when it showed up.
            val sightings = if (latest > old.latest) (old.sightings + now).takeLast(SIGHTINGS) else old.sightings
            old.copy(latest = latest, checked = now, sightings = sightings)
        }
    }

    fun seen(mediaId: Int, source: String, latest: Double, now: Long = System.currentTimeMillis()) = change(mediaId) { old ->
        LatestChapter(source, latest, latest, now, old?.takeIf { it.source == source }?.sightings.orEmpty())
    }

    companion object {
        private const val SIGHTINGS = 12
        private val LOCK = Any()

        fun isNew(item: LatestChapter?, record: MangaRecord): Boolean =
            item != null && item.source == record.source && item.latest > item.seen && item.latest > (record.chapterNumber ?: 0.0)
    }
}
