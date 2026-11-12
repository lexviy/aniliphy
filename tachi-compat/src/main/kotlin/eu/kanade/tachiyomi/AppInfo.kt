/*
 * Adapted from Mihon (https://github.com/mihonapp/mihon), Apache License 2.0.
 */
package eu.kanade.tachiyomi

/**
 * Used by extensions.
 */
@Suppress("UNUSED")
object AppInfo {
    internal var versionCode = 0
    internal var versionName = ""

    fun getVersionCode(): Int = versionCode

    fun getVersionName(): String = versionName

    fun getSupportedImageMimeTypes(): List<String> =
        listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/heif", "image/jxl")
}
