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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import com.aniliphy.core.BackupData
import com.aniliphy.core.FullBackup
import com.aniliphy.core.InvalidBackupException
import com.aniliphy.manga.MangaSettings
import com.aniliphy.novels.NovelSettings
import com.aniliphy.core.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class PendingConfirm { DeleteOffline, Reset }

@Composable
fun StorageScreen(onBack: () -> Unit, onDownloads: () -> Unit, onTravel: () -> Unit = {}) {
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

    var lastBackup by remember { mutableStateOf(BackupRecord.read(context)) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            context.toast(withContext(Dispatchers.IO) { exportEverything(context, uri) })
            lastBackup = BackupRecord.read(context)
        }
    }

    var toRestore by remember { mutableStateOf<BackupData?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val read = withContext(Dispatchers.IO) { readBackup(context, uri) }
            read.onSuccess { toRestore = it }.onFailure { context.toast(it.message ?: "Couldn't read the file") }
        }
    }

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

        SectionTitle("Offline")
        ActionRow(
            label = "Travel mode",
            description = travelLine(context),
            onClick = onTravel,
        )

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

        SectionTitle("Backup")
        ActionRow(
            label = "Back up everything",
            description = "Settings, where you stopped in every anime, manga and book, linked sources, goals and achievements, to one file",
            onClick = { exportLauncher.launch("aniliphy-backup-${java.time.LocalDate.now()}.zip") },
        )
        ActionRow(
            label = "Restore from a file",
            description = "Shows what's in it first. Also reads the old settings files",
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        )
        lastBackup?.let { record ->
            ActionRow(
                label = "Last backup",
                description = listOfNotNull(
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(record.at)),
                    Formatter.formatShortFileSize(context, record.bytes),
                    record.name,
                ).joinToString(" · "),
                enabled = false,
                onClick = {},
            )
        }

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

    toRestore?.let { data ->
        RestoreDialog(
            data = data,
            onRestore = { parts, merge ->
                toRestore = null
                scope.launch {
                    val error = withContext(Dispatchers.IO) { restoreBackup(context, data, parts, merge) }
                    if (error != null) context.toast(error) else activity?.let { restartApp(it) }
                }
            },
            onDismiss = { toRestore = null },
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

/** When and where the last backup went, shown under Backup (kept in its own store, never in a backup). */
internal data class BackupRecord(val at: Long, val bytes: Long, val name: String?) {
    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

        fun read(context: Context): BackupRecord? {
            val prefs = prefs(context)
            val at = prefs.getLong("last_at", 0L).takeIf { it > 0 } ?: return null
            return BackupRecord(at, prefs.getLong("last_bytes", 0L), prefs.getString("last_name", null))
        }

        fun save(context: Context, record: BackupRecord) {
            prefs(context).edit().putLong("last_at", record.at).putLong("last_bytes", record.bytes).putString("last_name", record.name).apply()
        }

        /** Said once after the app restarts from a restore. */
        fun takeRestoredMessage(context: Context): String? {
            val prefs = prefs(context)
            val text = prefs.getString("restored", null) ?: return null
            prefs.edit().remove("restored").apply()
            return text
        }

        fun keepRestoredMessage(context: Context, text: String) {
            prefs(context).edit().putString("restored", text).commit()
        }
    }
}

private fun exportEverything(context: Context, uri: Uri): String = try {
    val data = FullBackup.collect(context, AppIcon.current(context).name)
    val bytes = java.io.ByteArrayOutputStream().also { FullBackup.write(it, data) }.toByteArray()
    val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
    output.use { it.write(bytes) }
    BackupRecord.save(context, BackupRecord(System.currentTimeMillis(), bytes.size.toLong(), displayName(context, uri)))
    "Backup saved"
} catch (_: IOException) {
    "Couldn't save the file"
} catch (_: SecurityException) {
    "Couldn't save the file"
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

private fun readBackup(context: Context, uri: Uri): Result<BackupData> = try {
    val input = context.contentResolver.openInputStream(uri) ?: throw IOException()
    Result.success(input.use { FullBackup.read(it) })
} catch (_: InvalidBackupException) {
    Result.failure(IOException("This isn't an aniliphy backup"))
} catch (e: IOException) {
    Result.failure(IOException(e.message?.takeIf { it.startsWith("This backup") || it.startsWith("The backup") } ?: "Couldn't read the file"))
} catch (_: SecurityException) {
    Result.failure(IOException("Couldn't read the file"))
}

private fun restoreBackup(context: Context, data: BackupData, parts: Set<FullBackup.Part>, merge: Boolean): String? = try {
    FullBackup.restore(context, data, parts, merge)
    if (FullBackup.Part.Settings in parts) {
        data.icon?.let { name -> AppIcon.entries.firstOrNull { it.name == name } }?.let { AppIcon.select(context, it) }
    }
    BackupRecord.keepRestoredMessage(context, restoredMessage(FullBackup.summary(data).filter { it.part in parts }))
    null
} catch (_: IOException) {
    "Couldn't restore everything. Try again"
}

/** "Restored: 7 anime, 19 manga, profile and settings", short enough for a toast. */
private fun restoredMessage(parts: List<FullBackup.PartSummary>): String {
    fun number(item: FullBackup.PartSummary) = item.count.substringBefore(' ')
    val said = parts.map { item ->
        when (item.part) {
            FullBackup.Part.Anime -> "${number(item)} anime"
            FullBackup.Part.Manga -> "${number(item)} manga"
            FullBackup.Part.Novels -> "${number(item)} novel series"
            FullBackup.Part.Profile -> "profile"
            FullBackup.Part.Fixes -> "your fixes"
            FullBackup.Part.Settings -> "settings"
        }
    }
    return when (said.size) {
        0 -> "Backup restored"
        1 -> "Restored: ${said.single()}"
        else -> "Restored: ${said.dropLast(1).joinToString(", ")} and ${said.last()}"
    }
}

/** Starts the app again, so nothing still holds what was there before the restore. */
private fun restartApp(activity: android.app.Activity) {
    val start = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
        ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ?: return activity.recreate()
    activity.startActivity(start)
    Runtime.getRuntime().exit(0)
}

@Composable
private fun RestoreDialog(data: BackupData, onRestore: (Set<FullBackup.Part>, Boolean) -> Unit, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    val summary = remember(data) { FullBackup.summary(data) }
    var chosen by remember(data) { mutableStateOf(summary.map { it.part }.toSet()) }
    var merge by remember { mutableStateOf(true) }
    val from = remember(data) {
        val created = data.manifest["created"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }
        listOfNotNull(
            created?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(it)) },
            (data.manifest["device"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
            (data.manifest["version"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { "aniliphy $it" },
        ).joinToString(" · ").ifEmpty { "Settings file from an older version" }
    }
    DialogFrame(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            BasicText("Restore this backup?", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
            BasicText(from, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp, fontFamily = JetBrainsMono))
            Spacer(Modifier.height(10.dp))
            summary.forEach { item ->
                val on = item.part in chosen
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = androidx.compose.ui.semantics.Role.Checkbox) { chosen = if (on) chosen - item.part else chosen + item.part }
                        .padding(vertical = 7.dp),
                ) {
                    CheckMark(on)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        BasicText(item.part.label, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
                        BasicText(item.part.detail, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
                    }
                    Spacer(Modifier.width(8.dp))
                    BasicText(item.count, style = TextStyle(color = theme.primary, fontSize = 13.sp, fontFamily = JetBrainsMono))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                ModeTile("Merge", "Per title, the newer place wins. Nothing here is lost", merge, Modifier.weight(1f)) { merge = true }
                Spacer(Modifier.width(8.dp))
                ModeTile("Replace", "This phone becomes exactly the file", !merge, Modifier.weight(1f)) { merge = false }
            }
            Spacer(Modifier.height(10.dp))
            BasicText(
                "Never in a backup: your AniList and GitHub tokens, site cookies, which extensions you trust, and anything " +
                    "still waiting to reach AniList. Log in and trust them again on a new phone. The app starts again after restoring.",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                PillButton("Cancel", onClick = onDismiss, filled = false)
                Spacer(Modifier.width(8.dp))
                PillButton("Restore", onClick = { onRestore(chosen, merge) }, enabled = chosen.isNotEmpty())
            }
        }
    }
}

@Composable
internal fun CheckMark(on: Boolean) {
    val theme = LocalAppTheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(top = 2.dp)
            .size(20.dp)
            .clip(newLookShape(4.dp))
            .background(if (on) theme.primary else Color.Transparent)
            .border(2.dp, theme.primary, newLookShape(4.dp)),
    ) {
        // Drawn, not typed: some looks' fonts have no check mark.
        if (on) Canvas(Modifier.size(12.dp)) {
            val tick = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.08f, size.height * 0.55f)
                lineTo(size.width * 0.4f, size.height * 0.85f)
                lineTo(size.width * 0.95f, size.height * 0.15f)
            }
            drawPath(tick, theme.background, style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.width * 0.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
private fun ModeTile(title: String, detail: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Column(
        modifier
            .clip(newLookShape(8.dp))
            .background(if (selected) theme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.5.dp, if (selected) theme.primary else theme.foreground.copy(alpha = 0.25f), newLookShape(8.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicText(title, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        BasicText(detail, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
    }
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Under "Travel mode": what it does, or that a trip is under way. */
internal fun travelLine(context: Context): String =
    if (com.aniliphy.work.TravelMode.trip(context) != null) "Back from a trip? See what you watched and free up space"
    else "Get what you're watching and reading ready for a trip without internet, in one tap"
