package com.aniliphy.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.aniliphy.japanese.AnkiExport
import com.aniliphy.japanese.Cue
import com.aniliphy.japanese.DualLayout
import com.aniliphy.japanese.JapaneseDictionary
import com.aniliphy.japanese.JapaneseSettings
import com.aniliphy.japanese.Kana
import com.aniliphy.japanese.LineCache
import com.aniliphy.japanese.LinePiece
import com.aniliphy.japanese.Lookup
import com.aniliphy.japanese.SavedWord
import com.aniliphy.japanese.SubtitleCues
import com.aniliphy.japanese.WordExport
import com.aniliphy.japanese.WordMatch
import com.aniliphy.japanese.WordStore
import com.aniliphy.source.SubtitleTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal fun wordStore(context: android.content.Context) = WordStore(File(context.filesDir, "japanese").apply { mkdirs() })

/** The Japanese file among an episode's own subtitle files (from the phone or Jimaku): its label and lines. */
internal fun japaneseFile(tracks: List<SubtitleTrack>): Pair<String, List<Cue>>? {
    val files = tracks.filter { it.url.startsWith("file:") }.sortedByDescending { it.language == "ja" }
    for (track in files) {
        val file = File(Uri.parse(track.url).path ?: continue)
        val cues = runCatching { SubtitleCues.parse(file.name, file.readText()) }.getOrNull() ?: continue
        if (track.language == "ja" || SubtitleCues.japanese(cues)) return track.label to cues
    }
    return null
}

private val lineShadow = Shadow(Color.Black, blurRadius = 7f)

/** What was tapped: the line and where in it. */
private data class Tapped(val line: String, val start: Int, val cue: Cue)

/**
 * The Japanese line over the video, each word tappable: the video pauses and the word's card opens. With the
 * dictionary missing the line still shows (and the card says where to get it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BoxScope.JapaneseOverlay(
    player: Player,
    cues: List<Cue>,
    delayMs: Long,
    sizeSp: Int,
    layout: DualLayout,
    controlsShown: Boolean,
    view: () -> PlayerView?,
    mediaId: Int,
    title: String,
    episode: String,
) {
    val context = LocalContext.current
    val settings = remember { JapaneseSettings.get(context).also { JapaneseDictionary.init(context) } }
    val now by produceState<Cue?>(null, cues, delayMs) {
        while (true) {
            value = SubtitleCues.at(cues, player.currentPosition, delayMs)
            delay(120)
        }
    }
    var tapped by remember { mutableStateOf<Tapped?>(null) }
    val scope = rememberCoroutineScope()
    val cue = tapped?.cue ?: now
    val dictionary = JapaneseDictionary.version
    val lines = cue?.text?.split('\n').orEmpty().filter { it.isNotBlank() }
    val pieces by produceState<List<List<LinePiece>>>(lines.map { listOf(LinePiece(it, 0, null)) }, cue?.text, dictionary) {
        value = lines.map { listOf(LinePiece(it, 0, null)) }
        if (!JapaneseDictionary.ready) return@produceState
        value = withContext(Dispatchers.IO) {
            lines.map { line -> LineCache.get(line) ?: Lookup.pieces(line, JapaneseDictionary).also { LineCache.put(line, it) } }
        }
    }
    if (cue != null) {
        val top = layout == DualLayout.Split
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp)
                .padding(top = if (top) (if (controlsShown) 70.dp else 14.dp) else 0.dp, bottom = if (top) 0.dp else if (layout == DualLayout.Stacked) 76.dp else 28.dp),
        ) {
            pieces.forEach { line ->
                FlowRow(horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.Bottom, modifier = Modifier.fillMaxWidth()) {
                    line.forEach { piece -> LinePieceText(piece, sizeSp, settings.furigana, piece == tappedPiece(tapped, line)) { start ->
                        if (settings.pauseOnTap) player.pause()
                        tapped = Tapped(lines[pieces.indexOf(line)], start, cue)
                    } }
                }
            }
        }
    }
    tapped?.let { t ->
        BackHandler { tapped = null }
        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { tapped = null })
        WordCard(
            t,
            modifier = Modifier.align(if (layout == DualLayout.Split) Alignment.BottomCenter else Alignment.TopCenter),
            onSave = { match ->
                val position = player.currentPosition
                val frameView = view()
                scope.launch {
                    val bitmap = FrameGrab.copy(frameView, width = 640, subtitles = false)
                    val saved = withContext(Dispatchers.IO) {
                        val store = wordStore(context)
                        val name = bitmap?.let { b ->
                            store.frames.mkdirs()
                            "w${System.currentTimeMillis()}.jpg".also { n -> File(store.frames, n).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 82, it) } }
                        }
                        store.add(
                            SavedWord(
                                id = UUID.randomUUID().toString(),
                                entryId = match.entry.id,
                                word = match.form,
                                reading = match.reading,
                                meanings = match.entry.senses.take(4).map { it.gloss.take(4).joinToString("; ") },
                                sentence = t.line,
                                surface = match.surface,
                                mediaId = mediaId,
                                title = title,
                                episode = episode,
                                positionMs = position,
                                frame = name,
                                addedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                    context.showToast("Saved ${match.form} · ${saved.size} words")
                }
            },
            onAgain = {
                player.seekTo((t.cue.startMs).coerceAtLeast(0))
                tapped = null
                player.play()
            },
            onResume = {
                tapped = null
                player.play()
            },
        )
    }
}

private fun tappedPiece(tapped: Tapped?, line: List<LinePiece>): LinePiece? =
    tapped?.let { t -> line.firstOrNull { it.match != null && it.start == t.start && line.joinToString("") { p -> p.text } == t.line } }

@Composable
private fun LinePieceText(piece: LinePiece, sizeSp: Int, furigana: Boolean, selected: Boolean, onTap: (Int) -> Unit) {
    val theme = LocalAppTheme.current
    val style = TextStyle(color = Color.White, fontSize = sizeSp.sp)
    val small = TextStyle(color = Color(0xFFFFE9A8), fontSize = (sizeSp * 0.48f).sp, shadow = lineShadow, textAlign = TextAlign.Center)
    val match = piece.match
    val wordChars = piece.text.any { Kana.isWordChar(it) }
    val modifier = Modifier
        .then(if (selected) Modifier.background(theme.primary.copy(alpha = 0.45f)) else Modifier)
        .then(if (wordChars) Modifier.clickable { onTap(piece.start) } else Modifier)
        .padding(horizontal = 1.dp)
    val ruby = if (match != null && furigana) Lookup.ruby(piece.text, match.form, match.reading) else null
    if (ruby == null || ruby.all { it.reading == null }) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (furigana) BasicText(" ", style = small)
            Outlined(piece.text, style)
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        ruby.forEach { part ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Outlined(part.reading ?: " ", small, stroke = 3f)
                Outlined(part.text, style)
            }
        }
    }
}

/** White text with a black edge, like the player's own subtitles, readable on light scenes. */
@Composable
private fun Outlined(text: String, style: TextStyle, stroke: Float = 5f) {
    Box {
        BasicText(text, maxLines = 1, softWrap = false, style = style.copy(color = Color.Black, shadow = null, drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round)))
        BasicText(text, maxLines = 1, softWrap = false, style = style.copy(shadow = null))
    }
}

/** The tapped word: its forms at that spot, reading, what conjugation was undone, meanings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordCard(t: Tapped, modifier: Modifier, onSave: (WordMatch) -> Unit, onAgain: () -> Unit, onResume: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val ready = JapaneseDictionary.ready
    val matches by produceState<List<WordMatch>?>(null, t, JapaneseDictionary.version) {
        value = if (!ready) emptyList() else withContext(Dispatchers.IO) { Lookup.at(t.line, t.start, JapaneseDictionary).distinctBy { it.entry.id }.take(8) }
    }
    var pick by remember(t) { mutableIntStateOf(0) }
    Column(
        modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .heightIn(max = 330.dp)
            .background(theme.surface.copy(alpha = 0.97f), lookShape(10.dp))
            .border(1.5.dp, theme.primary, lookShape(10.dp))
            .clickable(interactionSource = null, indication = null) {}
            .padding(14.dp),
    ) {
        val list = matches
        when {
            !ready -> {
                BasicText("No dictionary yet", style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Hint("Get JMdict (about 12 MB) to look words up. It stays on the phone.", Modifier.padding(vertical = 6.dp))
                val job = JapaneseDictionary.job
                if (job != null) DictionaryProgress(job) else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Download", onClick = { JapaneseDictionary.download(context, JapaneseDictionary.Part.Words) })
                    PillButton("▶ Resume", onClick = onResume, filled = false)
                }
                return@Column
            }
            list == null -> {
                Hint("Looking it up…")
                return@Column
            }
            list.isEmpty() -> {
                BasicText(t.line.substring(t.start).take(6), style = TextStyle(color = theme.foreground, fontSize = 22.sp))
                Hint("Not in the dictionary", Modifier.padding(vertical = 6.dp))
                PillButton("▶ Resume", onClick = onResume)
                return@Column
            }
        }
        val m = list!![pick.coerceIn(list.indices)]
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BasicText(m.form, style = TextStyle(color = theme.foreground, fontSize = 28.sp, fontWeight = FontWeight.Bold))
                m.reading?.takeIf { it != m.form }?.let { BasicText(it, style = TextStyle(color = theme.warning, fontSize = 16.sp), modifier = Modifier.align(Alignment.Bottom).padding(bottom = 4.dp)) }
                if (m.entry.common) Badge("COMMON", theme.warning)
                if (m.entry.name) Badge("NAME", theme.secondary)
            }
            if (m.reasons.isNotEmpty() || m.surface != m.form) {
                BasicText(
                    "${m.surface} ← " + (Lookup.describe(m).ifEmpty { "as written" }),
                    style = NumberStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 11.5.sp),
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                )
            }
            m.entry.senses.take(5).forEachIndexed { i, sense ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    BasicText("${i + 1}. ", style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 14.sp))
                    Column {
                        BasicText(sense.gloss.take(5).joinToString("; "), style = TextStyle(color = theme.foreground, fontSize = 14.sp, lineHeight = 19.sp))
                        val notes = (sense.pos + sense.misc).take(4).joinToString(" · ")
                        if (notes.isNotEmpty()) BasicText(notes, style = NumberStyle(color = theme.foreground.copy(alpha = 0.5f), fontSize = 10.5.sp))
                    }
                }
            }
        }
        if (list.size > 1) {
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEachIndexed { i, other ->
                    BasicText(
                        other.form,
                        style = TextStyle(color = if (i == pick) theme.primary else theme.foreground, fontSize = 13.sp),
                        modifier = Modifier.border(1.dp, if (i == pick) theme.primary else theme.panel, lookShape(3.dp)).clickable { pick = i }.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("★ Save word", onClick = { onSave(m) })
            PillButton("↺ Line again", onClick = onAgain, filled = false)
            PillButton("▶ Resume", onClick = onResume, filled = false)
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    BasicText(
        text,
        style = NumberStyle(color = Color.Black, fontSize = 10.sp),
        modifier = Modifier.background(color).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun DictionaryProgress(job: JapaneseDictionary.Job) {
    val theme = LocalAppTheme.current
    val (fraction, label) = when (job) {
        is JapaneseDictionary.Job.Downloading -> (if (job.total > 0) job.done.toFloat() / job.total else 0f) to
            "Downloading ${job.part.label}… ${job.done / 1_000_000} of ${if (job.total > 0) "${job.total / 1_000_000}" else "?"} MB"
        is JapaneseDictionary.Job.Building -> job.fraction to "Building the index… ${(job.fraction * 100).toInt()}%"
        is JapaneseDictionary.Job.Failed -> 0f to job.message
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (job !is JapaneseDictionary.Job.Failed) {
            Box(Modifier.fillMaxWidth().height(6.dp).background(theme.foreground.copy(alpha = 0.12f))) {
                Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(theme.primary))
            }
        }
        BasicText(label, style = TextStyle(color = if (job is JapaneseDictionary.Job.Failed) theme.error else theme.foreground.copy(alpha = 0.7f), fontSize = 12.5.sp), modifier = Modifier.padding(top = 4.dp))
    }
}

/** Settings › Japanese: the dictionary, how the lines show, and saved words. */
@Composable
internal fun JapaneseSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { JapaneseSettings.get(context).also { JapaneseDictionary.init(context) } }
    var words by rememberSaveable { mutableStateOf(false) }
    if (words) {
        BackHandler { words = false }
        SavedWordsScreen(onBack = { words = false })
        return
    }
    val theme = LocalAppTheme.current
    val version = JapaneseDictionary.version
    val count = remember(version) { wordStore(context).all().size }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Japanese", onBack)
        SectionTitle("Dictionary")
        val job = JapaneseDictionary.job
        JapaneseDictionary.Part.entries.forEach { part ->
            val db = remember(version) { JapaneseDictionary.db(part) }
            if (part == JapaneseDictionary.Part.Names) {
                SwitchRow(
                    "Names (JMnedict)",
                    settings.names,
                    { JapaneseDictionary.setNames(context, it) },
                    description = if (db != null) "${db.count} names · ${JapaneseDictionary.size(part) / 1_000_000} MB on the phone" else "People and place names: 13.5 MB more, about 80 MB on the phone",
                )
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    BasicText(part.label, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    BasicText(
                        if (db != null) "${db.count} words · ${JapaneseDictionary.size(part) / 1_000_000} MB on the phone · ${db.version}"
                        else "About 12 MB to download, about 70 MB on the phone · from jmdict-simplified, updated weekly",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp),
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (job == null || job is JapaneseDictionary.Job.Failed) {
                            PillButton(if (db != null) "Get the newest" else "Download", onClick = { JapaneseDictionary.download(context, part) }, filled = db == null)
                            if (db != null) PillButton("Delete", onClick = { JapaneseDictionary.delete(part) }, filled = false, color = theme.error)
                        } else {
                            PillButton("Cancel", onClick = { JapaneseDictionary.cancel() }, filled = false)
                        }
                    }
                }
            }
        }
        job?.let { Box(Modifier.padding(horizontal = 20.dp)) { DictionaryProgress(it) } }

        SectionTitle("Subtitles")
        SwitchRow("Japanese lines you can tap", settings.dual, { settings.dual = it }, description = "From a Japanese file loaded for the episode (Jimaku or the phone), shown with the other subtitles")
        SegmentedRow("Layout", DualLayout.entries, settings.layout, { it.label }, { settings.layout = it })
        SwitchRow("Furigana over kanji", settings.furigana, { settings.furigana = it }, description = "Readings from the dictionary")
        SwitchRow("Pause when a word is tapped", settings.pauseOnTap, { settings.pauseOnTap = it })

        SectionTitle("Words")
        Column(Modifier.fillMaxWidth().clickable { words = true }.padding(horizontal = 20.dp, vertical = 10.dp)) {
            BasicText("Saved words · $count ›", style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            BasicText("Send them to AnkiDroid or save them as a file", style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp))
        }
    }
}

@Composable
private fun SavedWordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    val store = remember { wordStore(context) }
    var list by remember { mutableStateOf(store.all()) }
    var exporting by remember { mutableStateOf(false) }

    fun toAnki() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AnkiExport.send(context, list, store.frames) } }
            result.onSuccess { sent ->
                list = withContext(Dispatchers.IO) { store.markSent(sent) }
                context.showToast(if (sent.isEmpty()) "Nothing new to send" else "Sent ${sent.size} to AnkiDroid")
            }.onFailure { context.showToast(it.message ?: "AnkiDroid didn't take them") }
        }
    }
    val askAnki = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toAnki() else context.showToast("AnkiDroid's permission is needed to add cards")
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { WordExport.zip(list, store.frames, it) } }.isSuccess
            }
            context.showToast(if (ok) "Saved ${list.size} words" else "Couldn't save the file")
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        BackHeader("Words · ${list.size}", onBack)
        if (list.isEmpty()) {
            Empty("No words yet. Tap a word in a Japanese subtitle line, then ★ Save word.", Modifier.padding(24.dp))
            return@Column
        }
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Export ›", onClick = { exporting = !exporting }, filled = exporting)
        }
        if (exporting) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth().border(1.dp, theme.panel, lookShape(8.dp)).padding(12.dp)) {
                val fresh = list.count { !it.sentToAnki }
                BasicText("To AnkiDroid", style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Hint("Deck \"aniliphy\", one card per word with the reading, meaning, line and frame. Words already sent are skipped ($fresh new).")
                Spacer(Modifier.height(6.dp))
                if (AnkiExport.installed(context)) {
                    PillButton("Send $fresh", enabled = fresh > 0, onClick = {
                        if (AnkiExport.allowed(context)) toAnki() else askAnki.launch(AnkiExport.PERMISSION)
                    })
                } else {
                    Hint("AnkiDroid isn't installed.", color = theme.warning)
                }
                Spacer(Modifier.height(12.dp))
                BasicText("As a file", style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                Hint("A .zip with words.tsv and the frames (media/), for Anki on the PC: import the list, copy media/ into Anki's media folder.")
                Spacer(Modifier.height(6.dp))
                PillButton("Save .zip", onClick = { saveZip.launch("aniliphy-words.zip") }, filled = false)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            list.forEach { w ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(width = 96.dp, height = 54.dp).background(theme.panel)) {
                        w.frame?.let { AsyncImage(File(store.frames, it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            BasicText(w.word, style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                            w.reading?.takeIf { it != w.word }?.let { BasicText("  $it", style = TextStyle(color = theme.warning, fontSize = 13.sp)) }
                            if (w.sentToAnki) BasicText("  ✓ Anki", style = NumberStyle(color = theme.success, fontSize = 10.sp))
                        }
                        BasicText(w.meanings.firstOrNull().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.5.sp))
                        BasicText(w.sentence, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.sp))
                        BasicText(w.source, style = NumberStyle(color = theme.foreground.copy(alpha = 0.5f), fontSize = 10.5.sp))
                    }
                    BasicText(
                        "✕",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 16.sp),
                        modifier = Modifier.clickable { list = store.remove(w.id) }.padding(6.dp),
                    )
                }
            }
        }
    }
}
