package com.aniliphy.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import java.io.File

object SiteCookies : CookieJar {
    @Serializable
    private data class Saved(val cookies: List<SavedCookie> = emptyList(), val agents: Map<String, String> = emptyMap())

    @Serializable
    private data class SavedCookie(val url: String, val header: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val cookies = mutableListOf<Cookie>()
    private val agents = mutableMapOf<String, String>()
    private var file: File? = null

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val saved = File(context.filesDir, "site_cookies.json")
        file = saved
        val data = saved.readTextOrNull()?.let { runCatching { json.decodeFromString<Saved>(it) }.getOrNull() } ?: return
        val now = System.currentTimeMillis()
        data.cookies.forEach { entry ->
            val url = entry.url.toHttpUrlOrNull() ?: return@forEach
            Cookie.parse(url, entry.header)?.takeIf { it.expiresAt > now }?.let(cookies::add)
        }
        agents.putAll(data.agents)
    }

    @Synchronized
    fun agentFor(host: String): String? = agents[host]

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        put(cookies)
        save()
    }

    @Synchronized
    fun fromBrowser(address: String, header: String, agent: String) {
        val url = address.toHttpUrlOrNull() ?: return
        val week = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000
        val given = header.split(';').mapNotNull { part ->
            val (name, value) = part.trim().split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            Cookie.Builder().name(name).value(value).domain(url.host).path("/").expiresAt(week).build()
        }
        put(given)
        agents[url.host] = agent
        save()
    }

    private fun put(given: List<Cookie>) {
        given.forEach { new ->
            cookies.removeAll { it.name == new.name && it.domain == new.domain && it.path == new.path }
            cookies.add(new)
        }
    }

    private fun save() {
        val target = file ?: return
        val now = System.currentTimeMillis()
        val kept = cookies.filter { it.persistent && it.expiresAt > now }.map {
            SavedCookie("https://${it.domain}${it.path}", it.toString())
        }
        runCatching { target.writeTextAtomically(json.encodeToString(Saved.serializer(), Saved(kept, agents.toMap()))) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        agents.clear()
        file?.delete()
    }
}

object BotCheck {
    var pending by mutableStateOf<String?>(null)

    fun isCheck(response: Response): Boolean {
        if (response.code != 403 && response.code != 503 && response.code != 429) return false
        if (response.header("cf-mitigated") == "challenge") return true
        val start = runCatching { response.peekBody(64 * 1024).string() }.getOrDefault("")
        return "challenge-platform" in start || "<title>Just a moment" in start || "cf-turnstile" in start
    }
}
