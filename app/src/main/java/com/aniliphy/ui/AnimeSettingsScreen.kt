package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListSync
import com.aniliphy.anime.AUTOPLAY_DELAY_MAX
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.source.AudioVersion
import com.aniliphy.anime.DoubleTapSeek
import com.aniliphy.anime.MobileQuality
import com.aniliphy.anime.PlaybackSpeed
import com.aniliphy.anime.PlayerOrientation
import com.aniliphy.anime.SkipSegments
import com.aniliphy.anime.SubtitleBackground
import com.aniliphy.anime.SubtitleColor
import com.aniliphy.anime.SubtitleLanguage
import com.aniliphy.anime.SubtitleSize
import com.aniliphy.anime.VideoFit
import com.aniliphy.anime.VideoQuality
import com.aniliphy.anime.WATCHED_PERCENT_MAX
import com.aniliphy.anime.WATCHED_PERCENT_MIN
import com.aniliphy.anime.WATCHED_PERCENT_STEP
import kotlin.math.roundToInt

@Composable
fun AnimeSettingsScreen(settings: AnimeSettings, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Anime settings", onBack = onBack)
        SectionTitle("Sources")
        PreferredSourceRow("anime", rememberSources { AnimeSites.all() }, settings.source) { settings.source = it }
        QualitySection(settings)
        LanguageSection(settings)
        PlaybackSection(settings)
        ScreenSection(settings)
        SubtitlesSection(settings)
        ProgressSection(settings)
        val askNotifications = rememberNotificationAsk()
        SectionTitle("Notifications")
        SwitchRow(
            label = "New episodes",
            description = "Notify when an anime you follow gets a new episode",
            checked = settings.notifyNewEpisodes,
            onChange = {
                settings.notifyNewEpisodes = it
                if (it) askNotifications()
            },
        )
        BatteryCheckRow(visible = settings.notifyNewEpisodes)
    }
}

@Composable
private fun QualitySection(settings: AnimeSettings) {
    SectionTitle("Quality")
    SegmentedRow(
        label = "Video quality",
        options = VideoQuality.entries,
        selected = settings.quality,
        optionLabel = { it.label },
        onSelect = { settings.quality = it },
    )
    SegmentedRow(
        label = "Quality on mobile data",
        description = "Used without Wi-Fi. Same keeps the quality above",
        options = MobileQuality.entries,
        selected = settings.mobileQuality,
        optionLabel = { it.label },
        onSelect = { settings.mobileQuality = it },
    )
    SwitchRow(
        label = "Ask before using mobile data",
        description = "Confirm before an episode starts without Wi-Fi",
        checked = settings.askOnMobileData,
        onChange = { settings.askOnMobileData = it },
    )
    SwitchRow(
        label = "Download on Wi-Fi only",
        description = "Episodes are saved with the quality above",
        checked = settings.downloadOnWifiOnly,
        onChange = { settings.downloadOnWifiOnly = it },
    )
}

@Composable
private fun LanguageSection(settings: AnimeSettings) {
    SectionTitle("Language")
    SegmentedRow(
        label = "Version",
        options = AudioVersion.entries,
        selected = settings.audio,
        optionLabel = { it.label },
        onSelect = { settings.audio = it },
    )
}

@Composable
private fun PlaybackSection(settings: AnimeSettings) {
    SectionTitle("Playback")
    SliderRow(
        label = "Autoplay delay",
        value = settings.autoplayDelay.toFloat(),
        valueRange = 0f..AUTOPLAY_DELAY_MAX.toFloat(),
        valueLabel = if (settings.autoplayDelay == 0) "No wait" else "${settings.autoplayDelay} s",
        onChange = { settings.autoplayDelay = it.roundToInt() },
    )
    SegmentedRow(
        label = "Default speed",
        options = PlaybackSpeed.entries,
        selected = settings.speed,
        optionLabel = { it.label },
        onSelect = { settings.speed = it },
    )
    SegmentedRow(
        label = "Skip opening and ending",
        description = "Only for episodes with known times",
        options = SkipSegments.entries,
        selected = settings.skipSegments,
        optionLabel = { it.label },
        onSelect = { settings.skipSegments = it },
    )
    SegmentedRow(
        label = "Double tap to seek",
        options = DoubleTapSeek.entries,
        selected = settings.doubleTapSeek,
        optionLabel = { it.label },
        onSelect = { settings.doubleTapSeek = it },
    )
    SwitchRow(
        label = "Picture-in-picture",
        description = "Keep the video in a small window when leaving the app",
        checked = settings.pictureInPicture,
        onChange = { settings.pictureInPicture = it },
    )
    SwitchRow(
        label = "Background audio",
        description = "Keep playing the sound with the screen locked",
        checked = settings.backgroundAudio,
        onChange = { settings.backgroundAudio = it },
    )
}

@Composable
private fun ScreenSection(settings: AnimeSettings) {
    SectionTitle("Screen")
    SwitchRow(
        label = "Brightness and volume gestures",
        description = "Swipe up or down on the sides of the player",
        checked = settings.gestures,
        onChange = { settings.gestures = it },
    )
    SegmentedRow(
        label = "Orientation",
        options = PlayerOrientation.entries,
        selected = settings.orientation,
        optionLabel = { it.label },
        onSelect = { settings.orientation = it },
    )
    SegmentedRow(
        label = "Video size",
        description = "Fit shows the whole video, Fill crops it to cover the screen",
        options = VideoFit.entries,
        selected = settings.fit,
        optionLabel = { it.label },
        onSelect = { settings.fit = it },
    )
    ControlsSizeRow(settings.controlsSize) { settings.controlsSize = it }
}

@Composable
private fun SubtitlesSection(settings: AnimeSettings) {
    SectionTitle("Subtitles")
    SubtitlePreview(settings)
    SegmentedRow(
        label = "Preferred language",
        description = "Used when the source has it",
        options = SubtitleLanguage.entries,
        selected = settings.subtitleLanguage,
        optionLabel = { it.label },
        onSelect = { settings.subtitleLanguage = it },
    )
    SegmentedRow(
        label = "Size",
        options = SubtitleSize.entries,
        selected = settings.subtitleSize,
        optionLabel = { it.label },
        onSelect = { settings.subtitleSize = it },
    )
    SegmentedRow(
        label = "Color",
        options = SubtitleColor.entries,
        selected = settings.subtitleColor,
        optionLabel = { it.label },
        onSelect = { settings.subtitleColor = it },
    )
    SegmentedRow(
        label = "Background",
        options = SubtitleBackground.entries,
        selected = settings.subtitleBackground,
        optionLabel = { it.label },
        onSelect = { settings.subtitleBackground = it },
    )
}

@Composable
private fun SubtitlePreview(settings: AnimeSettings) {
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(120.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF27324A), Color(0xFF5B4257), Color(0xFF8A6A4F))))
            .padding(10.dp),
    ) {
        val box = settings.subtitleBackground == SubtitleBackground.Box
        BasicText(
            text = "This is how subtitles will look.",
            style = TextStyle(
                color = Color(settings.subtitleColor.rgb),
                fontSize = settings.subtitleSize.sp.sp,
                textAlign = TextAlign.Center,
            ),
            modifier = if (box) {
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            } else {
                Modifier
            },
        )
    }
}

@Composable
private fun ProgressSection(settings: AnimeSettings) {
    SectionTitle("Progress")
    SliderRow(
        label = "Counts as watched at",
        value = settings.watchedPercent.toFloat(),
        valueRange = WATCHED_PERCENT_MIN.toFloat()..WATCHED_PERCENT_MAX.toFloat(),
        valueLabel = "${settings.watchedPercent}%",
        onChange = {
            settings.watchedPercent = (it / WATCHED_PERCENT_STEP).roundToInt() * WATCHED_PERCENT_STEP
        },
    )
    SegmentedRow(
        label = "Update AniList",
        options = AniListSync.entries,
        selected = settings.aniListSync,
        optionLabel = { it.label },
        onSelect = { settings.aniListSync = it },
    )
}
