package com.aniliphy.anilist

/** Which threads to list. */
data class ThreadQuery(
    val category: Int? = null,
    val mediaId: Int? = null,
    val search: String? = null,
    val subscribed: Boolean = false,
    val sort: String = "REPLIED_AT_DESC",
)

/** AniList's forum categories (fixed on their side; there's no query that lists them). */
object ForumCategories {
    const val ANIME = 1
    const val MANGA = 2
    const val RELEASE_DISCUSSION = 5

    val all: List<ThreadCategory> = listOf(
        ThreadCategory(1, "Anime"),
        ThreadCategory(2, "Manga"),
        ThreadCategory(3, "Light Novels"),
        ThreadCategory(4, "Visual Novels"),
        ThreadCategory(5, "Release Discussion"),
        ThreadCategory(7, "General"),
        ThreadCategory(8, "News"),
        ThreadCategory(9, "Music"),
        ThreadCategory(10, "Gaming"),
        ThreadCategory(15, "Recommendations"),
        ThreadCategory(16, "Forum Games"),
        ThreadCategory(17, "Misc"),
        ThreadCategory(18, "AniList Apps"),
        ThreadCategory(11, "Site Feedback"),
        ThreadCategory(12, "Bug Reports"),
        ThreadCategory(13, "Site Announcements"),
    )
}

/** Finds the discussion thread for one episode or chapter among a title's release threads. */
object EpisodeThreads {
    /**
     * The thread whose title names [number] as an episode (or chapter), like "[Spoilers] X - Episode 7 Discussion".
     * Ranges count ("Chapters 10-12"), and so does a season's numbering only when it's the same number, so
     * "Episode 17" doesn't match 7. Of several, the one with the most replies.
     */
    fun find(threads: List<ForumThread>, number: Int, chapter: Boolean): ForumThread? =
        threads.filter { t -> numbers(t.title, chapter)?.let { number in it } == true }.maxByOrNull { it.replyCount }

    private fun pattern(chapter: Boolean): Regex {
        val word = if (chapter) "(?:chapters?|ch\\.?)" else "(?:episodes?|ep\\.?)"
        return Regex("""(?i)\b$word\s*#?\s*0*(\d+)(?:\s*[-–~]\s*0*(\d+))?\b""")
    }

    /** The episode (or chapter) numbers a thread's title names, like 7..7 or 170..172; null when it names none. */
    fun numbers(title: String, chapter: Boolean): IntRange? {
        val m = pattern(chapter).find(title) ?: return null
        val from = m.groupValues[1].toIntOrNull() ?: return null
        val to = m.groupValues[2].toIntOrNull() ?: from
        return from..maxOf(from, to)
    }
}

/** AniList's forum markup, turned into blocks the app can show. */
object ForumText {
    sealed interface Block {
        data class Text(val spans: List<Span>, val heading: Boolean = false, val centered: Boolean = false) : Block
        data class Image(val url: String) : Block
        data class Video(val url: String) : Block
        data class Spoiler(val blocks: List<Block>) : Block
        data class Quote(val blocks: List<Block>) : Block
        data object Rule : Block
    }

    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    private val media = Regex("""(?i)\b(img|webm|youtube)\s*(\d*%?)\s*\(\s*([^)\s]+)\s*\)""")
    private val htmlImg = Regex("""(?i)<img[^>]*src=["']([^"']+)["'][^>]*>""")

    fun parse(source: String?): List<Block> {
        if (source.isNullOrBlank()) return emptyList()
        val text = decode(source)
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(htmlImg) { "img(${it.groupValues[1]})" }
            .replace(Regex("""(?i)</?(p|div|span|center|b|i|strong|em|u|h\d)[^>]*>"""), "")
        return blocks(text, centered = false)
    }

    private fun blocks(text: String, centered: Boolean): List<Block> {
        val out = mutableListOf<Block>()
        var rest = text
        // Spoilers and centred parts can hold anything, so they're taken out first, in order.
        val special = Regex("""(?s)~!(.*?)!~|~~~(.*?)~~~""")
        while (true) {
            val m = special.find(rest) ?: break
            out += plain(rest.substring(0, m.range.first), centered)
            val spoiler = m.groups[1]
            if (spoiler != null) {
                out += Block.Spoiler(blocks(spoiler.value, centered))
            } else {
                out += blocks(m.groupValues[2], centered = true)
            }
            rest = rest.substring(m.range.last + 1)
        }
        out += plain(rest, centered)
        return out
    }

    private fun plain(text: String, centered: Boolean): List<Block> {
        val out = mutableListOf<Block>()
        val quote = mutableListOf<String>()
        val para = StringBuilder()
        fun flushPara() {
            val t = para.toString().trim()
            if (t.isNotEmpty()) out += Block.Text(inline(t), centered = centered)
            para.clear()
        }
        fun flushQuote() {
            if (quote.isNotEmpty()) out += Block.Quote(plain(quote.joinToString("\n"), centered))
            quote.clear()
        }
        for (raw in text.split("\n")) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith(">")) {
                flushPara()
                quote += line.trimStart().removePrefix(">").removePrefix(" ")
                continue
            }
            flushQuote()
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flushPara()
                Regex("""^(-{3,}|\*{3,}|_{3,})$""").matches(trimmed) -> {
                    flushPara()
                    out += Block.Rule
                }
                trimmed.startsWith("#") -> {
                    flushPara()
                    out += Block.Text(inline(trimmed.trimStart('#').trim()), heading = true, centered = centered)
                }
                media.containsMatchIn(trimmed) -> {
                    var last = 0
                    for (m in media.findAll(trimmed)) {
                        para.append(trimmed.substring(last, m.range.first)).append(' ')
                        flushPara()
                        val url = m.groupValues[3]
                        out += when (m.groupValues[1].lowercase()) {
                            "img" -> Block.Image(url)
                            "youtube" -> Block.Video(if (url.startsWith("http")) url else "https://youtu.be/$url")
                            else -> Block.Video(url)
                        }
                        last = m.range.last + 1
                    }
                    para.append(trimmed.substring(last)).append(' ')
                }
                else -> para.append(trimmed).append('\n')
            }
        }
        flushQuote()
        flushPara()
        return out
    }

    /** Bold, italics, strikethrough, code and links within a paragraph. */
    fun inline(text: String): List<Span> {
        val token = Regex("""\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|`([^`]+)`|\[([^\]]+)]\((\S+?)\)|(?<![\w*])[*_]([^*_\n]+)[*_](?![\w*])|(https?://\S+)""")
        val out = mutableListOf<Span>()
        var last = 0
        for (m in token.findAll(text)) {
            if (m.range.first > last) out += Span(text.substring(last, m.range.first))
            val g = m.groupValues
            out += when {
                g[1].isNotEmpty() -> Span(g[1], bold = true)
                g[2].isNotEmpty() -> Span(g[2], bold = true)
                g[3].isNotEmpty() -> Span(g[3], strike = true)
                g[4].isNotEmpty() -> Span(g[4], code = true)
                g[5].isNotEmpty() -> Span(g[5], link = g[6])
                g[7].isNotEmpty() -> Span(g[7], italic = true)
                else -> Span(g[8], link = g[8])
            }
            last = m.range.last + 1
        }
        if (last < text.length) out += Span(text.substring(last))
        return out.filter { it.text.isNotEmpty() }
    }

    private fun decode(s: String) = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /** A short plain preview, spoilers left out. */
    fun preview(source: String?, max: Int = 160): String =
        plainBio(source)?.let { if (it.length > max) it.take(max).trimEnd() + "…" else it }.orEmpty()
}
