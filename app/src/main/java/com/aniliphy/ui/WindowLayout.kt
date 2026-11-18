package com.aniliphy.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf

/** How much room the app has: a phone, a small tablet upright, a wide screen, or a TV across the room. */
enum class WindowLayout {
    Compact,
    Medium,
    Expanded,
    Tv,
    ;

    /** A rail on the side instead of the look's bottom bar. */
    val rail: Boolean get() = this == Expanded || this == Tv

    /** A list and the page opened from it, side by side. */
    val twoPanes: Boolean get() = this == Expanded || this == Tv

    /** The smallest a cover may be in a grid. */
    val coverMinDp: Int get() = when (this) {
        Compact, Medium -> 106
        Expanded -> 112
        Tv -> 150
    }

    companion object {
        const val MEDIUM_DP = 600
        const val EXPANDED_DP = 840

        fun of(widthDp: Int, @Suppress("UNUSED_PARAMETER") heightDp: Int, tv: Boolean): WindowLayout = when {
            tv -> Tv
            widthDp >= EXPANDED_DP -> Expanded
            widthDp >= MEDIUM_DP -> Medium
            else -> Compact
        }
    }
}

internal val LocalWindowLayout = compositionLocalOf { WindowLayout.Compact }

/** Whether the app runs on a TV (or, in a debug build, was told to act as one for testing). */
internal object TvDevice {
    fun isTv(context: Context): Boolean {
        val ui = context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val forced = com.aniliphy.BuildConfig.DEBUG &&
            context.getSharedPreferences("debug", Context.MODE_PRIVATE).getBoolean("force_tv", false)
        return ui || leanback || forced
    }
}
