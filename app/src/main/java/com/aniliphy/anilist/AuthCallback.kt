package com.aniliphy.anilist

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Logging in with one tap: AniList's sign-in page, opened from the app, sends the token back to `aniliphy://auth`.
 * Any app can open such a link, so one is only taken while a login started here is waiting for it.
 */
object AuthCallback {
    const val SCHEME = "aniliphy"
    const val HOST = "auth"
    const val REDIRECT = "$SCHEME://$HOST"

    /** How long after tapping "Log in" the answer is still taken. */
    const val WAIT_MS = 30L * 60 * 1000

    fun authUrl(clientId: String): String {
        val id = URLEncoder.encode(clientId.trim(), "UTF-8")
        return "https://anilist.co/api/v2/oauth/authorize?client_id=$id&response_type=token"
    }

    /** What came back: a token, AniList's refusal, or nothing this app asked for. */
    sealed interface Answer {
        data class Token(val token: String, val expiresInSeconds: Long?) : Answer
        data class Refused(val reason: String) : Answer
        data object NotOurs : Answer
    }

    /**
     * Reads `aniliphy://auth#access_token=...&token_type=Bearer&expires_in=...` (the token comes after the #, so it
     * never reaches a server). [startedAt] is when the login was started here, or null if none was.
     */
    fun read(scheme: String?, host: String?, fragment: String?, query: String?, startedAt: Long?, now: Long): Answer {
        if (!scheme.equals(SCHEME, ignoreCase = true) || !host.equals(HOST, ignoreCase = true)) return Answer.NotOurs
        if (startedAt == null || now < startedAt || now - startedAt > WAIT_MS) return Answer.NotOurs
        val fields = fields(fragment) + fields(query)
        fields["error"]?.let { error ->
            val said = fields["error_description"]?.takeIf { it.isNotBlank() }
            return Answer.Refused(if (error == "access_denied") "You said no on AniList" else said ?: error)
        }
        val token = fields["access_token"]?.trim()?.takeIf { it.isNotEmpty() && it.length < 4096 && it.none(Char::isWhitespace) }
            ?: return Answer.Refused("AniList didn't send a token")
        return Answer.Token(token, fields["expires_in"]?.toLongOrNull())
    }

    private fun fields(part: String?): Map<String, String> = part.orEmpty().split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val value = pair.substringAfter('=', "")
        runCatching { URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8") }.getOrNull()
    }.toMap()
}
