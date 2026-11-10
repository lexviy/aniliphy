package com.aniliphy.novels

data class Segment(val start: Int, val end: Int?)

data class PdfChapter(val title: String, val start: Int, val end: Int)

object ChapterRules {
    private const val NUMBER =
        "(?:\\d+|[ivxlc]{1,6}|one|two|three|four|five|six|seven|eight|nine|ten|eleven" +
            "|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)"
    private val CHAPTER_TITLE = Regex(
        "^\\s*(?:(?:chapter|chap\\.?|ch\\.?|episode|capítulo|capitulo|cap\\.?)\\s*$NUMBER\\b|final\\s+chapter\\b|第\\s*\\d+\\s*章|\\d{1,3}\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val LEAD_IN = Regex("^\\s*(prologue|foreword|preface|prelude|opening|introduction)\\b", RegexOption.IGNORE_CASE)
    private val HEADING = Regex(
        "^(chapter|prologue|epilogue|interlude|intermission|afterword|side story|short story|final chapter)\\b",
        RegexOption.IGNORE_CASE,
    )
    private const val PAGES_PER_CHAPTER = 10
    private const val HEADING_MAX_LENGTH = 60
    private const val MAX_HEADINGS_ON_PAGE = 3
    private const val MIN_OUTLINE_PAGES = 3

    fun isChapterTitle(title: String) = CHAPTER_TITLE.containsMatchIn(title)

    fun detectRuns(titles: List<String>): List<Segment> {
        val runs = ArrayList<Segment>()
        var i = 0
        while (i < titles.size) {
            if (CHAPTER_TITLE.containsMatchIn(titles[i])) {
                var j = i
                while (j + 1 < titles.size && CHAPTER_TITLE.containsMatchIn(titles[j + 1])) j++
                runs.add(Segment(i, j))
                i = j + 1
            } else {
                i++
            }
        }
        return runs
    }

    fun leadIn(titles: List<String>, runs: List<Segment>): Int? {
        var i = runs.firstOrNull()?.start ?: return null
        while (i > 0 && LEAD_IN.containsMatchIn(titles[i - 1])) i--
        return i
    }

    fun counted(segments: List<Segment>, length: Int, upTo: Int? = null, starts: List<Int>? = null): Int {
        val last = length - 1
        return segments.sumOf { segment ->
            var stop = segment.end ?: last
            if (upTo != null) stop = minOf(stop, upTo)
            if (starts != null) {
                starts.count { it in segment.start..stop }
            } else {
                maxOf(0, stop - segment.start + 1)
            }
        }
    }

    fun chapterSections(segments: List<Segment>, length: Int, starts: List<Int>? = null): List<Int> {
        val last = length - 1
        return segments.flatMap { segment ->
            val range = segment.start..(segment.end ?: last)
            starts?.filter { it in range } ?: range.toList()
        }
    }

    fun lastSection(section: Int, length: Int, starts: List<Int>?): Int =
        if (starts == null) section else (starts.firstOrNull { it > section } ?: length) - 1

    fun firstSection(section: Int, starts: List<Int>?): Int = starts?.lastOrNull { it <= section } ?: section

    fun label(segments: List<Segment>, length: Int, section: Int, starts: List<Int>? = null): String {
        val total = counted(segments, length, starts = starts)
        if (total == 0) return "Section ${section + 1} of $length"
        val number = counted(segments, length, upTo = section, starts = starts)
        return when {
            number == 0 -> "Before chapter 1"
            number == total && section > lastSection(chapterSections(segments, length, starts).last(), length, starts) -> "After chapter $total"
            else -> "Chapter $number of $total"
        }
    }

    fun locate(wanted: Int, volumes: List<Triple<List<Segment>, Int, List<Int>?>>): Pair<Int, Int>? {
        if (wanted < 1) return null
        var base = 0
        volumes.forEachIndexed { index, (segments, length, starts) ->
            val sections = chapterSections(segments, length, starts)
            if (wanted <= base + sections.size) return index to sections[wanted - base - 1]
            base += sections.size
        }
        return null
    }

    fun outlineStarts(outline: List<Triple<Int, String, Int>>, pageCount: Int): List<Pair<String, Int>>? {
        val entries = outline.filter { it.third in 0 until pageCount }
        for (level in entries.map { it.first }.distinct().sorted()) {
            val found = LinkedHashMap<Int, String>()
            entries.filter { it.first == level }.forEach { (_, title, page) ->
                if (page !in found) found[page] = title.trim().ifEmpty { "Page ${page + 1}" }
            }
            if (found.size >= MIN_OUTLINE_PAGES) return found.toSortedMap().map { (page, title) -> title to page }
        }
        return null
    }

    fun headingStarts(pages: List<List<String>>): List<Pair<String, Int>>? {
        val starts = ArrayList<Pair<String, Int>>()
        pages.forEachIndexed { number, raw ->
            val lines = raw.map { it.trim() }.filter { it.isNotEmpty() }
            val isHeading = { line: String -> line.length <= HEADING_MAX_LENGTH && HEADING.containsMatchIn(line) }
            if (lines.count(isHeading) < MAX_HEADINGS_ON_PAGE) {
                lines.take(4).firstOrNull(isHeading)?.let { starts.add(it to number) }
            }
        }
        return starts.takeUnless { it.size < 2 || it.size > pages.size * 0.5 }
    }

    fun pdfChapters(starts: List<Pair<String, Int>>?, pageCount: Int): List<PdfChapter> {
        if (starts.isNullOrEmpty()) {
            return (0 until pageCount step PAGES_PER_CHAPTER).map { start ->
                val end = minOf(start + PAGES_PER_CHAPTER, pageCount)
                PdfChapter("Pages ${start + 1}-$end", start, end)
            }
        }
        val chapters = ArrayList<PdfChapter>()
        if (starts.first().second > 0) chapters.add(PdfChapter("Front matter", 0, starts.first().second))
        starts.forEachIndexed { index, (title, page) ->
            val end = starts.getOrNull(index + 1)?.second ?: pageCount
            chapters.add(PdfChapter(title, page, end))
        }
        return chapters
    }
}
