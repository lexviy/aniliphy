package com.aniliphy.novels

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil

/** One thing to say: a sentence (or a piece of a very long one). [start] is its place in the chapter's text, in characters. */
data class Spoken(val text: String, val paragraphEnd: Boolean, val start: Int)

/**
 * Splits a chapter into the sentences read aloud and marks each one in the page as `<span data-s="N">`, so what is
 * said and what is highlighted come from the same walk. A sentence that crosses `<i>` or a link becomes several spans
 * with the same number.
 */
object ReadAloudText {
    /** Below the engine's limit (TextToSpeech.getMaxSpeechInputLength() is 4000). */
    const val MAX_LENGTH = 3000
    const val ATTRIBUTE = "data-s"

    /** A rough reading pace at 1×, for "~14 min left". */
    private const val CHARS_PER_SECOND = 15.0

    private val BLOCKS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "div", "td", "th", "dt", "dd", "figcaption",
        "pre", "section", "article", "aside", "header", "footer", "caption", "center", "body", "nav", "main", "figure",
    )
    private val SKIPPED = setOf("rt", "rp", "script", "style", "head", "title", "noscript", "template", "svg", "math")
    private val ABBREVIATION = Regex(
        "(?:^|\\s|\\()(?:Mr|Mrs|Ms|Mx|Dr|Prof|St|Sr|Jr|Mt|Lt|Capt|Col|Gen|Sgt|Rev|vs|etc|No|Vol|Ch|e\\.g|i\\.e)\\.$",
        RegexOption.IGNORE_CASE,
    )
    private val SPACES = Regex("\\s+")
    private const val OPENERS = "\"'“‘«¿¡([「『（"

    /** The sentences of one block of text, as ranges into it (trimmed, never only punctuation). */
    fun sentences(text: String, locale: Locale = Locale.ENGLISH): List<IntRange> {
        val found = ArrayList<IntRange>()
        val breaks = BreakIterator.getSentenceInstance(locale)
        breaks.setText(text)
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            trimmed(text, start, end)?.let(found::add)
            start = end
            end = breaks.next()
        }
        val merged = ArrayList<IntRange>()
        for (range in found) {
            val previous = merged.lastOrNull()
            if (previous != null && joins(text.substring(previous.first, previous.last + 1), text.substring(range.first, range.last + 1))) {
                merged[merged.size - 1] = previous.first..range.last
            } else {
                merged += range
            }
        }
        return merged.filter { range -> (range.first..range.last).any { text[it].isLetterOrDigit() } }
            .flatMap { split(text, it) }
    }

    /** "Mr." before a name, or a quote ending in "!" that goes on in lowercase ("Wait!" he said.): one sentence. */
    private fun joins(before: String, after: String): Boolean {
        if (ABBREVIATION.containsMatchIn(before)) return true
        val first = after.firstOrNull { it.isLetter() } ?: return false
        return first.isLowerCase() && after.first().let { it.isLetter() || it == '…' }
    }

    private fun trimmed(text: String, start: Int, end: Int): IntRange? {
        var from = start
        var to = end
        // Scene breaks ("* * *") and other marks before the words aren't read.
        while (from < to && (text[from].isWhitespace() || !(text[from].isLetterOrDigit() || text[from] in OPENERS))) from++
        while (to > from && text[to - 1].isWhitespace()) to--
        return if (from < to) from until to else null
    }

    /** A sentence longer than the engine takes, cut at spaces. */
    private fun split(text: String, range: IntRange): List<IntRange> {
        if (range.last - range.first + 1 <= MAX_LENGTH) return listOf(range)
        val pieces = ArrayList<IntRange>()
        var from = range.first
        while (range.last - from + 1 > MAX_LENGTH) {
            var cut = from + MAX_LENGTH
            while (cut > from + MAX_LENGTH / 2 && !text[cut].isWhitespace()) cut--
            if (cut <= from + MAX_LENGTH / 2) cut = from + MAX_LENGTH
            trimmed(text, from, cut)?.let(pieces::add)
            from = cut
        }
        trimmed(text, from, range.last + 1)?.let(pieces::add)
        return pieces
    }

    /** Wraps every sentence of [document]'s body in spans and returns what to say, in order. */
    fun mark(document: Document, locale: Locale = Locale.ENGLISH): List<Spoken> {
        val body = document.body() ?: return emptyList()
        val runs = ArrayList<Pair<Element, MutableList<TextNode>>>()
        collect(body, body, runs)
        val spoken = ArrayList<Spoken>()
        var offset = 0
        for ((_, nodes) in runs) {
            val texts = nodes.map { it.wholeText }
            val whole = texts.joinToString("")
            val ranges = sentences(whole, locale)
            if (ranges.isEmpty()) continue
            val first = spoken.size
            ranges.forEachIndexed { i, range ->
                val said = whole.substring(range.first, range.last + 1).replace(SPACES, " ")
                spoken += Spoken(said, paragraphEnd = i == ranges.lastIndex, start = offset + range.first)
            }
            offset += whole.length
            var at = 0
            nodes.forEachIndexed { n, node ->
                val text = texts[n]
                wrap(node, text, at, ranges, first)
                at += text.length
            }
        }
        return spoken
    }

    private fun collect(element: Element, block: Element, runs: MutableList<Pair<Element, MutableList<TextNode>>>) {
        for (child in element.childNodes()) {
            when (child) {
                is TextNode -> {
                    val last = runs.lastOrNull()
                    if (last != null && last.first === block && last.second.lastOrNull()?.let { follows(it, child, block) } == true) {
                        last.second += child
                    } else {
                        runs += block to mutableListOf(child)
                    }
                }
                is Element -> {
                    val tag = child.normalName()
                    if (tag in SKIPPED) continue
                    collect(child, if (tag in BLOCKS) child else block, runs)
                }
            }
        }
    }

    /** True when no block (or line break) starts between [before] and [node], both in [block]. */
    private fun follows(before: Node, node: Node, block: Element): Boolean {
        var cursor: Node? = before
        while (cursor != null && cursor !== node) {
            cursor = nextInOrder(cursor, block)
            if (cursor is Element && (cursor.normalName() in BLOCKS || cursor.normalName() == "br")) return false
        }
        return cursor === node
    }

    private fun nextInOrder(node: Node, root: Element): Node? {
        if (node.childNodeSize() > 0) return node.childNode(0)
        var current: Node? = node
        while (current != null && current !== root) {
            current.nextSibling()?.let { return it }
            current = current.parentNode()
        }
        return null
    }

    /** Replaces one text node by plain text and sentence spans; [at] is where it starts in the block's text. */
    private fun wrap(node: TextNode, text: String, at: Int, ranges: List<IntRange>, first: Int) {
        val end = at + text.length
        val pieces = ArrayList<Node>()
        var cursor = at
        ranges.forEachIndexed { i, range ->
            val from = maxOf(range.first, at)
            val to = minOf(range.last + 1, end)
            if (from >= to) return@forEachIndexed
            if (from > cursor) pieces += TextNode(text.substring(cursor - at, from - at))
            pieces.add(Element("span").attr(ATTRIBUTE, (first + i).toString()).appendChild(TextNode(text.substring(from - at, to - at))))
            cursor = to
        }
        if (pieces.isEmpty()) return
        if (cursor < end) pieces += TextNode(text.substring(cursor - at))
        pieces.forEach { node.before(it) }
        node.remove()
    }

    /** The book's language, from `<html lang>`, for where sentences end. */
    fun locale(document: Document): Locale =
        document.selectFirst("html")?.attr("lang")?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.ENGLISH

    /** How long [spoken] takes to say from sentence [from] on, at [speed]. */
    fun millis(spoken: List<Spoken>, from: Int, speed: Float): Long {
        if (spoken.isEmpty()) return 0
        val total = spoken.last().let { it.start + it.text.length }
        val start = spoken.getOrNull(from)?.start ?: total
        return ((total - start) / (CHARS_PER_SECOND * speed.coerceAtLeast(0.1f)) * 1000).toLong()
    }

    /** "~14 min left" in the chapter, from sentence [index]. */
    fun minutesLeft(spoken: List<Spoken>, index: Int, speed: Float): Int = ceil(millis(spoken, index, speed) / 60_000.0).toInt()
}
