package com.aniliphy.japanese

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.Reader

@Serializable
data class DictForm(val text: String, val common: Boolean = false, val appliesTo: List<String> = emptyList())

@Serializable
data class DictSense(
    val pos: List<String> = emptyList(),
    val gloss: List<String> = emptyList(),
    val misc: List<String> = emptyList(),
    val info: List<String> = emptyList(),
)

/** One dictionary word: its written forms, readings and meanings. */
@Serializable
data class DictEntry(
    val id: Long,
    val kanji: List<DictForm> = emptyList(),
    val kana: List<DictForm> = emptyList(),
    val senses: List<DictSense> = emptyList(),
    /** From JMnedict: a person or place name. */
    val name: Boolean = false,
) {
    val common: Boolean get() = kanji.any { it.common } || kana.any { it.common }
    val forms: List<String> get() = kanji.map { it.text } + kana.map { it.text }
    val partsOfSpeech: Set<String> get() = senses.flatMapTo(HashSet()) { it.pos }

    /** The reading for [form]: the first kana that goes with it. */
    fun readingFor(form: String): String? {
        if (kana.any { it.text == form }) return form
        return kana.firstOrNull { it.appliesTo.isEmpty() || "*" in it.appliesTo || form in it.appliesTo }?.text ?: kana.firstOrNull()?.text
    }
}

/** Where words are looked up: the phone's dictionary, or a list in tests. */
fun interface WordSource {
    /** Every entry having one of [texts] as a written form or reading, by that text. */
    fun find(texts: Collection<String>): Map<String, List<DictEntry>>
}

/** A word list in memory (tests, and small dictionaries). */
class ListWordSource(entries: List<DictEntry>) : WordSource {
    private val byForm: Map<String, List<DictEntry>> = entries.flatMap { e -> e.forms.distinct().map { it to e } }.groupBy({ it.first }, { it.second })
    override fun find(texts: Collection<String>) = texts.mapNotNull { t -> byForm[t]?.let { t to it } }.toMap()
}

/** Reading jmdict-simplified's JSON (jmdict-eng, jmnedict-all) one word at a time. */
object JmdictJson {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    /** One element of "words". English meanings only. */
    fun entry(element: JsonObject): DictEntry? {
        val id = element.text("id")?.toLongOrNull() ?: return null
        val kanji = element.objects("kanji").mapNotNull { k ->
            DictForm(k.text("text") ?: return@mapNotNull null, (k["common"] as? JsonPrimitive)?.booleanOrNull == true)
        }
        val kana = element.objects("kana").mapNotNull { k ->
            DictForm(
                k.text("text") ?: return@mapNotNull null,
                (k["common"] as? JsonPrimitive)?.booleanOrNull == true,
                k.strings("appliesToKanji").filter { it != "*" },
            )
        }
        val names = element.objects("translation")
        val senses = if (names.isNotEmpty()) {
            names.map { t ->
                DictSense(pos = t.strings("type"), gloss = t.objects("translation").filter { (it.text("lang") ?: "eng") == "eng" }.mapNotNull { it.text("text") })
            }.filter { it.gloss.isNotEmpty() }
        } else {
            element.objects("sense").map { s ->
                DictSense(
                    pos = s.strings("partOfSpeech"),
                    gloss = s.objects("gloss").filter { (it.text("lang") ?: "eng") == "eng" }.mapNotNull { it.text("text") },
                    misc = s.strings("misc"),
                    info = s.strings("info"),
                )
            }.filter { it.gloss.isNotEmpty() }
        }
        if (senses.isEmpty() || kana.isEmpty() && kanji.isEmpty()) return null
        return DictEntry(id = if (names.isNotEmpty()) -id else id, kanji = kanji, kana = kana, senses = senses, name = names.isNotEmpty())
    }

    fun entry(text: String): DictEntry? = runCatching { entry(json.parseToJsonElement(text).jsonObject) }.getOrNull()

    /**
     * The elements of the top-level array [key] ("words"), each as its JSON text, without reading the whole file
     * (the English JMdict is over 100 MB).
     */
    fun elements(reader: Reader, key: String = "words"): Sequence<String> = sequence {
        val input = if (reader is java.io.BufferedReader) reader else reader.buffered(1 shl 16)
        var depth = 0
        var inString = false
        var escaped = false
        val token = StringBuilder()
        var lastString: String? = null
        var armed = false
        var inArray = false
        val element = StringBuilder()
        while (true) {
            val code = input.read()
            if (code < 0) break
            val c = code.toChar()
            // The wanted array is at depth 2 once open; its elements are everything deeper.
            val keep = inArray && depth >= 3
            if (inString) {
                if (keep) element.append(c) else if (depth == 1) token.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> {
                        inString = false
                        if (!keep && depth == 1) lastString = token.dropLast(1).toString()
                    }
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    token.setLength(0)
                    if (keep) element.append(c)
                }
                ':' -> {
                    if (keep) element.append(c)
                    if (!inArray && depth == 1) armed = lastString == key
                }
                '{', '[' -> {
                    if (!inArray && depth == 1 && c == '[' && armed) inArray = true
                    depth++
                    if (inArray && depth >= 3) element.append(c)
                }
                '}', ']' -> {
                    if (keep) element.append(c)
                    depth--
                    if (inArray && depth == 2 && element.isNotEmpty()) {
                        yield(element.toString())
                        element.setLength(0)
                    } else if (inArray && depth == 1) {
                        inArray = false
                        armed = false
                    }
                }
                else -> if (keep) element.append(c)
            }
        }
    }
}
