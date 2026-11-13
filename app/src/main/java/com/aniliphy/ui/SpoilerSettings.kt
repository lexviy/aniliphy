package com.aniliphy.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun SpoilerSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    SpoilerPrefs.load(context)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Spoilers", onBack)
        SwitchRow(
            "Spoiler-free mode",
            SpoilerPrefs.global,
            onChange = { SpoilerPrefs.setGlobal(context, it) },
            description = "Hide names of episodes and chapters you haven't reached, and synopses of sequels to things you haven't finished.",
        )
        val titles = SpoilerPrefs.titles.entries.sortedBy { it.value.second.lowercase() }
        SectionTitle("Titles set by hand")
        if (titles.isEmpty()) {
            Hint("None. Use the Spoiler-free chip on a title's page to set it on or off just for that one.", Modifier.padding(horizontal = 20.dp))
        }
        titles.forEach { (id, value) ->
            SwitchRow(
                value.second.ifBlank { "Title $id" },
                value.first,
                onChange = { SpoilerPrefs.setTitle(context, id, value.second, it.takeIf { on -> on != SpoilerPrefs.global }) },
                description = if (value.first) "Spoiler-free" else "Shows everything",
            )
        }
        Hint("Forum spoilers stay hidden either way until you tap them.", Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
    }
}
