package com.aniliphy.ui

import android.graphics.Bitmap
import kotlin.math.pow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Rect
import android.graphics.Shader
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import android.graphics.Canvas as NativeCanvas
import android.graphics.Path as NativePath

enum class SceneKind(val label: String) {
    Off("Off"),
    Rain("Rain"),
    Snow("Snow"),
    Stars("Falling stars"),
    Sakura("Sakura"),
    Fireflies("Fireflies"),
    Waves("Waves"),
    Clouds("Clouds"),
    Lilies("Spider lilies"),
}

private const val FRAME_NANOS = 15_000_000L
private const val FADE_IN_MILLIS = 500
private const val TAU = (2.0 * PI).toFloat()
private const val DEGREES = 180f / PI.toFloat()

@Composable
private fun rememberSceneTime(speed: Float): State<Float> {
    val currentSpeed by rememberUpdatedState(speed)
    return produceState(0f) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsed = now - last
                if (elapsed >= FRAME_NANOS) {
                    last = now
                    value += elapsed / 1_000_000_000f * currentSpeed
                }
            }
        }
    }
}

private typealias SceneFrame = (canvas: NativeCanvas, t: Float) -> Unit

@Composable
fun Scene(kind: SceneKind, theme: AppTheme, modifier: Modifier = Modifier, speed: Float = 1f) {
    if (kind == SceneKind.Off) return
    val time = rememberSceneTime(speed)
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val frame by produceState<SceneFrame?>(null, kind, theme, size, density) {
        if (size.width > 0 && size.height > 0) {
            value = withContext(Dispatchers.Default) {
                buildScene(kind, Stage(size.width.toFloat(), size.height.toFloat(), density, theme))
            }
        }
    }
    val fade by animateFloatAsState(if (frame != null) 1f else 0f, tween(FADE_IN_MILLIS))
    Spacer(
        modifier
            .onSizeChanged { size = it }
            .graphicsLayer { alpha = fade }
            .drawBehind { frame?.invoke(drawContext.canvas.nativeCanvas, time.value) },
    )
}

private fun buildScene(kind: SceneKind, stage: Stage): SceneFrame = when (kind) {
    SceneKind.Off -> { _, _ -> }
    SceneKind.Rain -> rainScene(stage)
    SceneKind.Snow -> snowScene(stage)
    SceneKind.Stars -> starsScene(stage)
    SceneKind.Sakura -> sakuraScene(stage)
    SceneKind.Fireflies -> firefliesScene(stage)
    SceneKind.Waves -> wavesScene(stage)
    SceneKind.Clouds -> cloudsScene(stage)
    SceneKind.Lilies -> liliesScene(stage)
}

private fun rnd(index: Int, salt: Int): Float {
    var h = index * 374761393 + salt * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0x7fffffff) / 2147483648f
}

private fun fract(value: Float) = value - floor(value)

private class Stage(val width: Float, val height: Float, val dp: Float, val theme: AppTheme) {
    val unit = min(width, height)
    val longSide = max(width, height)

    val detail = (unit / dp / 400f).coerceIn(0.3f, 1.2f)

    val ink = if (theme.dark) {
        lerp(theme.background, Color.Black, 0.6f)
    } else {
        lerp(theme.background, theme.foreground, 0.42f)
    }

    fun count(perScreen: Int, minimum: Int): Int =
        (perScreen * (width * height / (dp * dp)) / 290_000f).toInt().coerceAtLeast(minimum)

    fun bake(w: Float, h: Float, quality: Float = 1f, draw: DrawScope.() -> Unit): Bitmap {
        val image = ImageBitmap(
            ceil(w * quality).toInt().coerceAtLeast(1),
            ceil(h * quality).toInt().coerceAtLeast(1),
        )
        val canvas = Canvas(image)
        canvas.scale(quality, quality)
        CanvasDrawScope().draw(Density(dp), LayoutDirection.Ltr, canvas, Size(w, h), draw)
        return image.asAndroidBitmap().also { it.prepareToDraw() }
    }

    fun bakeScreen(quality: Float = 0.5f, draw: DrawScope.() -> Unit) = bake(width, height, quality, draw)

    fun glow(color: Color) = bake(96f, 96f) { softCircle(color, Offset(48f, 48f), 48f) }
}

private class Sprites {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()

    fun draw(
        canvas: NativeCanvas,
        bitmap: Bitmap,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        alpha: Float = 1f,
        degrees: Float = 0f,
        anchorX: Float = 0.5f,
        anchorY: Float = 0.5f,
    ) {
        if (alpha <= 0.004f) return
        paint.alpha = (alpha.coerceAtMost(1f) * 255f).toInt()
        matrix.setScale(w / bitmap.width, h / bitmap.height)
        matrix.postTranslate(-w * anchorX, -h * anchorY)
        if (degrees != 0f) matrix.postRotate(degrees)
        matrix.postTranslate(x, y)
        canvas.drawBitmap(bitmap, matrix, paint)
    }

    fun fill(canvas: NativeCanvas, bitmap: Bitmap, width: Float, height: Float, alpha: Float = 1f) =
        draw(canvas, bitmap, 0f, 0f, width, height, alpha, anchorX = 0f, anchorY = 0f)
}

private fun DrawScope.softCircle(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            0.35f to color.copy(alpha = color.alpha * 0.55f),
            0.7f to color.copy(alpha = color.alpha * 0.14f),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** An out-of-focus point of light: a flat disc, a touch brighter at the rim, as a lens draws it. */
private fun DrawScope.bokeh(color: Color, alpha: Float, center: Offset, radius: Float, blend: BlendMode = BlendMode.SrcOver) {
    val a = alpha.coerceIn(0f, 1f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = a * 0.7f),
            0.72f to color.copy(alpha = a * 0.78f),
            0.9f to color.copy(alpha = a),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = blend,
    )
}

/** A wash of light with no edge at all, [squash] times as tall as it is wide. */
private fun DrawScope.wash(color: Color, alpha: Float, center: Offset, radius: Float, squash: Float = 1f, blend: BlendMode = BlendMode.SrcOver) {
    scale(1f, squash, center) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                0.45f to color.copy(alpha = alpha * 0.4f),
                1f to color.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
            blendMode = blend,
        )
    }
}

private fun smooth(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/*
 * Rain: a city at night seen through a wet window. Nothing is drawn as an object: the street is only its lights,
 * thrown out of focus, and the one sharp thing is the water on the glass.
 */
private fun rainScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    // Sizes follow the short side of the picture, so the scene keeps its proportions on a tablet or a small card.
    val dp = stage.unit / 390f
    val width = stage.width
    val height = stage.height
    val dark = theme.dark
    val warm = Color(0xFFFFB070)
    val lights = if (dark) {
        listOf(theme.secondary, theme.primary, lerp(theme.secondary, Color(0xFF7A5CFF), 0.5f), warm)
    } else {
        listOf(Color(0xFFFFD9A8), Color(0xFFFFF4E0), lerp(theme.accent, Color.White, 0.35f), lerp(theme.primary, Color.White, 0.5f))
    }
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val vanishX = width * 0.54f
    val vanishY = height * 0.7f
    val buzzX = width * 0.86f
    val buzzY = height * 0.4f

    val city = stage.bakeScreen(0.75f) {
        val sky = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color.Black, 0.6f),
                0.55f to lerp(theme.background, Color(0xFF0B1030), 0.45f),
                1f to lerp(theme.background, theme.secondary, 0.1f),
            )
        } else {
            arrayOf(
                0f to lerp(theme.background, Color(0xFF8C95A6), 0.8f),
                0.6f to lerp(theme.background, Color(0xFFA9B0BD), 0.75f),
                1f to lerp(theme.background, Color(0xFF858D9C), 0.75f),
            )
        }
        drawRect(Brush.verticalGradient(*sky))
        // The glow of the street hanging in the wet air, gathered where the road runs away.
        wash(if (dark) lights[0] else Color(0xFFFFE6C4), if (dark) 0.2f else 0.35f, Offset(vanishX, vanishY), 260f * dp, 0.6f)
        wash(if (dark) lights[1] else Color.White, if (dark) 0.1f else 0.25f, Offset(width * 0.1f, height * 0.6f), 200f * dp)
        wash(if (dark) lights[2] else Color.White, if (dark) 0.12f else 0.2f, Offset(width * 0.95f, height * 0.5f), 220f * dp)
        // Buildings: only darker columns with no edge, tall at the sides, leaving the middle open.
        val shade = if (dark) Color.Black else Color(0xFF4A5364)
        repeat(7) { i ->
            val u = i / 6f
            val edge = min(1f, abs(u - 0.54f) * 2f)
            val w = (60f + 40f * rnd(i, 4)) * dp
            val top = height * (0.66f - 0.6f * edge - 0.06f * rnd(i, 5)) - 80f * dp
            val column = Brush.verticalGradient(
                0f to shade.copy(alpha = 0f),
                1f to shade.copy(alpha = if (dark) 0.3f else 0.2f),
                startY = top,
                endY = top + 200f * dp,
            )
            for (k in -4..4) {
                drawRect(column, Offset(width * u + k * w / 4f - w / 4f, top), Size(w / 2f, height - top), alpha = 0.28f * (1f - abs(k) / 5f))
            }
        }
        val strength = if (dark) 1f else 0.8f
        // Street lamps: two rows running away to one point, the same size out of focus, dimmer with distance.
        for (side in 0..1) repeat(8) { i ->
            if (rnd(i, 14 + side) < 0.2f) return@repeat
            val p = 1f - 0.72f.pow(i + 0.4f * side)
            val fromX = if (side == 1) width * 1.04f else -width * 0.06f
            val x = fromX + (vanishX - fromX) * p + (rnd(i, 12 + side) - 0.5f) * 14f * dp
            val y = height * 0.34f + (vanishY - 8f * dp - height * 0.34f) * p + (rnd(i, 16 + side) - 0.5f) * 16f * dp
            bokeh(
                if (dark) warm else Color(0xFFFFE2B8),
                strength * (0.5f - 0.42f * p) * (0.75f + 0.25f * rnd(i, 10 + side)),
                Offset(x, y),
                (15f - 5f * p) * dp,
                blend,
            )
        }
        // Signs stacked up the fronts of the buildings: one colour a stack, a few of them much brighter.
        val stacks = listOf(
            floatArrayOf(0.07f, 0.2f, 0.62f, 0f, 17f),
            floatArrayOf(0.2f, 0.42f, 0.66f, 1f, 13f),
            floatArrayOf(0.33f, 0.56f, 0.67f, 2f, 9f),
            floatArrayOf(0.74f, 0.5f, 0.68f, 0f, 10f),
            floatArrayOf(0.86f, 0.3f, 0.66f, 2f, 14f),
            floatArrayOf(0.97f, 0.16f, 0.6f, 1f, 18f),
        )
        val perStack = if (dark) 5 else 2
        stacks.forEachIndexed { s, stack ->
            val radius = stack[4] * dp
            repeat(perStack) { i ->
                val bright = rnd(i, 20 + s).pow(2.2f)
                val color = lights[(stack[3].toInt() + if (rnd(i, 30 + s) < 0.25f) 1 else 0) % lights.size]
                val along = (i + rnd(i, 50 + s) * 0.6f) / perStack
                bokeh(
                    color,
                    strength * (0.07f + 0.5f * bright),
                    Offset(width * stack[0] + (rnd(i, 40 + s) - 0.5f) * radius * 1.4f, height * (stack[1] + (stack[2] - stack[1]) * along)),
                    radius,
                    blend,
                )
            }
        }
        // A scatter of lit windows very far off, hardly there.
        repeat(16) { i ->
            bokeh(lights[i % lights.size], strength * (0.05f + 0.12f * rnd(i, 60)), Offset(width * rnd(i, 61), height * (0.3f + 0.36f * rnd(i, 62))), 5f * dp, blend)
        }
        // The wet road: the lights above, pulled down into long soft smears.
        repeat(12) { i ->
            wash(
                lights[i % lights.size],
                if (dark) 0.16f else 0.2f,
                Offset(width * (0.04f + 0.92f * rnd(i, 70)), height * (0.9f + 0.08f * rnd(i, 71))),
                (13f + 10f * rnd(i, 72)) * dp,
                6f,
                blend,
            )
        }
        wash(if (dark) warm else Color(0xFFFFF0D8), if (dark) 0.2f else 0.3f, Offset(vanishX, height * 0.9f), 90f * dp, 1.5f, blend)
    }
    val cityImage = city.asImageBitmap()
    val cityScale = city.width / width

    // A drop is a lens: it holds the street upside down, dark round the edge, with a rim of light underneath.
    fun DrawScope.lensDrop(x: Float, y: Float, rx: Float, ry: Float, strength: Float, lookX: Float = x, lookY: Float = y) {
        val reach = 70f * dp
        val srcW = (reach * 2f * cityScale).toInt().coerceIn(1, cityImage.width)
        val srcH = (reach * 2f * cityScale).toInt().coerceIn(1, cityImage.height)
        val srcX = ((lookX - reach) * cityScale).toInt().coerceIn(0, cityImage.width - srcW)
        val srcY = ((lookY - reach * 0.4f) * cityScale).toInt().coerceIn(0, cityImage.height - srcH)
        val left = floor(x - rx).toInt()
        val top = floor(y - ry).toInt()
        val across = IntSize(ceil(rx * 2f).toInt().coerceAtLeast(1), ceil(ry * 2f).toInt().coerceAtLeast(1))
        clipPath(Path().apply { addOval(Rect(x - rx, y - ry, x + rx, y + ry)) }) {
            scale(-1f, -1f, Offset(left + across.width / 2f, top + across.height / 2f)) {
                drawImage(cityImage, IntOffset(srcX, srcY), IntSize(srcW, srcH), IntOffset(left, top), across, alpha = strength)
                if (dark) drawImage(cityImage, IntOffset(srcX, srcY), IntSize(srcW, srcH), IntOffset(left, top), across, alpha = strength, blendMode = BlendMode.Plus)
            }
            val edge = if (dark) Color.Black.copy(alpha = 0.5f * strength) else Color(0xFF283246).copy(alpha = 0.4f * strength)
            drawRect(
                Brush.radialGradient(0.35f to Color.Transparent, 1f to edge, center = Offset(x, y - ry * 0.15f), radius = max(rx, ry)),
                Offset(x - rx, y - ry),
                Size(rx * 2f, ry * 2f),
            )
            val lip = if (dark) Color(0xFFE1EBFF).copy(alpha = 0.8f * strength) else Color.White.copy(alpha = 0.95f * strength)
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 1f to lip, startY = y + ry * 0.45f, endY = y + ry),
                Offset(x - rx, y),
                Size(rx * 2f, ry),
            )
        }
        if (rx > 1.8f * dp) {
            drawCircle(Color.White.copy(alpha = 0.8f * strength), max(0.45f * dp, rx * 0.16f), Offset(x - rx * 0.32f, y - ry * 0.4f))
        }
    }

    // The street and the still water on the glass never change, so they are one picture.
    val glass = stage.bakeScreen(1f) {
        drawImage(cityImage, dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()))
        // Breath on the glass: a faint mist, thicker towards the corners.
        repeat(12) { i ->
            val corner = if (rnd(i, 40) < 0.5f) rnd(i, 41) * 0.3f else 1f - rnd(i, 41) * 0.3f
            wash(
                if (dark) Color(0xFFAAB6EE) else Color.White,
                if (dark) 0.04f else 0.2f,
                Offset(width * corner, height * rnd(i, 42)),
                (130f + 110f * rnd(i, 43)) * dp,
            )
        }
        // Standing drops, most of them specks, a few big enough to hold the street.
        val speck = if (dark) Color(0xFFD7E1FF).copy(alpha = 0.3f) else Color.White.copy(alpha = 0.8f)
        repeat(stage.count(230, 90)) { i ->
            val x = width * rnd(i, 44)
            val y = height * rnd(i, 45)
            val r = (0.5f + 3.4f * rnd(i, 46).pow(4)) * dp
            if (r < 1.1f * dp) drawCircle(speck, r * 0.7f, Offset(x, y)) else lensDrop(x, y, r, r * 1.12f, 0.9f)
        }
        // The corners fall off a little, as through a lens.
        drawRect(
            Brush.radialGradient(
                0.375f to Color.Transparent,
                1f to if (dark) Color.Black.copy(alpha = 0.45f) else Color(0xFF283040).copy(alpha = 0.16f),
                center = Offset(width / 2f, height * 0.55f),
                radius = height * 0.8f,
            ),
        )
    }

    val discs = lights.map { color -> stage.bake(96f, 96f) { bokeh(color, 1f, Offset(48f, 48f), 48f) } }
    val dropWidth = 10f * dp
    val dropHeight = 14f * dp
    val runners = List(6) { i ->
        stage.bake(dropWidth + 2f, dropHeight + 2f) {
            lensDrop(size.width / 2f, size.height / 2f, dropWidth / 2f, dropHeight / 2f, 1f, width * (0.12f + 0.15f * i), height * 0.62f)
        }
    }
    val grainTile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { tile ->
        val pixels = IntArray(256 * 256) { i ->
            val v = rnd(i, 77)
            val alpha = (abs(v - 0.5f) * 2f * if (dark) 20f else 11f).toInt()
            (alpha shl 24) or if (v < 0.5f) 0 else 0xFFFFFF
        }
        tile.setPixels(pixels, 0, 256, 0, 0, 256, 256)
    }
    val grainMatrix = Matrix()
    val grainShader = BitmapShader(grainTile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    val grainPaint = Paint().apply { shader = grainShader }
    val grainScale = max(1f, dp / 2f)

    val sprites = Sprites()
    val glows = Sprites().apply { if (dark) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    val streakColor = if (dark) Color(0xFFD2E1FF) else Color.White
    val streakCounts = intArrayOf(stage.count(46, 18), stage.count(22, 9))
    val streakLines = Array(2) { FloatArray(streakCounts[it] * 4) }
    val flashPaint = Paint()
    val flashColor = if (dark) Color(0xFFB4B4FF) else Color.White
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    val trackShade = (if (dark) Color(0xFF00000A) else Color(0xFF465064)).toArgb()
    val trackLight = (if (dark) Color(0xFFC8D7FF) else Color.White).toArgb()
    val track = FloatArray(41 * 2)
    val nearX = floatArrayOf(0.04f, 0.95f, 0.2f, 0.8f, 0.5f)
    val nearY = floatArrayOf(0.52f, 0.6f, 0.8f, 0.84f, 0.93f)

    return { canvas, t ->
        sprites.fill(canvas, glass, width, height)
        // The nearest lights: very large, very faint discs that breathe, as if the eye kept refocusing.
        for (i in 0 until 5) {
            val x = nearX[i] * width + sin(t * 0.22f + i) * 5f * dp
            val y = nearY[i] * height + cos(t * 0.17f + i * 2f) * 3f * dp
            val r = (44f + 22f * rnd(i, 52)) * dp
            val alpha = (if (dark) 0.09f else 0.12f) * (0.75f + 0.25f * sin(t * (0.35f + 0.4f * rnd(i, 54)) + i))
            glows.draw(canvas, discs[i % discs.size], x, y, r * 2f, r * 2f, alpha)
        }
        // One sign with a bad tube.
        val buzz = fract(t / 7.3f)
        if (!(buzz < 0.02f || (buzz > 0.035f && buzz < 0.05f))) {
            glows.draw(canvas, discs[0], buzzX, buzzY, 28f * dp, 28f * dp, if (dark) 0.4f else 0.3f)
        }
        // Rain falling outside: too fast to see as drops, only as thin streaks, a little slanted by the wind.
        val gust = 0.12f + 0.05f * sin(t * 0.3f)
        val span = height + 140f * dp
        for (layer in 0..1) {
            val far = layer == 0
            val length = (if (far) 34f else 62f) * dp
            val speed = (if (far) 950f else 1400f) * dp
            val lines = streakLines[layer]
            for (i in 0 until streakCounts[layer]) {
                val k = i + layer * 500
                val fall = rnd(k, 70) * span + t * speed * (0.85f + 0.3f * rnd(k, 71))
                val y = fall.mod(span) - 70f * dp
                val x = rnd(k + floor(fall / span).toInt() * 131, 72) * (width + 60f * dp) - 30f * dp
                lines[i * 4] = x
                lines[i * 4 + 1] = y
                lines[i * 4 + 2] = x + length * gust
                lines[i * 4 + 3] = y + length
            }
            val alpha = (if (far) 0.045f else 0.075f) * if (dark) 1f else 4f
            streakPaint.color = streakColor.copy(alpha = alpha).toArgb()
            streakPaint.strokeWidth = (if (far) 0.7f else 1f) * dp
            canvas.drawLines(lines, streakPaint)
        }
        // Lightning a long way off: the whole sky lifts for an instant, twice.
        val strike = fract(t / 23f + 0.4f)
        if (strike < 0.02f) {
            val f = when {
                strike < 0.004f -> 1f
                strike < 0.008f -> 0.15f
                strike < 0.012f -> 0.6f
                else -> (0.02f - strike) / 0.008f * 0.3f
            }
            flashPaint.color = flashColor.copy(alpha = (if (dark) 0.14f else 0.25f) * f).toArgb()
            canvas.drawRect(0f, 0f, width, height, flashPaint)
        }
        // A few drops heavy enough to run: they hold, slip a little way, hold again, and the track behind dries.
        for (i in 0 until 6) {
            val period = 8f + 8f * rnd(i, 80)
            val cycle = t / period + rnd(i, 81)
            val phase = fract(cycle)
            val pass = floor(cycle).toInt() * 17 + i
            val x0 = width * (0.05f + 0.9f * rnd(pass, 82))
            val y0 = height * (-0.03f + 0.4f * rnd(pass, 83))
            val travel = height * (0.35f + 0.5f * rnd(pass, 84))
            val steps = 4 + (4f * rnd(pass, 85)).toInt()
            val run = min(1f, phase / 0.75f) * steps
            val step = floor(run)
            val progress = min(1f, (step + smooth((run - step - 0.55f) / 0.45f)) / steps)
            val dry = if (phase > 0.75f) 1f - (phase - 0.75f) / 0.25f else 1f
            val size = (2.6f + 2f * rnd(pass, 86)) * dp
            // Nearly straight down; now and then it finds a speck and steps sideways.
            for (k in 0..40) {
                val p = progress * k / 40f
                var x = x0
                for (s in 0 until steps) {
                    val jog = rnd(pass + s, 89)
                    if (jog < 0.35f) x += (if (jog < 0.175f) -1f else 1f) * (2f + 3f * rnd(pass + s, 90)) * dp * smooth((p * steps - s - 0.75f) / 0.25f)
                }
                track[k * 2] = x
                track[k * 2 + 1] = y0 + travel * p
            }
            val headX = track[80]
            val headY = track[81]
            // The track is glass wiped clean of mist: a little darker than the fog round it, a thread of light inside.
            val fade = min(travel * progress, 260f * dp).coerceAtLeast(1f)
            for (k in 0 until 40) {
                val shown = ((track[k * 2 + 3] - (headY - fade)) / fade).coerceIn(0f, 1f) * dry
                if (shown <= 0.01f) continue
                trackPaint.color = trackShade
                trackPaint.alpha = (255f * (if (dark) 0.3f else 0.16f) * shown).toInt()
                trackPaint.strokeWidth = size * 1.1f
                canvas.drawLine(track[k * 2], track[k * 2 + 1], track[k * 2 + 2], track[k * 2 + 3], trackPaint)
                trackPaint.color = trackLight
                trackPaint.alpha = (255f * (if (dark) 0.22f else 0.6f) * shown).toInt()
                trackPaint.strokeWidth = 0.7f * dp
                canvas.drawLine(track[k * 2], track[k * 2 + 1], track[k * 2 + 2], track[k * 2 + 3], trackPaint)
            }
            // Beads left behind where it paused.
            for (s in 1..steps) {
                val p = (s - 0.3f) / steps
                if (p > progress - 0.02f) break
                if (rnd(pass + s, 87) >= 0.6f) continue
                val at = ((p / progress) * 40f).toInt().coerceIn(0, 40)
                val bead = (1.2f + 0.8f * rnd(pass + s, 88)) * dp
                sprites.draw(canvas, runners[(pass + s).mod(runners.size)], track[at * 2], track[at * 2 + 1], bead * 2f, bead * 2.5f, 0.9f * dry)
            }
            val runner = runners[pass.mod(runners.size)]
            if (phase < 0.75f) {
                sprites.draw(canvas, runner, headX, headY, size * 2f, size * 2.8f)
            } else {
                sprites.draw(canvas, runner, headX, headY, size * 1.6f, size * 2.2f, dry)
            }
        }
        // Grain over everything, so nothing reads as a flat fill.
        val tick = floor(t * 20f).toInt()
        grainMatrix.setScale(grainScale, grainScale)
        grainMatrix.postTranslate(-rnd(tick, 90) * 256f * grainScale, -rnd(tick, 91) * 256f * grainScale)
        grainShader.setLocalMatrix(grainMatrix)
        canvas.drawRect(0f, 0f, width, height, grainPaint)
    }
}

private fun mixF(from: Float, to: Float, p: Float) = from + (to - from) * p

/** Three sliding-window passes over one row or column: close to a Gaussian of deviation [r]. */
private fun softenLine(a: FloatArray, b: FloatArray, start: Int, step: Int, n: Int, r: Int, wrap: Boolean) {
    var from = a
    var to = b
    repeat(3) {
        var sum = 0f
        for (i in -r..r) sum += from[start + (if (wrap) i.mod(n) else i.coerceIn(0, n - 1)) * step]
        val window = 2 * r + 1
        for (i in 0 until n) {
            to[start + i * step] = sum / window
            val add = i + r + 1
            val drop = i - r
            sum += from[start + (if (wrap) add.mod(n) else add.coerceIn(0, n - 1)) * step] -
                from[start + (if (wrap) drop.mod(n) else drop.coerceIn(0, n - 1)) * step]
        }
        val swap = from
        from = to
        to = swap
    }
    if (from !== a) for (i in 0 until n) a[start + i * step] = from[start + i * step]
}

/**
 * [source] thrown out of focus by [radius] of its own pixels. [wrap] joins the left edge to the right, for strips
 * that repeat end to end.
 */
private fun softened(source: Bitmap, radius: Float, wrap: Boolean = false): Bitmap {
    val w = source.width
    val h = source.height
    val r = radius.toInt().coerceIn(1, max(1, min(w, h) / 2 - 1))
    val pixels = IntArray(w * h)
    source.getPixels(pixels, 0, w, 0, 0, w, h)
    val channel = FloatArray(w * h)
    val spare = FloatArray(w * h)
    val alpha = FloatArray(w * h)
    val out = IntArray(w * h)
    for (i in pixels.indices) alpha[i] = (pixels[i] ushr 24) / 255f
    val weights = alpha.copyOf()
    for (y in 0 until h) softenLine(alpha, spare, y * w, 1, w, r, wrap)
    for (x in 0 until w) softenLine(alpha, spare, x, w, h, r, false)
    for (i in out.indices) out[i] = (alpha[i] * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        for (i in pixels.indices) channel[i] = ((pixels[i] ushr shift) and 0xFF) * weights[i]
        for (y in 0 until h) softenLine(channel, spare, y * w, 1, w, r, wrap)
        for (x in 0 until w) softenLine(channel, spare, x, w, h, r, false)
        for (i in out.indices) {
            val value = if (alpha[i] > 0.002f) (channel[i] / alpha[i] + 0.5f).toInt().coerceIn(0, 255) else 0
            out[i] = out[i] or (value shl shift)
        }
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        it.setPixels(out, 0, w, 0, 0, w, h)
        it.prepareToDraw()
    }
}

/** A drawing made at [quality] of full size and then softened by [radius] screen pixels. */
private fun Stage.bakeSoft(w: Float, h: Float, quality: Float, radius: Float, wrap: Boolean = false, draw: DrawScope.() -> Unit): Bitmap =
    softened(bake(w, h, quality, draw), radius * quality, wrap)

private fun DrawScope.picture(bitmap: Bitmap, alpha: Float = 1f, blend: BlendMode = BlendMode.SrcOver) {
    drawImage(bitmap.asImageBitmap(), dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()), alpha = alpha, blendMode = blend)
}

/** The corners fall off a little, as through a lens. */
private fun DrawScope.vignette(color: Color, centerY: Float, inner: Float, outer: Float) {
    drawRect(
        Brush.radialGradient(
            inner / outer to Color.Transparent,
            1f to color,
            center = Offset(size.width / 2f, size.height * centerY),
            radius = size.height * outer,
        ),
    )
}

/** Film grain: a tile of noise laid over the picture at a new place twenty times a second. */
private class Grain(strength: Float, private val scale: Float) {
    private val matrix = Matrix()
    private val shader: BitmapShader
    private val paint = Paint()

    init {
        val tile = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(256 * 256) { i ->
            val v = rnd(i, 77)
            ((abs(v - 0.5f) * 2f * strength).toInt() shl 24) or if (v < 0.5f) 0 else 0xFFFFFF
        }
        tile.setPixels(pixels, 0, 256, 0, 0, 256, 256)
        shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.shader = shader
    }

    fun draw(canvas: NativeCanvas, t: Float, width: Float, height: Float) {
        val tick = floor(t * 20f).toInt()
        matrix.setScale(scale, scale)
        matrix.postTranslate(-rnd(tick, 90) * 256f * scale, -rnd(tick, 91) * 256f * scale)
        shader.setLocalMatrix(matrix)
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

private fun Stage.grain(dark: Float, light: Float) = Grain(if (theme.dark) dark else light, max(1f, dp / 2f))

private fun Stage.dot(color: Color) = bake(48f, 48f) { wash(color, 1f, Offset(24f, 24f), 24f) }

private fun Stage.disc(color: Color) = bake(96f, 96f) { bokeh(color, 1f, Offset(48f, 48f), 48f) }

private fun additive(on: Boolean) = Sprites().apply { if (on) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }

/** A line that fades from [from] to [to] along its length, drawn in short pieces so no shader is made per frame. */
private fun fadeLine(canvas: NativeCanvas, paint: Paint, x0: Float, y0: Float, x1: Float, y1: Float, from: Float, to: Float, steps: Int = 10) {
    for (i in 0 until steps) {
        val a = mixF(from, to, (i + 0.5f) / steps)
        if (a <= 0.004f) continue
        paint.alpha = (a.coerceAtMost(1f) * 255f).toInt()
        val p = i / steps.toFloat()
        val q = (i + 1) / steps.toFloat()
        canvas.drawLine(mixF(x0, x1, p), mixF(y0, y1, p), mixF(x0, x1, q), mixF(y0, y1, q), paint)
    }
}

/*
 * Snow: a still night, one street lamp out of focus, and the snow only visible where it falls through light. No
 * trees, no moon, no houses: the dark is left dark.
 */
private fun snowScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val lampX = width * 0.79f
    val lampY = height * 0.2f
    val warm = if (dark) Color(0xFFFFC890) else Color(0xFFFFF3DF)

    val back = stage.bakeScreen(0.75f) {
        val sky = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color.Black, 0.55f),
                0.6f to lerp(theme.background, Color(0xFF0E1A3A), 0.5f),
                0.8f to lerp(theme.background, Color(0xFF2A3A66), 0.45f),
                1f to lerp(theme.background, Color(0xFF46588C), 0.5f),
            )
        } else {
            arrayOf(
                0f to lerp(theme.background, Color(0xFFB9C2D2), 0.7f),
                0.55f to lerp(theme.background, Color(0xFF8D98AC), 0.75f),
                0.78f to lerp(theme.background, Color(0xFF7C879C), 0.8f),
                1f to lerp(theme.background, Color(0xFFE9EEF6), 0.8f),
            )
        }
        drawRect(Brush.verticalGradient(*sky))
        // Whatever stands at the far side of the field: only a darker band with no outline.
        repeat(9) { i ->
            wash(
                if (dark) Color.Black else Color(0xFF56607A),
                if (dark) 0.3f else 0.22f,
                Offset(width * (i / 8f) + (rnd(i, 1) - 0.5f) * 40f * u, height * (0.7f + 0.04f * rnd(i, 2))),
                (90f + 60f * rnd(i, 3)) * u,
                0.55f,
            )
        }
        // Lights of a town a long way off, sitting on the horizon.
        val far = listOf(theme.secondary, theme.primary, Color(0xFFFFB070), theme.secondary, Color(0xFFFFB070))
        repeat(11) { i ->
            bokeh(
                if (dark) far[i % far.size] else Color(0xFFFFF0D8),
                (if (dark) 0.1f else 0.25f) + 0.3f * rnd(i, 5).pow(2),
                Offset(width * (0.03f + 0.62f * rnd(i, 4)), height * (0.735f + 0.03f * rnd(i, 6))),
                (5f + 5f * rnd(i, 7)) * u,
                blend,
            )
        }
        // The lamp: the air round it glows, and its light pools on the snow underneath.
        wash(warm, if (dark) 0.2f else 0.3f, Offset(lampX, lampY), 250f * u, blend = blend)
        wash(warm, if (dark) 0.16f else 0.2f, Offset(lampX, lampY + 120f * u), 190f * u, 1.7f, blend)
        wash(warm, if (dark) 0.3f else 0.35f, Offset(lampX - 14f * u, height * 0.93f), 190f * u, 0.26f, blend)
        // The snow on the ground: a pale floor, falling away into blue.
        val snow = if (dark) Color(0xFF9FB2E6) else Color.White
        drawRect(
            Brush.verticalGradient(0f to snow.copy(alpha = 0f), 1f to snow.copy(alpha = if (dark) 0.2f else 0.55f), startY = height * 0.76f, endY = height),
            Offset(0f, height * 0.76f),
            Size(width, height * 0.24f),
        )
        repeat(5) { i ->
            wash(if (dark) Color(0xFFC9D6FF) else Color.White, if (dark) 0.07f else 0.3f, Offset(width * rnd(i, 8), height * (1f + 0.03f * rnd(i, 9))), (150f + 80f * rnd(i, 10)) * u, 0.3f)
        }
        // The lamp itself has no edge: a hot core that burns out into its own glare.
        wash(warm, if (dark) 0.55f else 0.6f, Offset(lampX, lampY), 70f * u, blend = blend)
        wash(warm, 0.9f, Offset(lampX, lampY), 30f * u, blend = blend)
        wash(Color.White, 0.9f, Offset(lampX, lampY), 13f * u, blend = blend)
        vignette(if (dark) Color.Black.copy(alpha = 0.42f) else Color(0xFF283040).copy(alpha = 0.14f), 0.5f, 0.32f, 0.8f)
    }

    val cool = if (dark) Color(0xFFB6C6F4) else Color.White
    val glow = if (dark) Color(0xFFFFD9AE) else Color.White
    val coolDot = stage.dot(cool)
    val warmDot = stage.dot(glow)
    val coolDisc = stage.disc(cool)
    val warmDisc = stage.disc(glow)
    val still = Sprites()
    val sprites = additive(dark)
    val grain = stage.grain(20f, 11f)
    // Far snow is dust; the middle is what the eye follows; the nearest flakes pass the lens as big soft discs.
    val counts = intArrayOf(stage.count(420, 120), stage.count(170, 50), stage.count(40, 12), stage.count(10, 4))
    val small = floatArrayOf(0.9f, 2f, 4.5f, 14f)
    val large = floatArrayOf(1.6f, 3.8f, 8f, 30f)
    val slow = floatArrayOf(16f, 38f, 70f, 120f)
    val fast = floatArrayOf(30f, 66f, 100f, 190f)
    val strength = floatArrayOf(0.6f, 0.9f, 0.55f, 0.14f)
    val wind = floatArrayOf(0.35f, 0.8f, 1.3f, 2.4f)
    val sway = floatArrayOf(3f, 9f, 14f, 18f)
    val base = floatArrayOf(0.3f, 0.34f, 0.3f, 0.45f)
    val reach = 170f * u

    return { canvas, t ->
        still.fill(canvas, back, width, height)
        // The wind is never steady: it leans one way, eases, and now and then pushes harder.
        val drift = (70f * sin(t * 0.11f) + 28f * sin(t * 0.29f + 1f) + 9f * sin(t * 0.83f + 2f)) * u
        for (layer in 0 until 4) {
            val disc = layer == 3
            val padY = (large[layer] * 2f + 20f) * u
            val spanY = height + padY * 2f
            val spanX = width + 160f * u
            for (i in 0 until counts[layer]) {
                val k = i + layer * 1000
                val fall = rnd(k, 20) * spanY + t * mixF(slow[layer], fast[layer], rnd(k, 21)) * u
                val pass = floor(fall / spanY).toInt()
                val y = fall.mod(spanY) - padY
                val tumble = t * (0.5f + rnd(k, 23)) + k
                val x = (rnd(k + pass * 37, 22) * spanX + drift * wind[layer] + sin(tumble) * sway[layer] * u).mod(spanX) - 80f * u
                val r = mixF(small[layer], large[layer], rnd(k, 24)) * u
                // Every flake is as bright as the light it happens to be falling through.
                val dx = x - lampX
                val dy = (y - lampY).let { if (it > 0f) it * 0.5f else it * 1.5f }
                val lit = exp(-(dx * dx + dy * dy) / (reach * reach))
                // A flake turns as it falls and catches the light on and off.
                val catch = 0.75f + 0.25f * sin(tumble * 2.3f)
                val coolAlpha = strength[layer] * base[layer] * catch * if (dark) 1f else 3f
                val warmAlpha = strength[layer] * lit * 1.6f * catch * if (dark) 1f else 0.6f
                sprites.draw(canvas, if (disc) coolDisc else coolDot, x, y, r * 2f, r * 2f, coolAlpha)
                sprites.draw(canvas, if (disc) warmDisc else warmDot, x, y, r * 2f, r * 2f, warmAlpha)
            }
        }
        grain.draw(canvas, t, width, height)
    }
}

/*
 * Falling stars: the sky as a long night exposure shows it. Thousands of points of very different brightness, the
 * pale band of the galaxy, the whole sky turning slowly round the pole, and now and then a meteor: a thin fast
 * line, all from the same radiant, gone in under a second.
 */
private fun starsScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val poleX = width * 0.28f
    val poleY = height * 0.3f
    val reach = maxOf(
        hypot(poleX, poleY),
        hypot(width - poleX, poleY),
        hypot(poleX, height - poleY),
        hypot(width - poleX, height - poleY),
    ) + 4f
    val tilt = -0.95f
    val bandX = cos(tilt)
    val bandY = sin(tilt)
    val bandOff = reach * 0.34f
    fun gauss(i: Int, salt: Int) = (rnd(i, salt) + rnd(i, salt + 1) + rnd(i, salt + 2) - 1.5f) / 1.5f

    val sky = stage.bakeScreen(0.25f) {
        val wash = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color.Black, 0.7f),
                0.6f to lerp(theme.background, Color(0xFF050A1E), 0.55f),
                1f to lerp(theme.background, Color(0xFF101A3C), 0.5f),
            )
        } else {
            // The light theme is the hour before sunrise: the sky already pale low down, the last stars still out.
            arrayOf(
                0f to lerp(theme.background, Color(0xFF3C4A72), 0.8f),
                0.5f to lerp(theme.background, Color(0xFF7D86AD), 0.75f),
                0.85f to lerp(theme.background, Color(0xFFE3B9B0), 0.7f),
                1f to lerp(theme.background, Color(0xFFF6DCC0), 0.75f),
            )
        }
        drawRect(Brush.verticalGradient(*wash))
    }
    // The galaxy: a band across the sky, built from haze, with dark lanes of dust down its middle.
    val galaxy = stage.bake(reach * 2f, reach * 2f, (700f / (reach * 2f)).coerceAtMost(1f)) {
        val haze = if (dark) listOf(Color(0xFFC9D4FF), Color(0xFFC9D4FF), theme.secondary, theme.primary, Color(0xFFFFE2C4)) else listOf(Color.White)
        repeat(150) { i ->
            val s = (rnd(i, 1) * 2f - 1f) * reach
            val d = gauss(i, 2) * 58f * u + bandOff
            val core = 1f - abs(s) / reach * 0.5f
            wash(
                haze[i % haze.size],
                (if (dark) 0.03f else 0.035f) * core * (0.5f + rnd(i, 5)),
                Offset(reach + s * bandX - d * bandY, reach + s * bandY + d * bandX),
                (42f + 62f * rnd(i, 6)) * u,
                blend = if (dark) BlendMode.Plus else BlendMode.SrcOver,
            )
        }
        if (dark) repeat(46) { i ->
            val s = (rnd(i, 7) * 2f - 1f) * reach
            val d = (gauss(i, 8) * 16f + 10f * sin(s / u * 0.012f)) * u + bandOff
            val center = Offset(reach + s * bandX - d * bandY, reach + s * bandY + d * bandX)
            rotate((tilt + (rnd(i, 11) - 0.5f) * 0.5f) * DEGREES, center) {
                wash(lerp(theme.background, Color.Black, 0.7f), 0.2f + 0.2f * rnd(i, 12), center, (30f + 34f * rnd(i, 13)) * u, 0.3f)
            }
        }
    }
    // Stars: very many faint ones and very few bright, more of them towards the galaxy. They are kept as points and
    // sorted by colour and size, so the whole sky turns with a few calls and stays sharp.
    val tints = listOf(Color.White, Color.White, Color(0xFFCFDCFF), Color(0xFFAAC4FF), Color(0xFFFFE3C2), Color(0xFFFFD0A0))
    val total = if (dark) stage.count(3000, 700) else stage.count(300, 80)
    val classes = 5
    val sorted = List(tints.size * classes) { ArrayList<Float>() }
    repeat(total) { i ->
        val x: Float
        val y: Float
        if (rnd(i, 20) < 0.45f) {
            val s = (rnd(i, 21) * 2f - 1f) * reach
            val d = gauss(i, 22) * 90f * u + bandOff
            x = s * bandX - d * bandY
            y = s * bandY + d * bandX
        } else {
            x = (rnd(i, 21) * 2f - 1f) * reach
            y = (rnd(i, 22) * 2f - 1f) * reach
        }
        val magnitude = rnd(i, 25).pow(4)
        val tint = if (dark) (rnd(i, 26) * tints.size).toInt().coerceAtMost(tints.size - 1) else 0
        val bucket = sorted[tint * classes + (magnitude * classes).toInt().coerceAtMost(classes - 1)]
        bucket += x
        bucket += y
    }
    val points = sorted.map { it.toFloatArray() }
    val pointPaints = List(tints.size * classes) { index ->
        val m = (index % classes + 0.5f) / classes
        // Plain square points: at a pixel or two across nobody can tell, and they cost a fraction of round ones.
        Paint().apply {
            color = tints[index / classes].copy(alpha = (if (dark) 0.3f else 0.25f) + 0.7f * m).toArgb()
            strokeWidth = (0.9f + 1.5f * m) * u
        }
    }
    // The brightest few are drawn one by one so they can shimmer: a hard point with a little bloom round it.
    val bright = if (dark) 46 else 12
    val glows = tints.map { stage.dot(it) }
    val fire = stage.dot(if (dark) theme.primary else Color.White)
    // The land: no outline, only the place where the stars stop, with the glow of the air just above it.
    val land = stage.bakeSoft(width, height, 0.25f, 7f * u) {
        val path = Path()
        path.moveTo(-40f * u, height + 40f * u)
        var x = -40f * u
        while (x <= width + 40f * u) {
            val at = x / u
            val hill = 0.05f * exp(-((x - width * 0.86f) / (70f * u)).pow(2))
            path.lineTo(x, height * (0.935f - 0.03f * sin(at * 0.011f + 1f) - 0.018f * sin(at * 0.029f) - hill))
            x += 6f * u
        }
        path.lineTo(width + 40f * u, height + 40f * u)
        path.close()
        drawPath(path, (if (dark) lerp(theme.background, Color.Black, 0.75f) else Color(0xFF3A3F58)).copy(alpha = if (dark) 1f else 0.85f))
    }
    val ground = stage.bakeScreen(0.5f) {
        if (dark) {
            wash(theme.primary, 0.1f, Offset(width * 0.3f, height * 0.93f), 300f * u, 0.3f, BlendMode.Plus)
            wash(theme.secondary, 0.1f, Offset(width * 0.85f, height * 0.95f), 240f * u, 0.3f, BlendMode.Plus)
        }
        picture(land)
        vignette(if (dark) Color.Black.copy(alpha = 0.4f) else Color(0xFF1E243C).copy(alpha = 0.2f), 0.45f, 0.35f, 0.85f)
    }
    val still = Sprites()
    val sprites = additive(dark)
    val grain = stage.grain(18f, 11f)
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        if (dark) xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    val white = Color.White.toArgb()
    val trainTint = (if (dark) theme.primary else Color.White).toArgb()
    val radiantX = width * 0.72f
    val radiantY = height * 0.08f

    fun meteor(canvas: NativeCanvas, t: Float, stream: Int, every: Float, big: Boolean) {
        val slot = floor(t / every).toInt()
        val k = slot * 7 + stream * 1000
        if (big && rnd(k, 49) > 0.5f) return
        val life = if (big) 1.1f else 0.45f + 0.35f * rnd(k, 40)
        val linger = if (big) 1.6f else 0.7f
        val age = t - slot * every - rnd(k, 41) * (every - life - linger)
        if (age < 0f || age > life + linger) return
        // Every meteor of a shower comes out of the same point of the sky.
        val startX = width * (0.05f + 0.9f * rnd(k, 42))
        val startY = height * (0.06f + 0.5f * rnd(k, 43))
        var dx = startX - radiantX
        var dy = startY - radiantY + 40f * u
        val norm = hypot(dx, dy)
        dx /= norm
        dy /= norm
        val length = (if (big) 260f else 90f + 150f * rnd(k, 44)) * u
        val progress = min(1f, age / life)
        val head = 1f - (1f - progress).pow(1.6f)
        val headX = startX + dx * length * head
        val headY = startY + dy * length * head
        if (age <= life) {
            // It brightens, flares near the end and is gone.
            val power = sin(min(1f, progress * 1.15f) * PI.toFloat()).coerceAtLeast(0f).pow(0.6f) * if (big) 1f else 0.55f + 0.45f * rnd(k, 45)
            val tail = min(length * head, (if (big) 150f else 70f) * u)
            linePaint.color = white
            linePaint.strokeWidth = (if (big) 1.8f else 1.1f) * u
            fadeLine(canvas, linePaint, headX - dx * tail, headY - dy * tail, headX, headY, 0f, 0.95f * power)
            val r = (if (big) 13f else 5f) * u
            sprites.draw(canvas, if (big) fire else glows[0], headX, headY, r * 2f, r * 2f, power)
            if (big) sprites.draw(canvas, glows[0], headX, headY, 10f * u, 10f * u, power)
        }
        // The train it leaves hangs in the air a moment and fades.
        val fade = if (age <= life) progress else 1f - (age - life) / linger
        val from = max(0f, head - 0.75f)
        val fromX = startX + dx * length * from
        val fromY = startY + dy * length * from
        val peak = (if (big) 0.3f else 0.12f) * fade
        val kneeX = mixF(fromX, headX, 0.7f)
        val kneeY = mixF(fromY, headY, 0.7f)
        linePaint.color = if (big) trainTint else white
        linePaint.strokeWidth = (if (big) 2.6f else 1.4f) * u
        fadeLine(canvas, linePaint, fromX, fromY, kneeX, kneeY, 0f, peak, 8)
        fadeLine(canvas, linePaint, kneeX, kneeY, headX, headY, peak, 0f, 5)
    }

    return { canvas, t ->
        still.fill(canvas, sky, width, height)
        val turn = t * 0.0035f + 0.4f
        canvas.save()
        canvas.rotate(turn * DEGREES, poleX, poleY)
        still.draw(canvas, galaxy, poleX, poleY, reach * 2f, reach * 2f)
        canvas.translate(poleX, poleY)
        for (i in points.indices) if (points[i].isNotEmpty()) canvas.drawPoints(points[i], pointPaints[i])
        canvas.restore()
        for (i in 0 until bright) {
            val angle = rnd(i, 30) * TAU + turn
            val distance = sqrt(rnd(i, 31)) * reach
            val x = poleX + cos(angle) * distance
            val y = poleY + sin(angle) * distance
            if (x < -10f || x > width + 10f || y < -10f || y > height + 10f) continue
            val magnitude = 0.35f + 0.65f * rnd(i, 32).pow(2.5f)
            // The air is never still: the lower the star, the more it shimmers.
            val low = 0.3f + 0.7f * max(0f, y / height)
            val shimmer = 1f - low * 0.35f * (0.5f + 0.5f * sin(t * (5f + 6f * rnd(i, 34)) + i * 3f)) * (0.5f + 0.5f * sin(t * 1.3f + i))
            val r = (3f + 6f * magnitude) * u
            sprites.draw(canvas, glows[(rnd(i, 33) * tints.size).toInt().coerceAtMost(tints.size - 1)], x, y, r * 2f, r * 2f, (if (dark) 0.5f else 0.6f) * magnitude * shimmer)
            sprites.draw(canvas, glows[0], x, y, 3f * u, 3f * u, (0.6f + 0.4f * magnitude) * shimmer)
        }
        meteor(canvas, t, 0, 5.3f, false)
        meteor(canvas, t, 1, 8.9f, false)
        meteor(canvas, t, 2, 13.7f, false)
        meteor(canvas, t + 9f, 3, 31f, true)
        still.fill(canvas, ground, width, height)
        grain.draw(canvas, t, width, height)
    }
}

/*
 * Sakura: blossom at night, photographed with the lens wide open. The trees are only soft masses of pink out of
 * focus, a row of paper lanterns is only its warm discs, and the petals are the one thing that moves: small and
 * sharp far away, large and soft when they pass close, turning over as they fall.
 */
private fun sakuraScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val pink = if (dark) lerp(Color(0xFFFFB3CF), theme.secondary, 0.22f) else lerp(Color(0xFFFFC2D6), theme.accent, 0.15f)
    val pale = lerp(pink, Color.White, 0.55f)
    val warm = if (dark) Color(0xFFFFB877) else Color(0xFFFFF1DC)

    // Boughs: a few dark limbs thrown far out of focus, reaching in from the top corners.
    val boughs = stage.bakeSoft(width, height, 0.25f, 9f * u) {
        val color = if (dark) Color.Black.copy(alpha = 0.6f) else Color(0xFF46343C).copy(alpha = 0.4f)
        val right = width / u
        listOf(
            floatArrayOf(-20f, 60f, 120f, 150f, 250f, 120f, 13f),
            floatArrayOf(60f, -20f, 110f, 110f, 170f, 250f, 8f),
            floatArrayOf(right + 20f, 110f, right - 110f, 170f, right - 220f, 120f, 12f),
            floatArrayOf(right - 40f, -20f, right - 90f, 120f, right - 60f, 280f, 7f),
            floatArrayOf(-20f, 250f, 60f, 300f, 130f, 290f, 6f),
        ).forEach { b ->
            val path = Path()
            path.moveTo(b[0] * u, b[1] * u)
            path.quadraticTo(b[2] * u, b[3] * u, b[4] * u, b[5] * u)
            drawPath(path, color, style = Stroke(b[6] * u, cap = StrokeCap.Round))
        }
    }
    // Blossom: clouds of it, thickest in the top corners, thinning out towards the middle of the picture.
    fun mass(i: Int, salt: Int): Offset {
        val a = rnd(i, salt + 1).pow(1.5f)
        val b = rnd(i, salt + 2).pow(1.4f)
        return Offset(if (rnd(i, salt) < 0.5f) a * width * 0.62f else width - a * width * 0.62f, b * height * 0.42f * (1f - a * 0.45f))
    }
    val back = stage.bakeScreen(0.75f) {
        val sky = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color.Black, 0.5f),
                0.6f to lerp(theme.background, Color(0xFF12102E), 0.5f),
                1f to lerp(theme.background, Color(0xFF2A1430), 0.5f),
            )
        } else {
            arrayOf(
                0f to lerp(theme.background, Color(0xFFA9C3E6), 0.75f),
                0.6f to lerp(theme.background, Color(0xFFCFDCF0), 0.7f),
                1f to lerp(theme.background, Color(0xFFF3DFE6), 0.75f),
            )
        }
        drawRect(Brush.verticalGradient(*sky))
        picture(boughs)
        repeat(150) { i ->
            wash(if (i % 3 != 0) pink else pale, (if (dark) 0.1f else 0.2f) * (0.5f + rnd(i, 4)), mass(i, 1), (26f + 46f * rnd(i, 5)) * u)
        }
        // The lanterns hung under the trees, running away down the path.
        repeat(7) { i ->
            val p = 1f - 0.7f.pow(i)
            val at = Offset(mixF(width * 0.02f, width * 0.6f, p), mixF(height * 0.5f, height * 0.685f, p))
            wash(warm, 0.2f * (1f - p * 0.7f), at, (80f - 40f * p) * u, blend = blend)
            bokeh(warm, (if (dark) 0.75f else 0.7f) * (1f - p * 0.75f), at, (17f - 9f * p) * u, blend)
        }
        repeat(5) { i ->
            val p = 1f - 0.66f.pow(i + 0.5f)
            bokeh(warm, (if (dark) 0.45f else 0.5f) * (1f - p * 0.75f), Offset(mixF(width * 1.02f, width * 0.66f, p), mixF(height * 0.56f, height * 0.69f, p)), (14f - 7f * p) * u, blend)
        }
        // Where the lantern light catches blossom it shows as discs of pink and white.
        repeat(90) { i ->
            val at = mass(i, 10)
            bokeh(
                if (rnd(i, 15) < 0.3f) pale else pink,
                (if (dark) 0.05f else 0.12f) + 0.4f * rnd(i, 14).pow(2.4f),
                Offset(at.x, at.y + 14f * u),
                (6f + 13f * rnd(i, 16)) * u,
                blend,
            )
        }
        // The path below, pale with fallen petals and warm under the lanterns.
        wash(pink, if (dark) 0.16f else 0.3f, Offset(width * 0.5f, height * 0.98f), 300f * u, 0.3f, blend)
        wash(warm, 0.2f, Offset(width * 0.28f, height * 0.9f), 210f * u, 0.3f, blend)
        vignette(if (dark) Color.Black.copy(alpha = 0.4f) else Color(0xFF3C283C).copy(alpha = 0.12f), 0.5f, 0.32f, 0.8f)
    }
    // One petal, drawn large: narrow at the stem, wide at the end, with the little notch every cherry petal has.
    fun petal(blur: Float, tone: Float): Bitmap {
        val size = 96f
        val s = (size / 2f - blur * 3f - 2f) / 20f
        return stage.bakeSoft(size, size, 1f, blur) {
            val path = Path()
            translate(size / 2f, size / 2f) {
                path.moveTo(0f, 20f * s)
                path.cubicTo(-15f * s, 9f * s, -16f * s, -11f * s, -6f * s, -19f * s)
                path.quadraticTo(-2f * s, -20f * s, 0f, -14.5f * s)
                path.quadraticTo(2f * s, -20f * s, 6f * s, -19f * s)
                path.cubicTo(16f * s, -11f * s, 15f * s, 9f * s, 0f, 20f * s)
                drawPath(
                    path,
                    Brush.verticalGradient(
                        0f to lerp(pink, Color.White, (tone + 0.3f).coerceAtMost(1f)),
                        0.55f to lerp(pink, Color.White, tone),
                        1f to lerp(pink, Color(0xFFE2578C), 0.35f),
                        startY = -20f * s,
                        endY = 20f * s,
                    ),
                )
            }
        }
    }
    val petals = listOf(petal(1.2f, 0.35f), petal(3.3f, 0.3f), petal(9f, 0.25f))
    val sprites = Sprites()
    val grain = stage.grain(18f, 10f)
    val counts = intArrayOf(stage.count(80, 24), stage.count(46, 14), stage.count(7, 3), stage.count(60, 20))
    val small = floatArrayOf(3f, 6f, 22f, 4f)
    val large = floatArrayOf(5f, 10f, 40f, 9f)
    val slow = floatArrayOf(26f, 46f, 120f, 60f)
    val fast = floatArrayOf(46f, 78f, 170f, 110f)
    val wind = floatArrayOf(0.5f, 1f, 2.6f, 2.2f)
    val sway = floatArrayOf(6f, 12f, 20f, 10f)
    val which = intArrayOf(0, 1, 2, 1)
    val strength = floatArrayOf(0.8f, 0.9f, 0.55f, 0.9f)

    return { canvas, t ->
        sprites.fill(canvas, back, width, height)
        // The air moves in slow breaths; every so often a gust shakes a whole bough loose.
        val breeze = (26f * t + 60f * sin(t * 0.21f) + 22f * sin(t * 0.57f + 1f)) * u
        val gustPhase = fract(t / 17f)
        val gust = if (gustPhase < 0.3f) sin(gustPhase / 0.3f * PI.toFloat()) else 0f
        for (layer in 0 until 4) {
            val flurry = layer == 3
            if (flurry && gust <= 0.01f) continue
            val pad = (large[layer] + 20f) * u
            val spanY = height + pad * 2f
            val spanX = width + 200f * u
            for (i in 0 until counts[layer]) {
                val k = i + layer * 1000
                val fall = rnd(k, 20) * spanY + t * mixF(slow[layer], fast[layer], rnd(k, 21)) * u
                val pass = floor(fall / spanY).toInt()
                val spin = t * (0.7f + 1.5f * rnd(k, 23)) + k * 1.7f
                val y = fall.mod(spanY) - pad + sin(spin * 0.8f) * 5f * u
                val x = (rnd(k + pass * 37, 22) * spanX + breeze * wind[layer] + sin(spin * 0.6f) * sway[layer] * u).mod(spanX) - 100f * u
                val r = mixF(small[layer], large[layer], rnd(k, 24)) * u
                // A petal turns over and over: edge on it almost disappears, face on it catches the lantern light.
                val face = abs(cos(spin * 1.3f))
                val lit = 0.55f + 0.45f * exp(-((y - height * 0.62f) / (height * 0.3f)).pow(2))
                var alpha = strength[layer] * (0.5f + 0.5f * face) * if (dark) lit else 1f
                if (flurry) alpha *= if (rnd(k, 25) < 0.5f + 0.5f * gust) gust else 0f
                sprites.draw(canvas, petals[which[layer]], x, y, r * 2f, r * 2f * max(0.14f, face), alpha, (spin * 0.5f + k) * DEGREES)
            }
        }
        grain.draw(canvas, t, width, height)
    }
}

/*
 * Fireflies: a wood by a stream on a summer night, as a slow shutter sees it. The trees are only darker columns in
 * the mist. The fireflies are the light: each one glows and fades on its own slow breath, and where it flew while
 * it was lit it leaves a short curved stroke.
 */
private fun firefliesScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val water = height * 0.87f
    val glowColor = if (dark) lerp(Color(0xFFD6FF62), theme.primary, 0.15f) else lerp(Color(0xFFFFE873), theme.accent, 0.1f)
    val mist = if (dark) Color(0xFF7FA7B8) else Color(0xFFCFE0E4)
    val shade = if (dark) Color.Black else Color(0xFF1F2E36)

    // Trunks, far to near: each row darker and softer than the one behind it.
    val rows = listOf(floatArrayOf(9f, 6f, 12f, 0.22f, 5f), floatArrayOf(6f, 12f, 26f, 0.34f, 8f), floatArrayOf(4f, 28f, 52f, 0.55f, 14f))
    val trunks = rows.mapIndexed { row, spec ->
        val count = spec[0].toInt()
        stage.bakeSoft(width, height, 0.25f, spec[4] * u) {
            repeat(count) { i ->
                val along = (i + rnd(i, 1 + row)) / count
                // The near trunks keep to the sides, leaving the middle of the picture open.
                val x = if (row == 2) {
                    if (i % 2 == 1) width * (0.84f + 0.2f * rnd(i, 4)) else width * (-0.04f + 0.2f * rnd(i, 4))
                } else {
                    width * along
                }
                val lean = (rnd(i, 5 + row) - 0.5f) * 40f * u
                val path = Path()
                path.moveTo(x + lean, -30f * u)
                path.quadraticTo(x + lean * 0.3f, height * 0.5f, x, height * 0.92f)
                drawPath(path, shade.copy(alpha = spec[3] * (0.7f + 0.3f * rnd(i, 8 + row))), style = Stroke(mixF(spec[1], spec[2], rnd(i, 11 + row)) * u))
            }
        }
    }
    val back = stage.bakeScreen(0.75f) {
        val sky = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color.Black, 0.7f),
                0.5f to lerp(theme.background, Color(0xFF06141A), 0.6f),
                1f to lerp(theme.background, Color(0xFF0A1C1E), 0.6f),
            )
        } else {
            // The light theme is dusk: enough blue left in the air to see the wood by.
            arrayOf(
                0f to lerp(theme.background, Color(0xFF3F5568), 0.85f),
                0.5f to lerp(theme.background, Color(0xFF5D7684), 0.82f),
                1f to lerp(theme.background, Color(0xFF4A6068), 0.85f),
            )
        }
        drawRect(Brush.verticalGradient(*sky))
        // A gap in the canopy where the last of the sky comes through, and the mist it lights.
        wash(mist, if (dark) 0.13f else 0.28f, Offset(width * 0.56f, height * 0.3f), 210f * u, 1.3f)
        wash(mist, if (dark) 0.07f else 0.16f, Offset(width * 0.42f, height * 0.6f), 260f * u, 0.8f)
        trunks.forEach { picture(it) }
        // The canopy closes in overhead, the undergrowth below.
        repeat(16) { i ->
            val x = rnd(i, 20)
            wash(shade, if (dark) 0.4f else 0.3f, Offset(width * x, height * (0.12f * rnd(i, 21) * (1f + 2f * abs(x - 0.56f)))), (90f + 80f * rnd(i, 22)) * u)
        }
        repeat(14) { i ->
            val side = rnd(i, 23)
            wash(
                shade,
                if (dark) 0.45f else 0.35f,
                Offset(width * (if (side < 0.5f) side * 0.7f else 1f - (1f - side) * 0.7f), water - (10f + 50f * rnd(i, 24)) * u),
                (60f + 60f * rnd(i, 25)) * u,
                0.6f,
            )
        }
        // Ground mist lying in bands between the trees.
        repeat(9) { i ->
            wash(mist, if (dark) 0.05f else 0.1f, Offset(width * rnd(i, 26), height * (0.62f + 0.2f * rnd(i, 27))), (150f + 100f * rnd(i, 28)) * u, 0.22f)
        }
        // The stream: still black water that holds whatever light there is.
        val top = water - 14f * u
        drawRect(
            Brush.verticalGradient(
                0f to shade.copy(alpha = 0f),
                0.2f to shade.copy(alpha = if (dark) 0.55f else 0.4f),
                1f to shade.copy(alpha = if (dark) 0.3f else 0.25f),
                startY = top,
                endY = height,
            ),
            Offset(0f, top),
            Size(width, height - top),
        )
        wash(mist, if (dark) 0.1f else 0.2f, Offset(width * 0.56f, height * 0.95f), 150f * u, 0.35f)
        repeat(16) { i ->
            wash(mist, if (dark) 0.03f else 0.06f, Offset(width * rnd(i, 30), water + (height - water) * rnd(i, 31)), (40f + 60f * rnd(i, 32)) * u, 0.05f)
        }
        vignette(if (dark) Color.Black.copy(alpha = 0.5f) else Color(0xFF0A141A).copy(alpha = 0.3f), 0.5f, 0.3f, 0.8f)
    }
    val glow = stage.dot(glowColor)
    val core = stage.dot(lerp(glowColor, Color.White, 0.7f))
    val disc = stage.disc(glowColor)
    val still = Sprites()
    val sprites = additive(true)
    val grain = stage.grain(20f, 13f)
    val gain = if (dark) 1f else 0.9f
    val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = glowColor.toArgb()
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    val far = stage.count(70, 24)
    val mid = stage.count(24, 9)
    val near = stage.count(7, 3)

    // Where firefly k is at time t: a slow wander made of a few unrelated swings.
    fun flyX(k: Int, t: Float, reach: Float): Float {
        val a = 0.22f + 0.3f * rnd(k, 42)
        val p = k * 2.4f
        return width * (-0.05f + 1.1f * rnd(k, 40)) + reach * (sin(t * a + p) + 0.5f * sin(t * a * 2.3f + p * 1.7f) + 0.25f * sin(t * a * 5.1f + p))
    }
    fun flyY(k: Int, t: Float, reach: Float): Float {
        val b = 0.16f + 0.26f * rnd(k, 43)
        val p = k * 2.4f
        return height * (0.3f + 0.52f * rnd(k, 41).pow(0.8f)) + reach * 0.6f * (cos(t * b + p * 1.3f) + 0.5f * sin(t * b * 2.7f + p) + 0.2f * cos(t * b * 6.3f + p * 2f))
    }
    // How lit it is: a slow swell and a slower fade, then dark for a while. Neighbours fall roughly into step.
    fun lit(k: Int, t: Float): Float {
        val period = 3.1f + 0.9f * rnd(k, 44)
        val phase = floor(rnd(k, 45) * 3f) / 3f + 0.12f * rnd(k, 46)
        val at = fract(t / period + phase)
        return when {
            at < 0.18f -> (at / 0.18f).pow(1.5f)
            at < 0.6f -> (1f - (at - 0.18f) / 0.42f).pow(1.8f)
            else -> 0f
        }
    }

    return { canvas, t ->
        still.fill(canvas, back, width, height)
        // Far off between the trees: only sparks.
        for (i in 0 until far) {
            val k = i + 2000
            val b = lit(k, t * 0.9f)
            if (b < 0.02f) continue
            val y = flyY(k, t, 16f * u)
            if (y > water) continue
            sprites.draw(canvas, glow, flyX(k, t, 16f * u), y, 7f * u, 7f * u, 0.75f * b * gain)
        }
        // The ones the eye follows: a glow, a hot core, and the stroke of light they drew over the last second.
        for (i in 0 until mid) {
            val k = i + 1000
            val reach = 62f * u
            val x = flyX(k, t, reach)
            val y = flyY(k, t, reach)
            var fromX = x
            var fromY = y
            for (j in 1..10) {
                val then = t - j * 0.15f
                val toX = flyX(k, then, reach)
                val toY = flyY(k, then, reach)
                val a = lit(k, then) * (1f - j / 11f) * 0.55f * gain
                if (a > 0.01f) {
                    trailPaint.alpha = (a * 255f).toInt()
                    trailPaint.strokeWidth = 2.2f * (1f - j / 14f) * u
                    canvas.drawLine(fromX, fromY, toX, toY, trailPaint)
                }
                fromX = toX
                fromY = toY
            }
            val b = lit(k, t)
            if (b < 0.02f) continue
            sprites.draw(canvas, glow, x, y, 32f * u, 32f * u, 0.5f * b * gain)
            sprites.draw(canvas, core, x, y, 6f * u, 6f * u, b * 1.2f)
            // And its light on the water underneath.
            val mirror = water + (water - y) * 0.28f + 6f * u
            if (mirror < height + 20f * u) sprites.draw(canvas, glow, x + sin(t * 1.7f + i) * 1.5f * u, mirror, 18f * u, 44f * u, 0.22f * b * gain)
        }
        // And a few so close to the lens they are only big soft discs.
        for (i in 0 until near) {
            val k = i + 3000
            val b = lit(k, t)
            if (b < 0.02f) continue
            val r = (16f + 18f * rnd(k, 47)) * u
            sprites.draw(canvas, disc, flyX(k, t * 1.25f, 120f * u), flyY(k, t * 1.25f, 120f * u), r * 2f, r * 2f, (0.1f + 0.14f * rnd(k, 48)) * b * gain)
        }
        grain.draw(canvas, t, width, height)
    }
}

/*
 * Waves: the sea at night with the moon somewhere above the frame. There is no drawn wave anywhere: the water is
 * only what it does to the light, a road of glitter that narrows to the horizon, slow bands of swell coming in
 * under it, and the pale sheet of a wave sliding up the sand and back.
 */
private fun wavesScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val horizon = height * 0.33f
    val roadX = width * 0.56f
    val moon = if (dark) Color(0xFFDFE8FF) else Color(0xFFFFF3DC)
    // A point on the water: z runs 0 at the horizon to 1 at the sand; distance is squeezed up towards the horizon.
    fun seaY(z: Float) = horizon + (height * 0.9f - horizon) * z.pow(1.7f)

    val back = stage.bakeScreen(0.75f) {
        val sky = if (dark) {
            arrayOf(0f to lerp(theme.background, Color(0xFF0C1430), 0.5f), 1f to lerp(theme.background, Color(0xFF1C2A52), 0.55f))
        } else {
            // The light theme is early morning, the sun just out of frame.
            arrayOf(0f to lerp(theme.background, Color(0xFFB6C9E2), 0.75f), 1f to lerp(theme.background, Color(0xFFF3DCC4), 0.75f))
        }
        drawRect(Brush.verticalGradient(*sky, startY = 0f, endY = horizon), Offset.Zero, Size(width, horizon + 2f))
        val sea = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color(0xFF16264C), 0.55f),
                0.25f to lerp(theme.background, Color(0xFF081226), 0.6f),
                1f to lerp(theme.background, Color(0xFF03070F), 0.6f),
            )
        } else {
            arrayOf(
                0f to lerp(theme.background, Color(0xFF9FB6CF), 0.8f),
                0.3f to lerp(theme.background, Color(0xFF6F8DAB), 0.82f),
                1f to lerp(theme.background, Color(0xFF55718F), 0.85f),
            )
        }
        drawRect(Brush.verticalGradient(*sea, startY = horizon, endY = height), Offset(0f, horizon), Size(width, height - horizon))
        // The moon is above the picture: only its light comes down through the haze.
        wash(moon, if (dark) 0.3f else 0.5f, Offset(roadX, -40f * u), 300f * u, blend = blend)
        wash(moon, if (dark) 0.14f else 0.3f, Offset(roadX, horizon * 0.5f), 240f * u, 1.2f, blend)
        wash(if (dark) theme.secondary else Color(0xFFFFD9B8), if (dark) 0.1f else 0.3f, Offset(width * 0.2f, horizon), 240f * u, 0.25f, blend)
        wash(if (dark) theme.primary else Color.White, if (dark) 0.07f else 0.25f, Offset(width * 0.85f, horizon), 200f * u, 0.22f, blend)
        // The road of light on the water, before any single glint: wide and faint near, a bright thread far off.
        repeat(14) { i ->
            val z = i / 13f
            wash(moon, (if (dark) 0.1f else 0.14f) * (1f - z * 0.6f), Offset(roadX, seaY(z)), (30f + 150f * z) * u, 0.28f, blend)
        }
        wash(moon, if (dark) 0.3f else 0.4f, Offset(roadX, horizon + 3f * u), 70f * u, 0.1f, blend)
        // Boats fishing by lamplight out on the horizon, and the streak each lamp lays on the water.
        val lamps = if (dark) listOf(theme.primary, Color(0xFFFFB070), theme.secondary, Color(0xFFFFB070), theme.primary) else listOf(Color(0xFFFFF0D8))
        val places = floatArrayOf(0.07f, 0.16f, 0.3f, 0.8f, 0.9f, 0.97f)
        repeat(6) { i ->
            val color = lamps[i % lamps.size]
            val bright = 0.3f + 0.5f * rnd(i, 3)
            bokeh(color, (if (dark) 0.9f else 0.6f) * bright, Offset(width * places[i], horizon - 3f * u), (4f + 3f * rnd(i, 4)) * u, blend)
            wash(color, (if (dark) 0.3f else 0.25f) * bright, Offset(width * places[i], horizon + 16f * u), 5f * u, 5f, blend)
        }
        // The horizon is a soft dark seam where the haze meets the water, never a line.
        val seam = if (dark) Color(0xFF00000A) else Color(0xFF465A78)
        drawRect(
            Brush.verticalGradient(
                0f to seam.copy(alpha = 0f),
                0.5f to seam.copy(alpha = if (dark) 0.3f else 0.2f),
                1f to seam.copy(alpha = 0f),
                startY = horizon - 8f * u,
                endY = horizon + 10f * u,
            ),
            Offset(0f, horizon - 8f * u),
            Size(width, 18f * u),
        )
    }
    val shade = stage.bakeScreen(0.2f) {
        vignette(if (dark) Color.Black.copy(alpha = 0.5f) else Color(0xFF1E3250).copy(alpha = 0.18f), 0.5f, 0.32f, 0.82f)
    }
    val glint = stage.dot(if (dark) Color(0xFFEEF3FF) else Color.White)
    val band = stage.dot(if (dark) Color(0xFF9FB4E8) else Color.White)
    val trough = stage.dot(if (dark) Color.Black else Color(0xFF2C4868))
    val foam = stage.dot(if (dark) Color(0xFFCFDCFF) else Color.White)
    val still = Sprites()
    val sprites = additive(dark)
    val grain = stage.grain(20f, 11f)
    // Every glint has its place on the water and its own moment to flash.
    val glints = stage.count(620, 200)
    val glintZ = FloatArray(glints) { rnd(it, 10).pow(0.75f) }
    val glintStray = BooleanArray(glints) { rnd(it, 14) < 0.12f }
    val glintOff = FloatArray(glints) { if (glintStray[it]) (rnd(it, 15) - 0.5f) * 2f else (rnd(it, 11) + rnd(it, 12) + rnd(it, 13) - 1.5f) / 1.5f }
    val glintRate = FloatArray(glints) { 1.4f + 2.6f * rnd(it, 16) }
    val glintPhase = FloatArray(glints) { rnd(it, 17) * TAU }
    val glintSize = FloatArray(glints) { 0.6f + 0.8f * rnd(it, 18) }
    val glintY = FloatArray(glints) { seaY(glintZ[it]) }
    val bands = 11

    return { canvas, t ->
        still.fill(canvas, back, width, height)
        // Swell: long low bands rolling in from the horizon, each a dark trough with a paler back.
        for (b in 0 until bands) {
            val z = fract(b / bands.toFloat() + t * 0.022f)
            val y = seaY(z)
            val thick = (3f + 46f * z * z) * u
            val show = sin(min(1f, z * 1.15f) * PI.toFloat()) * (0.35f + 0.65f * z)
            val x = width * (0.5f + 0.25f * sin(b * 2.4f + t * 0.05f))
            val half = width * (0.9f + 0.5f * rnd(b, 20))
            still.draw(canvas, trough, x, y, half * 2f, thick * 1.6f, (if (dark) 0.5f else 0.22f) * show, anchorY = 0f)
            sprites.draw(canvas, band, x, y - thick * 1.1f, half * 2f, thick * 1.4f, (if (dark) 0.1f else 0.2f) * show, anchorY = 0f)
        }
        // Glitter: tiny mirrors, each tilting towards the moon for an instant as the water moves under it.
        for (i in 0 until glints) {
            val z = glintZ[i]
            val lift = sin(t * glintRate[i] + glintPhase[i] + z * 30f - t * 1.1f)
            if (lift < 0.55f) continue
            val flash = ((lift - 0.55f) / 0.45f).pow(2.2f)
            val spread = if (glintStray[i]) width * 0.6f else (10f + 120f * z) * u
            val x = roadX + glintOff[i] * spread + sin(t * 0.4f + glintPhase[i]) * 6f * z * u
            val w = (2f + 15f * z) * glintSize[i] * u
            val h = (0.9f + 3.6f * z) * glintSize[i] * u
            sprites.draw(canvas, glint, x, glintY[i], w * 2f, h * 2f, flash * (if (glintStray[i]) 0.35f else 0.95f) * (0.9f + 0.1f * z))
        }
        // The last of each wave: a thin sheet of foam that slides up the sand, hangs, and drains away.
        val cycle = fract(t / 9.5f)
        val reach = if (cycle < 0.38f) sin(cycle / 0.38f * PI.toFloat() / 2f) else (1f - (cycle - 0.38f) / 0.62f).pow(1.6f)
        val wet = if (cycle < 0.38f) 1f else 0.35f + 0.65f * (1f - (cycle - 0.38f) / 0.62f).pow(0.7f)
        val edge = height * (1.02f - 0.115f * reach)
        val wave = floor(t / 9.5f) * 2.1f
        for (i in 0..12) {
            val x = width * i / 12f
            val y = edge + (10f * sin(i * 1.3f + wave) + 5f * sin(i * 2.9f + t * 0.3f)) * u
            sprites.draw(canvas, foam, x, y - 8f * u, max(124f * u, width / 5f), 150f * u, (if (dark) 0.2f else 0.5f) * wet, anchorY = 0f)
            sprites.draw(canvas, foam, x, y - 7f * u, max(80f * u, width / 8f), 16f * u, (if (dark) 0.26f else 0.5f) * wet * reach, anchorY = 0f)
        }
        // Wet sand left behind keeps a little of the sky.
        sprites.draw(canvas, band, width / 2f, height * 0.9f, width * 1.4f, height * 0.18f, (if (dark) 0.1f else 0.16f) * (1f - reach), anchorY = 0f)
        still.fill(canvas, shade, width, height)
        grain.draw(canvas, t, width, height)
    }
}

/*
 * Clouds: a moonlit sky seen from above the weather, like a time-lapse from a mountain top. Banks of cloud at five
 * heights slide past at their own speeds, lit from behind so every one has a pale rim and a dark belly, and far
 * below the last layer closes into a sea of cloud.
 */
private fun cloudsScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val lightX = width * 0.63f
    val lightY = height * 0.27f
    val moon = if (dark) Color(0xFFDFE7FF) else Color(0xFFFFF6E4)
    val long = ceil(max(width, 390f * u) * 2.4f)

    val sky = stage.bakeScreen(0.75f) {
        val wash = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color(0xFF02040C), 0.6f),
                0.5f to lerp(theme.background, Color(0xFF101C44), 0.55f),
                0.85f to lerp(theme.background, theme.secondary, 0.22f),
                1f to lerp(theme.background, theme.secondary, 0.3f),
            )
        } else {
            // The light theme is the same sky an hour after sunrise.
            arrayOf(
                0f to lerp(theme.background, Color(0xFF7FA3D4), 0.8f),
                0.55f to lerp(theme.background, Color(0xFFB9D0EA), 0.75f),
                1f to lerp(theme.background, Color(0xFFFFDCC0), 0.75f),
            )
        }
        drawRect(Brush.verticalGradient(*wash))
        if (dark) repeat(stage.count(260, 80)) { i ->
            val m = rnd(i, 1).pow(4)
            drawCircle(
                Color(0xFFE1E8FF).copy(alpha = (0.2f + 0.7f * m) * (1f - rnd(i, 3) * 0.6f)),
                (0.6f + 0.9f * m) * u,
                Offset(width * rnd(i, 2), height * 0.7f * rnd(i, 3).pow(1.3f)),
            )
        }
        // The moon itself is too bright to hold: a burnt-out core inside rings of glow.
        val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
        wash(moon, if (dark) 0.16f else 0.4f, Offset(lightX, lightY), 300f * u, blend = blend)
        wash(moon, if (dark) 0.3f else 0.5f, Offset(lightX, lightY), 120f * u, blend = blend)
        wash(moon, 0.85f, Offset(lightX, lightY), 38f * u, blend = blend)
        wash(Color.White, 0.95f, Offset(lightX, lightY), 17f * u, blend = blend)
    }

    class Tone(val belly: Color, val body: Color, val rim: Color)
    fun tone(depth: Float) = if (dark) {
        Tone(
            lerp(theme.background, Color.Black, 0.45f + 0.2f * depth),
            lerp(lerp(theme.background, Color(0xFF4A5890), 0.62f), theme.secondary, 0.1f * depth),
            lerp(Color(0xFFCFD9FF), theme.secondary, 0.1f + 0.1f * depth),
        )
    } else {
        Tone(
            lerp(Color(0xFF8E9FBD), Color(0xFF6C7C9C), depth),
            lerp(Color(0xFFF2F5FB), Color(0xFFE3E9F3), depth),
            lerp(Color.White, Color(0xFFFFE6CC), 0.3f + 0.4f * depth),
        )
    }
    // One long strip of cloud that repeats end to end. Each bank is a heap of soft puffs on a level base; all the
    // bellies are laid down first, then the bodies, then the lit tops, so the heap reads as one solid thing. The
    // strip is softened with its ends joined, so there is no seam where it repeats.
    fun strip(tall: Float, seed: Int, banks: Int, puff: Float, tone: Tone, fill: Boolean): Bitmap {
        val quality = 0.4f
        val soft = stage.bakeSoft(long, tall, quality, puff * 0.16f, wrap = true) {
            val puffs = ArrayList<FloatArray>()
            repeat(banks) { b ->
                val k = seed * 100 + b
                val centerX = long * ((b + rnd(k, 1)) / banks)
                val base = tall * (if (fill) 0.5f + 0.25f * rnd(k, 2) else 0.62f + 0.2f * rnd(k, 2))
                val span = long / banks * (if (fill) 1.5f else 0.4f + 0.4f * rnd(k, 3))
                val rise = tall * (if (fill) 0.3f else 0.2f + 0.2f * rnd(k, 4))
                repeat(if (fill) 110 else 46 + (30f * rnd(k, 5)).toInt()) { i ->
                    val j = k * 50 + i
                    val along = (rnd(j, 6) + rnd(j, 7) + rnd(j, 8)) / 1.5f - 1f
                    val dome = 1f - along * along
                    puffs += floatArrayOf(centerX + along * span, base - rnd(j, 10) * rise * dome, puff * (0.55f + 0.75f * rnd(j, 9)) * (0.6f + 0.4f * dome))
                }
            }
            // Puffs are wider than tall.
            fun wide(color: Color, alpha: Float, x: Float, y: Float, r: Float) {
                for (copy in -1..1) {
                    val center = Offset(x + copy * long, y)
                    if (center.x < -r * 2f || center.x > long + r * 2f) continue
                    scale(1.7f, 1f, center) { wash(color, alpha, center, r) }
                }
            }
            puffs.forEach { wide(tone.belly, 0.4f, it[0] + it[2] * 0.1f, it[1] + it[2] * 0.55f, it[2] * 1.1f) }
            puffs.forEach { wide(tone.body, 0.55f, it[0], it[1], it[2]) }
            puffs.forEach { wide(tone.rim, 0.16f, it[0] - it[2] * 0.1f, it[1] - it[2] * 0.45f, it[2] * 0.8f) }
        }
        if (!fill) return soft
        return stage.bake(long, tall, quality) {
            picture(soft)
            drawRect(
                Brush.verticalGradient(
                    0f to tone.body.copy(alpha = 0f),
                    0.5f to tone.body.copy(alpha = 0.9f),
                    1f to tone.belly,
                    startY = tall * 0.55f,
                    endY = tall,
                ),
                Offset(0f, tall * 0.55f),
                Size(long, tall * 0.45f),
            )
        }
    }
    // High thin cloud: long combed streaks, almost nothing.
    val high = stage.bake(long, 220f * u, 0.3f) {
        repeat(46) { i ->
            for (copy in -1..1) {
                val center = Offset(long * rnd(i, 20) + copy * long, (30f + 160f * rnd(i, 21)) * u)
                rotate((-0.1f + 0.05f * rnd(i, 22)) * DEGREES, center) {
                    wash(
                        if (dark) Color(0xFFAEBCF0) else Color.White,
                        (if (dark) 0.07f else 0.3f) * (0.4f + rnd(i, 23)),
                        center,
                        (90f + 140f * rnd(i, 24)) * u,
                        0.06f + 0.06f * rnd(i, 25),
                    )
                }
            }
        }
    }
    val images = listOf(
        high,
        strip(150f * u, 1, 5, 20f * u, tone(0.1f), false),
        strip(200f * u, 2, 4, 32f * u, tone(0.35f), false),
        strip(260f * u, 3, 3, 48f * u, tone(0.6f), false),
        strip(max(330f * u, height * 0.36f), 4, 4, 70f * u, tone(1f), true),
    )
    val tall = floatArrayOf(220f * u, 150f * u, 200f * u, 260f * u, max(330f * u, height * 0.36f))
    val top = floatArrayOf(0.02f, 0.23f, 0.36f, 0.5f, 0.66f)
    val speed = floatArrayOf(5f, 9f, 15f, 24f, 38f)
    val strength = floatArrayOf(1f, 0.75f, 0.9f, 1f, 1f)
    val haze = stage.dot(if (dark) Color(0xFFCFD9FF) else Color(0xFFFFF2DC))
    val shade = stage.bakeScreen(0.2f) {
        vignette(if (dark) Color.Black.copy(alpha = 0.45f) else Color(0xFF283C64).copy(alpha = 0.14f), 0.45f, 0.32f, 0.85f)
    }
    val still = Sprites()
    val sprites = additive(dark)
    val grain = stage.grain(18f, 10f)

    return { canvas, t ->
        still.fill(canvas, sky, width, height)
        for (i in images.indices) {
            // Each layer drifts at its own pace and breathes a little, as cloud does when it is speeded up.
            val x = -floor((t * speed[i] * u + i * 173f * u).mod(long))
            val y = height * top[i] + sin(t * 0.07f + i * 1.9f) * 5f * u
            still.draw(canvas, images[i], x, y, long, tall[i], strength[i], anchorX = 0f, anchorY = 0f)
            still.draw(canvas, images[i], x + long, y, long, tall[i], strength[i], anchorX = 0f, anchorY = 0f)
            // Light from the moon caught in the air between this layer and the next.
            if (i in 1..3) sprites.draw(canvas, haze, lightX, lightY + (60f + i * 40f) * u, 460f * u, 420f * u, (if (dark) 0.08f else 0.12f) * (1f - i * 0.18f))
        }
        still.fill(canvas, shade, width, height)
        grain.draw(canvas, t, width, height)
    }
}

/*
 * Spider lilies: a field of them at nightfall, shot low among the stems with the lens wide open. A few heads in
 * the middle distance hold what focus there is; behind them the field melts into red discs, and in front two or
 * three flowers are so close they are only a red blur across the corners.
 */
private fun liliesScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dark = theme.dark
    val width = stage.width
    val height = stage.height
    val u = stage.unit / 390f
    val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val red = if (dark) lerp(Color(0xFFFF2D3F), theme.secondary, 0.16f) else lerp(Color(0xFFE4202F), theme.accent, 0.12f)
    val glow = if (dark) Color(0xFFC9D2FF) else Color(0xFFFFF4E2)

    val back = stage.bakeScreen(0.75f) {
        val sky = if (dark) {
            arrayOf(
                0f to lerp(theme.background, Color.Black, 0.55f),
                0.5f to lerp(theme.background, Color(0xFF0E1024), 0.55f),
                0.7f to lerp(theme.background, Color(0xFF2A0A14), 0.6f),
                1f to lerp(theme.background, Color(0xFF100408), 0.7f),
            )
        } else {
            arrayOf(
                0f to lerp(theme.background, Color(0xFFC9D3D6), 0.75f),
                0.5f to lerp(theme.background, Color(0xFFB4C0B4), 0.75f),
                0.72f to lerp(theme.background, Color(0xFF7D9076), 0.8f),
                1f to lerp(theme.background, Color(0xFF4F6350), 0.85f),
            )
        }
        drawRect(Brush.verticalGradient(*sky))
        // The last light in the sky behind the field, and the mist lying over it.
        wash(glow, if (dark) 0.14f else 0.4f, Offset(width * 0.4f, height * 0.42f), 280f * u, 0.6f, blend)
        wash(glow, if (dark) 0.06f else 0.2f, Offset(width * 0.7f, height * 0.2f), 260f * u, blend = blend)
        // The far edge of the field: a dark soft line of trees, no outline.
        repeat(10) { i ->
            wash(
                if (dark) Color.Black else Color(0xFF3C4C40),
                if (dark) 0.4f else 0.25f,
                Offset(width * (i / 9f) + (rnd(i, 1) - 0.5f) * 30f * u, height * (0.47f + 0.03f * rnd(i, 2))),
                (70f + 50f * rnd(i, 3)) * u,
                0.5f,
            )
        }
        // The field itself, far away: one red haze, then thousands of heads reduced to discs that shrink into it.
        wash(red, if (dark) 0.26f else 0.4f, Offset(width * 0.5f, height * 0.6f), max(330f * u, width * 0.85f), 0.24f, blend)
        wash(red, if (dark) 0.16f else 0.3f, Offset(width * 0.3f, height * 0.72f), 300f * u, 0.4f, blend)
        repeat(stage.count(170, 60)) { i ->
            val z = rnd(i, 4).pow(1.6f)
            bokeh(
                if (rnd(i, 6) < 0.2f) lerp(red, Color(0xFFFFB0A0), 0.4f) else red,
                (if (dark) 0.1f else 0.22f) + 0.34f * rnd(i, 7).pow(2),
                Offset(width * rnd(i, 5), height * (0.535f + 0.2f * z)),
                (2.5f + 11f * z) * u,
                blend,
            )
        }
        repeat(6) { i ->
            wash(glow, if (dark) 0.035f else 0.12f, Offset(width * rnd(i, 8), height * (0.5f + 0.1f * rnd(i, 9))), (150f + 90f * rnd(i, 10)) * u, 0.18f, blend)
        }
        // Down among the stems it is dark.
        val floor = if (dark) Color(0xFF050208) else Color(0xFF2C3A2E)
        drawRect(
            Brush.verticalGradient(0f to floor.copy(alpha = 0f), 1f to floor.copy(alpha = if (dark) 0.85f else 0.6f), startY = height * 0.74f, endY = height),
            Offset(0f, height * 0.74f),
            Size(width, height * 0.26f),
        )
    }

    class Head(val image: Bitmap, val size: Float, val anchorY: Float)
    // One head, about 2R across: six florets round the top of the stem, each a fan of narrow petals that curl back
    // on themselves, with the long stamens sweeping out past them and up.
    fun head(radius: Float, blur: Float, seed: Int, quality: Float): Head {
        val r = radius * u
        val pad = blur * u * 3f + 4f
        val size = r * 2.3f + pad * 2f
        val lift = r * 0.15f
        val deep = lerp(red, Color(0xFF5A0010), 0.5f)
        val pale = lerp(red, Color(0xFFFFD0C0), 0.45f)
        val sharp = stage.bake(size, size, quality) {
            translate(size / 2f, size / 2f + lift) {
                repeat(6) { f ->
                    val around = f / 6f * TAU + rnd(f, seed) * 0.5f
                    val cx = cos(around) * r * 0.15f
                    val cy = sin(around) * r * 0.07f - r * 0.02f
                    val dir = atan2(sin(around) * 0.5f - 0.35f, cos(around))
                    repeat(6) { p ->
                        val a = dir + (p - 2.5f) * 0.46f + (rnd(f * 7 + p, seed + 1) - 0.5f) * 0.3f
                        val length = r * (0.42f + 0.14f * rnd(f * 7 + p, seed + 2))
                        val ox = cos(a)
                        val oy = sin(a)
                        val side = if (p < 3) -1f else 1f
                        val path = Path()
                        path.moveTo(cx, cy)
                        path.cubicTo(
                            cx + ox * length * 0.5f, cy + oy * length * 0.5f,
                            cx + ox * length * 1.05f, cy + oy * length * 1.05f - length * 0.05f,
                            cx + ox * length * 0.82f - oy * side * length * 0.22f, cy + oy * length * 0.82f + ox * side * length * 0.22f - length * 0.3f,
                        )
                        drawPath(path, if ((p + f) % 3 == 0) deep else red, style = Stroke(r * 0.034f, cap = StrokeCap.Round))
                    }
                    repeat(6) { s ->
                        val a = dir + (s - 2.5f) * 0.26f + (rnd(f * 7 + s, seed + 3) - 0.5f) * 0.2f
                        val length = r * (0.86f + 0.14f * rnd(f * 7 + s, seed + 4))
                        val end = Offset(cx + cos(a) * length, cy + sin(a) * length * 0.8f - length * 0.34f)
                        val path = Path()
                        path.moveTo(cx, cy)
                        path.quadraticTo(cx + cos(a) * length * 0.7f, cy + sin(a) * length * 0.7f + length * 0.16f, end.x, end.y)
                        drawPath(path, red, style = Stroke(r * 0.011f, cap = StrokeCap.Round))
                        drawCircle(pale, r * 0.017f, end)
                    }
                }
            }
        }
        // Its own glow first, then the flower, so thin petals still carry light when they are out of focus.
        val halo = softened(sharp, (blur * 2.5f * u + r * 0.08f) * quality)
        val flower = softened(sharp, max(1f, blur * u * quality))
        val image = stage.bake(size, size, quality) {
            picture(halo, if (dark) 0.7f else 0.35f)
            picture(flower)
        }
        return Head(image, size, (size / 2f + lift) / size)
    }
    val middle = listOf(head(60f, 0.7f, 11, 1f), head(60f, 0.7f, 23, 1f), head(60f, 0.7f, 37, 1f))
    val behind = listOf(head(34f, 2.4f, 41, 1f), head(34f, 2.4f, 53, 1f))
    val close = listOf(head(120f, 11f, 61, 0.3f), head(120f, 11f, 73, 0.3f))
    val mote = stage.dot(if (dark) Color(0xFFFFD9C0) else Color.White)
    val shade = stage.bakeScreen(0.2f) {
        vignette(if (dark) Color.Black.copy(alpha = 0.5f) else Color(0xFF141E14).copy(alpha = 0.25f), 0.5f, 0.3f, 0.8f)
    }
    val still = Sprites()
    val sprites = additive(dark)
    val grain = stage.grain(20f, 11f)
    val stemPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    val stemColor = (if (dark) lerp(Color(0xFF16301C), theme.background, 0.3f) else Color(0xFF3D5A3A)).toArgb()
    val stem = NativePath()
    val spots = floatArrayOf(0.1f, 0.27f, 0.42f, 0.6f, 0.74f, 0.9f, 0.5f, 0.34f)
    val motes = stage.count(46, 16)

    fun flower(canvas: NativeCanvas, t: Float, head: Head, x: Float, headY: Float, baseY: Float, scale: Float, alpha: Float, stemWidth: Float, k: Int) {
        // Wind runs across the field as a wave: every stem leans with it a moment after its neighbour.
        val at = x / u
        val lean = (sin(t * 0.7f - at * 0.012f + k) * 0.035f + sin(t * 1.9f + k * 2f) * 0.012f) * (1f + 0.8f * max(0f, sin(t * 0.19f - at * 0.004f)))
        val length = baseY - headY
        val headX = x + sin(lean) * length
        val topY = baseY - cos(lean) * length
        if (stemWidth > 0f) {
            stem.rewind()
            stem.moveTo(x, baseY)
            stem.quadTo(x + sin(lean) * length * 0.3f, baseY - length * 0.55f, headX, topY)
            stemPaint.color = stemColor
            stemPaint.alpha = (alpha * 0.9f * 255f).toInt()
            stemPaint.strokeWidth = stemWidth * u
            canvas.drawPath(stem, stemPaint)
        }
        val across = head.size * scale * if (rnd(k, 32) < 0.5f) -1f else 1f
        still.draw(canvas, head.image, headX, topY, across, head.size * scale, alpha, (lean * 1.6f + (rnd(k, 31) - 0.5f) * 0.3f) * DEGREES, anchorY = head.anchorY)
    }

    return { canvas, t ->
        still.fill(canvas, back, width, height)
        for (i in 0 until 16) {
            val k = i + 100
            val z = rnd(k, 20)
            flower(canvas, t, behind[i % 2], width * (0.02f + 0.96f * ((i + rnd(k, 21)) / 16f)), height * (0.6f + 0.1f * z), height * (0.9f + 0.1f * z), 0.75f + 0.5f * z, if (dark) 0.75f else 0.85f, 1.1f, k)
        }
        for (i in spots.indices) {
            val k = i + 200
            val z = rnd(k, 22)
            flower(canvas, t, middle[i % 3], width * (spots[i] + 0.05f * (rnd(k, 23) - 0.5f)), height * (0.66f + 0.13f * z), height * 1.04f, 0.85f + 0.45f * z, 1f, 2.2f, k)
        }
        // Dust and seed in the air, lit from behind.
        val span = height * 0.75f
        for (i in 0 until motes) {
            val rise = rnd(i, 40) * span + t * (4f + 9f * rnd(i, 41)) * u
            val along = rise.mod(span)
            val x = width * rnd(i + floor(rise / span).toInt() * 13, 42) + sin(t * 0.4f + i) * 14f * u
            val r = (1f + 2.4f * rnd(i, 43) * rnd(i, 44)) * u
            val twinkle = sin(t * (0.6f + rnd(i, 45)) + i)
            sprites.draw(canvas, mote, x, height * 0.95f - along, r * 2f, r * 2f, (if (dark) 0.5f else 0.7f) * (0.4f + 0.6f * twinkle * twinkle) * sin(along / span * PI.toFloat()))
        }
        // And the ones against the lens.
        flower(canvas, t, close[0], width * 0.03f, height * 0.9f, height * 1.5f, 1.5f, if (dark) 0.8f else 0.85f, 0f, 300)
        flower(canvas, t, close[1], width * 0.97f, height * 0.84f, height * 1.5f, 1.8f, if (dark) 0.8f else 0.85f, 0f, 301)
        flower(canvas, t, close[0], width * 0.6f, height * 1.03f, height * 1.5f, 1.3f, if (dark) 0.8f else 0.85f, 0f, 302)
        still.fill(canvas, shade, width, height)
        grain.draw(canvas, t, width, height)
    }
}
