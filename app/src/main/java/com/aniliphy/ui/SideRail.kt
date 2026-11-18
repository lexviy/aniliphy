package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tabs down the side, for wide screens and TVs, where there's room for all of them (no middle button or extras
 * menu). One rail for every look, made of the look's own pieces: the bar part's mark for the selected tab
 * ([TabMark]), its label face and its small motion.
 */
@Composable
internal fun SideRail(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit) {
    val theme = LocalAppTheme.current
    val style = Look.spec.bar
    val tv = LocalWindowLayout.current == WindowLayout.Tv
    val first = remember { FocusRequester() }
    // A TV starts with the rail in focus, on the open tab.
    if (tv) LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val background = when (style) {
        UiStyle.Genga, UiStyle.Konbini -> theme.background
        UiStyle.Jrpg, UiStyle.Glass -> Color.Transparent
        else -> theme.surface
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .width(if (tv) 96.dp else 84.dp)
            .fillMaxHeight()
            .background(if (tv) Color.Transparent else background)
            .drawBehind {
                if (!tv) drawLine(theme.hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
            }
            .then(if (tv) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
            .verticalScroll(rememberScrollState())
            .padding(vertical = 12.dp)
            .then(
                when (style) {
                    UiStyle.Jrpg -> Modifier.padding(horizontal = 6.dp).rpgWindow(theme)
                    UiStyle.Glass -> Modifier.padding(horizontal = 6.dp).glass(theme, androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
                    else -> Modifier
                },
            )
            .selectableGroup(),
    ) {
        tabs.forEach { tab ->
            val isSelected = tab == selected
            val pop by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (isSelected) 1.08f else 1f,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f),
                label = "pop",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .tabCenter(tab.ordinal)
                    .then(if (isSelected) Modifier.focusRequester(first) else Modifier)
                    .clip(lookShape(12.dp))
                    .then(
                        when {
                            !isSelected -> Modifier
                            style == UiStyle.Konbini -> Modifier.background(theme.foreground)
                            style == UiStyle.Glass -> Modifier.clip(CircleShape).background(if (theme.dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.07f))
                            style == UiStyle.Genga -> Modifier.drawBehind {
                                val step = 6.dp.toPx()
                                var x = -size.height
                                while (x < size.width) {
                                    drawLine(theme.primary.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5.dp.toPx())
                                    x += step
                                }
                            }
                            else -> Modifier
                        },
                    )
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                    .padding(vertical = 8.dp)
                    .semantics { contentDescription = tab.label },
            ) {
                Box(Modifier.lookAlive(Alive.Tab, active = isSelected).tabNews(!isSelected && hasNews(tab))) {
                    TabMark(tab, tab.ordinal, isSelected, style, pop)
                }
                Spacer(Modifier.height(3.dp))
                val color = when {
                    style == UiStyle.Konbini && isSelected -> theme.background
                    style == UiStyle.Jrpg && isSelected -> theme.accent
                    isSelected -> if (style == UiStyle.Glass || style == UiStyle.Eki) theme.ink else theme.primary
                    else -> theme.foreground.copy(alpha = 0.75f)
                }
                val text = if (style == UiStyle.Genga || style == UiStyle.Konbini) tab.label.uppercase() else tab.label
                BasicText(
                    text,
                    maxLines = 2,
                    style = when (style) {
                        UiStyle.Genga, UiStyle.Konbini -> NumberStyle(color = color, fontSize = 10.sp, bold = isSelected)
                        UiStyle.Bunko -> TitleStyle(color = color, fontSize = 11.sp)
                        else -> TextStyle(color = color, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center)
                    },
                )
            }
        }
    }
}
