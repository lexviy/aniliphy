package com.aniliphy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
internal fun BackHeader(title: String, onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .height(60.dp)
            .padding(horizontal = 20.dp),
    ) {
        Canvas(Modifier.size(24.dp)) {
            val u = size.minDimension / 24f
            listOf(Offset(14.5f, 5f), Offset(14.5f, 19f)).forEach { end ->
                drawLine(
                    color = theme.primary,
                    start = Offset(7.5f, 12f) * u,
                    end = end * u,
                    strokeWidth = 2f * u,
                    cap = StrokeCap.Round,
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        BasicText(
            text = title,
            style = TextStyle(color = theme.foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold),
        )
    }
}

/**
 * A section's heading, set large. Tankōbon adds a rule running to the edge, and [number], when given, in front like
 * a chapter number ("01").
 */
@Composable
internal fun SectionTitle(title: String, number: Int? = null) {
    val theme = LocalAppTheme.current
    if (Look.modern) {
        BasicText(
            text = title,
            maxLines = 1,
            style = TitleStyle(color = theme.ink, fontSize = 21.sp),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp).lookAlive(Alive.Heading),
        )
        return
    }
    if (!Look.tankobon) {
        StyledHeading(title, number, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp))
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp).lookAlive(Alive.Heading),
    ) {
        if (number != null) {
            BasicText("%02d".format(number), style = NumberStyle(color = theme.primary, fontSize = 12.sp, bold = true))
            Spacer(Modifier.width(10.dp))
        }
        BasicText(text = title, maxLines = 1, style = TextStyle(color = theme.ink, fontSize = 20.sp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(2.dp).background(theme.rule))
    }
}

@Composable
internal fun Label(
    label: String,
    description: String?,
    modifier: Modifier = Modifier,
    color: Color = LocalAppTheme.current.foreground,
) {
    val theme = LocalAppTheme.current
    Column(modifier) {
        BasicText(label, style = TextStyle(color = color, fontSize = 15.sp))
        if (description != null) {
            BasicText(
                description,
                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
            )
        }
    }
}

@Composable
internal fun ActionRow(
    label: String,
    onClick: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
    color: Color = LocalAppTheme.current.foreground,
) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Label(label, description, color = color)
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalAppTheme.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(newLookShape(16.dp))
                .background(theme.surface)
                .padding(20.dp),
        ) {
            BasicText(
                title,
                style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(8.dp))
            BasicText(message, style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton("Cancel", theme.foreground, onDismiss)
                Spacer(Modifier.width(8.dp))
                DialogButton(confirmLabel, theme.error, onConfirm)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicText(label, style = TextStyle(color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = { onChange(!checked) })
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Label(label, description, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = theme.background,
                checkedTrackColor = theme.primary,
                checkedBorderColor = theme.primary,
                uncheckedThumbColor = theme.foreground.copy(alpha = 0.7f),
                uncheckedTrackColor = theme.surface,
                uncheckedBorderColor = theme.panel,
            ),
        )
    }
}

@Composable
internal fun <T> SegmentedRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    description: String? = null,
) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Label(label, description)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(newLookShape(10.dp))
                .background(theme.surface)
                .selectableGroup(),
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(newLookShape(10.dp))
                        .background(if (isSelected) theme.primary else theme.surface)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(vertical = 10.dp),
                ) {
                    BasicText(
                        optionLabel(option),
                        maxLines = 1,
                        style = TextStyle(
                            color = if (isSelected) theme.background else theme.foreground,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
    onChangeFinished: (() -> Unit)? = null,
) {
    val theme = LocalAppTheme.current
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText(label, style = TextStyle(color = theme.foreground, fontSize = 15.sp))
            BasicText(valueLabel, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp))
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = theme.primary,
                activeTrackColor = theme.primary,
                inactiveTrackColor = theme.panel,
            ),
        )
    }
}

@Composable
internal fun rememberNotificationAsk(): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    return {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            !com.aniliphy.work.ReleaseNotifications.allowed(context)
        ) {
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
internal fun BatteryCheckRow(visible: Boolean) {
    if (!visible) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    val limited = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(!power.isIgnoringBatteryOptimizations(context.packageName))
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        limited.value = !power.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    if (!limited.value) return
    ActionRow(
        label = "Check on time",
        description = "Android saves battery on this app, so new releases may be found hours late. Tap to let it check every half hour",
        onClick = {
            val ask = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${context.packageName}"),
            )
            runCatching { context.startActivity(ask) }.onFailure {
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        },
    )
}

/** Tankōbon and Modern keep their rounded settings controls; the other looks take their own corners. */
private fun newLookShape(radius: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.Shape =
    if (Look.tankobon || Look.modern) RoundedCornerShape(radius) else lookShape(radius)
