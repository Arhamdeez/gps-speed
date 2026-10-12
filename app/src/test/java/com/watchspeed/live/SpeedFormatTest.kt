package com.watchspeed.live

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedFormatTest {
    @Test
    fun formatsKilometersPerHour() {
        assertEquals("18", SpeedFormat.format(5f))
        assertEquals("0", SpeedFormat.format(0f))
    }

    @Test
    fun roundsToTheNearestWholeNumber() {
        assertEquals("18", SpeedFormat.format(5.1f))
        assertEquals("19", SpeedFormat.format(5.2f))
    }

    @Test
    fun missingFixIsAPlaceholder() {
        assertEquals("--", SpeedFormat.format(null))
    }

    @Test
    fun unitIsKilometersPerHour() {
        assertEquals("km/h", SpeedFormat.unit())
    }
}
