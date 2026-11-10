package com.aniliphy.novels

import android.content.Context
import com.aniliphy.anilist.AniListSync
import com.aniliphy.core.AdvanceMode
import com.aniliphy.core.PrefStore
import com.aniliphy.core.ReaderOrientation
import com.aniliphy.core.TapZones

enum class NovelReadingMode(val label: String) { Pages("Pages"), Scroll("Scroll") }

enum class NovelFont(val label: String) {
    Serif("Serif"),
    SansSerif("Sans serif"),
    Book("From the book"),
}

enum class LineSpacing(val label: String, val factor: Float) {
    Compact("Compact", 1.25f),
    Normal("Normal", 1.5f),
    Wide("Wide", 1.8f),
}

enum class NovelMargins(val label: String, val dp: Int) {
    Narrow("Narrow", 10),
    Normal("Normal", 20),
    Wide("Wide", 34),
}

enum class NovelAlignment(val label: String) { Left("Left"), Justified("Justified") }

enum class NovelBackground(val label: String) {
    Theme("Theme"),
    White("White"),
    Sepia("Sepia"),
    Black("Black"),
}

enum class NovelIndicator(val label: String) {
    Chapter("Chapter"),
    Percent("Percent"),
    Off("Off"),
}

enum class BookFormat(val label: String) { Epub("EPUB"), Pdf("PDF") }

enum class ChapterCounting(val label: String) { Automatic("Automatic"), Manual("Manual") }

const val NOVEL_FONT_SIZE_MIN = 12
const val NOVEL_FONT_SIZE_MAX = 30

class NovelSettings(context: Context) : PrefStore(context, "novels") {
    var mode by enumPref("mode", NovelReadingMode.Pages, NovelReadingMode.entries)
    var tapZones by enumPref("tap_zones", TapZones.Normal, TapZones.entries)
    var volumeButtons by boolPref("volume_buttons", false)
    var nextChapter by enumPref("next_chapter", AdvanceMode.Automatic, AdvanceMode.entries)
    var nextVolume by enumPref("next_volume", AdvanceMode.Ask, AdvanceMode.entries)
    var keepScreenOn by boolPref("keep_screen_on", true)
    var controlsSize by floatPref("controls_size", 1f)
    var orientation by enumPref("orientation", ReaderOrientation.FollowPhone, ReaderOrientation.entries)

    var fontSize by intPref("font_size", 17)
    var font by enumPref("font", NovelFont.Serif, NovelFont.entries)
    var lineSpacing by enumPref("line_spacing", LineSpacing.Normal, LineSpacing.entries)
    var margins by enumPref("margins", NovelMargins.Normal, NovelMargins.entries)
    var alignment by enumPref("alignment", NovelAlignment.Justified, NovelAlignment.entries)
    var background by enumPref("background", NovelBackground.Theme, NovelBackground.entries)
    var showIllustrations by boolPref("show_illustrations", true)

    var indicator by enumPref("indicator", NovelIndicator.Percent, NovelIndicator.entries)
    var clockAndBattery by boolPref("clock_and_battery", false)

    var folderUri by stringPref("folder_uri", "")
    var preferredFormat by enumPref("preferred_format", BookFormat.Epub, BookFormat.entries)

    var counting by enumPref("counting", ChapterCounting.Automatic, ChapterCounting.entries)
    var aniListSync by enumPref("anilist_sync", AniListSync.Automatic, AniListSync.entries)

    companion object {
        @Volatile private var instance: NovelSettings? = null

        fun get(context: Context): NovelSettings = instance ?: synchronized(this) {
            instance ?: NovelSettings(context.applicationContext).also { instance = it }
        }

        fun forget() {
            instance = null
        }
    }
}
