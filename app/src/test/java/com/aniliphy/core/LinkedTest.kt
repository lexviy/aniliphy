package com.aniliphy.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkedTest {
    @Test
    fun placesPointToTheirEntry() {
        val linked = Sources.linked(
            mapOf(
                30 to "ta1:/anime/frieren",
                10 to "tm2:/manga/abc",
                20 to "ta1:/anime/frieren",
                40 to null,
                50 to "not a place",
            ),
        )
        assertEquals(mapOf("tm2:/manga/abc" to 10, "ta1:/anime/frieren" to 20), linked)
        assertEquals(20, linked[Sources.place("ta1", "/anime/frieren")])
    }
}
