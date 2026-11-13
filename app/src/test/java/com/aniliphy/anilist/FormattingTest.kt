package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FormattingTest {
    @Test fun nice() {
        assertEquals("Tv short", Formatting.nice("TV_SHORT"))
        assertEquals("?", Formatting.nice(null))
        assertEquals("?", Formatting.nice(""))
    }

    @Test fun relativeTime() {
        val now = 100_000_000L
        assertEquals("", Formatting.relativeTime(null, now))
        assertEquals("just now", Formatting.relativeTime(now - 59, now))
        assertEquals("just now", Formatting.relativeTime(now + 500, now))
        assertEquals("1m ago", Formatting.relativeTime(now - 60, now))
        assertEquals("1h ago", Formatting.relativeTime(now - 3600, now))
        assertEquals("1d ago", Formatting.relativeTime(now - 86400, now))
        assertEquals("1mo ago", Formatting.relativeTime(now - 86400 * 30, now))
        assertEquals("1y ago", Formatting.relativeTime(now - 86400 * 365, now))
    }

    @Test fun date() {
        assertEquals("?", Formatting.date(null))
        assertEquals("?", Formatting.date(FuzzyDate()))
        assertEquals("2027", Formatting.date(FuzzyDate(2027)))
        assertEquals("Jan 2027", Formatting.date(FuzzyDate(2027, 1)))
        assertEquals("Jan 10, 2027", Formatting.date(FuzzyDate(2027, 1, 10)))
    }

    @Test fun countdown() {
        assertEquals("0m", Formatting.countdown(-50))
        assertEquals("5m", Formatting.countdown(300))
        assertEquals("1h 0m", Formatting.countdown(3600))
        assertEquals("1d 0h 0m", Formatting.countdown(86400))
        assertEquals("2d 3h 4m", Formatting.countdown(2 * 86400 + 3 * 3600 + 4 * 60 + 59))
    }

    @Test fun nextEpisode() {
        val media = Media(id = 1, nextAiringEpisode = NextAiring(7, 86400))
        assertEquals("Ep 7 in 1h 0m (Fri, Jan 02, 00:00)", Formatting.nextEpisode(media, 82800, ZoneOffset.UTC))
        assertNull(Formatting.nextEpisode(Media(id = 1), 0, ZoneOffset.UTC))
    }

    @Test fun cleanDescription() {
        assertEquals("No synopsis.", Formatting.cleanDescription(null))
        assertEquals("One\nTwo", Formatting.cleanDescription("<i>One</i><br>Two "))
        assertEquals("One\nTwo", Formatting.cleanDescription("One<BR />Two"))
        assertEquals("A & B's <c> é", Formatting.cleanDescription("A &amp; B&#39;s &lt;c&gt; &#xE9;"))
        assertEquals("&unknown;", Formatting.cleanDescription("&unknown;"))
        assertEquals("One\n\nTwo", Formatting.cleanDescription("One<br><br>\n<br>\n Two"))
    }

    @Test fun infoRowsDependOnTheKind() {
        val anime = Media(id = 1, type = "ANIME", season = "FALL", seasonYear = 2026, duration = 24)
        val rows = Formatting.infoRows(anime, 0, ZoneOffset.UTC).toMap()
        assertEquals("Fall 2026", rows["Season"])
        assertEquals("24 min", rows["Duration"])

        val manga = Formatting.infoRows(Media(id = 1, type = "MANGA", format = "MANGA"), 0, ZoneOffset.UTC).toMap()
        assertNull(manga["Season"])
        assertNull(manga["Duration"])
    }

    @Test fun animeDetails() {
        assertEquals(
            "TV Short · 12 eps · 80%",
            Formatting.animeDetails(Media(id = 1, format = "TV_SHORT", episodes = 12, averageScore = 80)),
        )
        assertEquals("", Formatting.animeDetails(Media(id = 1)))
    }

    @Test fun relationsFollowTheOrderAndNameTheFormat() {
        val media = Media(
            id = 1,
            relations = Edges(
                listOf(
                    RelationEdge("OTHER", Media(id = 2, type = "ANIME")),
                    RelationEdge("ADAPTATION", Media(id = 3, type = "MANGA", format = "NOVEL")),
                    RelationEdge("PREQUEL", Media(id = 4, type = "ANIME")),
                    RelationEdge("SEQUEL", null),
                ),
            ),
        )
        val items = Formatting.relations(media)
        assertEquals(listOf(4, 3, 2), items.map { it.id })
        assertEquals("Adaptation\nLight novel", items[1].kind)
    }

    @Test fun recommendationsNameTheKindAndScore() {
        val media = Media(
            id = 1,
            recommendations = Nodes(
                listOf(
                    Recommendation(1, Media(id = 2, type = "MANGA", format = "NOVEL", averageScore = 75)),
                    Recommendation(1, null),
                ),
            ),
        )
        assertEquals("Light novel\n★ 75%", Formatting.recommendations(media).single().kind)
    }

    @Test fun byYearKeepsRunsTogether() {
        fun made(id: Int, year: Int?) = Media(id = id, startDate = FuzzyDate(year))
        val groups = Formatting.byYear(listOf(made(1, 2026), made(2, 2026), made(3, 2025), made(4, null)))
        assertEquals(listOf(2026, 2025, null), groups.map { it.first })
        assertEquals(2, groups[0].second.size)
    }

    @Test fun seasons() {
        assertEquals("FALL", AnimeSeason.of(LocalDate.of(2026, 11, 9)).name)
        assertEquals("WINTER", AnimeSeason.of(LocalDate.of(2026, 1, 1)).name)
        assertEquals("SPRING", AnimeSeason.of(LocalDate.of(2026, 4, 1)).name)
        assertEquals("SUMMER", AnimeSeason.of(LocalDate.of(2026, 9, 30)).name)
        val next = AnimeSeason.of(LocalDate.of(2026, 11, 9)).next()
        assertEquals(AnimeSeason("WINTER", 2027), next)
        assertEquals("Winter 2027", next.label)
        assertEquals(AnimeSeason("SPRING", 2027), next.next())
    }

    @Test fun listStats() {
        fun entry(status: String?, progress: Int, score: Double, episodes: Int?, duration: Int?, format: String?) =
            ListEntry(
                status = status,
                progress = progress,
                score = score,
                media = Media(id = progress, episodes = episodes, duration = duration, format = format),
            )
        val stats = ListStats.of(
            listOf(
                entry(ListStatus.COMPLETED, 24, 8.0, 24, 60, "TV"),
                entry(ListStatus.CURRENT, 12, 0.0, 1, null, "MOVIE"),
                entry(ListStatus.CURRENT, 0, 6.0, null, 24, "TV"),
            ),
            MediaKind.Anime,
        )
        assertEquals(3, stats.count)
        assertEquals(36, stats.progress)
        assertEquals((24 * 60 + 12 * 24) / 1440.0, stats.daysWatched, 0.0001)
        assertEquals(7.0, stats.meanScore, 0.0001)
        assertEquals(1, stats.lengths.toMap()["17-28"])
        assertEquals(1, stats.lengths.toMap()["1"])
        assertEquals(1, stats.lengths.toMap()["Unknown"])
        assertEquals("TV" to 2, stats.formats.first())
        assertEquals(0, stats.formats.toMap()["OVA"])
        assertEquals(ListStatus.CURRENT to 2, stats.statuses.first())
    }

    @Test fun emptyListStats() {
        val stats = ListStats.of(emptyList(), MediaKind.LightNovel)
        assertEquals(0.0, stats.meanScore, 0.0)
        assertEquals(0, stats.formats.toMap()["NOVEL"])
    }

    @Test fun formatsAreWrittenAsPeopleWriteThem() {
        assertEquals("TV", Formatting.format("TV"))
        assertEquals("TV Short", Formatting.format("TV_SHORT"))
        assertEquals("OVA", Formatting.format("OVA"))
        assertEquals("Light novel", Formatting.format("NOVEL"))
        assertEquals("One shot", Formatting.format("ONE_SHOT"))
        assertEquals("Movie", Formatting.format("MOVIE"))
    }

    @Test fun airingLine() {
        fun at(airingAt: Long?) = Media(id = 1, nextAiringEpisode = airingAt?.let { NextAiring(7, it) })
        assertEquals("Ep 7 in 1h 0m", Formatting.airingLine(at(3600), 0))
        assertEquals("Ep 7 out now", Formatting.airingLine(at(100), 100))
        assertEquals("Date unknown", Formatting.airingLine(at(null), 0))
    }

    @Test fun listProgress() {
        fun anime(total: Int?) = ListEntry(progress = 2, media = Media(id = 1, type = "ANIME", episodes = total))
        assertEquals("2 / 24 eps", Formatting.listProgress(anime(24)))
        assertEquals("2 / ? eps", Formatting.listProgress(anime(null)))
        val paper = Media(id = 1, type = "MANGA", format = "NOVEL", chapters = 40, volumes = 5)
        assertEquals("3 / 40 ch", Formatting.listProgress(ListEntry(progress = 3, media = paper)))
        assertEquals("2 / 5 vols", Formatting.listProgress(ListEntry(progressVolumes = 2, media = paper)))
        assertEquals("3 / 40 ch · 2 / 5 vols", Formatting.listProgress(ListEntry(progress = 3, progressVolumes = 2, media = paper)))
    }

    @Test fun scoreHidesZeroAndWholeDecimals() {
        assertNull(Formatting.score(0.0))
        assertNull(Formatting.score(null))
        assertEquals("8", Formatting.score(8.0))
        assertEquals("8.5", Formatting.score(8.5))
    }

    @Test fun titleSearchIgnoresCaseAndChecksEveryTitle() {
        val media = Media(id = 1, title = MediaTitle(romaji = "Sousou no Frieren", english = "Frieren"))
        assertEquals(true, Formatting.matches(media, " SOUSOU "))
        assertEquals(true, Formatting.matches(media, "frie"))
        assertEquals(true, Formatting.matches(media, ""))
        assertEquals(false, Formatting.matches(media, "bocchi"))
    }

    @Test fun personTextCleansMarkdownAndFindsTheHeight() {
        val text = Formatting.personText(
            "__Height:__ 190 cm\n**Bold** __under__ *it* ~~gone~~ [link](http://x)",
            staff = false,
            showSpoilers = false,
        )
        assertEquals("190 cm", text.height)
        assertEquals("Bold under it gone link", text.description)
    }

    @Test fun spoilersStayHiddenUntilAsked() {
        val raw = "Known. ~!The secret!~ End."
        val hidden = Formatting.personText(raw, staff = false, showSpoilers = false)
        assertEquals("Known. [Spoiler] End.", hidden.description)
        assertEquals(true, hidden.hasSpoilers)
        assertEquals("Known. The secret End.", Formatting.personText(raw, false, true).description)
        assertEquals(false, Formatting.personText("Plain", false, false).hasSpoilers)
    }

    @Test fun staffBiographyDropsLinesThatAreOnlyLinks() {
        val raw = "Bio line\n[Twitter](http://a) | [Site](http://b)\nMore about [them](http://c)"
        assertEquals("Bio line\nMore about them", Formatting.personText(raw, true, false).description)
    }

    @Test fun personWithoutDescription() {
        assertEquals("No description.", Formatting.personText(null, false, false).description)
    }

    @Test fun birthday() {
        assertNull(Formatting.birthday(null))
        assertNull(Formatting.birthday(FuzzyDate(2000, 1)))
        assertEquals("Dec 7", Formatting.birthday(FuzzyDate(null, 12, 7)))
        assertEquals("Dec 7, 1989", Formatting.birthday(FuzzyDate(1989, 12, 7)))
    }

    @Test fun yearsActive() {
        assertNull(Formatting.yearsActive(emptyList()))
        assertEquals("1995-present", Formatting.yearsActive(listOf(1995)))
        assertEquals("1995-2020", Formatting.yearsActive(listOf(1995, 2020)))
    }

    @Test fun lightNovelLengthsCountVolumes() {
        val novel = ListEntry(media = Media(id = 1, format = "NOVEL", chapters = 300, volumes = 4))
        val lengths = ListStats.of(listOf(novel), MediaKind.LightNovel).lengths.toMap()
        assertEquals(1, lengths["2-5"])
        assertEquals(0, lengths["201+"])
        val manga = ListEntry(media = Media(id = 1, format = "MANGA", chapters = 300, volumes = 4))
        assertEquals(1, ListStats.of(listOf(manga), MediaKind.Manga).lengths.toMap()["201+"])
    }

    @Test fun statsLeaveOutPlanningAndCountRepeats() {
        val seen = ListEntry(
            status = "COMPLETED", progress = 12, repeat = 2,
            media = Media(id = 1, episodes = 12, duration = 20, format = "TV"),
        )
        val planned = ListEntry(status = "PLANNING", media = Media(id = 2, episodes = 24, format = "MOVIE"))
        val stats = ListStats.of(listOf(seen, planned), MediaKind.Anime)
        assertEquals(1, stats.count)
        assertEquals(36, stats.progress)
        assertEquals(36 * 20 / 1440.0, stats.daysWatched, 0.0001)
        assertEquals(0, stats.formats.toMap()["MOVIE"])
        assertEquals(1, stats.statuses.toMap()["PLANNING"])
    }

    private fun insightEntry(
        id: Int,
        status: String = "COMPLETED",
        score: Double = 0.0,
        repeat: Int = 0,
        progress: Int = 0,
        progressVolumes: Int = 0,
        episodes: Int? = null,
        chapters: Int? = null,
        volumes: Int? = null,
        duration: Int? = null,
    ) = ListEntry(
        status = status, score = score, repeat = repeat, progress = progress, progressVolumes = progressVolumes,
        media = Media(id = id, title = MediaTitle(romaji = "T$id"), episodes = episodes, chapters = chapters, volumes = volumes, duration = duration),
    )

    @Test fun insightsRankGenresWithoutPlanningAndUseOnlyGivenScores() {
        val entries = listOf(
            insightEntry(1, score = 8.0),
            insightEntry(2, score = 6.0),
            insightEntry(3),
            insightEntry(4, status = "PLANNING"),
        )
        val details = mapOf(
            1 to Media(id = 1, genres = listOf("Action", "Drama")),
            2 to Media(id = 2, genres = listOf("Action")),
            3 to Media(id = 3, genres = listOf("Action", "Comedy")),
            4 to Media(id = 4, genres = listOf("Sports")),
        )
        val genres = ListInsights.of(entries, MediaKind.Anime, details).genres
        assertEquals(listOf("Action", "Comedy", "Drama"), genres.map { it.name })
        assertEquals(3, genres[0].count)
        assertEquals(7.0, genres[0].meanScore!!, 0.0001)
        assertEquals(null, genres[1].meanScore)
    }

    @Test fun insightsHideSpoilerAndWeakTags() {
        val details = mapOf(
            1 to Media(
                id = 1,
                tags = listOf(
                    MediaTag("Ghoul", rank = 90),
                    MediaTag("Twist", rank = 90, isMediaSpoiler = true),
                    MediaTag("Secret", rank = 90, isGeneralSpoiler = true),
                    MediaTag("Weak", rank = 10),
                ),
            ),
        )
        assertEquals(
            listOf("Ghoul"),
            ListInsights.of(listOf(insightEntry(1)), MediaKind.Anime, details).tags.map { it.name },
        )
    }

    @Test fun insightsCountEachPersonOncePerTitle() {
        val author = StaffEdge(node = PersonRef(id = 9, name = PersonName(full = "A")))
        val details = mapOf(
            1 to Media(id = 1, staff = Edges(listOf(author, author))),
            2 to Media(id = 2, staff = Edges(listOf(author))),
        )
        val staff = ListInsights.of(listOf(insightEntry(1), insightEntry(2)), MediaKind.Manga, details).staff
        assertEquals(listOf(RankedPerson(9, "A", 2)), staff)
    }

    @Test fun insightsScoresShowEveryStepOnlyWhenScored() {
        assertEquals(emptyList<Pair<String, Int>>(), ListInsights.of(listOf(insightEntry(1)), MediaKind.Anime, emptyMap()).scores)
        val scores = ListInsights.of(
            listOf(insightEntry(1, score = 7.5), insightEntry(2, score = 10.0)),
            MediaKind.Anime,
            emptyMap(),
        ).scores
        assertEquals(10, scores.size)
        assertEquals("10" to 1, scores.first())
        assertEquals(1, scores.toMap()["8"])
        assertEquals(0, scores.toMap()["1"])
    }

    @Test fun insightsGroupReleaseDatesNewestFirst() {
        val details = mapOf(
            1 to Media(id = 1, startDate = FuzzyDate(year = 1999)),
            2 to Media(id = 2, startDate = FuzzyDate(year = 2014)),
            3 to Media(id = 3, startDate = FuzzyDate(year = 2019)),
        )
        val insights = ListInsights.of(listOf(insightEntry(1), insightEntry(2), insightEntry(3)), MediaKind.Anime, details)
        assertEquals(listOf("2010s" to 2, "1990s" to 1), insights.decades)
        assertEquals(listOf("2019", "2014", "1999"), insights.years.map { it.first })
    }

    @Test fun insightsOrderRepeatsAndLeaveOutNeverRepeated() {
        val insights = ListInsights.of(
            listOf(insightEntry(1, repeat = 1), insightEntry(2, repeat = 3), insightEntry(3)),
            MediaKind.Anime,
            emptyMap(),
        )
        assertEquals(listOf(2, 1), insights.mostRepeated.map { it.media.id })
    }

    @Test fun insightsWorkOutWhatsLeft() {
        val anime = ListInsights.of(
            listOf(
                insightEntry(1, status = "CURRENT", progress = 2, episodes = 12, duration = 20),
                insightEntry(2, status = "PAUSED", progress = 1),
                insightEntry(3, status = "PLANNING", episodes = 1, duration = 100),
                insightEntry(4, status = "DROPPED", progress = 1, episodes = 50),
                insightEntry(5, status = "CURRENT", progress = 15, episodes = 12),
            ),
            MediaKind.Anime,
            emptyMap(),
        )
        assertEquals(Remaining(titles = 3, units = 10, minutes = 200, unknown = 1), anime.inProgress)
        assertEquals(Remaining(titles = 1, units = 1, minutes = 100, unknown = 0), anime.planning)
        val novels = ListInsights.of(
            listOf(insightEntry(1, status = "CURRENT", progress = 300, progressVolumes = 2, chapters = 400, volumes = 5)),
            MediaKind.LightNovel,
            emptyMap(),
        )
        assertEquals(Remaining(titles = 1, units = 3, minutes = 0, unknown = 0), novels.inProgress)
    }

    @Test fun durationsAreShort() {
        assertEquals("45 min", Formatting.duration(45))
        assertEquals("2 h 5 min", Formatting.duration(125))
        assertEquals("3 d 1 h", Formatting.duration(3 * 1440 + 60))
    }

    @Test fun activityDetailsReadLikeSentences() {
        val anime = Media(id = 1, type = "ANIME", episodes = 12)
        assertEquals("Watched episode 3/12", Formatting.activityDetail(Activity(status = "watched episode", progress = "3", media = anime)))
        assertEquals("Watched episodes 1 - 3 of 12", Formatting.activityDetail(Activity(status = "watched episode", progress = "1 - 3", media = anime)))
        assertEquals("Completed", Formatting.activityDetail(Activity(status = "completed", media = anime)))
        assertEquals("Activity", Formatting.activityDetail(Activity(media = anime)))
    }

    @Test
    fun countsAreShortened() {
        assertEquals("950", Formatting.compactCount(950))
        assertEquals("1k", Formatting.compactCount(1_000))
        assertEquals("1.2k", Formatting.compactCount(1_249))
        assertEquals("34k", Formatting.compactCount(34_567))
        assertEquals("1.5M", Formatting.compactCount(1_500_000))
    }
}
