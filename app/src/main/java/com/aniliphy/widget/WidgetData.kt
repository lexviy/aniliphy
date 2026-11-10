package com.aniliphy.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.SessionState
import com.aniliphy.anime.AnimeHistory
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.manga.MangaHistory
import com.aniliphy.manga.sources.SourceHttp
import com.aniliphy.novels.NovelFiles
import com.aniliphy.novels.NovelProgressStore
import com.aniliphy.novels.NovelSeries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Keeps `widgets.json` and the reduced covers up to date. The widgets only ever read what is written here.
 *
 * It is refreshed when the app goes to the background, after a sync, when the light novel library loads and on
 * every run of the background check.
 */
object WidgetData {
    const val FILE = "widgets.json"
    const val IMAGES = "widget_covers"

    /** Wide enough to stay sharp in a column of the widget, small enough for many to fit in its memory. */
    private const val IMAGE_WIDTH = 240

    /** The corner radius, as a share of the width. */
    private const val CORNER = 0.07f
    private const val TAG = "WidgetData"
    private const val RECOMMEND_EVERY_MS = 24 * 60 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun file(context: Context) = File(context.filesDir, FILE)

    fun images(context: Context) = File(context.filesDir, IMAGES)

    fun read(context: Context): WidgetSnapshot =
        runCatching { file(context).readTextOrNull()?.let { json.decodeFromString<WidgetSnapshot>(it) } }.getOrNull()
            ?: WidgetSnapshot()

    fun image(context: Context, item: WidgetItem): File? =
        item.image?.let { File(images(context), it) }?.takeIf { it.isFile }

    /** Refreshes in the background, for callers on the main thread. */
    fun refreshSoon(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    /** Remembers the light novel library's titles and covers, which only the app can work out, then refreshes. */
    fun libraryLoaded(context: Context, series: List<NovelSeries>, files: NovelFiles, progress: NovelProgressStore) {
        val app = context.applicationContext
        scope.launch {
            val recent = series.mapNotNull { novel -> progress.lastRead(novel.key)?.let { novel to it } }
                .sortedByDescending { it.second.updated }
                .take(WidgetLists.LIMIT)
                .map { it.first }
            val covers = recent.mapNotNull { novel ->
                val local = novel.volumes.firstOrNull()?.let { runCatching { files.cover(it) }.getOrNull() }?.absolutePath
                (local ?: novel.media?.coverImage?.best)?.let { novel.key to it }
            }.toMap()
            refresh(app, novelTitles = recent.associate { it.key to it.title }, novelCovers = covers, library = series.map { it.key })
        }
    }

    suspend fun refresh(
        context: Context,
        novelTitles: Map<String, String> = emptyMap(),
        novelCovers: Map<String, String> = emptyMap(),
        library: List<String>? = null,
    ) = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val old = read(context)
                val dir = context.filesDir
                val covers = old.novelCovers + novelCovers
                val titles = old.novels.associate { it.key to it.title } + novelTitles
                val progress = NovelProgressStore(File(dir, "novel_progress.json"))
                val books = library ?: old.library
                val places = progress.raw().keys.mapNotNull { key -> progress.lastRead(key)?.let { NovelPlace(key, it) } }
                val listed = WidgetSnapshot(
                    anime = WidgetLists.anime(AnimeHistory(File(dir, "anime_history.json")).all()),
                    manga = WidgetLists.manga(MangaHistory(File(dir, "manga_history.json")).all()),
                    novels = WidgetLists.novels(places, titles, covers, books),
                    recommendations = old.recommendations,
                    recommended = old.recommended,
                    novelCovers = covers.filterKeys { key -> places.any { it.key == key } },
                    library = books,
                )
                val stale = System.currentTimeMillis() - old.recommended > RECOMMEND_EVERY_MS || old.recommendations.isEmpty()
                val recommended = if (stale) recommend(context, listed)?.let { listed.copy(recommendations = it, recommended = System.currentTimeMillis()) } else null
                val next = withImages(context, withMediaCovers(context, recommended ?: listed, old.mediaCovers), old)
                if (next.copy(made = 0) != old.copy(made = 0)) {
                    file(context).writeTextAtomically(json.encodeToString(next.copy(made = System.currentTimeMillis())))
                }
                ContinueWidget.update(context)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't refresh the widgets", e)
            }
        }
    }

    /**
     * Finds a cover for the items saved without one: first in the AniList lists the app keeps in its cache, then
     * by asking AniList for that title alone. What is found is kept, so each title is only asked for once.
     */
    private suspend fun withMediaCovers(context: Context, snapshot: WidgetSnapshot, known: Map<String, String>): WidgetSnapshot {
        val missing = snapshot.items.filter { it.cover == null && it.key.toIntOrNull() != null }.map { it.key }.toSet()
        val found = known.filterKeys { it in missing }.toMutableMap()
        val wanted = missing - found.keys
        if (wanted.isNotEmpty()) {
            val services = AniListServices.get(context)
            val userId = runCatching { userId(services) }.getOrNull()
            if (userId != null) {
                val entries = runCatching { services.api.animeList(userId).value }.getOrNull().orEmpty() +
                    runCatching { services.api.mangaList(userId).value }.getOrNull().orEmpty()
                entries.forEach { entry ->
                    val key = entry.media.id.toString()
                    if (key in wanted) entry.media.coverImage.best?.let { found[key] = it }
                }
            }
            for (key in wanted - found.keys) {
                runCatching { services.api.media(key.toInt()).value.coverImage.best }.getOrNull()?.let { found[key] = it }
            }
        }
        fun fill(items: List<WidgetItem>) = items.map { item -> if (item.cover == null) item.copy(cover = found[item.key]) else item }
        return snapshot.copy(
            anime = fill(snapshot.anime),
            manga = fill(snapshot.manga),
            novels = fill(snapshot.novels),
            mediaCovers = found,
        )
    }

    /**
     * Works out the recommendations, or returns null when AniList couldn't be reached. Seeds come from your AniList
     * lists (watching or reading now, then finished with the highest scores), or from this phone's history in guest
     * mode. Only reads from AniList.
     */
    private suspend fun recommend(context: Context, snapshot: WidgetSnapshot): List<WidgetItem>? {
        val services = AniListServices.get(context)
        val userId = runCatching { userId(services) }.getOrNull()
        val history = snapshot.anime.map { RecommendationSeed(it.key.toInt(), it.title, MediaKind.Anime) } +
            snapshot.manga.map { RecommendationSeed(it.key.toInt(), it.title, MediaKind.Manga) } +
            snapshot.novels.mapNotNull { item -> item.key.toIntOrNull()?.let { RecommendationSeed(it, item.title, MediaKind.LightNovel) } }
        val entries = if (userId == null) {
            emptyList()
        } else {
            try {
                services.api.animeList(userId).value + services.api.mangaList(userId).value
            } catch (_: Exception) {
                return null
            }
        }
        val seeds = if (entries.isEmpty()) WidgetRecommendations.seedsFromHistory(history) else WidgetRecommendations.seeds(entries)
        if (seeds.isEmpty()) return emptyList()
        val found = seeds.associate { seed ->
            seed.mediaId to runCatching { services.api.details(seed.mediaId).value.recommendations.nodes }.getOrNull().orEmpty()
        }
        if (found.values.all { it.isEmpty() }) return null
        val exclude = entries.map { it.media.id }.toSet() + history.map { it.mediaId }
        return WidgetRecommendations.pick(seeds, found, exclude)
    }

    private suspend fun userId(services: AniListServices): Int? {
        if (services.session.state is SessionState.Loading) withContext(Dispatchers.Main) { services.session.restore() }
        return services.session.userId
    }

    /** Gives each item its reduced cover, reusing the ones already made, and deletes the ones no longer used. */
    private fun withImages(context: Context, snapshot: WidgetSnapshot, old: WidgetSnapshot): WidgetSnapshot {
        val dir = images(context).apply { mkdirs() }
        val known = old.items.filter { it.image != null }.associate { it.cover to it.image }
        fun fill(items: List<WidgetItem>) = items.map { item ->
            val cover = item.cover ?: return@map item.copy(image = null)
            val name = known[cover]?.takeIf { it.endsWith(".png") && File(dir, it).isFile } ?: reduce(cover, dir)
            item.copy(image = name)
        }
        val next = snapshot.copy(
            anime = fill(snapshot.anime),
            manga = fill(snapshot.manga),
            novels = fill(snapshot.novels),
            recommendations = fill(snapshot.recommendations),
        )
        val used = next.items.mapNotNull { it.image }.toSet()
        dir.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
        return next
    }

    /**
     * Downloads or reads [cover], shrinks it to [IMAGE_WIDTH], rounds its corners and saves it as a PNG.
     * Returns the file name.
     */
    private fun reduce(cover: String, dir: File): String? {
        val name = hash(cover) + ".png"
        val target = File(dir, name)
        if (target.isFile && target.length() > 0) return name
        val bytes = runCatching { load(cover) }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= IMAGE_WIDTH) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val height = (decoded.height.toLong() * IMAGE_WIDTH / decoded.width).toInt().coerceAtLeast(1)
        val scaled = if (decoded.width > IMAGE_WIDTH) decoded.scale(IMAGE_WIDTH, height) else decoded
        val rounded = rounded(scaled)
        val temporary = File(dir, "$name.part")
        temporary.outputStream().use { rounded.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rounded.recycle()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
        return if (temporary.renameTo(target)) name else null
    }

    private fun rounded(source: Bitmap): Bitmap {
        val out = createBitmap(source.width, source.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val radius = source.width * CORNER
        Canvas(out).drawRoundRect(RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()), radius, radius, paint)
        return out
    }

    private fun load(cover: String): ByteArray? {
        val path = cover.removePrefix("file://")
        if (path.startsWith("/")) return File(path).takeIf { it.isFile }?.readBytes()
        val request = Request.Builder().url(cover).header("User-Agent", SourceHttp.USER_AGENT).build()
        return SourceHttp.client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.bytes() else null
        }
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
