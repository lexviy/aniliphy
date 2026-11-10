package com.aniliphy.source

import java.io.IOException
import java.net.URI

/** Fetches a page as text. Throws [BotCheckException] when the site asks for a robot check. */
fun interface PageFetcher {
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): String
}

/**
 * What the app gives extensions: its HTTP client, with the cookies and user agent of passed robot checks.
 * Extensions should make every request through here, so robot checks and cookies keep working.
 */
interface SourceServices {
    val fetcher: PageFetcher

    @Throws(IOException::class)
    fun bytes(url: String, headers: Map<String, String>): Pair<ByteArray, String?>

    fun userAgent(url: String): String
}

/** The site asks to prove you're not a robot; the app shows the check in a browser. */
class BotCheckException(val url: String) :
    IOException("${hostOf(url) ?: "The site"} wants to check you're not a robot. Pass the check, then try again")

/** The answer didn't have the expected shape, most likely because the site changed. */
class SiteChangedException(what: String, cause: Throwable? = null) :
    IOException("$what. The site may have changed, so aniliphy may need an update", cause)

private fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()?.ifEmpty { null }
