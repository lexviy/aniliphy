package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListSync
import com.aniliphy.core.AdvanceMode
import com.aniliphy.core.ReaderOrientation
import com.aniliphy.core.TapZones
import com.aniliphy.manga.DownloadDefault
import com.aniliphy.manga.MangaSettings
import com.aniliphy.manga.sources.MangaSites
import com.aniliphy.manga.PageFit
import com.aniliphy.manga.PagePreload
import com.aniliphy.manga.ReadMark
import com.aniliphy.manga.ReaderBackground
import com.aniliphy.manga.ReadingDirection
import com.aniliphy.manga.ReadingMode
import com.aniliphy.manga.WebtoonGap
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MangaSettingsScreen(settings: MangaSettings, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Manga settings", onBack = onBack)
        ReadingSection(settings)
        DisplaySection(settings)
        SourcesSection(settings)
        OfflineSection(settings)
        ProgressSection(settings)
        val askNotifications = rememberNotificationAsk()
        SectionTitle("Notifications")
        SwitchRow(
            label = "New chapters",
            description = "Notify when a manga you follow gets a new chapter",
            checked = settings.notifyNewChapters,
            onChange = {
                settings.notifyNewChapters = it
                if (it) askNotifications()
            },
        )
        BatteryCheckRow(visible = settings.notifyNewChapters)
    }
}

@Composable
private fun ReadingSection(settings: MangaSettings) {
    SectionTitle("Reading")
    SegmentedRow(
        label = "Reading mode",
        description = "Two pages shows pages side by side",
        options = ReadingMode.entries,
        selected = settings.mode,
        optionLabel = { it.label },
        onSelect = { settings.mode = it },
    )
    SegmentedRow(
        label = "Direction",
        description = "For page modes. Manga is usually read right to left",
        options = ReadingDirection.entries,
        selected = settings.direction,
        optionLabel = { it.label },
        onSelect = { settings.direction = it },
    )
    SwitchRow(
        label = "Webtoon mode for long strips",
        description = "Manhwa and webtoons always open in webtoon mode",
        checked = settings.webtoonForLongStrips,
        onChange = { settings.webtoonForLongStrips = it },
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
}

@Composable
private fun DisplaySection(settings: MangaSettings) {
    SectionTitle("Display")
    ReaderPreview(settings)
    SegmentedRow(
        label = "Page fit",
        options = PageFit.entries,
        selected = settings.fit,
        optionLabel = { it.label },
        onSelect = { settings.fit = it },
    )
    SegmentedRow(
        label = "Background",
        options = ReaderBackground.entries,
        selected = settings.background,
        optionLabel = { it.label },
        onSelect = { settings.background = it },
    )
    SegmentedRow(
        label = "Gap between webtoon pages",
        options = WebtoonGap.entries,
        selected = settings.webtoonGap,
        optionLabel = { it.label },
        onSelect = { settings.webtoonGap = it },
    )
    SliderRow(
        label = "Webtoon width",
        value = settings.webtoonWidth,
        valueRange = MIN_WEBTOON_WIDTH..1f,
        valueLabel = "${(settings.webtoonWidth * 100).roundToInt()}%",
        onChange = { settings.webtoonWidth = (it * 20).roundToInt() / 20f },
    )
    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
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
    SwitchRow(
        label = "Page indicator",
        description = "Show the page and chapter number",
        checked = settings.pageIndicator,
        onChange = { settings.pageIndicator = it },
    )
}

@Composable
private fun ReaderPreview(settings: MangaSettings) {
    val theme = LocalAppTheme.current
    val background = when (settings.background) {
        ReaderBackground.Black -> Color.Black
        ReaderBackground.White -> Color.White
        ReaderBackground.Theme -> theme.background
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        PreviewFrame("Portrait", 66.dp, 130.dp, background) { fittedPage(settings.fit) }
        PreviewFrame("Landscape", 130.dp, 66.dp, background) { fittedPage(settings.fit) }
        PreviewFrame("Webtoon", 66.dp, 130.dp, background) { webtoonStrip(settings.webtoonGap.dp.dp) }
    }
}

@Composable
private fun PreviewFrame(
    label: String,
    width: Dp,
    height: Dp,
    background: Color,
    content: DrawScope.() -> Unit,
) {
    val theme = LocalAppTheme.current
    val shape = RoundedCornerShape(6.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier
                .size(width, height)
                .clip(shape)
                .border(1.dp, theme.foreground.copy(alpha = 0.4f), shape),
        ) {
            drawRect(background)
            content()
        }
        Spacer(Modifier.height(4.dp))
        BasicText(label, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp))
    }
}

private const val PAGE_ASPECT = 0.7f

private fun DrawScope.fittedPage(fit: PageFit) {
    val byWidth = size.width
    val byHeight = size.height * PAGE_ASPECT
    val width = when (fit) {
        PageFit.Width -> byWidth
        PageFit.Height -> byHeight
        PageFit.Whole -> min(byWidth, byHeight)
    }
    val height = width / PAGE_ASPECT
    val top = ((size.height - height) / 2f).coerceAtLeast(0f)
    mockPage(Offset((size.width - width) / 2f, top), Size(width, height))
}

private fun DrawScope.webtoonStrip(gap: Dp) {
    val height = size.width * 1.2f
    var top = 0f
    while (top < size.height) {
        mockPage(Offset(0f, top), Size(size.width, height))
        top += height + gap.toPx()
    }
}

private fun DrawScope.mockPage(topLeft: Offset, page: Size) {
    val ink = Color(0xFF2B2B30)
    val line = Stroke(1.dp.toPx())
    drawRect(Color(0xFFF2F0EA), topLeft, page)

    fun panel(x: Float, y: Float, width: Float, height: Float, shade: Color? = null) {
        val offset = topLeft + Offset(page.width * x, page.height * y)
        val panelSize = Size(page.width * width, page.height * height)
        if (shade != null) drawRect(shade, offset, panelSize)
        drawRect(ink, offset, panelSize, style = line)
    }
    panel(0.08f, 0.06f, 0.84f, 0.3f, Color(0xFFC9C7C2))
    panel(0.08f, 0.39f, 0.4f, 0.26f)
    panel(0.52f, 0.39f, 0.4f, 0.26f, Color(0xFF8E8D8A))
    panel(0.08f, 0.68f, 0.84f, 0.26f)
}

@Composable
private fun SourcesSection(settings: MangaSettings) {
    SectionTitle("Sources")
    PreferredSourceRow("manga", rememberSources { MangaSites.all() }, settings.source) { settings.source = it }
    SegmentedRow(
        label = "Pages to load ahead",
        description = "More pages load faster but use more data",
        options = PagePreload.entries,
        selected = settings.preload,
        optionLabel = { it.label },
        onSelect = { settings.preload = it },
    )
    SwitchRow(
        label = "Ask before using mobile data",
        description = "Confirm before a chapter opens without Wi-Fi",
        checked = settings.askOnMobileData,
        onChange = { settings.askOnMobileData = it },
    )
}

@Composable
private fun OfflineSection(settings: MangaSettings) {
    SectionTitle("Offline")
    SwitchRow(
        label = "Download only on Wi-Fi",
        checked = settings.downloadOnWifiOnly,
        onChange = { settings.downloadOnWifiOnly = it },
    )
    SegmentedRow(
        label = "Download button",
        description = "What it saves by default",
        options = DownloadDefault.entries,
        selected = settings.downloadDefault,
        optionLabel = { it.label },
        onSelect = { settings.downloadDefault = it },
    )
    SwitchRow(
        label = "Delete chapters after reading",
        description = "Free up space once a downloaded chapter is read",
        checked = settings.deleteAfterReading,
        onChange = { settings.deleteAfterReading = it },
    )
}

@Composable
private fun ProgressSection(settings: MangaSettings) {
    SectionTitle("Progress")
    SegmentedRow(
        label = "Chapter counts as read at",
        options = ReadMark.entries,
        selected = settings.readMark,
        optionLabel = { it.label },
        onSelect = { settings.readMark = it },
    )
    SegmentedRow(
        label = "Update AniList",
        options = AniListSync.entries,
        selected = settings.aniListSync,
        optionLabel = { it.label },
        onSelect = { settings.aniListSync = it },
    )
}
