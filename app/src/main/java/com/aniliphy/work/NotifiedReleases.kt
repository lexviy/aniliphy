package com.aniliphy.work

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.json.Json
import java.io.File

class NotifiedReleases(private val file: File) {
    enum class Kind(val key: String) { Chapter("ch"), Episode("ep") }

    private val json = Json { ignoreUnknownKeys = true }

    private fun read(): MutableMap<String, Double> =
        runCatching { file.readTextOrNull()?.let { json.decodeFromString<Map<String, Double>>(it) } }
            .getOrNull().orEmpty().toMutableMap()

    @Synchronized
    fun firstTime(kind: Kind, found: Map<Int, Double>): Map<Int, Double> {
        if (found.isEmpty()) return emptyMap()
        val told = read()
        val fresh = found.filter { (id, value) -> told["${kind.key}:$id"]?.let { value > it } ?: true }
        if (fresh.isNotEmpty()) {
            fresh.forEach { (id, value) -> told["${kind.key}:$id"] = value }
            file.parentFile?.mkdirs()
            file.writeTextAtomically(json.encodeToString(told))
        }
        return fresh
    }
}
