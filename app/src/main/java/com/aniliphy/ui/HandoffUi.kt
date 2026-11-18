package com.aniliphy.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.WidgetOpen
import com.aniliphy.WidgetOpenRequest
import com.aniliphy.core.Incognito
import com.aniliphy.sync.GistStore
import com.aniliphy.sync.HandoffFile
import com.aniliphy.sync.HandoffPlace
import com.aniliphy.sync.HandoffRules
import com.aniliphy.sync.SyncPayload
import com.aniliphy.sync.SyncRules
import com.aniliphy.sync.SyncSettings
import com.aniliphy.widget.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException

/** "Continue on the other device": sending to anilixy, the PC's requests, and the card for what the PC played. */
internal class HandoffState(
    context: Context,
    private val anime: AnimeUi,
    private val manga: MangaUi,
    private val novels: NovelsUi,
    private val scope: CoroutineScope,
) {
    private val app = context.applicationContext
    private val settings = SyncSettings.get(app)
    private val gists = GistStore()

    /** Sent from the PC ("Send to phone"), waiting for an answer here. */
    var incoming by mutableStateOf<HandoffPlace?>(null)
        private set

    /** What the PC played or read last, when it's newer than this phone's place. */
    var pcLast by mutableStateOf<HandoffPlace?>(null)
        private set

    private var lastCheck = 0L

    val usable: Boolean get() = settings.ready && settings.handoff && settings.gistId.isNotBlank() && !Incognito.on

    private fun nowS() = System.currentTimeMillis() / 1000.0

    private fun seen(): Set<String> = settings.handoffSeen.split(',').filter { it.isNotBlank() }.toSet()

    private fun markSeen(id: String) {
        settings.handoffSeen = (settings.handoffSeen.split(',').filter { it.isNotBlank() && it != id } + id).takeLast(SEEN_KEPT).joinToString(",")
    }

    /** This phone's own place for a title, in seconds, to compare with the PC's. */
    private fun localUpdated(place: HandoffPlace): Double? = when (place.kind) {
        "anime" -> place.key.toIntOrNull()?.let { anime.history.get(it) }?.updated?.div(1000.0)
        "manga" -> place.key.toIntOrNull()?.let { manga.history.get(it) }?.updated?.div(1000.0)
        "novel" -> (novels.progress.raw()[place.key]?.get("updated") as? JsonPrimitive)?.doubleOrNull
        else -> null
    }

    /** Looks at the gist for a request from the PC and for the PC's last place; at most every half a minute. */
    fun check(force: Boolean = false) {
        if (!usable) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY_MS) return
        lastCheck = now
        val token = settings.token.trim()
        val gistId = settings.gistId
        scope.launch {
            val files = try {
                withContext(Dispatchers.IO) { gists.readFiles(token, gistId, listOf(HandoffFile.TO_PHONE, HandoffFile.PC_LAST)) }
            } catch (_: IOException) {
                return@launch
            }
            val nowS = nowS()
            incoming = HandoffRules.waiting(HandoffFile.parse(files[HandoffFile.TO_PHONE]), nowS)
                ?.takeIf { it.id !in seen() }
            val last = HandoffFile.parse(files[HandoffFile.PC_LAST])
            pcLast = last?.request?.let { place ->
                val local = withContext(Dispatchers.IO) { localUpdated(place) }
                HandoffRules.pcCard(last, local, seen(), nowS)
            }?.takeIf { it.id != incoming?.id }
        }
    }

    /** The place to send for a title, from what this phone has saved (the player and readers save first). */
    fun placeFor(kind: String, key: String, title: String?): HandoffPlace? {
        val now = nowS()
        val id = java.util.UUID.randomUUID().toString()
        return when (kind) {
            "anime" -> anime.history.get(key.toIntOrNull() ?: return null)?.takeIf { it.hasPlace }?.let { record ->
                HandoffPlace(id, kind, key, title ?: record.title, now, "phone", anime = SyncRules.anime(record).copy(updated = now))
            }
            "manga" -> manga.history.get(key.toIntOrNull() ?: return null)?.takeIf { it.hasPlace }?.let { record ->
                HandoffPlace(id, kind, key, title ?: record.title, now, "phone", manga = SyncRules.manga(record).copy(updated = now))
            }
            "novel" -> novels.progress.raw()[key]?.let { record ->
                val anilist = (record["mediaId"] as? JsonPrimitive)?.content?.toIntOrNull()
                    ?: (record["anilist_id"] as? JsonPrimitive)?.content?.toIntOrNull()
                HandoffPlace(id, kind, key, title ?: key, now, "phone", novel = JsonObject(record + ("updated" to JsonPrimitive(now))), anilist = anilist)
            }
            else -> null
        }
    }

    /** Writes the request for anilixy, then watches for it being taken (for two minutes). */
    fun send(place: HandoffPlace) {
        if (!usable) return
        val token = settings.token.trim()
        val gistId = settings.gistId
        scope.launch {
            try {
                withContext(Dispatchers.IO) { gists.writeFile(token, gistId, HandoffFile.TO_PC, HandoffFile.encode(HandoffFile(request = place))) }
            } catch (_: IOException) {
                app.showToast("Couldn't reach GitHub")
                return@launch
            }
            app.showToast("Sent to the PC · waiting for anilixy…")
            repeat(TAKEN_CHECKS) {
                delay(TAKEN_EVERY_MS)
                val taken = runCatching {
                    withContext(Dispatchers.IO) { HandoffFile.parse(gists.readFile(token, gistId, HandoffFile.TO_PC))?.taken }
                }.getOrNull()
                if (taken == place.id) {
                    app.showToast("The PC took it ✓")
                    return@launch
                }
            }
        }
    }

    /** Puts the place in this phone's history and opens it. A request from the PC is answered so it isn't asked again. */
    fun accept(place: HandoffPlace) {
        markSeen(place.id)
        if (incoming?.id == place.id) incoming = null
        if (pcLast?.id == place.id) pcLast = null
        val token = settings.token.trim()
        val gistId = settings.gistId
        scope.launch {
            val kind = withContext(Dispatchers.IO) { apply(place) } ?: run {
                app.showToast("That isn't something this phone can open")
                return@launch
            }
            WidgetOpenRequest.open = WidgetOpen(kind, place.key)
            if (place.from == "pc") runCatching {
                withContext(Dispatchers.IO) {
                    val file = HandoffFile.parse(gists.readFile(token, gistId, HandoffFile.TO_PHONE))
                    if (file?.request?.id == place.id) gists.writeFile(token, gistId, HandoffFile.TO_PHONE, HandoffFile.encode(file.copy(taken = place.id)))
                }
            }
        }
    }

    fun dismiss(place: HandoffPlace) {
        markSeen(place.id)
        if (incoming?.id == place.id) incoming = null
        if (pcLast?.id == place.id) pcLast = null
    }

    private fun apply(place: HandoffPlace): WidgetKind? {
        val json = SyncPayload.json
        return when (place.kind) {
            "anime" -> {
                val id = place.key.toIntOrNull() ?: return null
                val synced = place.anime ?: return null
                anime.history.edit(id) { SyncRules.applyAnime(it, synced.copy(updated = maxOf(synced.updated, nowS()))) }
                scope.launch { anime.historyChanged() }
                WidgetKind.Anime
            }
            "manga" -> {
                val id = place.key.toIntOrNull() ?: return null
                val synced = place.manga ?: return null
                manga.history.edit(id) { SyncRules.applyManga(it, synced.copy(updated = maxOf(synced.updated, nowS()))) }
                scope.launch { manga.historyChanged() }
                WidgetKind.Manga
            }
            "novel" -> {
                val record = place.novel ?: return null
                val text = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(place.key to JsonObject(record + ("updated" to JsonPrimitive(nowS()))))))
                scope.launch { novels.importSynced(text); novels.progressChanged() }
                WidgetKind.Novel
            }
            else -> null
        }
    }

    companion object {
        private const val CHECK_EVERY_MS = 30_000L
        private const val TAKEN_EVERY_MS = 15_000L
        private const val TAKEN_CHECKS = 8
        private const val SEEN_KEPT = 30

        fun clock(atS: Double): String = java.time.Instant.ofEpochMilli((atS * 1000).toLong())
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
}

/** Asks "Continue here?" for something the PC sent. */
@Composable
internal fun HandoffQuestion(handoff: HandoffState) {
    val place = handoff.incoming ?: return
    val theme = LocalAppTheme.current
    DialogFrame(onDismiss = { handoff.dismiss(place) }) {
        BasicText("Continue here?", style = TextStyle(color = theme.foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold))
        BasicText("From the PC, ${HandoffState.clock(place.at)}", style = NumberStyle(color = theme.warning, fontSize = 12.sp))
        BasicText(place.title ?: "?", style = TextStyle(color = theme.foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold))
        HandoffRules.where(place)?.let { BasicText(it, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Continue", onClick = { handoff.accept(place) })
            PillButton("Not now", filled = false, onClick = { handoff.dismiss(place) })
        }
    }
}

/** At the top of Home and the Anime/Manga tabs: what the PC played or read last. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PcLastCard(handoff: HandoffState, only: String? = null) {
    val place = handoff.pcLast ?: return
    if (only != null && place.kind != only) return
    val theme = LocalAppTheme.current
    val cover = remember(place.id) {
        when (place.kind) {
            "anime" -> place.key.toIntOrNull()?.let { handoffCover(it, anime = true) }
            "manga" -> place.key.toIntOrNull()?.let { handoffCover(it, anime = false) }
            else -> null
        }
    }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(lookShape(12.dp))
            .background(theme.warning.copy(alpha = 0.08f))
            .border(1.5.dp, theme.warning, lookShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (cover != null) Cover(cover, Modifier.width(58.dp).size(58.dp, 82.dp))
        Column(Modifier.weight(1f)) {
            BasicText("ON THE PC · ${HandoffState.clock(place.at)}", style = NumberStyle(color = theme.warning, fontSize = 11.sp, bold = true))
            BasicText(
                place.title ?: "?", maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(top = 2.dp),
            )
            HandoffRules.where(place)?.let {
                BasicText("You stopped at $it on the PC", style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.5.sp), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Continue here", onClick = { handoff.accept(place) })
                PillButton("✕", filled = false, onClick = { handoff.dismiss(place) })
            }
        }
    }
}

private var coverLookup: ((Int, Boolean) -> String?)? = null

private fun handoffCover(id: Int, anime: Boolean): String? = coverLookup?.invoke(id, anime)

/** The sheet behind "⇄ PC" in the player and the readers. [save] saves the place here first. */
@Composable
internal fun SendToPcDialog(
    handoff: HandoffState,
    kind: String,
    key: String,
    title: String?,
    save: () -> Unit,
    pause: (() -> Unit)?,
    onDone: () -> Unit,
) {
    val theme = LocalAppTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var pauseAfter by remember { mutableStateOf(true) }
    val place = remember {
        save()
        handoff.placeFor(kind, key, title)
    }
    DialogFrame(onDismiss = onDone) {
        BasicText("Continue on the PC", style = TextStyle(color = theme.foreground, fontSize = 19.sp, fontWeight = FontWeight.Bold))
        if (place == null) {
            BasicText("Nothing saved for this one yet: read or watch a little first.", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp))
            PillButton("OK", onClick = onDone)
            return@DialogFrame
        }
        BasicText(
            listOfNotNull(place.title, HandoffRules.where(place)).joinToString(" · "),
            style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        BasicText(
            "Goes to anilixy through the sync gist. If anilixy is open it asks \"Continue here?\" within half a minute; if not, it asks the next time it opens (for 12 hours).",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp),
        )
        if (pause != null) SwitchRow("Pause here when it's sent", pauseAfter, { pauseAfter = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Send", onClick = {
                handoff.send(place)
                if (pauseAfter) pause?.invoke()
                LookSounds.tap()
                onDone()
            })
            PillButton("Cancel", filled = false, onClick = onDone)
        }
        if (!handoff.usable) context.showToast("Set up the sync with anilixy first (Settings › Sync)")
    }
}

/** Covers for the PC card come from the phone's own history. */
internal fun handoffCovers(anime: AnimeUi, manga: MangaUi) {
    coverLookup = { id, isAnime -> if (isAnime) anime.history.get(id)?.cover else manga.history.get(id)?.cover }
}
