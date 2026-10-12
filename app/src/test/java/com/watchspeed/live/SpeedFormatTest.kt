package com.watchspeed.live

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedFormatTest {
    @Test
    fun formatsKilometersPerHour() {
        assertEquals("18.0", SpeedFormat.format(5f, useMph = false))
        assertEquals("0.0", SpeedFormat.format(0f, useMph = false))
    }

    @Test
    fun formatsMilesPerHour() {
        assertEquals("11.2", SpeedFormat.format(5f, useMph = true))
    }

    @Test
    fun missingFixIsAPlaceholder() {
        assertEquals("--.-", SpeedFormat.format(null, useMph = false))
    }

    @Test
    fun unitLabelFollowsTheToggle() {
        assertEquals("km/h", SpeedFormat.unit(useMph = false))
        assertEquals("mph", SpeedFormat.unit(useMph = true))
    }
}
