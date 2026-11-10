package com.aniliphy.source

import java.io.IOException

data class NovelLink(val title: String, val url: String)

/** [page] is opened in the browser; [volumes] are links to the volumes on it. */
data class NovelWork(val page: String, val volumes: List<NovelLink>)

/** Finds where to get more volumes of a light novel. */
interface NovelSite : Site {
    /** Null when the site doesn't have it. */
    @Throws(IOException::class)
    fun find(title: String): NovelWork?
}
