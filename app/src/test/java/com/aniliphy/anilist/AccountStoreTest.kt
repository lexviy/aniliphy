package com.aniliphy.anilist

import com.aniliphy.core.writeTextAtomically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AccountStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val store by lazy { AccountStore(folder.root) }

    @Test fun tokenRoundTripKeepsAnilixysFormat() {
        assertNull(store.token())
        store.saveToken("abc")
        assertEquals("abc", store.token())
        assertEquals("""{"access_token":"abc"}""", File(folder.root, "token.json").readText())
        store.deleteToken()
        assertNull(store.token())
    }

    @Test fun clientIdRoundTrip() {
        store.saveClientId("123")
        assertEquals("123", store.clientId())
    }

    @Test fun guestDefaultsToOff() {
        assertFalse(store.isGuest())
        store.saveGuest(true)
        assertTrue(store.isGuest())
        store.saveGuest(false)
        assertFalse(store.isGuest())
    }

    @Test fun damagedFilesReadAsMissing() {
        File(folder.root, "token.json").writeText("{oops")
        assertNull(store.token())
        File(folder.root, "token.json").writeText("[1]")
        assertNull(store.token())
        File(folder.root, "guest.json").writeText("{\"guest\":{}}")
        assertFalse(store.isGuest())
    }

    @Test fun authUrl() {
        assertEquals(
            "https://anilist.co/api/v2/oauth/authorize?client_id=12%263&response_type=token",
            AccountStore.authUrl(" 12&3 "),
        )
    }

    @Test fun atomicWriteLeavesNoTemporaryFiles() {
        val target = File(folder.root, "nested/deep/file.json")
        target.writeTextAtomically("one")
        target.writeTextAtomically("two")
        assertEquals("two", target.readText())
        assertEquals(listOf("file.json"), target.parentFile!!.list()!!.toList())
    }
}
