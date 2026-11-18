package com.aniliphy.japanese

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.core.PrefStore
import com.aniliphy.core.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FilterInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

enum class DualLayout(val label: String) { Split("JP top · EN bottom"), Stacked("Both bottom"), JapaneseOnly("JP only") }

class JapaneseSettings(context: Context) : PrefStore(context, "japanese") {
    /** Japanese lines drawn by the app, so words can be tapped. */
    var dual by boolPref("dual", true)
    var layout by enumPref("layout", DualLayout.Split, DualLayout.entries)
    var furigana by boolPref("furigana", true)
    var pauseOnTap by boolPref("pause_on_tap", true)
    var names by boolPref("names", false)
    var ankiDeck by stringPref("anki_deck", "aniliphy")
    var ankiModel by stringPref("anki_model", "")

    companion object {
        @Volatile private var instance: JapaneseSettings? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: JapaneseSettings(context.applicationContext).also { instance = it } }
    }
}

/** One dictionary on the phone: an SQLite file with each word as JSON and an index of its forms. */
class DictionaryDb private constructor(val file: File) : WordSource {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    private val cache = object : LinkedHashMap<Long, DictEntry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, DictEntry>?) = size > 2000
    }

    val version: String? get() = meta("version")
    val count: Int get() = meta("count")?.toIntOrNull() ?: 0

    private fun meta(key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized
    override fun find(texts: Collection<String>): Map<String, List<DictEntry>> {
        val out = HashMap<String, MutableList<DictEntry>>()
        texts.distinct().chunked(400).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT f.text, e.id, e.data FROM forms f JOIN entries e ON e.id = f.entry WHERE f.text IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(1)
                    val entry = cache[id] ?: runCatching { JmdictJson.json.decodeFromString<DictEntry>(c.getString(2)) }.getOrNull()?.also { cache[id] = it } ?: continue
                    val list = out.getOrPut(c.getString(0)) { mutableListOf() }
                    if (list.none { it.id == id }) list += entry
                }
            }
        }
        return out
    }

    fun close() = db.close()

    companion object {
        fun open(file: File): DictionaryDb? = if (file.isFile) runCatching { DictionaryDb(file) }.getOrNull() else null

        /** Builds [into] from a jmdict-simplified .json.zip, a word at a time; [progress] gets 0..1 as the file is read. */
        suspend fun build(zip: File, into: File, version: String, progress: (Float) -> Unit) {
            val temp = File(into.path + ".part").apply { delete() }
            File(temp.path + "-journal").delete()
            val db = SQLiteDatabase.openOrCreateDatabase(temp, null)
            try {
                db.rawQuery("PRAGMA journal_mode = OFF", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous = OFF")
                db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
                db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, data TEXT NOT NULL)")
                db.execSQL("CREATE TABLE forms (text TEXT NOT NULL, entry INTEGER NOT NULL)")
                val total = zip.length().coerceAtLeast(1)
                var read = 0L
                val counting = object : FilterInputStream(zip.inputStream().buffered(1 shl 16)) {
                    override fun read(): Int = super.read().also { if (it >= 0) read++ }
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
                }
                var count = 0
                ZipInputStream(counting).use { z ->
                    val entry = generateSequence { z.nextEntry }.firstOrNull { it.name.endsWith(".json") } ?: error("No dictionary in the download")
                    val insertEntry = db.compileStatement("INSERT OR REPLACE INTO entries (id, data) VALUES (?, ?)")
                    val insertForm = db.compileStatement("INSERT INTO forms (text, entry) VALUES (?, ?)")
                    db.beginTransaction()
                    try {
                        for (text in JmdictJson.elements(z.reader(Charsets.UTF_8))) {
                            val word = JmdictJson.entry(text) ?: continue
                            insertEntry.bindLong(1, word.id)
                            insertEntry.bindString(2, JmdictJson.json.encodeToString(word))
                            insertEntry.executeInsert()
                            for (form in word.forms.distinct()) {
                                insertForm.bindString(1, form)
                                insertForm.bindLong(2, word.id)
                                insertForm.executeInsert()
                            }
                            count++
                            if (count % 2000 == 0) {
                                db.setTransactionSuccessful()
                                db.endTransaction()
                                coroutineContext.ensureActive()
                                progress((read.toFloat() / total).coerceAtMost(0.99f))
                                db.beginTransaction()
                            }
                        }
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                    check(entry.name.isNotEmpty())
                }
                if (count == 0) error("The download had no words")
                db.execSQL("CREATE INDEX forms_text ON forms (text)")
                val meta = ContentValues()
                listOf("version" to version, "count" to count.toString()).forEach { (k, v) ->
                    meta.clear()
                    meta.put("key", k)
                    meta.put("value", v)
                    db.insert("meta", null, meta)
                }
            } finally {
                db.close()
            }
            into.delete()
            if (!temp.renameTo(into)) error("Couldn't keep the dictionary")
            progress(1f)
        }
    }
}

/**
 * The dictionaries on the phone (JMdict, and JMnedict names when wanted), downloaded from jmdict-simplified's latest
 * release on request. Lookups go to both.
 */
object JapaneseDictionary : WordSource {
    enum class Part(val label: String, val asset: Regex, val file: String) {
        Words("JMdict (English)", Regex("""^jmdict-eng-\d.*\.json\.zip$"""), "jmdict.db"),
        Names("Names (JMnedict)", Regex("""^jmnedict-all-.*\.json\.zip$"""), "jmnedict.db"),
    }

    sealed interface Job {
        data class Downloading(val part: Part, val done: Long, val total: Long) : Job
        data class Building(val part: Part, val fraction: Float) : Job
        data class Failed(val part: Part, val message: String) : Job
    }

    private const val RELEASES = "https://api.github.com/repos/scriptin/jmdict-simplified/releases/latest"

    var job by mutableStateOf<Job?>(null)
        private set

    /** Bumped when a dictionary comes or goes, for screens to look again. */
    var version by mutableStateOf(0)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: kotlinx.coroutines.Job? = null
    private val open = HashMap<Part, DictionaryDb?>()
    private var dir: File? = null
    private var names = false

    fun init(context: Context) {
        if (dir == null) dir = File(context.filesDir, "japanese").apply { mkdirs() }
        names = JapaneseSettings.get(context).names
    }

    private fun file(part: Part) = File(dir!!, part.file)

    @Synchronized
    fun db(part: Part): DictionaryDb? {
        if (dir == null) return null
        return open.getOrPut(part) { DictionaryDb.open(file(part)) }
    }

    val ready: Boolean get() = db(Part.Words) != null

    override fun find(texts: Collection<String>): Map<String, List<DictEntry>> {
        val words = db(Part.Words)?.find(texts).orEmpty()
        val people = if (names) db(Part.Names)?.find(texts).orEmpty() else emptyMap()
        if (people.isEmpty()) return words
        return (words.keys + people.keys).associateWith { words[it].orEmpty() + people[it].orEmpty() }
    }

    fun setNames(context: Context, on: Boolean) {
        JapaneseSettings.get(context).names = on
        names = on
        if (on && db(Part.Names) == null) download(context, Part.Names)
        version++
    }

    fun download(context: Context, part: Part) {
        init(context)
        if (running?.isActive == true) return
        running = scope.launch {
            val zip = File(context.cacheDir, "${part.file}.zip")
            try {
                job = Job.Downloading(part, 0, 0)
                val release = JmdictJson.json.parseToJsonElement(Web.get(RELEASES, mapOf("Accept" to "application/vnd.github+json")).text).jsonObject
                val tag = (release["tag_name"] as? JsonPrimitive)?.contentOrNull ?: "?"
                val asset = (release["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .firstOrNull { part.asset.matches((it["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()) }
                    ?: error("No ${part.label} in the latest release")
                val url = (asset["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: error("No download link")
                Web.download(url, zip) { done, total -> job = Job.Downloading(part, done, total) }
                job = Job.Building(part, 0f)
                synchronized(this@JapaneseDictionary) { open.remove(part)?.close() }
                DictionaryDb.build(zip, file(part), tag) { job = Job.Building(part, it) }
                synchronized(this@JapaneseDictionary) { open.remove(part) }
                job = null
                version++
            } catch (e: kotlinx.coroutines.CancellationException) {
                job = null
                throw e
            } catch (e: Exception) {
                job = Job.Failed(part, e.message ?: "Couldn't get the dictionary")
            } finally {
                zip.delete()
            }
        }
    }

    fun cancel() {
        running?.cancel()
        job = null
    }

    @Synchronized
    fun delete(part: Part) {
        open.remove(part)?.close()
        dir?.let { File(it, part.file).delete() }
        version++
    }

    /** Size on the phone, in bytes. */
    fun size(part: Part): Long = dir?.let { File(it, part.file).length() } ?: 0
}

/** Lines already cut into words, so a subtitle isn't looked up again each time it shows. */
object LineCache {
    private val cache = object : LinkedHashMap<String, List<LinePiece>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<LinePiece>>?) = size > 200
    }

    @Synchronized
    fun get(line: String): List<LinePiece>? = cache[line]

    @Synchronized
    fun put(line: String, pieces: List<LinePiece>) {
        cache[line] = pieces
    }

    @Synchronized
    fun clear() = cache.clear()
}
