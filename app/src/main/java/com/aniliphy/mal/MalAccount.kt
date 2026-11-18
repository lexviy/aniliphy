package com.aniliphy.mal

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.BuildConfig
import com.aniliphy.anilist.AniListServices
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.ListMirror
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
private data class MalAccountFile(val name: String = "", val since: Long = 0)

@Serializable
private data class MalLink(val malId: Int, val anime: Boolean, val title: String)

/**
 * The MyAnimeList side: the login, the queue of copies, and which AniList entry is which MAL title (so a removal can
 * be copied too). Tokens live in a private file, never in a backup or the Gist.
 */
class MalAccount private constructor(private val context: Context) : ListMirror.Listener {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenFile = File(context.filesDir, "mal_token.json")
    private val accountFile = File(context.filesDir, "mal_account.json")
    private val linksFile = File(context.filesDir, "mal_links.json")
    private val prefs = context.getSharedPreferences("mal", Context.MODE_PRIVATE)
    val queue = MalQueue(File(context.filesDir, MalQueue.FILE))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sending = Mutex()
    private val linksLock = Any()

    val clientId: String get() = BuildConfig.MAL_CLIENT_ID

    private val tokens = object : MalTokenStore {
        override fun tokens(): MalTokens? = tokenFile.readTextOrNull()?.let { runCatching { json.decodeFromString(MalTokens.serializer(), it) }.getOrNull() }
        override fun save(tokens: MalTokens?) {
            if (tokens == null) {
                tokenFile.delete()
                loggedOut()
            } else {
                tokenFile.writeTextAtomically(json.encodeToString(MalTokens.serializer(), tokens))
            }
        }
    }
    val api = MalApi(clientId, tokens)

    var name by mutableStateOf(account().name.takeIf { tokenFile.exists() })
        private set
    var since by mutableStateOf(account().since)
        private set
    var waiting by mutableIntStateOf(queue.all().size)
        private set
    /** Copies refused by MAL for good, for the settings page to say. */
    var lastProblem by mutableStateOf<String?>(null)
        private set

    var copy: Boolean
        get() = prefs.getBoolean("copy", true)
        set(value) {
            prefs.edit().putBoolean("copy", value).apply()
            copyState = value
        }
    var copyState by mutableStateOf(copy)
        private set

    private fun account(): MalAccountFile =
        accountFile.readTextOrNull()?.let { runCatching { json.decodeFromString(MalAccountFile.serializer(), it) }.getOrNull() } ?: MalAccountFile()

    private fun loggedOut() {
        accountFile.delete()
        name = null
    }

    // Login: MAL's page in the browser, back through aniliphy://mal.

    fun startLogin(): String {
        val verifier = MalAuth.verifier()
        val state = MalAuth.state()
        prefs.edit().putString("verifier", verifier).putString("state", state).putLong("started", System.currentTimeMillis()).apply()
        return MalAuth.authUrl(clientId, verifier, state)
    }

    /** Takes MAL's answer; null when the link wasn't ours. Throws what went wrong. */
    suspend fun finishLogin(uri: Uri): Boolean? {
        val answer = MalAuth.read(
            uri.scheme, uri.host, uri.encodedQuery, prefs.getString("state", null),
            prefs.getLong("started", 0L).takeIf { it > 0 }, System.currentTimeMillis(),
        )
        val verifier = prefs.getString("verifier", null)
        when (answer) {
            MalAuth.Answer.NotOurs -> return null
            is MalAuth.Answer.Refused -> {
                clearLogin()
                throw java.io.IOException(answer.reason)
            }
            is MalAuth.Answer.Code -> {
                clearLogin()
                api.login(answer.code, verifier ?: throw java.io.IOException("The login started on another phone"))
                val who = api.me()
                val now = System.currentTimeMillis()
                accountFile.writeTextAtomically(json.encodeToString(MalAccountFile.serializer(), MalAccountFile(who, now)))
                name = who
                since = now
                return true
            }
        }
    }

    private fun clearLogin() = prefs.edit().remove("verifier").remove("state").remove("started").apply()

    fun logout() {
        tokens.save(null)
        queue.clear()
        linksFile.delete()
        waiting = 0
    }

    // Copies.

    private fun links(): MutableMap<Int, MalLink> =
        linksFile.readTextOrNull()?.let { runCatching { json.decodeFromString<Map<Int, MalLink>>(it).toMutableMap() }.getOrNull() } ?: HashMap()

    private fun link(entryId: Int, link: MalLink?) = synchronized(linksLock) {
        val all = links()
        if (link == null) all.remove(entryId) else all[entryId] = link
        linksFile.writeTextAtomically(json.encodeToString(all.toMap()))
    }

    override fun saved(entry: ListMirror.Saved) {
        if (name == null || !copy) return
        val malId = entry.malId ?: return
        link(entry.entryId, MalLink(malId, entry.anime, entry.title))
        queue.add(MalWrite.Update(MalList.entry(malId, entry.anime, entry.status, entry.progress, entry.volumes, entry.score, entry.repeat), entry.title))
        waiting = queue.all().size
        flush()
    }

    override fun deleted(entryId: Int) {
        if (name == null || !copy) return
        val known = synchronized(linksLock) { links()[entryId] } ?: return
        link(entryId, null)
        queue.add(MalWrite.Delete(known.malId, known.anime, known.title))
        waiting = queue.all().size
        flush()
    }

    /** Sends what's waiting (does nothing while logged out; it's kept for after the next login). */
    fun flush() {
        if (name == null) return
        scope.launch {
            if (!sending.tryLock()) return@launch
            try {
                // MAL busy or the network gone: try again after a while, waiting longer each time (while the app runs).
                var wait = 15_000L
                while (true) {
                    val result = runCatching { queue.flush(api, gapMs = GAP_MS) }.getOrNull() ?: return@launch
                    waiting = result.left
                    result.dropped.lastOrNull()?.let { (write, why) -> lastProblem = "${write.title}: $why" }
                    val stop = result.stoppedBy
                    if (result.left == 0 || stop == null || stop is MalLoggedOutException) {
                        if (stop is MalLoggedOutException) lastProblem = stop.message
                        return@launch
                    }
                    android.util.Log.i("aniliphy-mal", "copy paused (${stop.message}), ${result.left} left; again in ${wait / 1000} s")
                    kotlinx.coroutines.delay(wait)
                    wait = (wait * 2).coerceAtMost(10 * 60_000L)
                }
            } finally {
                sending.unlock()
            }
        }
    }

    // Comparing the two lists before the first copy.

    /** AniList's lists against MAL's; reads both, writes nothing. */
    suspend fun compare(): MalComparison {
        val services = AniListServices.get(context)
        val userId = services.session.userId ?: throw java.io.IOException("Log in to AniList first")
        val anime = services.api.animeList(userId, refresh = true).value
        val manga = services.api.mangaList(userId, refresh = true).value
        val sides = (anime + manga).map { it.side() }
        // Learn the links now, so a later removal on AniList reaches MAL even before anything was saved here.
        synchronized(linksLock) {
            val all = links()
            (anime + manga).forEach { e -> e.media.idMal?.let { all[e.id] = MalLink(it, e.media.type != "MANGA", e.side().title) } }
            linksFile.writeTextAtomically(json.encodeToString(all.toMap()))
        }
        return MalList.compare(sides, api.list(anime = true) + api.list(anime = false))
    }

    private fun ListEntry.side() = AniListSide(
        mediaId = media.id,
        malId = media.idMal,
        anime = media.type != "MANGA",
        title = media.title.english ?: media.title.romaji ?: media.title.native ?: "#${media.id}",
        status = status ?: "PLANNING",
        progress = progress,
        volumes = progressVolumes,
        score = score,
        repeat = repeat,
    )

    /** Queues the chosen differences and sends them. */
    fun send(chosen: List<MalDifference>) {
        chosen.forEach { queue.add(MalWrite.Update(it.wanted, it.title)) }
        waiting = queue.all().size
        flush()
    }

    companion object {
        private const val GAP_MS = 400L
        @Volatile private var instance: MalAccount? = null

        fun get(context: Context): MalAccount = instance ?: synchronized(this) {
            instance ?: MalAccount(context.applicationContext).also {
                instance = it
                ListMirror.listener = it
            }
        }
    }
}
