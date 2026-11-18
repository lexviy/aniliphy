package com.aniliphy.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anime.SleepTimer
import com.aniliphy.novels.AloudSleep
import com.aniliphy.novels.NovelSettings
import com.aniliphy.novels.ReadAloud
import com.aniliphy.novels.ReadAloudText
import java.util.Locale

private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f)

private fun aloudSpeedLabel(speed: Float) = String.format(Locale.US, "%.1f×", speed)

/** The bar while a book is read aloud: where it is, time left, and the controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadAloudBar(label: String, onMenu: () -> Unit, onSettings: () -> Unit, modifier: Modifier) {
    val ink = readerBarInk(video = false)
    val theme = LocalAppTheme.current
    val speed = ReadAloud.speed()
    val minutes = ReadAloudText.minutesLeft(ReadAloud.spoken, ReadAloud.index, speed)
    ReaderBar(top = false, insets = Modifier.navigationBarsPadding(), modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AloudWave(ReadAloud.playing, ink.text)
            Spacer(Modifier.width(8.dp))
            ReaderBarText(label, ink, Modifier.weight(1f))
            if (minutes > 0) ReaderBarText("~$minutes min left in chapter", ink, dim = true)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            PillButton("⏮", onClick = { ReadAloud.skip(-1) }, filled = false)
            PillButton(if (ReadAloud.playing) "❚❚" else "▶", onClick = { ReadAloud.toggle() }, alive = true)
            PillButton("⏭", onClick = { ReadAloud.skip(1) }, filled = false)
            PillButton(aloudSpeedLabel(speed), onClick = {
                ReadAloud.setSpeed(SPEEDS.firstOrNull { it > speed + 0.01f } ?: SPEEDS.first())
            }, filled = false)
            val sleep = ReadAloud.sleep
            PillButton(
                when {
                    sleep == AloudSleep.EndOfChapter -> "☾ chapter"
                    sleep.minutes != null -> "☾ " + SleepTimer.label(ReadAloud.sleepLeftMs ?: 0)
                    else -> "☾"
                },
                onClick = { ReadAloud.chooseSleep(AloudSleep.entries[(sleep.ordinal + 1) % AloudSleep.entries.size]) },
                filled = false,
                color = if (sleep != AloudSleep.Off) theme.warning else theme.primary,
            )
            PillButton("⚙", onClick = onSettings, filled = false)
            PillButton("☰", onClick = onMenu, filled = false)
            PillButton("✕", onClick = { ReadAloud.stop() }, filled = false)
        }
    }
}

/** Four bars that move while the voice speaks and rest when paused. */
@Composable
private fun AloudWave(moving: Boolean, color: Color) {
    val wave = rememberInfiniteTransition(label = "aloud")
    val heights = listOf(0.5f, 1f, 0.65f, 0.9f).mapIndexed { i, rest ->
        val phase by wave.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(380 + i * 90), RepeatMode.Reverse),
            label = "bar$i",
        )
        if (moving) phase else rest * 0.5f
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(14.dp)) {
        heights.forEach { h -> Box(Modifier.width(3.dp).height((14 * h).dp).background(color)) }
    }
}

/** ⚙: voice, speed, pitch, sleep timer and the pause between paragraphs. */
@Composable
internal fun ReadAloudSheet(settings: NovelSettings) {
    val theme = LocalAppTheme.current
    var chosen by remember { mutableStateOf(ReadAloud.voiceName()) }
    var speed by remember { mutableFloatStateOf(settings.aloudSpeed) }
    var pitch by remember { mutableFloatStateOf(settings.aloudPitch) }
    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
        SliderRow("Speed", speed, 0.5f..2.5f, aloudSpeedLabel(speed), onChange = { speed = it }, onChangeFinished = { ReadAloud.setSpeed(speed) })
        SliderRow("Pitch", pitch, 0.5f..2f, String.format(Locale.US, "%.1f", pitch), onChange = { pitch = it }, onChangeFinished = { ReadAloud.setPitch(pitch) })
        SegmentedRow(
            label = "Sleep timer",
            options = AloudSleep.entries,
            selected = ReadAloud.sleep,
            optionLabel = { if (it == AloudSleep.EndOfChapter) "Chapter end" else it.label },
            onSelect = { ReadAloud.chooseSleep(it) },
        )
        SwitchRow(
            "Pause between paragraphs",
            settings.aloudParagraphPause,
            { ReadAloud.setParagraphPause(it) },
            description = "A short breath after each paragraph",
        )
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            BasicText("Voice", style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            val voices = ReadAloud.voices
            if (voices.isEmpty()) Hint("The phone's voices show here once reading starts.")
            val numbers = HashMap<String, Int>()
            voices.forEach { voice ->
                val place = voice.locale.toLanguageTag()
                val number = (numbers[place] ?: 0) + 1
                numbers[place] = number
                val selected = voice.name == chosen
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            ReadAloud.setVoice(voice)
                            chosen = voice.name
                        }
                        .padding(vertical = 8.dp),
                ) {
                    BasicText(
                        "${voice.locale.displayName} · voice $number",
                        style = TextStyle(color = if (selected) theme.primary else theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    )
                    BasicText(
                        if (voice.isNetworkConnectionRequired) "needs internet · higher quality" else "on the phone · works offline",
                        style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
                    )
                }
            }
            Hint("Tap a voice to hear a sample. More voices: Android settings › Text-to-speech.")
        }
    }
}
