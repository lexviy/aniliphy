package com.aniliphy.anime

import androidx.media3.common.MimeTypes
import com.aniliphy.manga.sources.SourceHttp
import okhttp3.Request

/**
 * The kind of video at [url], from its address. Sources mostly give HLS, which stays the default when the address
 * doesn't say; Mihon and Aniyomi extensions also give DASH and plain files.
 */
internal fun videoMimeOf(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        path.endsWith(".mp4") || path.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        else -> MimeTypes.APPLICATION_M3U8
    }
}

/** Whether the address alone says what [url] is, without falling back to HLS. */
private fun saysKind(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".mkv", ".webm").any { path.endsWith(it) }
}

/** The kind of a server's Content-Type, or null when it doesn't tell. */
internal fun videoMimeOfType(type: String?): String? {
    val clean = type?.substringBefore(';')?.trim()?.lowercase() ?: return null
    return when {
        "mpegurl" in clean -> MimeTypes.APPLICATION_M3U8
        clean == "application/dash+xml" -> MimeTypes.APPLICATION_MPD
        clean == "video/mp4" -> MimeTypes.VIDEO_MP4
        clean == "video/x-matroska" || clean == "video/matroska" -> MimeTypes.VIDEO_MATROSKA
        clean == "video/webm" -> MimeTypes.VIDEO_WEBM
        else -> null
    }
}

/**
 * Like [videoMimeOf], but when the address doesn't say, asks the server for the first byte and goes by the type it
 * answers with. Done before a download, where a wrong guess would save nothing.
 */
internal fun probeVideoMime(url: String, headers: Map<String, String>): String {
    if (saysKind(url)) return videoMimeOf(url)
    val asked = runCatching {
        val request = Request.Builder().url(url).header("User-Agent", SourceHttp.USER_AGENT)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=0-0")
            .build()
        SourceHttp.client.newCall(request).execute().use { videoMimeOfType(it.header("Content-Type")) }
    }.getOrNull()
    return asked ?: MimeTypes.APPLICATION_M3U8
}

/** The kind of a subtitle file, from its address. */
internal fun subtitleExtensionOf(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return listOf("srt", "ass", "ssa", "ttml", "xml", "vtt").firstOrNull { path.endsWith(".$it") } ?: "vtt"
}
