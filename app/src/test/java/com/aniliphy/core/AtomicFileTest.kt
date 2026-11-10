package com.aniliphy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AtomicFileTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun writesReplaceTheWholeFileAndLeaveNothingBehind() {
        val file = File(folder.root, "deep/inside/data.json")
        file.writeTextAtomically("first, and longer")
        file.writeTextAtomically("second")
        assertEquals("second", file.readTextOrNull())
        assertEquals(listOf("data.json"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun aMissingFileReadsAsNull() {
        assertNull(File(folder.root, "none.json").readTextOrNull())
    }
}
