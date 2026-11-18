package com.aniliphy.mal

import kotlinx.serialization.Serializable
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * Logging in to MyAnimeList: its own page in the browser, which sends a one-time code back to `aniliphy://mal`.
 * MAL only takes the "plain" PKCE method: the secret made here goes as the challenge, and again with the code, so the
 * code is worth nothing to an app that catches the link without it.
 */
object MalAuth {
    const val SCHEME = "aniliphy"
    const val HOST = "mal"
    const val REDIRECT = "$SCHEME://$HOST"
    const val TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"

    /** How long after tapping "Log in" the answer is still taken. */
    const val WAIT_MS = 30L * 60 * 1000

    private const val CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** A fresh PKCE verifier (MAL wants 43 to 128 of these characters). */
    fun verifier(random: SecureRandom = SecureRandom()): String = buildString { repeat(96) { append(CHARS[random.nextInt(CHARS.length)]) } }

    /** A fresh value to tell our own answer from any other. */
    fun state(random: SecureRandom = SecureRandom()): String = buildString { repeat(24) { append(CHARS[random.nextInt(62)]) } }

    fun authUrl(clientId: String, verifier: String, state: String): String =
        "https://myanimelist.net/v1/oauth2/authorize?response_type=code" +
            "&client_id=" + enc(clientId) +
            "&code_challenge=" + enc(verifier) +
            "&code_challenge_method=plain" +
            "&redirect_uri=" + enc(REDIRECT) +
            "&state=" + enc(state)

    /** The form sent to [TOKEN_URL] for the code. */
    fun codeForm(clientId: String, code: String, verifier: String): Map<String, String> = mapOf(
        "client_id" to clientId,
        "grant_type" to "authorization_code",
        "code" to code,
        "code_verifier" to verifier,
        "redirect_uri" to REDIRECT,
    )

    /** The form sent to [TOKEN_URL] when the access token runs out. */
    fun refreshForm(clientId: String, refreshToken: String): Map<String, String> = mapOf(
        "client_id" to clientId,
        "grant_type" to "refresh_token",
        "refresh_token" to refreshToken,
    )

    sealed interface Answer {
        data class Code(val code: String) : Answer
        data class Refused(val reason: String) : Answer
        data object NotOurs : Answer
    }

    /** Reads `aniliphy://mal?code=...&state=...`; [expectedState] and [startedAt] are from the login started here. */
    fun read(scheme: String?, host: String?, query: String?, expectedState: String?, startedAt: Long?, now: Long): Answer {
        if (!scheme.equals(SCHEME, ignoreCase = true) || !host.equals(HOST, ignoreCase = true)) return Answer.NotOurs
        if (expectedState == null || startedAt == null || now < startedAt || now - startedAt > WAIT_MS) return Answer.NotOurs
        val fields = fields(query)
        if (fields["state"] != expectedState) return Answer.NotOurs
        fields["error"]?.let { error ->
            return Answer.Refused(if (error == "access_denied") "You said no on MyAnimeList" else fields["message"] ?: fields["hint"] ?: error)
        }
        val code = fields["code"]?.trim()?.takeIf { it.isNotEmpty() && it.length < 4096 && it.none(Char::isWhitespace) }
            ?: return Answer.Refused("MyAnimeList didn't send a code")
        return Answer.Code(code)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun fields(part: String?): Map<String, String> = part.orEmpty().split('&').mapNotNull { pair ->
        val name = pair.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        runCatching { URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
    }.toMap()
}

/** What MAL's token page answers. */
@Serializable
data class MalTokens(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long = 0,
    /** When it was saved (ms), to know when it runs out. */
    val savedAt: Long = 0,
) {
    fun expiresAt(): Long = savedAt + expires_in * 1000
}
