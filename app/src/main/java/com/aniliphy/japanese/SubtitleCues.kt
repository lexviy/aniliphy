package com.aniliphy.japanese

/** One subtitle line and when it shows. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** Subtitle files read into lines, for showing Japanese ourselves (so each word can be tapped). */
object SubtitleCues {
    private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val ARROW = Regex("""^\s*(\S+)\s*-->\s*(\S+)""")
    private val HTML_TAG = Regex("""</?[a-zA-Z][^>]*>""")
    private val ASS_TAG = Regex("""\{[^}]*\}""")

    fun parse(name: String, text: String): List<Cue> {
        val clean = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lower = name.lowercase()
        val cues = if (lower.endsWith(".ass") || lower.endsWith(".ssa") || clean.contains("[Events]")) ass(clean) else srtOrVtt(clean)
        return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    fun millis(value: String): Long? {
        val m = TIME.find(value) ?: return null
        val (h, min, s, frac) = m.destructured
        val fraction = when (frac.length) {
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return ((h.ifEmpty { "0" }.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + fraction
    }

    private fun srtOrVtt(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val arrow = ARROW.find(lines[i])
            if (arrow == null) {
                i++
                continue
            }
            val start = millis(arrow.groupValues[1])
            val end = millis(arrow.groupValues[2])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (start != null && end != null) out += Cue(start, end, body.toString().replace(HTML_TAG, "").trim())
        }
        return out
    }

    private fun ass(text: String): List<Cue> {
        var format: List<String>? = null
        var inEvents = false
        val out = ArrayList<Cue>()
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inEvents = trimmed.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (trimmed.startsWith("Format:", ignoreCase = true)) {
                format = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) continue
            val fields = format ?: listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            val parts = trimmed.substringAfter(':').split(',', limit = fields.size)
            if (parts.size < fields.size) continue
            val start = millis(parts[fields.indexOf("start")]) ?: continue
            val end = millis(parts[fields.indexOf("end")]) ?: continue
            val raw = parts[fields.indexOf("text").takeIf { it >= 0 } ?: fields.lastIndex]
            // Drawings (signs made of vector shapes) aren't text.
            if (Regex("""\\p[1-9]""").containsMatchIn(raw)) continue
            val body = raw.replace(ASS_TAG, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ").trim()
            out += Cue(start, end, body)
        }
        return out
    }

    /** What shows at [positionMs] (several lines join), with the file moved by [delayMs]; null when nothing. */
    fun at(cues: List<Cue>, positionMs: Long, delayMs: Long = 0): Cue? {
        val t = positionMs - delayMs
        val now = cues.filter { t >= it.startMs && t < it.endMs }
        if (now.isEmpty()) return null
        return Cue(now.minOf { it.startMs } + delayMs, now.maxOf { it.endMs } + delayMs, now.joinToString("\n") { it.text }.trim())
    }

    /** Whether the file is Japanese: enough kana and kanji in its lines. */
    fun japanese(cues: List<Cue>): Boolean {
        val text = cues.take(200).joinToString("") { it.text }
        if (text.isEmpty()) return false
        return text.count { Kana.isKana(it) || Kana.isKanji(it) } * 4 >= text.count { !it.isWhitespace() }
    }
}
