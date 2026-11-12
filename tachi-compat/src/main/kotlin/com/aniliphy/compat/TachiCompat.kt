package com.aniliphy.compat

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.network.interceptor.UncaughtExceptionInterceptor
import eu.kanade.tachiyomi.network.interceptor.UserAgentInterceptor
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory

/**
 * What Mihon and Aniyomi extensions expect to find in the app they run in. [init] must run before any of their
 * classes is created, since they look these up as they are built.
 */
object TachiCompat {
    @Volatile
    private var ready = false

    @Synchronized
    fun init(
        app: Application,
        versionCode: Int,
        versionName: String,
        client: OkHttpClient,
        cookies: CookieJar,
        forgetCookies: (HttpUrl, List<String>?) -> Int,
        userAgent: () -> String,
        /** Proceeds with a request and handles the site's robot check, the app's way. */
        siteCheck: (Interceptor.Chain) -> Response,
    ) {
        if (ready) return
        AppInfo.versionCode = versionCode
        AppInfo.versionName = versionName
        val jar = AndroidCookieJar(cookies, forgetCookies)
        // As in Mihon: some extensions look for these three in the client, by name, and put their own interceptors around them.
        val withCompat = client.newBuilder().cookieJar(jar).apply {
            interceptors().add(0, UncaughtExceptionInterceptor())
            interceptors().add(1, UserAgentInterceptor(userAgent))
            addInterceptor(CloudflareInterceptor(siteCheck))
        }.build()
        val network = NetworkHelper(jar, withCompat, userAgent)
        Injekt.addSingleton(app)
        Injekt.addSingletonFactory<Context> { app }
        Injekt.addSingletonFactory { network }
        Injekt.addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        ready = true
    }
}
