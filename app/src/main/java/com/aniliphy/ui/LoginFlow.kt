package com.aniliphy.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.anilist.AniListException
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.AuthCallback
import com.aniliphy.anilist.InvalidTokenException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one-tap login: "Log in with AniList" opens anilist.co in the browser, and AniList sends the token back to
 * aniliphy://auth. The time the login was started is kept on disk, since Android may close the app meanwhile.
 */
internal object LoginFlow {
    /** The AniList app the one-tap login goes through (its redirect is aniliphy://auth). */
    const val CLIENT_ID = "57255"

    private const val PREFS = "login"
    private const val STARTED = "started_at"

    /** Waiting for the browser to come back. */
    var waiting by mutableStateOf(false)
        private set

    /** Checking the token that came back. */
    var checking by mutableStateOf(false)
        private set

    var problem by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun start(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(STARTED, System.currentTimeMillis()).apply()
        problem = null
        waiting = true
        context.openLink(AuthCallback.authUrl(CLIENT_ID))
    }

    fun cancel(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(STARTED).apply()
        waiting = false
    }

    /** Takes [uri] if it's AniList's answer to a login started here; true when it was ours to take. */
    fun take(context: Context, uri: Uri): Boolean {
        if (!uri.scheme.equals(AuthCallback.SCHEME, ignoreCase = true)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val started = prefs.getLong(STARTED, 0L).takeIf { it > 0 }
        when (val answer = AuthCallback.read(uri.scheme, uri.host, uri.encodedFragment, uri.encodedQuery, started, System.currentTimeMillis())) {
            AuthCallback.Answer.NotOurs -> return true
            is AuthCallback.Answer.Refused -> {
                prefs.edit().remove(STARTED).apply()
                waiting = false
                problem = answer.reason
            }
            is AuthCallback.Answer.Token -> {
                prefs.edit().remove(STARTED).apply()
                waiting = false
                checking = true
                problem = null
                val session = AniListServices.get(context).session
                scope.launch {
                    try {
                        // Asks AniList who this is first: the token kept now is only replaced by one that works.
                        session.login(answer.token)
                    } catch (_: InvalidTokenException) {
                        problem = "AniList didn't accept the login. Try again"
                    } catch (e: AniListException) {
                        problem = e.message
                    } finally {
                        checking = false
                    }
                }
            }
        }
        return true
    }
}
