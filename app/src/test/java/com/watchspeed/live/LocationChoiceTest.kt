package com.watchspeed.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationChoiceTest {
    @Test
    fun bothWhileThePhoneIsConnectedAndSharingLocation() {
        assertEquals(LocationMode.BOTH, locationMode(phoneConnected = true, phoneLocationOn = true))
    }

    @Test
    fun watchOnlyWhenThePhoneIsGoneOrLocationIsOff() {
        assertEquals(LocationMode.WATCH, locationMode(phoneConnected = false, phoneLocationOn = true))
        assertEquals(LocationMode.WATCH, locationMode(phoneConnected = true, phoneLocationOn = false))
        assertEquals(LocationMode.WATCH, locationMode(phoneConnected = false, phoneLocationOn = false))
    }

    @Test
    fun phoneSpeedShowsWhenTheWatchIsStill() {
        val chosen = preferredSource(
            mode = LocationMode.BOTH,
            holding = FixSource.WATCH,
            watch = SourceOffer(ageNanos = 100_000_000L, speedMps = 0f),
            phone = SourceOffer(ageNanos = 200_000_000L, speedMps = 6f),
        )
        assertEquals(FixSource.PHONE, chosen)
    }

    @Test
    fun movingSourcesDoNotFlicker() {
        val chosen = preferredSource(
            mode = LocationMode.BOTH,
            holding = FixSource.WATCH,
            watch = SourceOffer(ageNanos = 100_000_000L, speedMps = 8f),
            phone = SourceOffer(ageNanos = 100_000_000L, speedMps = 9f),
        )
        assertEquals(FixSource.WATCH, chosen)
    }

    @Test
    fun watchOnlyIgnoresThePhone() {
        val chosen = preferredSource(
            mode = LocationMode.WATCH,
            holding = FixSource.PHONE,
            watch = SourceOffer(ageNanos = 100_000_000L, speedMps = 0f),
            phone = SourceOffer(ageNanos = 100_000_000L, speedMps = 12f),
        )
        assertEquals(FixSource.WATCH, chosen)
    }

    @Test
    fun phoneCoversAWatchThatHasNoFix() {
        val chosen = preferredSource(
            mode = LocationMode.BOTH,
            holding = null,
            watch = null,
            phone = SourceOffer(ageNanos = 100_000_000L, speedMps = 4f),
        )
        assertEquals(FixSource.PHONE, chosen)
    }

    @Test
    fun nothingToShowStaysEmpty() {
        assertNull(
            preferredSource(
                mode = LocationMode.WATCH,
                holding = null,
                watch = null,
                phone = SourceOffer(ageNanos = 0L, speedMps = 5f),
            ),
        )
    }
}
