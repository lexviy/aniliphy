package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anime.AnimeHistory
import com.aniliphy.core.ActivityDay
import com.aniliphy.core.ActivityLog
import com.aniliphy.core.ActivityStats
import com.aniliphy.manga.MangaHistory
import com.aniliphy.widget.ActivitySettings
import com.aniliphy.widget.WidgetData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.TextStyle as DayStyle

/** One thing waiting to reach AniList. */
private data class Waiting(val kind: String, val title: String, val amount: String)

/** Settings → Activity: the "continue" notification, what's waiting to reach AniList, and your statistics. */
@Composable
fun ActivityScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val anime = LocalAnime.current
    val manga = LocalManga.current
    val novels = LocalNovels.current
    val settings = remember { ActivitySettings.get(context) }
    val askNotifications = rememberNotificationAsk()
    var notify by remember { mutableStateOf(settings.continueNotification) }
    var reload by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf<List<Waiting>?>(null) }
    var stats by remember { mutableStateOf<ActivityStats.Summary?>(null) }

    LaunchedEffect(reload) {
        waiting = withContext(Dispatchers.IO) {
            val animeTitles = AnimeHistory(File(context.filesDir, "anime_history.json")).all()
            val mangaTitles = MangaHistory(File(context.filesDir, "manga_history.json")).all()
            anime.waiting().map { (id, count) -> Waiting("Anime", animeTitles[id]?.title ?: "AniList #$id", "episode $count") } +
                manga.waiting().map { (id, count) -> Waiting("Manga", mangaTitles[id]?.title ?: "AniList #$id", "chapter $count") } +
                novels.waiting().map { (id, count) ->
                    Waiting("Light novel", novels.series.firstOrNull { it.mediaId == id }?.title ?: "AniList #$id", "chapter $count")
                }
        }
        stats = withContext(Dispatchers.IO) { ActivityStats.summary(ActivityLog.all(context), LocalDate.now()) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Activity", onBack = onBack)

        SectionTitle("Continue")
        SwitchRow(
            label = "Continue notification",
            description = "A quiet notification with your latest anime, manga and light novel, each one tap from where you stopped",
            checked = notify,
            onChange = {
                notify = it
                settings.continueNotification = it
                if (it) askNotifications()
                WidgetData.refreshSoon(context)
            },
        )

        SectionTitle("Waiting for AniList")
        val list = waiting
        when {
            list == null -> Unit
            list.isEmpty() -> Hint("Everything has reached AniList.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            else -> {
                Hint(
                    "Saved on this phone while AniList couldn't be reached. It's sent on its own when it can be; you can also try now.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                list.forEach { WaitingRow(it) }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    PillButton("Send now", onClick = {
                        anime.flushPending()
                        manga.flushPending()
                        novels.flushPending()
                        reload++
                    })
                }
            }
        }

        SectionTitle("Statistics")
        stats?.let { Statistics(it) }
    }
}

@Composable
private fun WaitingRow(item: Waiting) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        BasicText(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        BasicText("${item.kind} · ${item.amount}", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
    }
}

@Composable
private fun Statistics(summary: ActivityStats.Summary) {
    val theme = LocalAppTheme.current
    val locale = LocalConfiguration.current.locales[0]
    if (summary.all.isEmpty) {
        Hint(
            "Counts the time you watch and the chapters you read, from now on. Only kept on this phone.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        return
    }
    Totals("Last 7 days", summary.week)
    Totals("Last 30 days", summary.month)
    Totals("Since counting began", summary.all)
    if (summary.streak > 1) {
        Hint("${summary.streak} days in a row", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = theme.primary)
    }
    // A bar per day of the last week: anime minutes and chapters read, each against the week's busiest day.
    val most = summary.lastWeek.maxOf { (_, day) -> day.animeMs / 60_000 + (day.mangaChapters + day.novelChapters) * CHAPTER_MINUTES }.coerceAtLeast(1)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        summary.lastWeek.forEach { (date, day) ->
            val amount = day.animeMs / 60_000 + (day.mangaChapters + day.novelChapters) * CHAPTER_MINUTES
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Box(
                    Modifier
                        .width(14.dp)
                        .fillMaxHeight(0.85f * amount / most + 0.02f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (amount > 0) theme.primary else theme.foreground.copy(alpha = 0.15f)),
                )
                BasicText(
                    date.dayOfWeek.getDisplayName(DayStyle.NARROW, locale),
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 11.sp),
                )
            }
        }
    }
}

@Composable
private fun Totals(label: String, day: ActivityDay) {
    val theme = LocalAppTheme.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        BasicText(label, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium))
        BasicText(
            "${ActivityStats.duration(day.animeMs)} of anime · ${day.mangaChapters} manga chapters · ${day.novelChapters} light novel chapters",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp),
        )
    }
}

/** For the week's bars, a chapter counts like this many minutes of anime. */
private const val CHAPTER_MINUTES = 5L
