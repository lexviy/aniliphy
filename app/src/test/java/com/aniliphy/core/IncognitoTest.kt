package com.aniliphy.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IncognitoTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private val now = ZonedDateTime.of(2026, 11, 17, 22, 30, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun turnsOffWhenAsked() {
        assertEquals(0L, Incognito.until(IncognitoOff.Never, now, zone))
        assertEquals(now + 3_600_000, Incognito.until(IncognitoOff.Hour, now, zone))
        assertEquals(now + 3 * 3_600_000, Incognito.until(IncognitoOff.ThreeHours, now, zone))
        assertEquals(ZonedDateTime.of(2026, 11, 18, 0, 0, 0, 0, zone).toInstant().toEpochMilli(), Incognito.until(IncognitoOff.Tomorrow, now, zone))
    }

    @Test
    fun offWithoutPrefs() {
        assertEquals(false, Incognito.on)
    }
}
