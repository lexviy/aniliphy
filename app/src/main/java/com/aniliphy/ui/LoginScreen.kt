package com.aniliphy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AccountStore
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.AniListSession
import com.aniliphy.anilist.InvalidTokenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LoginScreen(session: AniListSession) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val store = remember { AniListServices.get(context).store }
    val scope = rememberCoroutineScope()
    var clientId by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf(session.notice) }

    LaunchedEffect(Unit) {
        if (clientId.isEmpty()) clientId = withContext(Dispatchers.IO) { store.clientId().orEmpty() }
    }

    fun openTokenLink() {
        val id = clientId.trim()
        if (id.isEmpty()) {
            problem = "Paste the Client ID first"
            return
        }
        problem = null
        scope.launch { withContext(Dispatchers.IO) { store.saveClientId(id) } }
        context.openLink(AccountStore.authUrl(id))
    }

    fun logIn() {
        val typed = token.trim()
        if (typed.isEmpty()) {
            problem = "Paste the token first"
            return
        }
        busy = true
        problem = null
        scope.launch {
            try {
                session.login(typed)
            } catch (_: InvalidTokenException) {
                problem = "AniList doesn't accept this token"
            } catch (e: AniListException) {
                problem = e.message
            } finally {
                busy = false
            }
        }
    }

    val stepStyle = TextStyle(color = theme.foreground, fontSize = 15.sp)
    var otherWays by rememberSaveable { mutableStateOf(false) }
    val shownProblem = LoginFlow.problem ?: problem
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        if (LocalWindowLayout.current == WindowLayout.Tv) {
            // A TV has no good way to paste or sign in through a browser: the phone sends the login.
            Spacer(Modifier.height(24.dp))
            TvCodePanel(session, Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            PillButton(
                "Continue as guest",
                onClick = { scope.launch { session.continueAsGuest() } },
                filled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        Spacer(Modifier.height(24.dp))
        BasicText("ANILIST", style = NumberStyle(color = theme.accent, fontSize = 12.sp, bold = true))
        BasicText("Log in to\nAniList", style = TitleStyle(color = theme.foreground, fontSize = 32.sp, lineHeight = 34.sp))
        Spacer(Modifier.height(10.dp))
        BasicText(
            "Opens anilist.co in your browser. Tap Authorize there and you're back here, logged in.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 14.sp),
        )
        Spacer(Modifier.height(20.dp))
        PillButton(
            when {
                LoginFlow.checking -> "Checking..."
                LoginFlow.waiting -> "Open AniList again"
                else -> "Log in with AniList"
            },
            onClick = { LoginFlow.start(context) },
            enabled = !LoginFlow.checking && !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        PillButton(
            "Continue as guest",
            onClick = {
                LoginFlow.cancel(context)
                scope.launch { session.continueAsGuest() }
            },
            filled = false,
            enabled = !busy && !LoginFlow.checking,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "As a guest, progress stays on this phone.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 12.sp),
        )
        if (LoginFlow.waiting || LoginFlow.checking) {
            Spacer(Modifier.height(14.dp))
            LookLoading(if (LoginFlow.checking) "Logging in..." else "Waiting for AniList...", color = theme.secondary)
        }
        shownProblem?.let {
            Spacer(Modifier.height(10.dp))
            BasicText(it, style = TextStyle(color = theme.error, fontSize = 14.sp))
        }
        Spacer(Modifier.height(22.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(theme.foreground.copy(alpha = 0.18f)))
        BasicText(
            (if (otherWays) "▾ " else "▸ ") + "Other ways: your own Client ID, or paste a token",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().clickable { otherWays = !otherWays }.padding(vertical = 14.dp),
        )
        if (!otherWays) return@Column
        BasicText("1. Create an app on AniList, with this Redirect URL:", style = stepStyle)
        Spacer(Modifier.height(4.dp))
        BasicText(
            "https://anilist.co/api/v2/oauth/pin",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.75f), fontSize = 13.sp),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Open anilist.co/settings/developer",
            style = TextStyle(color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .clickable { context.openLink(AccountStore.DEVELOPER_URL) }
                .padding(vertical = 8.dp),
        )
        Spacer(Modifier.height(14.dp))
        BasicText("2. Paste its Client ID and open the token link:", style = stepStyle)
        Spacer(Modifier.height(8.dp))
        InputBox(clientId, { clientId = it }, "Client ID", Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        PillButton("Open token link", ::openTokenLink, filled = false)
        Spacer(Modifier.height(20.dp))
        BasicText("3. Authorize, copy the token and paste it here:", style = stepStyle)
        Spacer(Modifier.height(8.dp))
        InputBox(
            token,
            { token = it },
            "Token",
            Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(16.dp))
        PillButton(if (busy) "Checking..." else "Log in", ::logIn, enabled = !busy)
        Spacer(Modifier.height(24.dp))
    }
}
