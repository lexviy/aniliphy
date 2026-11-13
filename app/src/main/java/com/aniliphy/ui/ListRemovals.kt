package com.aniliphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.anilist.AniListException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A title taken off the list, still waiting out its undo time. */
internal data class Removal(val id: Int, val entryId: Int, val mediaId: Int, val title: String)

/**
 * Taking titles off the list with a moment to undo it. The entry is hidden straight away but AniList is only told once
 * the undo time is over, so undoing sends nothing at all and the entry keeps its dates, notes and activity untouched.
 * If the app closes before then, nothing was sent and the entry simply stays on the list.
 */
internal class ListRemovals(
    /** Tells AniList to delete a list entry by its id. */
    private val delete: suspend (entryId: Int) -> Unit,
    private val scope: CoroutineScope,
    private val describe: (AniListException) -> String,
    private val onRemoved: () -> Unit,
) {
    /** The removal that can still be undone, shown in the undo bar. */
    var pending by mutableStateOf<Removal?>(null)
        private set

    /** Why the last removal didn't go through, shown in the undo bar for a moment. */
    var failed by mutableStateOf<String?>(null)
        private set

    /** Titles (media ids) to leave out of lists while their removal is waiting or being sent. */
    var hidden by mutableStateOf(emptySet<Int>())
        private set

    private var count = 0
    private var timer: Job? = null

    fun remove(entryId: Int, mediaId: Int, title: String) {
        pending?.let { send(it) }
        val removal = Removal(++count, entryId, mediaId, title)
        failed = null
        hidden = hidden + mediaId
        pending = removal
        timer = scope.launch {
            delay(UNDO_MILLIS)
            send(removal)
        }
    }

    fun undo() {
        val removal = pending ?: return
        timer?.cancel()
        pending = null
        hidden = hidden - removal.mediaId
    }

    /** The title [mediaId] was saved again while its removal was waiting: keep it on the list. */
    fun keep(mediaId: Int) {
        if (pending?.mediaId == mediaId) undo()
    }

    fun dismissFailure() {
        failed = null
    }

    private fun send(removal: Removal) {
        if (pending?.id == removal.id) {
            timer?.cancel()
            pending = null
        }
        scope.launch {
            try {
                delete(removal.entryId)
                onRemoved()
                // Lists reload after onRemoved; keep hiding it until they have, so it doesn't flash back.
                delay(SETTLE_MILLIS)
            } catch (e: AniListException) {
                failed = "Couldn't remove ${removal.title}: ${describe(e)}"
            } finally {
                hidden = hidden - removal.mediaId
            }
        }
    }

    companion object {
        const val UNDO_MILLIS = 5_000L
        private const val SETTLE_MILLIS = 4_000L
    }
}
