package com.aniliphy.ui

import android.content.Context
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.core.AppStorage
import com.aniliphy.core.InvalidBackupException
import com.aniliphy.core.SettingsBackup
import com.aniliphy.manga.MangaSettings
import com.aniliphy.novels.NovelSettings
import com.aniliphy.core.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private const val BACKUP_FILE_NAME = "aniliphy-settings.json"

private enum class PendingConfirm { DeleteOffline, Reset }

@Composable
fun StorageScreen(onBack: () -> Unit, onDownloads: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current.applicationContext
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()

    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var measurement by remember { mutableIntStateOf(0) }
    LaunchedEffect(measurement) {
        usage = withContext(Dispatchers.IO) { AppStorage.usage(context) }
    }

    fun onDisk(done: String, work: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { work() }
            measurement++
            context.toast(done)
        }
    }

    fun reloadApp(message: String) {
        NovelSettings.forget()
        MangaSettings.forget()
        com.aniliphy.anime.AnimeSettings.forget()
        com.aniliphy.widget.ActivitySettings.forget()
        context.toast(message)
        activity?.recreate()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { context.toast(withContext(Dispatchers.IO) { exportSettings(context, uri) }) }
    }

    var fileToImport by remember { mutableStateOf<Uri?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> fileToImport = uri }

    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Storage", onBack = onBack)

        SectionTitle("Space used")
        UsageSection(context, usage, theme.primary, theme.secondary, theme.accent)

        SectionTitle("Clean up")
        ActionRow(
            label = "Downloads",
            description = "The saved episodes and chapters, to delete one by one",
            onClick = onDownloads,
        )
        ActionRow(
            label = "Clear cache",
            description = "Temporary files. The app downloads them again when needed",
            enabled = (usage?.cache ?: 0L) > 0L,
            onClick = { onDisk("Cache cleared") { AppStorage.clearCache(context) } },
        )
        ActionRow(
            label = "Delete offline manga and anime",
            description = "All the chapters and episodes saved on this phone",
            enabled = (usage?.offlineManga ?: 0L) > 0L,
            onClick = { confirm = PendingConfirm.DeleteOffline },
        )

        SectionTitle("Settings backup")
        ActionRow(
            label = "Export settings",
            description = "Save them to a file, to use on another phone",
            onClick = { exportLauncher.launch(BACKUP_FILE_NAME) },
        )
        ActionRow(
            label = "Import settings",
            description = "Replace the current settings with those in a file",
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        )

        SectionTitle("Reset")
        ActionRow(
            label = "Reset app",
            description = "Delete all settings and data on this phone",
            color = theme.error,
            onClick = { confirm = PendingConfirm.Reset },
        )
    }

    when (confirm) {
        PendingConfirm.DeleteOffline -> ConfirmDialog(
            title = "Delete offline manga and anime?",
            message = "All saved chapters and episodes will be removed from this phone.",
            confirmLabel = "Delete",
            onConfirm = {
                confirm = null
                onDisk("Offline manga and anime deleted") { AppStorage.deleteOfflineManga(context) }
            },
            onDismiss = { confirm = null },
        )
        PendingConfirm.Reset -> ConfirmDialog(
            title = "Reset app?",
            message = "All settings, saved chapters and cached files will be deleted. " +
                "This can't be undone.",
            confirmLabel = "Reset",
            onConfirm = {
                confirm = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AppStorage.deleteEverything(context)
                        AppIcon.select(context, AppIcon.Cat)
                    }
                    reloadApp("App reset")
                }
            },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }

    fileToImport?.let { uri ->
        ConfirmDialog(
            title = "Import settings?",
            message = "Your current settings will be replaced by those in the file.",
            confirmLabel = "Import",
            onConfirm = {
                fileToImport = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) { importSettings(context, uri) }
                    if (error == null) reloadApp("Settings imported") else context.toast(error)
                }
            },
            onDismiss = { fileToImport = null },
        )
    }
}

@Composable
private fun UsageSection(
    context: Context,
    usage: StorageUsage?,
    cacheColor: Color,
    offlineColor: Color,
    dataColor: Color,
) {
    val theme = LocalAppTheme.current
    fun size(bytes: Long?) = if (bytes == null) "…" else Formatter.formatShortFileSize(context, bytes)

    val segments = usage?.let {
        listOf(it.cache to cacheColor, it.offlineManga to offlineColor, it.appData to dataColor)
    }.orEmpty()
    val total = usage?.total ?: 0L
    Canvas(
        Modifier
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(theme.panel),
    ) {
        if (total > 0L) {
            var start = 0f
            segments.forEach { (bytes, color) ->
                val width = size.width * bytes / total
                drawRect(color, Offset(start, 0f), Size(width, size.height))
                start += width
            }
        }
    }
    UsageRow("Cache", size(usage?.cache), cacheColor)
    UsageRow("Offline manga and anime", size(usage?.offlineManga), offlineColor)
    UsageRow("Settings and app data", size(usage?.appData), dataColor)
    UsageRow("Total", size(usage?.total), color = null)
}

@Composable
private fun UsageRow(label: String, value: String, color: Color?) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
    ) {
        if (color != null) {
            Canvas(Modifier.size(10.dp).clip(CircleShape)) { drawRect(color) }
            Spacer(Modifier.width(10.dp))
        }
        BasicText(
            label,
            style = TextStyle(color = theme.foreground, fontSize = 14.sp),
            modifier = Modifier.weight(1f),
        )
        BasicText(
            value,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 14.sp),
        )
    }
}

private fun exportSettings(context: Context, uri: Uri): String = try {
    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
    output.use { SettingsBackup.export(context, AppIcon.current(context).name, it) }
    "Settings exported"
} catch (_: IOException) {
    "Couldn't save the file"
} catch (_: SecurityException) {
    "Couldn't save the file"
}

private fun importSettings(context: Context, uri: Uri): String? = try {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException()
    val icon = input.use { SettingsBackup.import(context, it) }
    AppIcon.entries.firstOrNull { it.name == icon }?.let { AppIcon.select(context, it) }
    null
} catch (_: InvalidBackupException) {
    "This isn't an aniliphy settings file"
} catch (_: IOException) {
    "Couldn't read the file"
} catch (_: SecurityException) {
    "Couldn't read the file"
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
