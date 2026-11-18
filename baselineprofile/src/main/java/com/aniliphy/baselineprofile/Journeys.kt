package com.aniliphy.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** The copy made for profiles and measuring, installed beside the real app (see app/build.gradle.kts). */
const val PACKAGE = "com.aniliphy.benchmark"

/** The copy has no account: it goes in as a guest, so nothing is ever written to AniList. */
fun MacrobenchmarkScope.enterAsGuest() {
    device.wait(Until.findObject(By.text(Pattern.compile("(?i).*continue as guest.*"))), 8_000)?.click()
    device.waitForIdle()
}

private fun MacrobenchmarkScope.tab(label: String) {
    device.findObject(By.text(Pattern.compile("(?i)$label")))?.click() ?: return
    device.waitForIdle()
    Thread.sleep(1_200)
}

/** What people do most: look at Home, scroll it, move between the tabs. Only looks, never taps a title. */
fun MacrobenchmarkScope.browse() {
    Thread.sleep(3_000)
    repeat(2) {
        device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
        device.waitForIdle()
    }
    device.findObject(By.scrollable(true))?.fling(Direction.UP)
    listOf("anime", "manga", "light novel", "anilist").forEach { tab(it) }
}
