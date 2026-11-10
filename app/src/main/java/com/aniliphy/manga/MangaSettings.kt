package com.aniliphy.manga

import android.content.Context
import com.aniliphy.anilist.AniListSync
import com.aniliphy.core.AdvanceMode
import com.aniliphy.core.PrefStore
import com.aniliphy.core.ReaderOrientation
import com.aniliphy.core.TapZones
import com.aniliphy.manga.sources.MangaSites

enum class ReadingMode(val label: String) {
    Page("Page"),
    TwoPages("Two pages"),
    Webtoon("Webtoon"),
}

enum class ReadingDirection(val label: String) {
    RightToLeft("Right to left"),
    LeftToRight("Left to right"),
}

enum class PageFit(val label: String) { Width("Width"), Height("Height"), Whole("Whole page") }

enum class ReaderBackground(val label: String) { Black("Black"), White("White"), Theme("Theme") }

enum class WebtoonGap(val label: String, val dp: Int) {
    None("None", 0),
    Small("Small", 4),
    Large("Large", 12),
}

enum class PagePreload(val label: String, val pages: Int) {
    P2("2", 2),
    P5("5", 5),
    P10("10", 10),
}

enum class DownloadDefault(val label: String) {
    Current("Current"),
    NextFive("Next 5"),
    Choose("Choose"),
}

enum class ReadMark(val label: String) {
    LastPageOpens("Last page opens"),
    EndOfLastPage("End of last page"),
}

class MangaSettings(context: Context) : PrefStore(context, "manga") {
    var mode by enumPref("mode", ReadingMode.Page, ReadingMode.entries)
    var direction by enumPref("direction", ReadingDirection.RightToLeft, ReadingDirection.entries)
    var webtoonForLongStrips by boolPref("webtoon_for_long_strips", true)
    var tapZones by enumPref("tap_zones", TapZones.Normal, TapZones.entries)
    var volumeButtons by boolPref("volume_buttons", false)
    var nextChapter by enumPref("next_chapter", AdvanceMode.Automatic, AdvanceMode.entries)

    var fit by enumPref("fit", PageFit.Whole, PageFit.entries)
    var background by enumPref("background", ReaderBackground.Black, ReaderBackground.entries)
    var webtoonGap by enumPref("webtoon_gap", WebtoonGap.None, WebtoonGap.entries)
    var keepScreenOn by boolPref("keep_screen_on", true)
    var orientation by enumPref("orientation", ReaderOrientation.FollowPhone, ReaderOrientation.entries)
    var pageIndicator by boolPref("page_indicator", true)
    var webtoonWidth by floatPref("webtoon_width", 1f)
    var controlsSize by floatPref("controls_size", 1f)

    private var savedSource by stringPref("source", MangaSites.DEFAULT)

    /** The id of the preferred source. Before extensions it was saved by the source's name. */
    var source: String
        get() = savedSource.let { saved ->
            if (MangaSites.find(saved) != null) saved else MangaSites.all().firstOrNull { it.name == saved }?.id ?: saved
        }
        set(value) {
            savedSource = value
        }
    var preload by enumPref("preload", PagePreload.P5, PagePreload.entries)
    var askOnMobileData by boolPref("ask_on_mobile_data", true)

    var downloadOnWifiOnly by boolPref("download_on_wifi_only", true)
    var downloadDefault by enumPref("download_default", DownloadDefault.Choose, DownloadDefault.entries)
    var deleteAfterReading by boolPref("delete_after_reading", false)

    var readMark by enumPref("read_mark", ReadMark.LastPageOpens, ReadMark.entries)
    var aniListSync by enumPref("anilist_sync", AniListSync.Automatic, AniListSync.entries)

    var notifyNewChapters by boolPref("notify_new_chapters", true)

    companion object {
        @Volatile private var instance: MangaSettings? = null

        fun get(context: Context): MangaSettings = instance ?: synchronized(this) {
            instance ?: MangaSettings(context.applicationContext).also { instance = it }
        }

        fun forget() {
            instance = null
        }
    }
}
