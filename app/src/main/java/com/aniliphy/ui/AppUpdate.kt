package com.aniliphy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.aniliphy.core.AppRelease
import com.aniliphy.core.AppUpdates
import com.aniliphy.core.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * A newer aniliphy on GitHub: looked for at most once a day, shown as a strip on Home and in About, downloaded and
 * handed to Android's installer from inside the app.
 */
internal object AppUpdate {
    private const val PREFS = "updates"
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The newest release, when it's newer than this app and wasn't skipped. */
    var available by mutableStateOf<AppRelease?>(null)
        private set

    /** The strip on Home was closed for this version. */
    var stripClosed by mutableStateOf(false)
        private set

    var sheetOpen by mutableStateOf(false)

    var checking by mutableStateOf(false)
        private set

    /** Bytes so far and in all while downloading; null when not downloading. */
    var downloading by mutableStateOf<Pair<Long, Long>?>(null)
        private set

    var problem by mutableStateOf<String?>(null)
        private set

    /** Said after a manual check that found nothing newer. */
    var upToDate by mutableStateOf(false)
        private set

    private var loaded = false
    private var job: Job? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        val release = prefs.getString("latest", null)?.let { runCatching { json.decodeFromString<AppRelease>(it) }.getOrNull() }
        show(context, release)
    }

    private fun show(context: Context, release: AppRelease?) {
        val prefs = prefs(context)
        val installed = AppUpdates.installedVersion(context)
        val newer = release?.takeIf { AppUpdates.isNewer(it.version, installed) && prefs.getString("skipped", null) != it.version }
        available = newer
        stripClosed = newer != null && prefs.getString("strip_closed", null) == newer.version
    }

    /** Looks on GitHub when a day has gone by since the last look, or now when [force]d (About › Check now). */
    fun check(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        load(app)
        val prefs = prefs(app)
        val now = System.currentTimeMillis()
        if (checking || (!force && !AppUpdates.dueForCheck(prefs.getLong("checked_at", 0L), now))) return
        checking = true
        upToDate = false
        if (force) problem = null
        scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { AppUpdates().latest() }
                prefs.edit().putLong("checked_at", now).putString("latest", release?.let { json.encodeToString(AppRelease.serializer(), it) }).apply()
                if (force && release != null && prefs.getString("skipped", null) == release.version) prefs.edit().remove("skipped").apply()
                show(app, release)
                if (force && available == null) upToDate = true
            } catch (e: IOException) {
                if (force) problem = "Couldn't reach GitHub: ${e.message ?: "no connection"}"
            } finally {
                checking = false
            }
        }
    }

    fun closeStrip(context: Context) {
        val version = available?.version ?: return
        prefs(context).edit().putString("strip_closed", version).apply()
        stripClosed = true
    }

    fun skip(context: Context) {
        val version = available?.version ?: return
        prefs(context).edit().putString("skipped", version).apply()
        sheetOpen = false
        available = null
    }

    /** Downloads the release's APK (or uses the one already downloaded) and opens Android's installer with it. */
    fun install(context: Context) {
        val release = available ?: return
        val url = release.apkUrl ?: run {
            context.openLink(release.pageUrl)
            return
        }
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            problem = "Allow aniliphy to install apps, then come back and tap Install again"
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        if (job?.isActive == true) return
        problem = null
        val folder = File(app.cacheDir, "updates").apply { mkdirs() }
        val apk = File(folder, "aniliphy-${release.version}.apk")
        job = scope.launch {
            try {
                if (!(apk.exists() && (release.apkBytes <= 0 || apk.length() == release.apkBytes))) {
                    folder.listFiles()?.forEach { it.delete() }
                    downloading = 0L to release.apkBytes
                    withContext(Dispatchers.IO) {
                        Web.download(url, apk) { done, total -> scope.launch { downloading = done to (if (total > 0) total else release.apkBytes) } }
                    }
                }
                downloading = null
                val trouble = withContext(Dispatchers.IO) { signatureTrouble(app, apk) }
                if (trouble != null) {
                    problem = trouble
                    return@launch
                }
                openInstaller(context, apk)
            } catch (e: IOException) {
                downloading = null
                problem = "The download failed: ${e.message ?: "no connection"}"
            }
        }
    }

    /** Android only installs over this app an APK signed with the same key; better to say so than to fail there. */
    private fun signatureTrouble(context: Context, apk: File): String? {
        val pm = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        } ?: return "The downloaded file isn't a working APK. Try again"
        if (archive.packageName != context.packageName) return "The downloaded APK is another app"
        val installed = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        fun keys(info: android.content.pm.PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures.orEmpty().map { it.toCharsString() }.toSet()
        }
        val theirs = keys(archive)
        val ours = keys(installed)
        if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs.intersect(ours).isEmpty()) {
            return "This APK is signed with another key than the app you have (a test build?), so Android won't install it over it"
        }
        return null
    }

    private fun openInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            problem = "This phone has no installer for APKs"
        }
    }

    fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)
}

/** The strip at the top of Home: "aniliphy 0.7.0 is out". */
@Composable
internal fun UpdateStrip() {
    val release = AppUpdate.available ?: return
    if (AppUpdate.stripClosed) return
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(lookShape(10.dp))
            .border(1.5.dp, theme.accent, lookShape(10.dp))
            .background(theme.accent.copy(alpha = 0.08f))
            .clickable { AppUpdate.sheetOpen = true }
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
    ) {
        NewBadge("NEW", fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        BasicText(
            "aniliphy ${release.version} is out" + (release.apkBytes.takeIf { it > 0 }?.let { " · ${AppUpdate.megabytes(it)} MB" } ?: ""),
            maxLines = 1,
            style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        BasicText("What's new ›", style = TextStyle(color = theme.accent, fontSize = 13.sp))
        BasicText(
            "✕",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 15.sp),
            modifier = Modifier.clickable { AppUpdate.closeStrip(context) }.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** The release's notes, with Download and install. */
@Composable
internal fun UpdateSheet() {
    if (!AppUpdate.sheetOpen) return
    val release = AppUpdate.available ?: run {
        AppUpdate.sheetOpen = false
        return
    }
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val installed = androidx.compose.runtime.remember { AppUpdates.installedVersion(context) }
    Dialog(onDismissRequest = { AppUpdate.sheetOpen = false }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(newLookShape(16.dp))
                .background(theme.surface)
                .padding(20.dp),
        ) {
            BasicText("$installed → ${release.version}", style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true))
            BasicText("What's new", style = TitleStyle(color = theme.foreground, fontSize = 22.sp))
            Spacer(Modifier.height(8.dp))
            Box(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                BasicText(
                    release.notes.ifBlank { "No notes for this version." },
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp),
                )
            }
            Spacer(Modifier.height(14.dp))
            val progress = AppUpdate.downloading
            PillButton(
                when {
                    progress != null -> "Downloading..."
                    release.apkUrl == null -> "Open on GitHub"
                    else -> "Download and install"
                },
                onClick = { AppUpdate.install(context) },
                enabled = progress == null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (progress != null) {
                val (done, total) = progress
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).background(theme.foreground.copy(alpha = 0.12f))) {
                    if (total > 0) Box(Modifier.fillMaxWidth((done.toFloat() / total).coerceIn(0f, 1f)).height(4.dp).background(theme.primary))
                }
                BasicText(
                    "${AppUpdate.megabytes(done)} / ${if (total > 0) AppUpdate.megabytes(total) else "?"} MB · then Android asks to install it",
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AppUpdate.problem?.let {
                Spacer(Modifier.height(8.dp))
                BasicText(it, style = TextStyle(color = theme.error, fontSize = 13.sp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BasicText(
                    "Later",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp),
                    modifier = Modifier.clickable { AppUpdate.sheetOpen = false }.padding(vertical = 6.dp),
                )
                BasicText(
                    "Skip this version",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp),
                    modifier = Modifier.clickable { AppUpdate.skip(context) }.padding(vertical = 6.dp),
                )
                BasicText(
                    "GitHub",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp),
                    modifier = Modifier.clickable { context.openLink(release.pageUrl) }.padding(vertical = 6.dp),
                )
            }
        }
    }
}
