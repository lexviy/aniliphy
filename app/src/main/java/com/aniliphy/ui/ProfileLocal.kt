package com.aniliphy.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aniliphy.anilist.Achievements
import com.aniliphy.anilist.ActivityCalendar
import com.aniliphy.anilist.Goal
import com.aniliphy.anilist.Goals
import com.aniliphy.anilist.ListEntry
import com.aniliphy.anilist.MediaKind
import com.aniliphy.core.ActivityDay
import com.aniliphy.core.ActivityLog
import com.aniliphy.core.readTextOrNull
import com.aniliphy.core.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

/**
 * What the profile keeps on this phone: your goals, the three achievements in your showcase and which moments were
 * already celebrated. One file, so the Gist sync can carry it later.
 */
@Serializable
internal data class ProfileLocalData(
    val goals: List<Goal> = emptyList(),
    val showcase: List<String> = emptyList(),
    val celebrated: Set<String> = emptySet(),
    /** Kinds of moments whose past was recorded without celebrating (the first time the app saw them). */
    val seeded: Set<String> = emptySet(),
    /** When goals and the showcase last changed (ms), so the Gist sync keeps the newer of each. */
    val goalsUpdated: Long = 0,
    val showcaseUpdated: Long = 0,
)

internal class ProfileLocal private constructor(private val file: File) {
    var data by mutableStateOf(read())
        private set

    fun update(change: (ProfileLocalData) -> ProfileLocalData) = synchronized(this) {
        data = change(data)
        runCatching { file.writeTextAtomically(json.encodeToString(ProfileLocalData.serializer(), data)) }
    }

    /** Takes what the sync merged; returns true if anything here changed. */
    fun replace(merged: ProfileLocalData): Boolean = synchronized(this) {
        if (merged == data) return false
        update { merged }
        true
    }

    fun toggleShowcase(id: String) = update { d ->
        val list = if (id in d.showcase) d.showcase - id else (d.showcase + id).takeLast(SHOWCASE)
        d.copy(showcase = list, showcaseUpdated = System.currentTimeMillis())
    }

    fun saveGoal(goal: Goal) = update { d -> d.copy(goals = d.goals.filter { it.id != goal.id } + goal, goalsUpdated = System.currentTimeMillis()) }

    fun removeGoal(id: String) = update { d -> d.copy(goals = d.goals.filter { it.id != id }, goalsUpdated = System.currentTimeMillis()) }

    /**
     * Celebrates the [found] moments (key to words) not celebrated before, as one moment. The first time a [kind] is
     * seen everything found is only recorded, so what was earned before the app knew doesn't all go off at once.
     */
    fun celebrate(kind: String, found: Map<String, String>) {
        val d = data
        val fresh = found.filterKeys { it !in d.celebrated }
        if (fresh.isEmpty() && kind in d.seeded) return
        update { it.copy(celebrated = it.celebrated + found.keys, seeded = it.seeded + kind) }
        if (kind !in d.seeded || fresh.isEmpty()) return
        val words = fresh.values.toList()
        LookMoment.milestone(LookWords.milestone(momentStyle, if (words.size == 1) words.single() else "${words.first()} +${words.size - 1}"))
    }

    private fun read(): ProfileLocalData =
        runCatching { file.readTextOrNull()?.let { json.decodeFromString(ProfileLocalData.serializer(), it) } }.getOrNull() ?: ProfileLocalData()

    companion object {
        const val SHOWCASE = 3
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        @Volatile private var instance: ProfileLocal? = null

        fun get(context: Context): ProfileLocal = instance ?: synchronized(this) {
            instance ?: ProfileLocal(File(context.applicationContext.filesDir, "profile_local.json")).also { instance = it }
        }
    }
}

/** A day of app activity as one number for the heatmap: chapters, plus one for every 20 minutes watched. */
internal fun ActivityDay.amount(): Int = mangaChapters + novelChapters + ((animeMs + 20 * 60_000 - 1) / (20 * 60_000)).toInt()

internal fun Map<LocalDate, ActivityDay>.chapters(): Map<LocalDate, Int> = mapValues { it.value.mangaChapters + it.value.novelChapters }

internal fun Map<LocalDate, ActivityDay>.minutes(): Map<LocalDate, Int> = mapValues { (it.value.animeMs / 60_000).toInt() }

/**
 * Looks for new achievements, goals reached and streak marks whenever your lists change, and celebrates them with
 * the look's moment.
 */
internal object ProfileWatch {
    fun check(context: Context, entries: Collection<ListEntry>, aniListDays: Map<LocalDate, Int> = emptyMap(), today: LocalDate = LocalDate.now()) {
        if (entries.isEmpty()) return
        val local = ProfileLocal.get(context)
        val anime = entries.filter { it.media.kind == MediaKind.Anime }
        val paper = entries.filter { it.media.kind != MediaKind.Anime }
        val results = Achievements.of(anime, paper, today) + Achievements.seasons(anime)
        local.celebrate(
            "achievements",
            results.filter { it.earned }.associate { r ->
                "ach:${r.id}:${r.level}" to "${Achievements.levelName(r.level, r.single)} · ${r.achievement.title}"
            },
        )
        val days = ActivityLog.all(context)
        local.data.goals.forEach { goal ->
            val p = Goals.progress(goal, anime, paper, days.chapters(), days.minutes(), today)
            if (p.done) local.celebrate("goal:${goal.id}", mapOf("goal:${goal.id}:${p.from}" to "Goal reached · ${p.goal.target} ${goal.kind.unit}"))
            else local.celebrate("goal:${goal.id}", emptyMap())
        }
        streak(local, ActivityCalendar.merge(aniListDays, days.mapValues { it.value.amount() }), today)
    }

    fun streak(local: ProfileLocal, days: Map<LocalDate, Int>, today: LocalDate) {
        val streak = ActivityCalendar.streak(days, today)
        val last = if ((days[today] ?: 0) > 0) today else today.minusDays(1)
        val start = last.minusDays((streak - 1).toLong())
        local.celebrate(
            "streak",
            ActivityCalendar.STREAK_MARKS.filter { streak >= it }.associate { "streak:$it:$start" to "$it-day streak" },
        )
    }
}
