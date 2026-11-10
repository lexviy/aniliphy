package com.aniliphy.manga

import com.aniliphy.source.SourceSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FoundSourcesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun titlesMatchWithoutCaseOrPunctuation() {
        assertEquals("kaguya sama love is war", FoundSources.normalized("Kaguya-sama: Love Is War!"))
        val results = listOf(SourceSeries("Kaguya-sama - Love is War", "a"), SourceSeries("Kaguya-sama: Love is War Doujin", "b"))
        assertEquals("a", FoundSources.exact(results, listOf("Kaguya-sama wa Kokurasetai", "Kaguya-sama: Love Is War"))?.url)
    }

    @Test
    fun onlyOneExactMatchIsTaken() {
        assertNull(FoundSources.exact(listOf(SourceSeries("Berserk of Gluttony", "a")), listOf("Berserk")))
        assertNull(FoundSources.exact(listOf(SourceSeries("Monster", "a"), SourceSeries("MONSTER", "b")), listOf("Monster")))
    }

    @Test
    fun theSourcesAreTriedInOrder() {
        val asked = mutableListOf<String>()
        val id = FoundSources.look(listOf("One Piece"), listOf("first", "second")) { source, query ->
            asked += "$source:$query"
            if (source == "second") listOf(SourceSeries("One Piece", "https://second.example/one_piece")) else emptyList()
        }
        assertEquals("second:https://second.example/one_piece", id)
        assertEquals(listOf("first:One Piece", "second:One Piece"), asked)
        assertNull(FoundSources.look(listOf("Nothing"), listOf("first")) { _, _ -> emptyList() })
    }

    @Test(expected = IOException::class)
    fun aSiteDownIsNotANoMatch() {
        FoundSources.look(listOf("One Piece"), listOf("first")) { _, _ -> throw IOException("down") }
    }

    @Test
    fun whatWasFoundIsKept() {
        val file = File(folder.root, "found.json")
        FoundSources(file).put(7, FoundSources.Found("first:x", 10))
        FoundSources(file).put(8, FoundSources.Found(null, 20))
        assertEquals(mapOf(7 to FoundSources.Found("first:x", 10), 8 to FoundSources.Found(null, 20)), FoundSources(file).all())
    }
}
