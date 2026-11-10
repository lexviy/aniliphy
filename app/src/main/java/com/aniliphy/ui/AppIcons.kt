package com.aniliphy.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.aniliphy.R

enum class AppIcon(
    val alias: String,
    val label: String,
    @param:DrawableRes val foreground: Int,
    val background: Color,
) {
    Cat("com.aniliphy.IconCat", "Cat", R.drawable.ic_app_cat, Color(0xFF1A1B26)),
    Classic("com.aniliphy.IconClassic", "Classic", R.drawable.ic_app_classic, Color(0xFF1A1B26)),
    Play("com.aniliphy.IconPlay", "Play", R.drawable.ic_app_play, Color(0xFF7A2E45)),
    Manga("com.aniliphy.IconManga", "Manga", R.drawable.ic_app_manga, Color(0xFFF5F3EF)),
    Book("com.aniliphy.IconBook", "Novel", R.drawable.ic_app_book, Color(0xFF0C2931)),
    ;

    companion object {
        fun current(context: Context): AppIcon {
            val packages = context.packageManager
            return entries.firstOrNull { icon ->
                val state = packages.getComponentEnabledSetting(ComponentName(context, icon.alias))
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: Cat
        }

        /**
         * Leaves a single icon on the launcher. An icon picked before the cat became the default stays enabled
         * next to it, which would show the app twice. The old default, Classic, gives way to the cat; any other
         * pick is kept.
         */
        fun tidy(context: Context) {
            val packages = context.packageManager
            val shown = entries.filter { icon ->
                when (packages.getComponentEnabledSetting(ComponentName(context, icon.alias))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == Cat
                    else -> false
                }
            }
            if (shown.size <= 1) return
            val keep = shown.firstOrNull { it != Cat && it != Classic } ?: Cat
            select(context, keep)
        }

        fun select(context: Context, icon: AppIcon) {
            val packages = context.packageManager
            packages.setComponentEnabledSetting(
                ComponentName(context, icon.alias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach { other ->
                packages.setComponentEnabledSetting(
                    ComponentName(context, other.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
