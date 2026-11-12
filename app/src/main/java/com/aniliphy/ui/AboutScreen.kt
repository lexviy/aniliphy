package com.aniliphy.ui

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/lexviy/aniliphy"

private const val DISCLAIMER =
    "aniliphy doesn't host or link to any content, and it doesn't come with any source: it is a client for " +
        "AniList with a player and readers. What it plays or shows depends entirely on the extensions you choose " +
        "to install, which are not part of aniliphy. You are responsible for what you install and for following " +
        "the laws of your country. Support the official releases when you can."

private val LIBRARIES = listOf(
    "AndroidX Activity" to "Apache-2.0",
    "AndroidX Media3 (ExoPlayer)" to "Apache-2.0",
    "AndroidX Preference" to "Apache-2.0",
    "AndroidX WorkManager" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "Dela Gothic One (font)" to "OFL-1.1",
    "Inter (font)" to "OFL-1.1",
    "JetBrains Mono (font)" to "OFL-1.1",
    "Outfit (font)" to "OFL-1.1",
    "Barlow Semi Condensed (font)" to "OFL-1.1",
    "DotGothic16 (font)" to "OFL-1.1",
    "IBM Plex Sans Condensed (font)" to "OFL-1.1",
    "Kalam (font)" to "OFL-1.1",
    "M PLUS 1p (font)" to "OFL-1.1",
    "M PLUS Rounded 1c (font)" to "OFL-1.1",
    "Manrope (font)" to "OFL-1.1",
    "Share Tech Mono (font)" to "OFL-1.1",
    "Shippori Mincho B1 (font)" to "OFL-1.1",
    "Space Mono (font)" to "OFL-1.1",
    "Yuji Syuku (font)" to "OFL-1.1",
    "Zen Maru Gothic (font)" to "OFL-1.1",
    "Jetpack Compose" to "Apache-2.0",
    "jsoup" to "MIT",
    "Kotlin, Coroutines and Serialization" to "Apache-2.0",
    "Injekt" to "MIT",
    "Material 3" to "Apache-2.0",
    "Mihon and Aniyomi extension API" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "PdfBox-Android" to "Apache-2.0",
    "RxJava" to "Apache-2.0",
)

@Composable
fun AboutScreen(onOpenLicense: () -> Unit, onOpenFontLicense: () -> Unit, onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val icon = remember { AppIcon.current(context) }
    val version = remember {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    fun open(url: String) {
        runCatching { uriHandler.openUri(url) }.onFailure {
            Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        BackHeader(title = "About", onBack = onBack)

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Image(
                painter = painterResource(icon.foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(icon.background),
            )
            Spacer(Modifier.height(10.dp))
            BasicText(
                "aniliphy",
                style = TextStyle(color = theme.foreground, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            )
            BasicText(
                "Version $version",
                style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 13.sp),
            )
        }

        SectionTitle("Project")
        ActionRow(
            label = "Source code",
            description = "github.com/lexviy/aniliphy",
            onClick = { open(SOURCE_URL) },
        )
        ActionRow(
            label = "License",
            description = "GNU Affero General Public License v3.0",
            onClick = onOpenLicense,
        )

        SectionTitle("Disclaimer")
        BasicText(
            DISCLAIMER,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp, lineHeight = 20.sp),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        SectionTitle("Libraries")
        ActionRow(
            label = "Font license",
            description = "SIL Open Font License 1.1, with each font's copyright",
            onClick = onOpenFontLicense,
        )
        LIBRARIES.forEach { (name, license) ->
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 5.dp),
            ) {
                BasicText(
                    name,
                    style = TextStyle(color = theme.foreground, fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                BasicText(
                    license,
                    style = TextStyle(color = theme.foreground.copy(alpha = 0.65f), fontSize = 14.sp),
                )
            }
        }
    }
}

@Composable
fun LicenseScreen(onBack: () -> Unit, asset: String = "LICENSE", title: String = "License") {
    val theme = LocalAppTheme.current
    val context = LocalContext.current.applicationContext
    var paragraphs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        paragraphs = withContext(Dispatchers.IO) {
            context.assets.open(asset).bufferedReader().use { it.readText() }
                .split(Regex("""\n\s*\n"""))
                .map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
                .filter { it.isNotEmpty() }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        item { BackHeader(title = title, onBack = onBack) }
        items(paragraphs) { paragraph ->
            BasicText(
                paragraph,
                style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
    }
}
