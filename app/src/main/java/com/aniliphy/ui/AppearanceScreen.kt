package com.aniliphy.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardShape = RoundedCornerShape(12.dp)
private val CardWidth = 156.dp
private val CardHeight = 112.dp
private val RowPadding = PaddingValues(horizontal = 20.dp)

@Composable
fun AppearanceScreen(appearance: AppearanceState, onBack: () -> Unit, onEditCustomTheme: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Appearance", onBack = onBack)
        StyleSection(appearance)
        ThemeSection(appearance, onEditCustomTheme)
        ShaderSection(appearance)
        AnimationSection(appearance)
        TextSection(appearance)
        CoverSection(appearance)
        BottomBarSection(appearance)
        LookExtrasSection(appearance)
        AppIconSection()
    }
}

@Composable
private fun ThemeSection(appearance: AppearanceState, onEditCustomTheme: () -> Unit) {
    val current = LocalAppTheme.current
    SectionTitle("Theme")
    val themes = appearance.allThemes
    LazyRow(
        state = rememberLazyListState(themes.indexOfFirst { appearance.isSelected(it) }.coerceAtLeast(0)),
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(themes, key = { it.name }) { theme ->
            ThemeCard(
                theme = theme,
                selected = appearance.isSelected(theme),
                onClick = { appearance.select(theme) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    SwitchRow(
        label = "Follow system",
        description = if (appearance.followSystem) {
            "Tap a dark and a light theme: each is used with the phone's matching mode"
        } else {
            "Switch between a dark and a light theme with the phone's mode"
        },
        checked = appearance.followSystem,
        onChange = { appearance.followSystem = it },
    )
    SwitchRow(
        label = "Pure black (AMOLED)",
        description = if (current.dark) {
            "Black background, saves battery on these screens"
        } else {
            "Only applies to dark themes"
        },
        checked = appearance.amoled,
        onChange = { appearance.amoled = it },
    )
    BasicText(
        text = "Edit custom theme",
        style = TextStyle(color = current.foreground, fontSize = 15.sp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEditCustomTheme)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    )
    BasicText(
        text = "Accent color",
        style = TextStyle(color = current.foreground, fontSize = 15.sp),
        modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 8.dp),
    )
    LazyRow(
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(AccentColor.entries, key = { it.name }) { accent ->
            AccentSwatch(
                accent = accent,
                selected = accent == appearance.accent,
                onClick = { appearance.accent = accent },
            )
        }
    }
}

@Composable
internal fun ThemeCard(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(CardWidth)
            .height(CardHeight)
            .clip(CardShape)
            .background(theme.background)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) theme.primary else theme.panel,
                shape = CardShape,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                BasicText(
                    text = "Aa",
                    style = TextStyle(color = theme.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                )
                BasicText(
                    text = theme.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = theme.primary, fontSize = 12.sp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(theme.surface)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                listOf(
                    theme.primary,
                    theme.secondary,
                    theme.accent,
                    theme.success,
                    theme.warning,
                    theme.error,
                ).forEach { color ->
                    Box(Modifier.size(13.dp).clip(CircleShape).background(color))
                }
            }
        }
        if (selected) CheckMark(theme.primary, theme.background, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun AccentSwatch(accent: AccentColor, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    val color = accent.color(theme.dark)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (color != null) {
                        Brush.linearGradient(listOf(color, color))
                    } else {
                        Brush.sweepGradient(listOf(theme.secondary, theme.accent, theme.success, theme.secondary))
                    },
                )
                .then(if (selected) Modifier.border(2.5.dp, theme.foreground, CircleShape) else Modifier),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(accent.label, style = TextStyle(color = theme.foreground, fontSize = 11.sp))
    }
}

@Composable
private fun ShaderSection(appearance: AppearanceState) {
    val theme = LocalAppTheme.current
    SectionTitle("Shader")
    if (!ShadersSupported) {
        BasicText(
            text = "Shaders need Android 13 or newer.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp),
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        return
    }
    LazyRow(
        state = rememberLazyListState(appearance.shader.ordinal),
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ShaderKind.entries, key = { it.name }) { kind ->
            ShaderCard(
                kind = kind,
                mode = appearance.colorVisionMode,
                selected = kind == appearance.shader,
                onClick = { appearance.shader = kind },
            )
        }
    }
    if (appearance.shader == ShaderKind.ColorVision) {
        Spacer(Modifier.height(6.dp))
        SegmentedRow(
            label = "Color vision type",
            options = ColorVisionMode.entries,
            selected = appearance.colorVisionMode,
            optionLabel = { it.label },
            onSelect = { appearance.colorVisionMode = it },
        )
    }
}

@Composable
private fun ShaderCard(kind: ShaderKind, mode: ColorVisionMode, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        Modifier
            .width(CardWidth)
            .height(CardHeight)
            .clip(CardShape)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) theme.primary else theme.panel,
                shape = CardShape,
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .shaderEffect(kind, theme.primary, mode)
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFFE5484D),
                            Color(0xFFF2B44A),
                            Color(0xFF4CC38A),
                            Color(0xFF3E8BE8),
                            Color(0xFF9B6BE3),
                        ),
                    ),
                )
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = "Aa",
                style = TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                text = kind.label,
                maxLines = 1,
                style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selected) {
            CheckMark(
                theme.primary,
                theme.background,
                Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}

@Composable
private fun AnimationSection(appearance: AppearanceState) {
    SectionTitle("Animation")
    LazyRow(
        state = rememberLazyListState(appearance.animation.ordinal),
        contentPadding = RowPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(SceneKind.entries, key = { it.name }) { kind ->
            AnimationCard(
                kind = kind,
                intensity = appearance.animationIntensity,
                speed = appearance.animationSpeed.factor,
                selected = kind == appearance.animation,
                onClick = { appearance.animation = kind },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    SliderRow(
        label = "Opacity",
        value = appearance.animationIntensity,
        valueRange = 0.05f..1f,
        valueLabel = "${(appearance.animationIntensity * 100).toInt()}%",
        onChange = { appearance.animationIntensity = it },
    )
    SegmentedRow(
        label = "Speed",
        options = AnimationSpeed.entries,
        selected = appearance.animationSpeed,
        optionLabel = { it.label },
        onSelect = { appearance.animationSpeed = it },
    )
    SwitchRow(
        label = "Only on the home page",
        description = "Show the animation only in the Anilist section",
        checked = appearance.animationHomeOnly,
        onChange = { appearance.animationHomeOnly = it },
    )
    SwitchRow(
        label = "Battery saver",
        description = "Stop the animation at 20% battery or in the phone's power saving mode",
        checked = appearance.animationBatterySaver,
        onChange = { appearance.animationBatterySaver = it },
    )
}

@Composable
private fun AnimationCard(
    kind: SceneKind,
    intensity: Float,
    speed: Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalAppTheme.current
    Box(
        Modifier
            .width(CardWidth)
            .height(CardHeight)
            .clip(CardShape)
            .background(theme.surface)
            .border(
                width = if (selected) 2.5.dp else 1.dp,
                color = if (selected) theme.primary else theme.panel,
                shape = CardShape,
            )
            .clickable(onClick = onClick),
    ) {
        Scene(kind, theme, Modifier.fillMaxSize().alpha(intensity), speed)
        BasicText(
            text = kind.label,
            maxLines = 1,
            style = TextStyle(color = theme.foreground, fontSize = 12.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(theme.surface.copy(alpha = 0.85f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        if (selected) {
            CheckMark(
                theme.primary,
                theme.background,
                Modifier.align(Alignment.TopEnd).padding(10.dp),
            )
        }
    }
}

@Composable
private fun CheckMark(background: Color, tick: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(20.dp)) {
        drawCircle(background)
        val u = size.minDimension / 20f
        val corner = Offset(8.5f, 14f) * u
        listOf(Offset(5f, 10.5f), Offset(15f, 6f)).forEach { end ->
            drawLine(tick, corner, end * u, strokeWidth = 2f * u, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun StyleSection(appearance: AppearanceState) {
    val theme = LocalAppTheme.current
    SectionTitle("Style")
    // Ten looks don't fit a segmented row: a two-column grid of name and one line about it.
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 20.dp).selectableGroup(),
    ) {
        UiStyle.entries.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { style ->
                    val selected = appearance.uiStyle == style
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(lookShape(10.dp))
                            .background(if (selected) theme.primary else theme.surface)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { appearance.uiStyle = style })
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        BasicText(style.label, maxLines = 1, style = TitleStyle(color = if (selected) theme.background else theme.ink, fontSize = 15.sp))
                        BasicText(
                            style.blurb,
                            maxLines = 2,
                            style = TextStyle(color = (if (selected) theme.background else theme.foreground).copy(alpha = 0.8f), fontSize = 11.sp),
                        )
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TextSection(appearance: AppearanceState) {
    SectionTitle("Text")
    SegmentedRow(
        label = "Text size",
        options = TextSize.entries,
        selected = appearance.textSize,
        optionLabel = { it.label },
        onSelect = { appearance.textSize = it },
    )
}

@Composable
private fun CoverSection(appearance: AppearanceState) {
    SectionTitle("Covers")
    SegmentedRow(
        label = "Corners",
        options = CoverCorners.entries,
        selected = appearance.coverCorners,
        optionLabel = { it.label },
        onSelect = { appearance.coverCorners = it },
    )
    SegmentedRow(
        label = "Layout",
        options = CoverLayout.entries,
        selected = appearance.coverLayout,
        optionLabel = { it.label },
        onSelect = { appearance.coverLayout = it },
    )
    CoverPreview(appearance.coverCorners, appearance.coverLayout)
}

@Composable
private fun CoverPreview(corners: CoverCorners, layout: CoverLayout) {
    val theme = LocalAppTheme.current
    val shape = RoundedCornerShape(if (corners == CoverCorners.Rounded) 10.dp else 0.dp)
    val colors = listOf(theme.primary, theme.secondary, theme.accent)

    @Composable
    fun cover(width: Dp, height: Dp, color: Color) {
        Box(
            Modifier
                .width(width)
                .height(height)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.45f)))),
        )
    }

    if (layout == CoverLayout.Grid) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            colors.forEach { cover(72.dp, 104.dp, it) }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            colors.forEachIndexed { index, color ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cover(36.dp, 52.dp, color)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier
                                .width((150 - index * 24).dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(theme.foreground.copy(alpha = 0.5f)),
                        )
                        Box(
                            Modifier
                                .width(70.dp)
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(theme.foreground.copy(alpha = 0.25f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarSection(appearance: AppearanceState) {
    SectionTitle("Bottom bar")
    SwitchRow(
        label = "Show labels",
        description = "Turn off to show only the symbols",
        checked = appearance.barShowLabels,
        onChange = { appearance.barShowLabels = it },
    )
    SwitchRow(
        label = "Hide when scrolling",
        description = "Scroll down to hide the bar, scroll up to bring it back",
        checked = appearance.barHideOnScroll,
        onChange = { appearance.barHideOnScroll = it },
    )
}

@Composable
private fun LookExtrasSection(appearance: AppearanceState) {
    SectionTitle("Moving around")
    SwitchRow(
        label = "Swipe between tabs",
        description = "Swipe the screen left or right to go to the next tab",
        checked = appearance.tabSwipe,
        onChange = { appearance.tabSwipe = it },
    )
    SwitchRow(
        label = "Look's tab animation",
        description = "Each look changes tabs its own way. Off uses a plain slide",
        checked = appearance.lookTabMotion,
        onChange = { appearance.lookTabMotion = it },
    )
    SwitchRow(
        label = "Look animations",
        description = "Small things moving in the interface: cursors, lamps, ribbons, pencil lines. Off leaves them still. Also off when the phone removes animations",
        checked = appearance.lookUiMotion,
        onChange = { appearance.lookUiMotion = it },
    )
    if (appearance.lookUiMotion) {
        LookAnimationPreview(Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        SegmentedRow(
            label = "Strength",
            options = LookIntensity.entries,
            selected = appearance.lookUiIntensity,
            optionLabel = { it.label },
            onSelect = { appearance.lookUiIntensity = it },
        )
        SwitchRow(label = "On the selected tab", checked = appearance.lookUiTabs, onChange = { appearance.lookUiTabs = it })
        SwitchRow(label = "On headings", checked = appearance.lookUiHeadings, onChange = { appearance.lookUiHeadings = it })
        SwitchRow(
            label = "On cards and buttons",
            description = "Up next, the stats on a title's page, Watch and Read, your profile, menus",
            checked = appearance.lookUiCards,
            onChange = { appearance.lookUiCards = it },
        )
        SwitchRow(
            label = "On small things",
            description = "NEW stickers, countdowns about to air, the search label, tab news",
            checked = appearance.lookUiSmall,
            onChange = { appearance.lookUiSmall = it },
        )
        SwitchRow(
            label = "Pause when idle",
            description = "Everything rests after 20 seconds without a touch, and moves again when you touch the screen",
            checked = appearance.lookUiIdlePause,
            onChange = { appearance.lookUiIdlePause = it },
        )
    }
    SwitchRow(
        label = "Look moments",
        description = "Pull to refresh, empty lists and finishing an episode or chapter drawn the look's way",
        checked = appearance.lookMoments,
        onChange = { appearance.lookMoments = it },
    )
    SwitchRow(
        label = "Vibration",
        description = "A small buzz in the look's rhythm when changing tabs and finishing something",
        checked = appearance.lookHaptics,
        onChange = { appearance.lookHaptics = it },
    )
    SwitchRow(
        label = "Sounds",
        description = "The look's own sounds for taps, changing tabs and finishing something. Quiet in silent mode and while a video plays",
        checked = appearance.lookSounds,
        onChange = { appearance.lookSounds = it },
    )
    ActionRow(
        label = "Preview finishing an episode",
        description = "Shows the look's moment without marking anything",
        enabled = appearance.lookMoments,
        onClick = { LookMoment.fire(episode = true, number = 12) },
    )
    ActionRow(
        label = "Preview the end of a chapter",
        description = "As a reader shows it, with the next chapter",
        enabled = appearance.lookMoments,
        onClick = { LookMoment.fire(episode = false, number = 138, next = "Chapter 139") },
    )
    ActionRow(
        label = "Preview completing a title",
        enabled = appearance.lookMoments,
        onClick = { LookMoment.fire(episode = true, number = 12, total = 12) },
    )
    ActionRow(
        label = "Preview a milestone",
        enabled = appearance.lookMoments,
        onClick = { LookMoment.milestone(LookWords.milestone(momentStyle, "1,000 episodes watched")) },
    )
}

@Composable
private fun AppIconSection() {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcon.current(context)) }
    SectionTitle("App icon")
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        AppIcon.entries.forEach { icon ->
            val selected = icon == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        AppIcon.select(context, icon)
                        current = icon
                    }
                    .padding(4.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(icon.background)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) theme.primary else theme.panel,
                            shape = CircleShape,
                        ),
                ) {
                    Image(
                        painter = painterResource(icon.foreground),
                        contentDescription = null,
                        modifier = Modifier.size(58.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                BasicText(icon.label, style = TextStyle(color = theme.foreground, fontSize = 12.sp))
            }
        }
    }
}
