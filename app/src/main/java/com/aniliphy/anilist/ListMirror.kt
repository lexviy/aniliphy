package com.aniliphy.anilist

/**
 * Hears every list entry saved on (or removed from) AniList, so another site can get a copy (MyAnimeList).
 * The app sets [listener] when that's on; AniList's own code doesn't know who listens.
 */
object ListMirror {
    data class Saved(
        val entryId: Int,
        val mediaId: Int,
        val malId: Int?,
        val anime: Boolean,
        val title: String,
        val status: String,
        val progress: Int,
        val volumes: Int,
        /** Null when the save didn't touch the score (a progress save). */
        val score: Double?,
        val repeat: Int,
    )

    interface Listener {
        fun saved(entry: Saved)
        fun deleted(entryId: Int)
    }

    @Volatile var listener: Listener? = null

    fun saved(entry: Saved) {
        runCatching { listener?.saved(entry) }
    }

    fun deleted(entryId: Int) {
        runCatching { listener?.deleted(entryId) }
    }
}
