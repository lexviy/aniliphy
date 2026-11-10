package com.aniliphy.anilist

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class AniListSessionTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { AccountStore(File(folder.root, "files")) }
    private val cacheFolder by lazy { File(folder.root, "cache") }
    private val cache by lazy { ApiCache(cacheFolder) }

    private val viewerAnswer = ok("""{"Viewer":{"id":7,"name":"Me"}}""")
    private val rejected = HttpReply(400, """{"errors":[{"message":"Invalid token"}],"data":null}""")

    private fun session(answer: (FakeTransport.Request) -> HttpReply): AniListSession {
        lateinit var session: AniListSession
        val api = GraphQlAniListApi(GraphQlClient(FakeTransport(answer), cache)) { session.currentToken() }
        session = AniListSession(store, cache) { api }
        return session
    }

    @Test fun nothingSavedMeansLoggedOut() = runTest {
        val session = session { viewerAnswer }
        session.restore()
        assertEquals(SessionState.LoggedOut, session.state)
    }

    @Test fun guestIsRemembered() = runTest {
        session { viewerAnswer }.continueAsGuest()
        val next = session { viewerAnswer }
        next.restore()
        assertEquals(SessionState.Guest, next.state)
        assertNull(next.currentToken())
    }

    @Test fun loginSavesTokenAndLeavesGuest() = runTest {
        val session = session { viewerAnswer }
        session.continueAsGuest()
        session.login("abc")
        assertEquals(7, session.userId)
        assertEquals("abc", store.token())
        assertFalse(store.isGuest())
    }

    @Test fun rejectedTokenIsNotSaved() = runTest {
        val session = session { rejected }
        val failure = runCatching { session.login("bad") }.exceptionOrNull()
        assertTrue(failure is InvalidTokenException)
        assertNull(store.token())
        assertEquals(SessionState.Loading, session.state)
    }

    @Test fun restoreWorksOfflineWithASavedProfile() = runTest {
        session { viewerAnswer }.login("abc")
        val offline = session { throw IOException("down") }
        offline.restore()
        assertEquals(7, offline.userId)
        assertEquals("abc", offline.currentToken())
    }

    @Test fun restoreOfflineWithoutAProfileKeepsTheTokenForLater() = runTest {
        store.saveToken("abc")
        val offline = session { throw IOException("down") }
        offline.restore()
        assertEquals(SessionState.LoggedOut, offline.state)
        assertNotNull(offline.notice)
        assertEquals("abc", store.token())
    }

    @Test fun restoreForgetsAnExpiredToken() = runTest {
        session { viewerAnswer }.login("abc")
        val next = session { rejected }
        next.restore()
        assertEquals(SessionState.LoggedOut, next.state)
        assertNull(store.token())
        assertNull(store.viewer())
        assertNotNull(next.notice)
    }

    @Test fun restoreChecksTheTokenOnlyOnce() = runTest {
        var checks = 0
        val session = session { checks++; viewerAnswer }
        session.login("abc")
        checks = 0
        session.restore()
        session.restore()
        assertEquals(0, checks)
    }

    @Test fun restoreNoticesEverythingWasDeleted() = runTest {
        val session = session { viewerAnswer }
        session.login("abc")
        store.deleteToken()
        session.restore()
        assertEquals(SessionState.LoggedOut, session.state)
        assertNull(session.currentToken())
    }

    @Test fun logoutRemovesTheLoginAndTheAccountsCache() = runTest {
        val session = session { viewerAnswer }
        session.login("abc")
        val cached = cache.entry("q", "", "abc")
        cache.write(cached, cache.currentGeneration(), "{}")
        assertTrue(cached.exists())

        session.logout()

        assertEquals(SessionState.LoggedOut, session.state)
        assertNull(store.token())
        assertNull(store.viewer())
        assertNull(session.currentToken())
        assertFalse(cached.exists())
    }
}
