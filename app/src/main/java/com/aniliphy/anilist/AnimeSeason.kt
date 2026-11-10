package com.aniliphy.anilist

import java.time.LocalDate

data class AnimeSeason(val name: String, val year: Int) {
    val label: String get() = "${name.lowercase().replaceFirstChar { it.uppercase() }} $year"

    fun next(): AnimeSeason {
        val index = NAMES.indexOf(name)
        return if (index == NAMES.lastIndex) AnimeSeason(NAMES.first(), year + 1) else AnimeSeason(NAMES[index + 1], year)
    }

    companion object {
        private val NAMES = listOf("WINTER", "SPRING", "SUMMER", "FALL")

        fun of(date: LocalDate) = AnimeSeason(NAMES[(date.monthValue - 1) / 3], date.year)
    }
}
