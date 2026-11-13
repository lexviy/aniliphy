package com.aniliphy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.core.writeTextAtomically
import com.aniliphy.sync.ProgressSync
import com.aniliphy.sync.SyncDifference
import com.aniliphy.sync.SyncDifferences
import com.aniliphy.sync.SyncPayload
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

    private val differencesFile = java.io.File(app.filesDir, "sync_differences.json")

    /** Titles where this phone and the computer disagreed at a sync, newest first. */
    var differences by mutableStateOf(readDifferences())
        private set

    private fun readDifferences(): List<SyncDifference> = runCatching {
        differencesFile.takeIf { it.isFile }?.readText()?.let { SyncPayload.json.decodeFromString<List<SyncDifference>>(it) }
    }.getOrNull().orEmpty()

    private fun keepDifferences(list: List<SyncDifference>) {
        differences = list
        scope.launch(Dispatchers.IO) {
            runCatching { differencesFile.writeTextAtomically(SyncPayload.json.encodeToString(list)) }
        }
    }

    /** Goes back to the place that wasn't kept for this title, and syncs so the computer gets it too. */
    fun useOther(difference: SyncDifference) {
        scope.launch {
            withContext(Dispatchers.IO) {
                ProgressSync(anime.history, manga.history, novels.progress).useOther(difference, importNovels = { novels.importSynced(it) })
            }
            when (difference.kind) {
                SyncDifference.Kind.Anime -> anime.historyChanged()
                SyncDifference.Kind.Manga -> manga.historyChanged()
                SyncDifference.Kind.Novel -> novels.progressChanged()
            }
            keepDifferences(differences - difference)
            com.aniliphy.widget.WidgetData.refreshSoon(app)
            app.showToast(if (difference.keptComputer) "Back to this phone's place" else "Now at the computer's place")
            sync(quiet = true)
        }
    }

    fun clearDifferences() = keepDifferences(emptyList())

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
                if (settings.profile) withContext(Dispatchers.IO) { syncProfile(token, result.gistId) }
                if (result.differences.isNotEmpty()) keepDifferences(SyncDifferences.remember(differences, result.differences))
                settings.lastSynced = System.currentTimeMillis().toString()
                if (result.anime > 0) anime.historyChanged()
                if (result.manga > 0) manga.historyChanged()
                if (result.novels > 0) novels.progressChanged()
                if (result.changed > 0) com.aniliphy.widget.WidgetData.refreshSoon(app)
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

    /** Goals, showcase, celebrated moments and activity, in their own gist file. Failing here doesn't fail the sync. */
    private fun syncProfile(token: String, gistId: String) = runCatching {
        if (settings.device.isBlank()) settings.device = java.util.UUID.randomUUID().toString()
        val device = settings.device
        val gists = com.aniliphy.sync.GistStore()
        val local = ProfileLocal.get(app)
        val mine = com.aniliphy.sync.ProfilePayload(
            profile = local.data,
            activity = mapOf(device to com.aniliphy.core.ActivityLog.own(app)) + com.aniliphy.core.ActivityLog.others(app),
        )
        val remoteText = gists.readFile(token, gistId, com.aniliphy.sync.ProfilePayload.FILE_NAME)
        val remote = remoteText?.let { com.aniliphy.sync.ProfilePayload.parse(it) ?: return@runCatching } ?: com.aniliphy.sync.ProfilePayload()
        val merged = com.aniliphy.sync.ProfileMerge.merge(mine, remote, device)
        local.replace(merged.profile)
        com.aniliphy.core.ActivityLog.setOthers(app, merged.activity - device)
        if (merged != remote) gists.writeFile(token, gistId, com.aniliphy.sync.ProfilePayload.FILE_NAME, SyncPayload.json.encodeToString(com.aniliphy.sync.ProfilePayload.serializer(), merged))
    }

    private fun describe(result: SyncResult): String {
        val synced = LookWords.synced(Look.style.takeIf { LookPrefs.moments })
        if (result.changed == 0) return if (result.sent) "$synced Sent this phone's progress" else "$synced Nothing new"
        val parts = listOfNotNull(
            result.anime.takeIf { it > 0 }?.let { "$it anime" },
            result.manga.takeIf { it > 0 }?.let { "$it manga" },
            result.novels.takeIf { it > 0 }?.let { "$it light novel" + if (it == 1) "" else "s" },
        )
        return "$synced New places for " + parts.joinToString(", ")
    }

    fun forgetToken() {
        settings.token = ""
        settings.gistId = ""
        settings.lastSynced = ""
        status = null
    }
}
