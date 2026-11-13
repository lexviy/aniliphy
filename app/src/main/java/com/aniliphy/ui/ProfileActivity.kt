package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Activity
import com.aniliphy.anilist.ActivityCalendar
import com.aniliphy.anilist.Fetched
import com.aniliphy.anilist.Paged
import com.aniliphy.anilist.ProfileExtras
import com.aniliphy.core.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val HEATMAP_WEEKS = 26

/** Your list activity, newest first, either all of it (more as you scroll) or one [day]'s. */
internal class ActivityFeed(private val ui: AniListUi, private val userId: Int) {
    var day by mutableStateOf<LocalDate?>(null)
        private set
    private var fresh = false
    val feed: PagedState<Activity> = ui.paged({ it.id }) { page -> fetch(page) }

    private suspend fun fetch(page: Int): Fetched<Paged<Activity>> {
        val refresh = fresh && page == 1
        fresh = false
        val zone = ZoneId.systemDefault()
        val d = day
        return if (d == null) {
            ui.api.activities(userId, page, refresh = refresh)
        } else {
            val from = d.atStartOfDay(zone).toEpochSecond() - 1
            val to = d.plusDays(1).atStartOfDay(zone).toEpochSecond()
            ui.api.activities(userId, page, from, to, refresh)
        }
    }

    fun select(newDay: LocalDate?) {
        day = newDay
        feed.restart { fetch(it) }
    }

    fun refresh() {
        fresh = true
        feed.restart { fetch(it) }
    }
}

internal fun Activity.day(zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    createdAt?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }

@Composable
internal fun ActivityTab(retained: Retained, userId: Int, aniListDays: Map<LocalDate, Int>, more: LoadState<ProfileExtras>, own: Boolean = true) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val feed = retained.get("activityFeed") { ActivityFeed(ui, userId) }
    val list = retained.get("activityList") { LazyListState() }
    var local by remember { mutableStateOf<Map<LocalDate, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        if (own) local = withContext(Dispatchers.IO) { ActivityLog.all(context) }.mapValues { it.value.amount() }
    }
    val days = remember(aniListDays, local) { ActivityCalendar.merge(aniListDays, local) }
    val today = remember { LocalDate.now() }
    val items = feed.feed.items
    val grouped = remember(items) { items.groupBy { it.day() } }

    LaunchedEffect(list, feed.feed) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= list.layoutInfo.totalItemsCount - 4) feed.feed.loadMore()
        }
    }

    LazyColumn(state = list, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "heat") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)) {
                Heatmap(days, today, feed.day) { picked -> feed.select(if (picked == feed.day) null else picked) }
                if (more.value == null && more.error != null) {
                    Hint("AniList's history didn't load; showing what this phone counted")
                }
            }
        }
        item(key = "streak") {
            val streak = ActivityCalendar.streak(days, today)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) {
                StatTile("Day streak", streak.toString(), Modifier.weight(1f))
                StatTile("Longest", ActivityCalendar.longestStreak(days).toString(), Modifier.weight(1f))
                StatTile("Active days", days.size.toString(), Modifier.weight(1f))
            }
        }
        feed.day?.let { day ->
            item(key = "picked") {
                Row(Modifier.padding(start = 20.dp, top = 12.dp)) {
                    FilterChip("${day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))}  ✕", true) { feed.select(null) }
                }
            }
        }
        grouped.forEach { (day, activities) ->
            item(key = "day-$day") {
                val plain = when (day) {
                    null -> "Earlier"
                    today -> "Today"
                    today.minusDays(1) -> "Yesterday"
                    else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", Locale.ENGLISH))
                }
                SectionTitle(if (day == null) plain else LookWords.day(momentStyle, day, plain, today))
            }
            items(activities, key = { "a${it.id}" }) { activity ->
                Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) { ActivityRow(activity) }
            }
        }
        item(key = "end") {
            val error = feed.feed.error
            when {
                error != null -> ErrorRow(error) { feed.feed.retry() }
                feed.feed.loading -> Loading()
                items.isEmpty() -> Empty(if (feed.day != null) "Nothing on this day" else "Nothing here yet")
            }
        }
    }
}

/**
 * Half a year of days as a grid, a column per week, darker the busier you were. Tapping a day shows only that day.
 */
@Composable
private fun Heatmap(days: Map<LocalDate, Int>, today: LocalDate, picked: LocalDate?, onPick: (LocalDate) -> Unit) {
    val theme = LocalAppTheme.current
    val weeks = remember(today) { ActivityCalendar.weeks(today, HEATMAP_WEEKS) }
    val style = Look.style
    val muted = theme.foreground.copy(alpha = 0.6f)
    Column {
        // Month names over the week each month starts in.
        Box(Modifier.fillMaxWidth().height(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                var lastMonth = -1
                weeks.forEach { week ->
                    val month = week.first().monthValue
                    Box(Modifier.weight(1f)) {
                        if (month != lastMonth && week.first().dayOfMonth <= 7) {
                            BasicText(
                                week.first().month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                                softWrap = false,
                                style = NumberStyle(color = muted, fontSize = 9.sp),
                            )
                        }
                    }
                    lastMonth = month
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(118.dp)
                .pointerInput(weeks) {
                    detectTapGestures { at ->
                        val column = (at.x / (size.width / weeks.size.toFloat())).toInt().coerceIn(0, weeks.lastIndex)
                        val row = (at.y / (size.height / 7f)).toInt().coerceIn(0, 6)
                        val day = weeks[column][row]
                        if (day <= today) onPick(day)
                    }
                },
        ) {
            val cw = size.width / weeks.size
            val ch = size.height / 7f
            val gap = 2.dp.toPx()
            val cell = minOf(cw, ch) - gap
            weeks.forEachIndexed { x, week ->
                week.forEachIndexed { y, day ->
                    if (day > today) return@forEachIndexed
                    val level = ActivityCalendar.level(days[day] ?: 0, days)
                    val color = if (level == 0) theme.surface else theme.primary.copy(alpha = 0.25f + 0.1875f * level)
                    val topLeft = Offset(x * cw + (cw - cell) / 2, y * ch + (ch - cell) / 2)
                    when (style) {
                        UiStyle.Stamp, UiStyle.Jrpg -> drawCircle(color, cell / 2, topLeft + Offset(cell / 2, cell / 2))
                        UiStyle.Modern, UiStyle.Glass, UiStyle.Shinya, UiStyle.Eki ->
                            drawRoundRect(color, topLeft, Size(cell, cell), CornerRadius(cell / 4))
                        else -> drawRect(color, topLeft, Size(cell, cell))
                    }
                    if (style == UiStyle.Tankobon && level == 4) drawRect(theme.ink, topLeft, Size(cell, cell), style = Stroke(1.dp.toPx()))
                    if (day == picked || (picked == null && day == today)) {
                        drawRect(if (day == picked) theme.accent else theme.foreground.copy(alpha = 0.6f), topLeft - Offset(1f, 1f), Size(cell + 2f, cell + 2f), style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            BasicText("Last 6 months · tap a day", style = TextStyle(color = muted, fontSize = 11.sp), modifier = Modifier.weight(1f))
            BasicText("less ", style = TextStyle(color = muted, fontSize = 10.sp))
            (0..4).forEach { level ->
                Box(
                    Modifier.padding(horizontal = 1.dp).width(9.dp).height(9.dp).clip(lookShape(2.dp, 2.dp))
                        .background(if (level == 0) theme.surface else theme.primary.copy(alpha = 0.25f + 0.1875f * level)),
                )
            }
            BasicText(" more", style = TextStyle(color = muted, fontSize = 10.sp))
        }
    }
}

/** What you were doing a year ago today, if anything. */
@Composable
internal fun OnThisDay(retained: Retained, userId: Int) {
    val ui = LocalAniList.current
    val today = remember { LocalDate.now() }
    val then = today.minusYears(1)
    val load = retained.get("onThisDay") {
        ui.load { refresh ->
            val zone = ZoneId.systemDefault()
            ui.api.activities(userId, 1, then.atStartOfDay(zone).toEpochSecond() - 1, then.plusDays(1).atStartOfDay(zone).toEpochSecond(), refresh)
                .map { it.items }
        }
    }
    val found = load.value.orEmpty()
    if (found.isEmpty()) return
    SectionTitle("On this day · ${then.year}")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
        found.take(3).forEach { ActivityRow(it) }
    }
}
