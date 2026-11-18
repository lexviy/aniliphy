package com.aniliphy.japanese

import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A word kept from a subtitle line, with where it was heard. */
@Serializable
data class SavedWord(
    val id: String,
    val entryId: Long,
    val word: String,
    val reading: String? = null,
    val meanings: List<String> = emptyList(),
    /** The whole line it was in. */
    val sentence: String = "",
    /** How it was written in the line (食べられない). */
    val surface: String = "",
    val mediaId: Int = 0,
    val title: String = "",
    val episode: String = "",
    val positionMs: Long = 0,
    /** A file in the words folder: the frame on screen when it was saved. */
    val frame: String? = null,
    val addedAt: Long = 0,
    val sentToAnki: Boolean = false,
) {
    /** "Frieren · ep 4 · 12:31" */
    val source: String
        get() = listOf(title, "ep $episode".takeIf { episode.isNotEmpty() }, clock(positionMs)).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · ")

    companion object {
        fun clock(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** Saved words in words.json, frames next to it in words/. */
class WordStore(private val dir: File) {
    private val file = File(dir, FILE)
    val frames = File(dir, FRAMES)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun all(): List<SavedWord> = runCatching { json.decodeFromString<List<SavedWord>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    private fun write(list: List<SavedWord>) = file.writeTextAtomically(json.encodeToString(list))

    /** Newest first. The same word from the same line isn't kept twice. */
    @Synchronized
    fun add(word: SavedWord): List<SavedWord> {
        val list = all()
        val same = list.firstOrNull { it.entryId == word.entryId && it.sentence == word.sentence }
        if (same != null) {
            word.frame?.let { File(frames, it).delete() }
            return list
        }
        return (listOf(word) + list).also(::write)
    }

    @Synchronized
    fun remove(id: String): List<SavedWord> {
        val list = all()
        list.firstOrNull { it.id == id }?.frame?.let { File(frames, it).delete() }
        return list.filter { it.id != id }.also(::write)
    }

    @Synchronized
    fun markSent(ids: Set<String>): List<SavedWord> = all().map { if (it.id in ids) it.copy(sentToAnki = true) else it }.also(::write)

    companion object {
        const val FILE = "words.json"
        const val FRAMES = "words"
    }
}

/** What goes out: to AnkiDroid as note fields, or in a .zip for Anki on the PC. */
object WordExport {
    val FIELDS = listOf("Word", "Reading", "Meaning", "Sentence", "Picture", "Source")

    private fun html(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    /** The line with the word in bold. */
    fun sentence(word: SavedWord): String {
        val at = if (word.surface.isNotEmpty()) word.sentence.indexOf(word.surface) else -1
        if (at < 0) return html(word.sentence)
        return html(word.sentence.substring(0, at)) + "<b>" + html(word.surface) + "</b>" + html(word.sentence.substring(at + word.surface.length))
    }

    fun meaning(word: SavedWord): String = word.meanings.mapIndexed { i, m -> if (word.meanings.size > 1) "${i + 1}. ${html(m)}" else html(m) }.joinToString("<br>")

    /** Fields in [FIELDS] order; [picture] is the image's name in Anki's media (or null). */
    fun fields(word: SavedWord, picture: String?): List<String> = listOf(
        html(word.word),
        html(word.reading.orEmpty()),
        meaning(word),
        sentence(word),
        picture?.let { "<img src=\"${html(it)}\">" }.orEmpty(),
        html(word.source),
    )

    /** One line per word, tab-separated, for Anki's import (tabs and newlines inside fields are taken out). */
    fun tsv(words: List<SavedWord>): String = buildString {
        append("#separator:tab\n#html:true\n#columns:").append(FIELDS.joinToString("\t")).append('\n')
        for (w in words) {
            append(fields(w, w.frame).joinToString("\t") { it.replace('\t', ' ').replace("\n", "<br>") }).append('\n')
        }
    }

    /** words.tsv plus the frames, for copying into Anki's media folder. */
    fun zip(words: List<SavedWord>, frames: File, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("words.tsv"))
            zip.write(tsv(words).toByteArray())
            zip.closeEntry()
            for (w in words) {
                val frame = w.frame?.let { File(frames, it) }?.takeIf { it.isFile } ?: continue
                zip.putNextEntry(ZipEntry("media/${frame.name}"))
                frame.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
