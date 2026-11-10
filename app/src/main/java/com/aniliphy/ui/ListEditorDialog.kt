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
import androidx.compose.ui.text.TextStyle
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
internal fun ListEditorDialog(media: Media, current: ListProgress?, onSaved: () -> Unit, onDismiss: () -> Unit) {
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
            problem?.let {
                BasicText(it, style = TextStyle(color = theme.error, fontSize = 13.sp), modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                                )
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
