package com.aniliphy.novels

import com.aniliphy.anilist.Media
import com.aniliphy.anilist.MediaTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelLibraryTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(name: String) = VolumeFile(name, name)

    @Test fun titlesComeFromFileNames() {
        assertEquals("Classroom of the Elite", NovelLibrary.guessTitle("Classroom of the Elite (Light Novel) Vol. 1"))
        assertEquals("The Beginning After The End: Providence", NovelLibrary.guessTitle("The Beginning After The End_ Providence, Book 11"))
        assertEquals("Wait For Me Yesterday in Spring", NovelLibrary.guessTitle("Wait For Me Yesterday in Spring"))
        assertEquals("86", NovelLibrary.guessTitle("86 - Volume 3 [Yen Press]"))
    }

    @Test fun volumesSortAsNumbers() {
        val sorted = listOf("B Vol 10", "B Vol 9", "B Vol 1").sortedWith(NovelLibrary.NATURAL)
        assertEquals(listOf("B Vol 1", "B Vol 9", "B Vol 10"), sorted)
    }

    @Test fun onlyBooksStayAndThePreferredFormatWins() {
        val files = listOf(file("a.pdf"), file("a.epub"), file("b.pdf"), file("notes.txt"))
        assertEquals(listOf("a.epub", "b.pdf"), NovelLibrary.chooseFormats(files, BookFormat.Epub).map { it.name })
        assertEquals(listOf("a.pdf", "b.pdf"), NovelLibrary.chooseFormats(files, BookFormat.Pdf).map { it.name })
    }

    private fun media(id: Int, english: String) = Media(id = id, title = MediaTitle(english = english))

    @Test fun filesJoinTheListTitleTheyMatchAndTheRestGroupByName() {
        val series = NovelLibrary.group(
            listOf(
                file("Classroom of the Elite (Light Novel) Vol. 2.epub"),
                file("Classroom of the Elite (Light Novel) Vol. 1.epub"),
                file("Other Tale Vol 2.epub"),
                file("Other Tale Vol 1.epub"),
            ),
            listOf(media(5, "Classroom of the Elite"), media(6, "Nothing Here")),
            emptyList(),
        )
        assertEquals(listOf("Classroom of the Elite", "Other Tale"), series.map { it.title })
        assertEquals("5", series[0].key)
        assertEquals(5, series[0].mediaId)
        assertEquals("Classroom of the Elite (Light Novel) Vol. 1.epub", series[0].volumes[0].name)
        assertEquals("local:other-tale", series[1].key)
        assertEquals(null, series[1].mediaId)
    }

    @Test fun customNovelsClaimTheirFilesFirst() {
        val a = file("Apotheosis The Beginning After the End, Book 12.epub")
        val b = file("The Beginning After The End_ Providence, Book 11.epub")
        val series = NovelLibrary.group(
            listOf(a, b),
            listOf(media(9, "The Beginning After the End")),
            listOf(CustomNovel("custom:x", "TBATE", listOf(a.uri, b.uri))),
        )
        assertEquals(listOf("TBATE"), series.map { it.title })
        assertEquals(listOf(b, a), series[0].volumes.sortedByDescending { it.name.first() == 'T' })
    }

    @Test fun aHandMadeLinkPointsAFileNameToATitle() {
        val series = NovelLibrary.group(
            listOf(file("Odd Name Vol 1.epub")),
            listOf(media(7, "Real Title")),
            emptyList(),
            mapOf("Odd Name" to 7),
        )
        assertEquals("7", series.single().key)
        assertEquals("Real Title", series.single().title)
        val notOnList = NovelLibrary.group(listOf(file("Odd Name Vol 1.epub")), emptyList(), emptyList(), mapOf("Odd Name" to 7))
        assertEquals(7, notOnList.single().mediaId)
        assertEquals("Odd Name", notOnList.single().title)
    }

    @Test fun customNovelsAreSaved() {
        val store = NovelCustomStore(folder.root)
        assertTrue(store.add("My Novel", listOf("u1")))
        assertFalse(store.add("my novel!", null))
        assertEquals(listOf("u1"), store.customs().single().files)
        store.update("custom:my-novel", "Renamed", null)
        assertEquals("Renamed", store.customs().single().title)
        assertEquals(null, store.customs().single().files)
        store.remove("custom:my-novel")
        assertTrue(store.customs().isEmpty())
        store.link("Odd", 7)
        assertEquals(mapOf("Odd" to 7), store.links())
        store.link("Odd", null)
        assertTrue(store.links().isEmpty())
    }
}
