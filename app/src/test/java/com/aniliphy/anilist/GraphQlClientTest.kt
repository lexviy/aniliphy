package com.aniliphy.anilist

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GraphQlClientTest {
    @get:Rule val folder = TemporaryFolder()

    private var clock = 1_000_000_000L
    private val cache by lazy { ApiCache(folder.root) { clock } }
    private val noVars = JsonObject(emptyMap())
    private val ttl = 1000L

    private fun client(answer: (FakeTransport.Request) -> HttpReply): Pair<GraphQlClient, FakeTransport> {
        val transport = FakeTransport(answer)
        return GraphQlClient(transport, cache) to transport
    }

    private fun JsonObject.n() = getValue("n").jsonPrimitive.int

    @Test fun answerIsCachedUntilItExpires() = runTest {
        var n = 0
        val (client, transport) = client { ok("""{"n":${++n}}""") }

        assertEquals(1, client.read("q", noVars, ttlMillis = ttl).value.n())
        clock += ttl - 1
        assertEquals(1, client.read("q", noVars, ttlMillis = ttl).value.n())
        assertEquals(1, transport.requests.size)

        clock += 1
        assertEquals(2, client.read("q", noVars, ttlMillis = ttl).value.n())
        assertEquals(2, transport.requests.size)
    }

    @Test fun refreshSkipsAFreshCopy() = runTest {
        var n = 0
        val (client) = client { ok("""{"n":${++n}}""") }
        client.read("q", noVars, ttlMillis = ttl)
        assertEquals(2, client.read("q", noVars, ttlMillis = ttl, refresh = true).value.n())
    }

    @Test fun withoutATtlNothingIsCached() = runTest {
        var n = 0
        val (client, transport) = client { ok("""{"n":${++n}}""") }
        client.read("q", noVars)
        assertEquals(2, client.read("q", noVars).value.n())
        assertEquals(2, transport.requests.size)
    }

    @Test fun accountsDoNotShareAnswers() = runTest {
        val (client, transport) = client { request -> ok("""{"n":${request.token!!.length}}""") }
        assertEquals(1, client.read("q", noVars, "a", ttl).value.n())
        assertEquals(2, client.read("q", noVars, "bb", ttl).value.n())
        assertEquals(2, transport.requests.size)
    }

    @Test fun expiredCopyIsUsedWhenOfflineAndMarkedStale() = runTest {
        var online = true
        val (client) = client { if (online) ok("""{"n":1}""") else throw IOException("down") }
        client.read("q", noVars, ttlMillis = ttl)
        online = false
        clock += ttl * 100

        val fetched = client.read("q", noVars, ttlMillis = ttl)
        assertTrue(fetched.stale)
        assertEquals(1, fetched.value.n())
    }

    @Test fun offlineWithNothingCachedFails() = runTest {
        val (client) = client { throw IOException("down") }
        val failure = runCatching { client.read("q", noVars, ttlMillis = ttl) }.exceptionOrNull()
        assertTrue(failure is OfflineException)
    }

    @Test fun serverOutageFallsBackButABadRequestDoesNot() = runTest {
        var reply = ok("""{"n":1}""")
        val (client) = client { reply }
        client.read("q", noVars, ttlMillis = ttl)
        clock += ttl * 100

        reply = HttpReply(500, "oops")
        assertTrue(client.read("q", noVars, ttlMillis = ttl).stale)

        reply = HttpReply(400, """{"errors":[{"message":"Bad query"}]}""")
        val failure = runCatching { client.read("q", noVars, ttlMillis = ttl) }.exceptionOrNull()
        assertEquals("Bad query", failure?.message)
    }

    @Test fun invalidTokenIsItsOwnFailure() = runTest {
        for (reply in listOf(
            HttpReply(400, """{"errors":[{"message":"Invalid token"}],"data":null}"""),
            HttpReply(401, ""),
        )) {
            val (client) = client { reply }
            val failure = runCatching { client.read("q", noVars, "t") }.exceptionOrNull()
            assertTrue(failure is InvalidTokenException)
        }
    }

    @Test fun notFoundIsItsOwnFailure() = runTest {
        val (client) = client { HttpReply(404, """{"errors":[{"message":"Not Found."}],"data":{"Media":null}}""") }
        assertTrue(runCatching { client.read("q", noVars) }.exceptionOrNull() is NotFoundException)
    }

    @Test fun nonJsonAnswerIsAFailureNotACrash() = runTest {
        val (client) = client { HttpReply(200, "<html>Cloudflare</html>") }
        assertTrue(runCatching { client.read("q", noVars) }.exceptionOrNull() is ServerException)
    }

    @Test fun partlyFailedAnswerIsShownButNotKept() = runTest {
        val (client, transport) = client {
            HttpReply(200, """{"data":{"n":1},"errors":[{"message":"Partial"}]}""")
        }
        client.read("q", noVars, ttlMillis = ttl)
        client.read("q", noVars, ttlMillis = ttl)
        assertEquals(2, transport.requests.size)
    }

    @Test fun rateLimitWaitsAsToldAndTriesAgain() = runTest {
        var calls = 0
        val (client) = client {
            if (++calls == 1) HttpReply(429, "", retryAfterSeconds = 7) else ok("""{"n":2}""")
        }
        val started = testScheduler.currentTime
        assertEquals(2, client.read("q", noVars).value.n())
        assertEquals(7000, testScheduler.currentTime - started)
    }

    @Test fun rateLimitTwiceGivesUp() = runTest {
        val (client, transport) = client { HttpReply(429, "", retryAfterSeconds = 1) }
        assertTrue(runCatching { client.read("q", noVars) }.exceptionOrNull() is RateLimitedException)
        assertEquals(2, transport.requests.size)
    }

    @Test fun mutationClearsTheAccountsCacheButNotPublicAnswers() = runTest {
        var n = 0
        val (client, transport) = client { request ->
            if (request.query == "m") ok("{}") else ok("""{"n":${++n}}""")
        }
        client.read("user", noVars, "t", ttl)
        client.read("public", noVars, ttlMillis = ttl)
        client.mutate("m", noVars, "t")
        val before = transport.requests.size

        client.read("public", noVars, ttlMillis = ttl)
        assertEquals(before, transport.requests.size)
        client.read("user", noVars, "t", ttl)
        assertEquals(before + 1, transport.requests.size)
    }

    @Test fun failedMutationStillClearsAndReportsTheError() = runTest {
        var n = 0
        val (client, transport) = client { request ->
            if (request.query == "m") {
                HttpReply(200, """{"data":null,"errors":[{"message":"Nope"}]}""")
            } else {
                ok("""{"n":${++n}}""")
            }
        }
        client.read("user", noVars, "t", ttl)
        val failure = runCatching { client.mutate("m", noVars, "t") }.exceptionOrNull()
        assertEquals("Nope", failure?.message)
        client.read("user", noVars, "t", ttl)
        assertEquals(3, transport.requests.size)
    }

    @Test fun mutationWithPartialErrorsIsAFailure() = runTest {
        val (client) = client { HttpReply(200, """{"data":{"x":null},"errors":[{"message":"Nope"}]}""") }
        assertThrows(ServerException::class.java) {
            kotlinx.coroutines.runBlocking { client.mutate("m", noVars, "t") }
        }
    }

    @Test fun answerThatStartedBeforeAChangeIsNotKept() = runTest {
        var n = 0
        val (client, transport) = client { request ->
            if (request.query == "user" && n++ == 0) cache.invalidateUser()
            ok("""{"n":$n}""")
        }
        client.read("user", noVars, "t", ttl)
        client.read("user", noVars, "t", ttl)
        assertEquals(2, transport.requests.size)
    }

    @Test fun damagedCacheFileCountsAsMissing() = runTest {
        var n = 0
        val (client) = client { ok("""{"n":${++n}}""") }
        client.read("q", noVars, ttlMillis = ttl)
        val file = cache.entry("q", noVars.toString(), null)
        file.writeText("{not json")
        file.setLastModified(clock)
        val fetched = client.read("q", noVars, ttlMillis = ttl)
        assertEquals(2, fetched.value.n())
        assertFalse(fetched.stale)
    }

    @Test fun pruneRemovesOnlyVeryOldAnswers() {
        val old = cache.entry("old", "", null)
        cache.write(old, cache.currentGeneration(), "{}")
        clock += 5000
        val recent = cache.entry("recent", "", null)
        cache.write(recent, cache.currentGeneration(), "{}")
        cache.prune(maxAgeMillis = 5000)
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    /** A transport whose answers take [millis] of (virtual) time, so requests overlap. */
    private class SlowTransport(private val millis: Long, private val answer: (FakeTransport.Request) -> HttpReply) : GraphQlTransport {
        val inner = FakeTransport(answer)
        override suspend fun post(body: String, token: String?): HttpReply {
            kotlinx.coroutines.delay(millis)
            return inner.post(body, token)
        }
    }

    @Test fun sameQuestionAskedTogetherGoesOnce() = runTest {
        var n = 0
        val transport = SlowTransport(100) { ok("""{"n":${++n}}""") }
        val client = GraphQlClient(transport, cache)
        val answers = List(4) { async { client.read("user", noVars, "t", ttl).value.n() } }.awaitAll()
        assertEquals(listOf(1, 1, 1, 1), answers)
        assertEquals(1, transport.inner.requests.size)
    }

    @Test fun questionAskedAfterAChangeDoesNotShareAnOlderAnswer() = runTest {
        var n = 0
        val transport = SlowTransport(100) { request -> if (request.query == "m") ok("{}") else ok("""{"n":${++n}}""") }
        val client = GraphQlClient(transport, cache)
        val before = async { client.read("user", noVars, "t", ttl) }
        testScheduler.advanceTimeBy(10)
        client.mutate("m", noVars, "t")
        val after = client.read("user", noVars, "t", ttl)
        before.await()
        assertEquals(2, transport.inner.requests.count { it.query == "user" })
        assertEquals(2, after.value.n())
    }

    @Test fun oneRateLimitPausesTheOtherRequests() = runTest {
        var limited = true
        val (client, transport) = client { request ->
            if (request.query == "a" && limited) {
                limited = false
                HttpReply(429, "", retryAfterSeconds = 5)
            } else {
                ok("""{"n":1}""")
            }
        }
        val a = async { client.read("a", noVars) }
        testScheduler.advanceTimeBy(1000)
        val started = testScheduler.currentTime
        client.read("b", noVars)
        // "b" waited for the pause "a" was told about instead of going out straight away.
        assertTrue(testScheduler.currentTime - started >= 3000)
        a.await()
        assertEquals(3, transport.requests.size)
    }

    @Test fun afterAChangeTheOldCopyStillHelpsWhenAniListCantBeReached() = runTest {
        var offline = false
        val (client) = client { request ->
            when {
                request.query == "m" -> ok("{}")
                offline -> throw IOException("down")
                else -> ok("""{"n":1}""")
            }
        }
        client.read("user", noVars, "t", ttl)
        client.mutate("m", noVars, "t")
        offline = true
        val fetched = client.read("user", noVars, "t", ttl)
        assertEquals(1, fetched.value.n())
        assertTrue(fetched.stale)
    }
}
