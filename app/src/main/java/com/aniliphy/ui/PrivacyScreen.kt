package com.aniliphy.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aniliphy.core.Incognito
import com.aniliphy.core.IncognitoOff
import com.aniliphy.core.LockAfter

/** The app lock: locked on a cold start and after being away longer than the chosen time. */
object AppLock {
    var locked by mutableStateOf(false)
    private var leftAt = 0L
    private var asking = false

    /** The phone has a PIN, pattern, password or fingerprint to ask for. */
    fun canLock(context: Context): Boolean =
        !TvDevice.isTv(context) && context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun onCreate(activity: Activity, fresh: Boolean) {
        val prefs = Incognito.prefs(activity)
        if (fresh && prefs.lock && canLock(activity)) locked = true
        applyRecents(activity)
    }

    fun onStop(activity: Activity) {
        if (!activity.isChangingConfigurations) leftAt = SystemClock.elapsedRealtime()
    }

    fun onStart(activity: Activity) {
        val prefs = Incognito.prefs(activity)
        if (!prefs.lock || !canLock(activity)) {
            locked = false
            return
        }
        if (leftAt > 0 && SystemClock.elapsedRealtime() - leftAt >= prefs.lockAfter.ms) locked = true
        leftAt = 0
        if (locked) ask(activity)
    }

    /** "Hide in recent apps": a blank card in the switcher (and no screenshots of the app). */
    fun applyRecents(activity: Activity) {
        if (Incognito.prefs(activity).hideInRecents) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Asks for the fingerprint, face or the phone's PIN; unlocks on success. */
    fun ask(activity: Activity) {
        if (asking || !locked) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val intent = activity.getSystemService(KeyguardManager::class.java)
                ?.createConfirmDeviceCredentialIntent("aniliphy", "Unlock to continue")
            if (intent == null) {
                locked = false
                return
            }
            asking = true
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST)
            return
        }
        val builder = BiometricPrompt.Builder(activity).setTitle("Unlock aniliphy")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        asking = true
        runCatching {
        builder.build().authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    asking = false
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    asking = false
                }
            },
        )
        }.onFailure { asking = false }
    }

    /** For the old (Android 8) PIN screen's answer. */
    fun onResult(requestCode: Int, ok: Boolean) {
        if (requestCode != REQUEST) return
        asking = false
        if (ok) locked = false
    }

    const val REQUEST = 0x10C4
}

/** Over everything while locked. */
@Composable
internal fun LockCover() {
    if (!AppLock.locked) return
    val theme = LocalAppTheme.current
    val activity = LocalActivity.current
    // Whatever was being typed under it stays hidden, keyboard included.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) {
        // A field under it can take focus again as its screen loads; floating keyboards don't show in the insets.
        val imm = view.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        while (true) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            kotlinx.coroutines.delay(400)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxSize()
            .background(theme.background)
            // Nothing under it can be touched.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        BasicText("aniliphy", style = TextStyle(color = theme.foreground, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(72.dp).clip(CircleShape).border(2.dp, theme.primary, CircleShape).clickable { activity?.let(AppLock::ask) },
        ) {
            BasicText("◉", style = TextStyle(color = theme.primary, fontSize = 30.sp))
        }
        BasicText("Touch the sensor or use your PIN", style = TextStyle(color = theme.foreground.copy(alpha = 0.7f), fontSize = 14.sp))
        PillButton("Unlock", onClick = { activity?.let(AppLock::ask) }, alive = true)
    }
}

/** While incognito is on: a strip in the status bar area, in the look's warning colour. */
@Composable
internal fun IncognitoStrip() {
    if (!Incognito.on) return
    val theme = LocalAppTheme.current
    Box(
        contentAlignment = Alignment.BottomCenter,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .drawBehind {
                drawRect(theme.background.copy(alpha = 0.85f))
                // Diagonal stripes, like tape across the top.
                val step = 14.dp.toPx()
                var x = -size.height
                while (x < size.width) {
                    drawLine(theme.warning.copy(alpha = 0.22f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = step / 2)
                    x += step
                }
                drawLine(theme.warning, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.5.dp.toPx())
            },
    ) {
        BasicText(
            "◐ INCOGNITO · NOTHING KEPT",
            style = NumberStyle(color = theme.warning, fontSize = 10.sp, bold = true),
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

/** Settings › Privacy. */
@Composable
internal fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val prefs = remember { Incognito.prefs(context) }
    var noLock by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackHeader("Privacy", onBack)
        SectionTitle("Incognito")
        SwitchRow(
            "Incognito", Incognito.on, { Incognito.set(context, it) },
            description = Incognito.prefs(context).incognitoUntil.toLongOrNull()?.takeIf { it > 0 && Incognito.on }?.let {
                "Until " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", java.util.Locale.ENGLISH))
            } ?: "Until you turn it off",
        )
        SegmentedRow(
            label = "Turn off by itself",
            options = IncognitoOff.entries,
            selected = prefs.autoOff,
            optionLabel = { it.label },
            onSelect = {
                prefs.autoOff = it
                if (Incognito.on) prefs.incognitoUntil = Incognito.until(it, System.currentTimeMillis()).toString()
            },
        )
        Hint(
            "While it's on:\n✕ where you stopped isn't kept (anime, manga, novels), nor Continue\n" +
                "✕ nothing goes to AniList (episodes, chapters, list changes, likes, posts) nor to the Gist sync\n" +
                "✕ nothing counts for history, achievements, Wrapped or the activity log\n" +
                "✓ downloads, settings and the rest work as normal\n" +
                "Also in the menu (☰) and as a quick-settings tile.",
        )
        // A TV has no fingerprint or face, and is shared by the room anyway: no lock there.
        val tv = LocalWindowLayout.current == WindowLayout.Tv
        if (!tv) SectionTitle("Lock")
        if (!tv) SwitchRow(
            "Lock the app", prefs.lock,
            {
                if (it && !AppLock.canLock(context)) noLock = true
                else prefs.lock = it
            },
            description = "Fingerprint, face or the phone's PIN when it opens",
        )
        if (noLock && !tv) Hint("Set a screen lock on the phone first (Android settings › Security). The app uses the same one.", color = LocalAppTheme.current.warning)
        if (prefs.lock && !tv) {
            SegmentedRow(
                label = "Ask again after",
                description = "Time away from the app before it locks again",
                options = LockAfter.entries,
                selected = prefs.lockAfter,
                optionLabel = { it.label },
                onSelect = { prefs.lockAfter = it },
            )
        }
        SwitchRow(
            "Hide in recent apps", prefs.hideInRecents,
            {
                prefs.hideInRecents = it
                activity?.let(AppLock::applyRecents)
            },
            description = "A blank card in the app switcher. Screenshots of the app are blocked too (Android ties the two together)",
        )
        if (!tv) Hint("The widget, notifications and downloads keep working while the app is locked; opening one of them asks first.")
    }
}
