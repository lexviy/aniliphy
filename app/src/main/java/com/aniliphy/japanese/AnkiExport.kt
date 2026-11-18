package com.aniliphy.japanese

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Sends saved words to AnkiDroid through its own content provider (the one its API library wraps): a note type
 * "aniliphy word" with [WordExport.FIELDS], in the chosen deck, with the frame as a picture.
 */
object AnkiExport {
    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val NOTES = Uri.parse("content://$AUTHORITY/notes")
    private val MODELS = Uri.parse("content://$AUTHORITY/models")
    private val DECKS = Uri.parse("content://$AUTHORITY/decks")
    private val MEDIA = Uri.parse("content://$AUTHORITY/media")
    private const val MODEL_NAME = "aniliphy word"
    private const val SEPARATOR = "\u001f"

    private const val FRONT = "<div class=word>{{Word}}</div>"
    private const val BACK = "{{FrontSide}}<hr id=answer><div class=reading>{{Reading}}</div><div>{{Meaning}}</div>" +
        "<div class=sentence>{{Sentence}}</div><div>{{Picture}}</div><div class=source>{{Source}}</div>"
    private const val CSS = ".card{font-family:sans-serif;font-size:20px;text-align:center;}" +
        ".word{font-size:42px}.reading{font-size:22px;color:#c58b00}.sentence{margin-top:12px;font-size:22px}" +
        ".source{margin-top:8px;font-size:13px;color:#888}img{max-width:100%;margin-top:10px}"

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun deckId(context: Context, name: String): Long {
        val resolver = context.contentResolver
        resolver.query(DECKS, arrayOf("deck_id", "deck_name"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getLong(0)
        }
        val made = resolver.insert(DECKS, ContentValues().apply { put("deck_name", name) }) ?: error("AnkiDroid didn't make the deck")
        return made.lastPathSegment!!.toLong()
    }

    private fun modelId(context: Context, settings: JapaneseSettings, deck: Long): Long {
        val resolver = context.contentResolver
        settings.ankiModel.toLongOrNull()?.let { id ->
            val there = runCatching {
                resolver.query(Uri.withAppendedPath(MODELS, id.toString()), arrayOf("_id", "name"), null, null, null)?.use { it.moveToFirst() && it.getString(1) == MODEL_NAME }
            }.getOrNull() == true
            if (there) return id
        }
        resolver.query(MODELS, arrayOf("_id", "name"), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == MODEL_NAME) return c.getLong(0).also { settings.ankiModel = it.toString() }
        }
        val values = ContentValues().apply {
            put("name", MODEL_NAME)
            put("field_names", WordExport.FIELDS.joinToString(SEPARATOR))
            put("num_cards", 1)
            put("css", CSS)
            put("deck_id", deck)
            put("sort_field_index", 0)
        }
        val made = resolver.insert(MODELS, values) ?: error("AnkiDroid didn't make the note type")
        val template = Uri.withAppendedPath(Uri.withAppendedPath(made, "templates"), "0")
        resolver.update(template, ContentValues().apply {
            put("name", "Word")
            put("question_format", FRONT)
            put("answer_format", BACK)
        }, null, null)
        return made.lastPathSegment!!.toLong().also { settings.ankiModel = it.toString() }
    }

    private fun picture(context: Context, frame: File): String? = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", frame)
        context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val added = context.contentResolver.insert(MEDIA, ContentValues().apply {
            put("file_uri", uri.toString())
            put("preferred_name", frame.nameWithoutExtension)
        })
        added?.path?.let { File(it).name }
    }.getOrNull()

    /** Sends [words] not sent before; gives the ids that went. Needs [allowed]. */
    fun send(context: Context, words: List<SavedWord>, frames: File): Set<String> {
        val settings = JapaneseSettings.get(context)
        val deck = deckId(context, settings.ankiDeck.ifBlank { "aniliphy" })
        val model = modelId(context, settings, deck)
        val sent = HashSet<String>()
        for (word in words.filter { !it.sentToAnki }) {
            val image = word.frame?.let { File(frames, it) }?.takeIf { it.isFile }?.let { picture(context, it) }
            val values = ContentValues().apply {
                put("mid", model)
                put("flds", WordExport.fields(word, image).joinToString(SEPARATOR))
                put("tags", "aniliphy")
            }
            if (context.contentResolver.insert(NOTES, values) != null) sent += word.id
        }
        return sent
    }
}
