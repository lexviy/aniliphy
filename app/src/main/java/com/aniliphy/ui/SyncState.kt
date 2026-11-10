package com.aniliphy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.sync.ProgressSync
import com.aniliphy.sync.SyncAuthException
import com.aniliphy.sync.SyncResult
import com.aniliphy.sync.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal class SyncUi(context: Context, private val anime: AnimeUi, private val manga: MangaUi, private val novels: NovelsUi) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val settings = SyncSettings.get(app)

    var running by mutableStateOf(false)
        private set

    var status by mutableStateOf<String?>(null)
        private set

    fun sync(quiet: Boolean = false) {
        if (running || !settings.ready) return
        running = true
        val token = settings.token.trim()
        val gistId = settings.gistId
        scope.launch {
            val message = try {
                novels.prepareForSync()
                val result = withContext(Dispatchers.IO) {
                    ProgressSync(anime.history, manga.history, novels.progress).run(token, gistId) { novels.importSynced(it) }
                }
                settings.gistId = result.gistId
                settings.lastSynced = System.currentTimeMillis().toString()
                if (result.anime > 0) anime.historyChanged()
                if (result.manga > 0) manga.historyChanged()
                if (result.novels > 0) novels.progressChanged()
                describe(result).also { if (!quiet || result.changed > 0) app.showToast(it) }
            } catch (e: SyncAuthException) {
                e.message!!.also { app.showToast(it) }
            } catch (_: IOException) {
                "Couldn't reach GitHub".also { if (!quiet) app.showToast(it) }
            } finally {
                running = false
            }
            status = message
        }
    }

    private fun describe(result: SyncResult): String {
        if (result.changed == 0) return if (result.sent) "Synced. Sent this phone's progress" else "Synced. Nothing new"
        val parts = listOfNotNull(
            result.anime.takeIf { it > 0 }?.let { "$it anime" },
            result.manga.takeIf { it > 0 }?.let { "$it manga" },
            result.novels.takeIf { it > 0 }?.let { "$it light novel" + if (it == 1) "" else "s" },
        )
        return "Synced. New places for " + parts.joinToString(", ")
    }

    fun forgetToken() {
        settings.token = ""
        settings.gistId = ""
        settings.lastSynced = ""
        status = null
    }
}
