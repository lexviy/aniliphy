package com.aniliphy.anime

/** The player's sleep timer: when the time is up the episode pauses, after the sound fades out. */
enum class SleepChoice(val label: String, val minutes: Int?) {
    Off("Off", null),
    EndOfEpisode("End of ep", null),
    M15("15 min", 15),
    M30("30 min", 30),
    M60("60 min", 60),
}

object SleepTimer {
    const val FADE_MS = 10_000L

    /** The sound's volume with [leftMs] to go: full until the last [FADE_MS], then down to nothing. */
    fun volume(leftMs: Long): Float = when {
        leftMs >= FADE_MS -> 1f
        leftMs <= 0 -> 0f
        else -> leftMs.toFloat() / FADE_MS
    }

    /** "28:40" for what's left. */
    fun label(leftMs: Long): String {
        val seconds = ((leftMs + 999) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
