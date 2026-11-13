package com.aniliphy.anilist

/**
 * Spoiler-free mode: names of episodes and chapters past where you are stay hidden, and so does the synopsis (and
 * banner) of a title whose prequel you haven't finished.
 */
object SpoilerFree {
    private val SEEN = setOf(ListStatus.COMPLETED, ListStatus.REPEATING)

    /** On for a title: its own choice if it has one, else the app-wide switch. */
    fun on(global: Boolean, forTitle: Boolean?): Boolean = forTitle ?: global

    /** Whether the name of episode or chapter [number] gives something away, with your [progress] on it. */
    fun hidesName(number: Double?, progress: Int): Boolean = number == null || number > progress

    /** "Episode 7" for a hidden one, the source's own name otherwise. */
    fun name(real: String, label: String, number: Double?, progress: Int, on: Boolean): String =
        if (on && hidesName(number, progress)) label else real

    /** The synopsis would spoil a prequel: one of them isn't completed (or isn't on your list at all). */
    fun hidesSynopsis(media: Media, statusOf: (Int) -> String?): Boolean =
        media.relations.edges.any { e ->
            e.relationType == "PREQUEL" && e.node?.type == media.type && e.node?.let { statusOf(it.id) } !in SEEN
        }
}
