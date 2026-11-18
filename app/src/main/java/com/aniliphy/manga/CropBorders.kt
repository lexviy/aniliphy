package com.aniliphy.manga

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs

/**
 * Finds the plain white or black edges around a scanned page. Works on a pixel array (ARGB), so it can be tested
 * without Android. Never cuts more than [MAX_SHARE] of a side, so a mostly blank page stays readable.
 */
object CropBorders {
    const val MAX_SHARE = 0.25f
    private const val TOLERANCE = 28
    /** Specks (dust, page numbers' antialiasing) a border line may have. */
    private const val NOISE = 0.01f

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    /** The box to keep. [vertical] false keeps the full height (webtoon strips continue from page to page). */
    fun bounds(pixels: IntArray, width: Int, height: Int, vertical: Boolean = true): Box {
        if (width < 8 || height < 8) return Box(0, 0, width, height)
        val corner = luma(pixels[0])
        val paper = when {
            corner > 255 - TOLERANCE * 2 -> 255
            corner < TOLERANCE * 2 -> 0
            else -> return Box(0, 0, width, height)
        }
        fun plain(x: Int, y: Int) = abs(luma(pixels[y * width + x]) - paper) <= TOLERANCE
        val stepX = maxOf(1, width / 200)
        val stepY = maxOf(1, height / 200)
        fun rowPlain(y: Int): Boolean {
            var off = 0; var n = 0
            var x = 0
            while (x < width) { if (!plain(x, y)) off++; n++; x += stepX }
            return off <= n * NOISE
        }
        fun columnPlain(x: Int, top: Int, bottom: Int): Boolean {
            var off = 0; var n = 0
            var y = top
            while (y < bottom) { if (!plain(x, y)) off++; n++; y += stepY }
            return off <= maxOf(1f, n * NOISE)
        }
        var top = 0
        var bottom = height
        if (vertical) {
            val most = (height * MAX_SHARE).toInt()
            while (top < most && rowPlain(top)) top++
            while (height - bottom < most && rowPlain(bottom - 1)) bottom--
        }
        val most = (width * MAX_SHARE).toInt()
        var left = 0
        var right = width
        while (left < most && columnPlain(left, top, bottom)) left++
        while (width - right < most && columnPlain(right - 1, top, bottom)) right--
        // A little margin back, so lines at the edge aren't cut.
        val pad = maxOf(2, width / 200)
        return Box(maxOf(0, left - pad), maxOf(0, top - pad), minOf(width, right + pad), minOf(height, bottom + pad))
    }

    /** For Coil: cuts the page's plain edges. */
    class Transform(private val vertical: Boolean) : Transformation() {
        override val cacheKey: String = "crop-borders-$vertical"

        override suspend fun transform(input: Bitmap, size: Size): Bitmap {
            val w = input.width
            val h = input.height
            val pixels = IntArray(w * h)
            input.getPixels(pixels, 0, w, 0, 0, w, h)
            val box = bounds(pixels, w, h, vertical)
            if (box.left == 0 && box.top == 0 && box.right == w && box.bottom == h) return input
            return Bitmap.createBitmap(input, box.left, box.top, box.width, box.height)
        }
    }
}
