package com.aniliphy.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.mal.MalAccount
import com.aniliphy.mal.MalComparison
import com.aniliphy.mal.MalDifference
import com.aniliphy.mal.MalLoggedOutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** The MAL login as the app sees it, while the browser has it. */
object MalLogin {
    var checking by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(context: Context) {
        problem = null
        context.openLink(MalAccount.get(context).startLogin())
    }

    /** Takes `aniliphy://mal`; true when it was that link (ours or not). */
    fun take(context: Context, uri: Uri): Boolean {
        if (!uri.host.equals(com.aniliphy.mal.MalAuth.HOST, ignoreCase = true) || !uri.scheme.equals(com.aniliphy.mal.MalAuth.SCHEME, ignoreCase = true)) return false
        val account = MalAccount.get(context)
        checking = true
        problem = null
        scope.launch {
            try {
                if (account.finishLogin(uri) == true) context.showToast("Logged in to MyAnimeList as ${account.name}")
            } catch (e: Exception) {
                problem = e.message ?: "MyAnimeList didn't take the login"
            } finally {
                checking = false
            }
        }
        return true
    }
}

@Composable
internal fun MalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val account = remember { MalAccount.get(context) }
    var comparing by remember { mutableStateOf(false) }
    if (comparing) {
        BackHandler { comparing = false }
        MalCompareScreen(account, onBack = { comparing = false })
        return
    }
    val theme = LocalAppTheme.current
    val small = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("MyAnimeList", onBack)
        val name = account.name
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (account.clientId.isBlank()) {
                BasicText("This build has no MyAnimeList Client ID.", style = small.copy(color = theme.error))
                return@Column
            }
            if (name == null) {
                BasicText("AniList stays your main list: every change made here is copied to MyAnimeList. Log in on MAL's own page; aniliphy never sees your password.", style = small)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (MalLogin.checking) "Checking…" else "Log in to MAL", onClick = { MalLogin.start(context) }, enabled = !MalLogin.checking)
                }
            } else {
                BasicText("Logged in as $name", style = TextStyle(color = theme.foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                val since = if (account.since > 0) "since " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(account.since)) else ""
                val waiting = account.waiting.takeIf { it > 0 }?.let { " · $it waiting for the network" } ?: ""
                BasicText(since + waiting, style = small)
            }
            MalLogin.problem?.let { BasicText(it, style = small.copy(color = theme.error)) }
            account.lastProblem?.let { BasicText("Last refused: $it", style = small.copy(color = theme.warning)) }
        }
        if (name != null) {
            SwitchRow(
                "Copy every change to MAL", account.copyState, { account.copy = it },
                description = "Status, progress, score and rewatches, as they're saved on AniList. Titles with no MAL page are left out",
            )
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Compare lists…", onClick = { comparing = true })
                PillButton("Log out", onClick = { account.logout() }, filled = false)
            }
        }
    }
}

private sealed interface CompareState {
    data object Loading : CompareState
    data class Failed(val message: String) : CompareState
    data class Ready(val result: MalComparison) : CompareState
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MalCompareScreen(account: MalAccount, onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<CompareState>(CompareState.Loading) }
    val ticked = remember { mutableStateListOf<Int>() }
    var sent by remember { mutableStateOf<Int?>(null) }
    fun load() {
        state = CompareState.Loading
        scope.launch {
            state = try {
                val result = account.compare()
                ticked.clear()
                // Missing ones and differences ticked to start with; nothing goes before "Send".
                ticked += (result.differ + result.missing).map { it.mediaId }
                CompareState.Ready(result)
            } catch (_: MalLoggedOutException) {
                CompareState.Failed("Log in to MyAnimeList again")
            } catch (e: java.net.SocketTimeoutException) {
                android.util.Log.w("aniliphy-mal", "compare timed out", e)
                CompareState.Failed("MyAnimeList took too long to answer. Try again in a moment")
            } catch (e: Exception) {
                CompareState.Failed(e.message ?: "Couldn't read the lists")
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }
    val small = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = JetBrainsMono)
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Compare with MAL", onBack)
        when (val s = state) {
            CompareState.Loading -> BasicText("Reading both lists…", style = small, modifier = Modifier.padding(20.dp))
            is CompareState.Failed -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BasicText(s.message, style = small.copy(color = theme.error))
                PillButton("Try again", onClick = { load() }, filled = false)
            }
            is CompareState.Ready -> {
                val r = s.result
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    BasicText("${r.differ.size} differ · ${r.missing.size} not on MAL", style = TextStyle(color = theme.foreground, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold))
                    BasicText(
                        "Ticked ones get AniList's version on MAL. ${r.matching} already match. " +
                            (if (r.noMalPage.isNotEmpty()) "${r.noMalPage.size} have no MAL page and stay out. " else "") +
                            "Only on MAL (${r.onlyOnMal}): left as they are.",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp),
                    )
                    sent?.let { BasicText("$it sent to MAL (or waiting for the network)", style = TextStyle(color = theme.success, fontSize = 13.sp), modifier = Modifier.padding(top = 6.dp)) }
                }
                val all = r.differ + r.missing
                if (all.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Send ${ticked.size} to MAL", onClick = {
                            val chosen = all.filter { it.mediaId in ticked }
                            account.send(chosen)
                            sent = chosen.size
                            ticked.clear()
                        }, enabled = ticked.isNotEmpty())
                        PillButton("Tick all", onClick = { ticked.clear(); ticked += all.map { it.mediaId } }, filled = false)
                        PillButton("None", onClick = { ticked.clear() }, filled = false)
                    }
                }
                all.forEach { d -> DifferenceRow(d, d.mediaId in ticked) { if (d.mediaId in ticked) ticked.remove(d.mediaId) else ticked += d.mediaId } }
                r.noMalPage.forEach { title ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        BasicText(title, style = TextStyle(color = theme.foreground.copy(alpha = 0.5f), fontSize = 15.sp))
                        BasicText("no MAL page · left out", style = small)
                    }
                }
            }
        }
    }
}

@Composable
private fun DifferenceRow(d: MalDifference, on: Boolean, onToggle: () -> Unit) {
    val theme = LocalAppTheme.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 3.dp).size(18.dp).border(2.dp, theme.primary).background(if (on) theme.primary else theme.background.copy(alpha = 0f)))
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(d.title, style = TextStyle(color = theme.foreground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                BasicText(if (d.wanted.anime) "ANIME" else "MANGA", style = TextStyle(color = theme.foreground.copy(alpha = 0.55f), fontSize = 11.sp, fontFamily = JetBrainsMono))
            }
            Spacer(Modifier.height(2.dp))
            d.changes.forEach { BasicText(it, style = TextStyle(color = theme.foreground.copy(alpha = 0.6f), fontSize = 12.sp, fontFamily = JetBrainsMono)) }
        }
    }
}
