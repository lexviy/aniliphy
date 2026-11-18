package com.aniliphy.japanese

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.zip.ZipInputStream

class JapaneseTest {
    @get:Rule val folder = TemporaryFolder()

    private fun e(id: Long, kanji: String?, kana: String, pos: String, gloss: String, common: Boolean = true) = DictEntry(
        id,
        kanji = listOfNotNull(kanji?.let { DictForm(it, common) }),
        kana = listOf(DictForm(kana, common)),
        senses = listOf(DictSense(pos = listOf(pos), gloss = listOf(gloss))),
    )

    private val dict = ListWordSource(
        listOf(
            e(1, "食べる", "たべる", "v1", "to eat"),
            e(2, "死ぬ", "しぬ", "v5n", "to die"),
            e(3, null, "もう", "adv", "already"),
            e(4, "お前", "おまえ", "pn", "you"),
            e(5, null, "は", "prt", "topic marker"),
            e(6, null, "いる", "v1", "to be"),
            e(7, "勉強", "べんきょう", "vs", "study"),
            e(8, "高い", "たかい", "adj-i", "tall"),
            e(9, "行く", "いく", "v5k-s", "to go"),
            e(10, "来る", "くる", "vk", "to come"),
            e(11, null, "よ", "prt", "emphasis"),
        ),
    )

    private fun base(text: String) = Lookup.at(text, 0, dict).firstOrNull()

    @Test
    fun conjugationsAreUndone() {
        val m = base("食べられなかった")!!
        assertEquals("食べる", m.form)
        assertEquals(listOf("passive or potential", "negative", "past"), m.reasons)
        assertEquals("死ぬ", base("死んでいる")!!.form)
        assertEquals("行く", base("行きます")!!.form)
        assertEquals("行く", base("行った")!!.form)
        assertEquals("高い", base("高くない")!!.form)
        assertEquals("来る", base("来た")!!.form)
        assertEquals("勉強する", base("勉強した")!!.form)
        assertEquals("食べる", base("食べちゃった")!!.form)
        // Kinds must fit: 高い isn't a verb, so 高かせる isn't 高い.
        assertNull(Lookup.at("高かせる", 0, dict).firstOrNull { it.surface == "高かせる" })
    }

    @Test
    fun aLineIsCutIntoWords() {
        val pieces = Lookup.pieces("お前はもう死んでいる！", dict)
        assertEquals(listOf("お前", "は", "もう", "死んでいる", "！"), pieces.map { it.text })
        assertNull(pieces.last().match)
        assertEquals("もう食べられないよ".let { Lookup.pieces(it, dict).map { p -> p.text } }, listOf("もう", "食べられない", "よ"))
    }

    @Test
    fun rubySitsOverTheKanji() {
        assertEquals(listOf(Ruby("死", "し"), Ruby("んでいる", null)), Lookup.ruby("死んでいる", "死ぬ", "しぬ"))
        assertEquals(listOf(Ruby("食", "た"), Ruby("べられない", null)), Lookup.ruby("食べられない", "食べる", "たべる"))
        assertEquals(listOf(Ruby("お", null), Ruby("前", "まえ")), Lookup.ruby("お前", "お前", "おまえ"))
        assertEquals(listOf(Ruby("もう", null)), Lookup.ruby("もう", "もう", "もう"))
        val study = base("勉強しなければならない")!!
        assertEquals("べんきょうする", study.reading)
        assertEquals(Ruby("勉強", "べんきょう"), Lookup.ruby(study.surface, study.form, study.reading).first())
    }

    @Test
    fun subtitleFiles() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\n<i>お前は</i>\nもう\n\n2\n00:00:03,000 --> 00:00:04,000\n死んでいる\n"
        val cues = SubtitleCues.parse("a.srt", srt)
        assertEquals(listOf(Cue(1000, 2500, "お前は\nもう"), Cue(3000, 4000, "死んでいる")), cues)
        assertEquals("死んでいる", SubtitleCues.at(cues, 3500)?.text)
        assertEquals("死んでいる", SubtitleCues.at(cues, 4200, delayMs = 500)?.text)
        assertNull(SubtitleCues.at(cues, 2700))
        assertTrue(SubtitleCues.japanese(cues))

        val ass = """
            [Script Info]
            Title: x
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\an8\i1}もう、\N食べられない
            Dialogue: 0,0:00:05.00,0:00:06.00,Sign,,0,0,0,,{\p1}m 0 0 l 10 10
        """.trimIndent()
        assertEquals(listOf(Cue(1500, 3000, "もう、\n食べられない")), SubtitleCues.parse("b.ass", ass))
        assertFalse(SubtitleCues.japanese(SubtitleCues.parse("c.vtt", "WEBVTT\n\n00:01.000 --> 00:02.000\nYou are already dead.\n")))
    }

    @Test
    fun jmdictIsReadAWordAtATime() {
        val text = """{"version":"3.6","tags":{"v1":"Ichidan verb","x":"a \"quoted] {thing"},"words":[
            {"id":"1358280","kanji":[{"common":true,"text":"食べる","tags":[]}],"kana":[{"common":true,"text":"たべる","tags":[],"appliesToKanji":["*"]}],
             "sense":[{"partOfSpeech":["v1","vt"],"misc":[],"info":[],"gloss":[{"lang":"eng","gender":null,"type":null,"text":"to eat"}]},
                      {"partOfSpeech":["v1"],"gloss":[{"lang":"eng","text":"to live on (e.g. a salary)"}]}]},
            {"id":"2","kanji":[],"kana":[{"common":false,"text":"ゝ","appliesToKanji":["*"]}],"sense":[{"partOfSpeech":["n"],"gloss":[{"lang":"eng","text":"repetition mark [x]"}]}]}
        ]}"""
        val elements = JmdictJson.elements(StringReader(text)).toList()
        assertEquals(2, elements.size)
        val eat = JmdictJson.entry(elements[0])!!
        assertEquals(1358280L, eat.id)
        assertEquals(listOf("食べる", "たべる"), eat.forms)
        assertEquals(listOf("to eat"), eat.senses[0].gloss)
        assertEquals(setOf("v1", "vt"), eat.partsOfSpeech)
        assertTrue(eat.common)
        assertEquals("たべる", eat.readingFor("食べる"))
        assertEquals("repetition mark [x]", JmdictJson.entry(elements[1])!!.senses[0].gloss[0])

        val name = JmdictJson.entry("""{"id":"5000001","kanji":[{"text":"冬月","tags":[]}],"kana":[{"text":"ふゆつき","tags":[],"appliesToKanji":["*"]}],"translation":[{"type":["surname"],"related":[],"translation":[{"lang":"eng","text":"Fuyutsuki"}]}]}""")!!
        assertTrue(name.name)
        assertEquals(-5000001L, name.id)
        assertEquals(listOf("Fuyutsuki"), name.senses[0].gloss)
    }

    @Test
    fun savedWordsAndExport() {
        val store = WordStore(folder.root)
        val w = SavedWord("a", 1, "食べる", "たべる", listOf("to eat", "to live on"), "もう食べられないよ", "食べられない", 1, "Frieren", "4", 751_000, null)
        store.add(w)
        store.add(w.copy(id = "b"))
        assertEquals(1, store.all().size)
        assertEquals("Frieren · ep 4 · 12:31", w.source)
        assertEquals("もう<b>食べられない</b>よ", WordExport.sentence(w))
        assertEquals("1. to eat<br>2. to live on", WordExport.meaning(w))
        val tsv = WordExport.tsv(store.all())
        assertTrue(tsv.lines()[3].startsWith("食べる\tたべる\t1. to eat"))
        assertEquals(listOf("a"), store.markSent(setOf("a")).filter { it.sentToAnki }.map { it.id })
        val out = ByteArrayOutputStream()
        WordExport.zip(store.all(), store.frames, out)
        val names = ZipInputStream(out.toByteArray().inputStream()).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals(listOf("words.tsv"), names)
        assertTrue(store.remove("a").isEmpty())
    }
}
