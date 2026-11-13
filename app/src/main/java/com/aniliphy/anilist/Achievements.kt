package com.aniliphy.anilist

import java.time.LocalDate
import java.time.ZoneId

/**
 * The app's own achievements, worked out from AniList lists only, so they work the same for you and for anyone
 * whose lists can be read (a friend's profile, later).
 */
enum class AchievementGroup(val label: String) { Watching("Watching"), Reading("Reading"), Taste("Taste"), Season("Seasons"), Secret("Secret") }

/**
 * One achievement. [levels] are the amounts for bronze, silver and gold (a single amount means it's simply earned).
 * [hidden] ones show no name or rule until earned.
 */
class Achievement(
    val id: String,
    val title: String,
    /** What counts, with "%d" for the amount needed. */
    val rule: String,
    val group: AchievementGroup,
    val levels: List<Int>,
    val hidden: Boolean = false,
    internal val measure: (Facts) -> Measured,
)

/** How far along: [value] now, and the dates each counted thing happened when known (to date the unlocks). */
class Measured(val value: Int, val dates: List<LocalDate> = emptyList())

/** An achievement worked out for one person. [level] is 0 (not yet), 1 bronze, 2 silver, 3 gold. */
data class AchievementResult(
    val achievement: Achievement,
    val value: Int,
    val level: Int,
    /** The amount for the next level, or null at the top. */
    val next: Int?,
    /** When the current level was reached, when the dates are known. */
    val reachedOn: LocalDate?,
) {
    val earned: Boolean get() = level > 0
    val id: String get() = achievement.id
    val single: Boolean get() = achievement.levels.size == 1

    /** 0..1 towards the next level (1 at the top). */
    val towardsNext: Float
        get() {
            val goal = next ?: return 1f
            val from = if (level == 0) 0 else achievement.levels[level - 1]
            return ((value - from).toFloat() / (goal - from)).coerceIn(0f, 1f)
        }
}

/** Lists boiled down to what the rules look at. */
class Facts(
    val anime: List<ListEntry>,
    val manga: List<ListEntry>,
    val novels: List<ListEntry>,
    val today: LocalDate,
    zone: ZoneId,
) {
    val all = anime + manga + novels
    val animeDone = Finishes.of(anime, zone).sortedBy { it.on }
    val mangaDone = Finishes.of(manga, zone).sortedBy { it.on }
    val novelsDone = Finishes.of(novels, zone).sortedBy { it.on }
    val allDone = (animeDone + mangaDone + novelsDone).sortedBy { it.on }
}

object Achievements {
    private fun count(finishes: List<Finish>, keep: (ListEntry) -> Boolean = { true }): Measured {
        val kept = finishes.filter { keep(it.entry) }
        return Measured(kept.size, kept.map { it.on })
    }

    private fun started(entries: List<ListEntry>) = entries.filter { it.status != ListStatus.PLANNING }

    private fun consumed(entry: ListEntry, total: Int?) = entry.progress + entry.repeat * (total ?: entry.progress)

    val ALL: List<Achievement> = listOf(
        Achievement("anime-done", "Completionist", "Finish %d anime", AchievementGroup.Watching, listOf(10, 100, 500)) {
            count(it.animeDone)
        },
        Achievement("episodes", "Marathoner", "Watch %d episodes", AchievementGroup.Watching, listOf(500, 2500, 10000)) { facts ->
            Measured(started(facts.anime).sumOf { consumed(it, it.media.episodes) })
        },
        Achievement("movies", "Movie night", "Finish %d movies", AchievementGroup.Watching, listOf(5, 25, 100)) {
            count(it.animeDone) { e -> e.media.format == "MOVIE" }
        },
        Achievement("long-haul", "The long haul", "Finish %d series of 100+ episodes", AchievementGroup.Watching, listOf(1, 3, 10)) {
            count(it.animeDone) { e -> (e.media.episodes ?: 0) >= 100 }
        },
        Achievement("rewatch", "Once more", "Rewatch or reread %d times", AchievementGroup.Watching, listOf(1, 10, 50)) { facts ->
            Measured(facts.all.sumOf { it.repeat })
        },
        Achievement("manga-done", "Bookworm", "Finish %d manga", AchievementGroup.Reading, listOf(10, 50, 200)) {
            count(it.mangaDone)
        },
        Achievement("chapters", "Page turner", "Read %d chapters", AchievementGroup.Reading, listOf(1000, 5000, 20000)) { facts ->
            Measured(started(facts.manga).sumOf { consumed(it, it.media.chapters) })
        },
        Achievement("novels-done", "Light reader", "Finish %d light novels", AchievementGroup.Reading, listOf(5, 20, 50)) {
            count(it.novelsDone)
        },
        Achievement("volumes", "Shelf filler", "Read %d volumes", AchievementGroup.Reading, listOf(25, 100, 400)) { facts ->
            Measured(started(facts.manga + facts.novels).sumOf { it.progressVolumes + it.repeat * (it.media.volumes ?: it.progressVolumes) })
        },
        Achievement("genres", "Explorer", "Finish titles in %d different genres", AchievementGroup.Taste, listOf(8, 14, 18)) { facts ->
            val seen = LinkedHashMap<String, LocalDate>()
            facts.allDone.forEach { f -> f.entry.media.genres.forEach { g -> seen.putIfAbsent(g, f.on) } }
            Measured(seen.size, seen.values.sorted())
        },
        Achievement("critic", "Critic", "Score %d titles", AchievementGroup.Taste, listOf(25, 100, 400)) { facts ->
            Measured(facts.all.count { it.score > 0 })
        },
        Achievement("world", "World tour", "Finish titles from %d countries", AchievementGroup.Taste, listOf(2, 3, 4)) { facts ->
            val seen = LinkedHashMap<String, LocalDate>()
            facts.allDone.forEach { f -> f.entry.media.countryOfOrigin?.let { seen.putIfAbsent(it, f.on) } }
            Measured(seen.size, seen.values.sorted())
        },
        Achievement("classics", "Old school", "Finish %d titles from before 1990", AchievementGroup.Taste, listOf(1, 5, 20)) {
            count(it.allDone) { e -> (e.media.seasonYear ?: e.media.startDate?.year ?: 9999) < 1990 }
        },
        Achievement("year", "Busy year", "Finish %d titles this year", AchievementGroup.Season, listOf(12, 52, 100)) { facts ->
            count(facts.allDone.filter { it.on.year == facts.today.year })
        },
        Achievement("simulcast", "Simulcast", "Finish %d anime from this season", AchievementGroup.Season, listOf(1, 3, 6)) { facts ->
            val season = AnimeSeason.of(facts.today)
            count(facts.animeDone) { e -> e.media.season == season.name && e.media.seasonYear == season.year }
        },
        // Secret ones.
        Achievement("perfect", "Perfect ten", "Give a title a 10", AchievementGroup.Secret, listOf(1), hidden = true) { facts ->
            Measured(facts.all.count { it.score >= 10.0 })
        },
        Achievement("same-day", "In one sitting", "Start and finish a title on the same day", AchievementGroup.Secret, listOf(1), hidden = true) { facts ->
            val days = facts.all.filter { it.startedAt != null && it.startedAt.toLocalDate() != null && it.startedAt == it.completedAt && it.startedAt.day != null }
            Measured(days.size, days.mapNotNull { it.completedAt?.toLocalDate() }.sorted())
        },
        Achievement("hoarder", "Someday", "Have %d titles on Planning", AchievementGroup.Secret, listOf(100), hidden = true) { facts ->
            Measured(facts.all.count { it.status == ListStatus.PLANNING })
        },
        Achievement("dropper", "Life's too short", "Drop %d titles", AchievementGroup.Secret, listOf(10), hidden = true) { facts ->
            Measured(facts.all.count { it.status == ListStatus.DROPPED })
        },
        Achievement("contrarian", "Contrarian", "Score %d titles 30 points away from AniList's average", AchievementGroup.Secret, listOf(5), hidden = true) { facts ->
            Measured(facts.all.count { e -> e.score > 0 && e.media.averageScore?.let { kotlin.math.abs(e.score * 10 - it) >= 30 } == true })
        },
    )

    fun of(
        anime: List<ListEntry>,
        paper: List<ListEntry>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AchievementResult> {
        val facts = Facts(anime, paper.ofKind(MediaKind.Manga), paper.ofKind(MediaKind.LightNovel), today, zone)
        return ALL.map { result(it, it.measure(facts)) }
    }

    /**
     * One badge per past anime season in which you finished something that aired then: bronze for 1, silver 3, gold 6.
     * Only seasons with at least one finish are listed, newest first.
     */
    fun seasons(anime: List<ListEntry>, zone: ZoneId = ZoneId.systemDefault()): List<AchievementResult> =
        Finishes.of(anime, zone)
            .filter { it.entry.media.season != null && it.entry.media.seasonYear != null }
            .filter { AnimeSeason.of(it.on).let { s -> s.name == it.entry.media.season && s.year == it.entry.media.seasonYear } }
            .groupBy { AnimeSeason(it.entry.media.season!!, it.entry.media.seasonYear!!) }
            .entries
            .sortedWith(compareByDescending<Map.Entry<AnimeSeason, List<Finish>>> { it.key.year }.thenByDescending { SEASONS.indexOf(it.key.name) })
            .map { (season, finishes) ->
                val achievement = Achievement(
                    "season-${season.name}-${season.year}", season.label, "Finish %d anime while ${season.label} airs",
                    AchievementGroup.Season, listOf(1, 3, 6),
                ) { Measured(0) }
                result(achievement, Measured(finishes.size, finishes.map { it.on }.sorted()))
            }

    private val SEASONS = listOf("WINTER", "SPRING", "SUMMER", "FALL")

    private fun result(achievement: Achievement, measured: Measured): AchievementResult {
        val level = achievement.levels.count { measured.value >= it }
        val reached = if (level == 0) null else measured.dates.sorted().getOrNull(achievement.levels[level - 1] - 1)
        return AchievementResult(achievement, measured.value, level, achievement.levels.getOrNull(level), reached)
    }

    /** Level names, for the look's words. */
    fun levelName(level: Int, single: Boolean): String = when {
        level <= 0 -> "Locked"
        single -> "Earned"
        level == 1 -> "Bronze"
        level == 2 -> "Silver"
        else -> "Gold"
    }
}
