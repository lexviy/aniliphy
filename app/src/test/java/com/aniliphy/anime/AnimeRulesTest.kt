package com.aniliphy.anime

import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.source.SourceEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnimeRulesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun episode(number: Double, id: String = "e$number") = SourceEpisode(id, number, "Episode $number")

    private val episodes = (1..12).map { episode(it.toDouble()) }

    private fun place(number: Double, ms: Long, id: String? = "e$number", watched: Int = 0) =
        AnimeRecord(episodeId = id, episodeNumber = number, positionMs = ms, durationMs = 1_400_000, watched = watched)

    private fun progress(count: Int, status: String = ListStatus.CURRENT) = ListProgress(progress = count, status = status)

    @Test fun startsAtTheFirstEpisodeWithNothingKnown() {
        assertEquals(StartEpisode(0, 0), AnimeRules.start(episodes, null, null))
    }

    @Test fun resumesFromTenSeconds() {
        assertEquals(StartEpisode(4, 60_000), AnimeRules.start(episodes, progress(4), place(5.0, 60_000)))
        assertEquals(StartEpisode(4, 0), AnimeRules.start(episodes, progress(4), place(5.0, 9_000)))
        assertEquals(StartEpisode(4, 60_000), AnimeRules.start(episodes, null, place(5.0, 60_000, id = "other")))
    }

    @Test fun watchedOnAniListOpensTheNextEpisode() {
        assertEquals(StartEpisode(11, 0), AnimeRules.start(episodes, progress(12), null))
        assertEquals(StartEpisode(7, 0), AnimeRules.start(episodes, progress(7), null))
        assertEquals(StartEpisode(5, 0), AnimeRules.start(episodes, progress(5), place(5.0, 600_000)))
        assertEquals(StartEpisode(5, 0), AnimeRules.start(episodes, null, place(5.0, 1_300_000, watched = 5)))
    }

    @Test fun completedStartsAgain() {
        assertEquals(StartEpisode(0, 0), AnimeRules.start(episodes, progress(12, ListStatus.COMPLETED), place(12.0, 60_000)))
    }

    @Test fun episodesCountAndPercent() {
        assertEquals(12, AnimeRules.aniListEpisode(episode(12.5)))
        assertNull(AnimeRules.aniListEpisode(episode(0.0)))
        assertEquals(24, AnimeRules.aniListEpisode(episode(25.0), total = 24))
        assertTrue(AnimeRules.watched(80_000, 100_000, 80))
        assertFalse(AnimeRules.watched(79_000, 100_000, 80))
        assertFalse(AnimeRules.watched(80_000, 0, 80))
        assertEquals(54, AnimeRules.percent(AnimeRecord(episodeNumber = 7.0, positionMs = 50, durationMs = 100, total = 12), null))
        assertEquals(50, AnimeRules.percent(AnimeRecord(episodeNumber = 11.0, durationMs = 0), latest = 20.0))
        assertNull(AnimeRules.percent(AnimeRecord(episodeNumber = 3.0), null))
    }

    @Test fun qualities() {
        val heights = listOf(360, 1080, 720)
        assertEquals(1, AnimeRules.pickHeight(heights, VideoQuality.Best))
        assertEquals(0, AnimeRules.pickHeight(heights, VideoQuality.Worst))
        assertEquals(2, AnimeRules.pickHeight(heights, VideoQuality.P720))
        assertEquals(0, AnimeRules.pickHeight(listOf(480, 1080), VideoQuality.P360))
        assertNull(AnimeRules.pickHeight(emptyList(), VideoQuality.Best))
        assertEquals(VideoQuality.P480, AnimeRules.quality(VideoQuality.Best, MobileQuality.P480, metered = true))
        assertEquals(VideoQuality.Best, AnimeRules.quality(VideoQuality.Best, MobileQuality.P480, metered = false))
        assertEquals(VideoQuality.P720, AnimeRules.quality(VideoQuality.P720, MobileQuality.Same, metered = true))
    }

    @Test fun subtitleLanguages() {
        assertEquals("pt", AnimeRules.languageOf("Portuguese (- Portuguese(Brazil))"))
        assertEquals("en", AnimeRules.languageOf("English"))
        assertEquals("en", AnimeRules.languageOf("Subtitles", "EN"))
        assertNull(AnimeRules.languageOf("Klingon"))
    }

    @Test fun historyKeepsThePlace() {
        val history = AnimeHistory(File(folder.root, "h.json"))
        history.setSource(5, "first:https://a-1", title = "T", cover = "c")
        history.savePlace(5, episode(3.0), 90_000, 1_400_000, null, null, now = 7, total = 12, titles = listOf("T"), mal = 99)
        history.markWatched(5, 2)
        history.markWatched(5, 1)
        val record = AnimeHistory(File(folder.root, "h.json")).get(5)!!
        assertEquals(3.0, record.episodeNumber!!, 0.0)
        assertEquals(90_000L, record.positionMs)
        assertEquals(2, record.watched)
        assertEquals(99, record.mal)
        history.setBackup(5, "second:https://b#1")
        history.setSource(5, "second:https://b#1")
        assertNull(history.get(5)!!.episodeId)
        assertNull(history.get(5)!!.backup)
        assertEquals(3.0, history.get(5)!!.episodeNumber!!, 0.0)
        history.clearPlace(5)
        assertFalse(history.get(5)!!.hasPlace)
        assertEquals(0, history.get(5)!!.watched)
    }

    @Test fun newEpisodesFromTheSchedule() {
        val store = NewEpisodes(File(folder.root, "n.json"))
        assertTrue(store.check(listOf(NewEpisodes.Airing(5, nextEpisode = 7, watched = 3))).isEmpty())
        assertEquals(mapOf(5 to 7), store.check(listOf(NewEpisodes.Airing(5, nextEpisode = 8, watched = 3))))
        assertTrue(store.check(listOf(NewEpisodes.Airing(5, nextEpisode = 8, watched = 7))).isEmpty())
        assertEquals(7, store.seen()[5])
        assertEquals(mapOf(5 to 8), store.check(listOf(NewEpisodes.Airing(5, nextEpisode = 9, watched = 7))))
        store.opened(5, 8)
        assertTrue(store.check(listOf(NewEpisodes.Airing(5, nextEpisode = 9, watched = 7))).isEmpty())
    }
}
