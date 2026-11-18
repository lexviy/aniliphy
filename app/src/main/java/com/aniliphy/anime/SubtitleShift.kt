package com.aniliphy.anime

import java.util.Locale

/**
 * Moves every line of a subtitle file by an offset, for the "Delay" setting. Positive shows them later.
 * Works on SubRip (.srt), WebVTT (.vtt) and Advanced SubStation (.ass/.ssa); times never go below zero.
 */
object SubtitleShift {
    enum class Format { Srt, Vtt, Ass }

    fun formatOf(name: String, text: String): Format {
        val lower = name.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".ass") || lower.endsWith(".ssa") || text.contains("[Events]") -> Format.Ass
            lower.endsWith(".vtt") || text.trimStart('﻿').startsWith("WEBVTT") -> Format.Vtt
            else -> Format.Srt
        }
    }

    // 00:01:02,345 (srt) / 00:01:02.345 or 01:02.345 (vtt)
    private val clock = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([.,])(\d{3})""")
    // 0:01:02.34 (ass, centiseconds)
    private val assClock = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")

    fun shift(text: String, format: Format, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        return when (format) {
            Format.Srt, Format.Vtt -> text.lines().joinToString("\n") { line ->
                if ("-->" !in line) line
                else clock.replace(line) { m ->
                    val (h, mi, s, sep, ms) = m.destructured
                    val total = (h.ifEmpty { "0" }.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + ms.toLong() + offsetMs
                    write(total.coerceAtLeast(0), sep, hours = h.isNotEmpty() || format == Format.Srt)
                }
            }
            Format.Ass -> text.lines().joinToString("\n") { line ->
                if (!line.startsWith("Dialogue:") && !line.startsWith("Comment:")) line
                else {
                    // Only the start and end fields (the 2nd and 3rd), not times that might be in the text.
                    val parts = line.split(",", limit = 4)
                    if (parts.size < 4) line
                    else listOf(parts[0], moveAss(parts[1], offsetMs), moveAss(parts[2], offsetMs), parts[3]).joinToString(",")
                }
            }
        }
    }

    private fun moveAss(value: String, offsetMs: Long): String {
        val m = assClock.matchEntire(value.trim()) ?: return value
        val (h, mi, s, cs) = m.destructured
        val total = ((h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + cs.toLong() * 10 + offsetMs).coerceAtLeast(0)
        val c = total / 10
        return "%d:%02d:%02d.%02d".format(Locale.ROOT, c / 360000, (c / 6000) % 60, (c / 100) % 60, c % 100)
    }

    private fun write(ms: Long, sep: String, hours: Boolean): String {
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val rest = ms % 1000
        return if (hours || h > 0) "%02d:%02d:%02d%s%03d".format(Locale.ROOT, h, m, s, sep, rest)
        else "%02d:%02d%s%03d".format(Locale.ROOT, m, s, sep, rest)
    }
}

/** Gives the player copies of [tracks] moved by [delayMs]; a track that can't be read stays as it is. */
object ShiftedSubtitles {
    fun prepare(
        cacheDir: java.io.File,
        tracks: List<com.aniliphy.source.SubtitleTrack>,
        delayMs: Long,
        read: (url: String) -> String,
    ): List<com.aniliphy.source.SubtitleTrack> {
        if (delayMs == 0L || tracks.isEmpty()) return tracks
        val dir = java.io.File(cacheDir, "subs-shifted").apply { mkdirs() }
        return tracks.map { track ->
            runCatching {
                val text = read(track.url)
                val format = SubtitleShift.formatOf(track.url, text)
                val ext = when (format) {
                    SubtitleShift.Format.Srt -> "srt"
                    SubtitleShift.Format.Vtt -> "vtt"
                    SubtitleShift.Format.Ass -> "ass"
                }
                val name = java.security.MessageDigest.getInstance("SHA-1").digest("${track.url}@$delayMs".toByteArray())
                    .joinToString("") { "%02x".format(it) }.take(20)
                val out = java.io.File(dir, "$name.$ext")
                if (!out.exists()) out.writeText(SubtitleShift.shift(text, format, delayMs))
                track.copy(url = android.net.Uri.fromFile(out).toString())
            }.getOrDefault(track)
        }
    }
}
