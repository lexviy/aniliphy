package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CustomThemeScreen(appearance: AppearanceState, onBack: () -> Unit) {
    LaunchedEffect(Unit) { appearance.select(appearance.customTheme) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }

    @Composable
    fun colorRow(name: String, value: Int, onChange: (Int) -> Unit) {
        ColorRow(
            name = name,
            value = value,
            expanded = expanded == name,
            onToggle = { expanded = if (expanded == name) null else name },
            onChange = onChange,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Custom theme", onBack = onBack)
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            ThemeCard(theme = appearance.customTheme, selected = true, onClick = {})
        }
        colorRow("Background", appearance.customBackground) { appearance.customBackground = it }
        colorRow("Surface", appearance.customSurface) { appearance.customSurface = it }
        colorRow("Text", appearance.customForeground) { appearance.customForeground = it }
        colorRow("Primary", appearance.customPrimary) { appearance.customPrimary = it }
        colorRow("Secondary", appearance.customSecondary) { appearance.customSecondary = it }
        colorRow("Accent", appearance.customAccent) { appearance.customAccent = it }
    }
}

@Composable
private fun ColorRow(
    name: String,
    value: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (Int) -> Unit,
) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFF000000.toInt() or value))
                .border(1.dp, theme.foreground.copy(alpha = 0.4f), CircleShape),
        )
        Spacer(Modifier.width(16.dp))
        BasicText(name, style = TextStyle(color = theme.foreground, fontSize = 15.sp), modifier = Modifier.weight(1f))
        BasicText(
            "#%06X".format(value and 0xFFFFFF),
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp),
        )
    }
    if (expanded) {
        listOf("Red" to 16, "Green" to 8, "Blue" to 0).forEach { (channel, shift) ->
            val current = (value shr shift) and 0xFF
            SliderRow(
                label = channel,
                value = current.toFloat(),
                valueRange = 0f..255f,
                valueLabel = current.toString(),
                onChange = { new ->
                    val mask = 0xFF shl shift
                    onChange((value and mask.inv()) or (new.toInt() shl shift))
                },
            )
        }
    }
}
