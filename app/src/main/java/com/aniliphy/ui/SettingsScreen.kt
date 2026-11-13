package com.aniliphy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.manga.MangaSettings
import com.aniliphy.novels.NovelSettings

private enum class SettingsPage { List, Appearance, CustomTheme, Anime, Manga, Novels, Storage, Downloads, Extensions, Sync, Social, Spoilers, Activity, Guide, About, License, FontLicense }

@Composable
fun SettingsScreen(appearance: AppearanceState) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.List) }
    val context = LocalContext.current.applicationContext
    val animeSettings = remember { AnimeSettings.get(context) }
    val mangaSettings = remember { MangaSettings.get(context) }
    val novelSettings = remember { NovelSettings.get(context) }

    HoldTabSwipe(page != SettingsPage.List)
    when (page) {
        SettingsPage.List -> Unit
        SettingsPage.Appearance -> {
            BackHandler { page = SettingsPage.List }
            AppearanceScreen(
                appearance,
                onBack = { page = SettingsPage.List },
                onEditCustomTheme = { page = SettingsPage.CustomTheme },
            )
            return
        }
        SettingsPage.CustomTheme -> {
            BackHandler { page = SettingsPage.Appearance }
            CustomThemeScreen(appearance, onBack = { page = SettingsPage.Appearance })
            return
        }
        SettingsPage.Anime -> {
            BackHandler { page = SettingsPage.List }
            AnimeSettingsScreen(animeSettings, onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Manga -> {
            BackHandler { page = SettingsPage.List }
            MangaSettingsScreen(mangaSettings, onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Novels -> {
            BackHandler { page = SettingsPage.List }
            NovelSettingsScreen(novelSettings, onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Storage -> {
            BackHandler { page = SettingsPage.List }
            StorageScreen(onBack = { page = SettingsPage.List }, onDownloads = { page = SettingsPage.Downloads })
            return
        }
        SettingsPage.Downloads -> {
            BackHandler { page = SettingsPage.Storage }
            DownloadsScreen(onBack = { page = SettingsPage.Storage })
            return
        }
        SettingsPage.Extensions -> {
            BackHandler { page = SettingsPage.List }
            ExtensionsScreen(onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Sync -> {
            BackHandler { page = SettingsPage.List }
            SyncScreen(onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Spoilers -> {
            BackHandler { page = SettingsPage.List }
            SpoilerSettingsScreen(onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Social -> {
            BackHandler { page = SettingsPage.List }
            SocialSettingsScreen(onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Activity -> {
            BackHandler { page = SettingsPage.List }
            ActivityScreen(onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.Guide -> {
            BackHandler { page = SettingsPage.List }
            GuideScreen(onBack = { page = SettingsPage.List })
            return
        }
        SettingsPage.About -> {
            BackHandler { page = SettingsPage.List }
            AboutScreen(
                onOpenLicense = { page = SettingsPage.License },
                onOpenFontLicense = { page = SettingsPage.FontLicense },
                onBack = { page = SettingsPage.List },
            )
            return
        }
        SettingsPage.License -> {
            BackHandler { page = SettingsPage.About }
            LicenseScreen(onBack = { page = SettingsPage.About })
            return
        }
        SettingsPage.FontLicense -> {
            BackHandler { page = SettingsPage.About }
            LicenseScreen(onBack = { page = SettingsPage.About }, asset = "OFL.txt", title = "Font license")
            return
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        val items = listOf(
            MenuItem("Appearance", PaletteIcon) { page = SettingsPage.Appearance },
            MenuItem("Anime settings", PlayIcon) { page = SettingsPage.Anime },
            MenuItem("Manga settings", MangaPageIcon) { page = SettingsPage.Manga },
            MenuItem("Light Novel settings", BookIcon) { page = SettingsPage.Novels },
            MenuItem("Extensions", ExtensionIcon) { page = SettingsPage.Extensions },
            MenuItem("Storage", StorageIcon) { page = SettingsPage.Storage },
            MenuItem("Social", PeopleIcon) { page = SettingsPage.Social },
            MenuItem("Spoilers", EyeIcon) { page = SettingsPage.Spoilers },
            MenuItem("Sync with anilixy", SyncIcon) { page = SettingsPage.Sync },
            MenuItem("Activity", ChartIcon) { page = SettingsPage.Activity },
            MenuItem("Guide", GuideIcon) { page = SettingsPage.Guide },
            MenuItem("About", InfoIcon) { page = SettingsPage.About },
        )
        if (Look.tankobon || Look.modern) {
            items.forEach { SettingsRow(it.label, it.icon, onClick = it.onClick) }
        } else {
            StyledMenu(items)
        }
    }
}

@Composable
private fun SettingsRow(label: String, icon: TabIcon, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(60.dp)
            .padding(horizontal = 20.dp),
    ) {
        Canvas(Modifier.size(24.dp)) {
            icon(this, IconPaint(theme.primary, cutout = theme.surface, filled = false))
        }
        Spacer(Modifier.width(18.dp))
        BasicText(
            text = label,
            style = TextStyle(color = theme.foreground, fontSize = 16.sp),
        )
    }
}
