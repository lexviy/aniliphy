/*
 * Adapted from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0.
 * aniliphy: the cookies live in the app's own jar, the one its robot checks fill, instead of the WebView's.
 */
package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar internal constructor(private val base: CookieJar, private val forget: (HttpUrl, List<String>?) -> Int) : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = base.saveFromResponse(url, cookies)

    override fun loadForRequest(url: HttpUrl): List<Cookie> = get(url)

    fun get(url: HttpUrl): List<Cookie> = base.loadForRequest(url)

    @Suppress("UNUSED_PARAMETER")
    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int = forget(url, cookieNames)

    fun removeAll() {
        forget(HttpUrl.Builder().scheme("https").host("localhost").build(), null)
    }
}
