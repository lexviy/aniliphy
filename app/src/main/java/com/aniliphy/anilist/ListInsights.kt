package com.aniliphy.anilist

import kotlin.math.roundToInt

data class RankedName(val name: String, val count: Int, val meanScore: Double?)

data class RankedPerson(val id: Int, val name: String, val count: Int)

data class Remaining(val titles: Int, val units: Long, val minutes: Long, val unknown: Int)

data class AnimePeople(
    val studios: List<RankedPerson> = emptyList(),
    val staff: List<RankedPerson> = emptyList(),
    val voiceActors: List<RankedPerson> = emptyList(),
)

data class ListInsights(
    val genres: List<RankedName>,
    val tags: List<RankedName>,
    val staff: List<RankedPerson>,
    val scores: List<Pair<String, Int>>,
    val decades: List<Pair<String, Int>>,
    val years: List<Pair<String, Int>>,
    val mostRepeated: List<ListEntry>,
    val inProgress: Remaining,
    val planning: Remaining,
) {
    companion object {
        private const val TOP = 10
        private const val MOST_REPEATED = 20
        private const val ASSUMED_EPISODE_MINUTES = 24

        private const val TAG_MIN_RANK = 60

        fun of(entries: List<ListEntry>, kind: MediaKind, details: Map<Int, Media>): ListInsights {
            val started = entries.filter { it.status != ListStatus.PLANNING }
            val years = started.mapNotNull { details[it.media.id]?.startDate?.year }
            return ListInsights(
                genres = ranked(started) { details[it]?.genres.orEmpty() },
                tags = ranked(started) { id ->
                    details[id]?.tags.orEmpty()
                        .filter { !it.isMediaSpoiler && !it.isGeneralSpoiler && (it.rank ?: 0) >= TAG_MIN_RANK }
                        .map { it.name }
                },
                staff = staffOf(started, details),
                scores = scores(started),
                decades = years.groupingBy { it / 10 * 10 }.eachCount().toSortedMap(reverseOrder())
                    .map { (decade, count) -> "${decade}s" to count },
                years = years.groupingBy { it }.eachCount().toSortedMap(reverseOrder())
                    .map { (year, count) -> year.toString() to count },
                mostRepeated = started.filter { it.repeat > 0 }
                    .sortedWith(compareByDescending<ListEntry> { it.repeat }.thenBy { it.media.title.display })
                    .take(MOST_REPEATED),
                inProgress = remaining(
                    entries.filter { it.status in setOf(ListStatus.CURRENT, ListStatus.REPEATING, ListStatus.PAUSED) },
                    kind,
                ),
                planning = remaining(entries.filter { it.status == ListStatus.PLANNING }, kind),
            )
        }

        private fun ranked(entries: List<ListEntry>, names: (Int) -> List<String>): List<RankedName> {
            val byName = HashMap<String, MutableList<ListEntry>>()
            entries.forEach { entry ->
                names(entry.media.id).distinct().forEach { byName.getOrPut(it) { ArrayList() }.add(entry) }
            }
            return byName.map { (name, list) ->
                val scores = list.map { it.score }.filter { it > 0 }
                RankedName(name, list.size, if (scores.isEmpty()) null else scores.average())
            }
                .sortedWith(compareByDescending<RankedName> { it.count }.thenBy { it.name })
                .take(TOP)
        }

        private fun staffOf(entries: List<ListEntry>, details: Map<Int, Media>): List<RankedPerson> {
            val people = entries.flatMap { entry ->
                details[entry.media.id]?.staff?.edges.orEmpty().mapNotNull { it.node }.distinctBy { it.id }
            }
            return people.groupBy { it.id }
                .map { (id, same) -> RankedPerson(id, same.first().name.full?.trim() ?: "?", same.size) }
                .sortedWith(compareByDescending<RankedPerson> { it.count }.thenBy { it.name })
                .take(TOP)
        }

        private fun scores(entries: List<ListEntry>): List<Pair<String, Int>> {
            val rounded = entries.filter { it.score > 0 }.map { it.score.roundToInt().coerceIn(1, 10) }
            if (rounded.isEmpty()) return emptyList()
            return (10 downTo 1).map { score -> score.toString() to rounded.count { it == score } }
        }

        private fun remaining(entries: List<ListEntry>, kind: MediaKind): Remaining {
            var units = 0L
            var minutes = 0L
            var unknown = 0
            entries.forEach { entry ->
                val total = when (kind) {
                    MediaKind.Anime -> entry.media.episodes
                    MediaKind.Manga -> entry.media.chapters
                    MediaKind.LightNovel -> entry.media.volumes
                }
                if (total == null) {
                    unknown++
                } else {
                    val done = if (kind == MediaKind.LightNovel) entry.progressVolumes else entry.progress
                    val left = (total - done).coerceAtLeast(0).toLong()
                    units += left
                    minutes += left * (entry.media.duration ?: ASSUMED_EPISODE_MINUTES)
                }
            }
            return Remaining(entries.size, units, if (kind == MediaKind.Anime) minutes else 0L, unknown)
        }
    }
}
