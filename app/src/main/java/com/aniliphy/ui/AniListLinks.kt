package com.aniliphy.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** An anilist.co link opened from another app, waiting for the app to show it. */
internal object AniListLinkRequest {
    var route by mutableStateOf<Route?>(null)
}

internal object AniListLinks {
    /** "anime/21/One-Piece" → the title; "user/Name" → that person. Anything else isn't ours. */
    fun parse(host: String?, segments: List<String>): Route? {
        if (host != "anilist.co" && host != "www.anilist.co") return null
        val kind = segments.getOrNull(0) ?: return null
        val arg = segments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return when (kind) {
            "anime", "manga" -> arg.toIntOrNull()?.let { Route.Media(it) }
            "user" -> Route.User(name = arg)
            // forum/thread/123 or forum/thread/123/comment/456
            "forum" -> if (arg == "thread") segments.getOrNull(2)?.toIntOrNull()?.let { id ->
                Route.Thread(id, segments.getOrNull(4)?.toIntOrNull()?.takeIf { segments.getOrNull(3) == "comment" })
            } else null
            else -> null
        }
    }
}
