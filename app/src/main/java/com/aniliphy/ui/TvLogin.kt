package com.aniliphy.ui

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.AniListSession
import com.aniliphy.sync.SyncSettings
import com.aniliphy.sync.TvLogin
import com.aniliphy.sync.TvPairing
import com.aniliphy.sync.TvPairingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

/** A TV found on the Wi-Fi, showing a code. */
internal data class FoundTv(val name: String, val host: InetAddress, val port: Int)

/** Puts the TV's listening port on the Wi-Fi under [TvPairing.SERVICE_TYPE]; returns how to take it down. */
private fun advertise(context: Context, port: Int): () -> Unit {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return {}
    val info = NsdServiceInfo().apply {
        serviceName = (Build.MODEL ?: "TV").take(40)
        serviceType = TvPairing.SERVICE_TYPE
        setPort(port)
    }
    val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) = Unit
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
    }
    runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
    return { runCatching { nsd.unregisterService(listener) } }
}

/** Looks for TVs showing a code; [onFound] is called on the main thread's state. Returns how to stop looking. */
@Suppress("DEPRECATION")
private fun discover(context: Context, found: MutableList<FoundTv>): () -> Unit {
    val nsd = context.getSystemService(NsdManager::class.java) ?: return {}
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceLost(info: NsdServiceInfo) {
            main.post { found.removeAll { it.name == info.serviceName } }
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            // One resolve at a time is all older Androids allow; a failed one is just skipped.
            runCatching {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host ?: return
                        val tv = FoundTv(info.serviceName, host, info.port)
                        main.post { found.removeAll { it.name == tv.name }; found.add(tv) }
                    }
                })
            }
        }
    }
    runCatching { nsd.discoverServices(TvPairing.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
    return { runCatching { nsd.stopServiceDiscovery(listener) } }
}

/**
 * The TV's way in: a code on screen and a phone that sends the login. The code changes after too many wrong tries
 * or when its ten minutes are up.
 */
@Composable
internal fun TvCodePanel(session: AniListSession, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var round by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    // A login that came in, waiting for OK on the TV: who it is, so a stranger's phone can't log the TV in as them.
    var pending by remember { mutableStateOf<Pair<TvLogin, String>?>(null) }
    val api = remember { AniListServices.get(context).api }

    fun accept(login: TvLogin) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (login.gistToken != null) {
                    val sync = SyncSettings.get(context)
                    sync.token = login.gistToken
                    sync.gistId = login.gistId.orEmpty()
                }
            }
            val token = login.anilistToken
            if (token != null) {
                runCatching { session.login(token) }.onFailure { note = "AniList didn't take that login. Try again from the phone" }
            } else {
                note = "Sync set up. Send the AniList login too to log in"
            }
        }
    }

    DisposableEffect(round) {
        val server = TvPairingServer(
            code = TvPairing.newCode(),
            onLogin = { login ->
                scope.launch {
                    val token = login.anilistToken
                    val who = if (token == null) "the sync only" else runCatching { api.viewer(token).name }.getOrNull()
                    if (who == null) note = "AniList didn't take that login. Try again from the phone"
                    else pending = login to who
                }
            },
            onClosed = { reason ->
                scope.launch {
                    when (reason) {
                        TvPairingServer.Reason.TooManyTries -> { note = "Too many wrong codes. Here's a new one"; round++ }
                        TvPairingServer.Reason.TimeUp -> round++
                        TvPairingServer.Reason.LoggedIn -> round++
                        TvPairingServer.Reason.Stopped -> Unit
                    }
                }
            },
        )
        val unadvertise = runCatching {
            server.start()
            code = server.code
            advertise(context, server.port)
        }.getOrElse { note = "Couldn't listen on the Wi-Fi"; { } }
        onDispose {
            unadvertise()
            server.stop()
        }
    }

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText("Log in with your phone", style = TitleStyle(color = theme.foreground, fontSize = 26.sp))
        Spacer(Modifier.height(8.dp))
        BasicText(
            "On the phone: aniliphy › Settings › Sync with anilixy › Log in a TV, on the same Wi-Fi. Enter this code there.",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.72f), fontSize = 15.sp, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(16.dp))
        BasicText(TvPairing.shown(code), style = NumberStyle(color = theme.accent, fontSize = 52.sp, bold = true))
        Spacer(Modifier.height(10.dp))
        BasicText(
            "The code changes after ${TvPairing.MAX_TRIES} wrong tries or ${TvPairing.LIFETIME_MS / 60_000} minutes",
            style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 13.sp),
        )
        note?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TextStyle(color = theme.secondary, fontSize = 14.sp, textAlign = TextAlign.Center))
        }
    }

    pending?.let { (login, who) ->
        ConfirmDialog(
            title = if (login.anilistToken != null) "Log in as $who?" else "Set up the sync?",
            message = "A phone sent this. Only say yes if it was yours.",
            confirmLabel = "Yes",
            onConfirm = { pending = null; accept(login) },
            onDismiss = { pending = null; note = "Not logged in" },
        )
    }
}

/** The phone's side: find the TV, type its code, send the login. */
@Composable
internal fun TvSendScreen(onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateListOf<FoundTv>() }
    var picked by remember { mutableStateOf<FoundTv?>(null) }
    var code by remember { mutableStateOf("") }
    var sendAniList by remember { mutableStateOf(true) }
    var sendGist by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val sync = remember { SyncSettings.get(context) }
    val services = remember { AniListServices.get(context) }
    // The session only loads its token once the AniList tab has opened, so read the saved one too.
    var anilistToken by remember { mutableStateOf(services.session.currentToken()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (anilistToken == null) anilistToken = withContext(Dispatchers.IO) { runCatching { services.store.token() }.getOrNull() }
    }
    val gistReady = sync.token.isNotBlank()

    DisposableEffect(Unit) {
        val stop = discover(context, found)
        onDispose { stop() }
    }
    BackHandler(onBack = onBack)
    val tv = picked ?: found.singleOrNull()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "Log in a TV", onBack = onBack)
        Hint(
            "Open aniliphy on the TV: it shows a code. Both need to be on the same Wi-Fi.",
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SectionTitle("TVs showing a code")
        if (found.isEmpty()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { LookLoading("Looking on this Wi-Fi...", color = theme.secondary) }
        }
        found.forEach { item ->
            val on = item == tv
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(lookShape(12.dp))
                    .background(if (on) theme.primary.copy(alpha = 0.18f) else theme.surface)
                    .clickable(role = Role.RadioButton) { picked = item }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                BasicText(item.name, style = TextStyle(color = theme.ink, fontSize = 15.sp))
                if (on) BasicText("●", style = TextStyle(color = theme.primary, fontSize = 14.sp))
            }
        }

        SectionTitle("Code")
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            InputBox(code, { code = TvPairing.clean(it) }, "The code on the TV", Modifier.fillMaxWidth())
        }
        SwitchRow(
            label = "Send my AniList login",
            description = if (anilistToken == null) "Log in to AniList on this phone first" else "The TV logs in as you",
            checked = sendAniList && anilistToken != null,
            enabled = anilistToken != null,
            onChange = { sendAniList = it },
        )
        SwitchRow(
            label = "Also send the sync with anilixy",
            description = if (gistReady) "The GitHub token and gist, so the TV keeps your places too" else "Set up the sync on this phone first",
            checked = sendGist && gistReady,
            enabled = gistReady,
            onChange = { sendGist = it },
        )
        Spacer(Modifier.height(10.dp))
        val login = TvLogin(
            anilistToken = anilistToken?.takeIf { sendAniList },
            gistToken = sync.token.takeIf { sendGist && gistReady },
            gistId = sync.gistId.takeIf { sendGist && gistReady },
        )
        PillButton(
            if (busy) "Sending..." else "Send to the TV",
            onClick = {
                val target = tv ?: return@PillButton
                busy = true
                result = null
                scope.launch {
                    val sent = withContext(Dispatchers.IO) { TvPairing.send(target.host, target.port, code, login) }
                    busy = false
                    result = when (sent) {
                        TvPairing.Sent.Done -> "Sent. The TV is logging in"
                        is TvPairing.Sent.WrongCode -> "That's not the code on the TV. ${sent.triesLeft} more ${if (sent.triesLeft == 1) "try" else "tries"} before it changes"
                        TvPairing.Sent.Closed -> "The TV stopped waiting. It shows a new code now"
                        is TvPairing.Sent.Failed -> sent.reason
                    }
                    if (sent == TvPairing.Sent.Done) code = ""
                }
            },
            enabled = !busy && tv != null && code.length == TvPairing.CODE_LENGTH && (login.anilistToken != null || login.gistToken != null),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        result?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = TextStyle(color = theme.secondary, fontSize = 14.sp), modifier = Modifier.padding(horizontal = 20.dp))
        }
        Hint(
            "Sent straight over the Wi-Fi, sealed with a key the two make together and the code. Nothing goes through the gist or the internet.",
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
