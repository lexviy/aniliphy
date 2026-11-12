/*
 * Adapted from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0.
 * aniliphy: built on the app's own HTTP client, so robot checks, cookies and the user agent are shared with it.
 */
package eu.kanade.tachiyomi.network

import okhttp3.OkHttpClient

class NetworkHelper internal constructor(
    val cookieJar: AndroidCookieJar,
    val client: OkHttpClient,
    private val userAgent: () -> String,
) {
    /**
     * @deprecated Since extension-lib 1.5
     */
    @Deprecated("The regular client handles Cloudflare by default")
    @Suppress("UNUSED")
    val cloudflareClient: OkHttpClient get() = client

    fun defaultUserAgentProvider(): String = userAgent()
}
