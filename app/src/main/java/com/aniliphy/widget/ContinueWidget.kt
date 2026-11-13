package com.aniliphy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import com.aniliphy.MainActivity
import com.aniliphy.WidgetOpen
import com.aniliphy.R
import com.aniliphy.ui.AppearanceState
import kotlin.math.max
import kotlin.math.min

/** One card's views: its cover and the picture of its title and detail. */
data class WidgetCard(val card: Int, val cover: Int, val title: Int)

/** The four columns of the widget: the latest title of each kind, and the one before it when the widget is tall. */
enum class WidgetColumn(val first: WidgetCard, val second: WidgetCard, val empty: Int, val header: Int) {
    Anime(
        WidgetCard(R.id.card_anime, R.id.cover_anime, R.id.title_anime),
        WidgetCard(R.id.card2_anime, R.id.cover2_anime, R.id.title2_anime),
        R.id.empty_anime,
        R.id.header_anime,
    ),
    Manga(
        WidgetCard(R.id.card_manga, R.id.cover_manga, R.id.title_manga),
        WidgetCard(R.id.card2_manga, R.id.cover2_manga, R.id.title2_manga),
        R.id.empty_manga,
        R.id.header_manga,
    ),
    Novels(
        WidgetCard(R.id.card_novels, R.id.cover_novels, R.id.title_novels),
        WidgetCard(R.id.card2_novels, R.id.cover2_novels, R.id.title2_novels),
        R.id.empty_novels,
        R.id.header_novels,
    ),
    Recommendations(
        WidgetCard(R.id.card_recommendations, R.id.cover_recommendations, R.id.title_recommendations),
        WidgetCard(R.id.card2_recommendations, R.id.cover2_recommendations, R.id.title2_recommendations),
        R.id.empty_recommendations,
        R.id.header_recommendations,
    ),
    ;

    /** Everything of this kind, newest (or best) first; the widget shows the first, the wheel all of them. */
    fun items(snapshot: WidgetSnapshot): List<WidgetItem> = when (this) {
        Anime -> snapshot.anime
        Manga -> snapshot.manga
        Novels -> snapshot.novels
        Recommendations -> snapshot.recommendations
    }
}

/** The app theme's colors, as plain ARGB, for the widget. */
data class WidgetColors(
    val background: Int,
    val panel: Int,
    val foreground: Int,
    val muted: Int,
    val primary: Int,
    val error: Int,
) {
    companion object {
        fun of(context: Context): WidgetColors {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val theme = AppearanceState(context).effectiveTheme(night)
            return WidgetColors(
                background = theme.background.toArgb(),
                panel = theme.panel.toArgb(),
                foreground = theme.foreground.toArgb(),
                muted = theme.foreground.copy(alpha = 0.7f).toArgb(),
                primary = theme.primary.toArgb(),
                error = theme.error.toArgb(),
            )
        }
    }
}

/** What the app gets when a card is tapped; see [WidgetOpenRequest]. */
object WidgetIntents {
    const val ACTION_OPEN = "com.aniliphy.WIDGET_OPEN"
    const val EXTRA_KIND = "com.aniliphy.WIDGET_KIND"
    const val EXTRA_KEY = "com.aniliphy.WIDGET_KEY"
    const val EXTRA_OF = "com.aniliphy.WIDGET_OF"
    const val EXTRA_COLUMN = "com.aniliphy.WIDGET_COLUMN"

    /**
     * What a tap on the widget, the wheel, a shortcut or the "continue" notification asks the app to open. [where]
     * tells the intents apart, so each keeps its own extras.
     */
    fun open(context: Context, item: WidgetItem, where: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .setData("aniliphy-widget://open/$where".toUri())
            .putExtra(EXTRA_KIND, item.kind.name)
            .putExtra(EXTRA_KEY, item.key)
            .putExtra(EXTRA_OF, item.of?.name)

    /** Reads such a request back; anything incomplete or unknown is ignored. */
    fun parse(action: String?, kind: String?, key: String?): WidgetOpen? {
        if (action != ACTION_OPEN || key.isNullOrBlank()) return null
        val known = WidgetKind.entries.firstOrNull { it.name == kind } ?: return null
        // Everything but light novels is keyed by its AniList id.
        if (known != WidgetKind.Novel && key.toIntOrNull() == null) return null
        return WidgetOpen(known, key)
    }
}

class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids)
        if (!WidgetData.file(context).isFile) WidgetData.refreshSoon(context)
    }

    /** Before Android 12 the widget picks its layout itself, so it redraws when resized. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        draw(context, manager, intArrayOf(id))
    }

    companion object {
        /** From this height (in dp) each column shows two titles. */
        private const val TALL_DP = 230f
        private const val WIDE_DP = 250f
        private const val SHORT_DP = 110f

        /** Redraws every widget on the home screen with the latest data and theme. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val colors = WidgetColors.of(context)
            val look = WidgetLook(context)
            val snapshot = WidgetData.read(context)
            ids.forEach { id ->
                // The widget's size on the home screen, upright: its plate and lettering are drawn to fit it.
                val options = manager.getAppWidgetOptions(id)
                val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: 320f
                val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: TALL_DP
                val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // The launcher picks the biggest layout that fits as the widget is resized.
                    RemoteViews(
                        mapOf(
                            SizeF(WIDE_DP, SHORT_DP) to views(context, snapshot, colors, look, width, min(height, TALL_DP - 1f), tall = false),
                            SizeF(WIDE_DP, TALL_DP) to views(context, snapshot, colors, look, width, max(height, TALL_DP), tall = true),
                        ),
                    )
                } else {
                    views(context, snapshot, colors, look, width, height, tall = height >= TALL_DP)
                }
                manager.updateAppWidget(id, views)
            }
        }

        private fun views(
            context: Context,
            snapshot: WidgetSnapshot,
            colors: WidgetColors,
            look: WidgetLook,
            width: Float,
            height: Float,
            tall: Boolean,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, if (tall) R.layout.widget_continue_tall else R.layout.widget_continue)
            views.setImageViewBitmap(R.id.widget_background, look.background(width, height))
            if (look.moving) {
                views.setViewVisibility(R.id.widget_motif, View.VISIBLE)
                views.setImageViewBitmap(R.id.widget_motif_a, look.motif(0))
                views.setImageViewBitmap(R.id.widget_motif_b, look.motif(1))
            } else {
                views.setViewVisibility(R.id.widget_motif, View.GONE)
            }
            // Four columns inside the plate's padding, each with its own margin.
            val columnWidth = ((width - 12f) / WidgetColumn.entries.size - 4f).coerceAtLeast(40f)
            val names = mapOf(
                WidgetColumn.Anime to R.string.widget_anime,
                WidgetColumn.Manga to R.string.widget_manga,
                WidgetColumn.Novels to R.string.widget_novels,
                WidgetColumn.Recommendations to R.string.widget_recommendations,
            )
            for (column in WidgetColumn.entries) {
                views.setImageViewBitmap(column.header, look.header(context.getString(names.getValue(column)), columnWidth))
                views.setTextColor(column.empty, look.muted)
                views.setOnClickPendingIntent(column.header, wheel(context, column))
                val items = column.items(snapshot)
                views.setViewVisibility(column.empty, if (items.isEmpty()) View.VISIBLE else View.GONE)
                val cards = if (tall) listOf(column.first, column.second) else listOf(column.first)
                cards.forEachIndexed { place, card ->
                    val item = items.getOrNull(place)
                    views.setViewVisibility(card.card, if (item != null) View.VISIBLE else View.INVISIBLE)
                    if (item != null) card(context, views, column, place, card, item, colors, look, columnWidth)
                }
            }
            return views
        }

        private fun card(
            context: Context,
            views: RemoteViews,
            column: WidgetColumn,
            place: Int,
            card: WidgetCard,
            item: WidgetItem,
            colors: WidgetColors,
            look: WidgetLook,
            columnWidth: Float,
        ) {
            val image = WidgetData.image(context, item)
            if (image != null) {
                views.setImageViewUri(card.cover, WidgetCoverProvider.uri(context, image.name))
                views.setInt(card.cover, "setColorFilter", 0)
            } else {
                views.setImageViewResource(card.cover, R.drawable.widget_card_placeholder)
                views.setInt(card.cover, "setColorFilter", colors.panel)
            }
            views.setImageViewBitmap(card.title, look.caption(item.title, item.detail, columnWidth))
            views.setContentDescription(card.card, "${item.title}, ${item.detail}")
            views.setOnClickPendingIntent(card.card, open(context, column, place, item))
        }

        /** Opens the title in the app, like the Anime, Manga and Light Novel tabs do. */
        private fun open(context: Context, column: WidgetColumn, place: Int, item: WidgetItem): PendingIntent =
            PendingIntent.getActivity(
                context,
                place * WidgetColumn.entries.size + column.ordinal,
                WidgetIntents.open(context, item, "widget/${column.name}/$place"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** The column's name opens the wheel with all its covers. */
        private fun wheel(context: Context, column: WidgetColumn): PendingIntent {
            val intent = Intent(context, WidgetWheelActivity::class.java)
                .setData("aniliphy-widget://wheel/${column.name}".toUri())
                .putExtra(WidgetIntents.EXTRA_COLUMN, column.name)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
