package com.aniliphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.ListEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Your list, looked up by title: lets any cover in the app (recommendations, related, search, seasons, people's pages)
 * show whether the title is on your list and how far along you are. Built from the full anime and manga lists, which
 * are already cached for the list pages, so it costs no extra queries most of the time.
 */
internal class ListIndex(
    private val anime: suspend (userId: Int) -> Fetched<List<ListEntry>>,
    private val manga: suspend (userId: Int) -> Fetched<List<ListEntry>>,
) {
    var entries by mutableStateOf<Map<Int, ListEntry>>(emptyMap())
        private set

    /** Loads the lists of [userId]; null (logged out) empties the index. Failures keep what was there. */
    suspend fun refresh(userId: Int?) {
        if (userId == null) {
            entries = emptyMap()
            return
        }
        try {
            entries = coroutineScope {
                val a = async { anime(userId).value }
                val m = async { manga(userId).value }
                (a.await() + m.await()).associateBy { it.media.id }
            }
        } catch (_: AniListException) {
        }
    }
}
