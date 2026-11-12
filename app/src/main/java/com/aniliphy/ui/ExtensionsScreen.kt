package com.aniliphy.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.core.Extensions
import com.aniliphy.core.InstalledExtension
import com.aniliphy.core.Sources
import com.aniliphy.core.TachiSite
import com.aniliphy.source.Site
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class SourceLists(
    val installed: List<InstalledExtension>,
    val anime: List<String>,
    val manga: List<String>,
    val novels: List<String>,
    val browsable: List<TachiSite>,
)

@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val lists by produceState<SourceLists?>(null, Sources.version) {
        value = withContext(Dispatchers.IO) {
            SourceLists(
                installed = Sources.installed,
                anime = Sources.anime.map { it.name },
                manga = Sources.manga.map { it.name },
                novels = Sources.novels.map { it.name },
                browsable = (Sources.manga + Sources.anime).filterIsInstance<TachiSite>(),
            )
        }
    }
    var trusting by remember { mutableStateOf<InstalledExtension?>(null) }
    var distrusting by remember { mutableStateOf<InstalledExtension?>(null) }
    var opened by remember { mutableStateOf<TachiSite?>(null) }
    var searchingAll by remember { mutableStateOf(false) }
    val importer = rememberBackupImport()
    opened?.let { site ->
        SourcePage(site, onBack = { opened = null })
        return
    }
    if (searchingAll) {
        SearchAllSources(onBack = { searchingAll = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Extensions", onBack = onBack)
        BasicText(
            "Episodes, chapters and light novels come from sources, which come from extensions: apps you install " +
                "like any APK. Install or remove one and it shows up here by itself.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 13.sp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        val shown = lists ?: return@Column

        SectionTitle("Installed")
        if (shown.installed.isEmpty()) {
            Empty("No extension is installed.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        shown.installed.forEach { extension ->
            val details = extension.problem ?: extension.sources.joinToString(", ").ifEmpty { "No sources" }
            ActionRow(
                label = listOf(extension.label, extension.version).filter { it.isNotBlank() }.joinToString("  ·  "),
                description = "$details\nTap to open it in the phone's settings, to remove it",
                color = if (extension.problem != null) theme.error else theme.foreground,
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", extension.packageName, null))
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
            )
            when {
                !extension.trusted && extension.signer != null ->
                    ActionRow("Trust ${extension.label}", onClick = { trusting = extension }, color = theme.primary)
                extension.trusted && !extension.sameKey && extension.signer != null ->
                    ActionRow("Stop trusting ${extension.label}", onClick = { distrusting = extension })
            }
        }

        SectionTitle("Sources")
        SourceNames("Anime", shown.anime)
        SourceNames("Manga", shown.manga)
        SourceNames("Light novels", shown.novels)

        // Every source can be searched at once; Mihon and Aniyomi ones can be browsed, and some have settings.
        if (shown.anime.isNotEmpty() || shown.manga.isNotEmpty()) {
            SectionTitle("Browse")
            ActionRow(
                label = "Search all sources",
                description = "One search on every anime and manga source",
                onClick = { searchingAll = true },
            )
            ActionRow(
                label = "Import a Mihon or Aniyomi backup",
                description = "Titles tracked on AniList there open with the same source here",
                onClick = { importer.pick() },
            )
            shown.browsable.forEach { site ->
                ActionRow(
                    label = site.name,
                    description = listOfNotNull(if (site.isAnime) "Anime" else "Manga", "settings".takeIf { site.configurable })
                        .joinToString(" · "),
                    onClick = { opened = site },
                )
            }
        }
    }

    trusting?.let { extension ->
        ConfirmDialog(
            title = "Trust ${extension.label}?",
            message = "An extension runs inside aniliphy, so it can see what aniliphy sees, like your AniList login. " +
                "Only trust it if you know who made it.\n\nKey: ${extension.signer?.chunked(4)?.take(8)?.joinToString(" ")}…",
            confirmLabel = "Trust",
            onConfirm = {
                extension.signer?.let { Extensions.trust(context, it) }
                trusting = null
                Sources.reload()
            },
            onDismiss = { trusting = null },
        )
    }
    distrusting?.let { extension ->
        ConfirmDialog(
            title = "Stop trusting ${extension.label}?",
            message = "Its sources stop working until you trust it again.",
            confirmLabel = "Stop trusting",
            onConfirm = {
                extension.signer?.let { Extensions.distrust(context, it) }
                distrusting = null
                Sources.reload()
            },
            onDismiss = { distrusting = null },
        )
    }
}

@Composable
private fun SourceNames(kind: String, names: List<String>) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        BasicText(kind, style = TextStyle(color = theme.foreground, fontSize = 15.sp))
        BasicText(
            names.joinToString(", ").ifEmpty { "None installed" },
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
        )
    }
}

/** The sources of one kind, followed so a picker changes when an extension is installed or removed. */
@Composable
internal fun <T : Site> rememberSources(all: () -> List<T>): List<T> {
    val version = Sources.version
    return remember(version) { all() }
}

/** Picks which source is tried first. */
@Composable
internal fun PreferredSourceRow(kind: String, sites: List<Site>, selected: String, onSelect: (String) -> Unit) {
    if (sites.isEmpty()) {
        ActionRow(
            label = "Preferred source",
            description = "No $kind source is installed. Add one in Settings → Extensions.",
            onClick = {},
            enabled = false,
        )
        return
    }
    SegmentedRow(
        label = "Preferred source",
        description = if (sites.size > 1) "The others are tried when this one finds nothing" else null,
        options = sites.map { it.id },
        selected = selected,
        optionLabel = { id -> sites.firstOrNull { it.id == id }?.name ?: id },
        onSelect = onSelect,
    )
}
