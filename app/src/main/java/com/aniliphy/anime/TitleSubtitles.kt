package com.aniliphy.anime

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.source.SubtitleTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Subtitle choices kept for one title: the delay, and a style of its own when it has one. */
@Serializable
data class TitleSubs(
    val delayMs: Long = 0,
    val size: SubtitleSize? = null,
    val color: SubtitleColor? = null,
    val background: SubtitleBackground? = null,
) {
    val ownStyle: Boolean get() = size != null || color != null || background != null
}

/**
 * Per-title subtitle choices (anime_title_subtitles.json) and the subtitle files loaded for an episode
 * (files/subs/<title>/<episode>/), which stay with the episode.
 */
class TitleSubtitles(private val filesDir: File) {
    private val file = File(filesDir, FILE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun all(): Map<Int, TitleSubs> =
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<Map<String, TitleSubs>>(it) }.getOrNull() }
            .orEmpty().mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v } }.toMap()

    fun get(mediaId: Int): TitleSubs = all()[mediaId] ?: TitleSubs()

    @Synchronized
    fun set(mediaId: Int, value: TitleSubs) {
        val map = all().toMutableMap()
        if (value == TitleSubs()) map.remove(mediaId) else map[mediaId] = value
        if (map.isEmpty()) file.delete() else runCatching { file.writeTextAtomically(json.encodeToString(map.mapKeys { it.key.toString() })) }
    }

    private fun folder(mediaId: Int, episodeId: String) = File(filesDir, "subs/$mediaId/${hash(episodeId)}")

    /** Keeps a subtitle file for this episode; a file with the same name is replaced. */
    fun add(mediaId: Int, episodeId: String, name: String, bytes: ByteArray): File {
        val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").take(120).ifBlank { "subtitles.srt" }
        val dir = folder(mediaId, episodeId).apply { mkdirs() }
        return File(dir, safe).apply { writeBytes(bytes) }
    }

    /** The files loaded for this episode, as tracks the player can add. */
    fun tracks(mediaId: Int, episodeId: String): List<SubtitleTrack> =
        folder(mediaId, episodeId).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.map { f ->
            SubtitleTrack(label = f.nameWithoutExtension.take(40), url = android.net.Uri.fromFile(f).toString(), language = languageOf(f.name))
        }

    fun remove(mediaId: Int, episodeId: String, label: String) {
        folder(mediaId, episodeId).listFiles().orEmpty().filter { it.nameWithoutExtension.take(40) == label }.forEach { it.delete() }
    }

    companion object {
        const val FILE = "anime_title_subtitles.json"

        private fun hash(text: String) =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        /** "Frieren 21.ja.srt" → ja; a name that says Japanese, English... */
        fun languageOf(name: String): String? {
            val lower = name.lowercase()
            val tag = Regex("""[._\-\s\[(](ja|jp|jpn|en|eng|pt|por|es|spa)[._\-\s\])]""").find(lower)?.groupValues?.get(1)
            return when {
                tag in setOf("ja", "jp", "jpn") || "japanese" in lower || lower.any { it in '぀'..'ヿ' } -> "ja"
                tag in setOf("en", "eng") || "english" in lower -> "en"
                tag in setOf("pt", "por") || "portug" in lower -> "pt"
                tag in setOf("es", "spa") || "spanish" in lower || "español" in lower -> "es"
                else -> null
            }
        }
    }
}
