package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response

/**
 * aniliphy: stands where Mihon's Cloudflare solver is, under the same name, since some extensions look for it by name
 * to put their interceptors around it. The robot check itself is the app's: [check] proceeds and turns a challenge
 * into its own exception, which the app answers by showing the check in a browser.
 */
class CloudflareInterceptor internal constructor(private val check: (Interceptor.Chain) -> Response) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = check(chain)
}
