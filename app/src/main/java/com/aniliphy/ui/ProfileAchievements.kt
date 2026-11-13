package com.aniliphy.ui

import kotlinx.coroutines.launch

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aniliphy.anilist.AchievementGroup
import com.aniliphy.anilist.AchievementResult
import com.aniliphy.anilist.Achievements
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Gold = Color(0xFFF2C14E)
private val Silver = Color(0xFFC9D1DC)
private val Bronze = Color(0xFFCF8A4E)

private val Hexagon = GenericShape { size, _ ->
    moveTo(size.width / 2, 0f)
    lineTo(size.width, size.height * 0.25f)
    lineTo(size.width, size.height * 0.75f)
    lineTo(size.width / 2, size.height)
    lineTo(0f, size.height * 0.75f)
    lineTo(0f, size.height * 0.25f)
    close()
}

/** A medal's outline in each look: a stamp is round, a sheet's cut is square, a manga panel has its corner cut. */
private fun medalShape(): Shape = when (Look.style) {
    UiStyle.Stamp, UiStyle.Jrpg, UiStyle.Glass -> CircleShape
    UiStyle.Tankobon -> CutCornerShape(bottomEnd = 10.dp)
    UiStyle.Genga, UiStyle.Konbini, UiStyle.Bunko -> RectangleShape
    UiStyle.Modern, UiStyle.Eki -> RoundedCornerShape(12.dp)
    UiStyle.Shinya -> Hexagon
}

private fun AchievementResult.shownTitle() = if (achievement.hidden && !earned) "???" else achievement.title

private fun AchievementResult.shownRule() =
    if (achievement.hidden && !earned) "A secret. Keep going." else achievement.rule.format(next ?: achievement.levels.last())

private fun AchievementResult.status(): String = when {
    !earned && achievement.hidden -> "Secret"
    next == null -> Achievements.levelName(level, single)
    else -> "%,d/%,d".format(value, next)
}

/** The medal: gold, silver or bronze, or a faint outline while locked. */
@Composable
private fun Medal(result: AchievementResult, size: Dp = 44.dp) {
    val theme = LocalAppTheme.current
    val shape = medalShape()
    val color = when {
        !result.earned -> theme.foreground.copy(alpha = 0.14f)
        result.single || result.level >= 3 -> Gold
        result.level == 2 -> Silver
        else -> Bronze
    }
    val mark = when {
        !result.earned -> if (result.achievement.hidden) "?" else "–"
        result.single -> "★"
        else -> "I".repeat(result.level)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(shape).background(color)
            .then(if (Look.style == UiStyle.Tankobon && result.earned) Modifier.border(2.dp, theme.ink, shape) else Modifier)
            .then(if (Look.style == UiStyle.Stamp && result.earned) Modifier.border(2.dp, theme.pencil, shape) else Modifier),
    ) {
        BasicText(
            mark,
            style = NumberStyle(color = if (result.earned) Color(0xFF1A1420) else theme.foreground.copy(alpha = 0.55f), fontSize = 13.sp, bold = true),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Badge(result: AchievementResult, inShowcase: Boolean, onOpen: () -> Unit, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .then(if (inShowcase) Modifier.border(1.5.dp, theme.accent, lookShape(12.dp)) else Modifier)
            .combinedClickable(onClick = onOpen, onLongClick = onHold)
            .padding(horizontal = 6.dp, vertical = 10.dp),
    ) {
        Medal(result)
        Spacer(Modifier.height(6.dp))
        BasicText(
            result.shownTitle(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        BasicText(result.status(), maxLines = 1, style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 10.sp))
        if (!result.earned || result.next != null) {
            if (!(result.achievement.hidden && !result.earned)) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(theme.foreground.copy(alpha = 0.15f))) {
                    Box(Modifier.fillMaxWidth(result.towardsNext).fillMaxHeight().background(theme.primary))
                }
            }
        }
    }
}

@Composable
internal fun AchievementsTab(retained: Retained, results: List<AchievementResult>?, error: String?, own: Boolean = true, onRetry: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val local = remember { ProfileLocal.get(context) }
    val group: MutableState<AchievementGroup?> = retained.get("achGroup") { mutableStateOf(null) }
    val chips = retained.get("achChips") { LazyListState() }
    val grid = retained.get("achGrid") { LazyGridState() }
    var open by remember { mutableStateOf<AchievementResult?>(null) }
    if (results == null) {
        if (error != null) ErrorRow(error, onRetry) else Loading()
        return
    }
    val shown = results.filter { group.value == null || it.achievement.group == group.value }
        .sortedWith(compareByDescending<AchievementResult> { it.earned }.thenByDescending { it.level })
    val earned = results.count { it.earned }
    fun hold(result: AchievementResult) {
        if (!result.earned || !own) return
        LookHaptics.hold(view)
        local.toggleShowcase(result.id)
    }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            state = chips,
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            item(key = "all") { FilterChip("All $earned/${results.size}", group.value == null) { group.value = null } }
            items(AchievementGroup.entries, key = { it.name }) { option ->
                FilterChip(option.label, group.value == option) { group.value = option }
            }
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (own) item(span = { GridItemSpan(maxLineSpan) }) {
                Hint("Hold an earned one to put it in your showcase (up to ${ProfileLocal.SHOWCASE})")
            }
            items(shown, key = { it.id }) { result ->
                Badge(result, own && result.id in local.data.showcase, onOpen = { open = result }, onHold = { hold(result) })
            }
        }
    }
    open?.let { result ->
        AchievementDialog(result, inShowcase = result.id in local.data.showcase, onShowcase = if (own) ({ hold(result); open = null }) else null) { open = null }
    }
}

@Composable
private fun AchievementDialog(result: AchievementResult, inShowcase: Boolean, onShowcase: (() -> Unit)?, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).padding(20.dp),
        ) {
            Medal(result, 64.dp)
            Spacer(Modifier.height(10.dp))
            BasicText(result.shownTitle(), style = TitleStyle(color = theme.foreground, fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            BasicText(result.shownRule(), style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center))
            Spacer(Modifier.height(10.dp))
            if (!(result.achievement.hidden && !result.earned)) {
                val levels = result.achievement.levels
                if (levels.size > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        levels.forEachIndexed { i, amount ->
                            BasicText(
                                "${Achievements.levelName(i + 1, false)} %,d".format(amount),
                                style = NumberStyle(color = if (result.level > i) theme.primary else theme.foreground.copy(alpha = 0.5f), fontSize = 12.sp, bold = result.level > i),
                            )
                        }
                    }
                }
                BasicText("Now: %,d".format(result.value), style = NumberStyle(color = theme.foreground, fontSize = 13.sp))
                result.reachedOn?.let {
                    BasicText(
                        "${Achievements.levelName(result.level, result.single)} since ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))}",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (result.earned) {
                    val context = LocalContext.current
                    val theme = LocalAppTheme.current
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    BasicText(
                        "Share",
                        style = TextStyle(color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            scope.launch {
                                ShareImage.share(
                                    context, theme,
                                    ShareCard(
                                        kicker = "Achievement",
                                        title = result.shownTitle(),
                                        lines = listOf(result.shownRule(), "${Achievements.levelName(result.level, result.single)} · %,d".format(result.value)),
                                    ),
                                    "Achievement on aniliphy: ${result.shownTitle()}",
                                )
                            }
                        }.padding(10.dp),
                    )
                }
                if (result.earned && onShowcase != null) {
                    BasicText(
                        if (inShowcase) "Take out of showcase" else "Put in showcase",
                        style = TextStyle(color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onShowcase).padding(10.dp),
                    )
                }
                BasicText(
                    "Close",
                    style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(10.dp),
                )
            }
        }
    }
}

/** The three achievements you picked, on the overview (or your best ones until you pick). */
@Composable
internal fun Showcase(results: List<AchievementResult>?, onMore: () -> Unit) {
    val context = LocalContext.current
    val local = remember { ProfileLocal.get(context) }
    val earned = results?.filter { it.earned } ?: return
    if (earned.isEmpty()) return
    val picked = local.data.showcase.mapNotNull { id -> earned.firstOrNull { it.id == id } }
    val shown = picked.ifEmpty { earned.sortedByDescending { it.level }.take(ProfileLocal.SHOWCASE) }
    SectionTitle("Showcase")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
        shown.forEach { result -> Badge(result, false, onOpen = onMore, onHold = onMore, modifier = Modifier.weight(1f)) }
        repeat(ProfileLocal.SHOWCASE - shown.size) { Spacer(Modifier.weight(1f)) }
    }
    Hint("${earned.size} of ${results.size} earned · tap to see them all")
}
