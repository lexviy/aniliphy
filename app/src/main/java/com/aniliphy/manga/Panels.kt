package com.aniliphy.manga

import kotlin.math.max
import kotlin.math.min

/** A panel as fractions of the page (0..1), so it holds at any size the page is drawn. */
data class Panel(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Finds the panels of a manga page from its gutters: the plain white (or black) bands between panels. The page is cut
 * along full-width bands into rows, each row along full-height bands into columns, and so on (an "XY cut"), so the
 * order falls out of the cutting: top to bottom, and right to left or left to right by the series' direction.
 *
 * Pages it can't cut (one picture, slanted gutters, art across the gutters) come back empty and show whole.
 * Works on ARGB pixels so it runs in tests without Android.
 */
object Panels {
    /** A band is still a gutter with this share of ink in it (speech bubble tails, specks). */
    private const val INK_ALLOWED = 0.015f
    /** Bits thinner than this share of the page (between gutters) are noise, not panels. */
    private const val MIN_SIDE = 0.04f
    /** Panels smaller than this share of the page are dropped (page numbers, notes in the margin). */
    private const val MIN_AREA = 0.012f
    private const val MAX_PANELS = 16
    private const val MAX_DEPTH = 6

    private fun luma(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private class Page(val gray: IntArray, val width: Int, val height: Int, val white: Boolean) {
        fun gutter(x: Int, y: Int): Boolean {
            val l = gray[y * width + x]
            return if (white) l >= 215 else l <= 45
        }
    }

    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The panels in reading order; empty when the page doesn't cut into at least two. */
    fun find(pixels: IntArray, width: Int, height: Int, rightToLeft: Boolean): List<Panel> {
        if (width < 32 || height < 32 || pixels.size < width * height) return emptyList()
        val gray = IntArray(width * height) { luma(pixels[it]) }
        val white = paperIsWhite(gray, width, height) ?: return emptyList()
        val page = Page(gray, width, height, white)
        val found = ArrayList<Region>()
        cut(page, Region(0, 0, width, height), rows = true, depth = 0, rtl = rightToLeft, out = found)
        val area = width.toFloat() * height
        val kept = found.filter { it.width * it.height >= area * MIN_AREA }
        if (kept.size < 2 || kept.size > MAX_PANELS) return emptyList()
        return kept.map { Panel(it.left / width.toFloat(), it.top / height.toFloat(), it.right / width.toFloat(), it.bottom / height.toFloat()) }
    }

    /** White or black paper, read from the page's edge; null when the edge is neither (art to the edge). */
    private fun paperIsWhite(gray: IntArray, width: Int, height: Int): Boolean? {
        var light = 0
        var dark = 0
        var n = 0
        fun look(x: Int, y: Int) {
            val l = gray[y * width + x]
            if (l >= 215) light++ else if (l <= 45) dark++
            n++
        }
        val edge = max(1, min(width, height) / 100)
        for (x in 0 until width step 2) for (d in 0 until edge) { look(x, d); look(x, height - 1 - d) }
        for (y in 0 until height step 2) for (d in 0 until edge) { look(d, y); look(width - 1 - d, y) }
        return when {
            light >= n * 0.6f -> true
            dark >= n * 0.6f -> false
            else -> null
        }
    }

    /** Spans of [from, to) that aren't gutter, given whether each line is a gutter. */
    private fun spans(from: Int, to: Int, minSide: Int, plain: (Int) -> Boolean): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in from until to) {
            val isPlain = plain(i)
            if (!isPlain && start < 0) start = i
            if (isPlain && start >= 0) {
                if (i - start >= minSide) out += start until i
                start = -1
            }
        }
        if (start >= 0 && to - start >= minSide) out += start until to
        return out
    }

    private fun rowPlain(page: Page, r: Region, y: Int): Boolean {
        var ink = 0
        val allowed = (r.width * INK_ALLOWED).toInt()
        for (x in r.left until r.right) if (!page.gutter(x, y) && ++ink > allowed) return false
        return true
    }

    private fun columnPlain(page: Page, r: Region, x: Int): Boolean {
        var ink = 0
        val allowed = (r.height * INK_ALLOWED).toInt()
        for (y in r.top until r.bottom) if (!page.gutter(x, y) && ++ink > allowed) return false
        return true
    }

    private fun cut(page: Page, r: Region, rows: Boolean, depth: Int, rtl: Boolean, out: MutableList<Region>) {
        val minRow = max(2, (page.height * MIN_SIDE).toInt())
        val minColumn = max(2, (page.width * MIN_SIDE).toInt())
        val byRows = spans(r.top, r.bottom, minRow) { rowPlain(page, r, it) }.map { Region(r.left, it.first, r.right, it.last + 1) }
        val byColumns = spans(r.left, r.right, minColumn) { columnPlain(page, r, it) }.map { Region(it.first, r.top, it.last + 1, r.bottom) }
            .let { if (rtl) it.reversed() else it }
        val (first, second) = if (rows) byRows to byColumns else byColumns to byRows
        val firstIsRows = rows
        when {
            depth >= MAX_DEPTH -> out += trim(page, r)
            first.size >= 2 -> first.forEach { cut(page, it, !firstIsRows, depth + 1, rtl, out) }
            second.size >= 2 -> second.forEach { cut(page, it, firstIsRows, depth + 1, rtl, out) }
            // One span either way: its plain edges come off, and that's a panel.
            first.size == 1 && second.size == 1 -> {
                val inner = Region(byColumns[0].left, byRows[0].top, byColumns[0].right, byRows[0].bottom)
                if (inner != r && depth < MAX_DEPTH) cut(page, inner, rows, depth + 1, rtl, out) else out += inner
            }
            first.isEmpty() && second.isEmpty() -> Unit
            else -> out += trim(page, r)
        }
    }

    private fun trim(page: Page, r: Region): Region {
        var top = r.top
        var bottom = r.bottom
        var left = r.left
        var right = r.right
        while (top < bottom - 1 && rowPlain(page, r, top)) top++
        while (bottom - 1 > top && rowPlain(page, r, bottom - 1)) bottom--
        while (left < right - 1 && columnPlain(page, r, left)) left++
        while (right - 1 > left && columnPlain(page, r, right - 1)) right--
        return Region(left, top, right, bottom)
    }

    /**
     * How to draw the page so [panel] fills the view: the scale and the shift (applied around the view's centre, as a
     * graphics layer does) for a page of [imageWidth]×[imageHeight] fitted whole into [viewWidth]×[viewHeight].
     */
    fun focus(panel: Panel, imageWidth: Int, imageHeight: Int, viewWidth: Float, viewHeight: Float, margin: Float = 0.04f, maxScale: Float = 4f): Triple<Float, Float, Float> {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return Triple(1f, 0f, 0f)
        val aspect = imageWidth.toFloat() / imageHeight
        val shownWidth = min(viewWidth, viewHeight * aspect)
        val shownHeight = shownWidth / aspect
        val left = (viewWidth - shownWidth) / 2
        val top = (viewHeight - shownHeight) / 2
        val panelWidth = panel.width * shownWidth
        val panelHeight = panel.height * shownHeight
        val scale = min(viewWidth * (1 - margin) / panelWidth, viewHeight * (1 - margin) / panelHeight).coerceIn(1f, maxScale)
        val centreX = left + (panel.left + panel.width / 2) * shownWidth
        val centreY = top + (panel.top + panel.height / 2) * shownHeight
        return Triple(scale, -scale * (centreX - viewWidth / 2), -scale * (centreY - viewHeight / 2))
    }
}
