package com.aniliphy.mal

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One entry as MAL keeps it (or should keep it). Null fields are "leave as it is". */
@Serializable
data class MalEntry(
    val malId: Int,
    val anime: Boolean,
    /** watching / reading, completed, on_hold, dropped, plan_to_watch / plan_to_read. */
    val status: String,
    /** Episodes or chapters. */
    val progress: Int? = null,
    val volumes: Int? = null,
    /** 0 to 10, 0 being no score. */
    val score: Int? = null,
    val repeat: Int? = null,
    val repeating: Boolean? = null,
    /** How many episodes/chapters MAL knows the title has (0 or null: unknown). MAL keeps progress within it. */
    val total: Int? = null,
)

/** An AniList entry, as much as the comparison needs. */
data class AniListSide(
    val mediaId: Int,
    val malId: Int?,
    val anime: Boolean,
    val title: String,
    val status: String,
    val progress: Int,
    val volumes: Int,
    /** 0 to 10 (decimals allowed); 0 is no score. */
    val score: Double,
    val repeat: Int,
)

/** One line of the comparison: what MAL has, and what it would get. */
data class MalDifference(val title: String, val mediaId: Int, val mal: MalEntry?, val wanted: MalEntry, val changes: List<String>)

data class MalComparison(
    /** On both, but not the same. */
    val differ: List<MalDifference>,
    /** On AniList, missing on MAL. */
    val missing: List<MalDifference>,
    val matching: Int,
    /** AniList entries with no MAL page: left out. */
    val noMalPage: List<String>,
    /** On MAL only: left alone. */
    val onlyOnMal: Int,
)

/** AniList's way of writing a list entry, turned into MAL's. */
object MalList {
    fun status(aniList: String, anime: Boolean): String = when (aniList) {
        "CURRENT", "REPEATING" -> if (anime) "watching" else "reading"
        "COMPLETED" -> "completed"
        "PAUSED" -> "on_hold"
        "DROPPED" -> "dropped"
        else -> if (anime) "plan_to_watch" else "plan_to_read"
    }

    /** MAL scores are whole numbers from 1 to 10; AniList's 0 is "none". */
    fun score(aniList: Double?): Int? = aniList?.let { if (it <= 0.0) 0 else it.roundToInt().coerceIn(1, 10) }

    /** The MAL entry for an AniList save; [score] null when the save didn't touch it. */
    fun entry(malId: Int, anime: Boolean, status: String, progress: Int?, volumes: Int?, score: Double?, repeat: Int?): MalEntry = MalEntry(
        malId = malId,
        anime = anime,
        status = status(status, anime),
        progress = progress,
        volumes = if (anime) null else volumes,
        score = score(score),
        repeat = repeat,
        repeating = status == "REPEATING",
    )

    /** The form MAL's my_list_status takes. */
    fun form(entry: MalEntry): Map<String, String> = buildMap {
        put("status", entry.status)
        if (entry.anime) {
            entry.progress?.let { put("num_watched_episodes", it.toString()) }
            entry.repeat?.let { put("num_times_rewatched", it.toString()) }
            entry.repeating?.let { put("is_rewatching", it.toString()) }
        } else {
            entry.progress?.let { put("num_chapters_read", it.toString()) }
            entry.volumes?.let { put("num_volumes_read", it.toString()) }
            entry.repeat?.let { put("num_times_reread", it.toString()) }
            entry.repeating?.let { put("is_rereading", it.toString()) }
        }
        entry.score?.let { put("score", it.toString()) }
    }

    /** What would change on MAL, in words ("watching 18 → completed 28"). Empty when they match. */
    fun changes(mal: MalEntry?, wanted: MalEntry): List<String> {
        val unit = if (wanted.anime) "ep" else "ch"
        if (mal == null) return listOf("not on MAL → ${words(wanted.status)} ${wanted.progress?.takeIf { it > 0 }?.let { "$unit $it" } ?: ""}".trim())
        val out = ArrayList<String>()
        val statusChanged = mal.status != wanted.status
        // MAL holds progress to the count it knows: at that count it's as far as it can go.
        val capped = mal.total?.takeIf { it > 0 }?.let { total -> wanted.progress != null && wanted.progress > total && mal.progress == total } == true
        val progressChanged = wanted.progress != null && mal.progress != wanted.progress && !capped
        if (statusChanged || progressChanged) {
            val before = "${words(mal.status)} ${mal.progress?.let { "$unit $it" } ?: ""}".trim()
            val after = "${words(wanted.status)} ${wanted.progress?.let { "$unit $it" } ?: ""}".trim()
            out += "$before → $after"
        }
        if (!wanted.anime && wanted.volumes != null && mal.volumes != wanted.volumes) out += "vol ${mal.volumes ?: 0} → ${wanted.volumes}"
        if (wanted.score != null && mal.score != wanted.score) out += "score ${mal.score?.takeIf { it > 0 } ?: "–"} → ${wanted.score.takeIf { it > 0 } ?: "–"}"
        if (wanted.repeat != null && (mal.repeat ?: 0) != wanted.repeat) out += "rewatched ${mal.repeat ?: 0} → ${wanted.repeat}"
        if (wanted.repeating != null && (mal.repeating ?: false) != wanted.repeating) out += if (wanted.repeating) "now again" else "not again"
        return out
    }

    private fun words(status: String) = status.replace('_', ' ')

    fun compare(aniList: List<AniListSide>, mal: List<MalEntry>): MalComparison {
        val byKey = mal.associateBy { it.anime to it.malId }
        val differ = ArrayList<MalDifference>()
        val missing = ArrayList<MalDifference>()
        val noPage = ArrayList<String>()
        var matching = 0
        val seen = HashSet<Pair<Boolean, Int>>()
        for (a in aniList) {
            val id = a.malId
            if (id == null) {
                noPage += a.title
                continue
            }
            seen += a.anime to id
            val wanted = entry(id, a.anime, a.status, a.progress, a.volumes, a.score, a.repeat)
            val there = byKey[a.anime to id]
            val changes = changes(there, wanted)
            when {
                there == null -> missing += MalDifference(a.title, a.mediaId, null, wanted, changes)
                changes.isEmpty() -> matching++
                else -> differ += MalDifference(a.title, a.mediaId, there, wanted, changes)
            }
        }
        val onlyOnMal = mal.count { (it.anime to it.malId) !in seen }
        return MalComparison(differ, missing, matching, noPage, onlyOnMal)
    }
}
