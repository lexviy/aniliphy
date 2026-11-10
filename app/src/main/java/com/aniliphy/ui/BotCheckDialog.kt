package com.aniliphy.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aniliphy.core.BotCheck
import com.aniliphy.core.SiteCookies
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val CHECK_EVERY_MS = 700L

private const val PASSED_COOKIE = "cf_clearance"

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BotCheckDialog() {
    val url = BotCheck.pending ?: return
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val host = remember(url) { url.toHttpUrlOrNull()?.host ?: url }
    var web by remember { mutableStateOf<WebView?>(null) }
    val close = { BotCheck.pending = null }

    LaunchedEffect(web, url) {
        val view = web ?: return@LaunchedEffect
        val cookies = CookieManager.getInstance()
        while (true) {
            delay(CHECK_EVERY_MS)
            val header = cookies.getCookie(url) ?: continue
            if (PASSED_COOKIE !in header) continue
            SiteCookies.fromBrowser(url, header, view.settings.userAgentString)
            context.showToast("Passed. Try again")
            close()
            return@LaunchedEffect
        }
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(theme.background).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        "Robot check: $host",
                        style = TextStyle(color = theme.foreground, fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    )
                    BasicText(
                        "Pass it and this closes by itself.",
                        style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 13.sp),
                    )
                }
                PillButton("Close", onClick = close)
            }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        CookieManager.getInstance().setAcceptCookie(true)
                        loadUrl(url)
                        web = this
                    }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
