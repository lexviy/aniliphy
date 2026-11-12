package com.aniliphy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aniliphy.core.Sources
import com.aniliphy.core.TachiBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal class BackupImport(val pick: () -> Unit)

private class ImportPreview(val anime: TachiBackup.Plan, val manga: TachiBackup.Plan) {
    val linking get() = anime.link.size + manga.link.size
}

/**
 * Picks a Mihon or Aniyomi backup, counts what it would change, and after a yes gives each AniList entry with no source
 * yet the source and address the title had there. Entries that already open with a source are left as they are.
 */
@Composable
internal fun rememberBackupImport(): BackupImport {
    val context = LocalContext.current
    val anime = LocalAnime.current
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("The file couldn't be opened")
                    val library = TachiBackup.read(bytes)
                    ImportPreview(
                        anime = TachiBackup.plan(library.anime, Sources.anime.map { it.id }.toSet(), anime.history.all().mapValues { it.value.source }),
                        manga = TachiBackup.plan(library.manga, Sources.manga.map { it.id }.toSet(), manga.history.all().mapValues { it.value.source }),
                    )
                }
            }
            result.onSuccess { preview = it }
                .onFailure { context.showToast((it as? IOException)?.message ?: "The backup couldn't be read") }
        }
    }
    preview?.let { shown ->
        val counts = listOf(
            "${shown.anime.link.size} anime and ${shown.manga.link.size} manga get their source",
            "${shown.anime.alreadySet + shown.manga.alreadySet} already open with a source and stay as they are",
            "${shown.anime.notOnAniList + shown.manga.notOnAniList} weren't tracked on AniList",
            "${shown.anime.notInstalled + shown.manga.notInstalled} are on sources that aren't installed",
        ).joinToString("\n")
        if (shown.linking == 0) {
            ConfirmDialog(
                title = "Nothing to import",
                message = counts,
                confirmLabel = "OK",
                onConfirm = { preview = null },
                onDismiss = { preview = null },
            )
        } else {
            ConfirmDialog(
                title = "Import ${shown.linking} titles?",
                message = counts,
                confirmLabel = "Import",
                onConfirm = {
                    preview = null
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            shown.anime.link.forEach { (id, item) -> anime.history.setSource(id, Sources.place(item.source, item.url), item.title, item.cover) }
                            shown.manga.link.forEach { (id, item) -> manga.history.setSource(id, Sources.place(item.source, item.url), item.title, item.cover) }
                        }
                        context.showToast("Imported ${shown.linking} titles")
                    }
                },
                onDismiss = { preview = null },
            )
        }
    }
    return remember(launcher) { BackupImport { launcher.launch(arrayOf("*/*")) } }
}
