package com.aniliphy.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

abstract class PrefStore(context: Context, fileName: String) {
    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    protected fun boolPref(key: String, default: Boolean) = pref(
        default,
        read = { prefs.getBoolean(key, default) },
        write = { prefs.edit().putBoolean(key, it).apply() },
    )

    protected fun floatPref(key: String, default: Float) = pref(
        default,
        read = { prefs.getFloat(key, default) },
        write = { prefs.edit().putFloat(key, it).apply() },
    )

    protected fun intPref(key: String, default: Int) = pref(
        default,
        read = { prefs.getInt(key, default) },
        write = { prefs.edit().putInt(key, it).apply() },
    )

    protected fun stringPref(key: String, default: String) = pref(
        default,
        read = { prefs.getString(key, default) ?: default },
        write = { prefs.edit().putString(key, it).apply() },
    )

    protected fun <E : Enum<E>> enumPref(key: String, default: E, entries: List<E>) = pref(
        default,
        read = { entries.firstOrNull { it.name == prefs.getString(key, null) } ?: default },
        write = { prefs.edit().putString(key, it.name).apply() },
    )

    private fun <T> pref(default: T, read: () -> T, write: (T) -> Unit) =
        object : ReadWriteProperty<Any?, T> {
            private var state by mutableStateOf(runCatching(read).getOrDefault(default))

            override fun getValue(thisRef: Any?, property: KProperty<*>) = state

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                state = value
                write(value)
            }
        }
}
