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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.aniliphy.anilist.Activity
import com.aniliphy.anilist.AnimePeople
import com.aniliphy.anilist.Favourites
import com.aniliphy.anilist.Formatting
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListInsights
import com.aniliphy.anilist.ListStats
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anilist.Overview
import com.aniliphy.anilist.Remaining
import com.aniliphy.anilist.SessionState
import com.aniliphy.anilist.ofKind
import kotlinx.coroutines.launch
import com.aniliphy.anilist.ProfileInsights
import com.aniliphy.anilist.PeriodComparison
import com.aniliphy.anilist.PlanningForecast
import com.aniliphy.anilist.HotTake
import kotlin.math.roundToInt
import java.time.LocalDate

internal class StatsData(
    val animeDetails: LoadState<Map<Int, Media>>,
    val paperDetails: LoadState<Map<Int, Media>>,
    val people: LoadState<AnimePeople>,
) {
    fun refresh() {
        animeDetails.load(refresh = true)
        paperDetails.load(refresh = true)
        people.load(refresh = true)
    }

    fun reload() {
        listOf(animeDetails, paperDetails, people).forEach { if (it.value != null) it.load() }
    }
}

private enum class StatsPart(val label: String) {
    Summary("Summary"), Genres("Genres & tags"), People("People"), Distributions("Distributions"),
}

@Composable
internal fun StatsTab(
    retained: Retained,
    anime: LoadState<List<ListEntry>>,
    paper: LoadState<List<ListEntry>>,
    extras: StatsData,
) {
    val kind: MutableState<MediaKind> = retained.get("statsKind") { mutableStateOf(MediaKind.Anime) }
    val part: MutableState<StatsPart> = retained.get("statsPart") { mutableStateOf(StatsPart.Summary) }
    val scroll = retained.get("statsScroll") { ScrollState(0) }
    val partsRow = retained.get("statsParts") { LazyListState() }
    val load = if (kind.value == MediaKind.Anime) anime else paper
    val details = if (kind.value == MediaKind.Anime) extras.animeDetails else extras.paperDetails
    val entries = load.value
    val context = LocalContext.current

    LaunchedEffect(kind.value) {
        if (details.value == null && !details.loading) details.load()
        if (kind.value == MediaKind.Anime && extras.people.value == null && !extras.people.loading) extras.people.load()
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 24.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        ) {
            MediaKind.entries.forEach { option ->
                FilterChip(Formatting.kindLabel(option), kind.value == option) { kind.value = option }
            }
        }
        LazyRow(
            state = partsRow,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            items(StatsPart.entries, key = { it.name }) { option ->
                FilterChip(option.label, part.value == option) { part.value = option }
            }
            item(key = "share") {
                val theme = LocalAppTheme.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                FilterChip("⇪ Picture", false) {
                    entries?.let { all ->
                        val mine = all.ofKind(kind.value)
                        scope.launch { ShareImage.share(context, theme, statsCard(mine, kind.value), statsText(mine, kind.value)) }
                    }
                }
            }
            item(key = "shareText") {
                FilterChip("⇪ Text", false) {
                    entries?.let { context.shareText(statsText(it.ofKind(kind.value), kind.value)) }
                }
            }
        }
        StaleNotice(load.stale)
        when {
            entries != null -> {
                val ofKind = remember(entries, kind.value) { entries.ofKind(kind.value) }
                if (ofKind.isEmpty()) {
                    Empty("Nothing on this list yet")
                } else {
                    val stats = remember(ofKind, kind.value) { ListStats.of(ofKind, kind.value) }
                    val insights = remember(ofKind, kind.value, details.value) {
                        ListInsights.of(ofKind, kind.value, details.value.orEmpty())
                    }
                    when (part.value) {
                        StatsPart.Summary -> StatsSummary(stats, ofKind, kind.value, insights)
                        StatsPart.Genres -> StatsGenres(insights, details)
                        StatsPart.People -> StatsPeople(insights, kind.value, extras.people, details)
                        StatsPart.Distributions -> StatsDistributions(retained, stats, insights, kind.value)
                    }
                }
            }
            load.error != null -> ErrorRow(load.error!!) { load.load() }
            else -> Hint("Loading...")
        }
    }
}

@Composable
private fun StatsSummary(stats: ListStats, entries: List<ListEntry>, kind: MediaKind, insights: ListInsights) {
    val anime = kind == MediaKind.Anime
    val mean = if (stats.meanScore > 0) "%.1f".format(java.util.Locale.ENGLISH, stats.meanScore) else "–"
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Total", stats.count.toString(), Modifier.weight(1f))
            StatTile("Mean score", mean, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (anime) {
                StatTile("Episodes watched", stats.progress.toString(), Modifier.weight(1f))
                StatTile("Days watched", "%.1f".format(java.util.Locale.ENGLISH, stats.daysWatched), Modifier.weight(1f))
            } else {
                StatTile("Chapters read", stats.progress.toString(), Modifier.weight(1f))
                StatTile("Volumes read", stats.volumes.toString(), Modifier.weight(1f))
            }
        }
    }
    val today = remember { LocalDate.now() }
    val comparison = remember(entries, kind) { ProfileInsights.compare(entries, kind, today) }
    PeriodPanel(comparison, kind, today)
    val forecast = remember(entries) { ProfileInsights.forecast(entries, today) }
    ForecastPanel(forecast, kind)
    LeftToFinish(insights, kind)
    val takes = remember(entries) { ProfileInsights.hotTakes(entries) }
    HotTakesPanel(takes)
}

@Composable
private fun StatsGenres(insights: ListInsights, details: LoadState<Map<Int, Media>>) {
    details.error?.let { message -> if (details.value == null) ErrorRow(message) { details.load() } }
    if (details.value == null && details.error == null) Hint("Loading...")
    RankedBars("Top genres", insights.genres.map { RankedRow(it.name, it.count, it.meanScore, null) })
    RankedBars("Top tags", insights.tags.map { RankedRow(it.name, it.count, it.meanScore, null) })
}

@Composable
private fun StatsPeople(insights: ListInsights, kind: MediaKind, people: LoadState<AnimePeople>, details: LoadState<Map<Int, Media>>) {
    if (kind == MediaKind.Anime) {
        people.error?.let { message -> if (people.value == null) ErrorRow(message) { people.load() } }
        if (people.value == null && people.error == null) Hint("Loading...")
        val found = people.value ?: AnimePeople()
        RankedBars("Top studios", found.studios.take(TOP_PEOPLE).map { RankedRow(it.name, it.count, null, Route.Studio(it.id)) })
        RankedBars("Top staff", found.staff.take(TOP_PEOPLE).map { RankedRow(it.name, it.count, null, Route.Staff(it.id)) })
        RankedBars("Top voice actors", found.voiceActors.take(TOP_PEOPLE).map { RankedRow(it.name, it.count, null, Route.Staff(it.id)) })
    } else {
        details.error?.let { message -> if (details.value == null) ErrorRow(message) { details.load() } }
        if (details.value == null && details.error == null) Hint("Loading...")
        RankedBars("Top staff", insights.staff.map { RankedRow(it.name, it.count, null, Route.Staff(it.id)) })
    }
}

@Composable
private fun StatsDistributions(retained: Retained, stats: ListStats, insights: ListInsights, kind: MediaKind) {
    val ui = LocalAniList.current
    val anime = kind == MediaKind.Anime
    StatBars("Scores", insights.scores, keepEmpty = true)
    val lengthTitle = when (kind) {
        MediaKind.Anime -> "Episode count"
        MediaKind.Manga -> "Chapter count"
        MediaKind.LightNovel -> "Volume count"
    }
    StatBars(lengthTitle, stats.lengths)
    StatBars(
        "Status",
        stats.statuses.map { (status, count) ->
            (if (status in ListStatus.ALL) ListStatus.label(status, kind) else status) to count
        },
    )
    StatBars("Format", stats.formats.map { (format, count) -> Formatting.format(format) to count })
    val byYear: MutableState<Boolean> = retained.get("statsByYear") { mutableStateOf(false) }
    StatBars(
        if (byYear.value) "Release year" else "Release decade",
        if (byYear.value) insights.years else insights.decades,
        action = (if (byYear.value) "By decade" else "By year") to { byYear.value = !byYear.value },
    )
    if (insights.mostRepeated.isNotEmpty()) {
        val rowState = retained.get("statsRepeated$kind") { LazyListState() }
        CoverRow(
            title = if (anime) "Most rewatched" else "Most reread",
            items = insights.mostRepeated,
            key = { it.media.id },
            state = rowState,
        ) { entry ->
            CoverCard(
                title = entry.media.title.display,
                subtitle = "${entry.repeat}x",
                url = entry.media.coverImage.best,
                onClick = { ui.navigator.open(Route.Media(entry.media.id)) },
                wide = false,
                mediaId = entry.media.id,
            )
        }
    }
}

private const val TOP_PEOPLE = 10

@Composable
private fun LeftToFinish(insights: ListInsights, kind: MediaKind) {
    val theme = LocalAppTheme.current
    fun amount(left: Remaining) = when (kind) {
        MediaKind.Anime -> Formatting.duration(left.minutes)
        MediaKind.Manga -> "${left.units} ch"
        MediaKind.LightNovel -> "${left.units} vols"
    }
    StatPanel("Left to finish") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LeftAmount(amount(insights.inProgress), "In progress · ${insights.inProgress.titles}", Modifier.weight(1f))
            LeftAmount(amount(insights.planning), "Planning · ${insights.planning.titles}", Modifier.weight(1f))
        }
        val unknown = insights.inProgress.unknown + insights.planning.unknown
        if (unknown > 0) {
            BasicText(
                "Not counted: $unknown with no known length",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun LeftAmount(value: String, label: String, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Column(modifier) {
        BasicText(
            value,
            maxLines = 1,
            style = TextStyle(color = theme.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
        )
    }
}

@Composable
internal fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Column(
        modifier
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicText(
            value,
            maxLines = 1,
            style = TextStyle(color = theme.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
        )
    }
}

@Composable
internal fun Bar(share: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(8.dp)) {
        if (share > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(share.coerceAtLeast(0.02f))
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(LocalAppTheme.current.primary),
            )
        }
    }
}

@Composable
internal fun StatPanel(title: String, action: Pair<String, () -> Unit>? = null, content: @Composable () -> Unit) {
    val theme = LocalAppTheme.current
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                title,
                style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (action != null) FilterChip(action.first, false, action.second)
        }
        content()
    }
}

@Composable
internal fun StatBars(
    title: String,
    rows: List<Pair<String, Int>>,
    keepEmpty: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
) {
    val shown = if (keepEmpty) rows else rows.filter { it.second > 0 }
    if (shown.isEmpty()) return
    val theme = LocalAppTheme.current
    val total = shown.sumOf { it.second }
    val largest = shown.maxOf { it.second }
    StatPanel(title, action) {
        shown.forEach { (label, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                    modifier = Modifier.width(80.dp),
                )
                Bar(count.toFloat() / largest, Modifier.weight(1f))
                BasicText(
                    if (count == 0) "0" else "$count · ${percent(count, total)}",
                    maxLines = 1,
                    style = TextStyle(color = theme.foreground, fontSize = 12.sp),
                    modifier = Modifier.padding(start = 10.dp).width(68.dp),
                )
            }
        }
    }
}

private class RankedRow(val name: String, val count: Int, val meanScore: Double?, val route: Route?)

@Composable
private fun RankedBars(title: String, rows: List<RankedRow>) {
    if (rows.isEmpty()) return
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val largest = rows.maxOf { it.count }
    StatPanel(title) {
        rows.forEach { row ->
            val route = row.route
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (route != null) Modifier.clickable { ui.navigator.open(route) } else Modifier)
                    .padding(vertical = 2.dp),
            ) {
                Row {
                    BasicText(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                        modifier = Modifier.weight(1f),
                    )
                    val mean = row.meanScore?.let { " · avg %.1f".format(java.util.Locale.ENGLISH, it) }.orEmpty()
                    BasicText(
                        "${row.count}$mean",
                        maxLines = 1,
                        style = TextStyle(color = theme.foreground, fontSize = 12.sp),
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Bar(row.count.toFloat() / largest, Modifier.fillMaxWidth())
            }
        }
    }
}

private fun percent(count: Int, total: Int): String {
    val share = count * 100 / total
    return if (share == 0) "<1%" else "$share%"
}


/** This month against last, this year against last: titles finished, episodes or chapters, and time for anime. */
@Composable
private fun PeriodPanel(c: PeriodComparison, kind: MediaKind, today: LocalDate) {
    val theme = LocalAppTheme.current
    val unit = when (kind) {
        MediaKind.Anime -> "Episodes"
        MediaKind.Manga -> "Chapters"
        MediaKind.LightNovel -> "Volumes"
    }
    val month = today.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    val lastMonth = today.minusMonths(1).month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
    StatPanel("Finished, compared") {
        CompareTable(listOf(month, lastMonth), buildList {
            add(Triple("Titles", c.thisMonth.titles, c.lastMonth.titles))
            add(Triple(unit, c.thisMonth.units, c.lastMonth.units))
            if (kind == MediaKind.Anime) add(Triple("Hours", (c.thisMonth.minutes / 60).toInt(), (c.lastMonth.minutes / 60).toInt()))
        })
        Spacer(Modifier.height(4.dp))
        CompareTable(listOf("${today.year}", "${today.year - 1}"), buildList {
            add(Triple("Titles", c.thisYear.titles, c.lastYear.titles))
            add(Triple(unit, c.thisYear.units, c.lastYear.units))
            if (kind == MediaKind.Anime) add(Triple("Hours", (c.thisYear.minutes / 60).toInt(), (c.lastYear.minutes / 60).toInt()))
        })
        if (c.guessed > 0) {
            BasicText(
                "${c.guessed} without a finish date on AniList are counted on the day they were last changed",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp),
            )
        }
    }
}

@Composable
private fun CompareTable(heads: List<String>, rows: List<Triple<String, Int, Int>>) {
    val theme = LocalAppTheme.current
    val muted = theme.foreground.copy(alpha = 0.65f)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row {
            Spacer(Modifier.weight(1f))
            heads.forEach { BasicText(it.uppercase(), style = NumberStyle(color = muted, fontSize = 11.sp, bold = true), modifier = Modifier.width(92.dp)) }
        }
        rows.forEach { (label, now, before) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(label, style = TextStyle(color = theme.foreground, fontSize = 13.sp), modifier = Modifier.weight(1f))
                Row(Modifier.width(92.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("%,d".format(now), style = NumberStyle(color = theme.foreground, fontSize = 14.sp, bold = true))
                    val diff = now - before
                    if (diff != 0) {
                        BasicText(
                            (if (diff > 0) " ▲" else " ▼") + kotlin.math.abs(diff),
                            style = NumberStyle(color = if (diff > 0) theme.primary else theme.error, fontSize = 11.sp),
                        )
                    }
                }
                BasicText("%,d".format(before), style = NumberStyle(color = muted, fontSize = 14.sp), modifier = Modifier.width(92.dp))
            }
        }
    }
}

/** "At your pace your 63 Planning titles take about 15 months." */
@Composable
private fun ForecastPanel(f: PlanningForecast, kind: MediaKind) {
    if (f.planning == 0) return
    val theme = LocalAppTheme.current
    val text = when (val months = f.months) {
        null -> "Nothing finished in the last six months, so there's no pace to go by for your ${f.planning} Planning titles."
        else -> {
            val pace = "%.1f".format(java.util.Locale.ENGLISH, f.perMonth)
            val time = when {
                months < 1.5 -> "about a month"
                months < 24 -> "about ${months.roundToInt()} months"
                else -> "about ${"%.1f".format(java.util.Locale.ENGLISH, months / 12)} years"
            }
            "At your pace ($pace a month) your ${f.planning} Planning titles take $time."
        }
    }
    StatPanel("Planning forecast") {
        BasicText(text, style = TextStyle(color = theme.foreground, fontSize = 13.sp, lineHeight = 18.sp))
    }
}

/** Where your scores are furthest from AniList's average. */
@Composable
private fun HotTakesPanel(takes: Pair<List<HotTake>, List<HotTake>>) {
    val (above, below) = takes
    if (above.isEmpty() && below.isEmpty()) return
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    StatPanel("Hot takes") {
        BasicText(
            "Your score against AniList's average, out of 100",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp),
        )
        (above + below).forEach { take ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { ui.navigator.open(Route.Media(take.entry.media.id)) }.padding(vertical = 3.dp),
            ) {
                BasicText(
                    take.entry.media.title.display,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    "%.1f vs %d".format(java.util.Locale.ENGLISH, take.entry.score, take.entry.media.averageScore ?: 0),
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.sp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                BasicText(
                    (if (take.difference > 0) "+" else "−") + kotlin.math.abs(take.difference),
                    style = NumberStyle(color = if (take.difference > 0) theme.primary else theme.error, fontSize = 13.sp, bold = true),
                )
            }
        }
    }
}

private fun statsCard(entries: List<ListEntry>, kind: MediaKind): ShareCard {
    val stats = ListStats.of(entries, kind)
    val anime = kind == MediaKind.Anime
    return ShareCard(
        kicker = "My ${Formatting.kindLabel(kind)}",
        title = "${stats.count} titles",
        big = if (anime) "%.1f".format(java.util.Locale.ENGLISH, stats.daysWatched) else "%,d".format(stats.progress),
        bigLabel = if (anime) "days watched" else "chapters",
        lines = listOfNotNull(
            if (anime) "${stats.progress} episodes" else "${stats.volumes} volumes",
            "Mean score ${"%.1f".format(java.util.Locale.ENGLISH, stats.meanScore)}".takeIf { stats.meanScore > 0 },
        ),
        cover = entries.filter { it.score > 0 }.maxByOrNull { it.score }?.media?.coverImage?.best,
    )
}

/** The stats as a few lines of text, for apps that only take text. */
private fun statsText(entries: List<ListEntry>, kind: MediaKind): String {
    val stats = ListStats.of(entries, kind)
    val label = Formatting.kindLabel(kind)
    val amount = if (kind == MediaKind.Anime) {
        "${stats.progress} episodes · ${"%.1f".format(java.util.Locale.ENGLISH, stats.daysWatched)} days"
    } else {
        "${stats.progress} chapters · ${stats.volumes} volumes"
    }
    val mean = if (stats.meanScore > 0) " · mean score ${"%.1f".format(java.util.Locale.ENGLISH, stats.meanScore)}" else ""
    return "My $label on Aniliphy: ${stats.count} titles · $amount$mean"
}

/** Opens Android's share sheet with [text]. */
internal fun android.content.Context.shareText(text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
}
