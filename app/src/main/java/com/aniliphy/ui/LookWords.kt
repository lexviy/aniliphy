package com.aniliphy.ui

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What each look says in its own words: notifications, sync, empty lists, the end of a list, day headings, buttons.
 * Plain functions, so the background worker can use them and tests can check them. A null look means Look moments are
 * off, and the plain wording is used. Titles and numbers are always kept whole, whatever the look.
 */
internal object LookWords {
    /** The look to word things in, or null for plain words when Look moments are off. */
    fun of(appearance: AppearanceState): UiStyle? = appearance.uiStyle.takeIf { appearance.lookMoments }

    /** A new chapter or episode out: the notification's title and text. */
    fun release(style: UiStyle?, chapter: Boolean, title: String, number: String): Pair<String, String> {
        val unit = if (chapter) "chapter" else "episode"
        val short = if (chapter) "Ch. $number" else "Ep. $number"
        return when (style) {
            null, UiStyle.Modern, UiStyle.Glass -> (if (chapter) "New chapter" else "New episode") to "$title — $unit $number"
            UiStyle.Tankobon -> (if (chapter) "Next chapter!" else "Next episode!") to "$title — $short is out. つづく!"
            UiStyle.Genga -> "New cut in" to "$title — $unit $number, ready for checking"
            UiStyle.Bunko -> (if (chapter) "Freshly printed" else "A new episode") to "$title, $unit $number, is out"
            UiStyle.Shinya -> (if (chapter) "Late edition" else "Now on air") to "$title — $unit $number"
            UiStyle.Jrpg -> "A wild $unit appeared!" to "$title — $unit $number"
            UiStyle.Eki -> "Arriving now" to "$title — $short has arrived at platform 1"
            UiStyle.Konbini -> "New in stock" to "1 x $title — $unit $number ... ¥0"
            UiStyle.Stamp -> "New stamp to collect" to "$title — $unit $number"
        }
    }

    /** The title of the "carry on" notification. */
    fun continueTitle(style: UiStyle?): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass -> "Continue"
        UiStyle.Tankobon -> "To be continued"
        UiStyle.Genga -> "Next cut"
        UiStyle.Bunko -> "Your bookmarks"
        UiStyle.Shinya -> "Coming up"
        UiStyle.Jrpg -> "Continue?"
        UiStyle.Eki -> "Next stop"
        UiStyle.Konbini -> "Your basket"
        UiStyle.Stamp -> "Stamps to collect"
    }

    /** The sync button while a sync runs. */
    fun syncing(style: UiStyle?): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass, UiStyle.Bunko -> "Syncing…"
        UiStyle.Tankobon -> "Sending the pages…"
        UiStyle.Genga -> "Sending to the studio…"
        UiStyle.Shinya -> "Transmitting…"
        UiStyle.Jrpg -> "Saving… Don't turn off the power"
        UiStyle.Eki -> "Transferring…"
        UiStyle.Konbini -> "Ringing up…"
        UiStyle.Stamp -> "Stamping…"
    }

    /** "Synced." at the start of a finished sync's message. */
    fun synced(style: UiStyle?): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass, UiStyle.Bunko, UiStyle.Genga -> "Synced."
        UiStyle.Tankobon -> "Synced!"
        UiStyle.Shinya -> "Signal received."
        UiStyle.Jrpg -> "Game saved."
        UiStyle.Eki -> "Transfer complete."
        UiStyle.Konbini -> "Thank you."
        UiStyle.Stamp -> "Stamped."
    }

    /** A search or filter with no results. */
    fun nothingFound(style: UiStyle?): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass -> "Nothing found"
        UiStyle.Tankobon -> "…Nothing found."
        UiStyle.Genga -> "Nothing found. Retake?"
        UiStyle.Bunko -> "No such title on these shelves"
        UiStyle.Shinya -> "No signal. Nothing found"
        UiStyle.Jrpg -> "No one by that name in this town"
        UiStyle.Eki -> "No such station"
        UiStyle.Konbini -> "Out of stock"
        UiStyle.Stamp -> "No stamp for that"
    }

    /** Under the last item of a list. */
    fun endOfList(style: UiStyle?): String? = when (style) {
        null -> null
        UiStyle.Modern, UiStyle.Glass -> "That's everything"
        UiStyle.Tankobon -> "To be continued"
        UiStyle.Genga -> "END OF SHEET"
        UiStyle.Bunko -> "完 · The end"
        UiStyle.Shinya -> "END OF BROADCAST"
        UiStyle.Jrpg -> "No more items."
        UiStyle.Eki -> "END OF LINE"
        UiStyle.Konbini -> "THANK YOU FOR SHOPPING"
        UiStyle.Stamp -> "That's every stamp"
    }

    /** How many are on a list, at its top. */
    fun listCount(style: UiStyle?, count: Int, anime: Boolean): String? {
        val titles = if (count == 1) "title" else "titles"
        return when (style) {
            null -> null
            UiStyle.Modern, UiStyle.Glass -> "$count $titles"
            UiStyle.Tankobon -> "Vol. 1 — $count $titles"
            UiStyle.Genga -> "$count CUTS"
            UiStyle.Bunko -> if (anime) "$count reels in the archive" else "$count books on the shelf"
            UiStyle.Shinya -> "$count programmes tonight"
            UiStyle.Jrpg -> "Party: $count ${if (count == 1) "member" else "members"}"
            UiStyle.Eki -> "Departures: $count"
            UiStyle.Konbini -> "Items: $count"
            UiStyle.Stamp -> "$count stamps on the card"
        }
    }

    private val WEEKDAYS = listOf("月", "火", "水", "木", "金", "土", "日")

    /** A day's heading on the Anime and Manga tabs. [plain] is the usual "Yesterday" or "Thursday, 5 November". */
    fun day(style: UiStyle?, day: LocalDate, plain: String, today: LocalDate = LocalDate.now()): String {
        val kanji = WEEKDAYS[day.dayOfWeek.value - 1]
        val short = day.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)).uppercase()
        return when (style) {
            null, UiStyle.Modern, UiStyle.Glass -> plain
            UiStyle.Tankobon -> "$kanji · $plain"
            UiStyle.Genga -> "$short ${day.format(DateTimeFormatter.ofPattern("dd.MM"))} · $plain"
            UiStyle.Bunko -> if (day == today || day == today.minusDays(1)) plain else
                "${day.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH))}, the ${ordinal(day.dayOfMonth)} of ${day.format(DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH))}"
            UiStyle.Shinya -> "${kanji}曜 · $plain"
            UiStyle.Jrpg -> "Day ${day.dayOfYear} · $plain"
            UiStyle.Eki -> "$kanji $short ${day.format(DateTimeFormatter.ofPattern("MM/dd"))} · $plain"
            UiStyle.Konbini -> "${day.format(DateTimeFormatter.ISO_LOCAL_DATE)} ($short)"
            UiStyle.Stamp -> "$plain ($kanji)"
        }
    }

    private fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** The label on the skip button for the intro or the outro. */
    fun skip(style: UiStyle?, intro: Boolean): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass, UiStyle.Bunko -> if (intro) "Skip intro" else "Skip outro"
        UiStyle.Tankobon -> if (intro) "Skip intro ≫" else "Skip outro ≫"
        UiStyle.Genga -> if (intro) "Skip OP ▸▸" else "Skip ED ▸▸"
        UiStyle.Shinya -> if (intro) "Skip OP ▶▶" else "Skip ED ▶▶"
        UiStyle.Jrpg -> if (intro) "Skip scene ▶▶" else "Skip credits ▶▶"
        UiStyle.Eki -> if (intro) "急行 Express ▶" else "急行 Skip ED ▶"
        UiStyle.Konbini -> if (intro) "Express lane ▶" else "Skip receipt ▶"
        UiStyle.Stamp -> if (intro) "Skip intro ◎" else "Skip outro ◎"
    }

    /** The big button on a media page: Watch or Read, in the look's words. */
    fun open(style: UiStyle?, anime: Boolean): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass, UiStyle.Tankobon -> if (anime) "Watch" else "Read"
        UiStyle.Genga -> if (anime) "Play cut" else "Check"
        UiStyle.Bunko -> if (anime) "Watch" else "Open"
        UiStyle.Shinya -> if (anime) "● On air" else "Read"
        UiStyle.Jrpg -> if (anime) "Fight" else "Examine"
        UiStyle.Eki -> "Board"
        UiStyle.Konbini -> if (anime) "Ring up" else "Take one"
        UiStyle.Stamp -> "Stamp in"
    }

    /** The user's own score under a media page's title. */
    fun yourScore(style: UiStyle?): String = when (style) {
        null, UiStyle.Modern, UiStyle.Glass, UiStyle.Tankobon -> "Your score"
        UiStyle.Genga -> "Director's grade"
        UiStyle.Bunko -> "Your review"
        UiStyle.Shinya -> "Your rating"
        UiStyle.Jrpg -> "ATK"
        UiStyle.Eki -> "Your rating"
        UiStyle.Konbini -> "Rating"
        UiStyle.Stamp -> "Your stamp"
    }

    /** A milestone passed, worded for the moment that celebrates it. */
    fun milestone(style: UiStyle?, what: String): String = when (style) {
        UiStyle.Jrpg -> "Achievement: $what"
        UiStyle.Konbini -> "+100 pts · $what"
        UiStyle.Eki -> "Now arriving: $what"
        UiStyle.Stamp -> "Collected: $what"
        else -> what
    }
}
