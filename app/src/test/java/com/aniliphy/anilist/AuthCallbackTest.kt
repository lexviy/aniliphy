package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthCallbackTest {
    private val now = 10_000_000L

    private fun read(fragment: String?, startedAt: Long? = now - 1_000, scheme: String = "aniliphy", host: String = "auth", query: String? = null) =
        AuthCallback.read(scheme, host, fragment, query, startedAt, now)

    @Test
    fun takesTheTokenFromAfterTheHash() {
        assertEquals(
            AuthCallback.Answer.Token("abc.def-123", 31536000),
            read("access_token=abc.def-123&token_type=Bearer&expires_in=31536000"),
        )
    }

    @Test
    fun onlyWhileALoginStartedHereIsWaiting() {
        assertEquals(AuthCallback.Answer.NotOurs, read("access_token=x", startedAt = null))
        assertEquals(AuthCallback.Answer.NotOurs, read("access_token=x", startedAt = now - AuthCallback.WAIT_MS - 1))
        assertEquals(AuthCallback.Answer.NotOurs, read("access_token=x", startedAt = now + 5_000))
    }

    @Test
    fun onlyOurAddress() {
        assertEquals(AuthCallback.Answer.NotOurs, read("access_token=x", scheme = "https"))
        assertEquals(AuthCallback.Answer.NotOurs, read("access_token=x", host = "evil"))
    }

    @Test
    fun aRefusalSaysSo() {
        assertEquals(AuthCallback.Answer.Refused("You said no on AniList"), read(null, query = "error=access_denied"))
        assertEquals(AuthCallback.Answer.Refused("AniList didn't send a token"), read("token_type=Bearer"))
        assertEquals(AuthCallback.Answer.Refused("AniList didn't send a token"), read("access_token=a%20b"))
    }

    @Test
    fun authUrlAsksForAToken() {
        assertEquals(
            "https://anilist.co/api/v2/oauth/authorize?client_id=123&response_type=token",
            AuthCallback.authUrl(" 123 "),
        )
    }
}
