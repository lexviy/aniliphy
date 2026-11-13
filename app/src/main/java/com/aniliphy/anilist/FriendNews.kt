package com.aniliphy.anilist

/** Which of the people you follow finished something that's on your list, since the last look. */
object FriendNews {
    /** At most this many notifications at once; more become one that sums them up. */
    const val MAX_SINGLE = 3

    /**
     * The completions newer than [lastSeen] whose title is on your list ([onList] ids), newest first. With no
     * [lastSeen] (the first check) nothing counts: what happened before you turned it on isn't news. Your own
     * activity ([me]) shows in AniList's feed too, and never counts.
     */
    fun finished(feed: List<Activity>, lastSeen: Int?, onList: Set<Int>, me: Int? = null): List<Activity> {
        if (lastSeen == null) return emptyList()
        return feed.filter { a ->
            a.id > lastSeen && a.status == "completed" && a.user != null && a.user.id != me && a.media?.id?.let { it in onList } == true
        }.sortedByDescending { it.id }
    }

    /** The highest activity id seen, so the next check starts after it. */
    fun newest(feed: List<Activity>, lastSeen: Int?): Int? = (feed.map { it.id } + listOfNotNull(lastSeen)).maxOrNull()
}
