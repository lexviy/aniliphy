package com.aniliphy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FullBackupTest {
    private fun parse(text: String) = Json.parseToJsonElement(text)

    @Test
    fun mergeKeepsTheNewerPlacePerTitle() {
        val phone = parse("""{"1":{"episodeNumber":3,"updated":200},"2":{"episodeNumber":5,"updated":500},"3":{"title":"Only here"}}""")
        val backup = parse("""{"1":{"episodeNumber":7,"updated":300},"2":{"episodeNumber":1,"updated":100},"4":{"title":"Only there","updated":1}}""")
        val merged = JsonMerge.merge(phone, backup) as JsonObject
        assertEquals(parse("""{"episodeNumber":7,"updated":300}"""), merged["1"])
        assertEquals(parse("""{"episodeNumber":5,"updated":500}"""), merged["2"])
        assertEquals(parse("""{"title":"Only here"}"""), merged["3"])
        assertEquals(parse("""{"title":"Only there","updated":1}"""), merged["4"])
    }

    @Test
    fun mergeJoinsListsAndKeepsLargerCounts() {
        val phone = parse("""{"celebrated":["a","b"],"2026-11-12":{"mangaChapters":2},"ch:1":369.0}""")
        val backup = parse("""{"celebrated":["b","c"],"2026-11-12":{"mangaChapters":5,"animeMs":10},"ch:1":300.0}""")
        val merged = JsonMerge.merge(phone, backup) as JsonObject
        assertEquals(parse("""["a","b","c"]"""), merged["celebrated"])
        assertEquals(parse("""{"mangaChapters":5,"animeMs":10}"""), merged["2026-11-12"])
        assertEquals(JsonPrimitive(369.0), merged["ch:1"])
    }

    @Test
    fun zipRoundTrip() {
        val data = BackupData(
            buildJsonObject { put("app", "aniliphy"); put("format", 2); put("icon", "Cat") },
            mapOf("anime" to JsonObject(mapOf("anilist_sync" to FullBackup.encode("Ask")!!))),
            mapOf("anime_history.json" to """{"1":{"updated":1},"2":{"updated":2}}""".toByteArray()),
        )
        val bytes = ByteArrayOutputStream().also { FullBackup.write(it, data) }.toByteArray()
        val back = FullBackup.read(ByteArrayInputStream(bytes))
        assertEquals("Cat", back.icon)
        assertEquals(data.prefs, back.prefs)
        assertEquals(String(data.files.getValue("anime_history.json")), String(back.files.getValue("anime_history.json")))
        val summary = FullBackup.summary(back).associate { it.part to it.count }
        assertEquals("2 titles", summary[FullBackup.Part.Anime])
        assertEquals("1 group", summary[FullBackup.Part.Settings])
    }

    @Test
    fun unknownAndPrivateEntriesAreIgnored() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            entry("manifest.json", """{"app":"aniliphy","format":2}""")
            entry("files/../../shared_prefs/login.xml", "x")
            entry("files/token.json", """{"access_token":"secret"}""")
            entry("prefs/sync.json", """{"token":{"type":"string","value":"secret"}}""")
            entry("prefs/../evil.json", "{}")
        }
        val back = FullBackup.read(ByteArrayInputStream(out.toByteArray()))
        assertTrue(back.files.isEmpty())
        assertTrue(back.prefs.isEmpty())
    }

    @Test
    fun readsTheOldSettingsFile() {
        val old = """{"app":"aniliphy","version":1,"icon":"Play","settings":{"manga":{"mode":{"type":"string","value":"Webtoon"}}}}"""
        val back = FullBackup.read(ByteArrayInputStream(old.toByteArray()))
        assertEquals("Play", back.icon)
        assertEquals("Webtoon", (back.prefs.getValue("manga")["mode"] as JsonObject)["value"].let { (it as JsonPrimitive).content })
        assertTrue(back.files.isEmpty())
    }

    @Test(expected = InvalidBackupException::class)
    fun otherZipsAreRefused() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("photo.jpg")); zip.write(1); zip.closeEntry() }
        FullBackup.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun privateStoresStayOut() {
        assertNull(FullBackup.partOf("sync"))
        assertNull(FullBackup.partOf("login"))
        assertNull(FullBackup.partOf("extensions"))
        assertEquals(FullBackup.Part.Fixes, FullBackup.partOf("canon"))
        assertEquals(FullBackup.Part.Settings, FullBackup.partOf("source_954848840440936266"))
        assertFalse("token.json" in FullBackup.FILES)
    }
}
