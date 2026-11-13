package com.aniliphy.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.aniliphy.R
import com.aniliphy.ui.AppTheme
import com.aniliphy.ui.AppearanceState
import com.aniliphy.ui.UiStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The widget dressed as the app is: the look chosen under Appearance (Tankōbon, Genga, Eki…) in the theme's colors.
 * A home-screen widget can't be handed a font or a shape, so its plate, the column names and each card's lettering
 * are drawn here as pictures, the way the app draws the same things.
 */
internal class WidgetLook(private val context: Context) {
    private val appearance = AppearanceState(context)
    private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val theme: AppTheme = appearance.effectiveTheme(night)
    val look: UiStyle = appearance.uiStyle

    private val metrics = context.resources.displayMetrics
    private val dp = metrics.density
    private val sp = dp * context.resources.configuration.fontScale.coerceIn(0.85f, 1.3f)

    private val ink = lerp(theme.foreground, if (theme.dark) Color.White else Color.Black, 0.45f)
    private val paper = if (theme.dark) lerp(theme.surface, theme.foreground, 0.05f) else lerp(theme.background, Color.White, 0.85f)
    private val paperInk = if (theme.dark) lerp(theme.foreground, Color.White, 0.4f) else Color(0xFF1D1F24)
    private val hairline = theme.foreground.copy(alpha = if (theme.dark) 0.32f else 0.35f)
    private val board = Color(0xFF15171C)

    /** The text shown when a column has nothing yet. */
    val muted: Int
        get() = when (look) {
            UiStyle.Eki -> Color(0xFFC3C7D0)
            UiStyle.Konbini -> paperInk.copy(alpha = 0.7f)
            else -> theme.foreground.copy(alpha = 0.7f)
        }.toArgb()

    /** A font and, for the variable ones, the weight to draw it at. */
    private class Face(val typeface: Typeface, val weight: Int?)

    private fun face(font: Int, weight: Int? = null) = Face(ResourcesCompat.getFont(context, font) ?: Typeface.DEFAULT_BOLD, weight)

    private fun TextPaint.use(face: Face) {
        typeface = face.typeface
        face.weight?.let { fontVariationSettings = "'wght' $it" }
    }

    private class Faces(val header: Face, val title: Face, val detail: Face)

    private val faces: Faces by lazy {
        when (look) {
            UiStyle.Tankobon -> Faces(face(R.font.dela_gothic_one), face(R.font.dela_gothic_one), face(R.font.jetbrains_mono, 500))
            UiStyle.Modern -> Faces(face(R.font.outfit, 700), face(R.font.inter, 600), face(R.font.inter, 500))
            UiStyle.Genga -> Faces(face(R.font.ibm_plex_condensed_bold), face(R.font.ibm_plex_condensed_bold), face(R.font.jetbrains_mono, 500))
            UiStyle.Bunko -> Faces(face(R.font.shippori_mincho_extrabold), face(R.font.shippori_mincho_extrabold), face(R.font.shippori_mincho_medium))
            UiStyle.Shinya -> Faces(face(R.font.mplus1p_black), face(R.font.mplus1p_bold), face(R.font.share_tech_mono))
            UiStyle.Jrpg -> Faces(face(R.font.dotgothic16), face(R.font.mplus_rounded_extrabold), face(R.font.dotgothic16))
            UiStyle.Eki -> Faces(face(R.font.barlow_semi_condensed_bold), face(R.font.barlow_semi_condensed_bold), face(R.font.jetbrains_mono, 500))
            UiStyle.Konbini -> Faces(face(R.font.space_mono_bold), face(R.font.space_mono_bold), face(R.font.space_mono_regular))
            UiStyle.Stamp -> Faces(face(R.font.zen_maru_black), face(R.font.zen_maru_black), face(R.font.zen_maru_medium))
            UiStyle.Glass -> Faces(face(R.font.manrope, 800), face(R.font.manrope, 700), face(R.font.manrope, 500))
        }
    }

    private fun bitmap(widthDp: Float, heightDp: Float): Bitmap =
        Bitmap.createBitmap(max(1, ceil(widthDp * dp).toInt()), max(1, ceil(heightDp * dp).toInt()), Bitmap.Config.ARGB_8888).also {
            it.density = metrics.densityDpi
        }

    private fun fill(color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }

    private fun line(color: Color, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = widthDp * dp
    }

    /** A rectangle with its bottom-right corner cut off, like a manga panel. */
    private fun cut(rect: RectF, by: Float) = Path().apply {
        moveTo(rect.left, rect.top)
        lineTo(rect.right, rect.top)
        lineTo(rect.right, rect.bottom - by)
        lineTo(rect.right - by, rect.bottom)
        lineTo(rect.left, rect.bottom)
        close()
    }

    /** Whether the widget gets the look's small motion (Appearance › Look animations). */
    val moving: Boolean = appearance.lookUiMotion

    /**
     * One of the two frames of the look's small motion in the widget's corner, 48 by 8 dp: speed lines, a glowing
     * dot, a boiling pencil ring, a swaying ribbon, a REC light, a twinkle, crossing lamps, a register light or a
     * stamp's ring. The launcher flips between the two.
     */
    fun motif(frame: Int): Bitmap {
        val image = bitmap(48f, 8f)
        val canvas = Canvas(image)
        val h = image.height.toFloat()
        val right = image.width - 6 * dp
        val mid = h / 2
        val on = frame == 0
        val red = Color(0xFFFF3B3B)
        when (look) {
            UiStyle.Tankobon -> {
                val paint = line(theme.primary, 1.5f)
                for (i in 0 until 3) {
                    val shift = if (on) 0f else 6 * dp
                    val y = (1.5f + i * 2.5f) * dp
                    canvas.drawLine(right - (18 + i * 6) * dp - shift, y, right - shift, y, paint)
                }
            }
            UiStyle.Modern, UiStyle.Glass -> {
                if (!on) canvas.drawCircle(right, mid, 3.8f * dp, fill(theme.primary.copy(alpha = 0.3f)))
                canvas.drawCircle(right, mid, 2.4f * dp, fill(theme.primary))
            }
            UiStyle.Genga -> {
                val jitter = if (on) 0f else 0.6f * dp
                canvas.drawOval(RectF(right - 3.4f * dp + jitter, 0.6f * dp, right + 3.4f * dp, h - 0.6f * dp - jitter), line(theme.error, 1.3f))
            }
            UiStyle.Bunko -> {
                val sway = if (on) -1.2f * dp else 1.2f * dp
                val path = Path().apply {
                    moveTo(right - 2.4f * dp + sway, 0f); lineTo(right + 2.4f * dp + sway, 0f)
                    lineTo(right + 2.4f * dp + sway, h); lineTo(right + sway, h - 2 * dp); lineTo(right - 2.4f * dp + sway, h); close()
                }
                canvas.drawPath(path, fill(theme.accent))
            }
            UiStyle.Shinya -> if (on) {
                canvas.drawCircle(right, mid, 3.6f * dp, fill(red.copy(alpha = 0.3f)))
                canvas.drawCircle(right, mid, 2.2f * dp, fill(red))
            } else {
                canvas.drawCircle(right, mid, 2.2f * dp, fill(red.copy(alpha = 0.35f)))
            }
            UiStyle.Jrpg -> {
                val r = if (on) 3.4f * dp else 1.6f * dp
                val paint = line(theme.accent, 1.2f)
                canvas.drawLine(right - r, mid, right + r, mid, paint)
                canvas.drawLine(right, mid - r, right, mid + r, paint)
            }
            UiStyle.Eki -> {
                val lamp = Color(0xFFFFB13B)
                canvas.drawCircle(right - 8 * dp, mid, 2.4f * dp, fill(if (on) lamp else lamp.copy(alpha = 0.2f)))
                canvas.drawCircle(right, mid, 2.4f * dp, fill(if (on) lamp.copy(alpha = 0.2f) else lamp))
            }
            UiStyle.Konbini -> canvas.drawCircle(right, mid, 2.4f * dp, fill(if (on) red else red.copy(alpha = 0.25f)))
            UiStyle.Stamp -> {
                val paint = line(theme.error, 1.3f).apply { if (!on) pathEffect = DashPathEffect(floatArrayOf(2 * dp, 1.5f * dp), 0f) }
                canvas.drawCircle(right, mid, 3.2f * dp, paint)
            }
        }
        return image
    }

    /** The plate everything sits on, [widthDp] by [heightDp]. */
    fun background(widthDp: Float, heightDp: Float): Bitmap {
        val image = bitmap(widthDp, heightDp)
        val canvas = Canvas(image)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val all = RectF(0f, 0f, w, h)
        fun round(radius: Float, paint: Paint, inset: Float = 0f) =
            canvas.drawRoundRect(RectF(inset * dp, inset * dp, w - inset * dp, h - inset * dp), radius * dp, radius * dp, paint)
        when (look) {
            UiStyle.Tankobon -> {
                val panel = cut(all, 22f * dp)
                canvas.drawPath(panel, fill(theme.background))
                // Screentone fading out of the top corner, as on a printed page.
                canvas.save()
                canvas.clipPath(panel)
                val dot = fill(theme.foreground)
                val step = 6f * dp
                var y = step / 2f
                while (y < 46f * dp) {
                    var x = w - 120f * dp
                    while (x < w) {
                        val strength = ((x - (w - 120f * dp)) / (120f * dp)) * (1f - y / (46f * dp))
                        dot.alpha = (255f * 0.22f * strength).toInt()
                        canvas.drawCircle(x, y, 1.1f * dp, dot)
                        x += step
                    }
                    y += step
                }
                canvas.restore()
                canvas.drawPath(panel, line(theme.foreground.copy(alpha = 0.16f), 1f))
            }
            UiStyle.Modern -> round(28f, fill(theme.background))
            UiStyle.Genga -> {
                canvas.drawRect(all, fill(theme.surface))
                canvas.drawRect(RectF(5f * dp, 5f * dp, w - 5f * dp, h - 5f * dp), line(hairline, 1f))
                // The three peg holes at the top of an animator's sheet.
                val hole = fill(theme.background)
                canvas.drawCircle(w / 2f, 5f * dp, 3f * dp, hole)
                canvas.drawRoundRect(RectF(w / 2f - 46f * dp, 3f * dp, w / 2f - 30f * dp, 7f * dp), 2f * dp, 2f * dp, hole)
                canvas.drawRoundRect(RectF(w / 2f + 30f * dp, 3f * dp, w / 2f + 46f * dp, 7f * dp), 2f * dp, 2f * dp, hole)
            }
            UiStyle.Bunko -> {
                round(3f, fill(theme.background))
                // The obi: a band of the theme's color round the foot of the book.
                canvas.save()
                canvas.clipRect(0f, h - 7f * dp, w, h)
                round(3f, fill(theme.primary))
                canvas.restore()
                round(3f, line(theme.foreground.copy(alpha = 0.14f), 1f), 0.5f)
            }
            UiStyle.Shinya -> {
                round(16f, fill(theme.surface))
                canvas.save()
                canvas.clipPath(Path().apply { addRoundRect(all, 16f * dp, 16f * dp, Path.Direction.CW) })
                val scan = fill(Color.Black.copy(alpha = if (theme.dark) 0.16f else 0.05f))
                var y = 0f
                while (y < h) {
                    canvas.drawRect(0f, y, w, y + dp, scan)
                    y += 3f * dp
                }
                canvas.restore()
            }
            UiStyle.Jrpg -> {
                round(10f, fill(theme.foreground))
                round(8f, fill(theme.background), 2.5f)
                round(6f, line(theme.foreground.copy(alpha = 0.35f), 1f), 5f)
            }
            UiStyle.Eki -> {
                round(10f, fill(board))
                canvas.drawRect(10f * dp, h - 5f * dp, w - 10f * dp, h - 3f * dp, fill(theme.primary))
            }
            UiStyle.Konbini -> {
                // A receipt: torn in teeth along the top and bottom.
                val tooth = 7f * dp
                val depth = 4f * dp
                val sheet = Path()
                sheet.moveTo(0f, depth)
                var x = 0f
                while (x < w) {
                    sheet.lineTo(min(w, x + tooth / 2f), 0f)
                    sheet.lineTo(min(w, x + tooth), depth)
                    x += tooth
                }
                sheet.lineTo(w, h - depth)
                x = w
                while (x > 0f) {
                    sheet.lineTo(max(0f, x - tooth / 2f), h)
                    sheet.lineTo(max(0f, x - tooth), h - depth)
                    x -= tooth
                }
                sheet.close()
                canvas.drawPath(sheet, fill(paper))
            }
            UiStyle.Stamp -> {
                round(20f, fill(theme.surface))
                round(15f, line(hairline, 1.5f).apply { pathEffect = DashPathEffect(floatArrayOf(6f * dp, 5f * dp), 0f) }, 5f)
            }
            UiStyle.Glass -> {
                round(30f, fill(theme.surface.copy(alpha = 0.72f)))
                val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, 0f, h * 0.5f, Color.White.copy(alpha = if (theme.dark) 0.14f else 0.5f).toArgb(), 0, Shader.TileMode.CLAMP)
                }
                round(30f, sheen)
                round(30f, line(Color.White.copy(alpha = if (theme.dark) 0.28f else 0.7f), 1f), 0.5f)
            }
        }
        return image
    }

    /** A column's name, [widthDp] wide. */
    fun header(label: String, widthDp: Float): Bitmap {
        val heightDp = 26f
        val image = bitmap(widthDp, heightDp)
        val canvas = Canvas(image)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        val shown = when (look) {
            UiStyle.Tankobon, UiStyle.Genga, UiStyle.Eki, UiStyle.Konbini, UiStyle.Shinya -> label.uppercase()
            UiStyle.Jrpg -> "▶ $label"
            else -> label
        }
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            use(faces.header)
            textSize = (if (look == UiStyle.Tankobon) 11.5f else 12.5f) * sp
            textAlign = Paint.Align.CENTER
        }
        val padding = when (look) {
            UiStyle.Genga, UiStyle.Bunko, UiStyle.Konbini, UiStyle.Jrpg -> 2f
            UiStyle.Tankobon, UiStyle.Shinya, UiStyle.Eki -> 6f
            else -> 9f
        } * dp
        // Long names shrink to stay inside their column.
        val room = w - padding * 2f - 2f * dp
        val wide = text.measureText(shown)
        if (wide > room) text.textSize *= room / wide
        val chip = min(w - 2f * dp, text.measureText(shown) + padding * 2f)
        val box = RectF(w / 2f - chip / 2f, 2f * dp, w / 2f + chip / 2f, h - 2f * dp)
        var baselineShift = 0f
        when (look) {
            UiStyle.Tankobon -> {
                canvas.drawPath(cut(box, 7f * dp), fill(theme.primary))
                text.color = theme.background.toArgb()
            }
            UiStyle.Modern -> {
                canvas.drawRoundRect(box, h, h, fill(theme.primary.copy(alpha = 0.18f)))
                text.color = theme.primary.toArgb()
            }
            UiStyle.Genga -> {
                canvas.drawLine(box.left, box.bottom - dp, box.right, box.bottom - dp, line(theme.error, 1.5f))
                text.color = ink.toArgb()
                baselineShift = -1.5f * dp
            }
            UiStyle.Bunko -> {
                canvas.drawLine(box.left, box.bottom - dp, box.right, box.bottom - dp, line(theme.primary, 1f))
                text.color = ink.toArgb()
                baselineShift = -1.5f * dp
            }
            UiStyle.Shinya -> {
                canvas.drawRoundRect(box, 4f * dp, 4f * dp, fill(theme.error))
                text.color = Color.White.toArgb()
            }
            UiStyle.Jrpg -> text.color = theme.foreground.toArgb()
            UiStyle.Eki -> {
                // A station name plate: white, black letters, the line's color underneath.
                canvas.drawRoundRect(box, 3f * dp, 3f * dp, fill(Color(0xFFF4F5F7)))
                canvas.drawRect(box.left + 3f * dp, box.bottom - 4f * dp, box.right - 3f * dp, box.bottom - 2f * dp, fill(theme.primary))
                text.color = board.toArgb()
                baselineShift = -1.5f * dp
            }
            UiStyle.Konbini -> {
                canvas.drawLine(1f * dp, h - 2f * dp, w - 1f * dp, h - 2f * dp, line(paperInk.copy(alpha = 0.6f), 1f).apply { pathEffect = DashPathEffect(floatArrayOf(3f * dp, 3f * dp), 0f) })
                text.color = paperInk.toArgb()
                baselineShift = -1.5f * dp
            }
            UiStyle.Stamp -> {
                // Pressed on by hand: a little crooked, ink only.
                canvas.rotate(-3f, w / 2f, h / 2f)
                canvas.drawRoundRect(RectF(box.left + dp, box.top + dp, box.right - dp, box.bottom - dp), 6f * dp, 6f * dp, line(theme.error, 1.6f))
                text.color = theme.error.toArgb()
            }
            UiStyle.Glass -> {
                canvas.drawRoundRect(box, h, h, fill(Color.White.copy(alpha = if (theme.dark) 0.14f else 0.55f)))
                text.color = theme.foreground.toArgb()
            }
        }
        val metricsOf = text.fontMetrics
        canvas.drawText(shown, w / 2f, h / 2f - (metricsOf.ascent + metricsOf.descent) / 2f + baselineShift, text)
        return image
    }

    private fun lines(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()

    /** A card's title and the line under it, [widthDp] wide. */
    fun caption(title: String, detail: String, widthDp: Float): Bitmap {
        val width = max(1, (widthDp * dp).toInt())
        val titleColor = when (look) {
            UiStyle.Eki -> Color(0xFFF2F3F5)
            UiStyle.Konbini -> paperInk
            else -> ink
        }
        val detailColor = when (look) {
            UiStyle.Tankobon, UiStyle.Shinya -> theme.primary
            UiStyle.Jrpg -> theme.accent
            UiStyle.Eki -> Color(0xFFFFC24A)
            UiStyle.Konbini -> paperInk.copy(alpha = 0.7f)
            else -> theme.foreground.copy(alpha = 0.7f)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            use(faces.title)
            // Dela Gothic One draws larger than other faces at the same size.
            textSize = (if (look == UiStyle.Tankobon) 10f else 11.5f) * sp
            color = titleColor.toArgb()
        }
        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            use(faces.detail)
            textSize = (if (look == UiStyle.Konbini || look == UiStyle.Tankobon) 9.5f else 10.5f) * sp
            color = detailColor.toArgb()
        }
        val top = lines(title, titlePaint, width)
        val bottom = lines(detail, detailPaint, width)
        val gap = 1.5f * dp
        val image = Bitmap.createBitmap(width, max(1, ceil(top.height + gap + bottom.height + dp).toInt()), Bitmap.Config.ARGB_8888)
        image.density = metrics.densityDpi
        val canvas = Canvas(image)
        top.draw(canvas)
        canvas.translate(0f, top.height + gap)
        bottom.draw(canvas)
        return image
    }
}
