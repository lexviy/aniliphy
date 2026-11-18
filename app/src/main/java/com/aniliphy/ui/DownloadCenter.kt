package com.aniliphy.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.work.DownloadService
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One episode or chapter waiting to be saved, written down so the queue outlives the app being closed. */
@Serializable
internal data class QueuedDownload(
    val anime: Boolean,
    val key: String,
    /** The player or reader request, as JSON. */
    val request: String,
    /** The episode or chapter, as JSON. */
    val item: String,
    val audio: String? = null,
)

internal class DownloadQueueStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    fun all(): List<QueuedDownload> = synchronized(lock) {
        file.readTextOrNull()?.let { runCatching { json.decodeFromString<List<QueuedDownload>>(it) }.getOrNull() }.orEmpty()
    }

    fun add(items: List<QueuedDownload>) = synchronized(lock) {
        val old = all()
        val keys = old.map { it.key }.toSet()
        write(old + items.filter { it.key !in keys })
    }

    fun remove(key: String) = synchronized(lock) {
        val old = all()
        if (old.any { it.key == key }) write(old.filterNot { it.key == key })
    }

    fun clear() = synchronized(lock) { file.delete() }

    private fun write(items: List<QueuedDownload>) {
        if (items.isEmpty()) file.delete() else runCatching { file.writeTextAtomically(json.encodeToString(items)) }
    }

    companion object {
        @Volatile private var shared: DownloadQueueStore? = null

        fun get(context: Context): DownloadQueueStore =
            shared ?: synchronized(this) {
                shared ?: DownloadQueueStore(File(context.applicationContext.filesDir, "download_queue.json")).also { shared = it }
            }
    }
}

/**
 * What the anime and manga download queues share: pausing, waiting for a connection (or Wi-Fi), what is being
 * saved right now, and the foreground service that keeps the app alive with a notification while anything is left.
 */
internal object DownloadCenter {
    enum class Wait(val label: String?) {
        None(null),
        Paused("Paused"),
        Network("Waiting for a connection"),
        WiFi("Waiting for Wi-Fi"),
    }

    data class Running(val title: String, val item: String, val percent: Int)

    var paused by mutableStateOf(false)
        private set

    var waiting by mutableStateOf(Wait.None)
        private set

    /** What each queue ("anime", "manga") is saving now. */
    val running = mutableStateMapOf<String, Running>()

    /** How many are left in each queue, the one running included. */
    val left = mutableStateMapOf<String, Int>()

    private val stops = ConcurrentHashMap<String, () -> Unit>()
    private val cancellers = ConcurrentHashMap<String, () -> Unit>()

    val busy: Boolean get() = left.values.sum() > 0

    /**
     * True while [com.aniliphy.work.AutoDownloadWorker] runs the queue in the background: it holds the notification
     * itself, since a service can't be started from there.
     */
    @Volatile var hostedByWorker = false

    /** Each queue says how to empty itself, for "Cancel all". */
    fun register(queue: String, cancelAll: () -> Unit) {
        cancellers[queue] = cancelAll
    }

    fun setLeft(context: Context, queue: String, count: Int) {
        if (count <= 0) {
            left.remove(queue)
            running.remove(queue)
        } else {
            left[queue] = count
        }
        changed(context)
    }

    fun setRunning(context: Context, queue: String, now: Running?) {
        if (running[queue] == now) return
        val newItem = running[queue]?.item != now?.item
        if (now == null) running.remove(queue) else running[queue] = now
        DownloadService.refresh(context, force = newItem)
    }

    /** Stops what is downloading right now (to pause or cancel); null once it's done. */
    fun setStop(queue: String, stop: (() -> Unit)?) {
        if (stop == null) stops.remove(queue) else stops[queue] = stop
    }

    fun pause(context: Context) {
        paused = true
        waiting = Wait.Paused
        // What is half saved stays, and carries on from there.
        stops.values.forEach { runCatching(it) }
        changed(context)
    }

    fun resume(context: Context) {
        paused = false
        waiting = Wait.None
        changed(context)
    }

    fun cancelAll(context: Context) {
        cancellers.values.forEach { runCatching(it) }
        stops.values.forEach { runCatching(it) }
        DownloadQueueStore.get(context).clear()
        paused = false
        changed(context)
    }

    /** Waits while paused, offline, or on mobile data with "Wi-Fi only" on. */
    suspend fun gate(context: Context, wifiOnly: () -> Boolean) {
        while (true) {
            val wait = blocker(context, wifiOnly())
            if (waiting != wait) {
                waiting = wait
                DownloadService.refresh(context, force = true)
            }
            if (wait == Wait.None) return
            delay(WAIT_STEP_MS)
        }
    }

    /** Why downloads can't go on right now, or None. */
    fun blocker(context: Context, wifiOnly: Boolean): Wait = when {
        paused -> Wait.Paused
        !context.online() -> Wait.Network
        wifiOnly && context.onMeteredNetwork() -> Wait.WiFi
        else -> Wait.None
    }

    private fun changed(context: Context) {
        if (busy) {
            if (DownloadService.running) DownloadService.refresh(context, force = true)
            else if (!hostedByWorker) runCatching { ContextCompat.startForegroundService(context, android.content.Intent(context, DownloadService::class.java)) }
        } else {
            waiting = Wait.None
            DownloadService.finish()
        }
    }

    private const val WAIT_STEP_MS = 1_500L
}

internal fun Context.online(): Boolean {
    val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
    val network = connectivity.activeNetwork ?: return false
    return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
