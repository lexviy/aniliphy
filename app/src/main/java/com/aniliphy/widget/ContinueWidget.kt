package com.aniliphy.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.compose.ui.graphics.toArgb
import androidx.core.net.toUri
import com.aniliphy.MainActivity
import com.aniliphy.R
import com.aniliphy.ui.AppearanceState

/** The four columns of the widget, each a stack you swipe up to go to the next card. */
enum class WidgetColumn(val stack: Int, val empty: Int, val header: Int) {
    Anime(R.id.stack_anime, R.id.empty_anime, R.id.header_anime),
    Manga(R.id.stack_manga, R.id.empty_manga, R.id.header_manga),
    Novels(R.id.stack_novels, R.id.empty_novels, R.id.header_novels),
    Recommendations(R.id.stack_recommendations, R.id.empty_recommendations, R.id.header_recommendations),
    ;

    fun items(snapshot: WidgetSnapshot): List<WidgetItem> = when (this) {
        Anime -> snapshot.anime
        Manga -> snapshot.manga
        Novels -> snapshot.novels
        Recommendations -> snapshot.recommendations
    }
}

/** The app theme's colors, as plain ARGB, for the widget. */
data class WidgetColors(val background: Int, val card: Int, val panel: Int, val foreground: Int, val muted: Int, val primary: Int) {
    companion object {
        fun of(context: Context): WidgetColors {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val theme = AppearanceState(context).effectiveTheme(night)
            return WidgetColors(
                background = theme.background.toArgb(),
                card = theme.background.toArgb(),
                panel = theme.panel.toArgb(),
                foreground = theme.foreground.toArgb(),
                muted = theme.foreground.copy(alpha = 0.7f).toArgb(),
                primary = theme.primary.toArgb(),
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
        val colors = WidgetColors.of(context)
        ids.forEach { manager.updateAppWidget(it, views(context, it, colors)) }
        if (!WidgetData.file(context).isFile) WidgetData.refreshSoon(context)
    }

    companion object {
        /** Redraws every widget on the home screen with the latest data and theme. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ContinueWidget::class.java))
            if (ids.isEmpty()) return
            val colors = WidgetColors.of(context)
            ids.forEach { manager.updateAppWidget(it, views(context, it, colors)) }
            WidgetColumn.entries.forEach { manager.notifyAppWidgetViewDataChanged(ids, it.stack) }
        }

        @Suppress("DEPRECATION")
        private fun views(context: Context, widgetId: Int, colors: WidgetColors): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_continue)
            views.setInt(R.id.widget_background, "setColorFilter", colors.background)
            views.setInt(R.id.widget_background, "setImageAlpha", android.graphics.Color.alpha(colors.background))
            for (column in WidgetColumn.entries) {
                val adapter = Intent(context, ContinueWidgetService::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    .putExtra(WidgetIntents.EXTRA_COLUMN, column.name)
                adapter.data = adapter.toUri(Intent.URI_INTENT_SCHEME).toUri()
                views.setRemoteAdapter(column.stack, adapter)
                views.setEmptyView(column.stack, column.empty)
                views.setDisplayedChild(column.stack, 0)
                views.setTextColor(column.header, colors.primary)
                views.setTextColor(column.empty, colors.muted)
                val open = Intent(context, MainActivity::class.java).setAction(WidgetIntents.ACTION_OPEN)
                val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val template = PendingIntent.getActivity(
                    context,
                    widgetId * WidgetColumn.entries.size + column.ordinal,
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                )
                views.setPendingIntentTemplate(column.stack, template)
                val wheel = Intent(context, WidgetWheelActivity::class.java).putExtra(WidgetIntents.EXTRA_COLUMN, column.name)
                wheel.data = wheel.toUri(Intent.URI_INTENT_SCHEME).toUri()
                views.setOnClickPendingIntent(
                    column.header,
                    PendingIntent.getActivity(context, 0, wheel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
            }
            return views
        }
    }
}

class ContinueWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val column = intent.getStringExtra(WidgetIntents.EXTRA_COLUMN)
            ?.let { name -> WidgetColumn.entries.firstOrNull { it.name == name } } ?: WidgetColumn.Anime
        return CardFactory(applicationContext, column)
    }
}

private class CardFactory(private val context: Context, private val column: WidgetColumn) : RemoteViewsService.RemoteViewsFactory {
    private var items: List<WidgetItem> = emptyList()
    private var colors: WidgetColors? = null

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        items = column.items(WidgetData.read(context))
        colors = WidgetColors.of(context)
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount() = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val item = items.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_card)
        val colors = colors ?: WidgetColors.of(context)
        val views = RemoteViews(context.packageName, R.layout.widget_card)
        val image = WidgetData.image(context, item)
        if (image != null) {
            views.setImageViewUri(R.id.card_cover, WidgetCoverProvider.uri(context, image.name))
            views.setInt(R.id.card_cover, "setColorFilter", 0)
        } else {
            views.setImageViewResource(R.id.card_cover, R.drawable.widget_card_placeholder)
            views.setInt(R.id.card_cover, "setColorFilter", colors.panel)
        }
        views.setInt(R.id.card, "setBackgroundColor", colors.card)
        views.setTextViewText(R.id.card_title, item.title)
        views.setTextViewText(R.id.card_detail, item.detail)
        views.setTextColor(R.id.card_title, colors.foreground)
        views.setTextColor(R.id.card_detail, colors.muted)
        views.setContentDescription(R.id.card, "${item.title}, ${item.detail}")
        val fillIn = Intent()
            .putExtra(WidgetIntents.EXTRA_KIND, item.kind.name)
            .putExtra(WidgetIntents.EXTRA_KEY, item.key)
            .putExtra(WidgetIntents.EXTRA_OF, item.of?.name)
        views.setOnClickFillInIntent(R.id.card, fillIn)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount() = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds() = false
}
