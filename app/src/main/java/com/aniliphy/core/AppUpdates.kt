package com.aniliphy.core

import android.content.Context
import android.os.Build
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** A version of the app on GitHub's releases. */
@Serializable
data class AppRelease(
    val version: String,
    val notes: String = "",
    val apkUrl: String? = null,
    val apkBytes: Long = 0,
    val pageUrl: String = "",
    val publishedAt: String? = null,
)

/**
 * Looks for a newer version on the project's GitHub releases: at most once a day, without an account (GitHub allows
 * 60 such requests an hour per address), and only says so once per version unless asked.
 */
class AppUpdates(private val web: WebCall = Web) {
    @Serializable
    private data class Asset(val name: String = "", val browser_download_url: String = "", val size: Long = 0)

    @Serializable
    private data class Release(
        val tag_name: String = "",
        val body: String? = null,
        val html_url: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val published_at: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Throws(IOException::class)
    fun latest(): AppRelease? {
        val reply = web.get(LATEST_URL, mapOf("Accept" to "application/vnd.github+json"))
        if (reply.code == 404) return null
        if (!reply.ok || !reply.looksLikeJson) throw IOException("GitHub answered ${reply.code}")
        return parse(reply.text)
    }

    companion object {
        const val REPO = "lexviy/aniliphy"
        const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(text: String): AppRelease? {
            val release = runCatching { JSON.decodeFromString<Release>(text) }.getOrNull() ?: return null
            if (release.draft || release.prerelease || release.tag_name.isBlank()) return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            return AppRelease(
                version = release.tag_name.trim().removePrefix("v").removePrefix("V"),
                notes = release.body.orEmpty().replace("\r\n", "\n").trim(),
                apkUrl = apk?.browser_download_url,
                apkBytes = apk?.size ?: 0,
                pageUrl = release.html_url.ifBlank { RELEASES_PAGE },
                publishedAt = release.published_at,
            )
        }

        /** The numbers of a version like "0.6.10", so 0.6.10 counts as newer than 0.6.9. Anything after a "-" is left out. */
        private fun parts(version: String): List<Int> =
            version.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull() ?: 0 }

        fun isNewer(candidate: String, installed: String): Boolean {
            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        fun dueForCheck(lastCheck: Long, now: Long): Boolean = now - lastCheck >= DAY_MS || now < lastCheck

        /** The version installed, as Android knows it. */
        fun installedVersion(context: Context): String = try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
    }
}
