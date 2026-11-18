package com.aniliphy.mal

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class MalApiTest {
    private class Store(var tokens: MalTokens?) : MalTokenStore {
        override fun tokens() = tokens
        override fun save(tokens: MalTokens?) { this.tokens = tokens }
    }

    /** Answers like MAL: two list pages, one 401 on the first call, and the token page. */
    private class FakeMal : Interceptor {
        val calls = ArrayList<String>()
        var refused = false
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val url = request.url
            calls += request.method + " " + url.encodedPath + (url.queryParameter("offset")?.let { "@$it" } ?: "") + " " + request.header("Authorization").orEmpty()
            fun reply(code: Int, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
            return when {
                url.encodedPath == "/v1/oauth2/token" -> reply(200, """{"access_token":"new","refresh_token":"r2","expires_in":2678400}""")
                url.encodedPath.endsWith("/animelist") && !refused -> { refused = true; reply(401, "{}") }
                url.encodedPath.endsWith("/animelist") && url.queryParameter("offset") == null -> reply(
                    200,
                    """{"data":[{"node":{"id":1},"list_status":{"status":"watching","score":7,"num_episodes_watched":3,"is_rewatching":false,"num_times_rewatched":0}}],
                       "paging":{"next":"https://api.myanimelist.net/v2/users/@me/animelist?offset=300&limit=300"}}""",
                )
                url.encodedPath.endsWith("/animelist") -> reply(
                    200,
                    """{"data":[{"node":{"id":2},"list_status":{"status":"completed","score":0,"num_episodes_watched":12}}],"paging":{"previous":"x"}}""",
                )
                else -> reply(404, "{}")
            }
        }
    }

    @Test
    fun readsEveryPageOnceAndRenewsOnce() = runBlocking {
        val fake = FakeMal()
        val store = Store(MalTokens("old", "r1", expires_in = 2678400, savedAt = System.currentTimeMillis()))
        val api = MalApi("cid", store, OkHttpClient.Builder().addInterceptor(fake).build())
        val list = api.list(anime = true)
        assertEquals(listOf(1, 2), list.map { it.malId })
        assertEquals(MalEntry(1, true, "watching", 3, null, 7, 0, false), list[0])
        assertEquals(
            listOf(
                "GET /v2/users/@me/animelist Bearer old",
                "POST /v1/oauth2/token ",
                "GET /v2/users/@me/animelist Bearer new",
                "GET /v2/users/@me/animelist@300 Bearer new",
            ),
            fake.calls,
        )
        assertEquals("r2", store.tokens?.refresh_token)
    }
}
