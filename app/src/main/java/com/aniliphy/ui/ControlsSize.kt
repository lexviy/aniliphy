package com.aniliphy.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

internal const val MIN_CONTROLS_SIZE = 0.7f
internal const val MAX_CONTROLS_SIZE = 1.5f

@Composable
internal fun SizedControls(size: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scale = size.coerceIn(MIN_CONTROLS_SIZE, MAX_CONTROLS_SIZE)
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale), content = content)
}

@Composable
internal fun ControlsSizeRow(value: Float, onChange: (Float) -> Unit) {
    var shown by remember(value) { mutableFloatStateOf(value.coerceIn(MIN_CONTROLS_SIZE, MAX_CONTROLS_SIZE)) }
    SliderRow(
        label = "Controls size",
        value = shown,
        valueRange = MIN_CONTROLS_SIZE..MAX_CONTROLS_SIZE,
        valueLabel = "${(shown * 100).roundToInt()}%",
        onChange = { shown = (it * 20).roundToInt() / 20f },
        onChangeFinished = { if (shown != value) onChange(shown) },
    )
}
