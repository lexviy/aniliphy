package com.aniliphy.japanese

/**
 * Undoes conjugations, the way Yomitan does: 食べられなかった → 食べる (potential, negative, past). Each step swaps an
 * ending and says what kind of word the result must be; a chain only goes on when the kinds fit, and the dictionary
 * has the last word on whether the result is real (its part of speech must be one of [Candidate.kinds]).
 */
object Deinflect {
    /** What a form can be: the base word kinds, plus in-between forms like the て form or ます. */
    enum class Kind { V1, V5, VK, VS, ADJ_I, TE, MASU }

    data class Candidate(
        val text: String,
        /** Empty for the text as found (any word fits); else the kinds the dictionary word must be. */
        val kinds: Set<Kind>,
        /** Outermost first: 食べられない gives negative, then potential. */
        val reasons: List<String>,
    )

    private class Rule(val from: String, val to: String, val kindsIn: Set<Kind>, val kindsOut: Set<Kind>, val reason: String)

    private val rules: List<Rule> = buildList {
        fun r(reason: String, kindsIn: Set<Kind>, kindsOut: Kind, vararg pairs: String) {
            pairs.forEach { pair ->
                val (from, to) = pair.split(':')
                add(Rule(from, to, kindsIn, setOf(kindsOut), reason))
            }
        }
        val none = emptySet<Kind>()
        val adj = setOf(Kind.ADJ_I)
        val v1 = setOf(Kind.V1)
        val v5 = setOf(Kind.V5)
        val te = setOf(Kind.TE)
        val masu = setOf(Kind.MASU)
        val godanPast = arrayOf("いた:く", "いだ:ぐ", "した:す", "った:う", "った:つ", "った:る", "んだ:ぬ", "んだ:ぶ", "んだ:む", "行った:行く", "いった:いく")

        // Past
        r("past", none, Kind.V1, "た:る")
        r("past", none, Kind.V5, *godanPast)
        r("past", none, Kind.VK, "きた:くる", "来た:来る")
        r("past", none, Kind.VS, "した:する")
        r("past", none, Kind.ADJ_I, "かった:い")
        // て form, alone or before いる, しまう, おく...
        r("te form", te, Kind.V1, "て:る")
        r("te form", te, Kind.V5, "いて:く", "いで:ぐ", "して:す", "って:う", "って:つ", "って:る", "んで:ぬ", "んで:ぶ", "んで:む", "行って:行く", "いって:いく")
        r("te form", te, Kind.VK, "きて:くる", "来て:来る")
        r("te form", te, Kind.VS, "して:する")
        r("te form", te, Kind.ADJ_I, "くて:い")
        r("progressive", v1, Kind.TE, "ている:て", "でいる:で", "てる:て", "でる:で")
        r("finished", v5, Kind.TE, "てしまう:て", "でしまう:で")
        r("finished", v5, Kind.V1, "ちゃう:る")
        r("finished", v5, Kind.V5, "いちゃう:く", "いじゃう:ぐ", "しちゃう:す", "っちゃう:う", "っちゃう:つ", "っちゃう:る", "んじゃう:ぬ", "んじゃう:ぶ", "んじゃう:む")
        r("finished", v5, Kind.VS, "しちゃう:する")
        r("in advance", v5, Kind.TE, "ておく:て", "でおく:で", "とく:て", "どく:で")
        r("please", none, Kind.TE, "てください:て", "でください:で")
        // Negative
        r("negative", adj, Kind.V1, "ない:る")
        r("negative", adj, Kind.V5, "かない:く", "がない:ぐ", "さない:す", "たない:つ", "なない:ぬ", "ばない:ぶ", "まない:む", "らない:る", "わない:う")
        r("negative", adj, Kind.VK, "こない:くる", "来ない:来る")
        r("negative", adj, Kind.VS, "しない:する")
        r("negative", adj, Kind.ADJ_I, "くない:い")
        r("negative", none, Kind.V1, "ず:る", "ん:る")
        r("negative", none, Kind.V5, "かず:く", "がず:ぐ", "さず:す", "たず:つ", "なず:ぬ", "ばず:ぶ", "まず:む", "らず:る", "わず:う", "かん:く", "らん:る", "わん:う")
        // Polite
        r("polite", none, Kind.MASU, "ました:ます", "ません:ます", "ませんでした:ます", "ましょう:ます", "まして:ます")
        r("polite", masu, Kind.V1, "ます:る")
        r("polite", masu, Kind.V5, "きます:く", "ぎます:ぐ", "します:す", "ちます:つ", "にます:ぬ", "びます:ぶ", "みます:む", "ります:る", "います:う")
        r("polite", masu, Kind.VK, "きます:くる", "来ます:来る")
        r("polite", masu, Kind.VS, "します:する")
        // Passive, potential, causative: the result conjugates like an ichidan verb.
        r("passive or potential", v1, Kind.V1, "られる:る")
        r("passive", v1, Kind.V5, "かれる:く", "がれる:ぐ", "される:す", "たれる:つ", "なれる:ぬ", "ばれる:ぶ", "まれる:む", "われる:う")
        r("potential", v1, Kind.V5, "ける:く", "げる:ぐ", "せる:す", "てる:つ", "ねる:ぬ", "べる:ぶ", "める:む", "れる:る", "える:う")
        r("passive or potential", v1, Kind.VK, "こられる:くる", "来られる:来る")
        r("passive", v1, Kind.VS, "される:する")
        r("potential", v1, Kind.VS, "できる:する")
        r("causative", v1, Kind.V1, "させる:る")
        r("causative", v1, Kind.V5, "かせる:く", "がせる:ぐ", "させる:す", "たせる:つ", "なせる:ぬ", "ばせる:ぶ", "ませる:む", "らせる:る", "わせる:う")
        r("causative", v1, Kind.VS, "させる:する")
        r("causative", v1, Kind.VK, "こさせる:くる", "来させる:来る")
        // Want to
        r("want", adj, Kind.V1, "たい:る")
        r("want", adj, Kind.V5, "きたい:く", "ぎたい:ぐ", "したい:す", "ちたい:つ", "にたい:ぬ", "びたい:ぶ", "みたい:む", "りたい:る", "いたい:う")
        r("want", adj, Kind.VK, "きたい:くる", "来たい:来る")
        r("want", adj, Kind.VS, "したい:する")
        // Let's, imperative
        r("volitional", none, Kind.V1, "よう:る")
        r("volitional", none, Kind.V5, "こう:く", "ごう:ぐ", "そう:す", "とう:つ", "のう:ぬ", "ぼう:ぶ", "もう:む", "ろう:る", "おう:う")
        r("volitional", none, Kind.VK, "こよう:くる", "来よう:来る")
        r("volitional", none, Kind.VS, "しよう:する")
        r("imperative", none, Kind.V1, "ろ:る", "よ:る")
        r("imperative", none, Kind.V5, "け:く", "げ:ぐ", "せ:す", "て:つ", "ね:ぬ", "べ:ぶ", "め:む", "れ:る", "え:う")
        r("imperative", none, Kind.VK, "こい:くる", "来い:来る")
        r("imperative", none, Kind.VS, "しろ:する", "せよ:する")
        // If
        r("if", none, Kind.V1, "れば:る")
        r("if", none, Kind.V5, "けば:く", "げば:ぐ", "せば:す", "てば:つ", "ねば:ぬ", "べば:ぶ", "めば:む", "えば:う")
        r("if", none, Kind.VK, "くれば:くる", "来れば:来る")
        r("if", none, Kind.VS, "すれば:する")
        r("if", none, Kind.ADJ_I, "ければ:い")
        r("when", none, Kind.V1, "たら:る")
        r("when", none, Kind.V5, "いたら:く", "いだら:ぐ", "したら:す", "ったら:う", "ったら:つ", "ったら:る", "んだら:ぬ", "んだら:ぶ", "んだら:む")
        r("when", none, Kind.VK, "きたら:くる", "来たら:来る")
        r("when", none, Kind.VS, "したら:する")
        r("when", none, Kind.ADJ_I, "かったら:い")
        // Adjectives
        r("adverb", none, Kind.ADJ_I, "く:い")
        r("noun", none, Kind.ADJ_I, "さ:い")
        r("seems", none, Kind.ADJ_I, "そう:い")
        r("too much", v1, Kind.ADJ_I, "すぎる:い")
        r("too much", v1, Kind.V1, "すぎる:る")
        // ます stem before a verb ending (食べ‐たい is above; this covers 食べ‐ながら)
        r("while", none, Kind.V1, "ながら:る")
        r("while", none, Kind.V5, "きながら:く", "ぎながら:ぐ", "しながら:す", "ちながら:つ", "にながら:ぬ", "びながら:ぶ", "みながら:む", "りながら:る", "いながら:う")
    }

    /** [text] and every word it may be a conjugation of. */
    fun candidates(text: String): List<Candidate> {
        val found = LinkedHashMap<Pair<String, Set<Kind>>, Candidate>()
        val start = Candidate(text, emptySet(), emptyList())
        found[text to start.kinds] = start
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.reasons.size >= MAX_STEPS) continue
            for (rule in rules) {
                if (current.kinds.isNotEmpty() && current.kinds.none { it in rule.kindsIn }) continue
                if (!current.text.endsWith(rule.from)) continue
                // An ending alone is no word ("た" isn't a past of "る"), unless the rule has the word in it (来た).
                if (current.text.length == rule.from.length && !Kana.hasKanji(rule.from)) continue
                val base = current.text.dropLast(rule.from.length) + rule.to
                val next = Candidate(base, rule.kindsOut, current.reasons + rule.reason)
                val key = base to next.kinds
                if (key in found) continue
                found[key] = next
                queue += next
            }
        }
        return found.values.toList()
    }

    /** Whether a dictionary word with these parts of speech (JMdict codes: v1, v5k, adj-i...) can be [kinds]. */
    fun fits(kinds: Set<Kind>, partsOfSpeech: Collection<String>): Boolean {
        if (kinds.isEmpty()) return true
        return partsOfSpeech.any { pos -> kindOf(pos)?.let { it in kinds } == true }
    }

    fun kindOf(pos: String): Kind? = when {
        pos == "v1" || pos == "v1-s" -> Kind.V1
        pos.startsWith("v5") -> Kind.V5
        pos == "vk" -> Kind.VK
        pos == "vs" || pos == "vs-i" || pos == "vs-s" || pos == "vz" -> Kind.VS
        pos == "adj-i" || pos == "adj-ix" -> Kind.ADJ_I
        else -> null
    }

    private const val MAX_STEPS = 6
}
