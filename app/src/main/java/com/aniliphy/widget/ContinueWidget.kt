package com.aniliphy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import com.aniliphy.MainActivity
import com.aniliphy.R
import com.aniliphy.ui.AppearanceState

/** The four columns of the widget, each showing the latest title of its kind. */
enum class WidgetColumn(
    val card: Int,
    val cover: Int,
    val title: Int,
    val detail: Int,
    val empty: Int,
    val header: Int,
) {
    Anime(R.id.card_anime, R.id.cover_anime, R.id.title_anime, R.id.detail_anime, R.id.empty_anime, R.id.header_anime),
    Manga(R.id.card_manga, R.id.cover_manga, R.id.title_manga, R.id.detail_manga, R.id.empty_manga, R.id.header_manga),
    Novels(R.id.card_novels, R.id.cover_novels, R.id.title_novels, R.id.detail_novels, R.id.empty_novels, R.id.header_novels),
    Recommendations(
        R.id.card_recommendations,
        R.id.cover_recommendations,
        R.id.title_recommendations,
        R.id.detail_recommendations,
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
}

class ContinueWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        draw(context, manager, ids)
        if (!WidgetData.file(context).isFile) WidgetData.refreshSoon(context)
    }

    companion object {
        /** Redraws every widget on the home screen with the latest data and theme. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isNotEmpty()) draw(context, manager, ids)
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val colors = WidgetColors.of(context)
            val snapshot = WidgetData.read(context)
            ids.forEach { manager.updateAppWidget(it, views(context, snapshot, colors)) }
        }

        private fun views(context: Context, snapshot: WidgetSnapshot, colors: WidgetColors): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_continue)
            views.setInt(R.id.widget_background, "setColorFilter", colors.background)
            views.setInt(R.id.widget_background, "setImageAlpha", android.graphics.Color.alpha(colors.background))
            for (column in WidgetColumn.entries) {
                views.setTextColor(column.header, colors.primary)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    views.setColorStateList(column.header, "setBackgroundTintList", ColorStateList.valueOf(colors.primary))
                }
                views.setTextColor(column.empty, colors.muted)
                views.setOnClickPendingIntent(column.header, wheel(context, column))
                val item = column.items(snapshot).firstOrNull()
                views.setViewVisibility(column.card, if (item != null) View.VISIBLE else View.GONE)
                views.setViewVisibility(column.empty, if (item != null) View.GONE else View.VISIBLE)
                if (item != null) card(context, views, column, item, colors)
            }
            return views
        }

        private fun card(context: Context, views: RemoteViews, column: WidgetColumn, item: WidgetItem, colors: WidgetColors) {
            val image = WidgetData.image(context, item)
            if (image != null) {
                views.setImageViewUri(column.cover, WidgetCoverProvider.uri(context, image.name))
                views.setInt(column.cover, "setColorFilter", 0)
            } else {
                views.setImageViewResource(column.cover, R.drawable.widget_card_placeholder)
                views.setInt(column.cover, "setColorFilter", colors.panel)
            }
            views.setTextViewText(column.title, item.title)
            views.setTextViewText(column.detail, item.detail)
            views.setTextColor(column.title, colors.foreground)
            views.setTextColor(column.detail, colors.muted)
            views.setContentDescription(column.card, "${item.title}, ${item.detail}")
            views.setOnClickPendingIntent(column.card, open(context, column, item))
        }

        /** Opens the title in the app, like the Anime, Manga and Light Novel tabs do. */
        private fun open(context: Context, column: WidgetColumn, item: WidgetItem): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(WidgetIntents.ACTION_OPEN)
                .setData("aniliphy-widget://open/${column.name}".toUri())
                .putExtra(WidgetIntents.EXTRA_KIND, item.kind.name)
                .putExtra(WidgetIntents.EXTRA_KEY, item.key)
                .putExtra(WidgetIntents.EXTRA_OF, item.of?.name)
            return PendingIntent.getActivity(context, column.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** The column's name opens the wheel with all its covers. */
        private fun wheel(context: Context, column: WidgetColumn): PendingIntent {
            val intent = Intent(context, WidgetWheelActivity::class.java)
                .setData("aniliphy-widget://wheel/${column.name}".toUri())
                .putExtra(WidgetIntents.EXTRA_COLUMN, column.name)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
