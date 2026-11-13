package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.EntryForm
import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaKind
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListEditorDialog(
    media: Media,
    current: ListProgress?,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
    /** Takes the title off the list (with a moment to undo); only offered when it is on the list. */
    onRemove: ((entryId: Int) -> Unit)? = null,
) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    val anime = media.kind == MediaKind.Anime
    val progressTotal = if (anime) media.episodes else media.chapters
    val volumesTotal = if (anime) null else media.volumes
    val original = current ?: ListProgress()
    var form by remember { mutableStateOf(EntryForm.from(original)) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.surface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            BasicText(
                if (original.status == null) "Add to list" else "Edit entry",
                style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                media.title.display,
                style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ListStatus.ALL.forEach { status ->
                    FilterChipOnSurface(ListStatus.label(status, media.kind), form.status == status) {
                        form = form.withStatus(status, progressTotal, volumesTotal)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            NumberField(if (anime) "Episodes" else "Chapters", form.progress, "/ ${progressTotal?.takeIf { it > 0 } ?: "?"}") {
                form = form.copy(progress = it)
            }
            if (!anime) {
                NumberField("Volumes", form.volumes, "/ ${volumesTotal?.takeIf { it > 0 } ?: "?"}") {
                    form = form.copy(volumes = it)
                }
            }
            NumberField("Rating", form.score, "/ 10", decimal = true) { form = form.copy(score = it) }
            NumberField("Repeats", form.repeat, null) { form = form.copy(repeat = it) }
            val lists = remember { ui.load { ui.api.customLists(it) } }.value?.of(media.kind).orEmpty()
            if (lists.isNotEmpty()) {
                BasicText("Your lists", style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lists.forEach { name ->
                        val on = name in form.customLists
                        FilterChipOnSurface(name, on) {
                            form = form.copy(customLists = if (on) form.customLists - name else form.customLists + name)
                        }
                    }
                }
            }
            BasicText("Notes", style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.padding(top = 10.dp, bottom = 6.dp))
            InputBox(
                text = form.notes,
                onText = { form = form.copy(notes = it) },
                hint = "Only you see these",
                background = theme.panel,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
            problem?.let {
                BasicText(it, style = TextStyle(color = theme.error, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val entryId = original.entryId
                if (onRemove != null && entryId != null && original.status != null) {
                    PillButton("Remove", { onRemove(entryId) }, filled = false, enabled = !saving, color = theme.error)
                }
                Spacer(Modifier.weight(1f))
                PillButton("Cancel", onDismiss, filled = false, enabled = !saving)
                Spacer(Modifier.width(10.dp))
                PillButton(
                    if (saving) "Saving..." else "Save",
                    onClick = {
                        saving = true
                        problem = null
                        val values = form.values(progressTotal, volumesTotal, !anime, original.progressVolumes)
                        scope.launch {
                            try {
                                ui.api.saveEntry(
                                    media.id,
                                    values.status,
                                    values.progress,
                                    values.volumes,
                                    values.score,
                                    values.repeat,
                                    notes = form.notesToSend(original),
                                    customLists = form.customListsToSend(original),
                                )
                                if (values.status != original.status && values.status != null) {
                                    LookMoment.status(ListStatus.label(values.status, media.kind), values.status == ListStatus.COMPLETED, media.title.display)
                                }
                                onSaved()
                            } catch (e: AniListException) {
                                problem = "Couldn't save: ${ui.describe(e)}"
                                saving = false
                            }
                        }
                    },
                    enabled = !saving,
                )
            }
        }
    }
}

/**
 * What holding an entry on a list opens: change its status in one tap, open the full editor, or take it off the list.
 * A status change is saved at once, with the same rules as the editor (Completed fills in the episodes or chapters).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickEntryMenu(
    media: Media,
    current: ListProgress,
    /** The progress line under the title, like "5 / 12 eps". */
    detail: String,
    onSaved: () -> Unit,
    onEdit: () -> Unit,
    onRemove: (entryId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalAniList.current
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    val anime = media.kind == MediaKind.Anime
    val progressTotal = if (anime) media.episodes else media.chapters
    val volumesTotal = if (anime) null else media.volumes
    var saving by remember { mutableStateOf<String?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (saving == null) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.surface)
                .padding(20.dp),
        ) {
            BasicText(
                media.title.display,
                maxLines = 2,
                style = TextStyle(color = theme.foreground, fontSize = 17.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                listOfNotNull(ListStatus.label(current.status, media.kind), detail).joinToString(" · "),
                style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp),
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ListStatus.ALL.forEach { status ->
                    val label = ListStatus.label(status, media.kind)
                    FilterChipOnSurface(if (saving == status) "Saving..." else label, current.status == status) {
                        if (saving != null || status == current.status) return@FilterChipOnSurface
                        saving = status
                        problem = null
                        val values = EntryForm.from(current)
                            .withStatus(status, progressTotal, volumesTotal)
                            .values(progressTotal, volumesTotal, !anime, current.progressVolumes)
                        scope.launch {
                            try {
                                ui.api.saveEntry(media.id, values.status, values.progress, values.volumes, values.score, values.repeat)
                                LookMoment.status(label, status == ListStatus.COMPLETED, media.title.display)
                                onSaved()
                            } catch (e: AniListException) {
                                problem = "Couldn't save: ${ui.describe(e)}"
                                saving = null
                            }
                        }
                    }
                }
            }
            problem?.let {
                BasicText(it, style = TextStyle(color = theme.error, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val entryId = current.entryId
                if (entryId != null) {
                    PillButton("Remove", { onRemove(entryId) }, filled = false, enabled = saving == null, color = theme.error)
                }
                Spacer(Modifier.weight(1f))
                PillButton("Edit entry", onEdit, filled = false, enabled = saving == null)
            }
        }
    }
}

@Composable
private fun FilterChipOnSurface(label: String, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalAppTheme.current
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) theme.primary else theme.panel)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        BasicText(
            label,
            maxLines = 1,
            style = TextStyle(color = if (selected) theme.background else theme.foreground, fontSize = 13.sp),
        )
    }
}

@Composable
private fun NumberField(label: String, value: String, suffix: String?, decimal: Boolean = false, onChange: (String) -> Unit) {
    val theme = LocalAppTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        BasicText(label, style = TextStyle(color = theme.foreground, fontSize = 14.sp), modifier = Modifier.width(78.dp))
        InputBox(
            text = value,
            onText = onChange,
            hint = "0",
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            modifier = Modifier.width(88.dp),
            background = theme.panel,
        )
        if (suffix != null) {
            Spacer(Modifier.width(10.dp))
            BasicText(suffix, style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp))
        }
    }
}
