package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aniliphy.anilist.Goal
import com.aniliphy.anilist.GoalKind
import com.aniliphy.anilist.GoalPeriod
import com.aniliphy.anilist.GoalProgress
import com.aniliphy.anilist.Goals
import com.aniliphy.anilist.ListEntry
import com.aniliphy.core.ActivityDay
import com.aniliphy.core.ActivityLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Composable
internal fun GoalsTab(anime: List<ListEntry>?, paper: List<ListEntry>?) {
    val context = LocalContext.current
    val local = remember { ProfileLocal.get(context) }
    var days by remember { mutableStateOf<Map<LocalDate, ActivityDay>>(emptyMap()) }
    LaunchedEffect(Unit) { days = withContext(Dispatchers.IO) { ActivityLog.all(context) } }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }
    val goals = local.data.goals

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (anime == null || paper == null) {
            Loading()
            return@Column
        }
        val progress = remember(goals, anime, paper, days) {
            goals.map { Goals.progress(it, anime, paper, days.chapters(), days.minutes(), today) }
        }
        Spacer(Modifier.height(8.dp))
        progress.forEach { p -> GoalCard(p, today) { editing = p.goal } }
        val theme = LocalAppTheme.current
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .fillMaxWidth()
                .clip(lookShape(12.dp))
                .border(1.dp, theme.foreground.copy(alpha = 0.35f), lookShape(12.dp))
                .clickable { adding = true }
                .padding(14.dp),
        ) {
            BasicText("+ New goal", style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold))
        }
        if (goals.isEmpty()) {
            Hint("Like \"50 anime this year\" or \"40 chapters a week\". The pink mark shows where you'd be going evenly.")
        } else {
            Hint("The pink mark is where you'd be going evenly. Goals are kept on this phone.")
        }
    }
    if (adding || editing != null) {
        GoalEditor(
            start = editing,
            onSave = { goal ->
                local.saveGoal(goal)
                adding = false
                editing = null
            },
            onDelete = editing?.let { goal -> { local.removeGoal(goal.id); editing = null } },
            onDismiss = {
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun GoalCard(p: GoalProgress, today: LocalDate, onEdit: () -> Unit) {
    val theme = LocalAppTheme.current
    val goal = p.goal
    val left = (goal.target - p.value).coerceAtLeast(0)
    val daysLeft = ChronoUnit.DAYS.between(today, p.to)
    val line = when {
        p.done -> "Done"
        p.ahead -> "Ahead of pace · $left to go"
        else -> "A little behind · $left left, $daysLeft days to go"
    }
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.surface.copy(alpha = 0.82f))
            .lookAlive(Alive.Badge, active = p.done)
            .clickable(onClick = onEdit)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "${goal.kind.label} · ${goal.period.label.lowercase()}",
                style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                modifier = Modifier.weight(1f),
            )
            BasicText("%,d/%,d".format(p.value, goal.target), style = NumberStyle(color = theme.foreground, fontSize = 14.sp, bold = true))
        }
        Spacer(Modifier.height(9.dp))
        var width by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        Box(Modifier.fillMaxWidth().height(12.dp).onSizeChanged { width = it.width }) {
            Box(Modifier.fillMaxWidth().height(10.dp).align(Alignment.Center).background(theme.foreground.copy(alpha = 0.14f)))
            Box(Modifier.fillMaxWidth(fillOnShow(p.share)).height(10.dp).align(Alignment.CenterStart).background(if (p.done) theme.success else theme.primary))
            if (!p.done) {
                val x = with(density) { (width * p.pace).toDp() }
                Box(Modifier.offset(x = x - 1.dp).width(2.dp).fillMaxHeight().background(theme.accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        BasicText(line, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalEditor(start: Goal?, onSave: (Goal) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    val theme = LocalAppTheme.current
    var kind by remember { mutableStateOf(start?.kind ?: GoalKind.AnimeFinished) }
    var period by remember { mutableStateOf(start?.period ?: GoalPeriod.Year) }
    var target by remember { mutableIntStateOf(start?.target ?: 50) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().clip(lookShape(16.dp)).background(theme.surface).padding(20.dp),
        ) {
            BasicText(if (start == null) "New goal" else "Edit goal", style = TitleStyle(color = theme.foreground, fontSize = 19.sp))
            BasicText("What", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                GoalKind.entries.forEach { option -> FilterChip(option.label, kind == option) { kind = option } }
            }
            if (kind.fromApp) {
                BasicText(
                    "Counts only what you do in Aniliphy; AniList keeps totals, not days.",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp),
                )
            }
            BasicText("When", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GoalPeriod.entries.forEach { option -> FilterChip(option.label, period == option) { period = option } }
            }
            BasicText("How many", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(-10, -1).forEach { step -> FilterChip(if (step == -1) "−" else "−10", false) { target = (target + step).coerceAtLeast(1) } }
                BasicText(
                    "$target ${kind.unit}",
                    style = NumberStyle(color = theme.foreground, fontSize = 18.sp, bold = true),
                    modifier = Modifier.weight(1f),
                )
                listOf(1, 10).forEach { step -> FilterChip(if (step == 1) "+" else "+10", false) { target = (target + step).coerceAtMost(9999) } }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    EditorButton("Delete", theme.error, onDelete)
                    Spacer(Modifier.weight(1f))
                }
                EditorButton("Cancel", theme.foreground, onDismiss)
                EditorButton("Save", theme.primary) {
                    onSave(Goal(start?.id ?: UUID.randomUUID().toString(), kind, period, target))
                }
            }
        }
    }
}

@Composable
private fun EditorButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    BasicText(
        label,
        style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(10.dp),
    )
}
