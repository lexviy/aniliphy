package com.aniliphy.ui

import android.graphics.Bitmap
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

private class Mesh(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cols: Int,
    val rows: Int,
) {
    val vertices = FloatArray((cols + 1) * (rows + 1) * 2)

    inline fun draw(
        canvas: NativeCanvas,
        paint: Paint,
        dx: (u: Float, lift: Float) -> Float,
        dy: (u: Float, lift: Float) -> Float,
    ) {
        var index = 0
        for (row in 0..rows) {
            val v = row / rows.toFloat()
            val lift = 1f - v
            val y = top + v * height
            for (col in 0..cols) {
                val u = col / cols.toFloat()
                vertices[index++] = left + u * width + dx(u, lift)
                vertices[index++] = y + dy(u, lift)
            }
        }
        canvas.drawBitmapMesh(bitmap, cols, rows, vertices, 0, null, 0, paint)
    }
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

private fun DrawScope.softDisc(color: Color, center: Offset, radius: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            0.84f to color,
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

private fun DrawScope.softBand(color: Color, center: Offset, radiusX: Float, radiusY: Float) {
    scale(1f, radiusY / radiusX, center) { softCircle(color, center, radiusX) }
}

private fun DrawScope.ridge(color: Color, baseline: Float, rise: Float, roughness: Float, seed: Int) {
    val path = Path()
    path.moveTo(0f, size.height)
    val step = max(2f, size.width / 180f)
    var x = 0f
    val k = TAU * roughness / size.width
    while (x <= size.width + step) {
        val shape = sin(x * k + seed) * 0.5f +
            sin(x * k * 2.3f + seed * 2f) * 0.28f +
            sin(x * k * 5.1f + seed * 3f) * 0.14f +
            sin(x * k * 11.3f + seed * 5f) * 0.06f
        path.lineTo(x, baseline - (shape + 0.5f) * rise)
        x += step
    }
    path.lineTo(size.width, size.height)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.pine(x: Float, ground: Float, tall: Float, color: Color, snow: Color?, seed: Int) {
    drawLine(color, Offset(x, ground), Offset(x, ground - tall * 0.3f), strokeWidth = tall * 0.035f)
    val tiers = 7
    for (tier in 0 until tiers) {
        val low = tier / tiers.toFloat()
        val top = ground - tall + low * tall * 0.78f
        val bottom = top + tall * 0.27f
        val half = tall * (0.05f + 0.21f * low) * (0.9f + 0.2f * rnd(seed + tier, 90))
        val bough = Path().apply {
            moveTo(x, top)
            quadraticTo(x + half * 0.42f, top + (bottom - top) * 0.62f, x + half, bottom)
            quadraticTo(x, bottom - (bottom - top) * 0.2f, x - half, bottom)
            quadraticTo(x - half * 0.42f, top + (bottom - top) * 0.62f, x, top)
            close()
        }
        drawPath(bough, color)
        if (snow != null) {
            val cap = Path().apply {
                moveTo(x, top)
                quadraticTo(x + half * 0.35f, top + (bottom - top) * 0.5f, x + half * 0.92f, bottom - tall * 0.008f)
                quadraticTo(x + half * 0.3f, top + (bottom - top) * 0.62f, x, top + (bottom - top) * 0.34f)
                quadraticTo(x - half * 0.3f, top + (bottom - top) * 0.62f, x - half * 0.92f, bottom - tall * 0.008f)
                quadraticTo(x - half * 0.35f, top + (bottom - top) * 0.5f, x, top)
                close()
            }
            drawPath(cap, snow)
        }
    }
}

private fun DrawScope.moon(center: Offset, radius: Float, light: Color, shade: Color) {
    softCircle(light.copy(alpha = 0.3f), center, radius * 6f)
    softCircle(light.copy(alpha = 0.45f), center, radius * 2.2f)
    drawCircle(light, radius, center)
    repeat(26) { i ->
        val spot = center + Offset(rnd(i, 95) * 1.2f - 0.75f, rnd(i, 96) * 1.3f - 0.8f) * radius
        val size = radius * (0.12f + 0.3f * rnd(i, 97))
        if ((spot - center).getDistance() + size * 0.7f < radius) {
            softCircle(shade.copy(alpha = 0.13f), spot, size)
        }
    }
}

private fun DrawScope.verticalWash(top: Color, bottom: Color, from: Float = 0f, to: Float = size.height) {
    drawRect(
        brush = Brush.verticalGradient(listOf(top, bottom), startY = from, endY = to),
        topLeft = Offset(0f, from),
        size = Size(size.width, to - from),
    )
}

private fun Stage.grassMesh(tall: Float, color: Color, seed: Int, gap: Float = 2.6f): Mesh {
    val extra = 30f * dp
    val w = width + extra * 2f
    val bitmap = bake(w, tall, 0.8f) {
        val blades = (w / (gap * dp)).toInt()
        repeat(blades) { i ->
            val id = i + seed * 3000
            val x = rnd(id, 41) * w
            val blade = tall * (0.3f + 0.7f * rnd(id, 42) * rnd(id, 45).coerceAtLeast(0.5f))
            val lean = (rnd(id, 43) - 0.5f) * tall * 0.3f
            val tint = lerp(color, Color.White, rnd(id, 46) * 0.14f)
            val path = Path().apply {
                moveTo(x, tall)
                quadraticTo(x + lean * 0.15f, tall - blade * 0.6f, x + lean, tall - blade)
            }
            drawPath(
                path,
                tint.copy(alpha = color.alpha * (0.45f + 0.55f * rnd(id, 44))),
                style = Stroke((0.7f + 0.9f * rnd(id, 47)) * dp, cap = StrokeCap.Round),
            )
        }
    }
    return Mesh(bitmap, -extra, height - tall, w, tall, cols = 24, rows = 3)
}

private fun gustBend(u: Float, lift: Float, t: Float, reach: Float, phase: Float): Float =
    (sin(u * 21f + t * 1.5f + phase) * 0.6f + sin(u * 7f + t * 0.6f + phase) * 0.8f) * reach * lift * lift

private const val CITY_CYCLE = 28f
private const val CITY_WALK_END = 15f
private const val CITY_WALK_FLASH = 13f
private const val CITY_CARS_START = 16.5f
private const val CITY_CARS_AMBER = 26f

private const val CITY_EYE = 1.6f

private const val CITY_OFFICE = 0
private const val CITY_GLASS = 1
private const val CITY_SIGN_WALL = 2
private const val CITY_SIGN = 3
private const val CITY_FAR = 4

private val CityWords = listOf(
    "ラーメン", "カラオケ", "居酒屋", "ホテル", "映画", "寿司", "ゲーム",
    "喫茶", "書店", "中古", "電気", "音楽", "焼肉", "薬",
)
private val CityNeon = listOf(
    Color(0xFFFF3D8B), Color(0xFF3DE8FF), Color(0xFFFFB23D),
    Color(0xFF5CFF9A), Color(0xFFFF5252), Color(0xFFA97BFF),
)

private class CityLens(width: Float, height: Float) {
    private val centerX = width * 0.5f
    private val centerY = height * 0.5f
    val horizon = height * 0.82f

    val focal = max(width * 0.62f, (horizon - centerY) * 1.732f)

    private val pitch = atan2(horizon - centerY, focal)
    private val pitchSin = sin(pitch)
    val pitchCos = cos(pitch)

    val zenith = Offset(centerX, centerY - focal * pitchCos / pitchSin)

    private fun depth(y: Float, z: Float) = (y - CITY_EYE) * pitchSin + z * pitchCos

    fun x(x: Float, y: Float, z: Float) = centerX + focal * x / depth(y, z)

    fun y(y: Float, z: Float) =
        centerY - focal * ((y - CITY_EYE) * pitchCos - z * pitchSin) / depth(y, z)

    fun point(x: Float, y: Float, z: Float) = Offset(x(x, y, z), y(y, z))
}

private class CityPalette(stage: Stage) {
    private val theme = stage.theme
    private val ink = stage.ink
    val night = theme.dark
    val neon = CityNeon.map { lerp(it, if (night) theme.accent else theme.foreground, 0.14f) }
    val warm = if (night) Color(0xFFFFDDA6) else lerp(theme.background, Color.White, 0.75f)
    val cool = if (night) Color(0xFFA8D8FF) else lerp(theme.background, ink, 0.2f)
    val pole = if (night) lerp(ink, Color.Black, 0.45f) else lerp(ink, theme.foreground, 0.55f)
    val street = if (night) lerp(ink, Color.Black, 0.35f) else lerp(ink, theme.foreground, 0.2f)
    val haze = if (night) lerp(ink, theme.secondary, 0.14f) else lerp(theme.background, ink, 0.4f)

    fun wall(seed: Int): Color {
        val mix = rnd(seed, 210)
        return if (night) lerp(ink, theme.panel, 0.1f + 0.5f * mix) else lerp(theme.background, ink, 0.45f + 0.55f * mix)
    }
}

private class CityFace(
    val ax: Float,
    val az: Float,
    val bx: Float,
    val bz: Float,
    val low: Float,
    val high: Float,
    val style: Int,
    val seed: Int,
    val word: String = "",
    val shade: Float = 0f,
) {
    val wide: Float
        get() {
            val dx = bx - ax
            val dz = bz - az
            return sqrt(dx * dx + dz * dz)
        }
    val tall: Float get() = high - low
    val nearest: Float get() = min(az, bz)
    val middle: Float get() = (az + bz) * 0.5f

    fun corners(lens: CityLens): FloatArray {
        val mirrored = ax > bx
        val lx = if (mirrored) bx else ax
        val lz = if (mirrored) bz else az
        val rx = if (mirrored) ax else bx
        val rz = if (mirrored) az else bz
        return floatArrayOf(
            lens.x(lx, high, lz), lens.y(high, lz),
            lens.x(rx, high, rz), lens.y(high, rz),
            lens.x(rx, low, rz), lens.y(low, rz),
            lens.x(lx, low, lz), lens.y(low, lz),
        )
    }

    fun matrix(lens: CityLens, w: Float, h: Float) = Matrix().apply {
        setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, corners(lens), 0, 4)
    }

    fun visible(lens: CityLens, width: Float, height: Float): Boolean {
        val c = corners(lens)
        val left = min(min(c[0], c[2]), min(c[4], c[6]))
        val right = max(max(c[0], c[2]), max(c[4], c[6]))
        val top = min(min(c[1], c[3]), min(c[5], c[7]))
        val bottom = max(max(c[1], c[3]), max(c[5], c[7]))
        return right > 0f && left < width && bottom > 0f && top < height
    }

    fun path(lens: CityLens) = Path().apply {
        val c = corners(lens)
        moveTo(c[0], c[1])
        lineTo(c[2], c[3])
        lineTo(c[4], c[5])
        lineTo(c[6], c[7])
        close()
    }
}

private class CityPanel(val matrix: Matrix, val lights: List<RectF>, val lightColors: IntArray, val signs: List<RectF>)

private class CityScreen(val matrix: Matrix, val frames: List<Bitmap>)

private fun cityFaces(lens: CityLens, width: Float): List<CityFace> {
    val faces = ArrayList<CityFace>()

    val farLimit = width * 0.5f * 150f * lens.pitchCos / lens.focal + 20f
    var x = -farLimit
    var far = 0
    while (x < farLimit && far < 40) {
        val wide = 10f + 14f * rnd(far, 200)
        val z = 120f + 60f * rnd(far, 201)
        faces += CityFace(x, z, x + wide, z, 0f, 70f + 110f * rnd(far, 202), CITY_FAR, 300 + far)
        x += wide * (0.75f + 0.3f * rnd(far, 203))
        far++
    }

    val facets = 9
    val radius = 12f
    val towerZ = 74f
    for (i in 0 until facets) {
        val a0 = (-82f + 164f * i / facets) * PI.toFloat() / 180f
        val a1 = (-82f + 164f * (i + 1) / facets) * PI.toFloat() / 180f
        val edge = abs((a0 + a1) * 0.5f) / 1.43f
        faces += CityFace(
            sin(a0) * radius, towerZ - cos(a0) * radius,
            sin(a1) * radius, towerZ - cos(a1) * radius,
            0f, 80f, CITY_GLASS, 500, shade = edge * edge,
        )
    }

    fun row(side: Float, startX: Float, farZ: Float, slope: Float, heights: List<Float>, styles: List<Int>, seed: Int) {
        var z = farZ
        heights.forEachIndexed { index, high ->
            val deep = 10f + 3f * rnd(seed + index, 204)
            val nearZ = max(z - deep, 7f)
            val farX = side * (startX + (farZ - z) * slope)
            val nearX = side * (startX + (farZ - nearZ) * slope)
            val id = seed + index
            faces += CityFace(farX, z, nearX, nearZ, 0f, high, styles[index % styles.size], id)
            var at = z - 2.5f
            var blade = 0
            while (at > nearZ + 1f) {
                val bladeId = id * 11 + blade
                val wallX = side * (startX + (farZ - at) * slope)
                val bottom = 6f + 12f * rnd(bladeId, 205)
                val top = min(bottom + 9f + 7f * rnd(bladeId, 206), high - 1f)
                faces += CityFace(
                    wallX, at, wallX - side * 2.1f, at,
                    bottom, top, CITY_SIGN, bladeId,
                    word = CityWords[bladeId.mod(CityWords.size)],
                )
                at -= 3.4f + 2f * rnd(bladeId, 207)
                blade++
            }
            z = nearZ
        }
    }
    row(-1f, 13f, 72f, 0.3f, listOf(62f, 78f, 70f, 92f, 76f, 96f), listOf(CITY_SIGN_WALL, CITY_OFFICE, CITY_SIGN_WALL), 600)
    row(1f, 12f, 58f, 0.36f, listOf(70f, 62f, 96f, 84f, 100f), listOf(CITY_OFFICE, CITY_GLASS, CITY_OFFICE), 700)

    faces += CityFace(-9f, 61f, 9f, 61f, 80f, 92f, CITY_SIGN, 811, word = "映画")
    faces += CityFace(9f, 56f, 23f, 56f, 62f, 72f, CITY_SIGN, 812, word = "薬")

    return faces.sortedByDescending { it.middle }
}

private fun cityWord(id: Int) = CityWords[id.mod(CityWords.size)]

private fun DrawScope.cityText(text: String, x: Float, centerY: Float, textSize: Float, color: Color) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.textSize = textSize
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        this.color = color.toArgb()
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, centerY - (paint.ascent() + paint.descent()) * 0.5f, paint)
}

private fun DrawScope.citySign(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    color: Color,
    word: String,
    palette: CityPalette,
    inverted: Boolean,
) {
    val w = right - left
    val h = bottom - top
    if (w <= 0f || h <= 0f) return
    val center = Offset((left + right) * 0.5f, (top + bottom) * 0.5f)
    val body = when {
        inverted -> color
        palette.night -> lerp(color, Color.Black, 0.82f)
        else -> lerp(color, Color.White, 0.72f)
    }
    val ink = when {
        inverted -> lerp(color, Color.Black, 0.8f)
        palette.night -> lerp(color, Color.White, 0.5f)
        else -> lerp(color, Color.Black, 0.3f)
    }
    drawRect(body, Offset(left, top), Size(w, h))
    drawRect(color, Offset(left, top), Size(w, h), style = Stroke(max(0.6f * density, min(w, h) * 0.05f)))

    val stacked = h > w * 1.2f
    val glyph = if (stacked) min(w * 0.72f, h / word.length * 0.84f) else min(h * 0.62f, w / word.length * 0.86f)
    if (glyph < 4f) {
        repeat(word.length) { i ->
            val p = (i + 0.5f) / word.length
            if (stacked) {
                drawRect(ink, Offset(left + w * 0.28f, top + h * p - h * 0.3f / word.length), Size(w * 0.44f, h * 0.6f / word.length))
            } else {
                drawRect(ink, Offset(left + w * p - w * 0.3f / word.length, top + h * 0.3f), Size(w * 0.6f / word.length, h * 0.4f))
            }
        }
    } else if (stacked) {
        word.forEachIndexed { i, char ->
            cityText(char.toString(), center.x, top + h * (i + 0.5f) / word.length, glyph, ink)
        }
    } else {
        cityText(word, center.x, center.y, glyph, ink)
    }
}

private fun Stage.paintCityFace(
    face: CityFace,
    scale: Float,
    palette: CityPalette,
    lights: MutableList<RectF>,
    lightColors: MutableList<Int>,
    signs: MutableList<RectF>,
): Bitmap = bake(ceil(face.wide * scale), ceil(face.tall * scale)) {
    val w = size.width
    val h = size.height
    fun rowY(height: Float) = h - (height - face.low) * scale

    if (face.style == CITY_SIGN) {
        citySign(0f, 0f, w, h, palette.neon[face.seed.mod(palette.neon.size)], face.word, palette, face.seed.mod(4) == 0)
        signs += RectF(0f, 0f, w, h)
        return@bake
    }

    val wall = palette.wall(face.seed)
    if (face.style == CITY_FAR) {
        drawRect(lerp(palette.haze, wall, 0.25f))
        val floor = 4f * scale
        val bay = 3.2f * scale
        var y = 3f * scale
        var row = 0
        while (y < h) {
            var x = bay * 0.3f
            var column = 0
            while (x < w) {
                val id = face.seed * 613 + row * 29 + column
                if (rnd(id, 211) < 0.3f) {
                    drawRect(palette.warm.copy(alpha = 0.2f + 0.3f * rnd(id, 212)), Offset(x, y), Size(bay * 0.5f, floor * 0.45f))
                }
                x += bay
                column++
            }
            y += floor
            row++
        }
        return@bake
    }

    drawRect(wall)
    val floor = 3.7f
    if (face.style == CITY_GLASS) {
        var y = 6f
        var row = 0
        while (y < face.high) {
            val top = rowY(y + floor * 0.72f)
            val strip = rnd(face.seed + row, 214)
            drawRect(
                lerp(palette.cool, wall, 0.2f + 0.55f * strip).copy(alpha = 0.34f + 0.42f * strip),
                Offset(0f, top),
                Size(w, floor * 0.72f * scale),
            )
            y += floor
            row++
        }
        var x = 0f
        while (x < w) {
            drawRect(wall, Offset(x, 0f), Size(0.14f * scale, h))
            x += 1.7f * scale
        }
    } else {
        val bay = 2.9f
        var y = 6.2f
        var row = 0
        while (y < face.high) {
            val top = rowY(y + floor * 0.62f)
            val bottom = rowY(y)
            var x = 0.45f
            var column = 0
            while (x < face.wide - bay * 0.5f) {
                val id = face.seed * 773 + row * 41 + column
                val left = x * scale
                val right = (x + bay * 0.62f) * scale
                val roll = rnd(id, 215)
                val color = if (rnd(id, 216) < 0.72f) palette.warm else palette.cool
                if (roll < 0.36f) {
                    drawRect(color.copy(alpha = 0.3f + 0.6f * rnd(id, 217)), Offset(left, top), Size(right - left, bottom - top))
                } else if (roll < 0.46f && lights.size < 34) {
                    lights += RectF(left, top, right, bottom)
                    lightColors += color.copy(alpha = 0.75f).toArgb()
                }
                x += bay
                column++
            }
            y += floor
            row++
        }
    }

    if (face.style == CITY_SIGN_WALL) {
        val rows = 7
        val rowHigh = 3.2f
        for (row in 0 until rows) {
            val y = 7f + row * (rowHigh + 0.5f)
            val cells = 2 + (face.seed + row).mod(2)
            for (cell in 0 until cells) {
                val id = face.seed * 37 + row * 5 + cell
                val left = (cell + 0.05f) / cells * w
                val right = (cell + 0.95f) / cells * w
                val top = rowY(y + rowHigh)
                val bottom = rowY(y)
                citySign(left, top, right, bottom, palette.neon[id.mod(palette.neon.size)], cityWord(id), palette, id.mod(3) == 0)
                signs += RectF(left, top, right, bottom)
            }
        }
    }

    val shopTop = rowY(5.4f)
    var shop = 0f
    var index = 0
    while (shop < face.wide) {
        val id = face.seed * 59 + index
        val long = min(3f + 4f * rnd(id, 218), face.wide - shop)
        val color = palette.neon[id.mod(palette.neon.size)]
        drawRect(lerp(lerp(palette.warm, color, 0.35f), wall, 0.45f), Offset(shop * scale, shopTop), Size(long * scale, h - shopTop))
        drawRect(lerp(color, wall, 0.2f), Offset(shop * scale, shopTop), Size(long * scale, (h - shopTop) * 0.3f))
        shop += long
        index++
    }

    if (face.shade > 0f) drawRect(Color.Black.copy(alpha = face.shade * if (palette.night) 0.55f else 0.22f))
}

private fun Stage.cityDome(color: Color, night: Boolean): Bitmap = bake(160f, 84f) {
    val dome = Path().apply {
        moveTo(2f, 82f)
        cubicTo(8f, 12f, 152f, 12f, 158f, 82f)
        quadraticTo(132f, 68f, 106f, 82f)
        quadraticTo(80f, 68f, 54f, 82f)
        quadraticTo(28f, 68f, 2f, 82f)
        close()
    }
    drawPath(
        dome,
        brush = Brush.verticalGradient(
            listOf(lerp(color, Color.White, if (night) 0.34f else 0.45f).copy(alpha = color.alpha), color, lerp(color, Color.Black, 0.24f).copy(alpha = color.alpha)),
            startY = 18f,
            endY = 82f,
        ),
    )
    listOf(28f, 54f, 106f, 132f).forEach { rib ->
        val line = Path().apply {
            moveTo(80f, 20f)
            quadraticTo((80f + rib) * 0.5f + (rib - 80f) * 0.18f, 30f, rib, 76f)
        }
        drawPath(line, lerp(color, Color.Black, 0.45f).copy(alpha = 0.5f), style = Stroke(1.5f))
    }
    val shine = Path().apply {
        moveTo(26f, 54f)
        quadraticTo(44f, 26f, 76f, 24f)
    }
    drawPath(shine, Color.White.copy(alpha = 0.42f), style = Stroke(3f, cap = StrokeCap.Round))
    drawLine(lerp(color, Color.Black, 0.5f).copy(alpha = 1f), Offset(80f, 21f), Offset(80f, 6f), strokeWidth = 3f, cap = StrokeCap.Round)
}

private fun rainScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val rich = stage.detail >= 0.6f
    val lens = CityLens(width, height)
    val palette = CityPalette(stage)
    val night = palette.night

    fun DrawScope.stormClouds(color: Color, count: Int) {
        repeat(count) { i ->
            val center = Offset(rnd(i, 101) * size.width, size.height * (0.6f * rnd(i, 102) * rnd(i, 103)))
            val radius = stage.unit * (0.2f + 0.34f * rnd(i, 104))
            softCircle(color, center, radius)
        }
    }
    val backdrop = stage.bakeScreen {
        verticalWash(stage.ink.copy(alpha = 0.6f), Color.Transparent, 0f, size.height * 0.7f)
        stormClouds(stage.ink.copy(alpha = 0.5f), 34)
        stormClouds(theme.secondary.copy(alpha = 0.07f), 22)
        val underglow = if (night) 0.22f else 0.07f
        softBand(palette.neon[0].copy(alpha = underglow), Offset(size.width * 0.28f, size.height * 0.46f), size.width * 0.9f, stage.unit * 0.55f)
        softBand(palette.neon[1].copy(alpha = underglow), Offset(size.width * 0.74f, size.height * 0.38f), size.width * 0.8f, stage.unit * 0.5f)
    }
    val litClouds = stage.bakeScreen(0.35f) { stormClouds(theme.foreground.copy(alpha = 0.16f), 28) }

    val faces = cityFaces(lens, width).filter { it.visible(lens, width, height) }.filter { rich || it.style != CITY_SIGN || it.seed > 800 }

    val screenFaces = listOf(
        CityFace(-8.2f, 61f, 8.2f, 61f, 20f, 47f, CITY_SIGN, 900),
        CityFace(12f + 10f * 0.36f, 48f, 12f + 21f * 0.36f, 37f, 19f, 50f, CITY_SIGN, 901),
        CityFace(-(13f + 22f * 0.3f), 50f, -(13f + 33f * 0.3f), 39f, 33f, 56f, CITY_SIGN, 902),
    ).filter { it.visible(lens, width, height) }
    val wordsOn = listOf(
        listOf("雨", "新作", "音楽"),
        listOf("夜", "映画", "24H"),
        listOf("本日", "光", "街"),
    )
    fun faceScale(face: CityFace, most: Float = 1500f): Float {
        val fit = lens.focal / (face.nearest * lens.pitchCos)
        return min(fit, min(most / face.wide, 2400f / face.tall)).coerceAtLeast(1.5f)
    }
    val screens = screenFaces.mapIndexed { index, face ->
        val scale = faceScale(face, 900f)
        val w = ceil(face.wide * scale)
        val h = ceil(face.tall * scale)
        val frames = List(3) { frame ->
            val from = palette.neon[(index * 2 + frame * 2 + 1).mod(palette.neon.size)]
            val to = palette.neon[(index + frame * 3 + 4).mod(palette.neon.size)]
            stage.bake(w, h) {
                val deep = if (night) 0.45f else 0.15f
                drawRect(
                    brush = Brush.linearGradient(
                        listOf(lerp(from, Color.Black, deep), lerp(to, Color.Black, deep + 0.15f)),
                        start = Offset.Zero,
                        end = Offset(w, h),
                    ),
                )
                when (frame) {
                    0 -> softCircle(lerp(to, Color.White, 0.3f).copy(alpha = 0.85f), Offset(w * 0.55f, h * 0.3f), w * 0.7f)
                    1 -> repeat(7) { bar ->
                        val y = h * (bar + 0.25f) / 7f
                        drawRect(lerp(from, Color.White, 0.25f).copy(alpha = 0.32f), Offset(0f, y), Size(w * (0.3f + 0.7f * rnd(bar + index, 250)), h * 0.05f))
                    }
                    else -> repeat(5) { ring ->
                        drawCircle(
                            lerp(to, Color.White, 0.2f).copy(alpha = 0.5f - ring * 0.08f),
                            radius = w * (0.16f + ring * 0.17f),
                            center = Offset(w * 0.5f, h * 0.68f),
                            style = Stroke(w * 0.035f),
                        )
                    }
                }
                val word = wordsOn[index % wordsOn.size][frame]
                val stacked = h > w * 1.25f && word.none { it.isDigit() }
                if (stacked) {
                    val glyph = min(w * 0.62f, h * 0.72f / word.length)
                    word.forEachIndexed { i, char ->
                        cityText(char.toString(), w * 0.5f, h * 0.5f + (i - (word.length - 1) * 0.5f) * glyph * 1.05f, glyph, Color.White.copy(alpha = 0.8f))
                    }
                } else {
                    cityText(word, w * 0.5f, h * 0.5f, min(w * 0.72f / word.length * 1.3f, h * 0.4f), Color.White.copy(alpha = 0.8f))
                }
                var line = 0f
                val pitch = max(2f, h / 110f)
                while (line < h) {
                    drawRect(Color.Black.copy(alpha = 0.14f), Offset(0f, line), Size(w, pitch * 0.34f))
                    line += pitch
                }
            }
        }
        CityScreen(face.matrix(lens, frames[0].width.toFloat(), frames[0].height.toFloat()), frames)
    }

    val tickerFace = CityFace(-8.2f, 61f, 8.2f, 61f, 16.4f, 19.2f, CITY_SIGN, 903)
    val tickerScale = faceScale(tickerFace, 900f)
    val tickerWide = ceil(tickerFace.wide * tickerScale)
    val tickerHigh = ceil(tickerFace.tall * tickerScale)
    val tickerMatrix = tickerFace.matrix(lens, tickerWide, tickerHigh)
    val ticker = run {
        val text = "雨の夜  ・  本日限定  ・  新作映画  ・  音楽  ・  カラオケ  ・  ラーメン  ・  "
        val glyph = tickerHigh * 0.68f
        val long = Paint().apply {
            textSize = glyph
            typeface = Typeface.DEFAULT_BOLD
        }.measureText(text)
        stage.bake(ceil(long), tickerHigh) {
            drawRect(lerp(palette.neon[2], Color.Black, 0.85f))
            cityText(text, size.width * 0.5f, size.height * 0.5f, glyph, palette.neon[2])
        }
    }

    val panels = ArrayList<CityPanel>()
    val poleX = -1.5f
    val poleZ = 7f
    fun polePoint(x: Float, y: Float) = lens.point(poleX + x, y, poleZ)
    val headRed = lens.point(poleX + 2.7f, 8.45f, poleZ)
    val headAmber = lens.point(poleX + 2.33f, 8.45f, poleZ)
    val headGreen = lens.point(poleX + 1.96f, 8.45f, poleZ)
    val walkerRed = lens.point(poleX + 0.42f, 4.05f, poleZ)
    val walkerGreen = lens.point(poleX + 0.42f, 3.65f, poleZ)
    val lampSize = max(3f * dp, stage.unit * 0.022f)

    val city = stage.bakeScreen(0.9f) {
        val canvas = drawContext.canvas.nativeCanvas
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        faces.forEach { face ->
            val lights = ArrayList<RectF>()
            val colors = ArrayList<Int>()
            val signs = ArrayList<RectF>()
            val flat = stage.paintCityFace(face, faceScale(face), palette, lights, colors, signs)
            val matrix = face.matrix(lens, flat.width.toFloat(), flat.height.toFloat())
            if (face.style == CITY_SIGN && palette.night) {
                val c = face.corners(lens)
                val center = Offset((c[0] + c[4]) * 0.5f, (c[1] + c[5]) * 0.5f)
                val long = abs(c[5] - c[1])
                softBand(palette.neon[face.seed.mod(palette.neon.size)].copy(alpha = 0.26f), center, long * 0.9f, long * 0.62f)
            }
            canvas.drawBitmap(flat, matrix, paint)
            flat.recycle()
            if (lights.isNotEmpty() || signs.isNotEmpty()) {
                panels += CityPanel(matrix, lights, colors.toIntArray(), signs)
            }
        }
        val frameColor = lerp(stage.ink, Color.Black, 0.5f)
        (screenFaces + tickerFace).forEach { face ->
            val outline = face.path(lens)
            drawPath(outline, frameColor)
            drawPath(outline, frameColor, style = Stroke(4f * dp))
        }

        val neonAcross = if (night) 0.3f else 0.1f
        repeat(10) { i ->
            softBand(
                palette.neon[i % palette.neon.size].copy(alpha = neonAcross),
                Offset(size.width * (i + 0.5f) / 10f, lens.horizon - 8f * dp),
                size.width * 0.32f,
                stage.unit * 0.09f,
            )
        }
        verticalWash(Color.Transparent, palette.street, lens.horizon - 4f * dp, lens.horizon + 18f * dp)
        drawRect(palette.street, Offset(0f, lens.horizon + 18f * dp), Size(size.width, size.height))

        val poleTop = polePoint(0f, 11f)
        fun wire(from: Offset, to: Offset, sag: Float) {
            val line = Path().apply {
                moveTo(from.x, from.y)
                quadraticTo((from.x + to.x) * 0.5f, (from.y + to.y) * 0.5f + sag, to.x, to.y)
            }
            drawPath(line, palette.pole.copy(alpha = 0.9f), style = Stroke(max(0.7f, 1f * dp * stage.detail)))
        }
        val h = size.height
        val w = size.width
        wire(poleTop, Offset(-4f, h * 0.1f), h * 0.05f)
        wire(poleTop, Offset(-4f, h * 0.2f), h * 0.04f)
        wire(poleTop, Offset(w + 4f, h * 0.06f), h * 0.07f)
        wire(poleTop + Offset(0f, h * 0.012f), Offset(w + 4f, h * 0.14f), h * 0.06f)
        wire(poleTop + Offset(0f, h * 0.024f), Offset(w * 0.86f, h * 0.27f), h * 0.04f)
        wire(Offset(-4f, h * 0.03f), Offset(w * 0.5f, -4f), h * 0.05f)

        val foot = polePoint(0f, 0f)
        val footWide = max(4f * dp, abs(lens.x(0.13f, 0f, poleZ) - lens.x(0f, 0f, poleZ)))
        val topWide = max(2f * dp, abs(lens.x(0.09f, 11f, poleZ) - lens.x(0f, 11f, poleZ)))
        val pole = Path().apply {
            moveTo(foot.x - footWide, foot.y)
            lineTo(poleTop.x - topWide, poleTop.y)
            lineTo(poleTop.x + topWide, poleTop.y)
            lineTo(foot.x + footWide, foot.y)
            close()
        }
        drawPath(pole, palette.pole)

        fun box(x0: Float, x1: Float, y0: Float, y1: Float, color: Color) {
            val shape = Path().apply {
                val a = polePoint(x0, y1)
                val b = polePoint(x1, y1)
                val c = polePoint(x1, y0)
                val d = polePoint(x0, y0)
                moveTo(a.x, a.y)
                lineTo(b.x, b.y)
                lineTo(c.x, c.y)
                lineTo(d.x, d.y)
                close()
            }
            drawPath(shape, color)
        }
        box(0f, 3.1f, 9.15f, 9.3f, palette.pole)
        box(1.74f, 2.92f, 8.15f, 8.75f, palette.pole)
        box(2.3f, 2.36f, 8.75f, 9.2f, palette.pole)
        box(0.13f, 0.72f, 3.3f, 4.4f, palette.pole)
        val plate = lerp(Color(0xFF1B5FBF), theme.primary, 0.12f)
        box(-1.65f, -0.15f, 6.4f, 7.15f, plate)
        box(-1.55f, -0.25f, 6.47f, 7.08f, lerp(plate, Color.White, 0.14f))
        val arrow = polePoint(-1.2f, 6.76f)
        val arrowEnd = polePoint(-0.55f, 6.76f)
        drawLine(Color.White.copy(alpha = 0.85f), arrow, arrowEnd, strokeWidth = max(1f, 1.4f * dp * stage.detail))
    }

    val cityFlash = stage.bakeScreen(0.35f) {
        faces.forEach { face ->
            if (face.style == CITY_SIGN) return@forEach
            drawPath(face.path(lens), Color.White.copy(alpha = if (face.style == CITY_FAR) 0.05f else 0.09f))
        }
    }

    val beacons = faces.filter { it.style == CITY_OFFICE || it.style == CITY_GLASS }
        .sortedByDescending { it.high / it.middle }
        .map { lens.point(it.ax + (it.bx - it.ax) * 0.4f, it.high, it.az + (it.bz - it.az) * 0.4f) }
        .filter { it.x in 0f..width && it.y > 0f }
        .take(4)

    val clear = if (night) 0.5f else 0.62f
    val domes = listOf(
        Color.White.copy(alpha = clear),
        lerp(Color.White, palette.neon[0], 0.32f).copy(alpha = clear),
        lerp(Color.White, palette.neon[1], 0.32f).copy(alpha = clear),
        Color.White.copy(alpha = clear),
        if (night) lerp(stage.ink, Color.White, 0.14f) else lerp(stage.ink, theme.foreground, 0.6f),
        if (night) lerp(stage.ink, Color.White, 0.1f) else lerp(stage.ink, theme.foreground, 0.75f),
        lerp(Color(0xFF3E63DD), stage.ink, 0.3f),
        lerp(Color(0xFFD13F45), stage.ink, 0.25f),
        lerp(Color(0xFFE6C62A), stage.ink, 0.25f),
    ).map { stage.cityDome(it, night) }
    val crowdUnit = min(height, width * 2.2f)
    val rowWide = floatArrayOf(0.052f * crowdUnit, 0.074f * crowdUnit, 0.105f * crowdUnit, 0.15f * crowdUnit)
    val rowBase = floatArrayOf(lens.horizon + rowWide[0] * 0.42f, height * 0.872f, height * 0.918f, height * 0.985f)
    val rowFirst = IntArray(4)
    val rowCount = IntArray(4)
    var crowd = 0
    for (row in 0..3) {
        rowFirst[row] = crowd
        rowCount[row] = ((width + rowWide[row] * 2f) / (rowWide[row] * 0.46f)).toInt() + 1
        crowd += rowCount[row]
    }
    val crowdX = FloatArray(crowd)
    val crowdY = FloatArray(crowd)
    val crowdWide = FloatArray(crowd)

    fun streak(color: Color) = stage.bake(256f, 18f) {
        val shape = Path().apply {
            moveTo(0f, 9f)
            lineTo(246f, 3f)
            quadraticTo(256f, 9f, 246f, 15f)
            close()
        }
        drawPath(
            shape,
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.7f to color.copy(alpha = 0.55f),
                1f to lerp(color, Color.White, 0.6f),
            ),
        )
    }
    val headlamps = streak(Color(0xFFFFEFC8))
    val taillamps = streak(Color(0xFFFF3030))
    val headBloom = stage.glow(Color(0xFFFFEFC8))
    val tailBloom = stage.glow(Color(0xFFFF3030))
    val lightSprites = Sprites().apply {
        if (night) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    val carLampCount = stage.count(14, 6)

    val signalGreen = stage.glow(Color(0xFF3DFFA0))
    val signalRed = stage.glow(Color(0xFFFF3B3B))
    val signalAmber = stage.glow(Color(0xFFFFB23D))
    val rectPaint = Paint()
    val screenPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    val drops = List(3) { layer ->
        stage.bake(5f, 72f) {
            val alpha = 0.3f + 0.35f * layer
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.75f to theme.secondary.copy(alpha = alpha * 0.55f),
                    1f to lerp(theme.secondary, theme.foreground, 0.55f).copy(alpha = alpha),
                ),
            )
        }
    }
    val droplet = stage.glow(if (night) theme.foreground else Color.White)
    val mist = stage.glow(theme.foreground.copy(alpha = 0.09f))
    val mistCount = stage.count(6, 3)
    val splashCount = stage.count(46, 8)

    val dropCount = stage.count(240, 40)
    val fanLimit = atan2(width * 0.75f, height - lens.zenith.y)
    val dropSin = FloatArray(dropCount)
    val dropCos = FloatArray(dropCount)
    val dropLean = FloatArray(dropCount)
    for (i in 0 until dropCount) {
        val angle = (rnd(i, 3) * 2f - 1f) * fanLimit
        dropSin[i] = sin(angle)
        dropCos[i] = cos(angle)
        dropLean[i] = angle * DEGREES
    }

    val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    var bolt = NativePath()
    var boltPass = -1

    fun buildBolt(pass: Int) {
        val path = NativePath()
        fun fork(startX: Float, startY: Float, reach: Float, steps: Int, id: Int, lean: Float) {
            var x = startX
            var y = startY
            path.moveTo(x, y)
            for (step in 0 until steps) {
                val key = pass * 53 + id * 17 + step
                y += reach / steps
                x += ((rnd(key, 14) - 0.5f) * 0.55f + lean) * reach / steps * 2f
                path.lineTo(x, y)
                if (id < 12 && reach > stage.unit * 0.1f && rnd(key, 15) < 0.2f) {
                    val side = if (rnd(key, 16) < 0.5f) -0.5f else 0.5f
                    fork(x, y, reach * (0.22f + 0.28f * rnd(key, 17)), steps / 2 + 2, id * 2 + step + 1, side)
                    path.moveTo(x, y)
                }
            }
        }
        val start = width * (0.2f + 0.6f * rnd(pass, 12))
        fork(start, 0f, height * (0.26f + 0.16f * rnd(pass, 13)), 18, 1, 0f)
        bolt = path
        boltPass = pass
    }

    return frame@{ canvas, t ->
        sprites.fill(canvas, backdrop, width, height)

        val period = 11f
        val pass = floor(t / period).toInt()
        val age = t - pass * period - rnd(pass, 11) * (period - 1f)
        var flash = 0f
        if (age in 0f..0.5f) {
            flash = when {
                age < 0.08f -> 1f
                age < 0.16f -> 0.25f
                age < 0.26f -> 0.75f
                else -> 0.4f * (1f - (age - 0.26f) / 0.24f)
            }
            sprites.fill(canvas, litClouds, width, height, flash)
            if (boltPass != pass) buildBolt(pass)
            val fade = (1f - age / 0.5f).coerceIn(0f, 1f)
            boltPaint.color = theme.primary.copy(alpha = 0.14f * fade).toArgb()
            boltPaint.strokeWidth = 11f * dp
            canvas.drawPath(bolt, boltPaint)
            boltPaint.color = theme.primary.copy(alpha = 0.34f * fade).toArgb()
            boltPaint.strokeWidth = 4.5f * dp
            canvas.drawPath(bolt, boltPaint)
            boltPaint.color = theme.foreground.copy(alpha = fade).toArgb()
            boltPaint.strokeWidth = 1.3f * dp
            canvas.drawPath(bolt, boltPaint)
        }

        sprites.fill(canvas, city, width, height)
        if (flash > 0f) sprites.fill(canvas, cityFlash, width, height, flash)

        val signCover = (if (night) Color.Black.copy(alpha = 0.72f) else stage.ink.copy(alpha = 0.5f)).toArgb()
        for (p in panels.indices) {
            val panel = panels[p]
            var save = -1
            for (i in panel.lights.indices) {
                val rate = 9f + 26f * rnd(i + p * 40, 251)
                val slot = floor(t / rate + rnd(i + p * 40, 252)).toInt()
                if (rnd(slot * 7 + i, 253) < 0.55f) {
                    if (save < 0) {
                        save = canvas.save()
                        canvas.concat(panel.matrix)
                    }
                    rectPaint.color = panel.lightColors[i]
                    canvas.drawRect(panel.lights[i], rectPaint)
                }
            }
            rectPaint.color = signCover
            for (i in panel.signs.indices) {
                val id = i + p * 40
                val clock = t / (5f + 9f * rnd(id, 254)) + rnd(id, 255)
                if (fract(clock) < 0.04f && rnd(floor(t * 11f).toInt() + id * 50, 256) < 0.55f) {
                    if (save < 0) {
                        save = canvas.save()
                        canvas.concat(panel.matrix)
                    }
                    canvas.drawRect(panel.signs[i], rectPaint)
                }
            }
            if (save >= 0) canvas.restoreToCount(save)
        }

        for (i in screens.indices) {
            val screen = screens[i]
            val clock = t / 7f + i * 0.37f
            val current = floor(clock).toInt().mod(screen.frames.size)
            val next = (current + 1) % screen.frames.size
            val blend = ((fract(clock) - 0.88f) / 0.12f).coerceIn(0f, 1f)
            screenPaint.alpha = 255
            canvas.drawBitmap(screen.frames[current], screen.matrix, screenPaint)
            if (blend > 0f) {
                screenPaint.alpha = (blend * 255f).toInt()
                canvas.drawBitmap(screen.frames[next], screen.matrix, screenPaint)
            }
        }
        run {
            val long = ticker.width.toFloat()
            val shift = (t * 26f * dp).mod(long)
            val save = canvas.save()
            canvas.concat(tickerMatrix)
            canvas.clipRect(0f, 0f, tickerWide, tickerHigh)
            sprites.draw(canvas, ticker, -shift, 0f, long, tickerHigh, anchorX = 0f, anchorY = 0f)
            sprites.draw(canvas, ticker, long - shift, 0f, long, tickerHigh, anchorX = 0f, anchorY = 0f)
            canvas.restoreToCount(save)
        }

        val beaconPulse = 0.5f + 0.5f * sin(t * 2f)
        for (i in beacons.indices) {
            val beacon = beacons[i]
            val glow = (8f + 6f * beaconPulse) * dp * stage.detail
            sprites.draw(canvas, signalRed, beacon.x, beacon.y, glow, glow, 0.35f + 0.6f * beaconPulse)
        }

        val lightsClock = t.mod(CITY_CYCLE)
        val round = floor(t / CITY_CYCLE).toInt()

        val walkOn = lightsClock < CITY_WALK_FLASH ||
            (lightsClock < CITY_WALK_END && fract(lightsClock * 2f) < 0.5f)
        val head = when {
            lightsClock < CITY_CARS_START -> 0
            lightsClock < CITY_CARS_AMBER -> 2
            lightsClock < CITY_CARS_AMBER + 1f -> 1
            else -> 0
        }
        val headAt = if (head == 0) headRed else if (head == 1) headAmber else headGreen
        val headGlow = if (head == 0) signalRed else if (head == 1) signalAmber else signalGreen
        sprites.draw(canvas, headGlow, headAt.x, headAt.y, lampSize * 3.4f, lampSize * 3.4f)
        val walkAt = if (walkOn) walkerGreen else walkerRed
        sprites.draw(canvas, if (walkOn) signalGreen else signalRed, walkAt.x, walkAt.y, lampSize * 2.4f, lampSize * 2.4f)

        for (i in 0 until mistCount) {
            val w = stage.unit * (0.7f + 0.8f * rnd(i, 8))
            val span = width + w
            val x = (rnd(i, 9) * span + t * (6f + 8f * rnd(i, 10)) * dp).mod(span) - w * 0.5f
            sprites.draw(canvas, mist, x, lens.horizon - (10f + 34f * rnd(i, 18)) * dp, w, w * 0.16f)
        }

        val carsOpen = ((lightsClock - CITY_CARS_START) / 1.5f).coerceIn(0f, 1f) *
            ((CITY_CARS_AMBER + 1.2f - lightsClock) / 1.2f).coerceIn(0f, 1f)
        val roadTop = lens.horizon - rowWide[0] * 0.2f
        if (carsOpen > 0f) {
            val span = width + 320f * dp
            for (i in 0 until carLampCount) {
                val rate = 1.6f + 1.9f * rnd(i, 280)
                val slot = floor(t / rate + rnd(i, 281)).toInt()
                if (rnd(slot * 13 + i, 282) > 0.72f) continue
                val toRight = i % 2 == 0
                val speed = (420f + 340f * rnd(i, 283)) * dp * stage.detail.coerceAtLeast(0.5f)
                val travel = (rnd(i, 284) * span + t * speed).mod(span) - 160f * dp
                val x = if (toRight) travel else width - travel
                val nearer = rnd(i, 285)
                val y = roadTop + (if (toRight) 0f else -5f * dp) - nearer * 7f * dp
                val long = (90f + 110f * nearer) * dp * stage.detail.coerceAtLeast(0.5f)
                val thick = (1.6f + 1.8f * nearer) * dp
                val alpha = carsOpen * (0.3f + 0.3f * nearer)
                val wideSign = if (toRight) 1f else -1f
                val look = if (toRight) headlamps else taillamps
                val bloom = if (toRight) headBloom else tailBloom
                val pair = thick * 2.2f
                lightSprites.draw(canvas, look, x, y - pair * 0.5f, long * wideSign, thick, alpha, anchorX = 1f)
                lightSprites.draw(canvas, look, x, y + pair * 0.5f, long * wideSign, thick, alpha, anchorX = 1f)
                lightSprites.draw(canvas, bloom, x, y, thick * 8f, thick * 5f, alpha * 0.5f)
            }
        }

        for (row in 0..3) {
            val wide = rowWide[row]
            val span = width + wide * 2f
            val first = rowFirst[row]
            val pace = (rowWide[row] / rowWide[3]) * 0.55f + 0.45f
            for (index in 0 until rowCount[row]) {
                val i = first + index
                val heading = if (rnd(i, 270) < 0.5f) 1f else -1f
                val delay = rnd(i, 271) * 3f
                val walk = ((lightsClock - delay) / 11f).coerceIn(0f, 1f)
                val eased = walk * walk * (3f - 2f * walk)
                val moving = walk > 0f && walk < 1f
                val distance = (70f + 90f * rnd(i, 272)) * dp * pace * stage.detail.coerceAtLeast(0.4f)
                val startX = (index + 0.15f + 0.7f * rnd(i, 273)) / rowCount[row] * span
                val x = (startX + (round + eased) * distance * heading).mod(span) - wide
                val size = wide * (0.88f + 0.24f * rnd(i, 274))
                val high = size * 0.525f
                val bob = if (moving) abs(sin(t * (4.4f + rnd(i, 275)) + i)) * high * 0.1f else 0f
                val sway = sin(t * (if (moving) 2.6f else 0.8f) + i) * (if (moving) 4f else 1.4f)
                val y = rowBase[row] + rnd(i, 276) * wide * 0.2f - bob
                sprites.draw(canvas, domes[i % domes.size], x, y, size, high, degrees = sway, anchorY = 1f)
                crowdX[i] = x
                crowdY[i] = y - high * 0.8f
                crowdWide[i] = size
            }
            if (row == 0 && carsOpen > 0f) {
                val sweep = (t * 300f * dp).mod(width + 200f * dp) - 100f * dp
                lightSprites.draw(canvas, headBloom, sweep, rowBase[0] - wide * 0.3f, wide * 3.6f, wide * 0.9f, carsOpen * 0.22f)
                lightSprites.draw(canvas, tailBloom, width - sweep * 0.8f, rowBase[0] - wide * 0.3f, wide * 3.6f, wide * 0.9f, carsOpen * 0.2f)
            }
        }

        for (i in 0 until splashCount) {
            val rate = 0.5f + 0.7f * rnd(i, 4)
            val cycle = t / rate + rnd(i, 5)
            val phase = fract(cycle)
            if (phase > 0.4f) continue
            val key = floor(cycle).toInt() * 31 + i
            val dome = (rnd(key, 6) * crowd).toInt().coerceAtMost(crowd - 1)
            val size = crowdWide[dome]
            val x = crowdX[dome] + (rnd(key, 7) - 0.5f) * size * 0.62f
            val y = crowdY[dome] + rnd(key, 19) * size * 0.14f
            val up = phase / 0.4f
            val rise = sin(up * PI.toFloat()) * size * 0.08f
            val spread = up * size * 0.08f
            val bead = max(1.2f * dp, size * 0.03f)
            for (side in -1..1) {
                sprites.draw(
                    canvas, droplet,
                    x + side * spread,
                    y - rise * (if (side == 0) 1.5f else 1f),
                    bead, bead,
                    0.85f * (1f - up),
                )
            }
        }

        val wind = 0.12f * sin(t * 0.28f) + 0.04f * sin(t * 0.9f + 1f)
        val windLean = atan2(wind, 1f) * DEGREES
        for (i in 0 until dropCount) {
            val depth = rnd(i, 1)
            val layer = if (depth < 0.5f) 0 else if (depth < 0.85f) 1 else 2
            val length = (9f + 34f * depth * depth) * dp
            val endY = lens.horizon + (height - lens.horizon) * depth
            val fromTop = -lens.zenith.y / dropCos[i]
            val reach = (endY - lens.zenith.y) / dropCos[i] - fromTop + length
            val speed = (260f + 620f * depth) * dp
            val along = (rnd(i, 2) * reach + t * speed).mod(reach)
            val r = fromTop + along
            val x = lens.zenith.x + dropSin[i] * r + along * wind
            val y = lens.zenith.y + dropCos[i] * r
            sprites.draw(
                canvas, drops[layer], x, y,
                (0.7f + 1.6f * depth) * dp, length,
                degrees = -dropLean[i] - windLean, anchorY = 1f,
            )
        }
    }
}

private fun snowScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val snowLine = height - (58f * stage.detail + 10f) * dp
    val snowColor = lerp(theme.foreground, Color.White, 0.3f)

    val backdrop = stage.bakeScreen(0.7f) {
        verticalWash(stage.ink.copy(alpha = 0.5f), Color.Transparent, 0f, size.height * 0.55f)
        moon(
            Offset(size.width * 0.74f, size.height * 0.13f),
            stage.unit * 0.075f,
            lerp(theme.foreground, Color.White, 0.3f).copy(alpha = 0.72f),
            stage.ink,
        )
        ridge(stage.ink.copy(alpha = 0.32f), snowLine - stage.unit * 0.2f, stage.unit * 0.2f, 1.3f, 4)
        listOf(0.34f to 0.38f, 0.2f to 0.62f, 0.08f to 1f).forEachIndexed { row, (lift, shade) ->
            val tall = stage.unit * (0.09f + 0.075f * row)
            val trees = (size.width / (tall * 0.3f)).toInt()
            val line = snowLine - stage.unit * lift * 0.5f
            ridge(snowColor.copy(alpha = 0.05f + 0.05f * row), line, stage.unit * 0.05f, 2.2f, 11 + row)
            repeat(trees) { i ->
                val id = i + row * 200
                if (rnd(id, 110) < 0.72f) {
                    pine(
                        rnd(id, 111) * size.width,
                        line + rnd(id, 112) * 6f * dp,
                        tall * (0.65f + 0.6f * rnd(id, 113)),
                        stage.ink.copy(alpha = shade * 0.85f),
                        snowColor.copy(alpha = 0.14f + 0.2f * shade),
                        id,
                    )
                }
            }
        }
        ridge(snowColor.copy(alpha = 0.17f), snowLine + 4f * dp, 26f * dp * stage.detail, 1.8f, 21)
        ridge(snowColor.copy(alpha = 0.26f), snowLine + 20f * dp * stage.detail, 20f * dp * stage.detail, 2.6f, 25)
        repeat(stage.count(40, 8)) { i ->
            softBand(
                theme.secondary.copy(alpha = 0.1f),
                Offset(rnd(i, 114) * size.width, snowLine + rnd(i, 115) * (size.height - snowLine)),
                (18f + 40f * rnd(i, 116)) * dp,
                (3f + 6f * rnd(i, 117)) * dp,
            )
        }
    }

    val crystals = List(3) { variant ->
        stage.bake(96f, 96f) {
            val center = Offset(48f, 48f)
            softCircle(snowColor.copy(alpha = 0.22f), center, 46f)
            repeat(6) { arm ->
                val angle = arm * TAU / 6f + 0.26f
                val out = Offset(cos(angle), sin(angle))
                val side = Offset(-out.y, out.x)
                drawLine(snowColor, center, center + out * 40f, strokeWidth = 3f, cap = StrokeCap.Round)
                val branches = 2 + variant
                repeat(branches) { b ->
                    val along = 12f + b * 26f / branches
                    val root = center + out * along
                    val reach = (20f - b * 12f / branches) * (0.7f + 0.15f * variant)
                    val left = root + (out + side) * (reach * 0.6f)
                    val right = root + (out - side) * (reach * 0.6f)
                    drawLine(snowColor, root, left, strokeWidth = 2.2f, cap = StrokeCap.Round)
                    drawLine(snowColor, root, right, strokeWidth = 2.2f, cap = StrokeCap.Round)
                }
            }
            drawCircle(snowColor, radius = 3.5f, center = center)
        }
    }
    val soft = stage.glow(snowColor)
    val blowing = stage.glow(snowColor.copy(alpha = 0.3f))

    val farCount = stage.count(150, 30)
    val crystalCount = stage.count(34, 6)
    val nearCount = stage.count(8, 2)
    val blowCount = stage.count(10, 3)
    val glintCount = stage.count(34, 6)

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)
        val gust = sin(t * 0.21f) * 30f * dp + sin(t * 0.67f) * 8f * dp
        val span = height + 24f * dp
        val widthSpan = width + 40f * dp

        for (i in 0 until farCount) {
            val depth = rnd(i, 1)
            val y = (rnd(i, 2) * span + t * (8f + 16f * depth) * dp).mod(span) - 12f * dp
            val sway = sin(t * (0.3f + 0.8f * rnd(i, 4)) + i) * (5f + 8f * depth) * dp
            val x = (rnd(i, 3) * widthSpan + sway + gust * depth * 0.5f).mod(widthSpan) - 20f * dp
            val size = (1.6f + 2.6f * depth) * dp
            sprites.draw(canvas, soft, x, y, size, size, 0.3f + 0.45f * depth)
        }
        for (i in 0 until crystalCount) {
            val id = i + 1000
            val depth = rnd(id, 1)
            val y = (rnd(id, 2) * span + t * (24f + 24f * depth) * dp).mod(span) - 12f * dp
            val sway = sin(t * (0.4f + 0.7f * rnd(id, 4)) + i) * (10f + 10f * depth) * dp
            val x = (rnd(id, 3) * widthSpan + sway + gust * (0.6f + 0.4f * depth)).mod(widthSpan) - 20f * dp
            val size = (7f + 9f * depth) * dp
            sprites.draw(
                canvas, crystals[i % 3], x, y, size, size,
                0.55f + 0.4f * depth,
                degrees = (t * (16f + 34f * rnd(id, 5)) + i * 37f),
            )
        }
        for (i in 0 until blowCount) {
            val w = (60f + 110f * rnd(i, 6)) * dp
            val spanX = width + w
            val x = (rnd(i, 7) * spanX + t * 22f * dp + gust * 2f).mod(spanX) - w * 0.5f
            val y = snowLine + rnd(i, 8) * (height - snowLine)
            sprites.draw(canvas, blowing, x, y, w, w * 0.1f, 0.6f + 0.4f * sin(t * 0.8f + i))
        }
        for (i in 0 until glintCount) {
            val shine = sin(t * (1f + rnd(i, 9)) + i * 2f)
            if (shine > 0.72f) {
                val x = rnd(i, 10) * width
                val y = snowLine + 8f * dp + rnd(i, 11) * (height - snowLine - 8f * dp)
                sprites.draw(canvas, crystals[0], x, y, 7f * dp, 7f * dp, (shine - 0.72f) * 3.5f)
            }
        }
        for (i in 0 until nearCount) {
            val id = i + 2000
            val y = (rnd(id, 2) * span + t * (62f + 30f * rnd(id, 1)) * dp).mod(span) - 12f * dp
            val x = (rnd(id, 3) * widthSpan + sin(t * 0.5f + i) * 24f * dp + gust * 1.6f).mod(widthSpan) - 20f * dp
            val size = (16f + 22f * rnd(id, 5)) * dp
            sprites.draw(canvas, soft, x, y, size, size, 0.2f)
        }
    }
}

private fun starsScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val tints = listOf(
        theme.foreground,
        lerp(theme.foreground, theme.secondary, 0.6f),
        lerp(theme.foreground, theme.accent, 0.6f),
        lerp(theme.foreground, Color(0xFFFFD9A8), 0.5f),
    )

    val backdrop = stage.bakeScreen(1f) {
        verticalWash(stage.ink.copy(alpha = 0.3f), theme.primary.copy(alpha = 0.05f))
        fun bandPoint(along: Float, off: Float) = Offset(
            size.width * (-0.1f + 1.2f * along) + off * stage.unit * 0.3f,
            size.height * (1.05f - 1.1f * along) + off * stage.unit * 0.3f,
        )
        val colors = listOf(theme.primary, theme.secondary, theme.accent)
        repeat((110 * stage.detail).toInt().coerceAtLeast(20)) { i ->
            val center = bandPoint(rnd(i, 120), (rnd(i, 121) - 0.5f) * (rnd(i, 122) + 0.3f))
            softCircle(
                colors[i % 3].copy(alpha = 0.035f + 0.04f * rnd(i, 123)),
                center,
                stage.unit * (0.07f + 0.2f * rnd(i, 124)),
            )
        }
        repeat((34 * stage.detail).toInt().coerceAtLeast(6)) { i ->
            softCircle(
                stage.ink.copy(alpha = 0.16f),
                bandPoint(rnd(i, 125), (rnd(i, 126) - 0.5f) * 0.3f),
                stage.unit * (0.03f + 0.08f * rnd(i, 127)),
            )
        }
        repeat((3200 * stage.detail).toInt().coerceAtLeast(240)) { i ->
            val inBand = rnd(i, 128) < 0.6f
            val spread = (rnd(i, 129) + rnd(i, 130) - 1f)
            val center = if (inBand) bandPoint(rnd(i, 131), spread) else Offset(rnd(i, 132) * size.width, rnd(i, 133) * size.height)
            drawCircle(
                tints[i % 4].copy(alpha = 0.12f + 0.5f * rnd(i, 134) * rnd(i, 135)),
                radius = (0.25f + 0.5f * rnd(i, 136) * rnd(i, 137)) * dp,
                center = center,
            )
        }
    }

    val starGlows = tints.map { stage.glow(it) }
    val glints = tints.map { tint ->
        stage.bake(128f, 128f) {
            val center = Offset(64f, 64f)
            softCircle(tint.copy(alpha = 0.55f), center, 26f)
            listOf(0f, 90f).forEach { angle ->
                rotate(angle, center) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0f to Color.Transparent,
                            0.5f to tint,
                            1f to Color.Transparent,
                        ),
                        topLeft = Offset(0f, 63f),
                        size = Size(128f, 2f),
                    )
                }
            }
        }
    }
    val trail = stage.bake(256f, 16f) {
        val shape = Path().apply {
            moveTo(0f, 8f)
            lineTo(8f, 3f)
            lineTo(256f, 8f)
            lineTo(8f, 13f)
            close()
        }
        drawPath(
            shape,
            brush = Brush.horizontalGradient(
                0f to theme.foreground,
                0.2f to lerp(theme.foreground, theme.accent, 0.6f).copy(alpha = 0.7f),
                1f to Color.Transparent,
            ),
        )
    }
    val headGlow = stage.glow(lerp(theme.foreground, theme.accent, 0.5f))
    val satellite = stage.glow(theme.foreground)
    val starCount = stage.count(130, 26)

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)

        for (i in 0 until starCount) {
            val depth = rnd(i, 4)
            val twinkle = 0.5f + 0.5f * sin(t * (0.5f + 2.4f * rnd(i, 3)) + i)
            val x = rnd(i, 1) * width
            val y = rnd(i, 2) * height
            val alpha = (0.25f + 0.75f * depth) * (0.35f + 0.65f * twinkle)
            if (depth > 0.86f) {
                val reach = (10f + 12f * twinkle) * dp
                sprites.draw(canvas, glints[i % 4], x, y, reach, reach, alpha)
            } else {
                val size = (1.4f + 3f * depth * depth) * dp
                sprites.draw(canvas, starGlows[i % 4], x, y, size, size, alpha)
            }
        }

        val orbit = t / 38f
        val orbitPhase = fract(orbit)
        if (orbitPhase < 0.5f) {
            val pass = floor(orbit).toInt()
            val p = orbitPhase * 2f
            val x = width * (-0.05f + 1.1f * p)
            val y = height * (0.08f + 0.2f * rnd(pass, 140)) + p * height * 0.12f
            sprites.draw(canvas, satellite, x, y, 3.2f * dp, 3.2f * dp, 0.75f * sin(p * PI.toFloat()))
        }

        for (i in 0 until 5) {
            val period = 3.5f + 3.5f * rnd(i, 5)
            val cycle = t / period + rnd(i, 6)
            val phase = fract(cycle)
            if (phase >= 0.22f) continue
            val pass = floor(cycle).toInt() + i * 97
            val progress = phase / 0.22f
            val distance = stage.unit * 0.8f
            val headX = rnd(pass, 7) * width * 0.8f + width * 0.2f - progress * distance
            val headY = rnd(pass, 8) * height * 0.75f + progress * distance * 0.5f
            val fade = sin(progress * PI.toFloat())
            val long = (70f + 110f * rnd(pass, 10)) * dp * stage.detail.coerceAtLeast(0.5f)
            val glowSize = 18f * dp
            sprites.draw(canvas, trail, headX, headY, long, 3f * dp, fade, degrees = -26.5f, anchorX = 0f)
            sprites.draw(canvas, headGlow, headX, headY, glowSize, glowSize, fade * 0.8f)
            for (spark in 0 until 6) {
                val along = (spark + 1) / 7f
                val sx = headX + along * long * 0.9f + (rnd(pass + spark, 18) - 0.5f) * 12f * dp
                val sy = headY - along * long * 0.45f + (rnd(pass + spark, 19) - 0.5f) * 12f * dp
                val sparkle = (1.8f + 1.6f * rnd(pass + spark, 20)) * dp
                sprites.draw(canvas, headGlow, sx, sy, sparkle, sparkle, fade * (1f - along))
            }
        }
    }
}

private class TreeLayers(val back: Mesh, val front: Mesh)

private class TreeSpec(val baseX: Float, val toward: Float, val tall: Float, val seed: Int)

private fun sakuraScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val pinks = listOf(
        lerp(Color(0xFFF4A7C0), theme.accent, 0.1f),
        lerp(Color(0xFFE87FA3), theme.accent, 0.1f),
        lerp(Color(0xFFFBD3E0), theme.accent, 0.1f),
    )
    val bark = lerp(Color(0xFF4A322B), theme.foreground, 0.08f)
    val barkLight = lerp(Color(0xFF8A6250), theme.foreground, 0.1f)

    val backdrop = stage.bakeScreen(0.7f) {
        verticalWash(Color.Transparent, pinks[1].copy(alpha = 0.13f), size.height * 0.25f, size.height)
        moon(
            Offset(size.width * 0.56f, size.height * 0.68f),
            stage.unit * 0.075f,
            lerp(pinks[2], Color.White, 0.3f).copy(alpha = 0.7f),
            pinks[1],
        )
        ridge(stage.ink.copy(alpha = 0.22f), size.height * 0.86f, stage.unit * 0.24f, 1.4f, 41)
        ridge(stage.ink.copy(alpha = 0.34f), size.height * 0.9f, stage.unit * 0.13f, 2.6f, 46)
        torii(Offset(size.width * 0.5f, size.height * 0.905f), stage.unit * 0.075f, stage.ink.copy(alpha = 0.8f))
        val avenue = if (stage.detail < 0.6f) 3 else 6
        for (step in avenue downTo 1) {
            listOf(-1f, 1f).forEach { side ->
                val p = step / avenue.toFloat()
                distantTree(
                    Offset(
                        size.width * (0.5f + side * (0.07f + 0.36f * (1f - p))),
                        size.height * (0.905f + 0.05f * (1f - p)),
                    ),
                    stage.unit * (0.09f + 0.17f * (1f - p)),
                    lerp(pinks[0], stage.ink, 0.1f + 0.3f * p),
                    lerp(bark, stage.ink, 0.5f),
                    step * 3 + side.toInt(),
                )
            }
        }
        softBand(pinks[2].copy(alpha = 0.16f), Offset(size.width * 0.5f, size.height * 0.91f), size.width * 0.8f, stage.unit * 0.09f)
        verticalWash(Color.Transparent, stage.ink.copy(alpha = 0.55f), size.height * 0.93f, size.height)
        repeat(stage.count(320, 40)) { i ->
            val long = (1.2f + 1.7f * rnd(i, 21)) * dp
            val center = Offset(rnd(i, 22) * size.width, size.height * (1f - 0.075f * rnd(i, 23) * rnd(i, 26)))
            rotate(rnd(i, 24) * 180f, center) {
                drawOval(
                    pinks[i % 3].copy(alpha = 0.3f + 0.5f * rnd(i, 25)),
                    topLeft = center - Offset(long, long * 0.5f),
                    size = Size(long * 2f, long),
                )
            }
        }
    }

    val trees = stage.bakeTrees(
        listOf(
            TreeSpec(width * 1.02f, -1f, height * 0.66f, 2),
            TreeSpec(width * 0.17f, 1f, height * 0.96f, 1),
        ),
        pinks,
        bark,
        barkLight,
    )
    val grass = stage.grassMesh(
        16f * dp,
        lerp(Color(0xFF4F7A4C), stage.ink, 0.35f).copy(alpha = 0.9f),
        seed = 7,
    )

    val petals = pinks.map { pink ->
        stage.bake(48f, 48f) {
            val shape = Path().apply {
                moveTo(24f, 3f)
                cubicTo(44f, 8f, 42f, 34f, 27f, 44f)
                lineTo(24f, 38f)
                lineTo(21f, 44f)
                cubicTo(6f, 34f, 4f, 8f, 24f, 3f)
                close()
            }
            drawPath(
                shape,
                brush = Brush.verticalGradient(
                    listOf(lerp(pink, pinks[1], 0.5f), pink, lerp(pink, Color.White, 0.3f)),
                    startY = 3f,
                    endY = 44f,
                ),
            )
            drawLine(lerp(pink, pinks[1], 0.7f).copy(alpha = 0.6f), Offset(24f, 6f), Offset(24f, 30f), 1.2f)
        }
    }
    val petalCount = stage.count(90, 18)

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)
        val wind = sin(t * 0.33f) * 0.5f + sin(t * 0.87f + 1f) * 0.2f
        val paint = sprites.paint
        paint.alpha = 255

        val reach = (14f + wind * 12f) * dp * stage.detail
        trees.back.draw(
            canvas, paint,
            dx = { u, lift -> (wind * reach + sin(t * 0.9f + u * 5f + lift * 3f) * 2.2f * dp) * lift * lift },
            dy = { u, lift -> sin(t * 1.1f + u * 7f) * 1.2f * dp * lift },
        )
        trees.front.draw(
            canvas, paint,
            dx = { u, lift -> (wind * reach * 1.25f + sin(t * 1.25f + u * 8f + lift * 4f) * 3.4f * dp) * lift * lift },
            dy = { u, lift -> sin(t * 1.4f + u * 9f + 1f) * 1.8f * dp * lift },
        )
        paint.alpha = 255
        grass.draw(canvas, paint, dx = { u, lift -> gustBend(u, lift, t, 3.5f * dp, 0f) }, dy = { _, _ -> 0f })

        val span = height + 24f * dp
        val widthSpan = width + height * 0.3f
        for (i in 0 until petalCount) {
            val depth = rnd(i, 1)
            val y = (rnd(i, 2) * span + t * (13f + 32f * depth) * dp).mod(span) - 12f * dp
            val drift = sin(t * (0.5f + 0.7f * rnd(i, 4)) + i) * 18f * dp + wind * 34f * dp * depth - y * 0.25f
            val x = (rnd(i, 3) * widthSpan + drift).mod(widthSpan)
            val long = (5f + 7.5f * depth) * dp
            val turn = max(0.28f, abs(sin(t * (1.2f + rnd(i, 6)) + i)))
            sprites.draw(
                canvas, petals[i % 3], x, y, long * turn, long,
                0.5f + 0.5f * depth,
                degrees = t * (24f + 50f * rnd(i, 5)) + i * 40f,
            )
        }
    }
}

private fun DrawScope.distantTree(base: Offset, tall: Float, pink: Color, trunk: Color, seed: Int) {
    drawLine(trunk, base, base - Offset(0f, tall * 0.55f), strokeWidth = max(1f, tall * 0.05f))
    repeat(46) { i ->
        val id = i + seed * 40
        val center = base + Offset((rnd(id, 141) - 0.5f) * tall * 0.95f, -tall * (0.45f + 0.55f * rnd(id, 142)))
        val shade = if (center.y < base.y - tall * 0.72f) lerp(pink, Color.White, 0.3f * rnd(id, 143)) else pink
        softCircle(shade.copy(alpha = 0.6f), center, tall * (0.09f + 0.1f * rnd(id, 144)))
    }
}

private fun DrawScope.torii(base: Offset, tall: Float, color: Color) {
    val half = tall * 0.48f
    val post = tall * 0.055f
    listOf(-1f, 1f).forEach { side ->
        val x = base.x + side * half * 0.68f
        drawRect(color, Offset(x - post, base.y - tall * 0.9f), Size(post * 2f, tall * 0.9f))
    }
    drawRect(color, Offset(base.x - half * 0.82f, base.y - tall * 0.7f), Size(half * 1.64f, tall * 0.06f))
    val top = Path().apply {
        moveTo(base.x - half * 1.2f, base.y - tall * 1.03f)
        quadraticTo(base.x, base.y - tall * 0.9f, base.x + half * 1.2f, base.y - tall * 1.03f)
        lineTo(base.x + half * 1.04f, base.y - tall * 0.9f)
        quadraticTo(base.x, base.y - tall * 0.8f, base.x - half * 1.04f, base.y - tall * 0.9f)
        close()
    }
    drawPath(top, color)
}

private fun Stage.bakeTrees(
    specs: List<TreeSpec>,
    pinks: List<Color>,
    bark: Color,
    barkLight: Color,
): TreeLayers {
    class Branch(val start: Offset, val knot: Offset, val end: Offset, val startWidth: Float, val endWidth: Float, val level: Int)
    class Clump(val center: Offset, val radius: Float, val front: Boolean, val id: Int)
    class Tree(val branches: ArrayList<Branch>, val clumps: ArrayList<Clump>, val toward: Float, val tall: Float)

    val maxLevel = if (detail < 0.6f) 3 else 4
    val extra = 44f * dp
    val deep = lerp(pinks[1], Color(0xFF8E3A5C), 0.35f)

    fun plan(spec: TreeSpec): Tree {
        val branches = ArrayList<Branch>()
        val clumps = ArrayList<Clump>()
        val clumpRadius = spec.tall * 0.027f

        fun point(b: Branch, p: Float): Offset {
            val q = 1f - p
            return b.start * (q * q) + b.knot * (2f * q * p) + b.end * (p * p)
        }

        fun grow(start: Offset, angle: Float, length: Float, thick: Float, level: Int, id: Int) {
            val key = id + spec.seed * 500
            val age = 1f - level / maxLevel.toFloat()
            val turned = (angle + (if (angle >= 0f) 1f else -1f) * age * 0.14f).coerceIn(-1.72f, 1.72f)
            val end = start + Offset(sin(turned), -cos(turned)) * length
            val across = Offset(cos(turned), sin(turned)) * (rnd(key, 31) - 0.5f) * length * 0.22f
            val knot = (start + end) * 0.5f + across + Offset(0f, length * 0.08f * age)
            val endWidth = thick * 0.72f
            val branch = Branch(start, knot, end, thick, endWidth, level)
            branches += branch
            if (level == 0 || (level == 1 && rnd(key, 46) < 0.5f)) {
                clumps += Clump(end, clumpRadius * (0.7f + 0.6f * rnd(key, 40)), rnd(key, 41) < 0.4f, key)
            }
            if (level == 0) return
            val count = if (level >= maxLevel - 1 || rnd(key, 42) < 0.35f) 3 else 2
            repeat(count) { child ->
                val childId = id * 3 + child + 1
                val childKey = childId + spec.seed * 500
                val from = if (child == 0) 1f else 0.6f + 0.35f * rnd(childKey, 43)
                val spread = (child - (count - 1) / 2f) * 0.5f + (rnd(childKey, 32) - 0.5f) * 0.26f
                grow(
                    point(branch, from),
                    turned + spread,
                    length * (0.64f + 0.14f * rnd(childKey, 33)),
                    endWidth * if (child == 0) 0.92f else 0.74f,
                    level - 1,
                    childId,
                )
            }
        }

        val trunkWide = min(spec.tall * 0.05f, width * 0.1f)
        val base = Offset(spec.baseX, height + 4f * dp)
        val fork = base + Offset(spec.toward * spec.tall * 0.035f, -spec.tall * 0.4f)
        val trunk = Branch(
            base,
            (base + fork) * 0.5f + Offset(-spec.toward * spec.tall * 0.025f, 0f),
            fork,
            trunkWide,
            trunkWide * 0.68f,
            maxLevel + 1,
        )
        branches += trunk
        grow(point(trunk, 0.86f), spec.toward * 0.95f, spec.tall * 0.17f, trunkWide * 0.32f, maxLevel - 1, 11)
        listOf(-0.42f to 0.2f, 0.08f to 0.24f, 0.52f to 0.25f, 0.92f to 0.25f).forEachIndexed { index, (angle, long) ->
            grow(fork, spec.toward * angle, spec.tall * long, trunkWide * 0.46f, maxLevel, 20 + index)
        }
        return Tree(branches, clumps, spec.toward, spec.tall)
    }

    val trees = specs.map { plan(it) }

    val stamps = listOf(lerp(pinks[1], deep, 0.4f), pinks[1], pinks[0], pinks[2], lerp(pinks[2], Color.White, 0.35f)).map { pink ->
        bake(48f, 48f) {
            val center = Offset(24f, 24f)
            repeat(5) { petal ->
                rotate(petal * 72f, center) {
                    val shape = Path().apply {
                        moveTo(24f, 24f)
                        cubicTo(12f, 18f, 12f, 4f, 22f, 2f)
                        lineTo(24f, 6f)
                        lineTo(26f, 2f)
                        cubicTo(36f, 4f, 36f, 18f, 24f, 24f)
                        close()
                    }
                    drawPath(shape, pink)
                }
            }
            drawCircle(lerp(pink, deep, 0.6f), 5f, center)
            drawCircle(Color(0xFFFFE9A8).copy(alpha = 0.85f), 2f, center)
        }
    }
    val sprites = Sprites()

    fun DrawScope.blossoms(tree: Tree, front: Boolean) {
        val canvas = drawContext.canvas.nativeCanvas
        val flower = max(1.5f * dp, tree.tall * 0.0058f)
        tree.clumps.filter { it.front == front }.sortedBy { it.center.y }.forEach { clump ->
            val r = clump.radius
            softCircle(deep.copy(alpha = if (front) 0.3f else 0.45f), clump.center + Offset(0f, r * 0.12f), r * 1.2f)
            val flowers = ((r / flower) * (r / flower) * if (front) 2.4f else 1.6f).toInt().coerceIn(5, 110)
            repeat(flowers) { i ->
                val id = clump.id * 31 + i
                val angle = rnd(id, 34) * TAU
                val out = sqrt(rnd(id, 35))
                val rise = -cos(angle) * out
                val light = (0.5f + rise * -0.5f + (rnd(id, 36) - 0.5f) * 0.5f + if (front) 0.14f else -0.2f).coerceIn(0f, 0.999f)
                val size = flower * (0.8f + 0.5f * rnd(id, 37))
                sprites.draw(
                    canvas,
                    stamps[(light * stamps.size).toInt()],
                    clump.center.x + sin(angle) * out * r,
                    clump.center.y + rise * r,
                    size,
                    size,
                    degrees = rnd(id, 38) * 72f,
                )
            }
        }
    }

    fun DrawScope.wood(tree: Tree) {
        fun point(b: Branch, p: Float): Offset {
            val q = 1f - p
            return b.start * (q * q) + b.knot * (2f * q * p) + b.end * (p * p)
        }
        fun side(b: Branch, p: Float): Offset {
            val along = (b.knot - b.start) * (1f - p) + (b.end - b.knot) * p
            val long = along.getDistance().coerceAtLeast(0.01f)
            return Offset(-along.y, along.x) / long
        }

        tree.branches.sortedBy { -it.level }.forEach { b ->
            if (b.startWidth < 1.4f * dp) {
                val twig = Path().apply {
                    moveTo(b.start.x, b.start.y)
                    quadraticTo(b.knot.x, b.knot.y, b.end.x, b.end.y)
                }
                drawPath(twig, bark, style = Stroke(b.startWidth, cap = StrokeCap.Round))
                return@forEach
            }
            val steps = 10
            val shape = Path()
            for (step in 0..steps) {
                val p = step / steps.toFloat()
                val at = point(b, p) + side(b, p) * lerpWidth(b.startWidth, b.endWidth, p) * 0.5f
                if (step == 0) shape.moveTo(at.x, at.y) else shape.lineTo(at.x, at.y)
            }
            for (step in steps downTo 0) {
                val p = step / steps.toFloat()
                val at = point(b, p) - side(b, p) * lerpWidth(b.startWidth, b.endWidth, p) * 0.5f
                shape.lineTo(at.x, at.y)
            }
            shape.close()
            drawPath(shape, bark)
            drawCircle(bark, b.endWidth * 0.5f, b.end)

            if (b.startWidth > 3f * dp) {
                val lit = if (tree.toward > 0f) -0.28f else 0.28f
                val edge = Path()
                for (step in 0..steps) {
                    val p = step / steps.toFloat()
                    val at = point(b, p) + side(b, p) * lerpWidth(b.startWidth, b.endWidth, p) * lit
                    if (step == 0) edge.moveTo(at.x, at.y) else edge.lineTo(at.x, at.y)
                }
                drawPath(edge, barkLight.copy(alpha = 0.5f), style = Stroke(b.startWidth * 0.14f, cap = StrokeCap.Round))
                repeat((b.startWidth / dp * 2.4f).toInt().coerceAtMost(70)) { mark ->
                    val id = mark + b.level * 60 + (b.start.x + b.start.y).toInt()
                    val p = rnd(id, 39)
                    val w = lerpWidth(b.startWidth, b.endWidth, p)
                    val at = point(b, p) + side(b, p) * w * (rnd(id, 44) - 0.5f) * 0.82f
                    if (mark % 3 == 0) {
                        val half = side(b, p) * w * (0.08f + 0.1f * rnd(id, 45))
                        drawLine(barkLight.copy(alpha = 0.4f), at - half, at + half, strokeWidth = 0.9f * dp)
                    } else {
                        val along = (point(b, (p + 0.05f).coerceAtMost(1f)) - point(b, p))
                        drawLine(lerp(bark, Color.Black, 0.4f).copy(alpha = 0.7f), at, at + along, strokeWidth = 0.8f * dp)
                    }
                }
            }
        }

        val trunk = tree.branches.first()
        val flare = trunk.startWidth
        val foot = Path().apply {
            moveTo(trunk.start.x - flare * 1.15f, trunk.start.y)
            quadraticTo(trunk.start.x - flare * 0.52f, trunk.start.y - flare * 0.25f, trunk.start.x - flare * 0.45f, trunk.start.y - flare * 1.5f)
            lineTo(trunk.start.x + flare * 0.45f, trunk.start.y - flare * 1.5f)
            quadraticTo(trunk.start.x + flare * 0.52f, trunk.start.y - flare * 0.25f, trunk.start.x + flare * 1.15f, trunk.start.y)
            close()
        }
        drawPath(foot, bark)
    }

    val quality = if (detail < 0.6f) 1f else 0.8f
    val boxWidth = width + extra * 2f
    val boxHeight = height + extra
    val back = bake(boxWidth, boxHeight, quality) {
        translate(extra, extra) {
            trees.forEach { tree ->
                blossoms(tree, front = false)
                wood(tree)
            }
        }
    }
    val front = bake(boxWidth, boxHeight, quality) {
        translate(extra, extra) { trees.forEach { blossoms(it, front = true) } }
    }
    return TreeLayers(
        Mesh(back, -extra, -extra, boxWidth, boxHeight, cols = 14, rows = 18),
        Mesh(front, -extra, -extra, boxWidth, boxHeight, cols = 14, rows = 18),
    )
}

private fun lerpWidth(from: Float, to: Float, p: Float) = from + (to - from) * p

private fun firefliesScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val scale = stage.detail.coerceAtLeast(0.45f)
    val treeLine = height * 0.8f
    val grassColor = lerp(Color(0xFF3F6B45), theme.secondary, 0.2f)

    val backdrop = stage.bakeScreen(0.7f) {
        verticalWash(stage.ink.copy(alpha = 0.5f), theme.secondary.copy(alpha = 0.05f), 0f, treeLine)
        moon(
            Offset(size.width * 0.8f, size.height * 0.11f),
            stage.unit * 0.07f,
            lerp(theme.foreground, Color(0xFFFFEFC2), 0.5f).copy(alpha = 0.72f),
            stage.ink,
        )
        repeat((70 * stage.detail).toInt().coerceAtLeast(10)) { i ->
            drawCircle(
                theme.foreground.copy(alpha = 0.1f + 0.5f * rnd(i, 150) * rnd(i, 151)),
                radius = (0.3f + 0.5f * rnd(i, 152)) * dp,
                center = Offset(rnd(i, 153) * size.width, rnd(i, 154) * treeLine * 0.7f),
            )
        }
        ridge(stage.ink.copy(alpha = 0.3f), treeLine, stage.unit * 0.13f, 1.7f, 51)
        listOf(0.4f, 0.65f, 0.95f).forEachIndexed { row, shade ->
            val tall = stage.unit * (0.2f + 0.08f * row)
            val line = treeLine + stage.unit * 0.05f * row
            repeat((size.width / (tall * 0.2f)).toInt()) { i ->
                val id = i + row * 300
                pine(
                    rnd(id, 155) * size.width,
                    line + rnd(id, 156) * 8f * dp,
                    tall * (0.6f + 0.6f * rnd(id, 157)),
                    stage.ink.copy(alpha = shade * 0.85f),
                    null,
                    id,
                )
            }
        }
        verticalWash(stage.ink.copy(alpha = 0.9f), lerp(grassColor, stage.ink, 0.6f), treeLine + stage.unit * 0.1f, size.height)
    }

    val grassLayers = listOf(
        stage.grassMesh(46f * dp * scale, lerp(grassColor, stage.ink, 0.4f), 1, gap = 2.2f),
        stage.grassMesh(62f * dp * scale, lerp(grassColor, stage.ink, 0.18f), 2, gap = 2.4f),
        stage.grassMesh(38f * dp * scale, grassColor, 3, gap = 2.8f),
    )
    val mist = stage.glow(theme.foreground.copy(alpha = 0.06f))
    val colors = listOf(0f, 0.3f, 0.6f).map { lerp(theme.warning, Color(0xFFD8F26B), it) }
    val glows = colors.map { stage.glow(it) }
    val cores = colors.map { color ->
        stage.bake(32f, 32f) {
            softCircle(color, Offset(16f, 16f), 16f)
            softCircle(Color.White, Offset(16f, 16f), 6f)
        }
    }
    val bugCount = stage.count(52, 11)
    val mistCount = stage.count(7, 3)
    val trail = 7

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)
        val paint = sprites.paint

        for (i in 0 until mistCount) {
            val w = stage.unit * (0.8f + 0.9f * rnd(i, 60))
            val spanX = width + w
            val x = (rnd(i, 61) * spanX + t * (3f + 4f * rnd(i, 62)) * dp).mod(spanX) - w * 0.5f
            sprites.draw(canvas, mist, x, treeLine + stage.unit * 0.1f * rnd(i, 63), w, w * 0.14f)
        }

        fun positionX(i: Int, time: Float) = rnd(i, 1) * width +
            sin(time * (0.2f + 0.35f * rnd(i, 3)) + i) * 34f * dp +
            sin(time * (0.7f + 0.5f * rnd(i, 6)) + i * 3f) * 7f * dp

        fun positionY(i: Int, time: Float) = height * (0.3f + 0.66f * rnd(i, 2)) +
            cos(time * (0.2f + 0.3f * rnd(i, 4)) + i * 2f) * 26f * dp +
            cos(time * (0.8f + 0.5f * rnd(i, 7)) + i) * 6f * dp

        paint.alpha = 255
        grassLayers[0].draw(canvas, paint, dx = { u, lift -> gustBend(u, lift, t, 5f * dp, 0f) }, dy = { _, _ -> 0f })
        grassLayers[1].draw(canvas, paint, dx = { u, lift -> gustBend(u, lift, t, 7f * dp, 1.4f) }, dy = { _, _ -> 0f })

        for (i in 0 until bugCount) {
            val color = i % 3
            val x = positionX(i, t)
            val y = positionY(i, t)
            val beat = fract(t * (0.16f + 0.14f * rnd(i, 5)) + rnd(i, 8))
            val pulse = when {
                beat < 0.18f -> sin(beat / 0.18f * PI.toFloat())
                beat < 0.36f -> 0.75f * sin((beat - 0.18f) / 0.18f * PI.toFloat())
                else -> 0.04f
            }.coerceAtLeast(0f)

            for (step in 0 until trail) {
                val past = t - (step + 1) * 0.09f
                val size = (2.8f - step * 0.28f) * dp
                sprites.draw(
                    canvas, cores[color],
                    positionX(i, past), positionY(i, past),
                    size, size,
                    pulse * 0.32f * (1f - step / trail.toFloat()),
                )
            }
            val outer = (24f + 26f * pulse) * dp
            sprites.draw(canvas, glows[color], x, y, outer, outer, 0.55f * pulse)
            val core = (4.5f + 2f * pulse) * dp
            sprites.draw(canvas, cores[color], x, y, core, core, 0.3f + 0.7f * pulse)
        }

        paint.alpha = 255
        grassLayers[2].draw(canvas, paint, dx = { u, lift -> gustBend(u, lift, t, 8f * dp, 2.6f) }, dy = { _, _ -> 0f })
    }
}

private fun wavesScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val scale = stage.detail
    val band = min(height * 0.5f, 280f * dp)
    val horizon = height - band
    val sun = Offset(width * 0.72f, horizon - 8f * dp * scale)
    val sunRadius = (20f * scale + 5f) * dp

    val backdrop = stage.bakeScreen(0.7f) {
        verticalWash(Color.Transparent, theme.accent.copy(alpha = 0.2f), horizon * 0.2f, horizon)
        softCircle(theme.accent.copy(alpha = 0.42f), sun, sunRadius * 9f)
        softCircle(lerp(theme.accent, Color.White, 0.3f).copy(alpha = 0.55f), sun, sunRadius * 3f)
        drawCircle(lerp(theme.accent, Color.White, 0.35f).copy(alpha = 0.95f), sunRadius, sun)
        clipRect(bottom = horizon) {
            repeat(14) { i ->
                rotate(i * 360f / 14f + rnd(i, 160) * 12f, sun) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(theme.accent.copy(alpha = 0.1f), Color.Transparent),
                            startX = sun.x,
                            endX = sun.x + sunRadius * 8f,
                        ),
                        topLeft = Offset(sun.x, sun.y - sunRadius * 0.12f),
                        size = Size(sunRadius * 8f, sunRadius * 0.24f),
                    )
                }
            }
        }
        repeat((30 * scale).toInt().coerceAtLeast(6)) { i ->
            val y = horizon * (0.2f + 0.75f * rnd(i, 161))
            softBand(
                lerp(theme.accent, theme.foreground, rnd(i, 162)).copy(alpha = 0.045f + 0.06f * rnd(i, 163)),
                Offset(rnd(i, 164) * size.width, y),
                stage.unit * (0.25f + 0.5f * rnd(i, 165)),
                stage.unit * (0.012f + 0.03f * rnd(i, 166)),
            )
        }
        val island = Path().apply {
            val left = size.width * 0.08f
            val w = size.width * 0.3f
            moveTo(left, horizon)
            cubicTo(left + w * 0.2f, horizon - stage.unit * 0.07f, left + w * 0.55f, horizon - stage.unit * 0.05f, left + w, horizon)
            close()
        }
        drawPath(island, stage.ink.copy(alpha = 0.7f))
        val towerX = size.width * 0.18f
        val towerTop = horizon - stage.unit * 0.1f
        drawLine(stage.ink.copy(alpha = 0.8f), Offset(towerX, horizon - stage.unit * 0.03f), Offset(towerX, towerTop), strokeWidth = 2.4f * dp * scale)
    }
    val towerLight = Offset(width * 0.18f, horizon - stage.unit * 0.1f)

    val layers = 7
    val colors = List(7) { if (it % 2 == 0) theme.secondary else theme.primary }
    val foamColor = lerp(theme.foreground, Color.White, 0.3f)
    class Layer(val mesh: Mesh, val base: Float, val amplitude: Float, val waveNumber: Float, val speed: Float, val index: Int)

    val water = List(layers) { layer ->
        val depth = layer / (layers - 1f)
        val base = horizon + band * (0.02f + 0.86f * depth)
        val amplitude = (1.6f + 12f * depth) * dp * scale.coerceAtLeast(0.5f)
        val waveNumber = TAU / (width * (1.1f - 0.5f * depth).coerceAtLeast(0.3f))
        val direction = if (layer % 2 == 0) 1f else -1f
        val speed = (0.45f + 0.45f * depth) * direction
        val top = base - amplitude * 2f
        val h = height - top
        val color = colors[layer]
        val bitmap = stage.bake(width, h, 0.6f) {
            val crest = amplitude * 2f
            drawRect(
                brush = Brush.verticalGradient(
                    0f to color.copy(alpha = 0.34f + 0.14f * depth),
                    0.25f to color.copy(alpha = 0.2f + 0.1f * depth),
                    1f to stage.ink.copy(alpha = 0.26f),
                    startY = crest,
                    endY = h,
                ),
                topLeft = Offset(0f, crest),
                size = Size(size.width, h - crest),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(foamColor.copy(alpha = 0.2f + 0.4f * depth), Color.Transparent),
                    startY = crest,
                    endY = crest + (1.5f + 3.5f * depth) * dp,
                ),
                topLeft = Offset(0f, crest),
                size = Size(size.width, (1.5f + 3.5f * depth) * dp),
            )
            val rippleBase = layer * 700
            repeat((46 * scale * (0.5f + depth)).toInt().coerceAtLeast(6)) { i ->
                val id = rippleBase + i
                val y = crest + (rnd(id, 167) * rnd(id, 168)) * min(h - crest, 80f * dp)
                val x = rnd(id, 169) * size.width
                softBand(
                    foamColor.copy(alpha = (0.06f + 0.22f * depth) * rnd(id, 170)),
                    Offset(x, y),
                    (8f + 30f * rnd(id, 171)) * dp * (0.4f + 0.6f * depth),
                    (0.5f + 1.2f * rnd(id, 172)) * dp,
                )
            }
        }
        Layer(Mesh(bitmap, 0f, top, width, h, cols = 30, rows = 3), base, amplitude, waveNumber, speed, layer)
    }

    val reflection = stage.bake(64f, 6f) {
        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to lerp(theme.accent, Color.White, 0.4f),
                1f to Color.Transparent,
            ),
        )
    }
    val sparkle = stage.glow(foamColor)
    val light = stage.glow(lerp(theme.accent, Color.White, 0.5f))
    val sparkles = stage.count(16, 4)

    val gullPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.1f * dp
        color = theme.foreground.copy(alpha = 0.6f).toArgb()
    }
    val gull = NativePath()

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)

        val beam = sin(t * 0.9f)
        if (beam > 0f) sprites.draw(canvas, light, towerLight.x, towerLight.y, 22f * dp * scale, 22f * dp * scale, beam * 0.95f)

        for (i in 0 until 5) {
            val span = width + 40f * dp
            val x = (rnd(i, 50) * span + t * (7f + 5f * rnd(i, 51)) * dp).mod(span) - 20f * dp
            val y = horizon * (0.42f + 0.42f * rnd(i, 52)) + sin(t * 0.5f + i) * 5f * dp
            val wing = (5f + 3.5f * rnd(i, 53)) * dp * scale.coerceAtLeast(0.6f)
            val flap = sin(t * 4f + i * 2f) * wing * 0.35f
            gull.rewind()
            gull.moveTo(x - wing, y - flap)
            gull.quadTo(x - wing * 0.4f, y - wing * 0.45f, x, y)
            gull.quadTo(x + wing * 0.4f, y - wing * 0.45f, x + wing, y - flap)
            canvas.drawPath(gull, gullPaint)
        }

        val paint = sprites.paint
        paint.alpha = 255
        water.forEach { layer ->
            val phase = t * layer.speed + layer.index * 2f
            val k = layer.waveNumber
            val w = width
            val a = layer.amplitude
            layer.mesh.draw(
                canvas, paint,
                dx = { _, _ -> 0f },
                dy = { u, lift ->
                    if (lift <= 0f) {
                        0f
                    } else {
                        val x = u * w
                        val swell = sin(x * k + phase) +
                            sin(x * k * 2.3f - phase * 0.7f + layer.index) * 0.38f +
                            sin(x * k * 5.1f + phase * 1.4f) * 0.12f
                        swell * a * lift
                    }
                },
            )
        }

        for (i in 0 until 18) {
            val y = horizon + band * (i + 0.5f) / 18f
            val spread = sunRadius * (0.8f + i * 0.2f) * (0.6f + 0.4f * sin(t * 1.3f + i * 1.7f))
            val wobble = sin(t * 0.9f + i) * 4f * dp
            sprites.draw(canvas, reflection, sun.x + wobble, y, spread * 2f, 2f * dp, 0.55f * (1f - i / 22f))
        }

        water.forEach { layer ->
            for (i in 0 until sparkles) {
                val id = i + layer.index * 40
                val shine = sin(t * (1.2f + 1.4f * rnd(id, 55)) + id)
                if (shine > 0.5f) {
                    val phase = t * layer.speed + layer.index * 2f
                    val x = (rnd(id, 54) * width + t * 6f * dp * (if (layer.speed > 0f) 1f else -1f)).mod(width)
                    val swell = sin(x * layer.waveNumber + phase) +
                        sin(x * layer.waveNumber * 2.3f - phase * 0.7f + layer.index) * 0.38f +
                        sin(x * layer.waveNumber * 5.1f + phase * 1.4f) * 0.12f
                    val y = layer.base + swell * layer.amplitude + rnd(id, 56) * 5f * dp
                    val depth = layer.index / (layers - 1f)
                    val size = (2.2f + 2.6f * depth) * dp
                    sprites.draw(canvas, sparkle, x, y, size, size, (shine - 0.5f) * 2f)
                }
            }
        }
    }
}

private fun cloudsScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val sun = Offset(width * 0.2f, height * 0.16f)
    val light = lerp(theme.foreground, Color.White, 0.25f)
    val shadow = lerp(theme.foreground, stage.ink, 0.6f)

    val backdrop = stage.bakeScreen(0.5f) {
        verticalWash(theme.primary.copy(alpha = 0.06f), theme.accent.copy(alpha = 0.05f))
        softCircle(theme.accent.copy(alpha = 0.3f), sun, stage.longSide * 0.55f)
        softCircle(lerp(theme.accent, Color.White, 0.3f).copy(alpha = 0.5f), sun, stage.unit * 0.2f)
        drawCircle(lerp(theme.accent, Color.White, 0.4f).copy(alpha = 0.7f), radius = stage.unit * 0.06f, center = sun)
        repeat((46 * stage.detail).toInt().coerceAtLeast(10)) { i ->
            softCircle(
                light.copy(alpha = 0.015f + 0.025f * rnd(i, 180)),
                Offset(rnd(i, 181) * size.width, size.height * (0.8f + 0.25f * rnd(i, 182))),
                stage.unit * (0.12f + 0.25f * rnd(i, 183)),
            )
        }
    }

    val beam = stage.bake(256f, 64f) {
        val color = lerp(theme.accent, Color.White, 0.3f)
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.5f to color.copy(alpha = 0.3f),
                1f to Color.Transparent,
            ),
        )
        drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black)),
            blendMode = BlendMode.DstOut,
        )
    }

    fun cloudSprite(variant: Int, puffScale: Float): Bitmap {
        val w = puffScale * 7f
        val h = puffScale * 3.6f
        return stage.bake(w, h, min(1f, 300f * dp / w).coerceIn(0.5f, 1f)) {
            val center = Offset(w * 0.5f, h * 0.66f)
            val puffs = 26
            class Puff(val at: Offset, val radius: Float)
            val list = List(puffs) { puff ->
                val key = variant * 61 + puff
                val along = rnd(key, 5) * 2f - 1f
                val swell = 1f - along * along * 0.8f
                val radius = puffScale * (0.3f + 0.55f * swell) * (0.7f + 0.5f * rnd(key, 6))
                Puff(
                    center + Offset(along * puffScale * 2.5f, -swell * puffScale * (0.1f + 0.75f * rnd(key, 7))),
                    radius,
                )
            }
            list.forEach { softDisc(shadow.copy(alpha = 0.6f), it.at + Offset(0f, it.radius * 0.3f), it.radius * 1.02f) }
            list.forEach { softDisc(lerp(shadow, light, 0.5f).copy(alpha = 0.9f), it.at, it.radius) }
            list.forEach {
                softCircle(light.copy(alpha = 0.9f), it.at + Offset(-it.radius * 0.25f, -it.radius * 0.36f), it.radius * 0.72f)
            }
            repeat(18) { wisp ->
                val key = variant * 71 + wisp
                softBand(
                    light.copy(alpha = 0.12f),
                    center + Offset((rnd(key, 8) * 2f - 1f) * puffScale * 3f, (rnd(key, 9) - 0.2f) * puffScale * 0.5f),
                    puffScale * (0.3f + 0.5f * rnd(key, 10)),
                    puffScale * 0.08f,
                )
            }
        }
    }

    class Depth(val sprites: List<Bitmap>, val count: Int, val puff: Float, val drift: Float, val alpha: Float)

    val depths = listOf(
        Depth(List(3) { cloudSprite(it + 10, stage.unit * 0.03f) }, stage.count(12, 3), stage.unit * 0.03f, 2.2f, 0.34f),
        Depth(List(4) { cloudSprite(it + 20, stage.unit * 0.05f) }, stage.count(8, 3), stage.unit * 0.05f, 4.6f, 0.55f),
        Depth(List(4) { cloudSprite(it + 30, stage.unit * 0.085f) }, stage.count(5, 2), stage.unit * 0.085f, 9f, 0.8f),
    )
    val haze = stage.glow(light.copy(alpha = 0.22f))
    val hazeCount = stage.count(8, 3)
    val birdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1f * dp
        color = stage.ink.copy(alpha = 0.85f).toArgb()
    }
    val bird = NativePath()

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)

        for (i in 0 until 6) {
            val angle = 12f + i * 15f + sin(t * 0.06f + i) * 3f
            val long = stage.longSide * (0.65f + 0.3f * rnd(i, 70))
            val thick = stage.unit * (0.05f + 0.1f * rnd(i, 71))
            sprites.draw(
                canvas, beam, sun.x, sun.y, long, thick,
                0.35f + 0.3f * sin(t * 0.22f + i * 1.7f),
                degrees = angle, anchorX = 0f,
            )
        }

        depths.forEachIndexed { index, depth ->
            for (i in 0 until depth.count) {
                val id = i + index * 100
                val size = 0.85f + 0.5f * rnd(id, 1)
                val w = depth.puff * 7f * size
                val h = depth.puff * 3.6f * size
                val span = width + w
                val drift = depth.drift * (0.8f + 0.4f * rnd(id, 3)) * dp
                val x = (rnd(id, 2) * span + t * drift).mod(span) - w * 0.5f
                val y = height * (0.1f + 0.76f * rnd(id, 4)) + sin(t * 0.12f + i) * 3f * dp
                sprites.draw(canvas, depth.sprites[i % depth.sprites.size], x, y, w, h, depth.alpha)
            }
            if (index == 0) {
                val cycle = t / 34f
                val phase = fract(cycle)
                if (phase < 0.6f) {
                    val pass = floor(cycle).toInt()
                    val p = phase / 0.6f
                    val leadX = width * (-0.15f + 1.3f * p)
                    val leadY = height * (0.2f + 0.3f * rnd(pass, 72))
                    val wing = 4.5f * dp * stage.detail.coerceAtLeast(0.6f)
                    for (b in 0 until 9) {
                        val row = (b + 1) / 2
                        val side = if (b % 2 == 0) 1f else -1f
                        val bx = leadX - row * 11f * dp * stage.detail
                        val by = leadY + side * row * 7f * dp * stage.detail
                        val flap = sin(t * 5.5f + b * 1.1f) * wing * 0.4f
                        bird.rewind()
                        bird.moveTo(bx - wing, by - flap)
                        bird.quadTo(bx - wing * 0.4f, by - wing * 0.4f, bx, by)
                        bird.quadTo(bx + wing * 0.4f, by - wing * 0.4f, bx + wing, by - flap)
                        canvas.drawPath(bird, birdPaint)
                    }
                }
            }
        }

        for (i in 0 until hazeCount) {
            val long = (120f + 220f * rnd(i, 60)) * dp * stage.detail.coerceAtLeast(0.4f)
            val span = width + long
            val x = (rnd(i, 61) * span + t * (10f + 8f * rnd(i, 62)) * dp).mod(span) - long * 0.5f
            sprites.draw(canvas, haze, x, rnd(i, 63) * height, long, long * 0.05f)
        }
    }
}

private fun liliesScene(stage: Stage): SceneFrame {
    val theme = stage.theme
    val dp = stage.dp
    val width = stage.width
    val height = stage.height
    val sprites = Sprites()
    val scale = stage.detail.coerceAtLeast(0.42f)
    val red = lerp(Color(0xFFE0283C), theme.accent, 0.1f)
    val deep = lerp(Color(0xFF8E1024), theme.accent, 0.08f)
    val stem = lerp(Color(0xFF5C8A56), theme.secondary, 0.15f)
    val pollen = Color(0xFFFFD27A)

    val backdrop = stage.bakeScreen(0.6f) {
        verticalWash(stage.ink.copy(alpha = 0.4f), red.copy(alpha = 0.16f), 0f, size.height * 0.72f)
        verticalWash(red.copy(alpha = 0.16f), stage.ink.copy(alpha = 0.3f), size.height * 0.72f, size.height)
        moon(
            Offset(size.width * 0.28f, size.height * 0.17f),
            stage.unit * 0.09f,
            lerp(red, Color(0xFFFFD9B0), 0.72f).copy(alpha = 0.8f),
            deep,
        )
        repeat((26 * stage.detail).toInt().coerceAtLeast(6)) { i ->
            softBand(
                lerp(red, stage.ink, 0.4f).copy(alpha = 0.08f + 0.1f * rnd(i, 190)),
                Offset(rnd(i, 191) * size.width, size.height * (0.08f + 0.5f * rnd(i, 192))),
                stage.unit * (0.2f + 0.5f * rnd(i, 193)),
                stage.unit * (0.012f + 0.03f * rnd(i, 194)),
            )
        }
        ridge(stage.ink.copy(alpha = 0.3f), size.height * 0.72f, stage.unit * 0.22f, 1.4f, 61)
        ridge(stage.ink.copy(alpha = 0.5f), size.height * 0.75f, stage.unit * 0.14f, 2.5f, 66)
        verticalWash(Color.Transparent, stage.ink.copy(alpha = 0.75f), size.height * 0.75f, size.height)
    }

    val rows = List(3) { row ->
        val depth = row / 2f
        val spacing = (66f - 22f * depth) * dp * scale
        val tallest = height * 0.3f + (46f + 40f * (1f - depth)) * dp * scale + 76f * dp * scale
        val extra = 40f * dp
        val w = width + extra * 2f
        val bitmap = stage.bake(w, tallest, if (stage.detail < 0.6f) 1f else 0.8f) {
            val count = (w / spacing).toInt().coerceAtLeast(2) + 1
            repeat(count) { i ->
                val id = i + row * 100
                bakedLily(
                    root = Offset((i + 0.15f + 0.7f * rnd(id, 1)) * spacing, tallest + 2f * dp),
                    tall = height * (0.12f + 0.16f * rnd(id, 2)) + (46f + 40f * (1f - depth)) * dp * scale,
                    reach = (11f + 15f * depth + 5f * rnd(id, 3)) * dp * scale,
                    lean = (rnd(id, 9) - 0.5f) * 18f * dp * scale,
                    thick = (2f + 1.6f * depth) * dp * max(scale, 0.6f),
                    alpha = 0.55f + 0.45f * depth,
                    id = id,
                    red = red,
                    deep = deep,
                    stem = stem,
                    pollen = pollen,
                )
            }
            lilyFoliage((16f + 18f * depth) * dp * scale, stem, stage.ink, row)
        }
        Mesh(bitmap, -extra, height - tallest, w, tallest, cols = 30, rows = 5)
    }
    val mist = stage.glow(theme.foreground.copy(alpha = 0.1f))
    val petal = stage.bake(48f, 18f) {
        drawOval(
            brush = Brush.horizontalGradient(listOf(deep, red, lerp(red, Color.White, 0.2f))),
            size = Size(48f, 18f),
        )
    }
    val pollenGlow = stage.glow(pollen)
    val wing = stage.bake(48f, 64f) {
        val shape = Path().apply {
            moveTo(0f, 28f)
            cubicTo(14f, 0f, 46f, 2f, 46f, 22f)
            cubicTo(46f, 34f, 30f, 38f, 22f, 36f)
            cubicTo(32f, 42f, 30f, 62f, 18f, 62f)
            cubicTo(8f, 62f, 2f, 44f, 0f, 28f)
            close()
        }
        drawPath(shape, stage.ink.copy(alpha = 1f))
        drawPath(shape, red.copy(alpha = 0.45f), style = Stroke(1.6f))
        drawCircle(red, 3.2f, Offset(32f, 16f))
        drawCircle(red, 2.5f, Offset(18f, 50f))
    }
    val petalCount = stage.count(34, 8)
    val pollenCount = stage.count(46, 10)
    val mistCount = stage.count(5, 2)
    val butterflies = if (stage.detail < 0.6f) 1 else 3

    return { canvas, t ->
        sprites.fill(canvas, backdrop, width, height)
        val gust = sin(t * 0.41f) * 0.6f + sin(t * 1.1f + 2f) * 0.25f
        val paint = sprites.paint

        rows.forEachIndexed { row, mesh ->
            val depth = row / 2f
            val reach = (5f + 5f * depth) * dp * scale
            paint.alpha = 255
            mesh.draw(
                canvas, paint,
                dx = { u, lift ->
                    (gust * 0.9f + sin(u * 19f + t * 1.1f + row) * 0.6f) * reach * lift * lift
                },
                dy = { u, lift -> sin(u * 23f + t * 1.7f + row) * 1.2f * dp * lift },
            )
            for (i in 0 until mistCount) {
                val id = i + row * 20
                val w = stage.unit * (0.7f + 0.9f * rnd(id, 60))
                val span = width + w
                val x = (rnd(id, 61) * span + t * (3f + 4f * rnd(id, 62)) * dp).mod(span) - w * 0.5f
                val y = height - (84f - 34f * row) * dp * scale - rnd(id, 63) * 20f * dp
                sprites.draw(canvas, mist, x, y, w, w * 0.15f)
            }
        }

        for (i in 0 until pollenCount) {
            val cycle = t * (0.03f + 0.04f * rnd(i, 64)) + rnd(i, 65)
            val phase = fract(cycle)
            val x = (rnd(i, 66) * width + sin(t * 0.6f + i) * 18f * dp + phase * 40f * dp).mod(width)
            val y = height * (1f - phase * (0.35f + 0.5f * rnd(i, 67)))
            val size = (2f + 2.6f * rnd(i, 68)) * dp
            val twinkle = 0.6f + 0.4f * sin(t * 2.4f + i)
            sprites.draw(canvas, pollenGlow, x, y, size, size, 0.8f * sin(phase * PI.toFloat()) * twinkle)
        }

        for (i in 0 until petalCount) {
            val cycle = t * (0.05f + 0.05f * rnd(i, 70)) + rnd(i, 71)
            val phase = fract(cycle)
            val key = floor(cycle).toInt() * 17 + i
            val x = (rnd(key, 72) * width + phase * 150f * dp + sin(t * 0.8f + i) * 9f * dp).mod(width)
            val y = height * (1f - phase * (0.45f + 0.5f * rnd(key, 73)))
            val long = (4.5f + 4.5f * rnd(i, 74)) * dp
            val turn = max(0.3f, abs(sin(t * (1.1f + rnd(i, 76)) + i)))
            sprites.draw(
                canvas, petal, x, y, long, long * 0.4f * turn,
                0.8f * sin(phase * PI.toFloat()),
                degrees = t * (40f + 70f * rnd(i, 75)) + i * 30f,
            )
        }

        for (i in 0 until butterflies) {
            val pathT = t * (0.09f + 0.03f * i) + i * 2.1f
            val x = width * (0.5f + 0.42f * sin(pathT) + 0.06f * sin(pathT * 3.7f))
            val y = height * (0.62f + 0.09f * cos(pathT * 1.3f) + 0.03f * sin(pathT * 5.1f)) - i * 40f * dp * scale
            val spread = 0.22f + 0.78f * abs(sin(t * 7f + i * 1.3f))
            val body = (15f - i * 2f) * dp * scale
            val tilt = sin(pathT * 2.2f) * 18f
            sprites.draw(canvas, wing, x, y, body * spread, body * 1.3f, 0.95f, degrees = tilt, anchorX = 0f)
            sprites.draw(canvas, wing, x, y, -body * spread, body * 1.3f, 0.95f, degrees = tilt, anchorX = 0f)
        }
    }
}

private fun DrawScope.lilyFoliage(tall: Float, leaf: Color, ground: Color, seed: Int) {
    val dp = density
    verticalWash(Color.Transparent, ground.copy(alpha = 0.85f), size.height - tall * 1.3f, size.height)
    repeat((size.width / (3.4f * dp)).toInt()) { i ->
        val id = i + seed * 4000
        val x = rnd(id, 86) * size.width
        val high = tall * (0.45f + 0.55f * rnd(id, 87))
        val bend = (rnd(id, 88) - 0.5f) * tall * 1.5f
        val blade = Path().apply {
            moveTo(x, size.height)
            quadraticTo(x + bend * 0.2f, size.height - high, x + bend, size.height - high * (0.55f + 0.3f * rnd(id, 89)))
        }
        val tint = lerp(lerp(leaf, ground, 0.35f + 0.5f * rnd(id, 90)), Color.White, 0.06f * rnd(id, 91))
        drawPath(blade, tint, style = Stroke((1.1f + 1.3f * rnd(id, 92)) * dp, cap = StrokeCap.Round))
    }
}

private fun DrawScope.lilyFloret(
    center: Offset,
    size: Float,
    facing: Float,
    alpha: Float,
    id: Int,
    red: Color,
    deep: Color,
    pollen: Color,
) {
    val dp = density
    repeat(6) { index ->
        val behind = index % 2 == 1
        val angle = facing + (index * 60f + 30f + (rnd(id + index, 83) - 0.5f) * 16f) * PI.toFloat() / 180f
        val out = Offset(sin(angle), -cos(angle))
        val reach = size * if (behind) 0.86f else 1f
        val middle = center + out * reach * 0.62f + Offset(0f, -reach * 0.16f)
        val tip = center + out * reach * 0.95f + Offset(0f, reach * 0.26f)
        val across = Offset(-out.y, out.x) * size * 0.11f
        val petal = Path().apply {
            moveTo(center.x, center.y)
            quadraticTo((middle + across).x, (middle + across).y, tip.x, tip.y)
            quadraticTo((middle - across).x, (middle - across).y, center.x, center.y)
            close()
        }
        val near = if (behind) lerp(deep, Color.Black, 0.25f) else deep
        val far = if (behind) deep else red
        drawPath(
            petal,
            brush = Brush.linearGradient(
                listOf(near.copy(alpha = alpha), far.copy(alpha = alpha)),
                start = center,
                end = tip,
            ),
        )
        if (!behind) {
            val rib = Path().apply {
                moveTo(center.x, center.y)
                quadraticTo(middle.x, middle.y, tip.x, tip.y)
            }
            drawPath(rib, lerp(red, Color.White, 0.4f).copy(alpha = alpha * 0.5f), style = Stroke(0.35f * dp))
        }
        val curl = Path().apply {
            moveTo(tip.x, tip.y)
            val hook = tip + out * size * 0.15f + Offset(0f, size * 0.15f)
            quadraticTo(tip.x + out.x * size * 0.17f, tip.y - size * 0.03f, hook.x, hook.y)
        }
        drawPath(curl, far.copy(alpha = alpha), style = Stroke(0.9f * dp, cap = StrokeCap.Round))
    }
    repeat(6) { index ->
        val angle = facing * 0.65f + ((index - 2.5f) * 19f + (rnd(id + index, 84) - 0.5f) * 10f) * PI.toFloat() / 180f
        val out = Offset(sin(angle), -cos(angle))
        val long = size * (1.3f + 0.3f * rnd(id + index, 93))
        val tip = center + out * long + Offset(0f, -size * 0.22f)
        val control = center + out * long * 0.55f + Offset(0f, size * 0.12f)
        val stamen = Path().apply {
            moveTo(center.x, center.y)
            quadraticTo(control.x, control.y, tip.x, tip.y)
        }
        drawPath(stamen, red.copy(alpha = alpha * 0.9f), style = Stroke(0.55f * dp, cap = StrokeCap.Round))
        drawCircle(lerp(red, pollen, 0.8f).copy(alpha = alpha), radius = 0.85f * dp, center = tip)
    }
}

private fun DrawScope.bakedLily(
    root: Offset,
    tall: Float,
    reach: Float,
    lean: Float,
    thick: Float,
    alpha: Float,
    id: Int,
    red: Color,
    deep: Color,
    stem: Color,
    pollen: Color,
) {
    val dp = density
    val inBud = rnd(id, 85) < 0.16f
    val height = if (inBud) tall * 0.78f else tall
    val head = Offset(root.x + lean, root.y - height)

    val swayA = (rnd(id, 94) - 0.5f) * reach * 1.1f
    val swayB = (rnd(id, 95) - 0.5f) * reach * 0.8f
    val steps = 16
    fun stemPoint(p: Float): Offset {
        val q = 1f - p
        val x = q * q * q * root.x + 3f * q * q * p * (root.x + swayA) +
            3f * q * p * p * (head.x + swayB) + p * p * p * head.x
        return Offset(x, root.y - height * p)
    }
    fun stemWidth(p: Float) = thick * (1f - 0.48f * p)
    val shade = lerp(stem, Color.Black, 0.3f).copy(alpha = alpha)
    val body = Path().apply {
        moveTo(root.x - stemWidth(0f) * 0.5f, root.y)
        for (step in 1..steps) {
            val p = step / steps.toFloat()
            val at = stemPoint(p)
            lineTo(at.x - stemWidth(p) * 0.5f, at.y)
        }
        for (step in steps downTo 0) {
            val p = step / steps.toFloat()
            val at = stemPoint(p)
            lineTo(at.x + stemWidth(p) * 0.5f, at.y)
        }
        close()
    }
    drawPath(body, shade)
    val light = Path().apply {
        for (step in 0..steps) {
            val p = step / steps.toFloat()
            val at = stemPoint(p) - Offset(stemWidth(p) * 0.16f, 0f)
            if (step == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
        }
    }
    drawPath(light, lerp(stem, Color.White, 0.24f).copy(alpha = alpha * 0.8f), style = Stroke(thick * 0.3f, cap = StrokeCap.Round))

    val bract = lerp(stem, Color(0xFFC9A878), 0.55f).copy(alpha = alpha)
    listOf(-1f, 1f).forEach { side ->
        val tip = head + Offset(side * reach * 0.3f, reach * (0.2f + 0.12f * rnd(id + side.toInt(), 96)))
        val leaf = Path().apply {
            moveTo(head.x - thick * 0.4f, head.y + thick * 0.8f)
            quadraticTo(head.x + side * reach * 0.2f, head.y - reach * 0.03f, tip.x, tip.y)
            quadraticTo(head.x + side * reach * 0.08f, head.y + reach * 0.14f, head.x + thick * 0.4f, head.y + thick * 1.6f)
            close()
        }
        drawPath(leaf, bract)
    }

    val stalkColor = lerp(stem, deep, 0.45f).copy(alpha = alpha)
    val count = if (inBud) 5 else if (reach < 10f * dp) 4 else 5 + id.mod(3)
    val order = (0 until count).sortedBy { abs(it - (count - 1) / 2f) }
    order.forEach { index ->
        val spread = if (count > 1) index / (count - 1f) * 2f - 1f else 0f
        val angle = (spread * if (inBud) 42f else 102f) * PI.toFloat() / 180f +
            (rnd(id + index, 97) - 0.5f) * 0.2f
        val out = Offset(sin(angle), -cos(angle))
        val long = reach * (if (inBud) 0.3f else 0.4f + 0.1f * rnd(id + index, 98))
        val center = head + out * long + Offset(0f, -reach * 0.05f)
        val control = head + out * long * 0.55f + Offset(0f, reach * 0.05f)
        val pedicel = Path().apply {
            moveTo(head.x, head.y)
            quadraticTo(control.x, control.y, center.x, center.y)
        }
        drawPath(pedicel, stalkColor, style = Stroke(max(0.6f * dp, thick * 0.22f), cap = StrokeCap.Round))
        if (inBud) {
            val tip = center + out * reach * 0.42f
            val across = Offset(-out.y, out.x) * reach * 0.09f
            val middle = center + out * reach * 0.16f
            val bud = Path().apply {
                moveTo(center.x, center.y)
                quadraticTo((middle + across).x, (middle + across).y, tip.x, tip.y)
                quadraticTo((middle - across).x, (middle - across).y, center.x, center.y)
                close()
            }
            drawPath(
                bud,
                brush = Brush.linearGradient(
                    listOf(deep.copy(alpha = alpha), red.copy(alpha = alpha)),
                    start = center,
                    end = tip,
                ),
            )
        } else {
            lilyFloret(center, reach * 0.66f, angle, alpha, id * 7 + index, red, deep, pollen)
        }
    }
}
