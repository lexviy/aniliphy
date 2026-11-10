package com.aniliphy.novels

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

class BookException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class EpubChapter(val path: String, val title: String, val size: Long)

class EpubBook(file: File) : Closeable {
    private val zip: ZipFile
    private val paths: Map<String, String>
    val chapters: List<EpubChapter>

    val chapterStarts: List<Int>?
    private val coverPath: String?

    init {
        try {
            zip = ZipFile(file)
            paths = zip.entries().asSequence().filterNot { it.isDirectory }.associate { it.name.lowercase() to it.name }
            val container = readText("META-INF/container.xml") ?: throw BookException("This EPUB has no container file")
            val opfPath = xml(container).selectFirst("rootfile")?.attr("full-path")
                ?.takeIf { it.isNotBlank() } ?: throw BookException("This EPUB doesn't say where its contents are")
            val opf = xml(readText(opfPath) ?: throw BookException("This EPUB's contents are missing"))
            val base = opfPath.substringBeforeLast('/', "")

            class Item(val id: String, val path: String, val type: String, val properties: String)
            val items = opf.select("manifest > item").map { item ->
                Item(
                    item.attr("id"),
                    resolve(base, item.attr("href")),
                    item.attr("media-type").lowercase(),
                    item.attr("properties"),
                )
            }
            val byId = items.associateBy { it.id }
            val isDocument = { item: Item ->
                ("html" in item.type || item.type.isEmpty()) && "nav" !in item.properties.split(' ') && has(item.path)
            }
            var ordered = opf.select("spine > itemref").mapNotNull { byId[it.attr("idref")] }
                .filter(isDocument).distinctBy { it.path }
            if (ordered.isEmpty()) ordered = items.filter(isDocument)

            val titles = tocTitles(opf, items.firstOrNull { "nav" in it.properties.split(' ') }?.path, byId[opf.selectFirst("spine")?.attr("toc").orEmpty()]?.path)
            val listed = ordered.map { titles[it.path.substringAfterLast('/')] }
            val spread = listed.any { it == null } && listed.count { it != null && ChapterRules.isChapterTitle(it) } >= MIN_LISTED_CHAPTERS
            chapterStarts = if (spread) listed.indices.filter { listed[it] != null } else null
            var current: String? = null
            chapters = ordered.mapIndexed { index, item ->
                listed[index]?.let { current = it }
                val inherited = if (spread) current else null
                EpubChapter(item.path, listed[index] ?: inherited ?: heading(item.path), sizeOf(item.path))
            }
            val metaCover = opf.select("metadata > meta[name=cover]").firstOrNull()?.attr("content")
            coverPath = items.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
                ?: metaCover?.let { byId[it]?.path }
                ?: items.firstOrNull { it.type.startsWith("image/") && "cover" in it.path.lowercase() }?.path
                ?: items.firstOrNull { it.type.startsWith("image/") }?.path
        } catch (e: BookException) {
            throw e
        } catch (e: Exception) {
            throw BookException("Could not read the EPUB: ${e.message}", e)
        }
    }

    private fun tocTitles(opf: Document, navPath: String?, ncxPath: String?): Map<String, String> {
        val titles = HashMap<String, String>()
        val put = { href: String, title: String ->
            val key = decode(href.substringBefore('#')).substringAfterLast('/')
            val clean = title.replace(Regex("\\s+"), " ").trim()
            if (key.isNotEmpty() && clean.isNotEmpty()) titles.putIfAbsent(key, clean)
        }
        navPath?.let(::readText)?.let { text ->
            val nav = Jsoup.parse(text)
            val toc = nav.selectFirst("nav[epub:type=toc]") ?: nav.selectFirst("nav") ?: nav
            toc.select("a[href]").forEach { put(it.attr("href"), it.text()) }
        }
        (ncxPath ?: paths.values.firstOrNull { it.endsWith(".ncx", ignoreCase = true) })?.let(::readText)?.let { text ->
            xml(text).select("navPoint").forEach { point ->
                val label = point.selectFirst("navLabel")?.text().orEmpty()
                val href = point.selectFirst("content")?.attr("src").orEmpty()
                put(href, label)
            }
        }
        return titles
    }

    private fun heading(path: String): String = try {
        val root = Jsoup.parse(readText(path).orEmpty()).body()
        val text = root.selectFirst("h1, h2, h3, h4")?.text()?.takeIf { it.isNotBlank() } ?: root.text()
        text.replace(Regex("\\s+"), " ").trim().take(HEADING_LENGTH)
    } catch (_: Exception) {
        ""
    }

    private fun has(path: String) = path.lowercase() in paths

    private fun sizeOf(path: String) = paths[path.lowercase()]?.let { zip.getEntry(it)?.size }?.coerceAtLeast(1) ?: 1L

    fun read(path: String): ByteArray? {
        val name = paths[path.lowercase()] ?: return null
        val entry = zip.getEntry(name) ?: return null
        return synchronized(zip) { zip.getInputStream(entry).use { it.readBytes() } }
    }

    private fun readText(path: String) = read(path)?.toString(Charsets.UTF_8)

    fun cover(): ByteArray? = coverPath?.let(::read)

    override fun close() = zip.close()

    companion object {
        private const val HEADING_LENGTH = 80
        private const val MIN_LISTED_CHAPTERS = 2

        private fun xml(text: String) = Jsoup.parse(text, "", Parser.xmlParser())

        private fun decode(text: String) = try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            text
        }

        fun resolve(base: String, href: String): String {
            val clean = decode(href.substringBefore('#').substringBefore('?')).replace('\\', '/')
            val parts = ArrayList<String>()
            val joined = if (clean.startsWith("/")) clean else "$base/$clean"
            joined.split('/').forEach { part ->
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.add(part)
                }
            }
            return parts.joinToString("/")
        }
    }
}
