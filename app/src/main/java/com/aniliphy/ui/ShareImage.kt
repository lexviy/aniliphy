package com.aniliphy.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.aniliphy.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a share picture shows: a heading, a few lines, maybe a big number and a cover. */
internal data class ShareCard(
    val kicker: String,
    val title: String,
    val lines: List<String> = emptyList(),
    val big: String? = null,
    val bigLabel: String? = null,
    val cover: String? = null,
    /** A link written small at the bottom, so the picture leads somewhere. */
    val link: String? = null,
)

/** Draws [ShareCard]s on the phone in the active Look's colours and hands them to Android's share sheet. */
internal object ShareImage {
    private const val W = 1080
    private const val H = 1350

    private fun font(context: Context): Typeface {
        val res = when (Look.style) {
            UiStyle.Tankobon -> R.font.dela_gothic_one
            UiStyle.Modern -> R.font.outfit
            UiStyle.Genga -> R.font.ibm_plex_condensed_bold
            UiStyle.Bunko -> R.font.shippori_mincho_extrabold
            UiStyle.Shinya -> R.font.mplus1p_black
            UiStyle.Jrpg -> R.font.mplus_rounded_extrabold
            UiStyle.Eki -> R.font.barlow_semi_condensed_bold
            UiStyle.Konbini -> R.font.zen_maru_black
            UiStyle.Stamp -> R.font.yuji_syuku
            else -> null
        }
        return res?.let { runCatching { ResourcesCompat.getFont(context, it) }.getOrNull() } ?: Typeface.DEFAULT_BOLD
    }

    private fun corner(): Float = when (Look.style) {
        UiStyle.Tankobon, UiStyle.Genga, UiStyle.Eki, UiStyle.Stamp -> 0f
        UiStyle.Jrpg, UiStyle.Bunko, UiStyle.Konbini -> 14f
        else -> 36f
    }

    suspend fun render(context: Context, theme: AppTheme, card: ShareCard): Bitmap {
        val cover = card.cover?.let { url ->
            runCatching {
                val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                context.imageLoader.execute(request).image?.toBitmap()
            }.getOrNull()
        }
        return withContext(Dispatchers.Default) { draw(context, theme, card, cover) }
    }

    private fun draw(context: Context, theme: AppTheme, card: ShareCard, cover: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val face = font(context)
        val bg = theme.background.toArgb()
        val fg = theme.foreground.toArgb()
        val primary = theme.primary.toArgb()
        val accent = theme.accent.toArgb()
        val surface = theme.surface.toArgb()
        c.drawColor(bg)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // A wash of the look's colour across the top, and a band at the bottom.
        fill.color = primary
        fill.alpha = 26
        c.drawCircle(W * 0.9f, H * 0.05f, W * 0.55f, fill)
        fill.color = accent
        fill.alpha = 18
        c.drawCircle(W * 0.05f, H * 0.95f, W * 0.45f, fill)
        val r = corner()
        val pad = 80f
        var y = pad

        fun text(size: Float, color: Int, bold: Boolean = true) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) face else Typeface.DEFAULT
        }

        fun block(value: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
            StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()

        val kicker = text(34f, primary)
        kicker.letterSpacing = 0.12f
        c.drawText(card.kicker.uppercase(), pad, y + 34f, kicker)
        y += 80f

        if (cover != null) {
            val cw = if (card.big != null) 400f else 460f
            val ch = cw * 1.42f
            val left = (W - cw) / 2f
            val rect = RectF(left, y, left + cw, y + ch)
            fill.color = surface
            fill.alpha = 255
            c.drawRoundRect(RectF(rect.left + 14, rect.top + 18, rect.right + 14, rect.bottom + 18), r, r, Paint(fill).apply { color = 0x55000000 })
            c.save()
            val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
            c.clipPath(clip)
            val scale = maxOf(cw / cover.width, ch / cover.height)
            val dw = cover.width * scale
            val dh = cover.height * scale
            c.drawBitmap(cover, null, RectF(left + (cw - dw) / 2, y + (ch - dh) / 2, left + (cw + dw) / 2, y + (ch + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
            y += ch + 50f
        }

        val title = block(card.title, text(if (cover != null) 64f else 92f, fg), (W - 2 * pad).toInt(), if (cover != null) 2 else 4)
        c.save(); c.translate(pad, y); title.draw(c); c.restore()
        y += title.height + 28f

        card.big?.let { big ->
            val bigPaint = text(170f, primary)
            c.drawText(big, pad, y + 160f, bigPaint)
            card.bigLabel?.let { label ->
                val w = bigPaint.measureText(big)
                c.drawText(label, pad + w + 24f, y + 160f, text(40f, fg, bold = false).apply { alpha = 200 })
            }
            y += 200f
        }

        val linePaint = text(40f, fg, bold = false).apply { alpha = 220 }
        card.lines.forEach { line ->
            if (y > H - 220f) return@forEach
            val l = block(line, linePaint, (W - 2 * pad).toInt(), 2)
            c.save(); c.translate(pad, y); l.draw(c); c.restore()
            y += l.height + 14f
        }

        // Footer: a rule and the app's name, with the link if there is one.
        fill.color = primary
        fill.alpha = 255
        c.drawRect(pad, H - 150f, pad + 120f, H - 142f, fill)
        c.drawText("aniliphy", pad, H - 80f, text(44f, fg))
        card.link?.let { link ->
            val p = text(28f, fg, bold = false).apply { alpha = 170 }
            c.drawText(link.removePrefix("https://"), W - pad - p.measureText(link.removePrefix("https://")), H - 84f, p)
        }
        return bmp
    }

    /** Renders [card] and opens the share sheet with the picture (and [text] alongside, for apps that take both). */
    suspend fun share(context: Context, theme: AppTheme, card: ShareCard, text: String? = null) {
        val bmp = render(context, theme, card)
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, "aniliphy-${System.currentTimeMillis()}.png").also { out ->
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        text?.let { send.putExtra(Intent.EXTRA_TEXT, it) }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        chooser.clipData = android.content.ClipData.newRawUri(null, uri)
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }
}
