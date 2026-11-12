package com.aniliphy.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
    // The home-screen widget wears the same theme and look, so it is redrawn when either changes.
    val widgetContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(theme, appearance.uiStyle) { com.aniliphy.widget.ContinueWidget.update(widgetContext) }

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
    androidx.compose.runtime.SideEffect { Motion.speed = appearance.animationSpeed.factor }
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
                            .alpha(appearance.animationIntensity.coerceIn(0f, 1f)),
                        speed = appearance.animationSpeed.factor,
                    )
                }
                StyleBackdrop(Modifier.fillMaxSize())
                @Composable
                fun TabScreen(tab: Tab) {
                    when (tab) {
                        Tab.Settings -> SettingsScreen(appearance)
                        Tab.AniList -> AniListScreen(appearance, aniList)
                        Tab.LightNovel -> LightNovelScreen(appearance, novels)
                        Tab.Manga -> MangaScreen(appearance, manga)
                        Tab.Anime -> AnimeScreen(appearance, anime)
                    }
                }
                run {
                    // The new tab slides in from the side it was tapped on, the old one drifts the other way.
                    androidx.compose.animation.SharedTransitionLayout {
                    androidx.compose.animation.AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            val slide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(Motion.ms(340), easing = androidx.compose.animation.core.FastOutSlowInEasing)
                            (
                                androidx.compose.animation.slideInHorizontally(slide) { it / 6 * direction } +
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.ms(280)))
                                ) togetherWith (
                                androidx.compose.animation.slideOutHorizontally(slide) { -it / 6 * direction } +
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.ms(200)))
                                )
                        },
                        label = "tabs",
                        modifier = Modifier.fillMaxSize(),
                    ) { tab ->
                        CompositionLocalProvider(
                            LocalSharedScope provides this@SharedTransitionLayout,
                            LocalTabScope provides this@AnimatedContent,
                        ) { TabScreen(tab) }
                    }
                    }
                }
                TabCut(selected)
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
    when (Look.style) {
        UiStyle.Tankobon -> TankobonBottomBar(selected, showLabels, onSelect)
        UiStyle.Modern -> ModernBottomBar(selected, showLabels, onSelect)
        UiStyle.Genga, UiStyle.Bunko, UiStyle.Shinya, UiStyle.Jrpg, UiStyle.Eki, UiStyle.Konbini, UiStyle.Stamp, UiStyle.Glass ->
            StyledBottomBar(selected, showLabels, onSelect)
    }
}

/**
 * The tab bar in the other looks. They share a layout and differ in how the selected tab is marked: hatching and a
 * pencil circle (Genga), a bookmark ribbon (Bunko), a lit LED (Shin'ya), a menu cursor (JRPG), the station you're at
 * on a line (Eki), inverted print (Konbini), a stamp (Stamp rally) or a glass pill (Glass).
 */
@Composable
private fun StyledBottomBar(selected: Tab, showLabels: Boolean, onSelect: (Tab) -> Unit) {
    val theme = LocalAppTheme.current
    val style = Look.style
    val floating = style == UiStyle.Jrpg || style == UiStyle.Glass
    val barHeight = when {
        style == UiStyle.Eki -> if (showLabels) 80.dp else 62.dp
        floating -> if (showLabels) 66.dp else 56.dp
        else -> if (showLabels) 76.dp else 60.dp
    }
    val background = when (style) {
        UiStyle.Genga, UiStyle.Konbini -> theme.background
        UiStyle.Jrpg, UiStyle.Glass -> Color.Transparent
        else -> theme.surface
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (floating) theme.background.copy(alpha = if (style == UiStyle.Glass) 0f else 1f) else background)
            .navigationBarsPadding()
            .then(if (floating) Modifier.padding(horizontal = 12.dp, vertical = 10.dp) else Modifier)
            .drawBehind {
                when (style) {
                    UiStyle.Genga -> drawLine(theme.ink, Offset(0f, 0f), Offset(size.width, 0f), 2.dp.toPx())
                    UiStyle.Bunko -> {
                        drawLine(theme.hairline, Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
                        drawLine(theme.hairline, Offset(0f, 3.5.dp.toPx()), Offset(size.width, 3.5.dp.toPx()), 1.dp.toPx())
                    }
                    UiStyle.Konbini -> drawLine(
                        theme.hairline, Offset(0f, 1f), Offset(size.width, 1f), 2.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    )
                    else -> Unit
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .then(
                    when (style) {
                        UiStyle.Jrpg -> Modifier.rpgWindow(theme)
                        UiStyle.Glass -> Modifier.shadow(14.dp, CircleShape).glass(theme, CircleShape).padding(5.dp)
                        UiStyle.Eki -> Modifier.drawBehind {
                            val y = 27.dp.toPx()
                            drawLine(theme.primary, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 6.dp.toPx(), StrokeCap.Round)
                        }
                        else -> Modifier
                    },
                )
                .selectableGroup(),
        ) {
            Tab.entries.forEachIndexed { index, tab ->
                val isSelected = tab == selected
                val pop by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isSelected) 1.08f else 1f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f),
                    label = "pop",
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = if (style == UiStyle.Eki) Arrangement.Top else Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .then(
                            when {
                                !isSelected -> Modifier
                                style == UiStyle.Genga -> Modifier.drawBehind {
                                    val step = 6.dp.toPx()
                                    var x = -size.height
                                    while (x < size.width) {
                                        drawLine(theme.primary.copy(alpha = 0.35f), Offset(x, size.height), Offset(x + size.height, 0f), 1.5.dp.toPx())
                                        x += step
                                    }
                                }
                                style == UiStyle.Konbini -> Modifier.padding(4.dp).background(theme.foreground)
                                style == UiStyle.Glass -> Modifier.clip(CircleShape).background(if (theme.dark) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.07f))
                                else -> Modifier
                            },
                        )
                        .then(
                            if (style == UiStyle.Genga && index < Tab.entries.lastIndex) {
                                Modifier.drawBehind { drawLine(theme.hairline, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
                            } else {
                                Modifier
                            },
                        )
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                        .then(if (style == UiStyle.Eki) Modifier.padding(top = 6.dp) else Modifier)
                        .semantics { contentDescription = tab.label },
                ) {
                    TabMark(tab, index, isSelected, style, pop)
                    if (showLabels) {
                        Spacer(Modifier.height(if (style == UiStyle.Eki) 2.dp else 3.dp))
                        val labelColor = when {
                            style == UiStyle.Konbini && isSelected -> theme.background
                            style == UiStyle.Stamp && isSelected -> theme.pencil
                            style == UiStyle.Jrpg && isSelected -> theme.accent
                            isSelected -> if (style == UiStyle.Glass || style == UiStyle.Eki) theme.ink else theme.primary
                            else -> theme.foreground.copy(alpha = 0.75f)
                        }
                        val text = if (style == UiStyle.Genga || style == UiStyle.Konbini) tab.label.uppercase() else tab.label
                        BasicText(
                            text = text,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp),
                            modifier = Modifier.padding(horizontal = 2.dp),
                            style = when (style) {
                                UiStyle.Genga, UiStyle.Konbini -> NumberStyle(color = labelColor, bold = isSelected)
                                UiStyle.Bunko -> TitleStyle(color = labelColor, fontSize = 12.sp)
                                else -> TextStyle(color = labelColor, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** A tab's icon with the look's mark for the selected one around it. */
@Composable
private fun TabMark(tab: Tab, index: Int, selected: Boolean, style: UiStyle, pop: Float) {
    val theme = LocalAppTheme.current
    val muted = theme.foreground.copy(alpha = 0.78f)
    @Composable
    fun Icon(color: Color, cutout: Color, filled: Boolean, size: androidx.compose.ui.unit.Dp = 24.dp) {
        Canvas(Modifier.size(size).graphicsLayer { scaleX = pop; scaleY = pop }) {
            tab.icon(this, IconPaint(color, cutout = cutout, filled = filled))
        }
    }
    when (style) {
        UiStyle.Genga -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(46.dp, 32.dp)) {
            BasicText(
                "%02d".format(index + 1),
                style = NumberStyle(color = if (selected) theme.primary else muted, fontSize = 8.sp, bold = true),
                modifier = Modifier.align(Alignment.TopStart),
            )
            if (selected) {
                Box(
                    Modifier
                        .size(40.dp, 28.dp)
                        .graphicsLayer { rotationZ = -4f }
                        .background(theme.background, CircleShape)
                        .border(2.dp, theme.primary, CircleShape),
                )
            }
            Icon(if (selected) theme.primary else muted, theme.background, false, 22.dp)
        }
        UiStyle.Bunko -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp, 36.dp)) {
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-14).dp)
                        .size(20.dp, 24.dp)
                        .clip(RibbonShape)
                        .background(theme.primary),
                )
            }
            Box(Modifier.padding(top = 8.dp)) { Icon(if (selected) theme.primary else muted, theme.surface, selected) }
        }
        UiStyle.Shinya -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(22.dp, 4.dp)
                    .then(if (selected) Modifier.shadow(8.dp, RoundedCornerShape(2.dp), ambientColor = theme.primary, spotColor = theme.primary) else Modifier)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) theme.primary else theme.rule),
            )
            Spacer(Modifier.height(5.dp))
            Icon(if (selected) theme.primary else muted, theme.surface, selected)
        }
        UiStyle.Jrpg -> Row(verticalAlignment = Alignment.CenterVertically) {
            BlinkingCursor(theme.accent, 11.sp, visible = selected)
            Spacer(Modifier.width(3.dp))
            Icon(if (selected) theme.accent else muted, theme.surface, selected)
            Spacer(Modifier.width(14.dp))
        }
        UiStyle.Eki -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(42.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (selected) 42.dp else 32.dp)
                    .clip(CircleShape)
                    .background(if (selected) theme.primary else EkiPlate)
                    .border(if (selected) 4.dp else 3.dp, if (selected) EkiPlate else theme.primary, CircleShape),
            ) {
                Icon(if (selected) Color.White else EkiPlateInk, if (selected) theme.primary else EkiPlate, selected, if (selected) 20.dp else 16.dp)
            }
        }
        UiStyle.Konbini -> Icon(if (selected) theme.background else muted, theme.foreground, selected, 22.dp)
        UiStyle.Stamp -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(38.dp)
                .graphicsLayer { rotationZ = if (selected) -10f else 0f }
                .drawBehind {
                    val r = size.minDimension / 2f - 1.5.dp.toPx()
                    if (selected) {
                        drawCircle(theme.pencil, r, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx()))
                        drawCircle(theme.pencil, r - 3.5.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    } else {
                        drawCircle(
                            theme.hairline, r,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5f, 5f))),
                        )
                    }
                },
        ) {
            Icon(if (selected) theme.pencil else muted, theme.surface, selected, 20.dp)
        }
        UiStyle.Glass -> Icon(if (selected) theme.ink else muted, if (theme.dark) theme.surface else theme.background, selected, 22.dp)
        UiStyle.Tankobon, UiStyle.Modern -> Icon(if (selected) theme.primary else muted, theme.surface, selected)
    }
}

/** A bookmark ribbon: a strip with a V cut into its end. */
private val RibbonShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    moveTo(0f, 0f)
    lineTo(size.width, 0f)
    lineTo(size.width, size.height)
    lineTo(size.width / 2f, size.height * 0.72f)
    lineTo(0f, size.height)
    close()
}

/**
 * Modern's tab bar: a rounded bar floating above the bottom edge. The selected tab grows into a pill with its name,
 * and the pill springs open as you move between tabs.
 */
@Composable
private fun ModernBottomBar(selected: Tab, showLabels: Boolean, onSelect: (Tab) -> Unit) {
    val theme = LocalAppTheme.current
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(theme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(14.dp, shape)
                .clip(shape)
                .background(androidx.compose.ui.graphics.lerp(theme.surface, theme.foreground, 0.04f))
                .padding(horizontal = 8.dp)
                .selectableGroup(),
        ) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                val pill by androidx.compose.animation.animateColorAsState(
                    if (isSelected) theme.primary else theme.primary.copy(alpha = 0f),
                    androidx.compose.animation.core.tween(Motion.ms(220)),
                    label = "pill",
                )
                val iconColor = if (isSelected) theme.background else theme.foreground.copy(alpha = 0.75f)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(CircleShape)
                        .background(pill)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                        .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 500f))
                        .padding(horizontal = 12.dp)
                        .semantics { contentDescription = tab.label },
                ) {
                    Canvas(Modifier.size(24.dp)) {
                        tab.icon(this, IconPaint(iconColor, cutout = theme.primary, filled = isSelected))
                    }
                    if (isSelected && showLabels) {
                        Spacer(Modifier.size(8.dp))
                        BasicText(
                            tab.label,
                            maxLines = 1,
                            style = TitleStyle(color = theme.background, fontSize = 15.sp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tankōbon's tab bar: the selected tab sits on a slanted ink panel with a screentone shadow, and the panel slides
 * to the tapped tab with a little overshoot while its icon pops.
 */
@Composable
private fun TankobonBottomBar(selected: Tab, showLabels: Boolean, onSelect: (Tab) -> Unit) {
    val theme = LocalAppTheme.current
    val density = LocalDensity.current
    var barLeft by remember { mutableStateOf(0f) }
    var barTop by remember { mutableStateOf(0f) }
    val iconCenters = remember { androidx.compose.runtime.mutableStateMapOf<Tab, Offset>() }
    val target = iconCenters[selected]
    // Snaps into place the first time the bar is laid out (launch, closing a reader), then springs between tabs.
    val marker = remember { androidx.compose.animation.core.Animatable(Float.NaN) }
    LaunchedEffect(target?.x) {
        val x = target?.x ?: return@LaunchedEffect
        if (marker.value.isNaN()) {
            marker.snapTo(x)
        } else {
            marker.animateTo(x, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 420f))
        }
    }
    val markerX = marker.value
    Box(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .navigationBarsPadding()
            .onGloballyPositioned { val p = it.positionInRoot(); barLeft = p.x; barTop = p.y },
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(theme.rule))
        if (target != null && !markerX.isNaN()) {
            val width = with(density) { MARKER_WIDTH.toPx() }
            val height = with(density) { MARKER_HEIGHT.toPx() }
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = markerX - width / 2f
                        translationY = target.y - height / 2f
                    }
                    .size(MARKER_WIDTH, MARKER_HEIGHT),
            ) {
                Box(
                    Modifier
                        .offset(4.dp, 4.dp)
                        .size(MARKER_WIDTH, MARKER_HEIGHT)
                        .graphicsLayer { rotationZ = 0f }
                        .skewed()
                        .screentone(theme.primary),
                )
                Box(Modifier.size(MARKER_WIDTH, MARKER_HEIGHT).skewed().background(theme.primary))
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (showLabels) 78.dp else 62.dp)
                .selectableGroup(),
        ) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                val pop by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isSelected) 1.1f else 1f,
                    animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.4f, stiffness = 500f),
                    label = "pop",
                )
                val iconColor = if (isSelected) theme.background else theme.foreground.copy(alpha = 0.8f)
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(MARKER_WIDTH, MARKER_HEIGHT)
                            .onGloballyPositioned {
                                val p = it.positionInRoot()
                                iconCenters[tab] = Offset(
                                    p.x - barLeft + it.size.width / 2f,
                                    p.y - barTop + it.size.height / 2f,
                                )
                            },
                    ) {
                        Canvas(Modifier.size(24.dp).graphicsLayer { scaleX = pop; scaleY = pop }) {
                            tab.icon(this, IconPaint(iconColor, cutout = theme.primary, filled = isSelected))
                        }
                    }
                    if (showLabels) {
                        Spacer(Modifier.height(4.dp))
                        BasicText(
                            text = tab.label,
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 12.sp),
                            modifier = Modifier.padding(horizontal = 2.dp),
                            style = TextStyle(color = if (isSelected) theme.primary else theme.foreground.copy(alpha = 0.8f)),
                        )
                    }
                }
            }
        }
    }
}

private val MARKER_WIDTH = 52.dp
private val MARKER_HEIGHT = 34.dp

/** Slants a box like the edge of a manga panel. */
private fun Modifier.skewed(): Modifier = clip(SkewShape)

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
