package com.aniliphy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.manga.sources.MangaSites

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MangaDialogs(manga: MangaUi) {
    manga.askStart?.let { question ->
        ConfirmDialog(
            title = "Update AniList?",
            message = "${question.title}: ${question.message}",
            confirmLabel = "Update",
            onConfirm = { manga.answerStart(true) },
            onDismiss = { manga.answerStart(false) },
        )
        return
    }
    if (manga.starting) {
        DialogFrame(onDismiss = {}) { Hint("Updating AniList...") }
        return
    }
    val state = manga.pick ?: return
    val theme = LocalAppTheme.current
    DialogFrame(onDismiss = { manga.cancelPick() }) {
        BasicText(
            state.title.shown,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(color = theme.foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold),
        )
        when (state) {
            is SourcePick.Searching -> Hint("Searching ${state.source.name}...")
            is SourcePick.Nothing -> Hint(
                when {
                    state.tried.isEmpty() -> "No manga source is installed. Add one in Settings → Extensions."
                    state.failed -> "Could not reach ${state.tried.joinToString(" or ") { it.name }}"
                    else -> "Not found on ${state.tried.joinToString(" or ") { it.name }}"
                },
            )
            is SourcePick.Results -> {
                BasicText(
                    "Choose the series on ${state.source.name}. It's remembered for next time.",
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 12.sp),
                )
                LazyColumn(Modifier.heightIn(max = 340.dp).fillMaxWidth()) {
                    items(state.results, key = { it.url }) { series ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { manga.choose(state.title, state.source, series) }
                                .padding(vertical = 6.dp),
                        ) {
                            Cover(series.cover, Modifier.width(36.dp).height(52.dp))
                            Spacer(Modifier.width(10.dp))
                            BasicText(
                                series.title,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(color = theme.foreground, fontSize = 13.sp),
                            )
                        }
                    }
                }
            }
        }
        if (state !is SourcePick.Searching) {
            var text by remember(state.title) { mutableStateOf(state.title.titles.firstOrNull().orEmpty()) }
            InputBox(text, { text = it }, "Search with other words", Modifier.fillMaxWidth(), background = theme.panel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rememberSources { MangaSites.all() }.forEach { source ->
                    PillButton(
                        "Search ${source.name}",
                        onClick = {
                            val words = text.trim()
                            val title = if (words.isEmpty()) state.title else state.title.copy(titles = listOf(words))
                            manga.search(title, only = source)
                        },
                        filled = false,
                    )
                }
            }
        }
        Column { PillButton("Cancel", onClick = { manga.cancelPick() }, filled = false) }
    }
}
