package com.aniliphy.novels

@kotlinx.serialization.Serializable
enum class VolumeEntry { Resume, Start, End }

class ReadingSession(
    private val store: NovelProgressStore,
    val series: String,
    private val seriesTitle: String?,
    private val volumes: List<String>,
    val volumeIndex: Int,
    val titles: List<String>,
    private val sizes: List<Long>,
    manualCounting: Boolean,
    entry: VolumeEntry = VolumeEntry.Resume,
    val starts: List<Int>? = null,
) {
    val book: String = volumes[volumeIndex]
    private val runs = ChapterRules.detectRuns(titles)
    var segments: List<Segment>
        private set
    var auto: Boolean
        private set
    val base: Int
    var chapter: Int
        private set
    var position: Double
        private set

    val detected: Int?

    init {
        val state = store.state(series, book)
        var found = state.segments
        var isAuto = state.auto && found.isNotEmpty()
        var fresh = false
        if (found.isEmpty() && runs.isNotEmpty() && !manualCounting) {
            found = runs
            isAuto = true
            fresh = true
        } else if (isAuto && runs.isNotEmpty()) {
            found = runs
        }
        segments = found
        auto = isAuto
        base = store.baseFor(series, volumes, volumeIndex)
        val last = titles.size - 1
        var index = when (entry) {
            VolumeEntry.Start -> ChapterRules.leadIn(titles, runs) ?: found.firstOrNull()?.start ?: 0
            VolumeEntry.End -> last
            VolumeEntry.Resume ->
                if (state.length == 0 || (state.chapter == 0 && state.position == 0.0)) ChapterRules.leadIn(titles, runs) ?: 0 else state.chapter
        }
        var at = if (entry == VolumeEntry.Resume && state.length != 0) state.position else 0.0
        if (index !in 0..last) {
            index = 0
            at = 0.0
        }
        chapter = index
        position = at.coerceIn(0.0, 1.0)
        detected = if (fresh) ChapterRules.counted(found, titles.size, starts = starts) else null
    }

    val counting: Boolean get() = segments.lastOrNull()?.let { it.end == null } == true

    fun aniListChapter(): Int = base + ChapterRules.counted(segments, titles.size, upTo = chapter, starts = starts)

    fun label(): String = ChapterRules.label(segments, titles.size, chapter, starts)

    val listed: List<Int> get() = starts ?: titles.indices.toList()

    fun chapterStart(section: Int = chapter): Int = ChapterRules.firstSection(section, starts)

    fun nextChapter(): Int? = listed.firstOrNull { it > chapter }

    fun previousChapter(): Int? = listed.lastOrNull { it < chapterStart() }

    fun finishedVolume(): Boolean {
        val total = ChapterRules.counted(segments, titles.size, starts = starts)
        return total > 0 && chaptersRead() == base + total
    }

    fun chaptersRead(): Int {
        val atEnd = position >= FINISHED_AT
        return base + ChapterRules.chapterSections(segments, titles.size, starts).count { section ->
            val end = ChapterRules.lastSection(section, titles.size, starts)
            end < chapter || (end == chapter && atEnd)
        }
    }

    fun volumeFraction(): Double {
        val total = sizes.sumOf { it.toDouble() }
        if (total <= 0.0) return 0.0
        val before = sizes.take(chapter).sumOf { it.toDouble() }
        return ((before + sizes.getOrElse(chapter) { 0L } * position) / total).coerceIn(0.0, 1.0)
    }

    fun chapterFraction(): Double {
        val first = chapterStart()
        val last = ChapterRules.lastSection(first, titles.size, starts).coerceAtMost(sizes.size - 1)
        val total = (first..last).sumOf { sizes[it].toDouble() }
        if (total <= 0.0) return 0.0
        val before = (first until chapter).sumOf { sizes[it].toDouble() }
        return ((before + sizes[chapter] * position) / total).coerceIn(0.0, 1.0)
    }

    fun moveTo(index: Int, at: Double = 0.0) {
        chapter = index.coerceIn(0, titles.size - 1)
        position = at.coerceIn(0.0, 1.0)
    }

    fun setPosition(at: Double) {
        position = at.coerceIn(0.0, 1.0)
    }

    fun persist() = store.save(
        series,
        book,
        BookState(chapter, position, segments, titles.size, auto, starts),
        title = seriesTitle,
    )

    fun toggleCounting(): String {
        var notice = ""
        if (auto) {
            auto = false
            segments = emptyList()
            notice = "Automatic count off. "
        }
        val message = if (counting) {
            val open = segments.last()
            if (chapter < open.start) return "Go to a chapter after the start"
            segments = segments.dropLast(1) + Segment(open.start, chapter)
            "Paused. ${aniListChapter()} chapters counted"
        } else {
            if (segments.any { it.end != null && chapter in it.start..it.end }) return "This chapter is already counted"
            segments = segments + Segment(chapterStart(), null)
            "Counting from chapter ${aniListChapter()}"
        }
        persist()
        return notice + message
    }

    fun resetCounting(): String {
        segments = runs
        auto = segments.isNotEmpty()
        persist()
        return if (auto) {
            "Count reset: ${ChapterRules.counted(segments, titles.size, starts = starts)} chapters found"
        } else {
            "Count reset for this volume"
        }
    }

    private companion object {
        const val FINISHED_AT = 0.99
    }
}
