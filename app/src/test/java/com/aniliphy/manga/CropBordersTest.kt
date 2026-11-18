package com.aniliphy.manga

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropBordersTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    /** A [w]×[h] page of [paper] with ink from ([x0],[y0]) to ([x1],[y1]). */
    private fun page(w: Int, h: Int, paper: Int, ink: Int, x0: Int, y0: Int, x1: Int, y1: Int) =
        IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in x0 until x1 && y in y0 until y1) ink else paper }

    @Test
    fun cutsWhiteEdgesWithALittleRoomLeft() {
        val box = CropBorders.bounds(page(400, 600, white, black, 40, 50, 360, 560), 400, 600)
        assertTrue(box.left in 36..40)
        assertTrue(box.top in 46..50)
        assertTrue(box.right in 360..364)
        assertTrue(box.bottom in 560..564)
    }

    @Test
    fun cutsBlackEdgesToo() {
        val box = CropBorders.bounds(page(400, 600, black, white, 30, 0, 370, 600), 400, 600)
        assertTrue(box.left in 26..30)
        assertEquals(0, box.top)
    }

    @Test
    fun neverMoreThanAQuarter() {
        val box = CropBorders.bounds(page(400, 600, white, black, 190, 290, 210, 310), 400, 600)
        assertTrue(box.left <= 100)
        assertTrue(box.right >= 300)
    }

    @Test
    fun webtoonKeepsItsHeight() {
        val box = CropBorders.bounds(page(400, 600, white, black, 40, 50, 360, 560), 400, 600, vertical = false)
        assertEquals(0, box.top)
        assertEquals(600, box.bottom)
        assertTrue(box.left in 36..40)
    }

    @Test
    fun colouredPagesAreLeftAlone() {
        val grey = 0xFF808080.toInt()
        assertEquals(CropBorders.Box(0, 0, 400, 600), CropBorders.bounds(page(400, 600, grey, black, 40, 50, 360, 560), 400, 600))
    }
}
