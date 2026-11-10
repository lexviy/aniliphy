package com.aniliphy.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class SyncAuthException(message: String) : IOException(message)

class GistMissingException : IOException("The gist is gone")

fun interface GitHubCall {
    @Throws(IOException::class)
    fun send(method: String, path: String, token: String, body: String?): Pair<Int, String>
}

class GistStore(private val call: GitHubCall = okHttpCall) {
    private val json = SyncPayload.json

    private fun send(method: String, path: String, token: String, body: String? = null): String {
        val (code, text) = call.send(method, path, token, body)
        when {
            code == 401 -> throw SyncAuthException("GitHub didn't accept the token")
            code == 403 -> throw SyncAuthException("The token can't use gists. It needs the gist permission")
            code == 404 && path.startsWith("/gists/") -> throw GistMissingException()
            code !in 200..299 -> throw IOException("GitHub answered $code")
        }
        return text
    }

    fun find(token: String): String? {
        for (page in 1..10) {
            val list = json.parseToJsonElement(send("GET", "/gists?per_page=100&page=$page", token)) as? JsonArray ?: return null
            list.forEach { item ->
                val gist = item as? JsonObject ?: return@forEach
                val files = gist["files"] as? JsonObject ?: return@forEach
                if (SyncPayload.FILE_NAME in files) return (gist["id"] as? JsonPrimitive)?.contentOrNull
            }
            if (list.size < 100) return null
        }
        return null
    }

    fun read(token: String, gistId: String): SyncPayload {
        val gist = json.parseToJsonElement(send("GET", "/gists/$gistId", token)).jsonObject
        val file = (gist["files"] as? JsonObject)?.get(SyncPayload.FILE_NAME) as? JsonObject ?: return SyncPayload()
        val truncated = (file["truncated"] as? JsonPrimitive)?.contentOrNull == "true"
        val text = if (truncated) {
            val raw = (file["raw_url"] as? JsonPrimitive)?.contentOrNull ?: return SyncPayload()
            send("GET", raw, token)
        } else {
            (file["content"] as? JsonPrimitive)?.contentOrNull ?: return SyncPayload()
        }
        return SyncPayload.parse(text) ?: throw IOException("The gist's progress file comes from a newer version of the app")
    }

    private fun body(payload: SyncPayload) = buildJsonObject {
        put("description", DESCRIPTION)
        putJsonObject("files") {
            putJsonObject(SyncPayload.FILE_NAME) { put("content", json.encodeToString(SyncPayload.serializer(), payload)) }
        }
    }

    fun write(token: String, gistId: String, payload: SyncPayload) {
        send("PATCH", "/gists/$gistId", token, body(payload).toString())
    }

    fun create(token: String, payload: SyncPayload): String {
        val created = buildJsonObject {
            body(payload).forEach { (key, value) -> put(key, value) }
            put("public", false)
        }
        val answer = json.parseToJsonElement(send("POST", "/gists", token, created.toString())).jsonObject
        return (answer["id"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("GitHub didn't say which gist it made")
    }

    companion object {
        const val DESCRIPTION = "anilixy and aniliphy progress"
        private const val API = "https://api.github.com"
        private val JSON = "application/json".toMediaType()

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        }

        val okHttpCall = GitHubCall { method, path, token, body ->
            val url = if (path.startsWith("http")) path else API + path
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "aniliphy")
                .method(method, body?.toRequestBody(JSON) ?: if (method == "GET") null else ByteArray(0).toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { it.code to it.body.string() }
        }
    }
}
