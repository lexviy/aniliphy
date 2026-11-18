package com.aniliphy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class PanelsTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A page with framed panels (x, y, w, h in pixels) holding some random ink. */
    private fun page(width: Int, height: Int, panels: List<IntArray>, paper: Int = white, frame: Int = black, seed: Int = 1): IntArray {
        val px = IntArray(width * height) { paper }
        val random = Random(seed)
        for ((x, y, w, h) in panels.map { it.toList() }) {
            for (yy in y until y + h) for (xx in x until x + w) {
                val edge = xx < x + 3 || xx >= x + w - 3 || yy < y + 3 || yy >= y + h - 3
                px[yy * width + xx] = when {
                    edge -> frame
                    random.nextInt(10) == 0 -> 0xFF333333.toInt()
                    else -> 0xFFDDDDDD.toInt()
                }
            }
        }
        return px
    }

    private fun near(expected: Float, actual: Float, slack: Float = 0.02f) = assertTrue("$expected vs $actual", abs(expected - actual) <= slack)

    // Two on top, one wide, three below, like a usual page.
    private val layout = listOf(
        intArrayOf(310, 20, 270, 260), intArrayOf(20, 20, 270, 260),
        intArrayOf(20, 300, 560, 220),
        intArrayOf(410, 540, 170, 320), intArrayOf(215, 540, 175, 320), intArrayOf(20, 540, 175, 320),
    )

    @Test
    fun rightToLeftOrder() {
        val found = Panels.find(page(600, 880, layout), 600, 880, rightToLeft = true)
        assertEquals(6, found.size)
        // Top right first, then top left, the wide one, then the bottom row from the right.
        near(310 / 600f, found[0].left)
        near(20 / 600f, found[1].left)
        near(300 / 880f, found[2].top)
        assertTrue(found[2].width > 0.9f)
        near(410 / 600f, found[3].left)
        near(215 / 600f, found[4].left)
        near(20 / 600f, found[5].left)
    }

    @Test
    fun leftToRightOrder() {
        val found = Panels.find(page(600, 880, layout), 600, 880, rightToLeft = false)
        assertEquals(6, found.size)
        near(20 / 600f, found[0].left)
        near(310 / 600f, found[1].left)
        near(20 / 600f, found[3].left)
        near(410 / 600f, found[5].left)
    }

    @Test
    fun columnsInsideARow() {
        // A tall panel on the right beside two stacked on the left: rows inside a column.
        val panels = listOf(intArrayOf(320, 20, 260, 840), intArrayOf(20, 20, 280, 410), intArrayOf(20, 450, 280, 410))
        val found = Panels.find(page(600, 880, panels), 600, 880, rightToLeft = true)
        assertEquals(3, found.size)
        near(320 / 600f, found[0].left)
        near(20 / 880f, found[1].top)
        near(450 / 880f, found[2].top)
    }

    @Test
    fun blackGutters() {
        val found = Panels.find(page(600, 880, layout, paper = black, frame = white), 600, 880, rightToLeft = true)
        assertEquals(6, found.size)
    }

    @Test
    fun pagesThatDontCutShowWhole() {
        val random = Random(3)
        val art = IntArray(600 * 880) { 0xFF000000.toInt() or random.nextInt(0xFFFFFF) }
        assertTrue(Panels.find(art, 600, 880, rightToLeft = true).isEmpty())
        // One framed picture is one panel: nothing to step through.
        assertTrue(Panels.find(page(600, 880, listOf(intArrayOf(20, 20, 560, 840))), 600, 880, true).isEmpty())
        assertTrue(Panels.find(IntArray(10), 2, 5, true).isEmpty())
    }

    @Test
    fun aBubbleAcrossTheGutterKeepsThoseTwoTogether() {
        val px = page(600, 880, layout)
        // Ink bridging the two top panels across their gutter.
        for (y in 100 until 180) for (x in 280 until 320) px[y * 600 + x] = black
        val found = Panels.find(px, 600, 880, rightToLeft = true)
        assertEquals(5, found.size)
        assertTrue(found[0].width > 0.9f)
    }

    @Test
    fun focusCentresThePanel() {
        // A 600×900 page fitted in a 1080×1920 view: 1080 wide, 1620 tall, 150 above.
        val panel = Panel(0.5f, 0f, 1f, 0.5f)
        val (scale, dx, dy) = Panels.focus(panel, 600, 900, 1080f, 1920f, margin = 0f)
        // Half the width fills the view's width.
        near(2f, scale, 0.001f)
        // Its centre (810, 555) lands on the view's centre (540, 960).
        near(540f, 540f + scale * (810f - 540f) + dx, 0.5f)
        near(960f, 960f + scale * (555f - 960f) + dy, 0.5f)
        // Never smaller than the whole page.
        assertEquals(1f, Panels.focus(Panel(0f, 0f, 1f, 1f), 600, 900, 1080f, 1920f).first)
    }
}
