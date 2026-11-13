package com.aniliphy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.anilist.SpoilerFree

/** Spoiler-free mode: the app-wide switch and titles set by hand. Read by the background check too. */
object SpoilerPrefs {
    private const val FILE = "spoilers"
    private const val GLOBAL = "global"
    private const val TITLES = "titles"

    var global by mutableStateOf(false)
        private set

    /** Titles set by hand: id to (on, title). */
    val titles = mutableStateMapOf<Int, Pair<Boolean, String>>()
    @Volatile private var loaded = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            global = p.getBoolean(GLOBAL, false)
            p.getStringSet(TITLES, emptySet()).orEmpty().forEach { line ->
                val parts = line.split(':', limit = 3)
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@forEach
                titles[id] = (parts.getOrNull(1) == "1") to parts.getOrNull(2).orEmpty()
            }
            loaded = true
        }
    }

    /** Whether spoiler-free applies to [mediaId]. */
    fun on(context: Context, mediaId: Int?): Boolean {
        load(context)
        return SpoilerFree.on(global, mediaId?.let { titles[it]?.first })
    }

    fun setGlobal(context: Context, value: Boolean) {
        load(context)
        global = value
        prefs(context).edit().putBoolean(GLOBAL, value).apply()
    }

    /** Sets [mediaId] by hand, or back to following the app-wide switch with null. */
    fun setTitle(context: Context, mediaId: Int, title: String, value: Boolean?) {
        load(context)
        if (value == null) titles.remove(mediaId) else titles[mediaId] = value to title
        prefs(context).edit().putStringSet(TITLES, titles.map { (id, v) -> "$id:${if (v.first) 1 else 0}:${v.second}" }.toSet()).apply()
    }
}
