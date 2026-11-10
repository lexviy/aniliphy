package com.aniliphy.ui

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
                if (sync.running) "Syncing…" else "Sync now",
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
