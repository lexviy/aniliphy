package com.aniliphy.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * "Continue on the other device", between aniliphy and anilixy. Each direction has its own small file in the sync
 * gist, so the two apps never write the same file at once and the progress file (anilixy-sync.json) stays as it is.
 * The place is the same record the progress sync uses for that title.
 */
@Serializable
data class HandoffPlace(
    val id: String,
    /** "anime", "manga" or "novel". */
    val kind: String,
    /** The key in the progress file: the AniList id, or the series key for light novels. */
    val key: String,
    val title: String? = null,
    /** Seconds since 1970, like the progress file. */
    val at: Double,
    /** "phone" or "pc". */
    val from: String,
    val anime: SyncedAnime? = null,
    val manga: SyncedManga? = null,
    /** The light novel's record from the progress file. */
    val novel: JsonObject? = null,
    /** The AniList id of a light novel, when known. */
    val anilist: Int? = null,
    /** A short "Episode 8 · 12:40" for the question on the other side. */
    val where: String? = null,
)

@Serializable
data class HandoffFile(
    val app: String = APP,
    val version: Int = VERSION,
    val request: HandoffPlace? = null,
    /** The id of the last request the other side took. */
    val taken: String? = null,
) {
    companion object {
        const val APP = "anilixy-handoff"
        const val VERSION = 1
        /** Written by the phone, read and answered by the PC. */
        const val TO_PC = "anilixy-to-pc.json"
        /** Written by the PC ("Send to phone"), read and answered by the phone. */
        const val TO_PHONE = "anilixy-to-phone.json"
        /** The PC's last place, written by anilixy when you stop watching or reading. */
        const val PC_LAST = "anilixy-pc-last.json"

        fun parse(text: String?): HandoffFile? = text?.let {
            runCatching { SyncPayload.json.decodeFromString(serializer(), it) }.getOrNull()
                ?.takeIf { f -> f.app == APP && f.version <= VERSION }
        }

        fun encode(file: HandoffFile): String = SyncPayload.json.encodeToString(serializer(), file)
    }
}

object HandoffRules {
    /** How long a request or the PC's last place is still worth asking about. */
    const val FRESH_S = 12 * 3600.0

    fun fresh(place: HandoffPlace, nowS: Double) = nowS - place.at in -300.0..FRESH_S

    /** A request waiting for this side: there, fresh, and not taken yet. */
    fun waiting(file: HandoffFile?, nowS: Double): HandoffPlace? {
        val request = file?.request ?: return null
        if (file.taken == request.id || !fresh(request, nowS)) return null
        return request
    }

    /**
     * The card for what the PC played last: only when it's fresh, newer than this phone's own place for that title
     * (by more than a minute, so a sync echo doesn't count), and not dismissed.
     */
    fun pcCard(last: HandoffFile?, localUpdatedS: Double?, dismissed: Set<String>, nowS: Double): HandoffPlace? {
        val place = last?.request ?: return null
        if (place.from != "pc" || place.id in dismissed || !fresh(place, nowS)) return null
        if (localUpdatedS != null && place.at <= localUpdatedS + 60) return null
        return place
    }

    fun clock(seconds: Double?): String? {
        val s = seconds?.toLong()?.takeIf { it > 0 } ?: return null
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun number(value: Double?): String? = value?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }

    /** "Episode 8 · 12:40", "Chapter 41 · page 7", "Chapter 3". */
    fun where(place: HandoffPlace): String? = place.where ?: when {
        place.anime != null -> listOfNotNull(number(place.anime.episode)?.let { "Episode $it" }, clock(place.anime.position)).joinToString(" · ").ifEmpty { null }
        place.manga != null -> listOfNotNull(
            (number(place.manga.number) ?: place.manga.chapter)?.let { "Chapter $it" },
            place.manga.page.takeIf { it > 0 }?.let { "page ${it + 1}" },
        ).joinToString(" · ").ifEmpty { null }
        else -> null
    }
}
