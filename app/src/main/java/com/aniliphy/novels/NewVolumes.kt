package com.aniliphy.novels

import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Notices a new volume of a series you're reading appearing in the light novels folder, like the manga tab's "New
 * chapter". The volumes of each series are remembered the first time it's seen; any added later is new until you
 * open the series.
 */
object NewVolumes {
    @Serializable
    data class State(
        /** Series key → the volume files seen so far. */
        val seen: Map<String, List<String>> = emptyMap(),
        /** The series with a volume you haven't opened yet; also read by the widget. */
        val fresh: Set<String> = emptySet(),
    )

    /** Takes in the [library] (series key → volume files). [reading] are the series you've started. */
    fun update(state: State, library: Map<String, List<String>>, reading: Set<String>): State {
        val fresh = state.fresh.filter { it in library }.toMutableSet()
        library.forEach { (key, names) ->
            val known = state.seen[key] ?: return@forEach
            if (key in reading && names.any { it !in known }) fresh += key
        }
        return State(seen = state.seen + library.filterKeys { it !in state.seen }, fresh = fresh)
    }

    /** You opened the series: all its volumes count as seen. */
    fun opened(state: State, key: String, names: List<String>): State =
        State(seen = state.seen + (key to names), fresh = state.fresh - key)
}

class NewVolumesStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): NewVolumes.State = synchronized(LOCK) {
        runCatching { file.readTextOrNull()?.let { json.decodeFromString<NewVolumes.State>(it) } }.getOrNull() ?: NewVolumes.State()
    }

    fun change(update: (NewVolumes.State) -> NewVolumes.State): NewVolumes.State = synchronized(LOCK) {
        val old = read()
        val next = update(old)
        if (next != old) {
            file.parentFile?.mkdirs()
            file.writeTextAtomically(json.encodeToString(next))
        }
        next
    }

    private companion object {
        val LOCK = Any()
    }
}
