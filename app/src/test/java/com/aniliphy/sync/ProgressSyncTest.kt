package com.aniliphy.sync

import com.aniliphy.anime.AnimeHistory
import com.aniliphy.core.Sources
import com.aniliphy.source.AnimeSite
import com.aniliphy.source.Extension
import com.aniliphy.source.MangaSite
import com.aniliphy.source.ServerLink
import com.aniliphy.source.SourceChapter
import com.aniliphy.source.SourceEpisode
import com.aniliphy.source.SourceSeries
import com.aniliphy.source.SourceServices
import com.aniliphy.source.SourceShow
import com.aniliphy.source.VideoStream
import java.io.IOException
import org.junit.Before
import com.aniliphy.anime.AnimeRecord
import com.aniliphy.manga.MangaHistory
import com.aniliphy.manga.MangaRecord
import com.aniliphy.novels.NovelProgressStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressSyncTest {
    @get:Rule val folder = TemporaryFolder()

    /** Sources that, like the real ones, name shows and chapters differently for the desktop app. */
    private class SyncingSources : Extension {
        override fun anime(services: SourceServices): List<AnimeSite> = listOf(
            object : AnimeSite {
                override val id = "sync"
                override val name = "Sync"
                override fun search(query: String) = emptyList<SourceShow>()
                override fun episodes(showUrl: String) = emptyList<SourceEpisode>()
                override fun servers(episode: SourceEpisode) = emptyList<ServerLink>()
                override fun stream(server: ServerLink): VideoStream = throw IOException()
                override fun syncedShow(showUrl: String) =
                    showUrl.takeIf { it.startsWith(WATCH) }?.removePrefix(WATCH)?.substringBefore('?')?.ifEmpty { null }
                override fun showFromSync(show: String) = WATCH + show
            },
        )

        override fun manga(services: SourceServices): List<MangaSite> = listOf(
            object : MangaSite {
                override val id = "pages"
                override val name = "Pages"
                override val imageHeaders = emptyMap<String, String>()
                override fun search(query: String) = emptyList<SourceSeries>()
                override fun chapters(seriesUrl: String) = emptyList<SourceChapter>()
                override fun pages(chapter: SourceChapter) = emptyList<String>()
                override fun syncedChapterId(id: String) = CHAPTERS + id
                override fun chapterIdFromSync(id: String) = id.removePrefix(CHAPTERS)
            },
        )

        companion object {
            const val WATCH = "https://watch.example/"
            const val CHAPTERS = "https://read.example/chapters/"
        }
    }

    @Before fun sources() = Sources.useForTests(SyncingSources())

    private class FakeGitHub : GitHubCall {
        val gists = mutableMapOf<String, String>()
        var calls = mutableListOf<String>()

        override fun send(method: String, path: String, token: String, body: String?): Pair<Int, String> {
            calls += "$method $path"
            if (token != "good") return 401 to "{}"
            val json = SyncPayload.json
            fun gist(id: String) = """{"id":"$id","files":{"${SyncPayload.FILE_NAME}":{"content":${JsonPrimitive(gists[id]!!)}}}}"""
            return when {
                method == "GET" && path.startsWith("/gists?") -> 200 to gists.keys.joinToString(",", "[", "]") { gist(it) }
                method == "GET" -> path.removePrefix("/gists/").let { if (it in gists) 200 to gist(it) else 404 to "{}" }
                method == "POST" -> {
                    val id = "g${gists.size + 1}"
                    gists[id] = json.parseToJsonElement(body!!).jsonObject["files"]!!.jsonObject[SyncPayload.FILE_NAME]!!.jsonObject["content"]!!.jsonPrimitive.content
                    201 to """{"id":"$id"}"""
                }
                else -> {
                    val id = path.removePrefix("/gists/")
                    gists[id] = json.parseToJsonElement(body!!).jsonObject["files"]!!.jsonObject[SyncPayload.FILE_NAME]!!.jsonObject["content"]!!.jsonPrimitive.content
                    200 to gist(id)
                }
            }
        }
    }

    private fun phone(name: String): Triple<AnimeHistory, MangaHistory, NovelProgressStore> {
        val dir = File(folder.root, name).apply { mkdirs() }
        return Triple(AnimeHistory(File(dir, "a.json")), MangaHistory(File(dir, "m.json")), NovelProgressStore(File(dir, "n.json")))
    }

    @Test
    fun recordsTurnIntoTheDesktopsTermsAndBack() {
        val record = AnimeRecord(
            source = "sync:https://watch.example/black-clover-season-2-1-10492", episodeId = "150381", episodeNumber = 4.0,
            positionMs = 75_516, durationMs = 1_429_887, title = "Black Clover Season 2", updated = 1_794_249_662_647,
        )
        val synced = SyncRules.anime(record)
        assertEquals(SyncedAnime("Black Clover Season 2", "black-clover-season-2-1-10492", 4.0, 75.5, 1429.9, 1_794_249_662.647), synced)
        assertEquals("150381", SyncRules.applyAnime(record, synced.copy(position = 80.0)).episodeId)
        val moved = SyncRules.applyAnime(record, synced.copy(episode = 5.0, position = 12.0, updated = 1_800_000_000.0))
        assertNull(moved.episodeId)
        assertEquals(12_000L, moved.positionMs)
        assertEquals(1_800_000_000_000L, moved.updated)
        assertNull(SyncRules.showOf("other:https://other.example/x"))
        assertEquals("other:https://other.example/x", SyncRules.applyAnime(record.copy(source = "other:https://other.example/x"), synced.copy(show = null)).source)

        val manga = MangaRecord(source = "pages:https://read.example/series/A/K", chapterId = "01M6", chapterName = "Chapter 137", chapterNumber = 137.0, page = 14, pages = 15)
        val sent = SyncRules.manga(manga)
        assertEquals("https://read.example/chapters/01M6", sent.chapterId)
        val back = SyncRules.applyManga(MangaRecord(), SyncedManga(source = manga.source, chapterId = "https://read.example/chapters/01M7", chapter = "Chapter 138", page = 3))
        assertEquals("01M7", back.chapterId)
        assertEquals(138.0, back.chapterNumber!!, 0.0)
        assertEquals(3, back.page)
    }

    @Test
    fun theNewerPlaceWinsAndSourcesAreKept() {
        val local = SyncPayload(
            anime = mapOf("1" to SyncedAnime(show = null, episode = 3.0, updated = 20.0), "2" to SyncedAnime(show = "b", episode = 1.0, updated = 50.0)),
        )
        val remote = SyncPayload(
            anime = mapOf("1" to SyncedAnime(show = "a", episode = 2.0, updated = 10.0), "3" to SyncedAnime(show = "c", episode = 7.0, updated = 5.0)),
            novels = mapOf("9" to JsonObject(mapOf("updated" to JsonPrimitive(3.0)))),
        )
        val merged = SyncRules.merge(local, remote)
        assertEquals(SyncedAnime(show = "a", episode = 3.0, updated = 20.0), merged.anime["1"])
        assertEquals(local.anime["2"], merged.anime["2"])
        assertEquals(remote.anime["3"], merged.anime["3"])
        assertEquals(remote.novels["9"], merged.novels["9"])
    }

    @Test
    fun twoPhonesMeetInTheGist() {
        val github = FakeGitHub()
        val (animeA, mangaA, novelsA) = phone("a")
        val (animeB, mangaB, novelsB) = phone("b")
        animeA.edit(1) { AnimeRecord(source = "sync:https://watch.example/show-1", episodeNumber = 2.0, positionMs = 60_000, updated = 1_000_000) }
        novelsA.save("5", "Book 1.epub", com.aniliphy.novels.BookState(chapter = 3), title = "Five", now = 100.0)

        val first = ProgressSync(animeA, mangaA, novelsA, GistStore(github)).run("good", null) { }
        assertEquals("g1", first.gistId)
        assertTrue(first.sent)

        var books: String? = null
        val second = ProgressSync(animeB, mangaB, novelsB, GistStore(github)).run("good", null) { books = it }
        assertEquals("g1", second.gistId)
        assertEquals(1, second.anime)
        assertEquals(60_000L, animeB.get(1)!!.positionMs)
        assertEquals("sync:https://watch.example/show-1", animeB.get(1)!!.source)
        assertTrue(books!!.contains("Book 1.epub"))

        val third = ProgressSync(animeA, mangaA, novelsA, GistStore(github)).run("good", "g1") { }
        assertEquals(0, third.changed)
        assertTrue(!third.sent)
    }

    @Test
    fun aDeletedGistIsMadeAgain() {
        val github = FakeGitHub()
        val (anime, manga, novels) = phone("a")
        val result = ProgressSync(anime, manga, novels, GistStore(github)).run("good", "gone") { }
        assertEquals("g1", result.gistId)
    }

    @Test(expected = SyncAuthException::class)
    fun aWrongTokenSaysSo() {
        val (anime, manga, novels) = phone("a")
        ProgressSync(anime, manga, novels, GistStore(FakeGitHub())).run("bad", null) { }
    }
}
