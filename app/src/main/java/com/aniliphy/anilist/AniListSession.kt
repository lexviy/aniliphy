package com.aniliphy.anilist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data object Guest : SessionState
    data class LoggedIn(val viewer: Viewer) : SessionState
}

class AniListSession(
    private val store: AccountStore,
    private val cache: ApiCache,
    private val api: () -> AniListApi,
) {
    var state by mutableStateOf<SessionState>(SessionState.Loading)
        private set

    var notice by mutableStateOf<String?>(null)

    @Volatile
    private var token: String? = null
    private var checked = false

    fun currentToken(): String? = token

    val userId: Int? get() = (state as? SessionState.LoggedIn)?.viewer?.id

    suspend fun restore() {
        val saved = disk { store.token() }
        token = saved
        if (saved == null) {
            state = if (disk { store.isGuest() }) SessionState.Guest else SessionState.LoggedOut
            return
        }
        val viewer = disk { store.viewer() }
        if (viewer != null) state = SessionState.LoggedIn(viewer)
        if (checked && viewer != null) return
        try {
            val fresh = api().viewer(saved)
            disk { store.saveViewer(fresh) }
            checked = true
            state = SessionState.LoggedIn(fresh)
        } catch (_: InvalidTokenException) {
            tokenRejected()
        } catch (e: AniListException) {
            if (viewer == null) {
                notice = e.message
                state = SessionState.LoggedOut
            }
        }
    }

    suspend fun login(newToken: String) {
        val viewer = api().viewer(newToken)
        disk {
            store.saveToken(newToken)
            store.saveViewer(viewer)
            store.saveGuest(false)
        }
        token = newToken
        checked = true
        notice = null
        state = SessionState.LoggedIn(viewer)
    }

    suspend fun continueAsGuest() {
        disk { store.saveGuest(true) }
        token = null
        notice = null
        state = SessionState.Guest
    }

    suspend fun logout() {
        disk { forget() }
        notice = null
        state = SessionState.LoggedOut
    }

    suspend fun tokenRejected() {
        disk { forget() }
        notice = "Your AniList login expired, so log in again"
        state = SessionState.LoggedOut
    }

    private fun forget() {
        token = null
        checked = false
        store.deleteToken()
        store.deleteViewer()
        store.saveGuest(false)
        cache.invalidateUser()
    }

    private suspend fun <T> disk(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}

class AniListServices private constructor(context: Context) {
    val store = AccountStore(context.filesDir)
    val cache = ApiCache(File(context.cacheDir, "anilist")).also { thread(isDaemon = true) { it.prune() } }
    private val client = GraphQlClient(OkHttpTransport(), cache)
    val session: AniListSession = AniListSession(store, cache) { api }
    val api: AniListApi = GraphQlAniListApi(client) { session.currentToken() }

    companion object {
        @Volatile
        private var instance: AniListServices? = null

        fun get(context: Context): AniListServices = instance ?: synchronized(this) {
            instance ?: AniListServices(context.applicationContext).also { instance = it }
        }
    }
}
