package com.aniliphy.anilist

import kotlin.math.round

data class EntryForm(
    val status: String,
    val progress: String,
    val volumes: String,
    val score: String,
    val repeat: String,
) {
    fun values(progressTotal: Int?, volumesTotal: Int?, hasVolumes: Boolean, originalVolumes: Int): EntryValues {
        val typedVolumes = whole(volumes, volumesTotal)
        return EntryValues(
            status = status,
            progress = whole(progress, progressTotal),
            volumes = if (!hasVolumes || (typedVolumes == 0 && originalVolumes == 0)) null else typedVolumes,
            score = readScore(score),
            repeat = whole(repeat, null),
        )
    }

    fun withStatus(newStatus: String, progressTotal: Int?, volumesTotal: Int?): EntryForm {
        if (newStatus != ListStatus.COMPLETED) return copy(status = newStatus)
        return copy(
            status = newStatus,
            progress = progressTotal?.takeIf { it > 0 }?.toString() ?: progress,
            volumes = volumesTotal?.takeIf { it > 0 }?.toString() ?: volumes,
        )
    }

    companion object {
        fun from(current: ListProgress) = EntryForm(
            status = current.status ?: ListStatus.PLANNING,
            progress = current.progress.toString(),
            volumes = current.progressVolumes.toString(),
            score = Formatting.score(current.score).orEmpty(),
            repeat = current.repeat.toString(),
        )

        private fun whole(text: String, maximum: Int?): Int {
            val value = (text.trim().toIntOrNull() ?: 0).coerceAtLeast(0)
            return if (maximum != null && maximum > 0) value.coerceAtMost(maximum) else value
        }

        private fun readScore(text: String): Double {
            val value = text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
            return round(value.coerceIn(0.0, 10.0) * 10) / 10
        }
    }
}

data class EntryValues(
    val status: String,
    val progress: Int,
    val volumes: Int?,
    val score: Double,
    val repeat: Int,
)
