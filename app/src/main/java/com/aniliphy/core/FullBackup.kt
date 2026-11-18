package com.aniliphy.core

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class InvalidBackupException : IOException("Not an aniliphy backup")

/** What a backup file holds: settings by store (each key as {type, value}) and data files by path under files/. */
class BackupData(
    val manifest: JsonObject,
    val prefs: Map<String, JsonObject>,
    val files: Map<String, ByteArray>,
) {
    val icon: String? get() = manifest["icon"]?.jsonPrimitive?.contentOrNull
}

/**
 * Everything worth keeping in one zip, to move to another phone or keep safe: settings, where you stopped, linked
 * sources, the profile's goals and moments, and your own fixes. Never tokens, cookies, trusted extensions, caches,
 * or AniList changes still waiting to be sent. Restoring can merge (per title the newer one wins) or replace.
 */
object FullBackup {
    enum class Part(val label: String, val detail: String) {
        Settings("Settings", "Looks, player, readers, spoilers, lists, extensions' own settings"),
        Anime("Anime: where you stopped", "Episode and minute, Sub/Dub, source"),
        Manga("Manga: where you stopped", "Chapter and page, source"),
        Novels("Light novels", "Chapter in each book, linked AniList entries"),
        Profile("Profile", "Goals, achievement showcase, moments, activity"),
        Fixes("Your fixes", "Canon/anime-only marks, filler matches, skip times"),
    }

    data class PartSummary(val part: Part, val count: String)

    private const val APP_ID = "aniliphy"
    private const val FORMAT = 2
    private const val MAX_BYTES = 40L * 1024 * 1024

    /** Data files (under the app's files folder) and the part each belongs to. */
    internal val FILES: Map<String, Part> = linkedMapOf(
        "anime_history.json" to Part.Anime,
        "anime_new_episodes.json" to Part.Anime,
        "manga_history.json" to Part.Manga,
        "manga_found_sources.json" to Part.Manga,
        "manga_new_chapters.json" to Part.Manga,
        "novel_progress.json" to Part.Novels,
        "novel_new_volumes.json" to Part.Novels,
        "novels/custom_novels.json" to Part.Novels,
        "novels/novel_links.json" to Part.Novels,
        "profile_local.json" to Part.Profile,
        "activity_log.json" to Part.Profile,
        "skip_times.json" to Part.Fixes,
        "manga_series_reading.json" to Part.Settings,
        "anime_title_subtitles.json" to Part.Settings,
        "search_filters.json" to Part.Settings,
        "fillers/links.json" to Part.Fixes,
        "notified_releases.json" to Part.Settings,
    )

    /** Settings stores that stay on the phone: tokens and the device id (login, sync), trust, caches, this file's own record. */
    private val PRIVATE_STORES = setOf("login", "sync", "extensions", "updates", "backup", "jimaku", "privacy")

    /** Keys that only make sense on this phone (a folder picked with the system picker). */
    private val DEVICE_KEYS = mapOf("novels" to setOf("folder_uri"))

    private val STORE_NAME = Regex("""[A-Za-z0-9_.\-]+""")

    internal fun partOf(store: String): Part? = when {
        store in PRIVATE_STORES || store.startsWith("WebView") || store.startsWith("AwOrigin") || !STORE_NAME.matches(store) -> null
        store == "canon" -> Part.Fixes
        store == "milestones" -> Part.Profile
        else -> Part.Settings
    }

    private val json = Json { prettyPrint = false }

    // ---- making one

    fun collect(context: Context, icon: String): BackupData {
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        val prefs = prefsDir.listFiles().orEmpty()
            .filter { it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter { partOf(it) != null }
            .associateWith { store ->
                val skip = DEVICE_KEYS[store].orEmpty()
                JsonObject(
                    context.getSharedPreferences(store, Context.MODE_PRIVATE).all
                        .filterKeys { it !in skip }
                        .mapNotNull { (key, value) -> encode(value)?.let { key to it } }
                        .toMap(),
                )
            }
            .filterValues { it.isNotEmpty() }
        val files = FILES.keys.mapNotNull { path ->
            File(context.filesDir, path).takeIf { it.isFile }?.let { path to it.readBytes() }
        }.toMap()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val manifest = buildJsonObject {
            put("app", APP_ID)
            put("format", FORMAT)
            put("created", System.currentTimeMillis())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("version", version.orEmpty())
            put("icon", icon)
        }
        return BackupData(manifest, prefs, files)
    }

    fun write(output: OutputStream, data: BackupData) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("manifest.json", data.manifest.toString().toByteArray())
            data.prefs.forEach { (store, values) -> entry("prefs/$store.json", values.toString().toByteArray()) }
            data.files.forEach { (path, bytes) -> entry("files/$path", bytes) }
        }
    }

    // ---- reading one

    /** Reads a backup zip, or the settings file older versions exported (JSON). */
    fun read(input: InputStream): BackupData {
        val buffered = input.buffered()
        buffered.mark(4)
        val first = buffered.read()
        buffered.reset()
        return if (first == '{'.code) readSettingsFile(buffered) else readZip(buffered)
    }

    private fun readZip(input: InputStream): BackupData {
        var manifest: JsonObject? = null
        val prefs = mutableMapOf<String, JsonObject>()
        val files = mutableMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val bytes = readLimited(zip, MAX_BYTES - total)
                    total += bytes.size
                    val name = entry.name
                    when {
                        name == "manifest.json" -> manifest = json.parseToJsonElement(String(bytes)).jsonObject
                        name.startsWith("prefs/") && name.endsWith(".json") -> {
                            val store = name.removePrefix("prefs/").removeSuffix(".json")
                            // Only names a store can have, never one kept off backups.
                            if (partOf(store) != null) prefs[store] = json.parseToJsonElement(String(bytes)).jsonObject
                        }
                        // Only the known files, so nothing in the zip can be written anywhere else.
                        name.startsWith("files/") && name.removePrefix("files/") in FILES -> files[name.removePrefix("files/")] = bytes
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException()
        }
        val head = manifest ?: throw InvalidBackupException()
        if (head["app"]?.jsonPrimitive?.contentOrNull != APP_ID) throw InvalidBackupException()
        val format = head["format"]?.jsonPrimitive?.intOrNull ?: 0
        if (format > FORMAT) throw IOException("This backup comes from a newer version of aniliphy")
        return BackupData(head, prefs, files)
    }

    /** The settings-only file (format 1) of older versions: same {type, value} encoding. */
    private fun readSettingsFile(input: InputStream): BackupData {
        val root = try {
            json.parseToJsonElement(String(readLimited(input, MAX_BYTES))).jsonObject
        } catch (_: IllegalArgumentException) {
            throw InvalidBackupException()
        }
        if (root["app"]?.jsonPrimitive?.contentOrNull != APP_ID || root["version"]?.jsonPrimitive?.intOrNull != 1) throw InvalidBackupException()
        val settings = root["settings"] as? JsonObject ?: throw InvalidBackupException()
        val prefs = settings.filterKeys { partOf(it) != null }.mapValues { (it.value as? JsonObject) ?: throw InvalidBackupException() }
        val manifest = buildJsonObject {
            put("app", APP_ID)
            put("format", 1)
            root["icon"]?.jsonPrimitive?.contentOrNull?.let { put("icon", it) }
        }
        return BackupData(manifest, prefs, emptyMap())
    }

    private fun readLimited(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw IOException("The backup is too big")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    // ---- what's inside

    fun summary(data: BackupData): List<PartSummary> {
        fun keys(path: String) = data.files[path]?.let { runCatching { json.parseToJsonElement(String(it)) as? JsonObject }.getOrNull()?.size } ?: 0
        fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
        val parts = (data.prefs.keys.mapNotNull { partOf(it) } + data.files.keys.mapNotNull { FILES[it] }).toSet()
        return Part.entries.filter { it in parts }.map { part ->
            val count = when (part) {
                Part.Settings -> plural(data.prefs.keys.count { partOf(it) == Part.Settings }, "group")
                Part.Anime -> plural(keys("anime_history.json"), "title")
                Part.Manga -> plural(keys("manga_history.json"), "title")
                Part.Novels -> "${keys("novel_progress.json")} series"
                Part.Profile -> {
                    val goals = data.files["profile_local.json"]?.let { bytes ->
                        runCatching { (json.parseToJsonElement(String(bytes)).jsonObject["goals"] as? JsonArray)?.size }.getOrNull()
                    } ?: 0
                    if (goals > 0) plural(goals, "goal") else plural(keys("activity_log.json"), "day") + " of activity"
                }
                Part.Fixes -> (keys("skip_times.json") + keys("fillers/links.json") + (data.prefs["canon"]?.size ?: 0)).toString()
            }
            PartSummary(part, count)
        }
    }

    // ---- restoring

    fun restore(context: Context, data: BackupData, parts: Set<Part>, merge: Boolean) {
        data.prefs.forEach { (store, values) ->
            val part = partOf(store) ?: return@forEach
            if (part !in parts) return@forEach
            val prefs = context.getSharedPreferences(store, Context.MODE_PRIVATE)
            val kept = prefs.all.filterKeys { it in DEVICE_KEYS[store].orEmpty() }
            val editor = prefs.edit()
            if (!merge) editor.clear()
            values.forEach { (key, value) -> if (key !in DEVICE_KEYS[store].orEmpty()) put(editor, key, value) }
            kept.forEach { (key, value) -> encode(value)?.let { put(editor, key, it) } }
            editor.commit()
        }
        data.files.forEach { (path, bytes) ->
            val part = FILES[path] ?: return@forEach
            if (part !in parts) return@forEach
            val file = File(context.filesDir, path)
            val incoming = String(bytes)
            val text = if (merge && file.isFile) {
                val local = file.readTextOrNull()
                val merged = runCatching { JsonMerge.merge(json.parseToJsonElement(local.orEmpty()), json.parseToJsonElement(incoming)).toString() }.getOrNull()
                merged ?: local ?: incoming
            } else {
                incoming
            }
            file.writeTextAtomically(text)
        }
    }

    // ---- settings values

    internal fun encode(value: Any?): JsonObject? {
        val (type, element) = when (value) {
            is Boolean -> "boolean" to JsonPrimitive(value)
            is Int -> "int" to JsonPrimitive(value)
            is Long -> "long" to JsonPrimitive(value)
            is Float -> "float" to JsonPrimitive(value)
            is String -> "string" to JsonPrimitive(value)
            is Set<*> -> "set" to JsonArray(value.filterIsInstance<String>().sorted().map { JsonPrimitive(it) })
            else -> return null
        }
        return JsonObject(mapOf("type" to JsonPrimitive(type), "value" to element))
    }

    private fun put(editor: android.content.SharedPreferences.Editor, key: String, encoded: JsonElement) {
        val box = encoded as? JsonObject ?: return
        val value = box["value"] ?: return
        when (box["type"]?.jsonPrimitive?.contentOrNull) {
            "boolean" -> value.jsonPrimitive.booleanOrNull?.let { editor.putBoolean(key, it) }
            "int" -> value.jsonPrimitive.intOrNull?.let { editor.putInt(key, it) }
            "long" -> value.jsonPrimitive.longOrNull?.let { editor.putLong(key, it) }
            "float" -> value.jsonPrimitive.floatOrNull?.let { editor.putFloat(key, it) }
            "string" -> value.jsonPrimitive.contentOrNull?.let { editor.putString(key, it) }
            "set" -> runCatching { value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet() }.getOrNull()?.let { editor.putStringSet(key, it) }
        }
    }
}

/**
 * Joins what is on the phone with what comes from a backup without losing either: per entry, the one with the newer
 * date ("updated", "at", "looked") wins; lists are joined; numbers keep the larger (counts, last notified chapter);
 * anything else keeps what the phone has, and entries only one side has are kept.
 */
object JsonMerge {
    private val STAMPS = listOf("updated", "at", "looked")

    fun merge(local: JsonElement, incoming: JsonElement): JsonElement = when {
        local is JsonObject && incoming is JsonObject -> mergeObjects(local, incoming)
        local is JsonArray && incoming is JsonArray -> JsonArray((local + incoming).distinct())
        local is JsonPrimitive && incoming is JsonPrimitive && !local.isString && !incoming.isString -> {
            val a = local.doubleOrNull
            val b = incoming.doubleOrNull
            if (a != null && b != null && b > a) incoming else local
        }
        else -> local
    }

    private fun stamp(entry: JsonObject): Double? =
        STAMPS.firstNotNullOfOrNull { (entry[it] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull }

    private fun mergeObjects(local: JsonObject, incoming: JsonObject): JsonObject {
        val a = stamp(local)
        val b = stamp(incoming)
        if (a != null && b != null) return if (b > a) incoming else local
        if (a == null && b != null && local.isNotEmpty() && STAMPS.none { it in local }) {
            // Only the backup says when: it was touched more recently than an entry that never kept a date.
            return mergeKeys(incoming, local)
        }
        return mergeKeys(local, incoming)
    }

    private fun mergeKeys(first: JsonObject, second: JsonObject): JsonObject {
        val keys = first.keys + second.keys
        return JsonObject(
            keys.associateWith { key ->
                val x = first[key]
                val y = second[key]
                when {
                    x == null -> y!!
                    y == null -> x
                    else -> merge(x, y)
                }
            },
        )
    }
}
