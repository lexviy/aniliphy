package com.aniliphy.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color.TRANSPARENT
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.snapshotFlow
import com.aniliphy.WidgetOpen
import com.aniliphy.WidgetOpenRequest
import com.aniliphy.widget.WidgetKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.aniliphy.OpenTabRequest
import androidx.core.content.edit
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.manga.MangaSettings
import com.aniliphy.work.ReleaseNotifications

private enum class Tab(val label: String, val icon: TabIcon) {
    AniList("Anilist", HouseIcon),
    Anime("Anime", PlayIcon),
    Manga("Manga", MangaPageIcon),
    LightNovel("Light Novel", BookIcon),
    Settings("Settings", GearIcon),
}

private const val LOW_BATTERY_PERCENT = 20

@Composable
private fun AskForNotifications() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("release_notifications", android.content.Context.MODE_PRIVATE)
        val wanted = MangaSettings.get(context).notifyNewChapters || AnimeSettings.get(context).notifyNewEpisodes
        if (!wanted || ReleaseNotifications.allowed(context) || prefs.getBoolean("asked", false)) return@LaunchedEffect
        prefs.edit().putBoolean("asked", true).apply()
        ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    // The app opens on the tab it was left on (except Settings), also when it was closed.
    val lastTab = remember { context.getSharedPreferences(APP_STATE, android.content.Context.MODE_PRIVATE) }
    var selected by rememberSaveable {
        mutableStateOf(Tab.entries.firstOrNull { it.name == lastTab.getString(LAST_TAB, null) } ?: Tab.AniList)
    }
    LaunchedEffect(selected) {
        if (selected != Tab.Settings) lastTab.edit { putString(LAST_TAB, selected.name) }
    }
    val appearance = remember { AppearanceState(context.applicationContext) }
    val theme = appearance.effectiveTheme(isSystemInDarkTheme())

    val activity = LocalActivity.current as? ComponentActivity
    val looksDark = theme.dark != (appearance.shader == ShaderKind.InvertColors && ShadersSupported)
    LaunchedEffect(looksDark) {
        val style = if (looksDark) {
            SystemBarStyle.dark(TRANSPARENT)
        } else {
            SystemBarStyle.light(TRANSPARENT, TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    var barScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(selected, appearance.barHideOnScroll) { barScrolledAway = false }
    val hideOnScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -1f) barScrolledAway = true else if (available.y > 1f) barScrolledAway = false
                return Offset.Zero
            }
        }
    }

    val aniList = rememberAniListUi()
    val novels = rememberNovelsUi(aniList)
    val manga = rememberMangaUi(aniList)
    val anime = rememberAnimeUi(aniList)
    novels.onShowAniList = { selected = Tab.AniList }
    anime.onShowAniList = { selected = Tab.AniList }
    manga.onShowAniList = { selected = Tab.AniList }
    val sync = remember(anime, manga, novels) { SyncUi(context, anime, manga, novels) }
    LaunchedEffect(sync) { if (sync.settings.onOpen) sync.sync(quiet = true) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle, sync) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && sync.settings.onOpen) sync.sync(quiet = true)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Collected rather than keyed on the request, like the widget's below, so clearing it can't cancel anything.
    LaunchedEffect(Unit) {
        snapshotFlow { OpenTabRequest.tab }.filterNotNull().collect { asked ->
            OpenTabRequest.tab = null
            Tab.entries.firstOrNull { it.name == asked }?.let { selected = it }
        }
    }
    // Collected rather than keyed on the request: clearing it would restart a keyed effect and cancel the opening
    // halfway, after switching tabs but before the episode or chapter opens.
    LaunchedEffect(Unit) {
        snapshotFlow { WidgetOpenRequest.open }.filterNotNull().collect { asked ->
            WidgetOpenRequest.open = null
            // A tap on the widget can come while the app is still in the background; acting then could be undone when
            // it comes back, so wait until it's on screen. A player or reader left open would hide what was asked for.
            lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            if (anime.player != null) anime.closePlayer()
            if (manga.reader != null) manga.closeReader()
            if (novels.reader != null) novels.closeReader()
            openFromWidget(context, asked, anime, manga, novels, aniList) { selected = it }
        }
    }
    AskForNotifications()
    val batteryLow = rememberBatteryLow(appearance.animationBatterySaver)
    val sceneVisible = !batteryLow && (!appearance.animationHomeOnly || selected == Tab.AniList)

    val systemDensity = LocalDensity.current
    val density = remember(systemDensity, appearance.textSize) {
        Density(systemDensity.density, systemDensity.fontScale * appearance.textSize.scale)
    }

    CompositionLocalProvider(
        LocalAppTheme provides theme,
        LocalDensity provides density,
        LocalAniList provides aniList,
        LocalNovels provides novels,
        LocalManga provides manga,
        LocalAnime provides anime,
        LocalSync provides sync,
    ) {
        BotCheckDialog()
        val openAnime = anime.player
        if (openAnime != null) {
            androidx.compose.runtime.key(openAnime) {
                AnimePlayerScreen(openAnime, onClose = { anime.closePlayer() })
            }
            AnimeDialogs(anime)
            return@CompositionLocalProvider
        }
        val openManga = manga.reader
        if (openManga != null) {
            Box(Modifier.fillMaxSize().shaderEffect(appearance.shader, theme.primary, appearance.colorVisionMode)) {
                androidx.compose.runtime.key(openManga) {
                    MangaReaderScreen(openManga, onClose = { manga.closeReader() })
                }
            }
            MangaDialogs(manga)
            return@CompositionLocalProvider
        }
        val openBook = novels.reader
        if (openBook != null) {
            Box(Modifier.fillMaxSize().shaderEffect(appearance.shader, theme.primary, appearance.colorVisionMode)) {
                NovelReaderScreen(openBook, onClose = { novels.closeReader() })
            }
            return@CompositionLocalProvider
        }
        NovelDialogs(novels)
        MangaDialogs(manga)
        AnimeDialogs(anime)
        Column(
            Modifier
                .fillMaxSize()
                .shaderEffect(appearance.shader, theme.primary, appearance.colorVisionMode)
                .background(theme.background)
                .then(if (appearance.barHideOnScroll) Modifier.nestedScroll(hideOnScroll) else Modifier),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (sceneVisible) {
                    Scene(
                        kind = appearance.animation,
                        theme = theme,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(
                                appearance.animationIntensity *
                                    if (selected == Tab.Settings || selected == Tab.AniList || selected == Tab.LightNovel || selected == Tab.Manga || selected == Tab.Anime) 0.25f else 1f,
                            ),
                        speed = appearance.animationSpeed.factor,
                    )
                }
                if (selected == Tab.Settings) SettingsScreen(appearance)
                if (selected == Tab.AniList) AniListScreen(appearance, aniList)
                if (selected == Tab.LightNovel) LightNovelScreen(appearance, novels)
                if (selected == Tab.Manga) MangaScreen(appearance, manga)
                if (selected == Tab.Anime) AnimeScreen(appearance, anime)
            }
            AnimatedVisibility(
                visible = !(appearance.barHideOnScroll && barScrolledAway),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                BottomBar(
                    selected = selected,
                    showLabels = appearance.barShowLabels,
                    onSelect = { selected = it },
                )
            }
        }
    }
}

@Composable
private fun rememberBatteryLow(enabled: Boolean): Boolean {
    val context = LocalContext.current
    var low by remember { mutableStateOf(false) }

    DisposableEffect(enabled, context) {
        if (!enabled) {
            low = false
            return@DisposableEffect onDispose {}
        }
        val power = context.getSystemService(PowerManager::class.java)
        fun update(battery: Intent?) {
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val draining = !charging && level >= 0 && scale > 0 && level * 100 / scale <= LOW_BATTERY_PERCENT
            low = power.isPowerSaveMode || draining
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val battery = if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    intent
                } else {
                    registerBattery(receiverContext, null)
                }
                update(battery)
            }
        }
        update(registerBattery(context, receiver, filter))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return low
}

private fun registerBattery(
    context: Context,
    receiver: BroadcastReceiver?,
    filter: IntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED),
): Intent? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        context.registerReceiver(receiver, filter)
    }

@Composable
private fun BottomBar(selected: Tab, showLabels: Boolean, onSelect: (Tab) -> Unit) {
    val theme = LocalAppTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .navigationBarsPadding()
            .height(if (showLabels) 76.dp else 60.dp)
            .selectableGroup(),
    ) {
        Tab.entries.forEach { tab ->
            val isSelected = tab == selected
            val color = if (isSelected) theme.primary else theme.foreground
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .selectable(
                        selected = isSelected,
                        role = Role.Tab,
                        onClick = { onSelect(tab) },
                    ),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .then(
                            if (isSelected) {
                                Modifier.border(1.6.dp, color, CircleShape)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        tab.icon(this, IconPaint(color, cutout = theme.surface, filled = isSelected))
                    }
                }
                if (showLabels) {
                    Spacer(Modifier.height(2.dp))
                    BasicText(
                        text = tab.label,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp),
                        modifier = Modifier.padding(horizontal = 2.dp),
                        style = TextStyle(
                            color = color,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

/** Opens what was tapped on the home screen widget, the same way its card in the app does. */
private suspend fun openFromWidget(
    context: Context,
    asked: WidgetOpen,
    anime: AnimeUi,
    manga: MangaUi,
    novels: NovelsUi,
    aniList: AniListUi,
    select: (Tab) -> Unit,
) {
    when (asked.kind) {
        WidgetKind.Anime -> {
            val id = asked.key.toIntOrNull() ?: return
            select(Tab.Anime)
            val record = withContext(Dispatchers.IO) { anime.history.get(id) }?.takeIf { it.hasPlace } ?: return
            anime.continueWatching(id, record)
        }
        WidgetKind.Manga -> {
            val id = asked.key.toIntOrNull() ?: return
            select(Tab.Manga)
            val record = withContext(Dispatchers.IO) { manga.history.get(id) }?.takeIf { it.hasPlace } ?: return
            manga.continueReading(id, record)
        }
        WidgetKind.Novel -> {
            select(Tab.LightNovel)
            val novel = withTimeoutOrNull(WIDGET_LIBRARY_WAIT_MS) {
                snapshotFlow { novels.series.firstOrNull { it.key == asked.key } }.filterNotNull().first()
            }
            if (novel != null) novels.open(novel) else context.showToast("That book isn't in your light novels folder")
        }
        WidgetKind.Recommendation -> {
            val id = asked.key.toIntOrNull() ?: return
            aniList.navigator.open(Route.Media(id))
            select(Tab.AniList)
        }
    }
}

private const val WIDGET_LIBRARY_WAIT_MS = 20_000L
private const val APP_STATE = "app_state"
private const val LAST_TAB = "last_tab"
