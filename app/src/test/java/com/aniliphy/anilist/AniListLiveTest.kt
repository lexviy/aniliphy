package com.aniliphy.anilist

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class AniListLiveTest {
    @get:Rule val folder = TemporaryFolder()

    private lateinit var api: AniListApi

    @Before fun setUp() {
        assumeTrue(System.getenv("ANILIST_LIVE") == "1")
        val client = GraphQlClient(OkHttpTransport(), ApiCache(folder.root))
        api = GraphQlAniListApi(client) { null }
    }

    @Test fun seasons() = runBlocking {
        val now = AnimeSeason.of(LocalDate.now())
        assertTrue(api.season(now).value.items.isNotEmpty())
        assertTrue(api.season(now.next()).value.items.isNotEmpty())
    }

    @Test fun searchSeparatesTheKinds() = runBlocking {
        val novels = api.search("overlord", MediaKind.LightNovel).value.items
        assertTrue(novels.isNotEmpty())
        assertTrue(novels.all { it.kind == MediaKind.LightNovel })
        val manga = api.search("overlord", MediaKind.Manga).value.items
        assertTrue(manga.isNotEmpty())
        assertTrue(manga.all { it.kind == MediaKind.Manga })
        val anime = api.search("overlord", MediaKind.Anime).value.items
        assertTrue(anime.isNotEmpty())
        assertTrue(anime.all { it.kind == MediaKind.Anime })
    }

    @Test fun searchEverything() = runBlocking {
        val results = api.searchEverything("one piece").value
        assertTrue(results.anime.isNotEmpty())
        assertTrue(results.manga.isNotEmpty())
        val people = api.searchEverything("gojo").value
        assertTrue(people.characters.isNotEmpty())
        val studio = api.searchEverything("madhouse").value
        assertTrue(studio.studios.isNotEmpty())
    }

    @Test fun browsing() = runBlocking {
        assertEquals("Satoru Gojou", api.characters().value.items.first().name.full)
        assertTrue(api.staffMembers().value.items.isNotEmpty())
        assertEquals("Kyoto Animation", api.studios().value.items.first().name)
        assertTrue(api.characters("luffy").value.items.isNotEmpty())
        assertTrue(api.staffMembers("oda").value.items.isNotEmpty())
        assertTrue(api.studios("ghibli").value.items.isNotEmpty())
    }

    @Test fun detailsOfCowboyBebop() = runBlocking {
        val details = api.details(1).value
        assertEquals("Cowboy Bebop", details.title.romaji)
        assertEquals(MediaKind.Anime, details.kind)
        assertTrue(details.studios.nodes.isNotEmpty())
        assertTrue(details.characters.edges.isNotEmpty())
        assertTrue(Formatting.relations(details).isNotEmpty())
        assertTrue(Formatting.recommendations(details).isNotEmpty())
        assertTrue(api.media(1).value.description!!.isNotEmpty())
    }

    @Test fun peopleAndStudios() = runBlocking {
        val character = api.character(1).value
        assertTrue(character.media.isNotEmpty())
        val staff = api.staff(95001).value
        assertFalse(staff.name.full.isNullOrEmpty())
        val studio = api.studio(14).value
        assertTrue(studio.animes.isNotEmpty())
    }

    @Test fun listsOfAPublicUser() = runBlocking {
        val real = OkHttpTransport()
        val client = GraphQlClient({ body, _ -> real.post(body, null) }, ApiCache(folder.root))
        val anonymous = GraphQlAniListApi(client) { "unused" }
        val publicUser = client.read(FIND_USERS, vars()).value["Page"]!!.jsonObject["users"]!!.jsonArray
            .map { it.jsonObject }
            .first { user ->
                val statistics = user.getValue("statistics").jsonObject
                listOf("anime", "manga").all {
                    statistics.getValue(it).jsonObject.getValue("count").jsonPrimitive.int > 0
                }
            }
            .getValue("id").jsonPrimitive.int
        val anime = anonymous.animeList(publicUser).value
        val paper = anonymous.mangaList(publicUser).value
        println("public lists: ${anime.size} anime, ${paper.size} manga and novels")
        assertTrue(anime.isNotEmpty())
        assertTrue(anime.all { it.media.kind == MediaKind.Anime })
        assertEquals(anime.size, anime.distinctBy { it.media.id }.size)
        assertTrue(paper.none { it.media.kind == MediaKind.Anime })
        anonymous.watching(publicUser)
        anonymous.reading(publicUser)
        val airing = anonymous.airingFromList(publicUser).value
        val dates = airing.mapNotNull { it.media.nextAiringEpisode?.airingAt }
        assertEquals(dates.sorted(), dates)
        assertTrue(anonymous.favourites(publicUser).value.anime.nodes.size >= 0)
        anonymous.progress(1)
        ListStats.of(anime, MediaKind.Anime)
        ListStats.of(paper.ofKind(MediaKind.LightNovel), MediaKind.LightNovel)
        Unit
    }

    @Test fun unknownTitleIsNotFound() = runBlocking {
        assertTrue(runCatching { api.media(999_999_999) }.exceptionOrNull() is NotFoundException)
    }

    @Test fun badTokenIsRecognised() = runBlocking {
        assertTrue(runCatching { api.viewer("not-a-real-token") }.exceptionOrNull() is InvalidTokenException)
    }

    private companion object {
        const val FIND_USERS = "{Page(perPage:25){users(search:\"a\"){id statistics{anime{count} manga{count}}}}}"
    }
}
