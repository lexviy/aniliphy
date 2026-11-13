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

    /** Favourites that aren't on your list, from the profile's favourites. */
    private var otherFavourites by mutableStateOf<Set<Int>>(emptySet())

    /** Stars changed here since: shown straight away, without fetching the lists again. */
    private var changed by mutableStateOf<Map<Int, Boolean>>(emptyMap())

    /** Whether the title is one of your favourites, for the star on its cover. */
    fun isFavourite(mediaId: Int): Boolean =
        changed[mediaId] ?: (entries[mediaId]?.media?.isFavourite == true || mediaId in otherFavourites)

    fun setFavourite(mediaId: Int, favourite: Boolean) {
        changed = changed + (mediaId to favourite)
    }

    /** The anime and manga favourites as AniList has them now. */
    fun knownFavourites(mediaIds: Set<Int>) {
        otherFavourites = mediaIds
        changed = changed.filter { (id, favourite) -> favourite != (id in mediaIds) }
    }

    /** Loads the lists of [userId]; null (logged out) empties the index. Failures keep what was there. */
    suspend fun refresh(userId: Int?) {
        if (userId == null) {
            entries = emptyMap()
            otherFavourites = emptySet()
            changed = emptyMap()
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
