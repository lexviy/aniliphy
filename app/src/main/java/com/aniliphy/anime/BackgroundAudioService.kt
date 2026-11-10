package com.aniliphy.anime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aniliphy.MainActivity
import com.aniliphy.R

class BackgroundAudioService : MediaSessionService() {
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = BackgroundAudio.player
        if (player == null) {
            stopSelf()
            return
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val session = MediaSession.Builder(this, player).setSessionActivity(open).build()
        BackgroundAudio.session = session
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = BackgroundAudio.session

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = BackgroundAudio.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        BackgroundAudio.releaseSession()
        super.onDestroy()
    }
}

object BackgroundAudio {
    internal var player: Player? = null
    internal var session: MediaSession? = null

    fun start(context: Context, player: Player) {
        if (this.player === player && session != null) return
        stop(context)
        this.player = player
        context.startService(Intent(context, BackgroundAudioService::class.java))
    }

    fun stop(context: Context) {
        releaseSession()
        player = null
        context.stopService(Intent(context, BackgroundAudioService::class.java))
    }

    internal fun releaseSession() {
        session?.release()
        session = null
    }
}
