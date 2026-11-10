package com.aniliphy.novels

import com.aniliphy.anilist.Media
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class VolumeFile(val uri: String, val name: String, val size: Long = 0, val modified: Long = 0) {
    val stem: String get() = name.substringBeforeLast('.')
    val isEpub: Boolean get() = name.endsWith(".epub", ignoreCase = true)
    val isBook: Boolean get() = name.endsWith(".epub", ignoreCase = true) || name.endsWith(".pdf", ignoreCase = true)
}

data class NovelSeries(
    val key: String,
    val title: String,
    val volumes: List<VolumeFile>,
    val media: Media? = null,
    val custom: Boolean = false,
) {
    val mediaId: Int? get() = media?.id ?: key.toIntOrNull()
}

@Serializable
data class CustomNovel(val id: String, val title: String, val files: List<String>? = null)

object NovelLibrary {
    const val CUSTOM_PREFIX = "custom:"
    const val LOCAL_PREFIX = "local:"

    private val VOLUME_SUFFIX = Regex("[\\s_\\-–:,(\\[]*(?:vol(?:ume)?\\.?|book|v)\\s*\\d+.*$", RegexOption.IGNORE_CASE)
    private val NOISE = Regex("\\s*[(\\[]\\s*(?:light novel|ln|epub|pdf)\\s*[)\\]]\\s*", RegexOption.IGNORE_CASE)

    fun slug(title: String) = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "novel" }

    fun guessTitle(stem: String): String {
        val plain = NOISE.replace(stem, " ").trim()
        val cut = VOLUME_SUFFIX.replace(plain, "").trim(' ', '-', '_')
        return cut.ifEmpty { plain }.replace("_ ", ": ").replace('_', ' ').replace(Regex("\\s+"), " ")
    }

    private fun normal(text: String) = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()

    fun matches(title: String, stem: String): Boolean {
        val wanted = normal(title)
        val name = normal(NOISE.replace(stem, " "))
        return wanted.isNotEmpty() && name.isNotEmpty() && (wanted in name || name in wanted)
    }

    private val NUMBERS = Regex("(\\d+)")

    val NATURAL = Comparator<String> { a, b ->
        val left = naturalParts(a)
        val right = naturalParts(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val x = left[i]
            val y = right[i]
            val order = when {
                x is Long && y is Long -> x.compareTo(y)
                else -> x.toString().compareTo(y.toString())
            }
            if (order != 0) return@Comparator order
        }
        left.size.compareTo(right.size)
    }

    private fun naturalParts(text: String): List<Any> {
        val parts = ArrayList<Any>()
        var at = 0
        NUMBERS.findAll(text).forEach { match ->
            if (match.range.first > at) parts.add(text.substring(at, match.range.first).lowercase())
            parts.add(match.value.toLongOrNull() ?: Long.MAX_VALUE)
            at = match.range.last + 1
        }
        if (at < text.length) parts.add(text.substring(at).lowercase())
        return parts
    }

    fun chooseFormats(files: List<VolumeFile>, preferred: BookFormat): List<VolumeFile> =
        files.filter { it.isBook }
            .groupBy { it.stem }
            .map { (_, same) ->
                same.firstOrNull { it.isEpub == (preferred == BookFormat.Epub) } ?: same.first()
            }
            .sortedWith(compareBy(NATURAL) { it.stem })

    fun group(
        files: List<VolumeFile>,
        list: List<Media>,
        customs: List<CustomNovel>,
        links: Map<String, Int> = emptyMap(),
    ): List<NovelSeries> {
        val left = files.toMutableList()
        val series = ArrayList<NovelSeries>()
        val take = { test: (VolumeFile) -> Boolean ->
            val taken = left.filter(test)
            left.removeAll(taken)
            taken
        }
        customs.forEach { novel ->
            val found = if (novel.files.isNullOrEmpty()) {
                take { matches(novel.title, it.stem) }
            } else {
                take { it.uri in novel.files }
            }
            series.add(NovelSeries(novel.id, novel.title, found, custom = true))
        }
        val byId = list.associateBy { it.id }
        val linkedTitles = links.filterValues { it in byId }
        linkedTitles.forEach { (title, id) ->
            val found = take { guessTitle(it.stem).equals(title, ignoreCase = true) }
            if (found.isNotEmpty()) series.add(NovelSeries(id.toString(), byId.getValue(id).title.display, found, byId[id]))
        }
        list.sortedByDescending { maxOf(it.title.english.orEmpty().length, it.title.romaji.orEmpty().length) }.forEach { media ->
            if (series.any { it.media?.id == media.id }) return@forEach
            val names = listOfNotNull(media.title.english, media.title.romaji).filter { it.isNotBlank() }
            val found = take { file -> names.any { matches(it, file.stem) } }
            if (found.isNotEmpty()) series.add(NovelSeries(media.id.toString(), media.title.display, found, media))
        }
        left.groupBy { guessTitle(it.stem) }.forEach { (title, found) ->
            val id = links[title]
            series.add(NovelSeries(id?.toString() ?: (LOCAL_PREFIX + slug(title)), title, found))
        }
        return series.map { it.copy(volumes = it.volumes.sortedWith(compareBy(NATURAL) { file -> file.stem })) }
            .sortedBy { it.title.lowercase() }
    }
}

class NovelCustomStore(private val folder: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val customFile get() = File(folder, "custom_novels.json")
    private val linksFile get() = File(folder, "novel_links.json")

    fun customs(): List<CustomNovel> = runCatching {
        customFile.readTextOrNull()?.let { json.decodeFromString<List<CustomNovel>>(it) }
    }.getOrNull().orEmpty().sortedBy { it.title.lowercase() }

    fun add(title: String, files: List<String>?): Boolean {
        val id = NovelLibrary.CUSTOM_PREFIX + NovelLibrary.slug(title)
        val all = customs()
        if (all.any { it.id == id }) return false
        write(all + CustomNovel(id, title.trim(), files?.takeIf { it.isNotEmpty() }))
        return true
    }

    fun update(id: String, title: String, files: List<String>?) =
        write(customs().map { if (it.id == id) it.copy(title = title.trim().ifEmpty { it.title }, files = files?.takeIf { f -> f.isNotEmpty() }) else it })

    fun remove(id: String) = write(customs().filterNot { it.id == id })

    private fun write(all: List<CustomNovel>) {
        folder.mkdirs()
        customFile.writeTextAtomically(json.encodeToString(all))
    }

    fun links(): Map<String, Int> = runCatching {
        linksFile.readTextOrNull()?.let { json.decodeFromString<Map<String, Int>>(it) }
    }.getOrNull().orEmpty()

    fun link(title: String, mediaId: Int?) {
        val next = links().toMutableMap()
        if (mediaId == null) next.remove(title) else next[title] = mediaId
        folder.mkdirs()
        linksFile.writeTextAtomically(json.encodeToString(next.toMap()))
    }
}
