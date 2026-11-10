package com.aniliphy.anime

import com.aniliphy.anilist.ListProgress
import com.aniliphy.anilist.ListStatus
import com.aniliphy.source.SourceEpisode
import kotlin.math.floor

data class StartEpisode(val episode: Int, val positionMs: Long)

object AnimeRules {
    const val MIN_RESUME_MS = 10_000L

    private val RESUMED = setOf(ListStatus.PLANNING, ListStatus.PAUSED, ListStatus.DROPPED)

    fun shouldResume(progress: ListProgress?): Boolean =
        progress != null && progress.status in RESUMED && progress.progress > 0

    fun aniListEpisode(episode: SourceEpisode, total: Int? = null): Int? {
        val whole = floor(episode.number).toInt()
        if (whole <= 0) return null
        return total?.takeIf { it > 0 }?.let { minOf(whole, it) } ?: whole
    }

    fun find(episodes: List<SourceEpisode>, record: AnimeRecord?): Int? {
        record ?: return null
        record.episodeId?.let { id -> episodes.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it } }
        val number = record.episodeNumber ?: return null
        return episodes.indexOfFirst { it.number == number }.takeIf { it >= 0 }
    }

    fun start(episodes: List<SourceEpisode>, progress: ListProgress?, record: AnimeRecord?): StartEpisode {
        if (episodes.isEmpty()) return StartEpisode(0, 0)
        if (progress?.status == ListStatus.COMPLETED) return StartEpisode(0, 0)
        val watched = progress?.progress ?: record?.watched ?: 0
        val saved = find(episodes, record)
        val resume = saved?.let { StartEpisode(it, record!!.positionMs.takeIf { ms -> ms >= MIN_RESUME_MS } ?: 0) }
        return when {
            resume != null && (watched <= 0 || watched < episodes[resume.episode].number) -> resume
            watched > 0 -> {
                val after = episodes.indexOfFirst { it.number > watched }
                StartEpisode(if (after >= 0) after else episodes.lastIndex, 0)
            }
            else -> resume ?: StartEpisode(0, 0)
        }
    }

    fun watched(positionMs: Long, durationMs: Long, percent: Int): Boolean =
        durationMs > 0 && positionMs * 100 >= durationMs * percent

    fun percent(record: AnimeRecord, latest: Double?): Int? {
        val number = record.episodeNumber ?: return null
        val total = record.total?.takeIf { it > 0 }?.toDouble() ?: latest?.takeIf { it > 0 } ?: return null
        val share = if (record.durationMs > 0) record.positionMs.toDouble() / record.durationMs else 0.0
        return (((number - 1).coerceAtLeast(0.0) + share.coerceIn(0.0, 1.0)) / total * 100).toInt().coerceIn(0, 100)
    }

    fun maxHeight(quality: VideoQuality): Int? = when (quality) {
        VideoQuality.P1080 -> 1080
        VideoQuality.P720 -> 720
        VideoQuality.P480 -> 480
        VideoQuality.P360 -> 360
        VideoQuality.Best, VideoQuality.Worst -> null
    }

    fun quality(settings: VideoQuality, mobile: MobileQuality, metered: Boolean): VideoQuality =
        if (!metered) {
            settings
        } else {
            when (mobile) {
                MobileQuality.Same -> settings
                MobileQuality.P1080 -> VideoQuality.P1080
                MobileQuality.P720 -> VideoQuality.P720
                MobileQuality.P480 -> VideoQuality.P480
                MobileQuality.P360 -> VideoQuality.P360
                MobileQuality.Worst -> VideoQuality.Worst
            }
        }

    fun pickHeight(heights: List<Int>, quality: VideoQuality): Int? {
        if (heights.isEmpty()) return null
        val indexed = heights.withIndex()
        return when (quality) {
            VideoQuality.Best -> indexed.maxBy { it.value }.index
            VideoQuality.Worst -> indexed.minBy { it.value }.index
            else -> {
                val limit = maxHeight(quality)!!
                (indexed.filter { it.value <= limit }.maxByOrNull { it.value } ?: indexed.minBy { it.value }).index
            }
        }
    }

    fun languageOf(label: String, given: String? = null): String? {
        given?.takeIf { it.isNotBlank() }?.let { return it.lowercase().take(2) }
        val name = label.lowercase()
        return LANGUAGES.entries.firstOrNull { (word, _) -> name.startsWith(word) }?.value
    }

    fun languageOf(language: SubtitleLanguage): String? = when (language) {
        SubtitleLanguage.English -> "en"
        SubtitleLanguage.Portuguese -> "pt"
        SubtitleLanguage.Spanish -> "es"
        SubtitleLanguage.Off -> null
    }

    private val LANGUAGES = linkedMapOf(
        "english" to "en", "portug" to "pt", "spanish" to "es", "español" to "es", "french" to "fr", "german" to "de",
        "italian" to "it", "russian" to "ru", "arabic" to "ar", "japanese" to "ja", "indonesian" to "id", "thai" to "th",
        "vietnamese" to "vi", "chinese" to "zh", "korean" to "ko", "turkish" to "tr", "polish" to "pl",
    )
}
