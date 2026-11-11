package com.aniliphy.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.aniliphy.R

/**
 * The shortcuts shown when holding the app's icon: carry on with the latest anime, manga and light novel, straight
 * into the player or reader like the widget. Kept up to date with the widget's data.
 */
object AppShortcuts {
    private const val TAG = "AppShortcuts"

    private enum class Kind(val id: String, val label: String) {
        Anime("continue_anime", "Continue anime"),
        Manga("continue_manga", "Continue manga"),
        Novel("continue_novel", "Continue light novel"),
    }

    fun update(context: Context, snapshot: WidgetSnapshot) {
        try {
            val latest = listOf(
                Kind.Anime to snapshot.anime.firstOrNull(),
                Kind.Manga to snapshot.manga.firstOrNull(),
                Kind.Novel to snapshot.novels.firstOrNull(),
            )
            val shortcuts = latest.mapIndexedNotNull { rank, (kind, item) ->
                item ?: return@mapIndexedNotNull null
                ShortcutInfoCompat.Builder(context, kind.id)
                    .setShortLabel(item.title.take(SHORT_LABEL))
                    .setLongLabel("${kind.label}: ${item.title}".take(LONG_LABEL))
                    .setIcon(icon(context, item))
                    .setIntent(WidgetIntents.open(context, item, "shortcut/${kind.id}"))
                    .setRank(rank)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            // A launcher can refuse shortcuts (too many, rate limits); the app works the same without them.
            Log.w(TAG, "Couldn't update the shortcuts", e)
        }
    }

    private fun icon(context: Context, item: WidgetItem): IconCompat =
        WidgetData.image(context, item)?.let { BitmapFactory.decodeFile(it.path) }?.let { cover ->
            // The middle of the cover, square, as launchers show shortcut icons round or square.
            val side = minOf(cover.width, cover.height)
            IconCompat.createWithBitmap(Bitmap.createBitmap(cover, (cover.width - side) / 2, (cover.height - side) / 2, side, side))
        }
            ?: IconCompat.createWithResource(context, R.mipmap.ic_launcher_cat)

    private const val SHORT_LABEL = 25
    private const val LONG_LABEL = 60
}
