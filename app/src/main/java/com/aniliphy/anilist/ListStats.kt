package com.aniliphy.anilist

data class ListStats(
    val count: Int,
    val progress: Int,
    val volumes: Int,
    val daysWatched: Double,
    val meanScore: Double,
    val lengths: List<Pair<String, Int>>,
    val statuses: List<Pair<String, Int>>,
    val formats: List<Pair<String, Int>>,
) {
    companion object {
        private const val ASSUMED_EPISODE_MINUTES = 24
        private const val UNKNOWN = "Unknown"

        private val ANIME_LENGTHS = listOf(
            "1" to 1, "2-6" to 6, "7-16" to 16, "17-28" to 28, "29-55" to 55, "56-100" to 100,
        )
        private const val ANIME_LONGEST = "101+"
        private val PAPER_LENGTHS = listOf(
            "1" to 1, "2-5" to 5, "6-10" to 10, "11-25" to 25, "26-50" to 50, "51-100" to 100, "101-200" to 200,
        )
        private const val PAPER_LONGEST = "201+"
        private val ANIME_FORMATS = listOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MUSIC")
        private val PAPER_FORMATS = listOf("MANGA", "ONE_SHOT", "NOVEL")

        fun of(entries: List<ListEntry>, kind: MediaKind): ListStats {
            val anime = kind == MediaKind.Anime
            val started = entries.filter { it.status != ListStatus.PLANNING }
            val scores = started.map { it.score }.filter { it > 0 }
            val minutes = if (anime) {
                started.sumOf { consumed(it, it.media.episodes).toLong() * (it.media.duration ?: ASSUMED_EPISODE_MINUTES) }
            } else {
                0L
            }
            return ListStats(
                count = started.size,
                progress = started.sumOf { consumed(it, if (anime) it.media.episodes else it.media.chapters) },
                volumes = started.sumOf { it.progressVolumes + it.repeat * (it.media.volumes ?: it.progressVolumes) },
                daysWatched = minutes / (60.0 * 24),
                meanScore = if (scores.isEmpty()) 0.0 else scores.average(),
                lengths = lengths(started, kind),
                statuses = byCount(ListStatus.ALL, entries.map { it.status }),
                formats = byCount(if (anime) ANIME_FORMATS else PAPER_FORMATS, started.map { it.media.format }),
            )
        }

        private fun consumed(entry: ListEntry, total: Int?): Int =
            entry.progress + entry.repeat * (total ?: entry.progress)

        private fun lengths(entries: List<ListEntry>, kind: MediaKind): List<Pair<String, Int>> {
            val anime = kind == MediaKind.Anime
            val buckets = if (anime) ANIME_LENGTHS else PAPER_LENGTHS
            val longest = if (anime) ANIME_LONGEST else PAPER_LONGEST
            val labels = entries.map { entry ->
                val total = when (kind) {
                    MediaKind.Anime -> entry.media.episodes
                    MediaKind.Manga -> entry.media.chapters
                    MediaKind.LightNovel -> entry.media.volumes
                }
                when {
                    total == null -> UNKNOWN
                    else -> buckets.firstOrNull { total <= it.second }?.first ?: longest
                }
            }
            val order = buckets.map { it.first } + longest + UNKNOWN
            return order.map { label -> label to labels.count { it == label } }
        }

        private fun byCount(known: List<String>, values: List<String?>): List<Pair<String, Int>> {
            val labels = values.map { it ?: UNKNOWN }
            val order = known + labels.filter { it !in known }.distinct()
            return order.map { label -> label to labels.count { it == label } }
                .sortedByDescending { it.second }
        }
    }
}
