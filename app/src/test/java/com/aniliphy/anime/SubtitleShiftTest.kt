package com.aniliphy.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleShiftTest {
    @Test
    fun srtMovesBothTimes() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello\n"
        val out = SubtitleShift.shift(srt, SubtitleShift.Format.Srt, 400)
        assertTrue(out.contains("00:00:01,900 --> 00:00:03,400"))
        assertTrue(out.contains("Hello"))
    }

    @Test
    fun neverBelowZeroAndAcrossMinutes() {
        val srt = "00:00:00,200 --> 00:00:59,900\nx"
        val out = SubtitleShift.shift(srt, SubtitleShift.Format.Srt, -500)
        assertTrue(out.startsWith("00:00:00,000 --> 00:00:59,400"))
        assertTrue(SubtitleShift.shift("00:00:59,900 --> 00:01:00,000\nx", SubtitleShift.Format.Srt, 200).startsWith("00:01:00,100 --> 00:01:00,200"))
    }

    @Test
    fun vttKeepsItsShortTimesAndSettings() {
        val vtt = "WEBVTT\n\n01:02.000 --> 01:04.500 line:90%\nHi"
        val out = SubtitleShift.shift(vtt, SubtitleShift.Format.Vtt, 1000)
        assertTrue(out, out.contains("01:03.000 --> 01:05.500 line:90%"))
        assertTrue(out.startsWith("WEBVTT"))
    }

    @Test
    fun assOnlyTheTimeFields() {
        val ass = "[Events]\nDialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,At 0:00:05.00 sharp"
        val out = SubtitleShift.shift(ass, SubtitleShift.Format.Ass, 250)
        assertEquals("Dialogue: 0,0:00:01.75,0:00:03.25,Default,,0,0,0,,At 0:00:05.00 sharp", out.lines()[1])
    }

    @Test
    fun formatFromNameOrText() {
        assertEquals(SubtitleShift.Format.Ass, SubtitleShift.formatOf("a.ass", ""))
        assertEquals(SubtitleShift.Format.Vtt, SubtitleShift.formatOf("x?y=1", "WEBVTT\n"))
        assertEquals(SubtitleShift.Format.Srt, SubtitleShift.formatOf("a.srt", "1\n"))
    }

    @Test
    fun sleepTimerFadesAtTheEnd() {
        assertEquals(1f, SleepTimer.volume(60_000), 0f)
        assertEquals(0.5f, SleepTimer.volume(5_000), 0.001f)
        assertEquals(0f, SleepTimer.volume(0), 0f)
        assertEquals("28:40", SleepTimer.label(1_720_000))
        assertEquals("0:01", SleepTimer.label(1))
    }

    @Test
    fun languageFromFileName() {
        assertEquals("ja", TitleSubtitles.languageOf("Frieren - 21.ja.srt"))
        assertEquals("ja", TitleSubtitles.languageOf("葬送のフリーレン 21.ass"))
        assertEquals("en", TitleSubtitles.languageOf("Show [eng].vtt"))
        assertEquals(null, TitleSubtitles.languageOf("Show 21.srt"))
    }

    @Test
    fun jimakuKeepsOnlySubtitleFiles() {
        val files = listOf(Jimaku.SubFile("u", "b.zip"), Jimaku.SubFile("u", "B.ass"), Jimaku.SubFile("u", "a.srt"), Jimaku.SubFile("u", "c.7z"))
        assertEquals(listOf("a.srt", "B.ass"), Jimaku.usable(files).map { it.name })
    }
}
