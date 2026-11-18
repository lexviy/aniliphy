package com.aniliphy.mal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Holds MAL's tokens; [save] null is logging out. */
interface MalTokenStore {
    fun tokens(): MalTokens?
    fun save(tokens: MalTokens?)
}

/** MyAnimeList's API (v2), as far as keeping a list in step needs. */
class MalApi(private val clientId: String, private val store: MalTokenStore, private val http: OkHttpClient = defaultClient) : MalWriter {
    private val json = Json { ignoreUnknownKeys = true }

    /** Trades the login's code for tokens. */
    suspend fun login(code: String, verifier: String) {
        store.save(tokenCall(MalAuth.codeForm(clientId, code, verifier)) ?: throw IOException("MyAnimeList didn't take the login"))
    }

    private suspend fun tokenCall(form: Map<String, String>): MalTokens? = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(MalAuth.TOKEN_URL).post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> json.decodeFromString(MalTokens.serializer(), text).copy(savedAt = System.currentTimeMillis())
                response.code in 400..499 -> null
                else -> throw IOException("MyAnimeList answered ${response.code}")
            }
        }
    }

    /** A token that works now: renewed a day before it runs out. */
    private suspend fun token(force: Boolean = false): String {
        val tokens = store.tokens() ?: throw MalLoggedOutException()
        if (!force && tokens.expiresAt() - System.currentTimeMillis() > RENEW_BEFORE_MS) return tokens.access_token
        val fresh = tokenCall(MalAuth.refreshForm(clientId, tokens.refresh_token))
        if (fresh == null) {
            store.save(null)
            throw MalLoggedOutException()
        }
        store.save(fresh)
        return fresh.access_token
    }

    /** MAL said the token ran out: renew it and ask again (once). */
    private object Renew

    /** Calls MAL with the token, renewing it once if MAL says it ran out. [read] may give null: that's an answer too. */
    private suspend fun <T> call(build: Request.Builder.() -> Unit, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        var renewed = false
        while (true) {
            val request = Request.Builder().apply(build).header("Authorization", "Bearer " + token(force = renewed)).build()
            val result: Any? = http.newCall(request).execute().use { response ->
                if (response.code == 401 && !renewed) Renew else read(response)
            }
            if (result !== Renew) {
                @Suppress("UNCHECKED_CAST")
                return@withContext result as T
            }
            renewed = true
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun check(response: Response, allow404: Boolean = false) {
        when {
            response.isSuccessful -> Unit
            response.code == 404 && allow404 -> Unit
            response.code == 401 -> throw MalLoggedOutException()
            response.code == 429 || response.code >= 500 -> throw IOException("MyAnimeList is busy (${response.code})")
            else -> throw MalRefusedException("MyAnimeList said no (${response.code})")
        }
    }

    /** The user's name. */
    suspend fun me(): String = call({ url("$API/users/@me") }) { response ->
        check(response)
        json.parseToJsonElement(response.body!!.string()).jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "?"
    }

    /** The whole list of one kind, page by page. */
    suspend fun list(anime: Boolean): List<MalEntry> {
        val out = ArrayList<MalEntry>()
        val kind = if (anime) "animelist" else "mangalist"
        val fields = if (anime) "num_episodes,list_status{status,score,num_episodes_watched,is_rewatching,num_times_rewatched}"
        else "num_chapters,list_status{status,score,num_chapters_read,num_volumes_read,is_rereading,num_times_reread}"
        var next: String? = "$API/users/@me/$kind?fields=$fields&limit=300&nsfw=true"
        val asked = HashSet<String>()
        // A page pointing back to one already read, or far too many pages, ends it.
        while (next != null && asked.add(next) && asked.size <= MAX_PAGES) {
            val url = next
            next = call({ url(url) }) { response ->
                check(response)
                val page = json.parseToJsonElement(response.body!!.string()).jsonObject
                for (item in page["data"]?.jsonArray.orEmpty()) {
                    val node = item.jsonObject["node"]?.jsonObject ?: continue
                    val status = item.jsonObject["list_status"]?.jsonObject ?: continue
                    val total = node[if (anime) "num_episodes" else "num_chapters"]?.jsonPrimitive?.intOrNull
                    out += entryOf(node["id"]?.jsonPrimitive?.intOrNull ?: continue, anime, status).copy(total = total)
                }
                page["paging"]?.jsonObject?.get("next")?.jsonPrimitive?.contentOrNull
            }
        }
        return out
    }

    private fun entryOf(id: Int, anime: Boolean, s: JsonObject): MalEntry {
        fun int(name: String) = s[name]?.jsonPrimitive?.intOrNull
        fun bool(name: String) = s[name]?.jsonPrimitive?.booleanOrNull
        return MalEntry(
            malId = id,
            anime = anime,
            status = s["status"]?.jsonPrimitive?.contentOrNull ?: "",
            progress = int(if (anime) "num_episodes_watched" else "num_chapters_read"),
            volumes = if (anime) null else int("num_volumes_read"),
            score = int("score"),
            repeat = int(if (anime) "num_times_rewatched" else "num_times_reread"),
            repeating = bool(if (anime) "is_rewatching" else "is_rereading"),
        )
    }

    override suspend fun update(entry: MalEntry) {
        val body = FormBody.Builder().apply { MalList.form(entry).forEach { (k, v) -> add(k, v) } }.build()
        call({ url(statusUrl(entry.malId, entry.anime)).patch(body) }) { check(it) }
    }

    override suspend fun delete(malId: Int, anime: Boolean) {
        call({ url(statusUrl(malId, anime)).delete() }) { check(it, allow404 = true) }
    }

    private fun statusUrl(malId: Int, anime: Boolean) = "$API/${if (anime) "anime" else "manga"}/$malId/my_list_status"

    companion object {
        private const val API = "https://api.myanimelist.net/v2"
        private const val RENEW_BEFORE_MS = 24L * 60 * 60 * 1000
        private const val MAX_PAGES = 50
        private val defaultClient by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS)
                // Long list pages stalled over HTTP/2 (the stream timed out with nothing read); HTTP/1.1 is fine.
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
        }
    }
}
