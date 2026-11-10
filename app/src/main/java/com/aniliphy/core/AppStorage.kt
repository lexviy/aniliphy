package com.aniliphy.core

import android.content.Context
import android.content.Intent
import java.io.File

data class StorageUsage(val cache: Long, val offlineManga: Long, val appData: Long) {
    val total: Long get() = cache + offlineManga + appData
}

object AppStorage {
    private const val OFFLINE_MANGA_DIR = "offline_manga"
    private const val OFFLINE_ANIME_DIR = "offline_anime"

    private fun offlineMangaDir(context: Context) = File(context.filesDir, OFFLINE_MANGA_DIR)

    private fun cacheDirs(context: Context) = listOfNotNull(context.cacheDir, context.externalCacheDir)

    private fun sharedPrefsDir(context: Context) = File(context.dataDir, "shared_prefs")

    fun usage(context: Context): StorageUsage {
        val offline = offlineMangaDir(context).contentSize() + File(context.filesDir, OFFLINE_ANIME_DIR).contentSize()
        val files = context.filesDir.contentSize() - offline
        val otherData = listOf(sharedPrefsDir(context), File(context.dataDir, "databases"))
            .sumOf { it.contentSize() }
        return StorageUsage(
            cache = cacheDirs(context).sumOf { it.contentSize() },
            offlineManga = offline,
            appData = files + otherData,
        )
    }

    fun clearCache(context: Context) = cacheDirs(context).forEach { it.deleteContents() }

    fun deleteOfflineManga(context: Context) {
        offlineMangaDir(context).deleteContents()
        com.aniliphy.anime.OfflineAnime.get(context).deleteAll()
    }

    fun deleteEverything(context: Context) {
        sharedPrefsDir(context).list()?.forEach { file ->
            context.getSharedPreferences(file.removeSuffix(".xml"), Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        SiteCookies.clear()
        context.filesDir.deleteContents()
        clearCache(context)
        context.databaseList().forEach { context.deleteDatabase(it) }
        context.contentResolver.persistedUriPermissions.forEach { permission ->
            val read = if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
            val write = if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
            context.contentResolver.releasePersistableUriPermission(permission.uri, read or write)
        }
    }

    private fun File.contentSize(): Long = walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun File.deleteContents() {
        listFiles()?.forEach { it.deleteRecursively() }
    }
}
