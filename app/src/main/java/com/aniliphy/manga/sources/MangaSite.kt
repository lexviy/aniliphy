package com.aniliphy.manga.sources

import com.aniliphy.core.MissingSourceException
import com.aniliphy.core.Sources
import com.aniliphy.source.SourceChapter
import com.aniliphy.source.SourceSeries
import com.aniliphy.source.MangaSite
import com.aniliphy.core.BotCheck
import com.aniliphy.source.BotCheckException
import com.aniliphy.source.PageFetcher
import com.aniliphy.source.SourceServices
import com.aniliphy.core.SiteCookies
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object SourceHttp : SourceServices {
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply { if (android.os.Build.VERSION.SDK_INT > 0) cookieJar(SiteCookies) }
            .build()
    }

    override fun userAgent(url: String): String = url.toHttpUrlOrNull()?.host?.let(SiteCookies::agentFor) ?: USER_AGENT

    private fun call(url: String, headers: Map<String, String>): Response {
        val request = Request.Builder().url(url).header("User-Agent", userAgent(url)).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val check = BotCheck.isCheck(response)
            response.close()
            if (check) {
                BotCheck.pending = url
                throw BotCheckException(url)
            }
            throw IOException("HTTP ${response.code}")
        }
        return response
    }

    override val fetcher = PageFetcher { url, headers -> call(url, headers).use { it.body.string() } }

    @Throws(IOException::class)
    override fun bytes(url: String, headers: Map<String, String>): Pair<ByteArray, String?> =
        call(url, headers).use { it.body.bytes() to it.header("Content-Type") }
}

object MangaSites {
    /** No source chosen yet: the first one installed is tried first. */
    const val DEFAULT = ""

    fun all(): List<MangaSite> = Sources.manga

    fun find(id: String?): MangaSite? = Sources.find(all(), id)

    @Throws(IOException::class)
    fun site(id: String): MangaSite = Sources.need(all(), id)

    /** The name to show for a source id, even when it isn't installed. */
    fun name(id: String?): String = find(id)?.name ?: id ?: "the site"

    fun sourceId(id: String, seriesUrl: String) = Sources.place(id, seriesUrl)

    fun parse(sourceId: String?): Pair<String, String>? = Sources.parse(sourceId)

    fun order(preferred: String?): List<MangaSite> = Sources.ordered(all(), preferred)
}

/** Stands in for a source whose extension isn't installed, so downloaded chapters still open. */
class MissingMangaSite(override val id: String) : MangaSite {
    override val name: String get() = id
    override val imageHeaders: Map<String, String> = emptyMap()

    override fun search(query: String): List<SourceSeries> = throw MissingSourceException(id)

    override fun chapters(seriesUrl: String): List<SourceChapter> = throw MissingSourceException(id)

    override fun pages(chapter: SourceChapter): List<String> = throw MissingSourceException(id)
}
