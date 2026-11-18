package com.aniliphy.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reads a `.aniliphy-look` file; null when it isn't one. */
internal fun readLookFile(context: Context, uri: Uri): LookSpec? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        // A look is a few hundred bytes; anything big isn't one.
        val bytes = stream.readNBytes(64 * 1024)
        LookSpec.read(String(bytes, Charsets.UTF_8))
    }
}.getOrNull()

/**
 * Makes or changes a look of your own. Every change shows at once across the app (the sample at the top, and this
 * page itself); leaving without saving puts the look that was in use back.
 */
@Composable
internal fun LookEditorScreen(appearance: AppearanceState, start: LookSpec, savedName: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    // Kept as the look file's text, so an edit survives the screen being made again (turning the phone, or the
    // export picker pushing the app out of memory).
    val beforeText by rememberSaveable { mutableStateOf(LookSpec.export(Look.spec)) }
    var specText by rememberSaveable { mutableStateOf(LookSpec.export(start)) }
    val spec = remember(specText) { LookSpec.read(specText) ?: start }
    var name by rememberSaveable { mutableStateOf(start.name) }
    var saved by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    // Shown at once, so a sound picked is heard as the new one and an imported look doesn't flash the old one first.
    fun set(new: LookSpec) {
        specText = LookSpec.export(new)
        Look.spec = new
    }
    remember { Look.spec = spec; true }
    // Leaving any way but Save puts the old look back.
    DisposableEffect(Unit) { onDispose { if (!saved) Look.spec = LookSpec.read(beforeText) ?: Look.spec } }
    BackHandler(onBack = onDone)

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(LookSpec.export(spec.copy(name = name.trim())).toByteArray()) }
            }.isSuccess
            context.showToast(if (ok) "Look exported" else "Couldn't write the file")
        }
    }

    fun finalName(): String {
        val wanted = name.trim().take(40).ifEmpty { "My look" }
        // A look of your own can't take one of the ten's names.
        return if (UiStyle.entries.any { it.label.equals(wanted, true) }) "$wanted (mine)" else wanted
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(title = if (savedName == null) "New look" else "Edit look", onBack = onDone)
        LookSample(spec)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                InputBox(name, { name = it.take(40) }, "Name", Modifier.fillMaxWidth())
            }
            ChoiceRow("Start from", "every part from one look", UiStyle.entries, null, { it.label }) { set(spec.allFrom(it)) }
            LookPart.entries.forEach { part ->
                ChoiceRow(part.label, part.hint, UiStyle.entries, spec[part], { it.label }) { style ->
                    set(spec.with(part, style))
                    // Hear or feel the part being picked.
                    when (part) {
                        LookPart.Sounds -> LookSounds.done(style)
                        else -> Unit
                    }
                }
            }
            FeelRow()

            SectionTitle("Colours")
            SegmentedRow(
                label = "Colours",
                options = listOf(false, true),
                selected = spec.colors != null,
                optionLabel = { if (it) "Own colours" else "Use my theme" },
                onSelect = { own -> set(spec.copy(colors = if (own) spec.colors ?: colorsOf(theme) else null)) },
                description = "Own colours travel with the look when you export it.",
            )
            spec.colors?.let { colors ->
                colors.all().forEach { (key, hex) ->
                    ColorRow(
                        name = key.replaceFirstChar { it.uppercase() },
                        value = hexInt(hex),
                        expanded = expanded == key,
                        onToggle = { expanded = if (expanded == key) null else key },
                        onChange = { set(spec.copy(colors = colors.with(key, "#%06x".format(it and 0xFFFFFF)))) },
                    )
                }
            }

            SectionTitle("Background")
            ChoiceRow("Scene", "Keep mine uses the one picked in Appearance", listOf<SceneKind?>(null) + SceneKind.entries, spec.scene, { it?.label ?: "Keep mine" }) {
                set(spec.copy(scene = it))
            }

            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                PillButton("Export", onClick = { export.launch(fileName(finalName())) }, filled = false, modifier = Modifier.weight(1f))
                PillButton(
                    "Save & use",
                    onClick = {
                        val done = spec.copy(name = finalName())
                        appearance.saveLook(done, replacing = savedName)
                        appearance.useLook(done)
                        saved = true
                        context.showToast("Saved “${done.name}”")
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                    alive = true,
                )
            }
            if (savedName != null) {
                Spacer(Modifier.height(8.dp))
                ActionRow(
                    "Delete this look",
                    onClick = {
                        appearance.deleteLook(savedName)
                        // Deleting the look in use goes back to its frame's look.
                        if (appearance.lookSpec.name == savedName && appearance.lookSpec.preset == null) {
                            appearance.uiStyle = appearance.lookSpec.frame
                            saved = true
                        }
                        onDone()
                    },
                    color = theme.error,
                )
            }
        }
    }
}

private fun fileName(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "look" } + "." + LookSpec.EXTENSION

private fun colorsOf(theme: AppTheme): LookColors {
    fun hex(c: androidx.compose.ui.graphics.Color) = "#%06x".format(
        ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt(),
    )
    return LookColors(hex(theme.background), hex(theme.surface), hex(theme.foreground), hex(theme.primary), hex(theme.secondary), hex(theme.accent))
}

/** A label and a row of choices. */
@Composable
private fun <T> ChoiceRow(label: String, hint: String, options: List<T>, selected: T?, optionLabel: (T) -> String, onPick: (T) -> Unit) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(label, style = TextStyle(color = theme.ink, fontSize = 14.sp))
            Spacer(Modifier.width(8.dp))
            BasicText(hint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 11.sp))
        }
        Spacer(Modifier.height(6.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(options) { option ->
                val on = option == selected
                BasicText(
                    optionLabel(option),
                    maxLines = 1,
                    style = TextStyle(color = if (on) theme.background else theme.foreground, fontSize = 13.sp),
                    modifier = Modifier
                        .clip(lookShape(14.dp))
                        .background(if (on) theme.primary else theme.surface)
                        .clickable(role = Role.RadioButton) { onPick(option) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** Plays the look's sound and buzz, to try them. */
@Composable
private fun FeelRow() {
    val view = LocalView.current
    ActionRow(
        "Try the sound and vibration",
        onClick = {
            LookSounds.done(Look.spec.sounds)
            LookHaptics.moment(view, Look.spec.haptics)
        },
        description = "Sounds from ${Look.spec.sounds.label}, vibration from ${Look.spec.haptics.label} (if they're on in Appearance)",
    )
}

/** What the look looks like: a card, its words and a menu in the look's frame. */
@Composable
private fun LookSample(spec: LookSpec) {
    val theme = LocalAppTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(lookShape(14.dp))
                .background(theme.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 58.dp, height = 82.dp)
                    .clip(coverShape())
                    .background(Brush.linearGradient(listOf(theme.primary, theme.accent))),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BasicText("Frieren: Beyond Journey's End", maxLines = 2, style = TitleStyle(color = theme.ink, fontSize = 17.sp))
                BasicText("Watching · Fall 2023", style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 12.sp))
                Spacer(Modifier.height(4.dp))
                BasicText("EP 18 / 28", style = NumberStyle(color = theme.primary, fontSize = 13.sp, bold = true))
                Spacer(Modifier.height(6.dp))
                PillButton(LookWords.continueTitle(spec.words), onClick = { LookSounds.tap() })
            }
        }
        BasicText(
            "${spec.summary()}",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp),
        )
    }
}
