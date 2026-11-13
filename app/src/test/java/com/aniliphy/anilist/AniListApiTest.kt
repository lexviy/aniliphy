package com.aniliphy.anilist

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AniListApiTest {
    @get:Rule val folder = TemporaryFolder()

    private fun api(token: String? = "t", answer: (FakeTransport.Request) -> HttpReply): Pair<AniListApi, FakeTransport> {
        val transport = FakeTransport(answer)
        return GraphQlAniListApi(GraphQlClient(transport, ApiCache(folder.root)), { token }) to transport
    }

    private fun progressAnswer(entry: String) =
        ok("""{"Media":{"episodes":12,"chapters":50,"volumes":5,"mediaListEntry":$entry}}""")

    private val savedAnswer = ok("""{"SaveMediaListEntry":{"id":9,"status":"CURRENT","progress":6}}""")

    private fun scripted(entry: String): (FakeTransport.Request) -> HttpReply = { request ->
        if (request.query.trimStart().startsWith("mutation")) savedAnswer else progressAnswer(entry)
    }

    private fun FakeTransport.Request.int(name: String) = variables.getValue(name).jsonPrimitive.int
    private fun FakeTransport.Request.text(name: String) = variables.getValue(name).jsonPrimitive.content

    @Test fun savingAnEpisodeSendsTheRulesAnswer() = runTest {
        val (api, transport) = api(answer = scripted("""{"progress":5,"status":"PLANNING","repeat":0}"""))
        val saved = api.saveEpisode(7, 6, 12)
        assertEquals(9, saved?.id)
        val mutation = transport.mutations.single()
        assertEquals(7, mutation.int("mediaId"))
        assertEquals(6, mutation.int("progress"))
        assertEquals("CURRENT", mutation.text("status"))
        assertFalse(mutation.variables.containsKey("repeat"))
        assertFalse(mutation.variables.containsKey("progressVolumes"))
        assertEquals("t", mutation.token)
    }

    @Test fun anOlderEpisodeSendsNothing() = runTest {
        val (api, transport) = api(answer = scripted("""{"progress":5,"status":"CURRENT"}"""))
        assertNull(api.saveEpisode(7, 3, 12))
        assertTrue(transport.mutations.isEmpty())
    }

    @Test fun titleNotOnTheListCanBeStarted() = runTest {
        val (api, transport) = api(answer = scripted("null"))
        api.startMedia(7)
        val mutation = transport.mutations.single()
        assertEquals("CURRENT", mutation.text("status"))
        assertEquals(0, mutation.int("progress"))
    }

    @Test fun progressIsAlwaysAskedLive() = runTest {
        val (api, transport) = api(answer = scripted("""{"progress":5,"status":"CURRENT"}"""))
        api.progress(7)
        api.progress(7)
        assertEquals(2, transport.requests.size)
    }

    @Test fun chapterTotalComesFromAniList() = runTest {
        val (api, transport) = api(answer = scripted("""{"progress":49,"status":"REPEATING","repeat":1}"""))
        api.saveChapter(7, chapter = 50)
        val mutation = transport.mutations.single()
        assertEquals("COMPLETED", mutation.text("status"))
        assertEquals(2, mutation.int("repeat"))
    }

    @Test fun notesAndListsAreSentOnlyWhenGiven() = runTest {
        val (api, transport) = api(answer = { savedAnswer })
        api.saveEntry(7, ListStatus.CURRENT)
        api.saveEntry(7, ListStatus.CURRENT, notes = "hi", customLists = listOf("Fav"))
        val (plain, full) = transport.mutations
        assertFalse(plain.variables.containsKey("notes"))
        assertFalse(plain.variables.containsKey("customLists"))
        assertEquals("hi", full.text("notes"))
        assertEquals("Fav", full.variables.getValue("customLists").jsonArray.single().jsonPrimitive.content)
    }

    @Test fun customListNamesAreRead() = runTest {
        val (api, _) = api(answer = {
            ok("""{"Viewer":{"mediaListOptions":{"animeList":{"customLists":["Comfy"]},"mangaList":{"customLists":[]}}}}""")
        })
        val lists = api.customLists().value
        assertEquals(listOf("Comfy"), lists.of(MediaKind.Anime))
        assertTrue(lists.of(MediaKind.LightNovel).isEmpty())
    }

    @Test fun progressKnowsNotesAndLists() = runTest {
        val (api, _) = api(answer = scripted("""{"id":44,"status":"CURRENT","notes":"n","customLists":[{"name":"A","enabled":true},{"name":"B","enabled":false}]}"""))
        val progress = api.progress(7)
        assertEquals("n", progress.notes)
        assertEquals(listOf("A"), progress.customLists)
    }

    @Test fun deletingSendsTheEntryId() = runTest {
        val (api, transport) = api(answer = { ok("""{"DeleteMediaListEntry":{"deleted":true}}""") })
        api.deleteEntry(321)
        val mutation = transport.mutations.single()
        assertTrue(mutation.query.contains("DeleteMediaListEntry"))
        assertEquals(321, mutation.int("id"))
    }

    @Test fun deletingFailsWhenAniListKeepsTheEntry() = runTest {
        val (api, _) = api(answer = { ok("""{"DeleteMediaListEntry":{"deleted":false}}""") })
        assertTrue(runCatching { api.deleteEntry(321) }.exceptionOrNull() is AniListException)
    }

    @Test fun progressKnowsTheEntryId() = runTest {
        val (api, _) = api(answer = scripted("""{"id":44,"progress":5,"status":"CURRENT"}"""))
        assertEquals(44, api.progress(7).entryId)
    }

    @Test fun scoreIsSentOutOfAHundred() = runTest {
        val (api, transport) = api(answer = { savedAnswer })
        api.saveEntry(7, ListStatus.COMPLETED, score = 8.5)
        assertEquals(85, transport.mutations.single().int("scoreRaw"))
    }

    @Test fun togglingAFavouriteNamesTheRightField() = runTest {
        val (api, transport) = api(answer = { ok("""{"ToggleFavourite":{}}""") })
        api.toggleFavourite(FavouriteTarget.Studio(3))
        val variables = transport.mutations.single().variables
        assertEquals(setOf("studioId"), variables.keys)
    }

    @Test fun changesWithoutALoginFailBeforeReachingTheNetwork() = runTest {
        val (api, transport) = api(token = null, answer = { savedAnswer })
        val failure = runCatching { api.saveEntry(7, ListStatus.CURRENT) }.exceptionOrNull()
        assertTrue(failure is NotLoggedInException)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun airingListIsSoonestFirstWithUndatedLast() = runTest {
        val (api) = api(answer = {
            ok(
                """{"MediaListCollection":{"lists":[{"entries":[
                {"status":"CURRENT","media":{"id":1,"status":"RELEASING","nextAiringEpisode":{"episode":3,"airingAt":300}}},
                {"status":"PAUSED","media":{"id":2,"status":"RELEASING","nextAiringEpisode":null}},
                {"status":"PLANNING","media":{"id":3,"status":"NOT_YET_RELEASED","nextAiringEpisode":{"episode":1,"airingAt":100}}},
                {"status":"PLANNING","media":{"id":4,"status":"FINISHED","nextAiringEpisode":null}},
                {"status":"PLANNING","media":{"id":5,"status":"NOT_YET_RELEASED","nextAiringEpisode":null}}
                ]}]}}""",
            )
        })
        assertEquals(listOf(3, 1, 2), api.airingFromList(1).value.map { it.media.id })
    }

    @Test fun titleOnACustomListIsNotCountedTwice() = runTest {
        val (api) = api(answer = {
            ok(
                """{"MediaListCollection":{"lists":[
                {"entries":[{"status":"CURRENT","media":{"id":1}},{"status":"CURRENT","media":{"id":2}}]},
                {"entries":[{"status":"CURRENT","media":{"id":1}}]}
                ]}}""",
            )
        })
        assertEquals(listOf(1, 2), api.animeList(1).value.map { it.media.id })
    }

    @Test fun mangaAndNovelsSplitByFormat() = runTest {
        val (api) = api(answer = {
            ok(
                """{"MediaListCollection":{"lists":[{"entries":[
                {"media":{"id":1,"type":"MANGA","format":"MANGA"}},
                {"media":{"id":2,"type":"MANGA","format":"ONE_SHOT"}},
                {"media":{"id":3,"type":"MANGA","format":"NOVEL"}}
                ]}]}}""",
            )
        })
        val all = api.mangaList(1).value
        assertEquals(listOf(1, 2), all.ofKind(MediaKind.Manga).map { it.media.id })
        assertEquals(listOf(3), all.ofKind(MediaKind.LightNovel).map { it.media.id })
    }

    @Test fun searchAsksForTheRightFormats() = runTest {
        val (api, transport) = api(answer = { ok("""{"Page":{"pageInfo":{"hasNextPage":true},"media":[{"id":1}]}}""") })
        val page = api.search("x", MediaKind.LightNovel).value
        assertTrue(page.hasNext)
        assertEquals(1, page.items.single().id)
        assertEquals(
            listOf("NOVEL"),
            transport.requests[0].variables.getValue("formats").jsonArray.map { it.jsonPrimitive.content },
        )
        api.search("x", MediaKind.Anime)
        assertFalse(transport.requests[1].variables.containsKey("formats"))
    }

    @Test fun browsingWithoutTextShowsThePopularOnes() = runTest {
        val (api, transport) = api(answer = { ok("""{"Page":{"pageInfo":{"hasNextPage":false},"characters":[]}}""") })
        api.characters()
        api.characters("  ")
        api.characters("gojo")
        fun sort(i: Int) = transport.requests.getOrNull(i)?.variables?.get("sort")?.jsonArray?.single()?.jsonPrimitive?.content
        assertEquals("FAVOURITES_DESC", sort(0))
        assertEquals(2, transport.requests.size)
        assertEquals("SEARCH_MATCH", sort(1))
    }

    @Test fun characterGathersAllPages() = runTest {
        val (api, transport) = api(answer = { request ->
            val page = request.int("page")
            ok(
                """{"Character":{"id":1,"name":{"full":"A"},"age":"17","media":{
                "pageInfo":{"hasNextPage":${page < 2}},
                "edges":[{"characterRole":"MAIN","node":{"id":$page}}]}}}""",
            )
        })
        val character = api.character(1).value
        assertEquals("17", character.age)
        assertEquals(listOf(1, 2), character.media.map { it.node?.id })
        assertEquals(2, transport.requests.size)
    }

    @Test fun staffGathersAllPagesAndHasANumericAge() = runTest {
        val (api) = api(answer = {
            ok(
                """{"Staff":{"id":1,"age":40,"yearsActive":[2000],"staffMedia":{
                "pageInfo":{"hasNextPage":false},
                "edges":[{"staffRole":"Director","node":{"id":5}}]}}}""",
            )
        })
        val staff = api.staff(1).value
        assertEquals(40, staff.age)
        assertEquals("Director", staff.media.single().staffRole)
    }

    @Test fun nullFieldsFallBackToDefaults() = runTest {
        val (api) = api(answer = {
            ok("""{"Media":{"id":1,"genres":null,"synonyms":null,"title":{"romaji":"R","english":null},"isFavourite":null}}""")
        })
        val media = api.media(1).value
        assertEquals(emptyList<String>(), media.genres)
        assertEquals("R", media.title.display)
        assertFalse(media.isFavourite)
    }

    @Test fun missingMediaIsNotFound() = runTest {
        val (api) = api(answer = { ok("""{"Media":null}""") })
        assertTrue(runCatching { api.media(1) }.exceptionOrNull() is NotFoundException)
    }

    @Test fun overviewDropsActivitiesWithoutMedia() = runTest {
        val (api) = api(answer = {
            ok(
                """{"Viewer":{"id":1,"name":"N"},"Page":{"activities":[
                {"id":1,"progress":"3 - 5","media":{"id":2}},{}]}}""",
            )
        })
        val overview = api.overview(1).value
        assertEquals("N", overview.viewer.name)
        assertEquals("3 - 5", overview.activities.single().progress)
    }

    @Test fun favouritesGatherEveryPageOfEveryType() = runTest {
        val (api, transport) = api(answer = { request ->
            when (request.int("page")) {
                1 -> ok(
                    """{"User":{"favourites":{
                    "anime":{"pageInfo":{"hasNextPage":false},"nodes":[{"id":1}]},
                    "manga":{"pageInfo":{"hasNextPage":true},"nodes":[{"id":2,"type":"MANGA","format":"NOVEL"}]},
                    "characters":{"pageInfo":{"hasNextPage":false},"nodes":[]},
                    "staff":{"pageInfo":{"hasNextPage":false},"nodes":[]},
                    "studios":{"pageInfo":{"hasNextPage":false},"nodes":[{"id":5,"name":"S"}]}}}}""",
                )
                else -> ok(
                    """{"User":{"favourites":{
                    "anime":{"pageInfo":{"hasNextPage":false},"nodes":[]},
                    "manga":{"pageInfo":{"hasNextPage":false},"nodes":[{"id":3,"type":"MANGA","format":"MANGA"}]},
                    "characters":{"pageInfo":{"hasNextPage":false},"nodes":[]},
                    "staff":{"pageInfo":{"hasNextPage":false},"nodes":[]},
                    "studios":{"pageInfo":{"hasNextPage":false},"nodes":[]}}}}""",
                )
            }
        })
        val favourites = api.favourites(1).value
        assertEquals(listOf(1), favourites.anime.nodes.map { it.id })
        assertEquals(listOf(2, 3), favourites.manga.nodes.map { it.id })
        assertEquals(MediaKind.LightNovel, favourites.manga.nodes.first().kind)
        assertEquals("S", favourites.studios.nodes.single().name)
        assertEquals(2, transport.requests.size)
    }

    @Test fun animePeopleKeepAniListsCounts() = runTest {
        val (api, _) = api(answer = {
            ok(
                """{"User":{"statistics":{"anime":{
                "studios":[{"count":26,"studio":{"id":7,"name":"A-1"}},{"count":1,"studio":null}],
                "staff":[{"count":46,"staff":{"id":8,"name":{"full":"S"}}}],
                "voiceActors":[{"count":25,"voiceActor":{"id":9,"name":{"full":"V"}}}]}}}}""",
            )
        })
        val people = api.animePeople(1).value
        assertEquals(listOf(RankedPerson(7, "A-1", 26)), people.studios)
        assertEquals(listOf(RankedPerson(8, "S", 46)), people.staff)
        assertEquals(listOf(RankedPerson(9, "V", 25)), people.voiceActors)
    }

    @Test fun watchingAndAiringComeFromTheWholeListInOneRequest() = runTest {
        val (api, transport) = api(answer = {
            ok(
                """{"MediaListCollection":{"lists":[{"entries":[
                {"status":"CURRENT","updatedAt":5,"media":{"id":1,"type":"ANIME","status":"RELEASING","nextAiringEpisode":{"episode":3,"airingAt":300}}},
                {"status":"REPEATING","updatedAt":9,"media":{"id":2,"type":"ANIME","status":"FINISHED"}},
                {"status":"COMPLETED","updatedAt":7,"media":{"id":3,"type":"ANIME","status":"RELEASING","nextAiringEpisode":{"episode":9,"airingAt":100}}},
                {"status":"PLANNING","updatedAt":1,"media":{"id":4,"type":"ANIME","status":"NOT_YET_RELEASED","nextAiringEpisode":{"episode":1,"airingAt":200}}}
                ]}]}}""",
            )
        })
        assertEquals(listOf(2, 1), api.watching(1).value.map { it.media.id })
        assertEquals(listOf(4, 1), api.airingFromList(1).value.map { it.media.id })
        assertEquals(1, transport.requests.size)
    }
}
