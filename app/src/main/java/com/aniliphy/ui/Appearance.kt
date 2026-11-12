package com.aniliphy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.aniliphy.core.PrefStore

data class AppTheme(
    val name: String,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val foreground: Color,
    val background: Color,
    val surface: Color,
    val panel: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val dark: Boolean = true,
)

private fun AppTheme(
    name: String,
    primary: Int,
    secondary: Int,
    accent: Int,
    foreground: Int,
    background: Int,
    surface: Int,
    panel: Int,
    success: Int,
    warning: Int,
    error: Int,
    dark: Boolean = true,
) = AppTheme(
    name,
    rgb(primary),
    rgb(secondary),
    rgb(accent),
    rgb(foreground),
    rgb(background),
    rgb(surface),
    rgb(panel),
    rgb(success),
    rgb(warning),
    rgb(error),
    dark,
)

private fun rgb(value: Int) = Color(0xFF000000.toInt() or value)

val Themes = listOf(
    AppTheme("tokyo-night-blue", 0x7aa2f7, 0x7dcfff, 0xff9e64, 0xc0caf5, 0x1a1b26, 0x24283b, 0x292e42, 0x9ece6a, 0xe0af68, 0xf7768e),
    AppTheme("tokyo-midnight", 0x7aa2f7, 0x7dcfff, 0xbb9af7, 0xc0caf5, 0x0b0c15, 0x13141f, 0x1c1e2e, 0x9ece6a, 0xe0af68, 0xf7768e),
    AppTheme("catppuccin", 0x89b4fa, 0xcba6f7, 0xf5c2e7, 0xcdd6f4, 0x1e1e2e, 0x313244, 0x45475a, 0xa6e3a1, 0xf9e2af, 0xf38ba8),
    AppTheme("gruvbox-dark", 0x83a598, 0xd3869b, 0xfe8019, 0xebdbb2, 0x282828, 0x3c3836, 0x504945, 0xb8bb26, 0xfabd2f, 0xfb4934),
    AppTheme("nord-night", 0x88c0d0, 0x81a1c1, 0xb48ead, 0xeceff4, 0x2e3440, 0x3b4252, 0x434c5e, 0xa3be8c, 0xebcb8b, 0xbf616a),
    AppTheme("dracula-night", 0xbd93f9, 0x8be9fd, 0xff79c6, 0xf8f8f2, 0x282a36, 0x343746, 0x44475a, 0x50fa7b, 0xf1fa8c, 0xff5555),
    AppTheme("sakura", 0xf5a9c0, 0xffc8dd, 0xcdb4db, 0xfde2ea, 0x1f1720, 0x2b2030, 0x3a2b40, 0xb5e48c, 0xf9d29d, 0xff6b81),
    AppTheme("pearl-white", 0x5b7db1, 0x7a6fa8, 0xd17a8a, 0x2e3440, 0xf5f3ef, 0xffffff, 0xe9e6e0, 0x4c9a6a, 0xc58a1b, 0xc0392b, dark = false),
    AppTheme("solarized-light", 0x268bd2, 0x1f7a74, 0xc02c74, 0x586e75, 0xfdf6e3, 0xeee8d5, 0xe4ddc8, 0x859900, 0x8a6900, 0xdc322f, dark = false),
    AppTheme("sepia-paper", 0x8a5a2b, 0x5c6b3b, 0xa33b3b, 0x3f3222, 0xf3e9d2, 0xfaf2df, 0xe6d8bc, 0x5f7a3f, 0x8a5f0f, 0x9b2c2c, dark = false),
    AppTheme("forest-moss", 0x7fbf7f, 0x4f9d69, 0xe0b84c, 0xd8e6d0, 0x0e1a12, 0x16251c, 0x203326, 0x7ed957, 0xd9a441, 0xd95d5d),
    AppTheme("ocean-deep", 0x2ec4b6, 0x48cae4, 0xff9f1c, 0xd6f3f0, 0x071c24, 0x0c2931, 0x123842, 0x06d6a0, 0xffd166, 0xef476f),
    AppTheme("desert-dusk", 0xe07a5f, 0xf2cc8f, 0x81b29a, 0xf3d9c6, 0x24120f, 0x321b17, 0x43251e, 0x8ab17d, 0xe9c46a, 0xd64545),
    AppTheme("crt-amber", 0xffb000, 0xa3e635, 0xff6b35, 0xffc66d, 0x0b0903, 0x151106, 0x21180a, 0xa3e635, 0xffd166, 0xff4d4d),
    AppTheme("lavender-light", 0x6c5ce7, 0x007287, 0xb94b34, 0x302b45, 0xf3efff, 0xfbf9ff, 0xe4dcf7, 0x2e8b57, 0x8f6416, 0xc0392b, dark = false),
    AppTheme("ink-mono", 0x2f2f2f, 0x6b6b6b, 0xd7263d, 0x111111, 0xf7f7f5, 0xffffff, 0xe8e8e4, 0x2f6b3a, 0x8a6d00, 0xb00020, dark = false),
    AppTheme("neon-arcade", 0x00f5d4, 0xf15bb5, 0xfee440, 0xf8f8f2, 0x09021a, 0x15072b, 0x211040, 0x7bf1a8, 0xf9c74f, 0xff006e),
    AppTheme("autumn", 0xb5651d, 0x8f6416, 0x5f6f28, 0x3a2a1f, 0xf6ead4, 0xfff5e3, 0xe8d5b5, 0x5f7a3f, 0x7a5510, 0xa63a2b, dark = false),
    AppTheme("spring", 0x2f7d4e, 0x2f6f94, 0xb23f6b, 0x2f3e33, 0xf3f8ef, 0xfbfdf8, 0xe1ecdc, 0x3f7430, 0x8a6500, 0xb23a48, dark = false),
    AppTheme("summer", 0x0077b6, 0x007267, 0xc13e50, 0x22333b, 0xeef8fb, 0xffffff, 0xd8edf2, 0x237a4a, 0x9a6200, 0xc0392b, dark = false),
    AppTheme("winter", 0x8ecae6, 0xbde0fe, 0xff6b6b, 0xe6f0f5, 0x0a1620, 0x112334, 0x193349, 0x2a9d8f, 0xffb703, 0xe63946),
)

val LocalAppTheme = staticCompositionLocalOf { Themes.first() }

const val CUSTOM_THEME_NAME = "custom"

enum class AnimationSpeed(val label: String, val factor: Float) {
    Slow("Slow", 0.5f),
    Normal("Normal", 1f),
    Fast("Fast", 1.8f),
}

enum class TextSize(val label: String, val scale: Float) {
    Small("Small", 0.88f),
    Normal("Normal", 1f),
    Large("Large", 1.18f),
}

enum class CoverCorners(val label: String) { Rounded("Rounded"), Square("Square") }

enum class CoverLayout(val label: String) { Grid("Grid"), List("List") }

enum class AccentColor(val label: String, val rgb: Int?) {
    Theme("Theme", null),
    Blue("Blue", 0x7aa2f7),
    Teal("Teal", 0x2ec4b6),
    Green("Green", 0x9ece6a),
    Yellow("Yellow", 0xe0af68),
    Orange("Orange", 0xff9e64),
    Red("Red", 0xf7768e),
    Pink("Pink", 0xf5a9c0),
    Purple("Purple", 0xbb9af7),
    ;

    fun color(forDarkTheme: Boolean): Color? = rgb?.let { value ->
        if (forDarkTheme) rgb(value) else lerp(rgb(value), Color.Black, 0.35f)
    }
}

class AppearanceState(context: Context) : PrefStore(context, "appearance") {
    private var themeName by stringPref("theme", Themes.first().name)
    private var darkThemeName by stringPref("theme_dark", Themes.first().name)
    private var lightThemeName by stringPref("theme_light", "pearl-white")
    var followSystem by boolPref("follow_system", false)
    var amoled by boolPref("amoled", false)
    var accent by enumPref("accent", AccentColor.Theme, AccentColor.entries)
    var shader by enumPref("shader", ShaderKind.Off, ShaderKind.entries)
    var colorVisionMode by enumPref("color_vision_mode", ColorVisionMode.Protanopia, ColorVisionMode.entries)

    var customBackground by intPref("custom_background", 0x14161f)
    var customSurface by intPref("custom_surface", 0x20232f)
    var customForeground by intPref("custom_foreground", 0xe6e8f0)
    var customPrimary by intPref("custom_primary", 0xe0683c)
    var customSecondary by intPref("custom_secondary", 0xf2b880)
    var customAccent by intPref("custom_accent", 0x7fd1ae)

    var animation by enumPref("animation", SceneKind.Off, SceneKind.entries)
    var animationIntensity by floatPref("animation_intensity", 1f)
    var animationSpeed by enumPref("animation_speed", AnimationSpeed.Normal, AnimationSpeed.entries)
    var animationHomeOnly by boolPref("animation_home_only", false)
    var animationBatterySaver by boolPref("animation_battery_saver", false)

    private var storedStyle by enumPref("ui_style", UiStyle.Tankobon, UiStyle.entries)
    var uiStyle: UiStyle
        get() = storedStyle
        set(value) {
            storedStyle = value
            Look.style = value
        }

    init {
        Look.style = storedStyle
    }

    var textSize by enumPref("text_size", TextSize.Normal, TextSize.entries)
    var coverCorners by enumPref("cover_corners", CoverCorners.Rounded, CoverCorners.entries)
    var coverLayout by enumPref("cover_layout", CoverLayout.Grid, CoverLayout.entries)
    var barShowLabels by boolPref("bar_show_labels", true)
    var barHideOnScroll by boolPref("bar_hide_on_scroll", false)

    val customTheme: AppTheme
        get() {
            val background = rgb(customBackground)
            val surface = rgb(customSurface)
            val isDark = background.luminance() < 0.5f
            return AppTheme(
                name = CUSTOM_THEME_NAME,
                primary = rgb(customPrimary),
                secondary = rgb(customSecondary),
                accent = rgb(customAccent),
                foreground = rgb(customForeground),
                background = background,
                surface = surface,
                panel = lerp(surface, rgb(customForeground), 0.12f),
                success = rgb(if (isDark) 0x9ece6a else 0x3f7430),
                warning = rgb(if (isDark) 0xe0af68 else 0x8a6500),
                error = rgb(if (isDark) 0xf7768e else 0xb23a48),
                dark = isDark,
            )
        }

    val allThemes: List<AppTheme> get() = Themes + customTheme

    private fun themeNamed(name: String): AppTheme =
        allThemes.firstOrNull { it.name == name } ?: Themes.first()

    fun select(theme: AppTheme) {
        if (followSystem) {
            if (theme.dark) darkThemeName = theme.name else lightThemeName = theme.name
        } else {
            themeName = theme.name
        }
    }

    fun isSelected(theme: AppTheme): Boolean =
        if (followSystem) {
            theme.name == darkThemeName || theme.name == lightThemeName
        } else {
            theme.name == themeName
        }

    fun effectiveTheme(systemDark: Boolean): AppTheme {
        var theme = when {
            !followSystem -> themeNamed(themeName)
            systemDark -> themeNamed(darkThemeName)
            else -> themeNamed(lightThemeName)
        }
        if (amoled && theme.dark) {
            theme = theme.copy(
                background = Color.Black,
                surface = Color(0xFF0C0C0E),
                panel = Color(0xFF1C1C20),
            )
        }
        accent.color(theme.dark)?.let { theme = theme.copy(primary = it) }
        return theme
    }
}
