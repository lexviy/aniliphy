package com.aniliphy.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal class IconPaint(val color: Color, val cutout: Color, val filled: Boolean) {
    val detail: Color get() = if (filled) cutout else color
}

internal typealias TabIcon = DrawScope.(IconPaint) -> Unit

private fun DrawScope.unit() = size.minDimension / 24f

private fun DrawScope.outline(width: Float = 1.8f) = Stroke(
    width = width * unit(),
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
)

private fun DrawScope.gridPath(block: Path.(u: Float) -> Unit) = Path().apply { block(unit()) }

private fun DrawScope.fillIfFilled(shape: Path, paint: IconPaint) {
    if (paint.filled) drawPath(shape, paint.color)
}

private fun DrawScope.pageShape(left: Float, top: Float, right: Float, bottom: Float) =
    gridPath { u ->
        addRoundRect(
            RoundRect(
                left * u,
                top * u,
                right * u,
                bottom * u,
                CornerRadius(1.5f * u),
            ),
        )
    }

private fun DrawScope.gridLine(
    color: Color,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    width: Float = 1.5f,
) {
    val u = unit()
    drawLine(
        color = color,
        start = Offset(x1 * u, y1 * u),
        end = Offset(x2 * u, y2 * u),
        strokeWidth = width * u,
        cap = StrokeCap.Round,
    )
}

internal val HouseIcon: TabIcon = { paint ->
    val body = gridPath { u ->
        moveTo(12f * u, 3f * u)
        lineTo(18.5f * u, 8.8f * u)
        lineTo(18.5f * u, 21f * u)
        lineTo(5.5f * u, 21f * u)
        lineTo(5.5f * u, 8.8f * u)
        close()
    }
    val walls = gridPath { u ->
        moveTo(2.5f * u, 11.5f * u)
        lineTo(12f * u, 3f * u)
        lineTo(21.5f * u, 11.5f * u)
        moveTo(5.5f * u, 9f * u)
        lineTo(5.5f * u, 21f * u)
        lineTo(18.5f * u, 21f * u)
        lineTo(18.5f * u, 9f * u)
    }
    val door = gridPath { u ->
        moveTo(10f * u, 21f * u)
        lineTo(10f * u, 14.5f * u)
        lineTo(14f * u, 14.5f * u)
        lineTo(14f * u, 21f * u)
    }
    fillIfFilled(body, paint)
    if (paint.filled) drawPath(door, paint.cutout) else drawPath(door, paint.color, style = outline())
    drawPath(walls, paint.color, style = outline())
}

internal val PlayIcon: TabIcon = { paint ->
    val triangle = gridPath { u ->
        moveTo(7f * u, 4f * u)
        lineTo(20f * u, 12f * u)
        lineTo(7f * u, 20f * u)
        close()
    }
    fillIfFilled(triangle, paint)
    drawPath(triangle, paint.color, style = outline())
}

internal val MangaPageIcon: TabIcon = { paint ->
    val page = pageShape(left = 4f, top = 2.5f, right = 20f, bottom = 21.5f)
    fillIfFilled(page, paint)
    gridLine(paint.detail, 4f, 10f, 20f, 8f)
    gridLine(paint.detail, 13.5f, 2.5f, 12f, 9f)
    gridLine(paint.detail, 4f, 15.5f, 20f, 15.5f)
    gridLine(paint.detail, 10.5f, 15.5f, 9.5f, 21.5f)
    drawPath(page, paint.color, style = outline())
}

internal val BookIcon: TabIcon = { paint ->
    val cover = pageShape(left = 4.5f, top = 2.5f, right = 19.5f, bottom = 21.5f)
    val scribble = gridPath { u ->
        moveTo(10.5f * u, 9f * u)
        cubicTo(11.2f * u, 5.5f * u, 12.2f * u, 5.5f * u, 12.6f * u, 8.5f * u)
        cubicTo(13f * u, 11.5f * u, 13.8f * u, 11.5f * u, 14.4f * u, 8.5f * u)
        cubicTo(15f * u, 5.5f * u, 15.8f * u, 5.5f * u, 16.2f * u, 8.5f * u)
        cubicTo(16.5f * u, 10.5f * u, 17f * u, 10.5f * u, 17.5f * u, 9f * u)
    }
    fillIfFilled(cover, paint)
    gridLine(paint.detail, 8f, 2.5f, 8f, 21.5f)
    gridLine(paint.detail, 8f, 18f, 19.5f, 18f)
    drawPath(scribble, paint.detail, style = outline(width = 1.4f))
    drawPath(cover, paint.color, style = outline())
}

private fun DrawScope.gridDot(color: Color, x: Float, y: Float, radius: Float = 1.2f) {
    val u = unit()
    drawCircle(color, radius = radius * u, center = Offset(x * u, y * u))
}

internal val PaletteIcon: TabIcon = { paint ->
    val palette = gridPath { u ->
        moveTo(12f * u, 3f * u)
        cubicTo(6.5f * u, 3f * u, 3f * u, 7f * u, 3f * u, 12f * u)
        cubicTo(3f * u, 17f * u, 7f * u, 21f * u, 12f * u, 21f * u)
        cubicTo(13.5f * u, 21f * u, 14f * u, 20f * u, 14f * u, 19f * u)
        cubicTo(14f * u, 17.5f * u, 13f * u, 17f * u, 13f * u, 16f * u)
        cubicTo(13f * u, 15f * u, 14f * u, 14.5f * u, 15.5f * u, 14.5f * u)
        lineTo(17.5f * u, 14.5f * u)
        cubicTo(19.5f * u, 14.5f * u, 21f * u, 13f * u, 21f * u, 11f * u)
        cubicTo(21f * u, 6.5f * u, 17f * u, 3f * u, 12f * u, 3f * u)
        close()
    }
    fillIfFilled(palette, paint)
    drawPath(palette, paint.color, style = outline())
    gridDot(paint.detail, 7.8f, 10.5f)
    gridDot(paint.detail, 11.5f, 7.2f)
    gridDot(paint.detail, 16.2f, 9f)
    gridDot(paint.detail, 8f, 15f)
}

internal val StorageIcon: TabIcon = { paint ->
    listOf(3.5f, 13f).forEach { top ->
        val drive = pageShape(left = 3f, top = top, right = 21f, bottom = top + 7.5f)
        fillIfFilled(drive, paint)
        drawPath(drive, paint.color, style = outline())
        gridDot(paint.detail, 7f, top + 3.75f)
        gridLine(paint.detail, 11f, top + 3.75f, 17.5f, top + 3.75f)
    }
}

internal val ExtensionIcon: TabIcon = { paint ->
    val piece = gridPath { u ->
        moveTo(4f * u, 7f * u)
        lineTo(9f * u, 7f * u)
        cubicTo(9f * u, 2.6f * u, 15f * u, 2.6f * u, 15f * u, 7f * u)
        lineTo(19f * u, 7f * u)
        lineTo(19f * u, 11f * u)
        cubicTo(23f * u, 11f * u, 23f * u, 17f * u, 19f * u, 17f * u)
        lineTo(19f * u, 21f * u)
        lineTo(4f * u, 21f * u)
        close()
    }
    fillIfFilled(piece, paint)
    drawPath(piece, paint.color, style = outline())
}

internal val InfoIcon: TabIcon = { paint ->
    val radius = 9f * unit()
    if (paint.filled) drawCircle(paint.color, radius = radius)
    drawCircle(paint.color, radius = radius, style = outline())
    gridDot(paint.detail, 12f, 7.8f)
    gridLine(paint.detail, 12f, 11.5f, 12f, 16.5f, width = 1.8f)
}

internal val GuideIcon: TabIcon = { paint ->
    val radius = 9f * unit()
    if (paint.filled) drawCircle(paint.color, radius = radius)
    drawCircle(paint.color, radius = radius, style = outline())
    val hook = gridPath { u ->
        moveTo(9.4f * u, 9.4f * u)
        cubicTo(9.4f * u, 7.9f * u, 10.6f * u, 6.9f * u, 12f * u, 6.9f * u)
        cubicTo(13.5f * u, 6.9f * u, 14.6f * u, 7.9f * u, 14.6f * u, 9.2f * u)
        cubicTo(14.6f * u, 10.5f * u, 13.4f * u, 11f * u, 12.6f * u, 11.6f * u)
        cubicTo(12.1f * u, 12f * u, 12f * u, 12.5f * u, 12f * u, 13.4f * u)
    }
    drawPath(hook, paint.detail, style = outline())
    gridDot(paint.detail, 12f, 16.8f)
}

internal val SyncIcon: TabIcon = { paint ->
    val u = unit()
    val box = androidx.compose.ui.geometry.Size(16f * u, 16f * u)
    val corner = Offset(4f * u, 4f * u)
    drawArc(paint.color, startAngle = 200f, sweepAngle = 130f, useCenter = false, topLeft = corner, size = box, style = outline())
    drawArc(paint.color, startAngle = 20f, sweepAngle = 130f, useCenter = false, topLeft = corner, size = box, style = outline())
    val heads = gridPath { g ->
        moveTo(17.3f * g, 3.2f * g)
        lineTo(18.9f * g, 6.6f * g)
        lineTo(15.2f * g, 7.1f * g)
        moveTo(6.7f * g, 20.8f * g)
        lineTo(5.1f * g, 17.4f * g)
        lineTo(8.8f * g, 16.9f * g)
    }
    drawPath(heads, paint.color, style = outline())
}

internal val GearIcon: TabIcon = { paint ->
    val teeth = 8
    val outer = 10f
    val root = 7.4f
    val toothHalf = 9.0
    val baseHalf = 15.0
    val gear = gridPath { u ->
        fun corner(angleDegrees: Double, radius: Float) = Offset(
            (12f + radius * cos(angleDegrees * PI / 180).toFloat()) * u,
            (12f + radius * sin(angleDegrees * PI / 180).toFloat()) * u,
        )
        for (tooth in 0 until teeth) {
            val center = tooth * 360.0 / teeth
            val start = corner(center - baseHalf, root)
            if (tooth == 0) moveTo(start.x, start.y) else lineTo(start.x, start.y)
            listOf(
                corner(center - toothHalf, outer),
                corner(center + toothHalf, outer),
                corner(center + baseHalf, root),
            ).forEach { lineTo(it.x, it.y) }
        }
        close()
    }
    fillIfFilled(gear, paint)
    drawPath(gear, paint.color, style = outline(width = 1.6f))
    val hole = 3f * unit()
    if (paint.filled) {
        drawCircle(paint.cutout, radius = hole)
    } else {
        drawCircle(paint.color, radius = hole, style = outline(width = 1.6f))
    }
}

internal val CharacterIcon: TabIcon = { paint ->
    val u = unit()
    if (paint.filled) drawCircle(paint.color, 9f * u, Offset(12f * u, 12f * u))
    drawCircle(paint.color, 9f * u, Offset(12f * u, 12f * u), style = outline())
    drawCircle(paint.detail, 1.2f * u, Offset(8.8f * u, 10f * u))
    drawCircle(paint.detail, 1.2f * u, Offset(15.2f * u, 10f * u))
    val smile = gridPath { g ->
        moveTo(8f * g, 14.5f * g)
        quadraticTo(12f * g, 18.5f * g, 16f * g, 14.5f * g)
    }
    drawPath(smile, paint.detail, style = outline(1.5f))
}

internal val StaffIcon: TabIcon = { paint ->
    val body = pageShape(3.5f, 10f, 20.5f, 20f)
    fillIfFilled(body, paint)
    drawPath(body, paint.color, style = outline())
    val top = gridPath { g ->
        moveTo(3.5f * g, 10f * g)
        lineTo(19f * g, 5f * g)
    }
    drawPath(top, paint.color, style = outline(2.2f))
    gridLine(paint.detail, 7f, 13f, 7f, 17f)
    gridLine(paint.detail, 12f, 13f, 12f, 17f)
    gridLine(paint.detail, 17f, 13f, 17f, 17f)
}

internal val StudioIcon: TabIcon = { paint ->
    val body = pageShape(5.5f, 3.5f, 18.5f, 20.5f)
    fillIfFilled(body, paint)
    drawPath(body, paint.color, style = outline())
    listOf(7.5f, 11.5f, 15.5f).forEach { y ->
        gridLine(paint.detail, 9f, y, 10.5f, y)
        gridLine(paint.detail, 13.5f, y, 15f, y)
    }
}

internal val ProfileIcon: TabIcon = { paint ->
    val u = unit()
    if (paint.filled) drawCircle(paint.color, 4f * u, Offset(12f * u, 8f * u))
    drawCircle(paint.color, 4f * u, Offset(12f * u, 8f * u), style = outline())
    val shoulders = gridPath { g ->
        moveTo(4.5f * g, 20.5f * g)
        cubicTo(4.5f * g, 15f * g, 19.5f * g, 15f * g, 19.5f * g, 20.5f * g)
        close()
    }
    fillIfFilled(shoulders, paint)
    drawPath(shoulders, paint.color, style = outline())
}
