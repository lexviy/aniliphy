package com.aniliphy.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class InvalidBackupException : IOException("Not an aniliphy settings file")

object SettingsBackup {
    private const val APP_ID = "aniliphy"
    private const val FORMAT_VERSION = 1
    private const val MAX_FILE_BYTES = 1024 * 1024

    private val STORES = listOf("appearance", "anime", "manga", "novels", "activity")

    private val DEVICE_ONLY_KEYS = mapOf("novels" to setOf("folder_uri"))

    fun export(context: Context, icon: String, output: OutputStream) {
        val settings = JSONObject()
        STORES.forEach { store ->
            val entries = JSONObject()
            prefs(context, store).all.forEach { (key, value) ->
                if (key !in deviceOnly(store)) encode(value)?.let { entries.put(key, it) }
            }
            settings.put(store, entries)
        }
        val root = JSONObject()
            .put("app", APP_ID)
            .put("version", FORMAT_VERSION)
            .put("icon", icon)
            .put("settings", settings)
        output.write(root.toString(2).toByteArray())
    }

    fun import(context: Context, input: InputStream): String {
        val root = try {
            JSONObject(String(input.readLimited()))
        } catch (_: JSONException) {
            throw InvalidBackupException()
        }
        if (root.optString("app") != APP_ID || root.optInt("version") != FORMAT_VERSION) {
            throw InvalidBackupException()
        }
        val settings = root.optJSONObject("settings") ?: throw InvalidBackupException()
        val decoded = STORES.filter(settings::has).associateWith { store ->
            val entries = settings.optJSONObject(store) ?: throw InvalidBackupException()
            entries.keys().asSequence().map { it to decode(entries.opt(it)) }.toMap()
        }

        decoded.forEach { (store, entries) ->
            val prefs = prefs(context, store)
            val kept = prefs.all.filterKeys { it in deviceOnly(store) }
            val editor = prefs.edit().clear()
            (entries + kept).forEach { (key, value) -> editor.putValue(key, value) }
            editor.commit()
        }
        return root.optString("icon")
    }

    private fun prefs(context: Context, store: String) =
        context.getSharedPreferences(store, Context.MODE_PRIVATE)

    private fun deviceOnly(store: String) = DEVICE_ONLY_KEYS[store].orEmpty()

    private fun encode(value: Any?): JSONObject? {
        val type = when (value) {
            is Boolean -> "boolean"
            is Int -> "int"
            is Float -> "float"
            is String -> "string"
            else -> return null
        }
        return JSONObject().put("type", type).put("value", value)
    }

    private fun decode(entry: Any?): Any {
        val json = entry as? JSONObject ?: throw InvalidBackupException()
        val value = json.opt("value")
        return when (json.optString("type")) {
            "boolean" -> value as? Boolean
            "int" -> (value as? Number)?.toInt()
            "float" -> (value as? Number)?.toFloat()
            "string" -> value as? String
            else -> null
        } ?: throw InvalidBackupException()
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
        }
    }

    private fun InputStream.readLimited(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            output.write(buffer, 0, count)
            if (output.size() > MAX_FILE_BYTES) throw InvalidBackupException()
        }
    }
}
