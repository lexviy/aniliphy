package com.aniliphy.japanese

/** A dictionary word found in a line. */
data class WordMatch(
    val start: Int,
    /** The text as written in the line, like 食べられない. */
    val surface: String,
    val entry: DictEntry,
    /** The dictionary form it matched, like 食べる. */
    val form: String,
    /** Conjugation steps, innermost first: potential, negative. */
    val reasons: List<String>,
) {
    val end: Int get() = start + surface.length
    /** For 勉強する (JMdict has 勉強 marked as taking する) the reading gets する too. */
    val reading: String?
        get() = if (form !in entry.forms && form.endsWith("する")) entry.readingFor(form.dropLast(2))?.plus("する") else entry.readingFor(form)
}

/** A piece of a line: a word (with its best match) or the text between words. */
data class LinePiece(val text: String, val start: Int, val match: WordMatch?)

/** Part of a word with the reading to print over it (null over kana). */
data class Ruby(val text: String, val reading: String?)

object Lookup {
    const val MAX_LENGTH = 12

    private data class Probe(val start: Int, val surface: String, val candidate: Deinflect.Candidate, val lookFor: String, val needsVs: Boolean)

    private fun probes(text: String, start: Int): List<Probe> {
        if (start !in text.indices || !Kana.isWordChar(text[start])) return emptyList()
        var end = start
        while (end < text.length && end - start < MAX_LENGTH && Kana.isWordChar(text[end])) end++
        val out = ArrayList<Probe>()
        for (stop in end downTo start + 1) {
            val surface = text.substring(start, stop)
            for (c in Deinflect.candidates(surface)) {
                out += Probe(start, surface, c, c.text, false)
                // 勉強する: JMdict has 勉強 marked as taking する.
                if (Deinflect.Kind.VS in c.kinds && c.text.endsWith("する") && c.text.length > 2) {
                    out += Probe(start, surface, c, c.text.dropLast(2), true)
                }
            }
        }
        return out
    }

    private fun matches(probes: List<Probe>, found: Map<String, List<DictEntry>>): List<WordMatch> {
        val out = ArrayList<WordMatch>()
        val seen = HashSet<Pair<Long, Int>>()
        for (p in probes) {
            for (entry in found[p.lookFor].orEmpty()) {
                val ok = if (p.needsVs) entry.partsOfSpeech.any { it == "vs" || it.startsWith("vs-") } else Deinflect.fits(p.candidate.kinds, entry.partsOfSpeech)
                if (!ok || !seen.add(entry.id to p.surface.length)) continue
                out += WordMatch(p.start, p.surface, entry, if (p.needsVs) p.lookFor + "する" else p.lookFor, p.candidate.reasons.reversed())
            }
        }
        // Longest first; then the word as written, common words, fewer steps, and words before names.
        return out.sortedWith(
            compareByDescending<WordMatch> { it.surface.length }
                .thenBy { it.entry.name }
                .thenBy { if (it.reasons.isEmpty()) 0 else 1 }
                .thenByDescending { it.entry.common }
                .thenBy { it.reasons.size }
                .thenBy { it.entry.id },
        )
    }

    /** Words starting at [start], best first. */
    fun at(text: String, start: Int, source: WordSource): List<WordMatch> {
        val probes = probes(text, start)
        if (probes.isEmpty()) return emptyList()
        return matches(probes, source.find(probes.mapTo(HashSet()) { it.lookFor }))
    }

    /** The line cut into words (longest match from the left) and what's between them, asked of [source] at once. */
    fun pieces(text: String, source: WordSource): List<LinePiece> {
        val all = text.indices.associateWith { probes(text, it) }
        val wanted = all.values.flatMapTo(HashSet()) { list -> list.map { it.lookFor } }
        val found = if (wanted.isEmpty()) emptyMap() else source.find(wanted)
        val out = ArrayList<LinePiece>()
        val gap = StringBuilder()
        var gapStart = 0
        var i = 0
        fun flush() {
            if (gap.isNotEmpty()) out += LinePiece(gap.toString(), gapStart, null)
            gap.setLength(0)
        }
        while (i < text.length) {
            val best = matches(all[i].orEmpty(), found).firstOrNull()
            if (best == null) {
                if (gap.isEmpty()) gapStart = i
                gap.append(text[i])
                i++
            } else {
                flush()
                out += LinePiece(best.surface, i, best)
                i += best.surface.length
            }
        }
        flush()
        return out
    }

    /**
     * [surface] cut so each run of kanji has its reading: 死んでいる (form 死ぬ, reading しぬ) → 死/し, んでいる.
     * Only the part the surface shares with the dictionary form gets readings.
     */
    fun ruby(surface: String, form: String, reading: String?): List<Ruby> {
        if (reading == null || !Kana.hasKanji(surface)) return listOf(Ruby(surface, null))
        val runs = runs(form)
        val pattern = runs.joinToString("", "^", "$") { (run, kanji) -> if (kanji) "(.+?)" else Regex.escape(Kana.toHiragana(run)) }
        val groups = Regex(pattern).find(Kana.toHiragana(reading))?.groupValues?.drop(1)
        val shared = surface.zip(form).takeWhile { (a, b) -> a == b }.size
        val out = ArrayList<Ruby>()
        var pos = 0
        var group = 0
        for ((run, kanji) in runs) {
            if (pos >= shared) break
            val end = pos + run.length
            if (end > shared) break
            out += Ruby(run, if (kanji) groups?.getOrNull(group) else null)
            if (kanji) group++
            pos = end
        }
        if (groups == null && out.isNotEmpty() && surface == form) return listOf(Ruby(surface, reading))
        if (pos < surface.length) out += Ruby(surface.substring(pos), null)
        return out.fold(ArrayList()) { acc, r ->
            // Kana next to kana join up.
            val last = acc.lastOrNull()
            if (last != null && last.reading == null && r.reading == null) acc[acc.lastIndex] = Ruby(last.text + r.text, null) else acc += r
            acc
        }
    }

    private fun runs(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        for (c in text) {
            val kanji = Kana.isKanji(c)
            val last = out.lastOrNull()
            if (last != null && last.second == kanji) out[out.lastIndex] = last.first + c to kanji else out += c.toString() to kanji
        }
        return out
    }

    /** "食べる ← potential · negative" style: what was undone, innermost first. */
    fun describe(match: WordMatch): String = match.reasons.joinToString(" · ")
}
