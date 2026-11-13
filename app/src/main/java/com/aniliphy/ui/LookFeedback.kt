package com.aniliphy.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import com.aniliphy.R
import kotlinx.coroutines.launch

/*
 * What the looks feel and sound like: a buzz in each look's rhythm (straight to the vibrator, so it works on phones
 * whose touch feedback is off or too weak to feel the system's small ticks) and a sound set per look, synthesized by
 * scripts/look-sounds.py. Both have their own switch in Appearance.
 */

/** A buzz in the look's own rhythm. */
internal object LookHaptics {
    /** One beat of a pattern: what it is, how strong (0..1), and how long after the beat before it. */
    private class Beat(val kind: Kind, val scale: Float, val gapMs: Int = 0)

    private enum class Kind(val primitive: Int, val minSdk: Int, val ms: Long, val amplitude: Int) {
        Tick(VibrationEffect.Composition.PRIMITIVE_TICK, 30, 12, 110),
        LowTick(8 /* PRIMITIVE_LOW_TICK */, 31, 14, 80),
        Click(VibrationEffect.Composition.PRIMITIVE_CLICK, 30, 20, 190),
        Thud(6 /* PRIMITIVE_THUD */, 31, 40, 255),
        Rise(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 30, 30, 150),
        SlowRise(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 30, 60, 110),
        Fall(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 30, 30, 150),
    }

    private fun beats(kind: Kind, scale: Float, times: Int, gapMs: Int) = List(times) { Beat(kind, scale, if (it == 0) 0 else gapMs) }

    private fun vibrator(context: Context): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        return vibrator?.takeIf { it.hasVibrator() }
    }

    private fun play(context: Context, pattern: List<Beat>, delayMs: Long = 0, view: View? = null) {
        if (!LookPrefs.haptics || pattern.isEmpty()) return
        val vibrator = vibrator(context) ?: return
        val effect = effect(vibrator, pattern)
        val run = {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Media usage: the app's own feedback, not the system's touch feedback (often switched off).
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
                } else {
                    vibrator.vibrate(effect)
                }
            } catch (_: SecurityException) {
            }
        }
        if (delayMs > 0 && view != null) view.postDelayed({ run() }, delayMs) else run()
    }

    private fun effect(vibrator: Vibrator, pattern: List<Beat>): VibrationEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val ids = pattern.map { it.kind }.distinct()
            if (ids.all { Build.VERSION.SDK_INT >= it.minSdk } && vibrator.areAllPrimitivesSupported(*ids.map { it.primitive }.toIntArray())) {
                val composition = VibrationEffect.startComposition()
                pattern.forEach { composition.addPrimitive(it.kind.primitive, it.scale.coerceIn(0f, 1f), it.gapMs) }
                return composition.compose()
            }
        }
        // Without primitives: a waveform of short pulses, softer ones weaker where the motor can vary its strength.
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        pattern.forEach { beat ->
            timings += beat.gapMs.toLong()
            amplitudes += 0
            timings += beat.kind.ms
            amplitudes += (beat.kind.amplitude * (0.4f + 0.6f * beat.scale)).toInt().coerceIn(1, 255)
        }
        return if (vibrator.hasAmplitudeControl()) {
            VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1)
        } else {
            VibrationEffect.createWaveform(timings.toLongArray(), -1)
        }
    }

    /** Switching tabs. */
    fun tab(view: View, style: UiStyle) {
        val (pattern, delay) = when (style) {
            UiStyle.Tankobon -> listOf(Beat(Kind.Click, 0.8f)) to 0L
            UiStyle.Modern -> listOf(Beat(Kind.Tick, 0.7f)) to 0L
            UiStyle.Genga -> beats(Kind.Tick, 0.7f, 2, 83) to 0L
            UiStyle.Bunko -> listOf(Beat(Kind.SlowRise, 0.35f), Beat(Kind.Click, 0.5f)) to 0L
            UiStyle.Shinya -> listOf(Beat(Kind.Thud, 0.6f)) to 0L
            UiStyle.Jrpg -> beats(Kind.Click, 0.6f, 2, 70) to 0L
            UiStyle.Eki -> beats(Kind.Tick, 0.55f, 4, 45) to 0L
            UiStyle.Konbini -> beats(Kind.LowTick, 0.8f, 5, 50) to 0L
            UiStyle.Stamp -> listOf(Beat(Kind.Thud, 1f)) to Motion.ms(200).toLong()
            UiStyle.Glass -> listOf(Beat(Kind.Rise, 0.4f), Beat(Kind.Tick, 0.4f)) to 0L
        }
        play(view.context, pattern, delay, view)
    }

    /** An episode or chapter marked as seen, or another of the look's moments. */
    fun moment(view: View, style: UiStyle) {
        val pattern = when (style) {
            UiStyle.Stamp -> listOf(Beat(Kind.Thud, 1f), Beat(Kind.Click, 0.5f, 120))
            UiStyle.Tankobon -> listOf(Beat(Kind.Click, 1f), Beat(Kind.Thud, 0.8f, 60))
            UiStyle.Eki -> beats(Kind.Tick, 0.6f, 6, 45) + Beat(Kind.Click, 0.8f, 60)
            UiStyle.Konbini -> beats(Kind.LowTick, 0.8f, 6, 50) + Beat(Kind.Click, 0.7f, 80)
            UiStyle.Jrpg -> beats(Kind.Click, 0.6f, 3, 75) + Beat(Kind.Click, 1f, 75)
            UiStyle.Genga -> beats(Kind.Tick, 0.7f, 3, 83)
            UiStyle.Bunko -> listOf(Beat(Kind.SlowRise, 0.4f), Beat(Kind.Thud, 0.7f))
            UiStyle.Shinya -> listOf(Beat(Kind.Thud, 0.7f), Beat(Kind.Tick, 0.5f, 150))
            UiStyle.Glass -> listOf(Beat(Kind.Rise, 0.5f), Beat(Kind.Fall, 0.4f, 40))
            UiStyle.Modern -> listOf(Beat(Kind.Click, 0.8f), Beat(Kind.Click, 0.6f, 90))
        }
        play(view.context, pattern)
    }

    /** Holding a card down. */
    fun hold(view: View) = play(view.context, listOf(Beat(Kind.Click, 1f)))
}

/** The look's sounds: a tap for buttons and cards, one for changing tabs, and one for finishing something. */
internal object LookSounds {
    private enum class Kind { Tap, Tab, Done }

    private var pool: SoundPool? = null
    private var audio: AudioManager? = null
    private val ids = HashMap<Pair<UiStyle, Kind>, Int>()
    private val loaded = HashSet<Int>()
    private var lastTap = 0
    private var lastTapAt = 0L

    /** While something else is the sound (the player is open), the interface keeps quiet. */
    @Volatile var muted = false

    fun init(context: Context) {
        if (pool != null) return
        val app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sounds = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attributes).build()
        sounds.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (style in UiStyle.entries) {
            for (kind in Kind.entries) ids[style to kind] = sounds.load(app, raw(style, kind), 1)
        }
        pool = sounds
    }

    private fun play(style: UiStyle, kind: Kind, volume: Float): Int {
        if (!LookPrefs.sounds || muted) return 0
        val sounds = pool ?: return 0
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return 0
        val id = ids[style to kind] ?: return 0
        if (synchronized(loaded) { id !in loaded }) return 0
        return sounds.play(id, volume, volume, 1, 0, 1f)
    }

    /** A button, card or row pressed. */
    fun tap() {
        lastTap = play(Look.style, Kind.Tap, 0.55f)
        lastTapAt = SystemClock.uptimeMillis()
    }

    /** Changing tabs; the tab's own tap gives way to it. */
    fun tab(style: UiStyle) {
        if (SystemClock.uptimeMillis() - lastTapAt < 250 && lastTap != 0) pool?.stop(lastTap)
        play(style, Kind.Tab, 0.7f)
    }

    /** Something finished: an episode or chapter seen, a milestone. */
    fun done(style: UiStyle) {
        play(style, Kind.Done, 0.8f)
    }

    /** Something starting while the app is open (new chapters, an episode about to air): the look's sound, very low. */
    fun cue(style: UiStyle) {
        play(style, Kind.Done, 0.18f)
    }

    private fun raw(style: UiStyle, kind: Kind): Int = when (style) {
        UiStyle.Tankobon -> when (kind) { Kind.Tap -> R.raw.look_tankobon_tap; Kind.Tab -> R.raw.look_tankobon_tab; Kind.Done -> R.raw.look_tankobon_done }
        UiStyle.Modern -> when (kind) { Kind.Tap -> R.raw.look_modern_tap; Kind.Tab -> R.raw.look_modern_tab; Kind.Done -> R.raw.look_modern_done }
        UiStyle.Genga -> when (kind) { Kind.Tap -> R.raw.look_genga_tap; Kind.Tab -> R.raw.look_genga_tab; Kind.Done -> R.raw.look_genga_done }
        UiStyle.Bunko -> when (kind) { Kind.Tap -> R.raw.look_bunko_tap; Kind.Tab -> R.raw.look_bunko_tab; Kind.Done -> R.raw.look_bunko_done }
        UiStyle.Shinya -> when (kind) { Kind.Tap -> R.raw.look_shinya_tap; Kind.Tab -> R.raw.look_shinya_tab; Kind.Done -> R.raw.look_shinya_done }
        UiStyle.Jrpg -> when (kind) { Kind.Tap -> R.raw.look_jrpg_tap; Kind.Tab -> R.raw.look_jrpg_tab; Kind.Done -> R.raw.look_jrpg_done }
        UiStyle.Eki -> when (kind) { Kind.Tap -> R.raw.look_eki_tap; Kind.Tab -> R.raw.look_eki_tab; Kind.Done -> R.raw.look_eki_done }
        UiStyle.Konbini -> when (kind) { Kind.Tap -> R.raw.look_konbini_tap; Kind.Tab -> R.raw.look_konbini_tab; Kind.Done -> R.raw.look_konbini_done }
        UiStyle.Stamp -> when (kind) { Kind.Tap -> R.raw.look_stamp_tap; Kind.Tab -> R.raw.look_stamp_tab; Kind.Done -> R.raw.look_stamp_done }
        UiStyle.Glass -> when (kind) { Kind.Tap -> R.raw.look_glass_tap; Kind.Tab -> R.raw.look_glass_tab; Kind.Done -> R.raw.look_glass_done }
    }
}

/**
 * Wraps the app's press indication so every clickable thing (buttons, cards, rows, tabs) plays the look's tap when a
 * press ends in a click, without touching each one. Scrolls and cancelled presses stay quiet.
 */
internal class SoundIndication(private val inner: Indication) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        SoundNode(interactionSource, inner as? IndicationNodeFactory)

    override fun equals(other: Any?): Boolean = other is SoundIndication && other.inner == inner
    override fun hashCode(): Int = inner.hashCode() * 31 + 7
}

private class SoundNode(private val source: InteractionSource, inner: IndicationNodeFactory?) : DelegatingNode() {
    init {
        inner?.let { delegate(it.create(source)) }
    }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { if (it is PressInteraction.Release) LookSounds.tap() }
        }
    }
}
