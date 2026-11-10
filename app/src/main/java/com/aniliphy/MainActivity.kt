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

class MainActivity : ComponentActivity() {
    private fun take(intent: Intent?) {
        val tab = intent?.getStringExtra(ReleaseNotifications.EXTRA_TAB) ?: return
        OpenTabRequest.tab = tab
        intent.removeExtra(ReleaseNotifications.EXTRA_TAB)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
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
