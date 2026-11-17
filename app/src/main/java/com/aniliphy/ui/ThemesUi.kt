package com.aniliphy.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.MediaKind
import com.aniliphy.anime.AnimeTheme
import com.aniliphy.anime.BackgroundAudio
import com.aniliphy.anime.Themes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Openings and endings playing one after another, with the screen off too (controls in the notification, through the
 * same service the player's sound-only mode uses). Pauses when an episode opens.
 */
internal object ThemeRadio {
    var queue by mutableStateOf<List<AnimeTheme>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    private var player: ExoPlayer? = null

    val current: AnimeTheme? get() = queue.getOrNull(index)

    /** Plays [themes] in order, from [first] (the first one when not given or not among them). */
    fun play(context: Context, themes: List<AnimeTheme>, first: AnimeTheme? = null) {
        val list = themes.filter { it.audioUrl != null }
        if (list.isEmpty()) {
            context.showToast("No song to play")
            return
        }
        val app = context.applicationContext
        val p = player ?: ExoPlayer.Builder(app).build().also { made ->
            made.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    index = made.currentMediaItemIndex
                    problem = null
                }

                override fun onPlayerError(error: PlaybackException) {
                    // One song that won't play doesn't stop the rest.
                    problem = "Couldn't play ${current?.song ?: "that song"}"
                    if (made.hasNextMediaItem()) {
                        made.seekToNextMediaItem()
                        made.prepare()
                        made.play()
                    }
                }
            })
            player = made
        }
        queue = list
        val start = first?.let(list::indexOf)?.takeIf { it >= 0 } ?: 0
        index = start
        p.setMediaItems(list.map(::item), start, 0)
        p.prepare()
        p.play()
        BackgroundAudio.start(app, p)
    }

    private fun item(theme: AnimeTheme) = MediaItem.Builder()
        .setUri(theme.audioUrl)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(theme.song ?: theme.label)
                .setArtist(listOf(theme.artists.joinToString(", "), "${theme.anime} ${theme.slug}").filter { it.isNotBlank() }.joinToString(" · "))
                .build(),
        )
        .build()

    fun toggle(context: Context) {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            // An episode's sound-only mode may have taken the notification meanwhile.
            BackgroundAudio.start(context.applicationContext, p)
            p.play()
        }
    }

    fun next() {
        player?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        player?.seekToPreviousMediaItem()
    }

    fun jump(to: Int) {
        val p = player ?: return
        if (to in queue.indices) {
            p.seekTo(to, 0)
            p.play()
        }
    }

    /** Where the song is and how long it is, in ms (0 when unknown). */
    fun place(): Pair<Long, Long> = player?.let { it.currentPosition to it.duration.coerceAtLeast(0) } ?: (0L to 0L)

    /** An episode is opening, or a quiz round is about to play: the radio waits. */
    fun pause() {
        player?.pause()
    }

    fun stop(context: Context) {
        val p = player ?: return
        if (BackgroundAudio.player === p) BackgroundAudio.stop(context.applicationContext)
        p.release()
        player = null
        queue = emptyList()
        playing = false
        problem = null
    }
}

/** The bar at the bottom while the radio plays: what's on, pause, and stop. */
@Composable
internal fun ThemeMiniBar(modifier: Modifier = Modifier) {
    val now = ThemeRadio.current ?: return
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(lookShape(10.dp))
            .background(theme.panel)
            .border(1.5.dp, theme.primary.copy(alpha = 0.7f), lookShape(10.dp))
            .clickable { AniListLinkRequest.route = Route.ThemeRadio }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        BasicText("♫", style = TextStyle(color = theme.primary, fontSize = 16.sp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            BasicText(now.song ?: now.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 13.5.sp, fontWeight = FontWeight.Bold))
            BasicText("${now.anime} · ${now.slug}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.5.sp))
        }
        SmallButton(if (ThemeRadio.playing) "❚❚" else "▶", if (ThemeRadio.playing) "Pause" else "Play") { ThemeRadio.toggle(context) }
        SmallButton("✕", "Stop the radio") { ThemeRadio.stop(context) }
    }
}

@Composable
private fun SmallButton(text: String, label: String, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
    ) {
        BasicText(text, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold))
    }
}

/** The openings and endings on a title's page; the ones after where you are stay hidden in spoiler-free mode. */
@Composable
internal fun MediaThemes(mediaId: Int) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val themes by produceState<List<AnimeTheme>?>(null, mediaId) {
        value = withContext(Dispatchers.IO) {
            try {
                Themes.shared(context).forMedia(listOf(mediaId))[mediaId].orEmpty()
            } catch (_: IOException) {
                emptyList()
            }
        }
    }
    val list = themes?.takeIf { it.isNotEmpty() } ?: return
    val hiding = SpoilerPrefs.on(context, mediaId)
    val seen = ui.entryFor(mediaId)?.let { if (it.status == ListStatus.COMPLETED) Int.MAX_VALUE else it.progress } ?: 0
    val shown = remember { mutableStateMapOf<String, Boolean>() }
    var all by remember(mediaId) { mutableStateOf(false) }
    SectionTitle("Openings & endings")
    Column(Modifier.padding(horizontal = 16.dp)) {
        list.forEachIndexed { i, song ->
            if (!all && i >= SHOWN_THEMES) return@forEachIndexed
            val hidden = hiding && song.spoils(seen) && shown[song.slug] != true
            val now = ThemeRadio.current == song
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(lookShape(8.dp))
                    .background(if (now) theme.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                    .combinedClickable(
                        onClick = { if (hidden) shown[song.slug] = true else ThemeRadio.play(context, list.filter { !(hiding && it.spoils(seen)) || shown[it.slug] == true }, song) },
                        onLongClick = { song.videoUrl?.let(context::openLink) },
                    )
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            ) {
                BasicText(
                    song.slug,
                    style = NumberStyle(color = if (song.type == "OP") theme.primary else theme.secondary, fontSize = 12.sp, bold = true),
                    modifier = Modifier.width(44.dp),
                )
                Column(Modifier.weight(1f)) {
                    if (hidden) {
                        BasicText("From episode ${song.firstEpisode ?: "?"} · after where you are", style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 13.sp))
                        BasicText("Tap to show", style = TextStyle(color = theme.foreground.copy(alpha = 0.45f), fontSize = 11.5.sp))
                    } else {
                        BasicText(song.song ?: song.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                        BasicText(
                            listOfNotNull(song.artists.joinToString(", ").takeIf { it.isNotBlank() }, song.episodes?.let { "episodes $it" }).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.5.sp),
                        )
                    }
                }
                if (!hidden && song.audioUrl != null) {
                    BasicText(if (now && ThemeRadio.playing) "❚❚" else "▶", style = TextStyle(color = theme.primary, fontSize = 14.sp))
                }
            }
        }
        if (!all && list.size > SHOWN_THEMES) {
            BasicText(
                "Show all ${list.size} ▾",
                style = TextStyle(color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.clip(lookShape(8.dp)).clickable { all = true }.padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
        Hint("Tap to listen · hold for the video", Modifier.padding(top = 2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            PillButton("♫ Theme radio", onClick = { ui.navigator.open(Route.ThemeRadio) }, filled = false)
            PillButton("? Guess the opening", onClick = { ui.navigator.open(Route.ThemeQuiz) }, filled = false)
        }
    }
}

/** Which of your titles a game or the radio draws from. */
private enum class ThemeSource(val label: String) { Completed("Completed"), Watching("Watching"), All("All my list") }

private fun ListEntry.counts(source: ThemeSource) = when (source) {
    ThemeSource.Completed -> status == ListStatus.COMPLETED
    ThemeSource.Watching -> status == ListStatus.CURRENT || status == ListStatus.REPEATING
    ThemeSource.All -> status != ListStatus.PLANNING
}

/** Themes of the titles on your anime list, the most recently updated first; spoilers left out where you hide them. */
private suspend fun listThemes(context: Context, entries: Collection<ListEntry>, limit: Int = 150): Map<Int, List<AnimeTheme>> {
    val anime = entries.filter { it.media.kind == MediaKind.Anime && it.status != ListStatus.PLANNING }
        .sortedByDescending { it.updatedAt ?: 0 }
        .take(limit)
    val found = withContext(Dispatchers.IO) {
        try {
            Themes.shared(context).forMedia(anime.map { it.media.id })
        } catch (_: IOException) {
            emptyMap()
        }
    }
    return anime.associate { entry ->
        val seen = if (entry.status == ListStatus.COMPLETED) Int.MAX_VALUE else entry.progress
        val hide = SpoilerPrefs.on(context, entry.media.id)
        entry.media.id to found[entry.media.id].orEmpty().filter { it.audioUrl != null && !(hide && it.spoils(seen)) }
    }
}

@Composable
internal fun ThemeRadioPage(retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    var source by retained.get("source") { mutableStateOf(ThemeSource.Completed) }
    var openings by retained.get("op") { mutableStateOf(true) }
    var endings by retained.get("ed") { mutableStateOf(true) }
    val entries = ui.onList.entries
    val all by produceState<Map<Int, List<AnimeTheme>>?>(null, entries.size) { value = listThemes(context, entries.values) }
    val pool = remember(all, source, openings, endings) {
        val byId = entries
        all.orEmpty().filterKeys { id -> byId[id]?.counts(source) == true }.values.flatten()
            .filter { (openings && it.type == "OP") || (endings && it.type == "ED") }
    }
    var place by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(Unit) {
        while (true) {
            place = ThemeRadio.place()
            delay(500)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Theme radio") { ui.navigator.back() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            ThemeSource.entries.forEach { option -> FilterChip(option.label, source == option) { source = option } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            FilterChip("OP", openings) { openings = !openings || !endings }
            FilterChip("ED", endings) { endings = !endings || !openings }
        }
        val now = ThemeRadio.current
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(lookShape(12.dp))
                .border(1.5.dp, theme.primary, lookShape(12.dp))
                .background(theme.surface.copy(alpha = 0.6f))
                .padding(16.dp)
                .lookAlive(Alive.Block),
        ) {
            if (now == null) {
                BasicText(
                    when {
                        all == null -> "Finding the songs of your list..."
                        pool.isEmpty() -> "No songs found for these titles"
                        else -> "${pool.size} songs from ${pool.map { it.mediaId }.distinct().size} titles"
                    },
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 14.sp),
                )
                Spacer(Modifier.height(10.dp))
                PillButton("⇄ Shuffle and play", onClick = { ThemeRadio.play(context, pool.shuffled()) }, enabled = pool.isNotEmpty())
            } else {
                BasicText("NOW · ${now.slug}", style = NumberStyle(color = theme.accent, fontSize = 11.sp, bold = true))
                BasicText(now.song ?: now.label, style = TitleStyle(color = theme.foreground, fontSize = 22.sp))
                if (now.artists.isNotEmpty()) BasicText(now.artists.joinToString(", "), style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp))
                BasicText(now.anime, style = TextStyle(color = theme.primary, fontSize = 13.sp), modifier = Modifier.clickable { ui.navigator.open(Route.Media(now.mediaId)) })
                Spacer(Modifier.height(10.dp))
                val (at, length) = place
                Box(Modifier.fillMaxWidth().height(4.dp).background(theme.foreground.copy(alpha = 0.15f))) {
                    if (length > 0) Box(Modifier.fillMaxWidth((at.toFloat() / length).coerceIn(0f, 1f)).height(4.dp).background(theme.primary))
                }
                Row {
                    BasicText(clock(at), style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp))
                    Spacer(Modifier.weight(1f))
                    BasicText(if (length > 0) clock(length) else "", style = NumberStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 11.sp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallButton("⇄", "Shuffle again") { ThemeRadio.play(context, pool.shuffled()) }
                    SmallButton("⏮", "Previous") { ThemeRadio.previous() }
                    SmallButton(if (ThemeRadio.playing) "❚❚" else "▶", "Play or pause") { ThemeRadio.toggle(context) }
                    SmallButton("⏭", "Next") { ThemeRadio.next() }
                    now.videoUrl?.let { url -> SmallButton("▣", "Watch the video") { context.openLink(url) } }
                }
                ThemeRadio.problem?.let { BasicText(it, style = TextStyle(color = theme.error, fontSize = 12.sp)) }
            }
        }
        if (now != null) {
            SectionTitle("Up next")
            val queue = ThemeRadio.queue
            LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                itemsIndexed(queue.drop(ThemeRadio.index + 1).take(30)) { i, song ->
                    BasicText(
                        "${song.song ?: song.label} · ${song.anime} ${song.slug}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().clickable { ThemeRadio.jump(ThemeRadio.index + 1 + i) }.padding(horizontal = 20.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** Best results in Guess the opening, kept on the phone. */
internal object QuizScores {
    private const val FILE = "theme_quiz"

    fun best(context: Context): Int = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("best_right", 0)

    fun bestPoints(context: Context): Int = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("best_points", 0)

    fun save(context: Context, right: Int, points: Int) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("best_right", maxOf(right, prefs.getInt("best_right", 0)))
            .putInt("best_points", maxOf(points, prefs.getInt("best_points", 0)))
            .apply()
    }
}

private data class QuizRound(val answer: AnimeTheme, val options: List<Pair<Int, String>>)

private const val ROUNDS = 10
private const val SHOWN_THEMES = 6
private const val ROUND_SECONDS = 15
/** About where the chorus comes in an opening. */
private const val CHORUS_MS = 40_000L

private fun rounds(all: Map<Int, List<AnimeTheme>>, names: Map<Int, String>): List<QuizRound> {
    val openings = all.mapValues { (_, list) -> list.filter { it.type == "OP" } }.filterValues { it.isNotEmpty() }
    val titles = openings.keys.filter { names[it] != null }.shuffled()
    if (titles.size < 4) return emptyList()
    return titles.take(ROUNDS).map { id ->
        val answer = openings.getValue(id).random()
        val others = titles.filter { it != id && names[it] != names[id] }.shuffled().take(3)
        QuizRound(answer, (others + id).shuffled().map { it to names.getValue(it) })
    }
}

@Composable
internal fun ThemeQuizPage(retained: Retained) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val feedbackStyle = momentStyle
    val entries = ui.onList.entries
    val all by produceState<Map<Int, List<AnimeTheme>>?>(null, entries.size) {
        value = listThemes(context, entries.values.filter { it.status == ListStatus.COMPLETED || it.status == ListStatus.CURRENT || it.status == ListStatus.REPEATING })
    }
    var game by retained.get("game") { mutableIntStateOf(0) }
    val rounds = remember(all, game) { all?.let { found -> rounds(found, found.keys.associateWith { entries[it]?.media?.title?.display ?: "" }.filterValues { it.isNotBlank() }) } }
    var round by retained.get("round") { mutableIntStateOf(0) }
    var points by retained.get("points") { mutableIntStateOf(0) }
    var right by retained.get("right") { mutableIntStateOf(0) }
    var streak by retained.get("streak") { mutableIntStateOf(0) }
    var bestStreak by retained.get("bestStreak") { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var gained by remember { mutableIntStateOf(0) }
    var left by remember { mutableIntStateOf(ROUND_SECONDS) }
    var startedAt by remember { mutableLongStateOf(0L) }
    val player = remember {
        ExoPlayer.Builder(context).build()
    }
    DisposableEffect(player) {
        ThemeRadio.pause()
        onDispose { player.release() }
    }
    val current = rounds?.getOrNull(round)
    // Each round: the song from about its chorus, 15 seconds to answer.
    LaunchedEffect(current) {
        val song = current?.answer ?: return@LaunchedEffect
        picked = null
        player.setMediaItem(MediaItem.fromUri(song.audioUrl!!))
        player.prepare()
        player.seekTo(CHORUS_MS)
        player.play()
        // The clock starts once the song is heard, not while it loads.
        left = ROUND_SECONDS
        kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!player.isPlaying) delay(100) }
        startedAt = System.currentTimeMillis()
        left = ROUND_SECONDS
        while (left > 0 && picked == null) {
            delay(1_000)
            left--
        }
        if (picked == null) {
            picked = -1
            streak = 0
            gained = 0
        }
    }
    LaunchedEffect(picked) { if (picked != null) delay(4_000).also { player.pause() } }

    fun answer(id: Int) {
        if (picked != null || current == null) return
        picked = id
        if (id == current.answer.mediaId) {
            val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt().coerceIn(0, ROUND_SECONDS)
            streak++
            bestStreak = maxOf(bestStreak, streak)
            gained = (300 + 30 * (ROUND_SECONDS - seconds)) * minOf(streak, 3)
            points += gained
            right++
            feedbackStyle?.let { style ->
                LookSounds.done(style)
                LookHaptics.moment(view, style)
            }
        } else {
            streak = 0
            gained = 0
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Guess the opening") { ui.navigator.back() }
        when {
            rounds == null -> LookLoading("Finding the openings of your list...", Modifier.padding(20.dp), color = theme.foreground.copy(alpha = 0.75f))
            rounds.isEmpty() -> Hint("Needs at least 4 Completed or Watching titles with openings on AnimeThemes.")
            current == null -> {
                // The end: the score, a card to share in the look, and another go.
                LaunchedEffect(game) { QuizScores.save(context, right, points) }
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.Start) {
                    BasicText("GUESS THE OPENING", style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true))
                    BasicText("$right/${rounds.size}", style = TitleStyle(color = theme.foreground, fontSize = 64.sp), modifier = Modifier.lookAlive(Alive.Stats))
                    BasicText("%,d pts · best streak %d".format(points, bestStreak), style = NumberStyle(color = theme.primary, fontSize = 15.sp, bold = true))
                    BasicText("Your best: ${maxOf(QuizScores.best(context), right)}/10 · %,d pts".format(maxOf(QuizScores.bestPoints(context), points)), style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp))
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Share", onClick = {
                            scope.launch {
                                ShareImage.share(
                                    context, theme,
                                    ShareCard(
                                        kicker = "Guess the opening",
                                        title = "Can you beat it?",
                                        big = "$right/${rounds.size}",
                                        bigLabel = "%,d pts · best streak %d".format(points, bestStreak),
                                    ),
                                    "I got $right/${rounds.size} in Guess the opening on aniliphy. Can you beat it?",
                                )
                            }
                        })
                        PillButton("Play again", onClick = {
                            round = 0; points = 0; right = 0; streak = 0; bestStreak = 0; game++
                        }, filled = false)
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                    BasicText("ROUND ${round + 1}/${rounds.size}", style = NumberStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp, bold = true))
                    Spacer(Modifier.weight(1f))
                    BasicText("%,d pts".format(points), style = NumberStyle(color = theme.primary, fontSize = 13.sp, bold = true))
                    if (streak >= 2) BasicText("  ×${minOf(streak, 3)} streak!", style = NumberStyle(color = theme.accent, fontSize = 13.sp, bold = true))
                }
                Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(6.dp).background(theme.foreground.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(left / ROUND_SECONDS.toFloat()).height(6.dp).background(if (left <= 4) theme.error else theme.primary))
                }
                BasicText(
                    if (picked == null) "♫ Listen… which one is it? (${left}s)" else "",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 14.sp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 20.dp)) {
                    current.options.forEach { (id, name) ->
                        val isAnswer = id == current.answer.mediaId
                        val color = when {
                            picked == null -> theme.foreground.copy(alpha = 0.35f)
                            isAnswer -> theme.success
                            id == picked -> theme.error
                            else -> theme.foreground.copy(alpha = 0.2f)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(lookShape(10.dp))
                                .border(1.5.dp, color, lookShape(10.dp))
                                .background(if (picked != null && isAnswer) theme.success.copy(alpha = 0.15f) else theme.surface.copy(alpha = 0.5f))
                                .clickable(enabled = picked == null) { answer(id) }
                                .padding(horizontal = 14.dp, vertical = 14.dp)
                                .alpha(if (picked != null && !isAnswer && id != picked) 0.6f else 1f),
                        ) {
                            BasicText(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
                            if (picked != null && isAnswer) BasicText(if (picked == id) "✓ +$gained" else "✓", style = NumberStyle(color = theme.success, fontSize = 13.sp, bold = true))
                            if (picked == id && !isAnswer) BasicText("✗", style = NumberStyle(color = theme.error, fontSize = 13.sp, bold = true))
                        }
                    }
                }
                if (picked != null) {
                    val song = current.answer
                    BasicText(
                        "It was ${listOfNotNull(song.song, song.artists.joinToString(", ").takeIf { it.isNotBlank() }).joinToString(" · ")} · ${song.anime} ${song.slug}",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    PillButton(if (round + 1 < rounds.size) "Next ›" else "See the score", onClick = {
                        player.pause()
                        round++
                    }, modifier = Modifier.padding(horizontal = 20.dp))
                }
            }
        }
    }
}
