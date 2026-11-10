package com.aniliphy.novels

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubBookTest {
    @get:Rule val folder = TemporaryFolder()

    private fun epub(vararg files: Pair<String, String>): File {
        val file = folder.newFile("book${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private val container = "META-INF/container.xml" to
        """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""

    private val opf = "OEBPS/content.opf" to """
        <package><metadata><meta name="cover" content="pic"/></metadata>
        <manifest>
          <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
          <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
          <item id="a" href="text/one%20a.xhtml" media-type="application/xhtml+xml"/>
          <item id="b" href="text/two.xhtml" media-type="application/xhtml+xml"/>
          <item id="gone" href="text/gone.xhtml" media-type="application/xhtml+xml"/>
          <item id="pic" href="images/c.jpg" media-type="image/jpeg"/>
        </manifest>
        <spine toc="ncx"><itemref idref="nav"/><itemref idref="a"/><itemref idref="gone"/><itemref idref="b"/><itemref idref="a"/></spine>
        </package>"""

    private val ncx = "OEBPS/toc.ncx" to """
        <ncx><navMap><navPoint><navLabel><text>Chapter  1</text></navLabel><content src="text/one%20a.xhtml#top"/></navPoint></navMap></ncx>"""

    @Test fun readsTheSpineInOrderWithoutDuplicatesOrMissingFiles() {
        EpubBook(
            epub(
                container, opf, ncx,
                "OEBPS/nav.xhtml" to "<html/>",
                "OEBPS/text/one a.xhtml" to "<html><body><p>Hi</p></body></html>",
                "OEBPS/text/two.xhtml" to "<html><body><h2>Heading  Two</h2><p>Hi</p></body></html>",
                "OEBPS/images/c.jpg" to "JPG",
            ),
        ).use { book ->
            assertEquals(listOf("OEBPS/text/one a.xhtml", "OEBPS/text/two.xhtml"), book.chapters.map { it.path })
            assertEquals(listOf("Chapter 1", "Heading Two"), book.chapters.map { it.title })
            assertArrayEquals("JPG".toByteArray(), book.cover())
        }
    }

    @Test fun sectionsLeftOutOfTheContentsBelongToTheChapterBeforeThem() {
        val spine = listOf("cover", "c1", "c1b", "c1c", "c2", "after")
        val spreadOpf = "OEBPS/content.opf" to """<package><manifest>
            ${spine.joinToString("") { """<item id="$it" href="$it.xhtml" media-type="application/xhtml+xml"/>""" }}
            <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            </manifest><spine toc="ncx">${spine.joinToString("") { """<itemref idref="$it"/>""" }}</spine></package>"""
        fun point(label: String, src: String) = """<navPoint><navLabel><text>$label</text></navLabel><content src="$src.xhtml"/></navPoint>"""
        val spreadNcx = "OEBPS/toc.ncx" to "<ncx><navMap>${point("Chapter 1: A", "c1")}${point("Chapter 2: B", "c2")}${point("Afterword", "after")}</navMap></ncx>"
        val pages = spine.map { "OEBPS/$it.xhtml" to "<html><body><p>2.1 Words</p></body></html>" }.toTypedArray()
        EpubBook(epub(container, spreadOpf, spreadNcx, *pages)).use { book ->
            assertEquals(listOf(1, 4, 5), book.chapterStarts)
            val titles = book.chapters.map { it.title }
            assertEquals(listOf("2.1 Words", "Chapter 1: A", "Chapter 1: A", "Chapter 1: A", "Chapter 2: B", "Afterword"), titles)
            val runs = ChapterRules.detectRuns(titles)
            assertEquals(2, ChapterRules.counted(runs, titles.size, starts = book.chapterStarts))
        }
    }

    @Test fun filesAreFoundWhateverTheirCase() {
        EpubBook(epub(container, opf, ncx, "OEBPS/Images/C.JPG" to "x", "OEBPS/text/two.xhtml" to "<p>a</p>")).use { book ->
            assertEquals(1, book.read("oebps/images/c.jpg")?.size)
            assertNull(book.read("nope"))
        }
    }

    @Test fun notAnEpubIsAClearError() {
        val notZip = folder.newFile("x.epub").apply { writeText("hello") }
        assertThrows(BookException::class.java) { EpubBook(notZip) }
        assertThrows(BookException::class.java) { EpubBook(epub("a.txt" to "b")) }
    }

    @Test fun linksCanNotLeaveTheBook() {
        assertEquals("a/c.png", EpubBook.resolve("a/b", "../c.png#x"))
        assertEquals("etc/passwd", EpubBook.resolve("a", "../../../etc/passwd"))
    }

    private val style = PageStyle(true, 17, NovelFont.Serif, 1.5f, 20, 16, 28, true, "#000", "#fff", true)

    @Test fun linksInsideTheBookStayAndOthersGo() {
        val html = EpubPage.prepare(
            """<html><body><a href="two.xhtml#n1">in</a><a href="#n2">here</a><a href="mailto:a@b">m</a>
            <a href="//evil/x">s</a><a href="javascript:alert(1)">j</a></body></html>""",
            style,
        )
        assertTrue("href=\"two.xhtml#n1\"" in html)
        assertTrue("href=\"#n2\"" in html)
        assertFalse("mailto" in html)
        assertFalse("evil" in html)
        assertFalse("javascript" in html)
    }

    @Test fun pagesCanNotRunAnything() {
        val html = EpubPage.prepare(
            """<html><head><title/><script>alert(1)</script></head>
            <body onload="x()"><p onclick="y()">Text</p><iframe src="https://x"></iframe><a href="https://evil">l</a></body></html>""",
            style,
        )
        assertFalse("script>" in html.substringBefore("aniliphy-reader").replace("script-src", ""))
        assertFalse("alert" in html)
        assertFalse("onclick" in html)
        assertFalse("onload" in html)
        assertFalse("iframe" in html)
        assertFalse("evil" in html)
        assertTrue("Content-Security-Policy" in html)
        assertTrue("<p>Text</p>" in html)
    }

    @Test fun selfClosedTagsDoNotSwallowTheBook() {
        val html = EpubPage.prepare("<html><head><title/></head><body><div/><p>Kept</p><br/></body></html>", style)
        assertTrue("<body><div></div><p>Kept</p><br></body>" in html)
    }

    @Test fun svgCoversBecomeImages() {
        val html = EpubPage.prepare(
            """<html><body><svg width="100%" viewBox="0 0 1 1"><image xlink:href="../i/c.jpg" width="1"/></svg></body></html>""",
            style,
        )
        assertTrue("""<img src="../i/c.jpg">""" in html)
        assertFalse("<svg" in html)
    }

    @Test fun hiddenIllustrationsAndOwnFontsFollowTheSettings() {
        val hidden = style.copy(illustrations = false, font = NovelFont.Book).css()
        assertTrue("img,svg,image{display:none" in hidden)
        assertFalse("font-family" in hidden)
        assertTrue("column-width" in style.css())
        assertFalse("column-width" in style.copy(paged = false).css())
    }
}
