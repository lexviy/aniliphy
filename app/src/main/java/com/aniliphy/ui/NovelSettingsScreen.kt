package com.aniliphy.ui

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListSync
import com.aniliphy.core.AdvanceMode
import com.aniliphy.core.ReaderOrientation
import com.aniliphy.core.TapZones
import com.aniliphy.novels.BookFormat
import com.aniliphy.novels.ChapterCounting
import com.aniliphy.novels.LineSpacing
import com.aniliphy.novels.NOVEL_FONT_SIZE_MAX
import com.aniliphy.novels.NOVEL_FONT_SIZE_MIN
import com.aniliphy.novels.NovelAlignment
import com.aniliphy.novels.NovelBackground
import com.aniliphy.novels.NovelFont
import com.aniliphy.novels.NovelIndicator
import com.aniliphy.novels.NovelMargins
import com.aniliphy.novels.NovelReadingMode
import com.aniliphy.novels.NovelSettings
import kotlin.math.roundToInt

@Composable
fun NovelSettingsScreen(settings: NovelSettings, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Light Novel settings", onBack = onBack)
        ReadingSection(settings)
        TextSection(settings)
        DisplaySection(settings)
        LibrarySection(settings)
        ProgressSection(settings)
    }
}

@Composable
private fun ReadingSection(settings: NovelSettings) {
    SectionTitle("Reading")
    SegmentedRow(
        label = "Reading mode",
        options = NovelReadingMode.entries,
        selected = settings.mode,
        optionLabel = { it.label },
        onSelect = { settings.mode = it },
    )
    SegmentedRow(
        label = "Tap zones",
        description = "Tap the sides of the screen to change page",
        options = TapZones.entries,
        selected = settings.tapZones,
        optionLabel = { it.label },
        onSelect = { settings.tapZones = it },
    )
    SwitchRow(
        label = "Volume buttons",
        description = "Change page with the volume buttons",
        checked = settings.volumeButtons,
        onChange = { settings.volumeButtons = it },
    )
    SegmentedRow(
        label = "Next chapter",
        description = "What happens at the end of a chapter",
        options = AdvanceMode.entries,
        selected = settings.nextChapter,
        optionLabel = { it.label },
        onSelect = { settings.nextChapter = it },
    )
    SegmentedRow(
        label = "Next volume",
        description = "What happens at the end of a volume",
        options = AdvanceMode.entries,
        selected = settings.nextVolume,
        optionLabel = { it.label },
        onSelect = { settings.nextVolume = it },
    )
    SegmentedRow(
        label = "Orientation",
        options = ReaderOrientation.entries,
        selected = settings.orientation,
        optionLabel = { it.label },
        onSelect = { settings.orientation = it },
    )
    SwitchRow(
        label = "Keep screen on",
        description = "Don't let the screen turn off while reading",
        checked = settings.keepScreenOn,
        onChange = { settings.keepScreenOn = it },
    )
}

@Composable
internal fun TextSection(settings: NovelSettings) {
    val theme = LocalAppTheme.current
    SectionTitle("Text")
    BasicText(
        text = "Font, spacing, margins and alignment only change EPUB books. A PDF always keeps its own layout.",
        style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
    ReaderPreview(settings)
    SliderRow(
        label = "Text size",
        value = settings.fontSize.toFloat(),
        valueRange = NOVEL_FONT_SIZE_MIN.toFloat()..NOVEL_FONT_SIZE_MAX.toFloat(),
        valueLabel = "${settings.fontSize}",
        onChange = { settings.fontSize = it.roundToInt() },
    )
    SegmentedRow(
        label = "Font",
        options = NovelFont.entries,
        selected = settings.font,
        optionLabel = { it.label },
        onSelect = { settings.font = it },
    )
    SegmentedRow(
        label = "Line spacing",
        options = LineSpacing.entries,
        selected = settings.lineSpacing,
        optionLabel = { it.label },
        onSelect = { settings.lineSpacing = it },
    )
    SegmentedRow(
        label = "Margins",
        options = NovelMargins.entries,
        selected = settings.margins,
        optionLabel = { it.label },
        onSelect = { settings.margins = it },
    )
    SegmentedRow(
        label = "Alignment",
        options = NovelAlignment.entries,
        selected = settings.alignment,
        optionLabel = { it.label },
        onSelect = { settings.alignment = it },
    )
    SegmentedRow(
        label = "Background",
        options = NovelBackground.entries,
        selected = settings.background,
        optionLabel = { it.label },
        onSelect = { settings.background = it },
    )
}

private const val SAMPLE_TEXT =
    "The rain had stopped by the time the last train pulled away, leaving the platform " +
        "quiet and the air smelling of wet iron. Nobody else had stepped off. Somewhere " +
        "past the fence, a lantern swayed once and went out."

@Composable
private fun ReaderPreview(settings: NovelSettings) {
    val theme = LocalAppTheme.current
    val (paper, ink) = when (settings.background) {
        NovelBackground.Theme -> theme.background to theme.foreground
        NovelBackground.White -> Color.White to Color(0xFF222222)
        NovelBackground.Sepia -> Color(0xFFF4ECD8) to Color(0xFF5B4636)
        NovelBackground.Black -> Color.Black to Color(0xFFD8D8D8)
    }
    val family = when (settings.font) {
        NovelFont.Serif -> FontFamily.Serif
        NovelFont.SansSerif -> FontFamily.SansSerif
        NovelFont.Book -> FontFamily.Default
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(270.dp)
            .clip(shape)
            .background(paper)
            .border(1.dp, theme.foreground.copy(alpha = 0.4f), shape),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = settings.margins.dp.dp, vertical = 12.dp),
        ) {
            if (settings.showIllustrations) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF3E5573), Color(0xFF8D6E7E), Color(0xFFD4A373)))),
                )
                Spacer(Modifier.height(10.dp))
            }
            BasicText(
                text = SAMPLE_TEXT,
                overflow = TextOverflow.Clip,
                style = TextStyle(
                    color = ink,
                    fontFamily = family,
                    fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * settings.lineSpacing.factor).sp,
                    textAlign = if (settings.alignment == NovelAlignment.Justified) TextAlign.Justify else TextAlign.Start,
                ),
            )
        }
        val status = TextStyle(color = ink.copy(alpha = 0.65f), fontSize = 11.sp)
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .background(paper)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            BasicText(
                when (settings.indicator) {
                    NovelIndicator.Chapter -> "Chapter 3"
                    NovelIndicator.Percent -> "42%"
                    NovelIndicator.Off -> ""
                },
                style = status,
            )
            if (settings.clockAndBattery) BasicText("21:30 · 80%", style = status)
        }
    }
}

@Composable
private fun DisplaySection(settings: NovelSettings) {
    SectionTitle("Display")
    SwitchRow(
        label = "Show illustrations",
        description = "Turn off to keep only the text of EPUB books",
        checked = settings.showIllustrations,
        onChange = { settings.showIllustrations = it },
    )
    SegmentedRow(
        label = "Progress indicator",
        options = NovelIndicator.entries,
        selected = settings.indicator,
        optionLabel = { it.label },
        onSelect = { settings.indicator = it },
    )
    SwitchRow(
        label = "Clock and battery",
        description = "Show them at the bottom of the reader",
        checked = settings.clockAndBattery,
        onChange = { settings.clockAndBattery = it },
    )
    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
}

@Composable
private fun LibrarySection(settings: NovelSettings) {
    val theme = LocalAppTheme.current
    val resolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            keepFolderAccess(resolver, uri, previous = settings.folderUri)
            settings.folderUri = uri.toString()
        }
    }
    SectionTitle("Library")
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { picker.launch(null) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        BasicText("Novels folder", style = TextStyle(color = theme.foreground, fontSize = 15.sp))
        BasicText(
            folderLabel(settings.folderUri),
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
        )
    }
    SegmentedRow(
        label = "Preferred format",
        description = "Used when a volume exists in both formats",
        options = BookFormat.entries,
        selected = settings.preferredFormat,
        optionLabel = { it.label },
        onSelect = { settings.preferredFormat = it },
    )
}

internal fun keepFolderAccess(resolver: ContentResolver, uri: Uri, previous: String) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    resolver.takePersistableUriPermission(uri, flags)
    if (previous.isNotEmpty() && previous != uri.toString()) {
        runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous), flags) }
    }
}

private fun folderLabel(uri: String): String {
    if (uri.isEmpty()) return "Not chosen. Tap to choose one"
    val path = Uri.parse(uri).lastPathSegment?.substringAfter(':', "").orEmpty()
    return path.ifEmpty { "Internal storage" }
}

@Composable
private fun ProgressSection(settings: NovelSettings) {
    SectionTitle("Progress")
    SegmentedRow(
        label = "Chapter counting",
        description = "Automatic skips the cover and extras. Manual lets you mark where counting starts and stops",
        options = ChapterCounting.entries,
        selected = settings.counting,
        optionLabel = { it.label },
        onSelect = { settings.counting = it },
    )
    SegmentedRow(
        label = "Update AniList",
        options = AniListSync.entries,
        selected = settings.aniListSync,
        optionLabel = { it.label },
        onSelect = { settings.aniListSync = it },
    )
    ImportProgress()
}

@Composable
private fun ImportProgress() {
    val novels = LocalNovels.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                try {
                    context.showToast(novels.importDesktopProgress(uri))
                } finally {
                    busy = false
                }
            }
        }
    }
    Hint("From the computer's ~/.config/anilixy/novel_progress.json. Books read more recently here stay as they are.")
    PillButton(
        if (busy) "Importing..." else "Import desktop progress",
        onClick = { picker.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        enabled = !busy,
        filled = false,
    )
}
