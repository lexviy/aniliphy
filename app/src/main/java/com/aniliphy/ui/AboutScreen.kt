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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/lexviy/aniliphy"
private const val ANILIXY_URL = "https://github.com/lexviy/anilixy"
private const val HYDE_URL = "https://github.com/HyDE-Project/HyDE"

private const val DISCLAIMER =
    "aniliphy doesn't host any content. It is a client for AniList, and it uses third-party " +
        "websites for the anime, manga and light novels. Those sites decide what is available, " +
        "and you are responsible for following the laws of your country. Support the official " +
        "releases when you can."

private val LIBRARIES = listOf(
    "AndroidX Activity" to "Apache-2.0",
    "AndroidX Media3 (ExoPlayer)" to "Apache-2.0",
    "AndroidX WorkManager" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "Jetpack Compose" to "Apache-2.0",
    "jsoup" to "MIT",
    "Kotlin, Coroutines and Serialization" to "Apache-2.0",
    "Material 3" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "PdfBox-Android" to "Apache-2.0",
)

@Composable
fun AboutScreen(onOpenLicense: () -> Unit, onBack: () -> Unit) {
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

        SectionTitle("Credits")
        ActionRow(
            label = "anilixy, by lexviy",
            description = "The desktop app this one is based on",
            onClick = { open(ANILIXY_URL) },
        )
        ActionRow(
            label = "HyDE",
            description = "The shaders are ported from this project (GPL-3.0)",
            onClick = { open(HYDE_URL) },
        )

        SectionTitle("Disclaimer")
        BasicText(
            DISCLAIMER,
            style = TextStyle(color = theme.foreground.copy(alpha = 0.8f), fontSize = 14.sp, lineHeight = 20.sp),
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        SectionTitle("Libraries")
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
fun LicenseScreen(onBack: () -> Unit) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current.applicationContext
    var paragraphs by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(Unit) {
        paragraphs = withContext(Dispatchers.IO) {
            context.assets.open("LICENSE").bufferedReader().use { it.readText() }
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
        item { BackHeader(title = "License", onBack = onBack) }
        items(paragraphs) { paragraph ->
            BasicText(
                paragraph,
                style = TextStyle(color = theme.foreground.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )
        }
    }
}
