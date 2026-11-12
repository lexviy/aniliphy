package com.aniliphy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private class GuidePage(val title: String, val sections: List<Pair<String, List<String>>>)

private val GUIDE = listOf(
    GuidePage(
        "General",
        listOf(
            "Getting started" to listOf(
                "Log in with your AniList account: create an app at anilist.co/settings/developer, paste its Client ID, open the token link and paste the token back.",
                "Or continue as a guest. Guests can watch and read too, but progress stays on this phone, and lists, favourites, the profile and new episode alerts need an account.",
            ),
            "The tabs" to listOf(
                "Anilist: your AniList, with the sections in the menu at the top left.",
                "Anime, Manga and Light Novel: what you're watching and reading, a row per day, the most recent first. Tap a cover to carry on.",
                "Settings: appearance, the settings of each part, storage and this guide.",
            ),
            "AniList sections" to listOf(
                "Home shows what's airing from your list, new manga chapters and the seasons. Pull down to refresh.",
                "Anime, Manga and Light Novel list your entries, by status. Their search looks in your list only.",
                "Home, Characters, Staff and Studios search all of AniList.",
                "Profile has your overview, stats and favourites.",
            ),
            "Title pages" to listOf(
                "Watch or Read starts the anime, manga or light novel.",
                "Edit entry changes the status, progress, score and repeats on AniList. The star adds or removes a favourite.",
                "A circled arrow with a number on a cover counts the times you watched or read it again.",
            ),
        ),
    ),
    GuidePage(
        "Anime",
        listOf(
            "Watching" to listOf(
                "Watch opens the episode after your AniList progress: with 12 watched, it opens 13.",
                "If you stopped in the middle of an episode, it carries on from there.",
                "The first time, pick the right show from the search results. It's remembered; the Source button in the player searches again or forgets it.",
                "Episodes come from the sources of your extensions (Settings → Extensions), starting with Preferred source in Anime settings. When one won't play, the others are tried by themselves.",
            ),
            "The player" to listOf(
                "Tap to show the controls. Double tap the sides to seek, the middle to pause.",
                "Swipe up or down on the left for brightness, on the right for volume. Swipe sideways to seek.",
                "Options has the speed, quality, Sub or Dub, subtitles, video size and skipping of the opening and ending.",
                "The next episode starts by itself after the autoplay delay (Anime settings).",
                "Picture-in-picture and Background audio, in Anime settings, keep the video or its sound going when you leave the app.",
            ),
            "Progress" to listOf(
                "When most of an episode is watched (80% unless changed), AniList progress goes up. With Update AniList on Ask first, you're asked before.",
                "Planning, Paused or Dropped anime with progress carry on and become Watching. Completed anime start again as Rewatching.",
                "The Anime tab marks an anime with New episode when one came out since you last opened it, by AniList's schedule.",
            ),
            "Downloads" to listOf(
                "Download in the player saves episodes with their subtitles, at the quality in Anime settings, on Wi-Fi only unless changed.",
                "Without a connection, the player lists the episodes you saved.",
            ),
        ),
    ),
    GuidePage(
        "Manga",
        listOf(
            "Reading" to listOf(
                "Read opens the chapter after your AniList progress: with 130 read, it opens 131. If you stopped in a chapter, it opens there.",
                "Chapters come from the sources of your extensions (Settings → Extensions), starting with Preferred source in Manga settings. The others are tried when it doesn't have the manga.",
                "The first time, pick the right series. It's remembered; the Source button searches again or forgets it.",
            ),
            "The reader" to listOf(
                "Tap the sides to turn the page and the middle for the controls. Swiping back on the first page goes to the end of the previous chapter.",
                "Chapters lists them all. Options has the reading mode (one page, two pages or webtoon), the direction, the page fit and the background.",
                "In two pages, First page Alone or Paired shifts the pairing when a two-page picture is split.",
                "Volume buttons and tap zones are in Manga settings.",
            ),
            "Offline" to listOf(
                "Download saves this chapter or the next 5, on Wi-Fi only unless changed in Manga settings.",
                "Delete chapters after reading, in Manga settings, removes them as you go; Storage deletes them all.",
                "Without a connection, the reader lists only the chapters you saved.",
            ),
            "Progress and new chapters" to listOf(
                "Finishing a chapter updates AniList (or asks first, with Update AniList on Ask first).",
                "The Manga tab marks a manga with New chapter when its source has one since you last opened it, and Home lists them under New chapters.",
            ),
        ),
    ),
    GuidePage(
        "Light novels",
        listOf(
            "Your books" to listOf(
                "Light novels are read from EPUB and PDF files in a folder you choose (Light Novel settings or the Light Novel tab).",
                "Files are matched by title, so keep the series name in the file name, like Classroom of the Elite Vol. 1.epub.",
                "Find more volumes looks for the series on the light novel sources of your extensions, to download it in the browser and save it to the folder.",
                "Add puts in a book that isn't on AniList, with its own files. Link to AniList ties a book to its AniList entry, for progress.",
            ),
            "Reading" to listOf(
                "A book opens where you stopped. Chapters and Volumes move around it.",
                "Text size, font, margins, the reading mode and tap zones are in Light Novel settings.",
            ),
            "AniList progress" to listOf(
                "The reader counts the chapters for AniList by itself, skipping the cover and extras.",
                "With Chapter counting on Manual, you mark where counting starts and stops. Reset count starts this volume again.",
            ),
        ),
    ),
    GuidePage(
        "Alerts & data",
        listOf(
            "Notifications" to listOf(
                "Every half hour, with a connection, the app looks for new episodes of what you're watching and new chapters of what you're reading, even when closed.",
                "Each one is told once. Tap it to open the Anime or Manga tab. Android may space the checks out to save battery.",
                "Chapters are looked for in manga read here, and in those you're reading on AniList when a source has a series with exactly the same title. New episodes need an account.",
                "Turn them off under Notifications in Anime settings and Manga settings.",
                "If Android saves battery on the app, Check on time shows there: tap it to let the checks run every half hour.",
            ),
            "Where things are kept" to listOf(
                "Everything stays in the app's own storage: the login, settings, progress, saved sources and downloads.",
                "Storage shows the space used, clears the cache, exports or imports the settings and resets the app.",
                "Storage → Downloads lists the saved episodes and chapters by title, with their size, to delete one or a whole title.",
            ),
            "Sites" to listOf(
                "When a site answers with a robot check, it opens in a small window. Pass it and the window closes; then try again.",
                "If a site changes, the message says so. The app then needs an update for that site.",
                "When an episode's host stops, the player moves to another server by itself, from the same moment.",
            ),
        ),
    ),
)

@Composable
fun GuideScreen(onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(title = "Guide", onBack = onBack)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(GUIDE) { index, page ->
                PillButton(page.title, onClick = { tab = index }, filled = index == tab)
            }
        }
        key(tab) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                GUIDE[tab].sections.forEach { (heading, points) ->
                    SectionTitle(heading)
                    points.forEach { point ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
                            BasicText(
                                "•",
                                style = TextStyle(color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.width(16.dp),
                            )
                            BasicText(
                                point,
                                style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}
