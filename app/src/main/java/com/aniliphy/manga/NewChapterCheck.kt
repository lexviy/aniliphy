package com.aniliphy.manga

import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.source.SourceChapter
import java.io.IOException

object NewChapterCheck {
    const val CHECK_EVERY_MS = 30 * 60_000L

    fun refresh(
        records: Map<Int, MangaRecord>,
        newChapters: NewChapters,
        now: Long = System.currentTimeMillis(),
        onChapters: (sourceId: String, chapters: List<SourceChapter>) -> Unit = { _, _ -> },
    ): Boolean {
        val known = newChapters.all()
        var any = false
        for ((mediaId, record) in records) {
            if (!record.hasPlace) continue
            val sourceId = record.source ?: continue
            val (source, url) = MangaSites.parse(sourceId) ?: continue
            val item = known[mediaId]
            if (item != null && item.source == sourceId && now - item.checked < CHECK_EVERY_MS) continue
            val chapters = try {
                MangaSites.site(source).chapters(url)
            } catch (_: IOException) {
                continue
            }
            onChapters(sourceId, chapters)
            val latest = MangaRules.latest(chapters) ?: continue
            newChapters.checked(mediaId, sourceId, latest, now)
            any = true
        }
        return any
    }

    fun fresh(records: Map<Int, MangaRecord>, newChapters: NewChapters): Map<Int, Double> {
        val known = newChapters.all()
        return records.mapNotNull { (id, record) ->
            val item = known[id]
            if (NewChapters.isNew(item, record)) id to item!!.latest else null
        }.toMap()
    }
}
