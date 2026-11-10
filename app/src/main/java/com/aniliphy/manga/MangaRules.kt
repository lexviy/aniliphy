package com.aniliphy.manga

import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.source.SourceChapter
import kotlin.math.floor

data class StartPlace(val chapter: Int, val page: Int)

object MangaRules {
    private val RESUMED = setOf(ListStatus.PLANNING, ListStatus.PAUSED, ListStatus.DROPPED)

    fun shouldResume(progress: ListProgress?): Boolean =
        progress != null && progress.status in RESUMED && progress.progress > 0

    fun aniListChapter(chapter: SourceChapter, total: Int? = null): Int? {
        val number = chapter.number ?: return null
        val whole = floor(number).toInt()
        if (whole <= 0) return null
        return total?.takeIf { it > 0 }?.let { minOf(whole, it) } ?: whole
    }

    fun find(chapters: List<SourceChapter>, record: MangaRecord?): Int? {
        record ?: return null
        record.chapterId?.let { id -> chapters.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it } }
        val number = record.chapterNumber ?: return null
        return chapters.indexOfFirst { it.number == number }.takeIf { it >= 0 }
    }

    fun start(chapters: List<SourceChapter>, progress: ListProgress?, record: MangaRecord?): StartPlace {
        if (chapters.isEmpty()) return StartPlace(0, 0)
        val saved = find(chapters, record)
        val resume = saved?.let { StartPlace(it, record?.page ?: 0) }
        val read = progress?.progress ?: 0
        return when {
            progress?.status == ListStatus.COMPLETED -> StartPlace(0, 0)
            resume != null && (read <= 0 || read < (chapters[resume.chapter].number ?: 0.0)) -> resume
            read > 0 -> {
                val after = chapters.indexOfFirst { (it.number ?: return@indexOfFirst false) > read }
                StartPlace(if (after >= 0) after else chapters.lastIndex, 0)
            }
            else -> resume ?: StartPlace(0, 0)
        }
    }

    fun counts(mark: ReadMark, page: Int, pages: Int, atEnd: Boolean): Boolean = when {
        pages <= 0 -> false
        atEnd -> true
        mark == ReadMark.LastPageOpens -> page >= pages - 1
        else -> false
    }

    fun percent(record: MangaRecord, latest: Double?): Int? {
        val number = record.chapterNumber ?: return null
        val total = record.total?.takeIf { it > 0 }?.toDouble() ?: latest?.takeIf { it > 0 } ?: return null
        val share = if (record.pages > 0) (record.page + 1).toDouble() / record.pages else 0.0
        return (((number - 1).coerceAtLeast(0.0) + share) / total * 100).toInt().coerceIn(0, 100)
    }

    fun latest(chapters: List<SourceChapter>): Double? = chapters.mapNotNull { it.number }.maxOrNull()

    fun spreads(pages: Int, wide: Set<Int> = emptySet(), shifted: Boolean = false): List<List<Int>> {
        val units = mutableListOf<List<Int>>()
        var index = 0
        if (!shifted && pages > 0) {
            units += listOf(0)
            index = 1
        }
        while (index < pages) {
            if (index in wide || index + 1 >= pages || index + 1 in wide) {
                units += listOf(index)
                index++
            } else {
                units += listOf(index, index + 1)
                index += 2
            }
        }
        return units
    }

    fun isLongStrip(width: Int, height: Int): Boolean = width > 0 && height.toDouble() / width >= LONG_STRIP

    fun isWebtoonOrigin(country: String?): Boolean = country == "KR" || country == "CN" || country == "TW"

    private const val LONG_STRIP = 2.2
}
