package com.aniliphy.anime

import com.aniliphy.source.AudioVersion
import android.content.Context
import com.aniliphy.anilist.AniListSync
import com.aniliphy.anime.sources.AnimeSites
import com.aniliphy.core.PrefStore

enum class VideoQuality(val label: String) {
    Best("Best"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    P360("360p"),
    Worst("Worst"),
}

enum class MobileQuality(val label: String) {
    Same("Same"),
    P1080("1080p"),
    P720("720p"),
    P480("480p"),
    P360("360p"),
    Worst("Worst"),
}

enum class PlaybackSpeed(val label: String, val factor: Float) {
    X050("0.5", 0.5f),
    X075("0.75", 0.75f),
    X100("1", 1f),
    X125("1.25", 1.25f),
    X150("1.5", 1.5f),
    X175("1.75", 1.75f),
    X200("2", 2f),
}

enum class SkipSegments(val label: String) { Off("Off"), Button("Button"), Automatic("Automatic") }

enum class DoubleTapSeek(val label: String, val seconds: Int) {
    S5("5 s", 5),
    S10("10 s", 10),
    S30("30 s", 30),
}

enum class PlayerOrientation(val label: String) { Landscape("Landscape"), FollowPhone("Follow phone") }

enum class VideoFit(val label: String) { Fit("Fit"), Fill("Fill") }

enum class SubtitleSize(val label: String, val sp: Int) {
    Small("Small", 14),
    Normal("Normal", 18),
    Large("Large", 24),
}

enum class SubtitleColor(val label: String, val rgb: Long) {
    White("White", 0xFFFFFFFF),
    Yellow("Yellow", 0xFFFFE066),
    Cyan("Cyan", 0xFF7DE8F2),
}

enum class SubtitleBackground(val label: String) { None("None"), Box("Box") }

enum class SubtitleLanguage(val label: String) {
    English("English"),
    Portuguese("Portuguese"),
    Spanish("Spanish"),
    Off("Off"),
}

const val AUTOPLAY_DELAY_MAX = 30
const val WATCHED_PERCENT_MIN = 70
const val WATCHED_PERCENT_MAX = 95
const val WATCHED_PERCENT_STEP = 5

class AnimeSettings(context: Context) : PrefStore(context, "anime") {
    var controlsSize by floatPref("controls_size", 1f)
    var quality by enumPref("quality", VideoQuality.Best, VideoQuality.entries)
    var mobileQuality by enumPref("mobile_quality", MobileQuality.Same, MobileQuality.entries)
    var askOnMobileData by boolPref("ask_on_mobile_data", true)

    var audio by enumPref("audio", AudioVersion.Sub, AudioVersion.entries)

    /** The id of the preferred source. */
    var source by stringPref("source", AnimeSites.DEFAULT)

    var autoplayDelay by intPref("autoplay_delay", 3)
    var speed by enumPref("speed", PlaybackSpeed.X100, PlaybackSpeed.entries)
    var skipSegments by enumPref("skip_segments", SkipSegments.Button, SkipSegments.entries)
    var doubleTapSeek by enumPref("double_tap_seek", DoubleTapSeek.S10, DoubleTapSeek.entries)
    var pictureInPicture by boolPref("picture_in_picture", false)
    var backgroundAudio by boolPref("background_audio", false)

    var gestures by boolPref("gestures", true)
    var orientation by enumPref("orientation", PlayerOrientation.Landscape, PlayerOrientation.entries)
    var fit by enumPref("fit", VideoFit.Fit, VideoFit.entries)

    var subtitleLanguage by enumPref("subtitle_language", SubtitleLanguage.English, SubtitleLanguage.entries)
    var subtitleSize by enumPref("subtitle_size", SubtitleSize.Normal, SubtitleSize.entries)
    var subtitleColor by enumPref("subtitle_color", SubtitleColor.White, SubtitleColor.entries)
    var subtitleBackground by enumPref("subtitle_background", SubtitleBackground.None, SubtitleBackground.entries)

    var watchedPercent by intPref("watched_percent", 80)
    var aniListSync by enumPref("anilist_sync", AniListSync.Automatic, AniListSync.entries)

    var notifyNewEpisodes by boolPref("notify_new_episodes", true)

    var downloadOnWifiOnly by boolPref("download_wifi_only", true)

    companion object {
        @Volatile private var instance: AnimeSettings? = null

        fun get(context: Context): AnimeSettings = instance ?: synchronized(this) {
            instance ?: AnimeSettings(context.applicationContext).also { instance = it }
        }

        fun forget() {
            instance = null
        }
    }
}
