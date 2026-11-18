package com.aniliphy.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.AniListException
import com.aniliphy.anime.AnimeRules
import com.aniliphy.anime.AnimeSettings
import com.aniliphy.work.TravelMode
import com.aniliphy.work.TravelPick
import com.aniliphy.work.TravelTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings › Storage › Travel mode: gets what you're watching and reading ready for a trip without internet. */
@Composable
fun TravelScreen(onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val app = context.applicationContext
    val anime = LocalAnime.current
    val manga = LocalManga.current
    val scope = rememberCoroutineScope()
    val services = remember { AniListServices.get(app) }
    val quality = remember { AnimeSettings.get(app).quality }

    var picks by remember { mutableStateOf<List<TravelPick>?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val chosen = remember { mutableStateMapOf<String, Boolean>() }
    val ahead = remember { mutableStateMapOf<String, Int>() }
    var pages by remember { mutableStateOf(true) }
    var preparing by remember { mutableStateOf(false) }
    var trip by remember { mutableStateOf(TravelMode.trip(app)) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        val user = services.session.userId
        if (user == null) {
            problem = "Log in to AniList first: travel mode starts from what you're watching and reading."
            return@LaunchedEffect
        }
        try {
            val watching = services.api.watching(user, refresh = context.online()).value
            val reading = services.api.reading(user, refresh = context.online()).value
            val list = withContext(Dispatchers.IO) { TravelMode.picks(app, watching, reading) }
            list.forEach { p ->
                if (p.key !in chosen) chosen[p.key] = p.source != null && p.left != 0
                if (p.key !in ahead) ahead[p.key] = if (p.anime) TravelMode.ANIME_AHEAD else TravelMode.MANGA_AHEAD
            }
            picks = list
        } catch (e: AniListException) {
            problem = "Couldn't read your lists: ${e.message}"
        }
    }

    // Downloaded so far, per title, for the bars while it works and the "delete what I watched" button.
    val saved by produceState<Map<String, List<Pair<Int?, Long>>>>(emptyMap(), AnimeDownloads.version, MangaDownloads.version, reload) {
        value = withContext(Dispatchers.IO) {
            val shows = anime.offline.titles().map { (id, show) ->
                "a$id" to show.episodes.map { AnimeRules.aniListEpisode(it.episode, null) to it.bytes }
            }
            val series = manga.offline.titles().map { (id, meta) ->
                "m$id" to meta.downloaded.map { (chapterId, c) -> c.number?.toInt() to manga.offline.chapterBytes(id, chapterId) }
            }
            (shows + series).toMap()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader(title = "Travel mode", onBack = onBack)

        val list = picks
        trip?.let { t ->
            if (list != null) WelcomeBack(t, list, saved, onDelete = { ids ->
                scope.launch(Dispatchers.IO) {
                    val shows = anime.offline.titles().toMap()
                    val series = manga.offline.titles().toMap()
                    ids.forEach { (key, number) ->
                        val mediaId = key.drop(1).toInt()
                        if (key.startsWith("a")) {
                            shows[mediaId]?.episodes?.filter { e -> AnimeRules.aniListEpisode(e.episode, null) == number }
                                ?.forEach { e -> AnimeDownloads.delete(anime.offline, mediaId, e.episode.id) }
                        } else {
                            series[mediaId]?.downloaded?.filter { c -> c.value.number?.toInt() == number }
                                ?.forEach { c -> MangaDownloads.delete(manga.offline, mediaId, c.key) }
                        }
                    }
                }
            }, onDone = {
                TravelMode.endTrip(app)
                trip = null
            })
        }

        when {
            problem != null -> Hint(problem!!, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            list == null -> Hint("Loading…", Modifier.padding(20.dp))
            list.isEmpty() -> Empty("Nothing in Watching or Reading on AniList.", Modifier.padding(20.dp))
            else -> {
                Hint(
                    "Picked for you: everything you're watching and reading that has a site linked. Already downloaded episodes and chapters aren't fetched again.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                listOf(true, false).forEach { isAnime ->
                    val group = list.filter { it.anime == isAnime }
                    if (group.isEmpty()) return@forEach
                    val on = group.count { chosen[it.key] == true }
                    SectionTitle(if (isAnime) "Anime" else "Manga")
                    Hint(
                        "$on of ${group.size} picked · ${if (isAnime) "episodes" else "chapters"} ahead on the right",
                        Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    )
                    group.forEach { p ->
                        TravelRow(
                            p,
                            chosen = chosen[p.key] == true,
                            ahead = ahead[p.key] ?: 1,
                            saved = saved[p.key]?.count { (n, _) -> n != null && n > p.progress } ?: 0,
                            onToggle = { chosen[p.key] = chosen[p.key] != true },
                            onAhead = { ahead[p.key] = it },
                        )
                    }
                }

                SectionTitle("Also")
                SwitchRow(
                    label = "AniList pages and covers",
                    description = "The title pages, your lists and the covers, to look through offline",
                    checked = pages,
                    onChange = { pages = it },
                )

                val picked = list.filter { chosen[it.key] == true && it.source != null }.map { it to (ahead[it.key] ?: 1) }
                val estimate = TravelMode.estimate(picked, quality, pages)
                val free = remember(reload) { app.filesDir.usableSpace }
                Estimate(estimate, free, quality.label)
                Hint(
                    "Quality comes from Anime settings › Quality. Downloads follow the Wi-Fi only setting, like the others.",
                    Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
                PillButton(
                    if (preparing) "Getting ready…" else "Get everything ready",
                    onClick = {
                        preparing = true
                        scope.launch {
                            val queued = withContext(Dispatchers.IO) { TravelMode.prepare(app, picked, pages) }
                            trip = TravelMode.trip(app)
                            preparing = false
                            reload++
                            context.showToast(if (queued == 0) "Nothing new to download" else "Downloading for $queued title${if (queued == 1) "" else "s"}")
                        }
                    },
                    enabled = picked.isNotEmpty() && !preparing && estimate.total < free,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (estimate.total >= free) Hint("Not enough free space: pick fewer, or fewer ahead.", Modifier.padding(horizontal = 20.dp), color = theme.error)
                DownloadQueueStatus()
            }
        }
    }
}

@Composable
private fun TravelRow(p: TravelPick, chosen: Boolean, ahead: Int, saved: Int, onToggle: () -> Unit, onAhead: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    val usable = p.source != null
    val dim = if (usable) 1f else 0.45f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = usable, role = Role.Checkbox, onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        CheckMark(chosen && usable)
        Spacer(Modifier.width(12.dp))
        Cover(p.cover, Modifier.size(38.dp, 54.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = dim), fontSize = 15.sp, fontWeight = FontWeight.Bold))
            val unit = if (p.anime) "episode" else "chapter"
            val detail = when {
                !usable -> "No site linked yet · open it once to link"
                else -> listOfNotNull(
                    "On $unit ${p.progress}" + (p.total?.let { " of $it" } ?: ""),
                    p.released?.takeIf { p.total == null || it < p.total }?.let { "$it out" },
                    saved.takeIf { it > 0 }?.let { "$it saved" },
                ).joinToString(" · ")
            }
            BasicText(detail, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.65f * dim), fontSize = 12.sp))
        }
        if (usable && chosen) {
            Spacer(Modifier.width(8.dp))
            val most = (p.left ?: TravelMode.MAX_AHEAD).coerceIn(1, TravelMode.MAX_AHEAD)
            Stepper(ahead.coerceAtMost(most), 1, most, onAhead)
        }
    }
}

@Composable
private fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    val shape = newLookShape(6.dp)
    Row(Modifier.border(1.5.dp, theme.foreground.copy(alpha = 0.25f), shape).clip(shape), verticalAlignment = Alignment.CenterVertically) {
        val style = TextStyle(color = theme.primary, fontSize = 15.sp, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold)
        BasicText("−", style = style.copy(color = if (value > min) theme.primary else theme.foreground.copy(alpha = 0.3f)),
            modifier = Modifier.clickable(enabled = value > min) { onChange(value - 1) }.padding(horizontal = 10.dp, vertical = 6.dp))
        BasicText("+$value", style = TextStyle(color = theme.foreground, fontSize = 13.sp, fontFamily = JetBrainsMono, textAlign = TextAlign.Center), modifier = Modifier.widthIn(min = 36.dp))
        BasicText("+", style = style.copy(color = if (value < max) theme.primary else theme.foreground.copy(alpha = 0.3f)),
            modifier = Modifier.clickable(enabled = value < max) { onChange(value + 1) }.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun Estimate(e: TravelMode.Estimate, free: Long, quality: String) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val mono = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.sp, fontFamily = JetBrainsMono)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .border(1.5.dp, theme.foreground.copy(alpha = 0.25f), newLookShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicText("≈ ${size(e.total)}", style = TextStyle(color = theme.primary, fontSize = 20.sp, fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(4.dp))
        BasicText("${e.episodes} episode${if (e.episodes == 1) "" else "s"} · $quality · ≈ ${size(e.episodeBytes)}", style = mono)
        BasicText("${e.chapters} chapter${if (e.chapters == 1) "" else "s"} · ≈ ${size(e.chapterBytes)}", style = mono)
        if (e.pageBytes > 0) BasicText("pages and covers · ≈ ${size(e.pageBytes)}", style = mono)
        BasicText("free on the phone: ${size(free)}", style = mono)
        Spacer(Modifier.height(6.dp))
        // The share of the free space this takes: downloads already there are not counted.
        val share = if (free > 0) (e.total.toFloat() / free).coerceIn(0f, 1f) else 1f
        Box(Modifier.fillMaxWidth().height(8.dp).background(theme.foreground.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(share).height(8.dp).background(if (share >= 1f) theme.error else theme.primary))
        }
    }
}

@Composable
private fun WelcomeBack(
    trip: TravelTrip,
    now: List<TravelPick>,
    saved: Map<String, List<Pair<Int?, Long>>>,
    onDelete: (List<Pair<String, Int>>) -> Unit,
    onDone: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val services = remember { AniListServices.get(context.applicationContext) }
    val pendingVersion by services.pendingVersion.collectAsState()
    val waiting = remember(pendingVersion) { services.pending.all().size + services.pending.conflicts().size }
    val progress = now.associate { it.key to it.progress }
    val back = TravelMode.back(trip, progress, waiting)
    // Downloads of the trip's titles at or before where you are now.
    val watched = trip.items.flatMap { item ->
        val key = (if (item.anime) "a" else "m") + item.mediaId
        val at = progress[key] ?: item.startProgress
        saved[key].orEmpty().filter { (n, _) -> n != null && n <= at }.map { (n, bytes) -> Triple(key, n!!, bytes) }
    }
    val bytes = watched.sumOf { it.third }
    val row = TextStyle(color = theme.foreground, fontSize = 14.sp)
    val value = TextStyle(color = theme.primary, fontSize = 14.sp, fontFamily = JetBrainsMono)
    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(newLookShape(8.dp))
            .background(theme.surface)
            .border(1.5.dp, theme.foreground.copy(alpha = 0.25f), newLookShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        val started = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(trip.startedAt))
        BasicText(if (back.episodes + back.chapters > 0) "Welcome back" else "Ready for the trip", style = TextStyle(color = theme.foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold))
        BasicText("Got ready on $started · ${trip.items.size} title${if (trip.items.size == 1) "" else "s"}", style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = JetBrainsMono))
        Spacer(Modifier.height(8.dp))
        @Composable
        fun kv(label: String, v: String) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            BasicText(label, style = row, modifier = Modifier.weight(1f))
            BasicText(v, style = value)
        }
        kv("Watched since", "${back.episodes} episode${if (back.episodes == 1) "" else "s"}")
        kv("Read since", "${back.chapters} chapter${if (back.chapters == 1) "" else "s"}")
        kv("Waiting for AniList", if (back.waiting == 0) "nothing" else "${back.waiting} · Activity")
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (watched.isNotEmpty()) {
                PillButton("Delete what I watched (${Formatter.formatShortFileSize(context, bytes)})", { onDelete(watched.map { it.first to it.second }) }, filled = false)
                Spacer(Modifier.width(8.dp))
            }
            PillButton("Done", onDone, filled = watched.isEmpty())
        }
    }
}
