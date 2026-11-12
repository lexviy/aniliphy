package com.aniliphy.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import com.aniliphy.sync.SyncDifference
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

internal val LocalSync = compositionLocalOf<SyncUi?> { null }

private const val NEW_TOKEN = "https://github.com/settings/tokens/new?scopes=gist&description=anilixy%20sync"

@Composable
fun SyncScreen(onBack: () -> Unit) {
    val sync = LocalSync.current ?: return
    val settings = sync.settings
    val context = LocalContext.current
    var token by remember { mutableStateOf(settings.token) }
    var confirmForget by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Sync with anilixy", onBack = onBack)
        Hint(
            "Keeps where you stopped the same here and in anilixy on the computer: the episode and its minute, " +
                "the manga chapter and page, the light novel chapter and the sources picked. AniList progress " +
                "already goes through AniList. Both apps keep it in a private gist on your GitHub account.",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SectionTitle("GitHub token")
        Hint(
            "Make a token with only the gist permission and paste it here and in anilixy. The same token finds the same gist.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        ActionRow(label = "Make a token on GitHub", description = "Opens GitHub with the gist permission ticked", onClick = { context.openLink(NEW_TOKEN) })
        InputBox(
            text = token,
            onText = { token = it.trim() },
            hint = "ghp_…",
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            PillButton(
                if (sync.running) LookWords.syncing(Look.style.takeIf { LookPrefs.moments }) else "Sync now",
                enabled = token.isNotBlank() && !sync.running,
                onClick = {
                    if (token != settings.token) {
                        settings.token = token
                        settings.gistId = ""
                    }
                    sync.sync()
                },
            )
        }
        val last = settings.lastSynced.toLongOrNull()
        val said = sync.status
        val line = listOfNotNull(
            said,
            last?.let { "Last synced " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) },
        ).joinToString(" · ")
        if (line.isNotEmpty()) Hint(line, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))

        if (sync.differences.isNotEmpty()) {
            SectionTitle("Different on the computer")
            Hint(
                "At a sync these had different places here and on the computer. The newer one was kept; you can go back to the other.",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            sync.differences.forEach { DifferenceRow(it, onUseOther = { sync.useOther(it) }) }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                PillButton("Clear the list", onClick = { sync.clearDifferences() }, filled = false)
            }
        }

        SectionTitle("When")
        SwitchRow(
            label = "Sync on opening and leaving",
            description = "Brings in the computer's places when the app opens, and sends this phone's when you leave it",
            checked = settings.onOpen,
            onChange = { settings.onOpen = it },
        )

        if (settings.ready) {
            SectionTitle("Account")
            if (settings.gistId.isNotBlank()) {
                ActionRow(
                    label = "Open the gist",
                    description = "Where the progress is kept, on GitHub",
                    onClick = { context.openLink("https://gist.github.com/${settings.gistId}") },
                )
            }
            ActionRow(
                label = "Forget the token",
                description = "Stops syncing. The gist stays on GitHub",
                color = LocalAppTheme.current.error,
                onClick = { confirmForget = true },
            )
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Forget the token?",
            message = "This phone stops syncing. Your progress here and the gist on GitHub stay as they are.",
            confirmLabel = "Forget",
            onConfirm = {
                confirmForget = false
                sync.forgetToken()
                token = ""
            },
            onDismiss = { confirmForget = false },
        )
    }
}

@Composable
private fun DifferenceRow(difference: SyncDifference, onUseOther: () -> Unit) {
    val theme = LocalAppTheme.current
    val muted = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                difference.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = theme.foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
            BasicText("This phone: ${difference.phone}" + if (!difference.keptComputer) "  ✓" else "", style = muted)
            BasicText("Computer: ${difference.computer}" + if (difference.keptComputer) "  ✓" else "", style = muted)
        }
        Spacer(Modifier.width(10.dp))
        PillButton(if (difference.keptComputer) "Use this phone's" else "Use the computer's", onClick = onUseOther, filled = false)
    }
}
