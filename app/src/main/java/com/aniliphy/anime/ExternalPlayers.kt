package com.aniliphy.anime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.aniliphy.source.SubtitleTrack

/** Video players episodes can be opened in, and what each one takes and gives back. */
enum class ExternalPlayer(
    val label: String,
    val packages: List<String>,
    val headers: Boolean,
    val subtitles: Boolean,
    val returnsPlace: Boolean,
) {
    Mpv("mpv", listOf("is.xyz.mpv"), headers = false, subtitles = true, returnsPlace = true),
    Vlc("VLC", listOf("org.videolan.vlc"), headers = false, subtitles = true, returnsPlace = true),
    Just("Just Player", listOf("com.brouken.player"), headers = true, subtitles = true, returnsPlace = true),
    Mx("MX Player", listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad"), headers = true, subtitles = true, returnsPlace = true),
    ;

    val detail: String
        get() = listOfNotNull(
            "Gets the site's headers".takeIf { headers },
            "the subtitles".takeIf { subtitles },
        ).joinToString(" and ").replaceFirstChar { it.uppercase() }.let { gets ->
            listOfNotNull(gets.ifEmpty { null }, "your place comes back".takeIf { returnsPlace }).joinToString(" · ")
        }

    fun installed(context: Context): String? = packages.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** The intent to play [url] here; [pkg] is the installed package. */
    fun intent(pkg: String, url: String, title: String, headers: Map<String, String>, subtitles: List<SubtitleTrack>, positionMs: Long): Intent {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mimeOf(url)).setPackage(pkg)
            .putExtra("title", title)
        val subs = subtitles.map { Uri.parse(it.url) }
        when (this) {
            Mpv -> {
                intent.putExtra("position", positionMs.toInt())
                if (subs.isNotEmpty()) intent.putExtra("subs", subs.toTypedArray()).putExtra("subs.enable", arrayOf(subs.first()))
            }
            Vlc -> {
                intent.component = ComponentName(pkg, "org.videolan.vlc.gui.video.VideoPlayerActivity")
                intent.putExtra("from_start", false).putExtra("position", positionMs)
                subs.firstOrNull()?.let { intent.putExtra("subtitles_location", it.toString()) }
            }
            Just, Mx -> {
                // The MX Player API, which Just Player also understands.
                intent.putExtra("position", positionMs.toInt()).putExtra("return_result", true)
                if (headers.isNotEmpty()) intent.putExtra("headers", headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                if (subs.isNotEmpty()) {
                    intent.putExtra("subs", subs.toTypedArray())
                    intent.putExtra("subs.name", subtitles.map { it.label }.toTypedArray())
                    intent.putExtra("subs.enable", arrayOf(subs.first()))
                }
            }
        }
        return intent
    }

    companion object {
        fun mimeOf(url: String): String {
            val path = url.substringBefore('?').lowercase()
            return when {
                path.endsWith(".m3u8") || "m3u8" in path -> "application/x-mpegURL"
                path.endsWith(".mpd") -> "application/dash+xml"
                else -> "video/*"
            }
        }

        /** Where the player stopped and how long the video is, from what it gave back (ms); null when it said nothing. */
        fun result(data: Intent?): Pair<Long, Long?>? {
            val extras = data?.extras ?: return null
            fun number(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
                when (val v = extras.get(key)) {
                    is Int -> v.toLong()
                    is Long -> v
                    else -> null
                }
            }
            val position = number("position", "extra_position") ?: return null
            return position to number("duration", "extra_duration")
        }
    }
}
