package com.aniliphy.novels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File

@Serializable
private data class StoredChapter(val title: String, val start: Int, val end: Int)

class PdfBook(file: File, val chapters: List<PdfChapter>) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try {
        PdfRenderer(descriptor)
    } catch (e: Exception) {
        descriptor.close()
        throw BookException("Could not read the PDF: ${e.message}", e)
    }
    private val lock = Mutex()
    val pageCount: Int = renderer.pageCount

    val shapes: List<Float> = (0 until pageCount).map { number ->
        renderer.openPage(number).use { it.height.toFloat() / it.width.coerceAtLeast(1) }
    }

    fun chapterOf(page: Int): Int = chapters.indexOfLast { page >= it.start }.coerceAtLeast(0)

    suspend fun render(number: Int, width: Int): Bitmap = lock.withLock {
        renderer.openPage(number).use { page ->
            val height = (width * page.height / page.width.coerceAtLeast(1)).coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    override fun close() {
        renderer.close()
        descriptor.close()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun open(context: Context, file: File, cache: File): PdfBook {
            val stored = runCatching {
                cache.readTextOrNull()?.let { json.decodeFromString<List<StoredChapter>>(it) }
            }.getOrNull()
            val chapters = stored?.map { PdfChapter(it.title, it.start, it.end) } ?: run {
                val found = findChapters(context, file)
                cache.writeTextAtomically(json.encodeToString(found.map { StoredChapter(it.title, it.start, it.end) }))
                found
            }
            return PdfBook(file, chapters)
        }

        private fun findChapters(context: Context, file: File): List<PdfChapter> {
            PDFBoxResourceLoader.init(context.applicationContext)
            val document = try {
                PDDocument.load(file)
            } catch (e: Exception) {
                throw BookException("Could not read the PDF: ${e.message}", e)
            }
            document.use {
                val count = it.numberOfPages
                val outline = runCatching { outlineOf(it) }.getOrDefault(emptyList())
                val starts = ChapterRules.outlineStarts(outline, count) ?: runCatching { headingsOf(it, count) }.getOrNull()
                return ChapterRules.pdfChapters(starts, count)
            }
        }

        private fun outlineOf(document: PDDocument): List<Triple<Int, String, Int>> {
            val found = ArrayList<Triple<Int, String, Int>>()
            val pages = document.pages
            fun walk(node: PDOutlineNode, level: Int) {
                node.children().forEach { item ->
                    val page = runCatching { item.findDestinationPage(document) }.getOrNull()
                    if (page != null) found.add(Triple(level, item.title.orEmpty(), pages.indexOf(page)))
                    walk(item, level + 1)
                }
            }
            document.documentCatalog.documentOutline?.let { walk(it, 1) }
            return found
        }

        private fun headingsOf(document: PDDocument, count: Int): List<Pair<String, Int>>? {
            val stripper = PDFTextStripper()
            val pages = (1..count).map { number ->
                stripper.startPage = number
                stripper.endPage = number
                runCatching { stripper.getText(document).lines() }.getOrDefault(emptyList())
            }
            return ChapterRules.headingStarts(pages)
        }
    }
}
