package com.aniliphy.anilist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendNewsTest {
    private fun a(id: Int, media: Int, status: String = "completed") =
        Activity(id = id, status = status, media = Media(id = media, type = "ANIME"), user = UserCard(id = 7, name = "x"))

    @Test fun firstCheckOnlyRemembers() {
        val feed = listOf(a(10, 1), a(9, 2))
        assertTrue(FriendNews.finished(feed, null, setOf(1, 2)).isEmpty())
        assertEquals(10, FriendNews.newest(feed, null))
    }

    @Test fun onlyNewCompletionsOfYourTitles() {
        val feed = listOf(a(12, 1), a(11, 3), a(10, 2, "watched episode"), a(8, 2))
        assertEquals(listOf(12), FriendNews.finished(feed, 9, setOf(1, 2)).map { it.id })
        assertEquals(12, FriendNews.newest(feed, 9))
        assertEquals(20, FriendNews.newest(emptyList(), 20))
        assertTrue(FriendNews.finished(feed, 9, setOf(1, 2), me = 7).isEmpty())
    }
}
