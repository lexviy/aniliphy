package com.aniliphy.anime

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** How much the picture is sharpened on the GPU. */
enum class SharpenLevel(val label: String, val amount: Float, val lines: Float) {
    Off("Off", 0f, 0f),
    Light("Light", 0.35f, 0f),
    Strong("Strong", 0.7f, 1f),
    ;

    /** One step less, for when the phone can't keep up. */
    fun lower(): SharpenLevel = entries[(ordinal - 1).coerceAtLeast(0)]
}

/** The level chosen for one title (null: the default from Anime settings). */
class SharpenTitles(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sharpen_titles", Context.MODE_PRIVATE)

    fun get(mediaId: Int): SharpenLevel? = prefs.getString(mediaId.toString(), null)?.let { name -> SharpenLevel.entries.firstOrNull { it.name == name } }

    fun set(mediaId: Int, level: SharpenLevel?) {
        prefs.edit().apply { if (level == null) remove(mediaId.toString()) else putString(mediaId.toString(), level.name) }.apply()
    }
}

/**
 * Counts dropped frames: more than [limit] within [windowMs] means the phone isn't keeping up with the effect, and
 * it should step down. After a step it waits a window before judging again.
 */
class SharpenGuard(private val windowMs: Long = 10_000, private val limit: Int = 24) {
    private val drops = ArrayDeque<Pair<Long, Int>>()
    private var quietUntil = 0L

    /** [count] frames were dropped at [nowMs]; true when it's time to step down. */
    fun dropped(count: Int, nowMs: Long): Boolean {
        if (nowMs < quietUntil) return false
        drops += nowMs to count
        while (drops.isNotEmpty() && nowMs - drops.first().first > windowMs) drops.removeFirst()
        if (drops.sumOf { it.second } <= limit) return false
        drops.clear()
        quietUntil = nowMs + windowMs
        return true
    }

    fun reset() {
        drops.clear()
        quietUntil = 0
    }
}

/**
 * Sharpening made for anime: lines and flat colours. The frame is enlarged to about 1080 lines when smaller, each
 * pixel pushed away from its four neighbours (kept within their range, so no halos), and at Strong the pixels darker
 * than their surroundings (line art) made a little darker still.
 */
@OptIn(UnstableApi::class)
class SharpenEffect(private val level: SharpenLevel) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(level, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = level == SharpenLevel.Off

    private class Program(private val level: SharpenLevel, useHdr: Boolean) : BaseGlShaderProgram(useHdr, 1) {
        private val program = GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            program.setFloatsUniform("uTexel", floatArrayOf(1f / inputWidth, 1f / inputHeight))
            if (inputHeight >= TARGET_HEIGHT) return Size(inputWidth, inputHeight)
            val scale = (TARGET_HEIGHT.toFloat() / inputHeight).coerceAtMost(2f)
            return Size((inputWidth * scale).toInt() / 2 * 2, (inputHeight * scale).toInt() / 2 * 2)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatUniform("uAmount", level.amount)
            program.setFloatUniform("uLines", level.lines)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            program.delete()
        }
    }

    companion object {
        private const val TARGET_HEIGHT = 1080

        private const val VERTEX = """#version 100
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        private const val FRAGMENT = """#version 100
precision highp float;
uniform sampler2D uTexSampler;
uniform vec2 uTexel;
uniform float uAmount;
uniform float uLines;
varying vec2 vTexSamplingCoord;
const vec3 LUMA = vec3(0.299, 0.587, 0.114);
void main() {
  vec2 t = vTexSamplingCoord;
  vec4 centre = texture2D(uTexSampler, t);
  vec3 c = centre.rgb;
  vec3 n = texture2D(uTexSampler, t + vec2(0.0, uTexel.y)).rgb;
  vec3 s = texture2D(uTexSampler, t - vec2(0.0, uTexel.y)).rgb;
  vec3 e = texture2D(uTexSampler, t + vec2(uTexel.x, 0.0)).rgb;
  vec3 w = texture2D(uTexSampler, t - vec2(uTexel.x, 0.0)).rgb;
  vec3 lo = min(c, min(min(n, s), min(e, w)));
  vec3 hi = max(c, max(max(n, s), max(e, w)));
  vec3 sharp = clamp(c + uAmount * (4.0 * c - n - s - e - w), lo, hi);
  float l = dot(sharp, LUMA);
  float around = dot((n + s + e + w) * 0.25, LUMA);
  float line = clamp((around - l) * 4.0, 0.0, 1.0) * uLines;
  gl_FragColor = vec4(sharp * (1.0 - 0.35 * line), centre.a);
}
"""
    }
}
