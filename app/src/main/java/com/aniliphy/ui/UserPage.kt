package com.aniliphy.ui

import android.net.Uri
import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Achievements
import com.aniliphy.anilist.ActivityCalendar
import com.aniliphy.anilist.Compatibility
import com.aniliphy.anilist.Favourites
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.ListComparison
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.ProfileExtras
import com.aniliphy.anilist.SharedTitle
import com.aniliphy.anilist.UserProfile
import com.aniliphy.anilist.ofKind
import com.aniliphy.anilist.plainBio
import java.time.LocalDate

private enum class UserTab(val label: String) {
    Compare("Compare"), Stats("Stats"), Activity("Activity"), Achievements("Achievements"), Favorites("Favorites"),
}

/** Someone else's profile: how your taste compares, and the same screens as your own. */
@Composable
internal fun UserPage(route: Route.User, retained: Retained) {
    val ui = LocalAniList.current
    val profile = retained.get("profile") { ui.load<UserProfile> { ui.api.user(route.id, route.name, it) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader(profile.value?.name ?: route.name ?: "Profile") { ui.navigator.back() }
        val user = profile.value
        when {
            user == null && profile.error != null -> ErrorRow(profile.error!!) { profile.load() }
            user == null -> Loading()
            else -> UserBody(user, retained)
        }
    }
}

@Composable
private fun UserBody(user: UserProfile, retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val tab: MutableState<UserTab> = retained.get("tab") { mutableStateOf(UserTab.Compare) }
    val anime = retained.get("anime") { ui.load<List<ListEntry>> { ui.api.animeList(user.id, it) } }
    val paper = retained.get("paper") { ui.load<List<ListEntry>> { ui.api.mangaList(user.id, it) } }
    val favourites = retained.get("favourites") { ui.load<Favourites>(autoStart = false) { ui.api.favourites(user.id, it) } }
    val more = retained.get("more") {
        ui.load<ProfileExtras> { _ -> Fetched(ProfileExtras(user.bannerImage, user.about, user.stats), false) }
    }
    val extras = retained.get("extras") {
        StatsData(
            animeDetails = ui.load(autoStart = false) { ui.api.animeDetails(user.id, it) },
            paperDetails = ui.load(autoStart = false) { ui.api.mangaDetails(user.id, it) },
            people = ui.load(autoStart = false) { ui.api.animePeople(user.id, it) },
        )
    }
    val mine = ui.onList.entries.values.toList()
    val theirs = listOfNotNull(anime.value, paper.value).flatten()
    val ready = anime.value != null && paper.value != null
    val comparison = remember(mine, theirs, ready) { if (ready && mine.isNotEmpty()) Compatibility.compare(mine, theirs) else null }
    val today = remember { LocalDate.now() }
    val achievements = remember(anime.value, paper.value) {
        val a = anime.value
        val p = paper.value
        if (a == null || p == null) null else Achievements.of(a, p, today) + Achievements.seasons(a)
    }
    val aniListDays = remember(user.stats) { ActivityCalendar.aniListDays(user.stats.activityHistory) }

    if (tab.value == UserTab.Compare) {
        val counts = listOfNotNull(
            anime.value?.size?.let { "$it anime" },
            paper.value?.ofKind(MediaKind.Manga)?.size?.let { "$it manga" },
            paper.value?.ofKind(MediaKind.LightNovel)?.size?.let { "$it light novels" },
        ).joinToString(" · ")
        ProfileCard(
            user.name, user.avatar?.best, counts, user.statistics.anime.episodesWatched, user.statistics.manga.chaptersRead,
            banner = user.bannerImage, bio = remember(user.about) { plainBio(user.about) },
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        FollowButton(user.id, user.isFollowing, user.isFollower)
        FilterChip("AniList ↗", false) { context.openLink("https://anilist.co/user/" + Uri.encode(user.name)) }
        Spacer(Modifier.weight(1f))
        comparison?.let { c ->
            c.percent?.let { CompatRing(it, "match") } ?: c.overlap?.let { CompatRing(it, "overlap") }
        }
    }
    val tabsRow = retained.get("tabsRow") { LazyListState() }
    LazyRow(
        state = tabsRow,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    ) {
        items(UserTab.entries, key = { it.name }) { option ->
            FilterChip(option.label, tab.value == option) {
                tab.value = option
                if (option == UserTab.Favorites && favourites.value == null && !favourites.loading) favourites.load()
            }
        }
    }
    when (tab.value) {
        UserTab.Compare -> when {
            !ready && (anime.error ?: paper.error) != null -> ErrorRow(anime.error ?: paper.error!!) { anime.load(); paper.load() }
            mine.isEmpty() -> Hint("Your list hasn't loaded yet")
            comparison == null -> Loading()
            else -> CompareTab(retained, user.name, comparison)
        }
        UserTab.Stats -> StatsTab(retained, anime, paper, extras)
        UserTab.Activity -> ActivityTab(retained, user.id, aniListDays, more, own = false)
        UserTab.Achievements -> AchievementsTab(retained, achievements, anime.error ?: paper.error, own = false) { anime.load(); paper.load() }
        UserTab.Favorites -> {
            if (favourites.value == null && !favourites.loading && favourites.error == null) favourites.load()
            FavoritesTab(retained, favourites)
        }
    }
}

@Composable
private fun CompatRing(percent: Int, label: String) {
    val theme = LocalAppTheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(46.dp).clip(CircleShape).background(theme.primary.copy(alpha = 0.18f)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("$percent%", style = NumberStyle(color = theme.primary, fontSize = 14.sp, bold = true))
            BasicText(label, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 8.sp))
        }
    }
}

@Composable
private fun CompareTab(retained: Retained, name: String, c: ListComparison) {
    val theme = LocalAppTheme.current
    val scroll = retained.get("compareScroll") { ScrollState(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            StatTile("In common", c.shared.size.toString(), Modifier.weight(1f))
            StatTile("Both scored", c.scoredTogether.toString(), Modifier.weight(1f))
            if (c.percent != null || c.overlap == null) {
                StatTile("Match", c.percent?.let { "$it%" } ?: "—", Modifier.weight(1f))
            } else {
                StatTile("Overlap", "${c.overlap}%", Modifier.weight(1f))
            }
        }
        if (c.percent == null) {
            Hint(
                "The match percent needs ${com.aniliphy.anilist.Compatibility.MIN_SCORED} titles you both scored; until then this is how much of the shorter list the other has",
                Modifier.padding(horizontal = 20.dp),
            )
        }
        if (c.theyLoved.isNotEmpty()) {
            SectionTitle("$name loved, not on your list")
            CoverStrip(c.theyLoved)
        }
        if (c.disagreements.isNotEmpty()) {
            SectionTitle("Where you disagree")
            c.disagreements.forEach { SideBySide(it, name) }
        }
        if (c.agreements.isNotEmpty()) {
            SectionTitle("Where you agree")
            c.agreements.take(8).forEach { SideBySide(it, name) }
        }
        if (c.youLoved.isNotEmpty()) {
            SectionTitle("You loved, not on their list")
            CoverStrip(c.youLoved)
        }
        if (c.shared.isEmpty()) Empty("Nothing in common yet")
    }
}

@Composable
private fun CoverStrip(entries: List<ListEntry>) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(entries, key = { it.media.id }) { entry ->
            Column(Modifier.width(84.dp).clickable { ui.navigator.open(Route.Media(entry.media.id)) }) {
                Cover(entry.media.coverImage.best, Modifier.size(width = 84.dp, height = 120.dp))
                BasicText(
                    entry.media.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground, fontSize = 11.sp, lineHeight = 14.sp),
                )
                if (entry.score > 0) BasicText("★ ${score(entry.score)}", style = NumberStyle(color = theme.primary, fontSize = 11.sp, bold = true))
            }
        }
    }
}

@Composable
private fun SideBySide(t: SharedTitle, name: String) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 3.dp)
            .fillMaxWidth()
            .clip(lookShape(10.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .clickable { ui.navigator.open(Route.Media(t.media.id)) }
            .padding(8.dp),
    ) {
        Cover(t.media.coverImage.best, Modifier.size(width = 34.dp, height = 48.dp))
        Spacer(Modifier.width(10.dp))
        BasicText(
            t.media.title.display, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            BasicText("You ${scoreOrDash(t.mine.score)}", style = NumberStyle(color = theme.foreground, fontSize = 12.sp, bold = true))
            BasicText("${name.take(12)} ${scoreOrDash(t.theirs.score)}", style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true))
        }
    }
}

private fun score(s: Double) = if (s % 1.0 == 0.0) s.toInt().toString() else "%.1f".format(s)
private fun scoreOrDash(s: Double) = if (s > 0) score(s) else "—"
