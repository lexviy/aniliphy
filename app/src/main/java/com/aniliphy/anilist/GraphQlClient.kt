package com.aniliphy.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed class AniListException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    open val temporary: Boolean get() = false
}

class OfflineException(cause: Throwable) : AniListException("No connection to AniList", cause) {
    override val temporary get() = true
}

class InvalidTokenException : AniListException("Invalid token")

class NotLoggedInException : AniListException("Not logged in")

class NotFoundException : AniListException("Not found on AniList")

class RateLimitedException : AniListException("Too many requests to AniList") {
    override val temporary get() = true
}

class ServerException(val status: Int, message: String) : AniListException(message) {
    override val temporary get() = status >= 500
}

data class Fetched<T>(val value: T, val stale: Boolean = false) {
    fun <R> map(transform: (T) -> R) = Fetched(transform(value), stale)
}

data class HttpReply(val status: Int, val body: String, val retryAfterSeconds: Long? = null)

fun interface GraphQlTransport {
    suspend fun post(body: String, token: String?): HttpReply
}

class OkHttpTransport(
    private val url: String = URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : GraphQlTransport {
    override suspend fun post(body: String, token: String?): HttpReply {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "aniliphy")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        return runInterruptible(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                HttpReply(
                    status = response.code,
                    body = response.body.string(),
                    retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
                )
            }
        }
    }

    private companion object {
        const val URL = "https://graphql.anilist.co"
        val JSON_TYPE = "application/json".toMediaType()
    }
}

class GraphQlClient(
    private val transport: GraphQlTransport,
    private val cache: ApiCache,
    maxConcurrent: Int = 4,
) {
    private val permits = Semaphore(maxConcurrent)

    /** Reads on their way, by what they ask: the same question asked again meanwhile waits for this answer. */
    private val inFlight = HashMap<String, CompletableDeferred<Fetched<JsonObject>>>()

    /** While AniList says to slow down, every request waits for this, instead of each finding out with its own 429. */
    @Volatile private var pause: CompletableDeferred<Unit>? = null

    suspend fun read(
        query: String,
        variables: JsonObject,
        token: String? = null,
        ttlMillis: Long? = null,
        refresh: Boolean = false,
    ): Fetched<JsonObject> {
        val entry = ttlMillis?.let { cache.entry(query, variables.toString(), token) }
        if (entry != null && !refresh) {
            val fresh = onDisk { cache.readFresh(entry, ttlMillis) }?.let(::parseCached)
            if (fresh != null) return Fetched(fresh)
        }
        // With the cache's generation in the key, nothing asked after a change gets an answer from before it.
        val key = "${cache.currentGeneration()}\u0000$query\u0000$variables\u0000${token.orEmpty()}"
        return shared(key) { fetch(query, variables, token, entry) }
    }

    /**
     * Runs [fetch] once for everyone asking [key] at the same time: after a change several screens ask for the same
     * list together, and only one request needs to go.
     */
    private suspend fun shared(key: String, fetch: suspend () -> Fetched<JsonObject>): Fetched<JsonObject> {
        while (true) {
            val mine = CompletableDeferred<Fetched<JsonObject>>()
            val running = synchronized(inFlight) { inFlight[key] ?: null.also { inFlight[key] = mine } }
            if (running != null) {
                try {
                    return running.await()
                } catch (e: CancellationException) {
                    // The one asking first went away; ask again (unless this one was cancelled too).
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    continue
                }
            }
            try {
                return fetch().also { mine.complete(it) }
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                synchronized(inFlight) { if (inFlight[key] === mine) inFlight.remove(key) }
            }
        }
    }

    private suspend fun fetch(query: String, variables: JsonObject, token: String?, entry: java.io.File?): Fetched<JsonObject> {
        val startedAt = cache.currentGeneration()
        val answer = try {
            send(query, variables, token)
        } catch (e: AniListException) {
            val stale = if (entry != null && e.temporary) {
                onDisk { cache.readAny(entry) }?.let(::parseCached)
            } else {
                null
            }
            return Fetched(stale ?: throw e, stale = true)
        }
        if (entry != null && answer.errors == null) {
            onDisk { cache.write(entry, startedAt, answer.data.toString()) }
        }
        return Fetched(answer.data)
    }

    suspend fun mutate(query: String, variables: JsonObject, token: String): JsonObject {
        onDisk { cache.invalidateUser() }
        try {
            val answer = send(query, variables, token)
            answer.errors?.let { throw ServerException(200, it) }
            return answer.data
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { cache.invalidateUser() }
        }
    }

    private suspend fun send(query: String, variables: JsonObject, token: String?): Answer {
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()
        pause?.await()
        val first = permits.withPermit { post(body, token) }
        if (first.status != RATE_LIMITED) return parse(first)
        waitOut((first.retryAfterSeconds ?: DEFAULT_WAIT).coerceIn(1, MAX_WAIT) * 1000)
        return parse(permits.withPermit { post(body, token) })
    }

    /**
     * Waits as long as AniList said, without holding a permit. The first to be told starts the pause and the others
     * join it, so requests stop going out until it's over rather than each spending more of the limit.
     */
    private suspend fun waitOut(millis: Long) {
        val mine = CompletableDeferred<Unit>()
        val current = synchronized(inFlight) { pause?.takeIf { !it.isCompleted } ?: mine.also { pause = it } }
        if (current !== mine) {
            current.await()
            return
        }
        try {
            delay(millis)
        } finally {
            mine.complete(Unit)
        }
    }

    private suspend fun post(body: String, token: String?) = try {
        transport.post(body, token)
    } catch (e: IOException) {
        throw OfflineException(e)
    }

    private fun parse(reply: HttpReply): Answer {
        val json = try {
            Json.parseToJsonElement(reply.body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val message = (json?.get("errors") as? JsonArray)?.joinToString(" ") { error ->
            (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
        }?.trim()
        when {
            reply.status == 401 || message.orEmpty().contains("invalid token", ignoreCase = true) ->
                throw InvalidTokenException()
            reply.status == RATE_LIMITED -> throw RateLimitedException()
            reply.status == 404 -> throw NotFoundException()
            reply.status !in 200..299 ->
                throw ServerException(reply.status, message.orEmpty().ifEmpty { "AniList error ${reply.status}" })
        }
        val data = json?.get("data")
        if (data == null || data is JsonNull) {
            throw ServerException(reply.status, message.orEmpty().ifEmpty { "Invalid answer from AniList" })
        }
        return Answer(data.jsonObject, message?.ifEmpty { "AniList error" })
    }

    private fun parseCached(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private suspend fun <T> onDisk(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private class Answer(val data: JsonObject, val errors: String?)

    private companion object {
        const val RATE_LIMITED = 429
        const val DEFAULT_WAIT = 10L
        const val MAX_WAIT = 60L
    }
}
