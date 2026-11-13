package com.aniliphy.ui

import kotlinx.coroutines.launch

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.Achievements
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.Wrapped
import com.aniliphy.anilist.WrappedMaker
import com.aniliphy.core.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle as DateStyle
import java.util.Locale

private const val SLIDE_MS = 4500

/** The year in review: a few screens, one after another like stories, in the look's own way. */
@Composable
internal fun WrappedScreen(year: Int, anime: List<ListEntry>, paper: List<ListEntry>, onClose: () -> Unit) {
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    var wrapped by remember { mutableStateOf<Wrapped?>(null) }
    LaunchedEffect(year) {
        wrapped = withContext(Dispatchers.Default) {
            val days = ActivityLog.all(context)
            val achievements = Achievements.of(anime, paper, LocalDate.of(year, 12, 31)) + Achievements.seasons(anime)
            WrappedMaker.of(year, anime, paper, achievements, days.chapters(), days.minutes())
        }
    }
    BackHandler(onBack = onClose)
    val w = wrapped
    Box(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        if (w == null) {
            Loading(Modifier.align(Alignment.Center))
            return@Box
        }
        val slides = remember(w) { slidesOf(w) }
        var index by remember { mutableIntStateOf(0) }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(index) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(Motion.ms(SLIDE_MS), easing = LinearEasing))
            if (index < slides.lastIndex) index++
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .pointerInput(slides.size) {
                    detectTapGestures { at ->
                        index = if (at.x < size.width / 3f) (index - 1).coerceAtLeast(0) else (index + 1).coerceAtMost(slides.lastIndex)
                    }
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                slides.indices.forEach { i ->
                    val fill = when {
                        i < index -> 1f
                        i == index -> progress.value
                        else -> 0f
                    }
                    Box(Modifier.weight(1f).height(3.dp).background(theme.foreground.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(theme.foreground))
                    }
                }
                Spacer(Modifier.width(10.dp))
                BasicText(
                    "✕",
                    style = TextStyle(color = theme.foreground, fontSize = 18.sp),
                    modifier = Modifier.clip(lookShape(8.dp)).clickable(onClick = onClose).padding(6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            BasicText(
                LookWords.wrappedStep(momentStyle, index + 1, slides.size),
                style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true),
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                val shown = remember(index) { Animatable(0f) }
                LaunchedEffect(index) { shown.animateTo(1f, tween(Motion.ms(520))) }
                Column(
                    Modifier.fillMaxWidth().graphicsLayer {
                        alpha = shown.value
                        translationY = (1f - shown.value) * 24.dp.toPx()
                    },
                ) {
                    slides[index](w)
                }
            }
            if (index == slides.lastIndex) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val theme = LocalAppTheme.current
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    FilterChip("⇪ Picture", true) { scope.launch { ShareImage.share(context, theme, wrappedCard(w), wrappedText(w)) } }
                    FilterChip("⇪ Text", false) { context.shareText(wrappedText(w)) }
                    FilterChip("Close", false, onClose)
                }
            }
        }
    }
}

private typealias Slide = @Composable (Wrapped) -> Unit

private fun slidesOf(w: Wrapped): List<Slide> {
    if (w.empty) return listOf<Slide>({ Big(LookWords.wrappedTitle(momentStyle, it.year), "Nothing finished on your lists that year.") })
    return buildList<Slide> {
        add { Intro(it) }
        if (it_hasTime(w)) add { Time(it) }
        if (w.topGenres.isNotEmpty()) add { Genres(it) }
        if (w.busiestMonth != null) add { Months(it) }
        if (w.favourite != null) add { Favourite(it) }
        if (w.achievements.isNotEmpty()) add { Unlocked(it) }
        add { Outro(it) }
    }
}

private fun it_hasTime(w: Wrapped) = w.hours > 0 || w.appMinutes > 0 || w.appChapters > 0

@Composable
private fun Big(head: String, line: String? = null, number: String? = null) {
    val theme = LocalAppTheme.current
    BasicText(head, style = TitleStyle(color = theme.foreground, fontSize = 26.sp))
    if (number != null) {
        Spacer(Modifier.height(6.dp))
        BasicText(number, style = NumberStyle(color = theme.primary, fontSize = 64.sp, bold = true))
    }
    if (line != null) {
        Spacer(Modifier.height(6.dp))
        BasicText(line, style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 17.sp, lineHeight = 23.sp))
    }
}

@Composable
private fun Intro(w: Wrapped) {
    val parts = listOfNotNull(
        w.anime.titles.takeIf { it > 0 }?.let { "$it anime" },
        w.manga.titles.takeIf { it > 0 }?.let { "$it manga" },
        w.novels.titles.takeIf { it > 0 }?.let { "$it light novels" },
    ).joinToString(" · ")
    Big(LookWords.wrappedTitle(momentStyle, w.year), "titles finished\n$parts", number = "%,d".format(w.titles))
}

@Composable
private fun Time(w: Wrapped) {
    val theme = LocalAppTheme.current
    if (w.hours > 0) {
        Big("Time watching", "of anime from your list. That's ${"%.1f".format(Locale.ENGLISH, w.hours / 24.0)} days.", number = "%,d h".format(w.hours))
    } else {
        Big("Time in the app")
    }
    if (w.appMinutes > 0 || w.appChapters > 0) {
        Spacer(Modifier.height(14.dp))
        BasicText(
            "In Aniliphy: ${w.appMinutes / 60} h watched · ${w.appChapters} chapters read",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp),
        )
    }
}

@Composable
private fun Genres(w: Wrapped) {
    val theme = LocalAppTheme.current
    Big("Top genres")
    Spacer(Modifier.height(10.dp))
    w.topGenres.forEachIndexed { i, (genre, count) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("${i + 1}", style = NumberStyle(color = theme.accent, fontSize = 15.sp, bold = true), modifier = Modifier.width(28.dp))
            BasicText(genre, style = TextStyle(color = theme.foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            BasicText("$count", style = NumberStyle(color = theme.primary, fontSize = 17.sp, bold = true))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.foreground.copy(alpha = 0.15f)))
    }
}

@Composable
private fun Months(w: Wrapped) {
    val theme = LocalAppTheme.current
    val busiest = w.busiestMonth ?: return
    Big("Busiest month", "${w.months[busiest.value - 1]} titles finished", number = busiest.getDisplayName(DateStyle.FULL, Locale.ENGLISH))
    Spacer(Modifier.height(18.dp))
    val top = w.months.max().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(130.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        w.months.forEachIndexed { i, count ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.fillMaxWidth().height((110f * fillOnShow(count.toFloat() / top)).dp.coerceAtLeast(2.dp))
                        .clip(lookShape(2.dp, 2.dp))
                        .background(if (i + 1 == busiest.value) theme.accent else theme.primary.copy(alpha = 0.5f)),
                )
                BasicText(Month.of(i + 1).getDisplayName(DateStyle.NARROW, Locale.ENGLISH), style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 10.sp))
            }
        }
    }
}

@Composable
private fun Favourite(w: Wrapped) {
    val theme = LocalAppTheme.current
    val fav = w.favourite ?: return
    Big("Your favourite")
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Cover(fav.media.coverImage.best, Modifier.size(width = 110.dp, height = 158.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            BasicText(fav.media.title.display, maxLines = 3, overflow = TextOverflow.Ellipsis, style = TitleStyle(color = theme.foreground, fontSize = 22.sp))
            if (fav.score > 0) {
                Spacer(Modifier.height(6.dp))
                BasicText("Scored %.1f".format(Locale.ENGLISH, fav.score), style = NumberStyle(color = theme.primary, fontSize = 16.sp, bold = true))
            }
        }
    }
    if (w.runnersUp.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        BasicText("Close behind", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            w.runnersUp.forEach { Cover(it.media.coverImage.best, Modifier.size(width = 58.dp, height = 84.dp)) }
        }
    }
}

@Composable
private fun Unlocked(w: Wrapped) {
    val theme = LocalAppTheme.current
    Big("Achievements", number = "${w.achievements.size}", line = "reached that year")
    Spacer(Modifier.height(12.dp))
    w.achievements.take(6).forEach { r ->
        BasicText(
            "${Achievements.levelName(r.level, r.single)} · ${r.achievement.title}",
            style = TextStyle(color = theme.foreground, fontSize = 15.sp),
            modifier = Modifier.padding(vertical = 3.dp),
        )
    }
}

@Composable
private fun Outro(w: Wrapped) {
    val theme = LocalAppTheme.current
    Big("That was ${w.year}", "${w.titles} titles, ${w.topGenres.firstOrNull()?.first ?: "all sorts"} above all.")
    if (w.guessed > 0) {
        Spacer(Modifier.height(12.dp))
        BasicText(
            "${w.guessed} titles had no finish date on AniList and are placed on the day they were last changed.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp),
        )
    }
}

private fun wrappedText(w: Wrapped): String = buildString {
    append("My ${w.year} on Aniliphy: ${w.titles} titles finished")
    if (w.hours > 0) append(", ${w.hours} h of anime")
    w.topGenres.firstOrNull()?.let { append(", mostly ${it.first}") }
    w.favourite?.let { append(". Favourite: ${it.media.title.display}") }
    append('.')
}

private fun wrappedCard(w: Wrapped) = ShareCard(
    kicker = "My ${w.year}",
    title = "${w.titles} titles finished",
    lines = listOfNotNull(
        "${w.hours} h of anime".takeIf { w.hours > 0 },
        "${w.manga.units + w.novels.units} chapters read".takeIf { w.manga.units + w.novels.units > 0 },
        w.topGenres.take(3).takeIf { it.isNotEmpty() }?.joinToString(" · ") { it.first },
        w.favourite?.let { "Favourite: ${it.media.title.display}" },
    ),
    cover = w.favourite?.media?.coverImage?.best,
)

/** The overview's way into the year in review. */
@Composable
internal fun WrappedCard(onOpen: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    val today = remember { LocalDate.now() }
    val year = WrappedMaker.yearFor(today)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.primary.copy(alpha = 0.16f))
            .lookAlive(Alive.Block)
            .clickable { onOpen(year) }
            .padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(LookWords.wrappedTitle(momentStyle, year), style = TitleStyle(color = theme.foreground, fontSize = 17.sp))
            BasicText("Your year in review is ready", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
        }
        BasicText("▶", style = TextStyle(color = theme.primary, fontSize = 20.sp))
    }
    if (today.monthValue == 12) {
        BasicText(
            "${year - 1}",
            style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp),
            modifier = Modifier.padding(start = 22.dp, top = 4.dp).clickable { onOpen(year - 1) },
        )
    }
}
