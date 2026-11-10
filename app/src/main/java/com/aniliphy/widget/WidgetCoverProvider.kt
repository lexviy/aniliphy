package com.aniliphy.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands the launcher the widget's covers by address. Sending them as bitmaps made some launchers show a cover in
 * the wrong column. It only serves the reduced covers in [WidgetData.IMAGES], read-only: public anime and manga
 * art, and covers of the light novels the widget shows.
 */
class WidgetCoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Covers are read-only")
        val name = uri.lastPathSegment?.takeIf { NAME.matches(it) } ?: throw FileNotFoundException(uri.toString())
        val context = context ?: throw FileNotFoundException(uri.toString())
        val file = File(WidgetData.images(context), name).takeIf { it.isFile } ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private val NAME = Regex("[0-9a-f]{40}\\.png")

        fun uri(context: Context, name: String): Uri = "content://${context.packageName}.widgetcovers/$name".toUri()
    }
}
