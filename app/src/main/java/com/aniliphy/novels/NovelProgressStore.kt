package com.aniliphy.novels

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

data class BookState(
    val chapter: Int = 0,
    val position: Double = 0.0,
    val segments: List<Segment> = emptyList(),
    val length: Int = 0,
    val auto: Boolean = false,
    val starts: List<Int>? = null,
)

data class ImportResult(val imported: Int, val kept: Int)

data class LastRead(val book: String, val chapter: Int, val updated: Double, val title: String?, val label: String = "")

class NovelProgressStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    private class Media(var last: String?, var updated: Double, var title: String?, val books: LinkedHashMap<String, BookState>)

    private fun load(): LinkedHashMap<String, Media> =
        parse(file.readTextOrNull()?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() })

    private fun parse(root: JsonObject?): LinkedHashMap<String, Media> {
        val all = LinkedHashMap<String, Media>()
        root?.forEach { (key, value) ->
            val item = value as? JsonObject ?: return@forEach
            val books = LinkedHashMap<String, BookState>()
            (item["books"] as? JsonObject)?.forEach { (name, raw) -> (raw as? JsonObject)?.let { books[name] = parseBook(it) } }
            all[key] = Media(item.text("last"), (item["updated"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, item.text("title"), books)
        }
        return all
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun parseBook(item: JsonObject) = BookState(
        chapter = (item["chapter"] as? JsonPrimitive)?.intOrNull ?: 0,
        position = (item["position"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
        segments = (item["segments"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val pair = raw as? JsonArray ?: return@mapNotNull null
            val start = (pair.getOrNull(0) as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            Segment(start, (pair.getOrNull(1) as? JsonPrimitive)?.intOrNull)
        },
        length = (item["length"] as? JsonPrimitive)?.intOrNull ?: 0,
        auto = (item["auto"] as? JsonPrimitive)?.booleanOrNull ?: false,
        starts = (item["starts"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull },
    )

    private fun save(all: Map<String, Media>) {
        val root = JsonObject(
            all.mapValues { (_, media) ->
                val fields = LinkedHashMap<String, JsonElement>()
                fields["last"] = media.last?.let(::JsonPrimitive) ?: JsonNull
                fields["updated"] = JsonPrimitive(media.updated)
                media.title?.let { fields["title"] = JsonPrimitive(it) }
                fields["books"] = JsonObject(media.books.mapValues { (_, book) -> bookJson(book) })
                JsonObject(fields)
            },
        )
        file.parentFile?.mkdirs()
        file.writeTextAtomically(root.toString())
    }

    private fun bookJson(book: BookState): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "chapter" to JsonPrimitive(book.chapter),
            "position" to JsonPrimitive(book.position),
            "segments" to JsonArray(
                book.segments.map { JsonArray(listOf(JsonPrimitive(it.start), it.end?.let(::JsonPrimitive) ?: JsonNull)) },
            ),
            "length" to JsonPrimitive(book.length),
            "auto" to JsonPrimitive(book.auto),
        )
        book.starts?.let { starts -> fields["starts"] = JsonArray(starts.map(::JsonPrimitive)) }
        return JsonObject(fields)
    }

    fun raw(): Map<String, JsonObject> = synchronized(lock) {
        val root = file.readTextOrNull()?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        root.orEmpty().mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }.toMap()
    }

    fun state(series: String, book: String): BookState = synchronized(lock) { load()[series]?.books?.get(book) ?: BookState() }

    fun save(series: String, book: String, state: BookState, title: String? = null, touch: Boolean = true, now: Double = System.currentTimeMillis() / 1000.0) =
        synchronized(lock) {
            val all = load()
            val media = all.getOrPut(series) { Media(null, 0.0, null, LinkedHashMap()) }
            media.books[book] = state
            if (touch) {
                media.last = book
                media.updated = now
            }
            title?.let { media.title = it }
            save(all)
        }

    fun baseFor(series: String, volumes: List<String>, index: Int): Int = synchronized(lock) {
        val books = load()[series]?.books.orEmpty()
        volumes.take(index).sumOf { name -> books[name]?.let { ChapterRules.counted(it.segments, it.length, starts = it.starts) } ?: 0 }
    }

    fun pickVolume(series: String, volumes: List<String>): Int = synchronized(lock) {
        val last = load()[series]?.last
        volumes.indexOf(last).coerceAtLeast(0)
    }

    /** The volume of [novel] read last, or its first one: the one Continue opens and whose cover it shows. */
    fun readingVolume(novel: NovelSeries): VolumeFile? =
        novel.volumes.getOrNull(pickVolume(novel.key, novel.volumes.map { it.name }))

    fun lastRead(series: String): LastRead? = synchronized(lock) {
        val media = load()[series] ?: return null
        val last = media.last ?: return null
        val book = media.books[last] ?: return null
        LastRead(
            last,
            book.chapter + 1,
            media.updated,
            media.title,
            ChapterRules.label(book.segments, book.length, book.chapter, book.starts),
        )
    }

    fun move(from: String, to: String) = synchronized(lock) {
        if (from == to) return@synchronized
        val all = load()
        val moved = all.remove(from) ?: return@synchronized
        val existing = all[to]
        if (existing == null) {
            all[to] = moved
        } else {
            moved.books.forEach { (name, book) -> existing.books.putIfAbsent(name, book) }
            if (moved.updated > existing.updated) {
                existing.last = moved.last
                existing.updated = moved.updated
            }
        }
        save(all)
    }

    fun import(
        text: String,
        seriesFor: (series: String, book: String) -> String = { series, _ -> series },
        prepare: (book: String, state: BookState) -> BookState = { _, state -> state },
    ): ImportResult = synchronized(lock) {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        val valid = root != null && root.values.all { (it as? JsonObject)?.get("books") is JsonObject }
        require(valid) { "Not a progress file" }
        val incoming = parse(root)
        val all = load()
        var imported = 0
        var kept = 0
        incoming.forEach { (series, media) ->
            media.books.forEach { (name, book) ->
                val key = seriesFor(series, name)
                val target = all.getOrPut(key) { Media(null, 0.0, media.title, LinkedHashMap()) }
                if ((target.books[name]?.length ?: 0) != 0 && target.updated >= media.updated) {
                    kept++
                } else {
                    target.books[name] = prepare(name, book)
                    imported++
                    if (name == media.last && media.updated >= target.updated) {
                        target.last = name
                        target.updated = media.updated
                    }
                }
            }
        }
        save(all)
        ImportResult(imported, kept)
    }

    fun forget(series: String) = synchronized(lock) {
        val all = load()
        if (all.remove(series) != null) save(all)
    }
}
