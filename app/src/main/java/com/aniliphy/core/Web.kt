package com.aniliphy.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An answer from a public web service: its status and body. */
class WebReply(val code: Int, val body: ByteArray, val contentType: String? = null) {
    val text: String get() = body.decodeToString()
    val ok: Boolean get() = code in 200..299

    /** True for JSON. A service behind a bot check answers HTML even with a 200, and that isn't an answer. */
    val looksLikeJson: Boolean get() = contentType?.contains("json") == true || text.trimStart().let { it.startsWith("{") || it.startsWith("[") }
}

/** How the app reaches the public services it uses (AniSkip, trace.moe, AnimeThemes, GitHub...). Tests pass their own. */
interface WebCall {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String> = emptyMap()): WebReply

    @Throws(IOException::class)
    fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String> = emptyMap()): WebReply
}

/** The real one, over OkHttp, with the app's own name as the user agent (these services ask clients to say who they are). */
object Web : WebCall {
    @Volatile
    var agent: String = "aniliphy (Android)"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    override fun get(url: String, headers: Map<String, String>): WebReply = send(
        Request.Builder().url(url).get(),
        headers,
    )

    override fun post(url: String, body: ByteArray, contentType: String, headers: Map<String, String>): WebReply = send(
        Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType())),
        headers,
    )

    /** Saves [url] to [into] bit by bit, telling [progress] how much has come so far (and the total, when known). */
    @Throws(IOException::class)
    fun download(url: String, into: java.io.File, progress: (done: Long, total: Long) -> Unit) {
        val request = Request.Builder().url(url).header("User-Agent", agent).build()
        val part = java.io.File(into.parentFile, into.name + ".part")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("The download answered ${response.code}")
            val total = response.body.contentLength()
            var done = 0L
            part.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        done += read
                        progress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("The download stopped halfway")
        }
        into.delete()
        if (!part.renameTo(into)) throw IOException("Couldn't save the download")
    }

    private fun send(builder: Request.Builder, headers: Map<String, String>): WebReply {
        builder.header("User-Agent", agent)
        headers.forEach { (name, value) -> builder.header(name, value) }
        try {
            client.newCall(builder.build()).execute().use { response ->
                return WebReply(response.code, response.body.bytes(), response.header("Content-Type"))
            }
        } catch (e: IllegalArgumentException) {
            throw IOException("Bad address", e)
        }
    }
}
