package com.aniliphy.novels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

class NovelFiles(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile get() = File(context.filesDir, "novel_index.json")
    private val copies get() = File(context.cacheDir, "novels")
    private val covers get() = File(context.cacheDir, "novel_covers")

    fun cachedIndex(): List<VolumeFile> = runCatching {
        indexFile.readTextOrNull()?.let { json.decodeFromString<List<VolumeFile>>(it) }
    }.getOrNull().orEmpty()

    fun scan(folder: String): List<VolumeFile> {
        if (folder.isEmpty()) return emptyList()
        val tree = Uri.parse(folder)
        val found = ArrayList<VolumeFile>()
        walk(tree, DocumentsContract.getTreeDocumentId(tree), 0, found)
        indexFile.writeTextAtomically(json.encodeToString(found.toList()))
        return found
    }

    private fun walk(tree: Uri, parent: String, depth: Int, into: MutableList<VolumeFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < MAX_DEPTH && !name.startsWith(".")) walk(tree, id, depth + 1, into)
                } else {
                    val file = VolumeFile(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                        name,
                        cursor.getLong(3),
                        cursor.getLong(4),
                    )
                    if (file.isBook) into.add(file)
                }
            }
        }
    }

    private fun hash(file: VolumeFile): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("${file.uri}|${file.size}|${file.modified}".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun localCopy(file: VolumeFile): File {
        copies.mkdirs()
        val target = File(copies, hash(file) + "." + file.name.substringAfterLast('.', "bin").lowercase())
        if (target.isFile && target.length() > 0) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val temporary = File(copies, target.name + ".part")
        val input = context.contentResolver.openInputStream(Uri.parse(file.uri))
            ?: throw BookException("Could not open ${file.name}")
        input.use { source -> temporary.outputStream().use { source.copyTo(it) } }
        if (!temporary.renameTo(target)) throw BookException("Could not store ${file.name}")
        copies.listFiles()?.filter { it.isFile && !it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT_COPIES)
            ?.forEach { it.delete() }
        return target
    }

    fun chaptersCache(file: VolumeFile) = File(copies.apply { mkdirs() }, hash(file) + ".json")

    fun cover(file: VolumeFile): File? {
        covers.mkdirs()
        val target = File(covers, hash(file) + ".jpg")
        if (target.isFile && target.length() > 0) return target
        return runCatching {
            val copy = localCopy(file)
            if (file.isEpub) {
                EpubBook(copy).use { book -> book.cover()?.let { target.writeBytes(it) } }
            } else {
                renderFirstPage(copy, target)
            }
            target.takeIf { it.isFile && it.length() > 0 }
        }.getOrNull()
    }

    private fun renderFirstPage(pdf: File, target: File) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val width = COVER_WIDTH
                    val height = width * page.height / page.width.coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bitmap.recycle()
                }
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 2
        const val KEPT_COPIES = 8
        const val COVER_WIDTH = 360
    }
}
