package com.aniliphy.anilist

data class ProgressChange(
    val progress: Int? = null,
    val progressVolumes: Int? = null,
    val status: String? = null,
    val repeat: Int? = null,
)

object ProgressRules {
    private val ACTIVE = setOf(ListStatus.CURRENT, ListStatus.REPEATING)

    fun start(current: ListProgress): ProgressChange? {
        if (current.status in ACTIVE) return null
        return ProgressChange(
            progress = 0,
            progressVolumes = if (current.progressVolumes > 0) 0 else null,
            status = if (current.status == ListStatus.COMPLETED) ListStatus.REPEATING else ListStatus.CURRENT,
        )
    }

    fun episode(current: ListProgress, episode: Int, totalEpisodes: Int?): ProgressChange? {
        if (episode <= current.progress) return null
        val rewatching = current.status == ListStatus.REPEATING
        val finished = totalEpisodes != null && totalEpisodes > 0 && episode >= totalEpisodes
        return ProgressChange(
            progress = episode,
            status = when {
                finished -> ListStatus.COMPLETED
                rewatching -> ListStatus.REPEATING
                else -> ListStatus.CURRENT
            },
            repeat = if (finished && rewatching) current.repeat + 1 else null,
        )
    }

    fun canOfferCompletion(current: ListProgress, read: Int, localVolumes: Int, mediaStatus: String?): Boolean {
        if (read <= 0 || current.status == ListStatus.COMPLETED) return false
        if (mediaStatus == "RELEASING" || mediaStatus == "NOT_YET_RELEASED" || mediaStatus == "HIATUS") return false
        val total = current.chapters
        if (total != null && total > 0 && read >= total) return false
        val volumes = current.volumes
        return volumes == null || volumes <= 0 || localVolumes >= volumes
    }

    fun complete(current: ListProgress, read: Int): ProgressChange = ProgressChange(
        progress = maxOf(read, current.progress, current.chapters ?: 0),
        progressVolumes = current.volumes?.takeIf { it > 0 } ?: current.progressVolumes.takeIf { it > 0 },
        status = ListStatus.COMPLETED,
        repeat = if (current.status == ListStatus.REPEATING) current.repeat + 1 else null,
    )

    fun chapter(current: ListProgress, chapter: Int?, volume: Int?): ProgressChange? {
        val newChapter = maxOf(chapter ?: 0, current.progress)
        val newVolume = maxOf(volume ?: 0, current.progressVolumes)
        val unchanged = newChapter == current.progress && newVolume == current.progressVolumes
        if (unchanged && (current.status in ACTIVE || current.status == ListStatus.COMPLETED)) return null

        val rereading = current.status == ListStatus.REPEATING
        val total = current.chapters
        val finished = total != null && total > 0 && newChapter >= total
        return ProgressChange(
            progress = newChapter,
            progressVolumes = newVolume.takeIf { it != 0 },
            status = when {
                finished -> ListStatus.COMPLETED
                rereading -> ListStatus.REPEATING
                else -> ListStatus.CURRENT
            },
            repeat = if (finished && rereading) current.repeat + 1 else null,
        )
    }
}
