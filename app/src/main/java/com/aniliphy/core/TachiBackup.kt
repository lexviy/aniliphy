package com.aniliphy.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Reads the library out of a Mihon or Aniyomi backup (`.tachibk`): for each title, the source and address it was read
 * or watched on, and its AniList entry when it was tracked there. Only those fields are declared; the rest is skipped.
 * The numbers are the ones in those apps' backup models.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object TachiBackup {
    /** The AniList tracker's id in both apps. */
    private const val ANILIST = 2

    @Serializable
    private class File(
        @ProtoNumber(1) val manga: List<Entry> = emptyList(),
        // Aniyomi kept anime here before it moved them to 501.
        @ProtoNumber(3) val olderAnime: List<Entry> = emptyList(),
        @ProtoNumber(501) val anime: List<Entry> = emptyList(),
    )

    @Serializable
    private class Entry(
        @ProtoNumber(1) val source: Long = 0,
        @ProtoNumber(2) val url: String = "",
        @ProtoNumber(3) val title: String = "",
        @ProtoNumber(9) val cover: String? = null,
        @ProtoNumber(18) val tracking: List<Track> = emptyList(),
    )

    @Serializable
    private class Track(
        @ProtoNumber(1) val syncId: Int = 0,
        @ProtoNumber(3) val mediaIdInt: Int = 0,
        @ProtoNumber(100) val mediaId: Long = 0,
    )

    /** One title from the backup. [aniList] is its AniList id, or null when it wasn't tracked there. */
    data class Item(val source: String, val url: String, val title: String, val cover: String?, val aniList: Int?)

    class Library(val anime: List<Item>, val manga: List<Item>)

    @Throws(IOException::class)
    fun read(bytes: ByteArray): Library {
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        } else {
            bytes
        }
        val file = try {
            ProtoBuf.decodeFromByteArray(File.serializer(), raw)
        } catch (e: SerializationException) {
            throw IOException("This isn't a Mihon or Aniyomi backup", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("This isn't a Mihon or Aniyomi backup", e)
        }
        return Library(
            anime = (file.anime + file.olderAnime).map { it.item("ta") },
            manga = file.manga.map { it.item("tm") },
        )
    }

    private fun Entry.item(prefix: String): Item {
        val track = tracking.firstOrNull { it.syncId == ANILIST }
        val id = track?.let { (if (it.mediaId > 0) it.mediaId else it.mediaIdInt.toLong()).toInt() }?.takeIf { it > 0 }
        return Item("$prefix$source", url, title, cover?.takeIf { it.isNotBlank() }, id)
    }

    /** What importing would do, counted before anything is changed. */
    class Plan(
        /** AniList id → place, title and cover, for entries that don't open with any source yet. */
        val link: Map<Int, Item>,
        /** Already open with a source here, which importing leaves alone. */
        val alreadySet: Int,
        /** Not tracked on AniList, so there's no entry to put them on. */
        val notOnAniList: Int,
        /** On a source that isn't installed. */
        val notInstalled: Int,
    )

    /**
     * [installed] are the source ids on the phone, [current] each AniList entry's saved place. Only entries with no
     * source get one, since setting a source forgets where you were on the old one.
     */
    fun plan(items: List<Item>, installed: Set<String>, current: Map<Int, String?>): Plan {
        val link = LinkedHashMap<Int, Item>()
        var alreadySet = 0
        var notOnAniList = 0
        var notInstalled = 0
        for (item in items) {
            val id = item.aniList
            when {
                id == null -> notOnAniList++
                item.source !in installed -> notInstalled++
                current[id] != null || id in link -> alreadySet++
                else -> link[id] = item
            }
        }
        return Plan(link, alreadySet, notOnAniList, notInstalled)
    }
}
