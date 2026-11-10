package com.aniliphy.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.manga.sources.SourceHttp
import com.aniliphy.source.AnimeSite
import com.aniliphy.source.Extension
import com.aniliphy.source.MangaSite
import com.aniliphy.source.NovelSite
import com.aniliphy.source.Site
import java.io.IOException

/** Every source the app knows, from the extensions it loaded. */
object Sources {
    private class Loaded(
        val anime: List<AnimeSite>,
        val manga: List<MangaSite>,
        val novels: List<NovelSite>,
        val installed: List<InstalledExtension>,
    )

    @Volatile
    private var context: Context? = null

    @Volatile
    private var loaded: Loaded? = null

    /** Grows every time the sources are loaded again, so screens that list them can follow. */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        loaded = null
        PackageChanges.register(context.applicationContext)
    }

    /** Loads the extensions again, after one was installed, updated, removed or trusted. */
    fun reload() {
        synchronized(this) { loaded = null }
        Handler(Looper.getMainLooper()).post { version++ }
    }

    private fun loaded(): Loaded = loaded ?: synchronized(this) { loaded ?: load().also { loaded = it } }

    private fun load(): Loaded = build(context?.let { runCatching { Extensions.find(it) }.getOrNull() }.orEmpty())

    /** For tests: use the sources of [extensions] as if they were installed. */
    fun useForTests(vararg extensions: Extension) {
        synchronized(this) {
            loaded = build(extensions.map { Extensions.Found(InstalledExtension(it.javaClass.name, "", "", 1, null, true, true, null), it) })
        }
    }

    private fun build(found: List<Extensions.Found>): Loaded {
        val parts = found.map { it to it.extension?.let(::partsOf) }
        // When two extensions bring a source with the same id, the first one wins.
        val all = parts.mapNotNull { it.second }
        return Loaded(
            anime = all.flatMap { it.anime }.distinctBy { it.id },
            manga = all.flatMap { it.manga }.distinctBy { it.id },
            novels = all.flatMap { it.novels }.distinctBy { it.id },
            installed = parts.map { (extension, part) -> extension.info.copy(sources = part?.names.orEmpty()) },
        )
    }

    private class Parts(val anime: List<AnimeSite>, val manga: List<MangaSite>, val novels: List<NovelSite>) {
        val names: List<String> get() = (anime + manga + novels).map { it.name }
    }

    private fun partsOf(extension: Extension): Parts {
        fun <T : Site> safely(get: () -> List<T>): List<T> = runCatching(get).getOrDefault(emptyList())
        return Parts(
            anime = safely { extension.anime(SourceHttp) },
            manga = safely { extension.manga(SourceHttp) },
            novels = safely { extension.novels(SourceHttp) },
        )
    }

    /** The extensions on the phone, including the ones that couldn't be loaded. */
    val installed: List<InstalledExtension> get() = loaded().installed

    val anime: List<AnimeSite> get() = loaded().anime

    val manga: List<MangaSite> get() = loaded().manga

    val novels: List<NovelSite> get() = loaded().novels

    /** Where a show or series is: `"<source id>:<address>"`, as saved with progress. */
    fun place(id: String, url: String) = "$id:$url"

    /** The source id and address in a [place], or null when it isn't one. The source may not be installed. */
    fun parse(place: String?): Pair<String, String>? {
        val (id, url) = place?.split(":", limit = 2)?.takeIf { it.size == 2 } ?: return null
        return if (id.isBlank() || url.isBlank()) null else id to url
    }

    /** [preferred] first, then the others in the order they were loaded. */
    fun <T : Site> ordered(sites: List<T>, preferred: String?): List<T> =
        sites.filter { it.id == preferred } + sites.filter { it.id != preferred }

    fun <T : Site> find(sites: List<T>, id: String?): T? = sites.firstOrNull { it.id == id }

    @Throws(IOException::class)
    fun <T : Site> need(sites: List<T>, id: String): T = find(sites, id) ?: throw MissingSourceException(id)
}

/** Loads the extensions again whenever an app is installed, updated or removed while aniliphy runs. */
class PackageChanges : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sources.reload()
    }

    companion object {
        fun register(context: Context) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
            val receiver = PackageChanges()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
    }
}

class MissingSourceException(val id: String) :
    IOException("The source \"$id\" isn't installed. Install its extension, or choose another source")
