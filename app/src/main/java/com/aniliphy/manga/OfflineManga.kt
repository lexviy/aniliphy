package com.aniliphy.manga

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.source.SourceChapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@Serializable
data class OfflineChapter(val folder: String, val pages: Int, val name: String, val number: Double? = null, val url: String = "")

@Serializable
data class OfflineMeta(
    val source: String? = null,
    val title: String? = null,
    val chapters: List<SourceChapter> = emptyList(),
    val downloaded: Map<String, OfflineChapter> = emptyMap(),
)

class OfflineManga(
    private val root: File,
    private val fetch: (url: String, headers: Map<String, String>) -> Pair<ByteArray, String?>,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val lock = Any()

    private fun dir(mediaId: Int) = File(root, mediaId.toString())

    private fun metaFile(mediaId: Int) = File(dir(mediaId), "meta.json")

    fun meta(mediaId: Int): OfflineMeta = synchronized(lock) {
        runCatching { metaFile(mediaId).readTextOrNull()?.let { json.decodeFromString<OfflineMeta>(it) } }.getOrNull() ?: OfflineMeta()
    }

    private fun write(mediaId: Int, meta: OfflineMeta) {
        val file = metaFile(mediaId)
        if (meta.downloaded.isEmpty()) {
            file.delete()
            dir(mediaId).delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeTextAtomically(json.encodeToString(meta))
    }

    private fun change(mediaId: Int, update: (OfflineMeta) -> OfflineMeta) = synchronized(lock) {
        write(mediaId, update(meta(mediaId)))
    }

    fun downloadedIds(mediaId: Int): Set<String> = meta(mediaId).downloaded.keys

    fun titles(): Map<Int, OfflineMeta> =
        root.listFiles()?.mapNotNull { it.name.toIntOrNull() }?.associateWith { meta(it) }?.filterValues { it.downloaded.isNotEmpty() }.orEmpty()

    fun chapterBytes(mediaId: Int, chapterId: String): Long {
        val info = meta(mediaId).downloaded[chapterId] ?: return 0
        return File(dir(mediaId), info.folder).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    fun localPages(mediaId: Int, chapterId: String): List<File>? {
        val info = meta(mediaId).downloaded[chapterId] ?: return null
        val files = File(dir(mediaId), info.folder).listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        return files.takeIf { it.isNotEmpty() && it.size >= info.pages }
    }

    fun rememberChapters(mediaId: Int, source: String, chapters: List<SourceChapter>) = synchronized(lock) {
        val meta = meta(mediaId)
        if (meta.downloaded.isEmpty() || (meta.chapters == chapters && meta.source == source)) return
        write(mediaId, meta.copy(source = source, chapters = chapters))
    }

    fun cachedChapters(mediaId: Int, source: String): List<SourceChapter> {
        val meta = meta(mediaId)
        if (meta.source != source) return emptyList()
        val listed = meta.chapters.filter { it.id in meta.downloaded }
        if (listed.isNotEmpty()) return listed
        return meta.downloaded.map { (id, info) -> SourceChapter(id, info.name, info.url.ifEmpty { id }, info.number) }.sortedBy { it.number ?: 0.0 }
    }

    @Throws(IOException::class)
    fun download(
        mediaId: Int,
        source: String,
        title: String?,
        chapter: SourceChapter,
        pages: List<String>,
        headers: Map<String, String>,
        onPage: (done: Int) -> Unit = {},
    ) {
        if (pages.isEmpty()) throw IOException("No pages")
        val name = folderName(chapter)
        val folder = File(dir(mediaId), name)
        folder.mkdirs()
        try {
            pages.forEachIndexed { index, url ->
                var data: Pair<ByteArray, String?>? = null
                var failure: IOException? = null
                for (attempt in 0 until 3) {
                    try {
                        data = fetch(url, headers)
                        break
                    } catch (e: IOException) {
                        failure = e
                    }
                }
                val (bytes, type) = data ?: throw failure ?: IOException("Could not download a page")
                File(folder, "%04d%s".format(index + 1, extension(url, type))).writeBytes(bytes)
                onPage(index + 1)
            }
        } catch (e: IOException) {
            folder.deleteRecursively()
            throw e
        }
        change(mediaId) { meta ->
            val kept = if (meta.source == source) meta.downloaded else emptyMap<String, OfflineChapter>().also {
                meta.downloaded.values.forEach { old -> File(dir(mediaId), old.folder).deleteRecursively() }
            }
            meta.copy(
                source = source,
                title = title ?: meta.title,
                downloaded = kept + (chapter.id to OfflineChapter(name, pages.size, chapter.name, chapter.number, chapter.url)),
                chapters = if (meta.source == source) meta.chapters else emptyList(),
            )
        }
    }

    fun delete(mediaId: Int, chapterId: String) = change(mediaId) { meta ->
        val info = meta.downloaded[chapterId] ?: return@change meta
        File(dir(mediaId), info.folder).deleteRecursively()
        meta.copy(downloaded = meta.downloaded - chapterId)
    }

    companion object {
        fun folderName(chapter: SourceChapter): String {
            val name = chapter.name.replace(Regex("""[^\w.\- ]+"""), "").trim().ifEmpty { "chapter" }
            val digest = MessageDigest.getInstance("SHA-1").digest(chapter.id.toByteArray())
                .joinToString("") { "%02x".format(it) }.take(8)
            return "$name [$digest]"
        }

        fun extension(url: String, contentType: String?): String {
            val path = url.lowercase().substringBefore('?')
            listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif").firstOrNull { path.endsWith(it) }?.let { return it }
            val kind = contentType?.substringAfter('/')?.substringBefore(';')?.trim()
            return if (kind in setOf("png", "webp", "gif", "avif")) ".$kind" else ".jpg"
        }
    }
}
