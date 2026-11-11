package com.aniliphy

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.ui.AppRoot
import com.aniliphy.widget.WidgetIntents
import com.aniliphy.widget.WidgetKind
import com.aniliphy.work.NewReleasesWorker
import com.aniliphy.work.ReleaseNotifications

object ReaderKeys {
    var onVolume: ((up: Boolean) -> Boolean)? = null
}

object PlayerWindow {
    var pictureInPicture = false

    var aspect: Rational = Rational(16, 9)

    var inPictureInPicture by mutableStateOf(false)
        internal set
}

object OpenTabRequest {
    var tab by mutableStateOf<String?>(null)
}

/** A card tapped on the home screen widget, waiting for the app to open it. */
data class WidgetOpen(val kind: WidgetKind, val key: String)

object WidgetOpenRequest {
    var open by mutableStateOf<WidgetOpen?>(null)
}

class MainActivity : ComponentActivity() {
    private fun take(intent: Intent?) {
        takeWidget(intent)
        val tab = intent?.getStringExtra(ReleaseNotifications.EXTRA_TAB) ?: return
        OpenTabRequest.tab = tab
        intent.removeExtra(ReleaseNotifications.EXTRA_TAB)
    }

    private fun takeWidget(intent: Intent?) {
        if (intent?.action != WidgetIntents.ACTION_OPEN) return
        WidgetIntents.parse(intent.action, intent.getStringExtra(WidgetIntents.EXTRA_KIND), intent.getStringExtra(WidgetIntents.EXTRA_KEY))
            ?.let { WidgetOpenRequest.open = it }
        // Tells the launcher which shortcuts get used, so it can rank them.
        intent.data?.pathSegments?.takeIf { it.firstOrNull() == "shortcut" }?.getOrNull(1)
            ?.let { androidx.core.content.pm.ShortcutManagerCompat.reportShortcutUsed(this, it) }
        intent.action = null
        intent.removeExtra(WidgetIntents.EXTRA_KIND)
        intent.removeExtra(WidgetIntents.EXTRA_KEY)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onStop() {
        super.onStop()
        com.aniliphy.widget.WidgetData.refreshSoon(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PlayerWindow.pictureInPicture) {
            runCatching {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(PlayerWindow.aspect).build())
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerWindow.inPictureInPicture = isInPictureInPictureMode
    }

    private fun isVolume(keyCode: Int) = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handler = ReaderKeys.onVolume
        if (handler != null && isVolume(keyCode) && handler(keyCode == KeyEvent.KEYCODE_VOLUME_UP)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (ReaderKeys.onVolume != null && isVolume(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        take(intent)
        com.aniliphy.core.SiteCookies.init(this)
        com.aniliphy.core.Sources.init(this)
        com.aniliphy.ui.AppIcon.tidy(this)
        NewReleasesWorker.schedule(this)
        setContent {
            AppRoot()
        }
    }
}
