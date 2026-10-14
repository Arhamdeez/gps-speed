package com.watchspeed.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideLogTest {
    @Test
    fun roundTripKeepsSpeedAndHeartRate() {
        val sample = RideSample(1_700_000_000_000L, 92, 168)
        assertEquals(sample, RideLog.parseLine(RideLog.formatLine(sample)))
        assertEquals(RideSample(5L, null, 80), RideLog.parseLine("5,,80"))
        assertEquals(RideSample(5L, 12, null), RideLog.parseLine("5,12,"))
    }

    @Test
    fun garbageLinesAreDropped() {
        assertNull(RideLog.parseLine("nope"))
        assertNull(RideLog.parseLine("5,12,999"))
        assertNull(RideLog.parseLine("5,-1,80"))
    }

    @Test
    fun aQuietGapStartsANewRide() {
        val rides = RideLog.ridesOf(
            listOf(
                RideSample(0L, 10, 60),
                RideSample(1_000L, 11, 61),
                RideSample(1_000L + RideLog.GAP_MS + 1L, 20, 80),
            ),
        )
        assertEquals(2, rides.size)
        assertEquals(80, rides[1].single().bpm)
    }

    @Test
    fun speedPeakUsesTheHeartRateFromThatMoment() {
        val peak = RideLog.speedPeakOf(
            listOf(
                RideSample(0L, 40, 90),
                RideSample(1_000L, 120, 148),
                RideSample(2_000L, 70, 160),
            ),
        )
        assertEquals(120, peak?.speedKmh)
        assertEquals(148, peak?.bpm)
        assertEquals(1_000L, peak?.timeMillis)
    }

    @Test
    fun speedPeakBorrowsANearbyHeartRate() {
        val peak = RideLog.speedPeakOf(
            listOf(
                RideSample(0L, 40, 110),
                RideSample(2_000L, 130, null),
                RideSample(8_000L, 50, 160),
            ),
        )
        assertEquals(130, peak?.speedKmh)
        assertEquals(110, peak?.bpm)
    }

    @Test
    fun stoppedSamplesAreNotASpeedPeak() {
        assertNull(
            RideLog.speedPeakOf(
                listOf(
                    RideSample(0L, 0, 80),
                    RideSample(1_000L, null, 90),
                ),
            ),
        )
    }

    @Test
    fun peakUsesTheSpeedFromThatMoment() {
        val peak = RideLog.peakOf(
            listOf(
                RideSample(0L, 40, 90),
                RideSample(1_000L, 99, 154),
                RideSample(2_000L, 70, 120),
            ),
        )
        assertEquals(154, peak?.bpm)
        assertEquals(99, peak?.speedKmh)
        assertEquals(1_000L, peak?.timeMillis)
    }

    @Test
    fun peakBorrowsANearbySpeed() {
        val peak = RideLog.peakOf(
            listOf(
                RideSample(0L, 88, 70),
                RideSample(1_000L, null, 150),
            ),
        )
        assertEquals(150, peak?.bpm)
        assertEquals(88, peak?.speedKmh)
    }

    @Test
    fun chartKeepsTheHighestBeat() {
        val samples = List(200) { index ->
            RideSample(index * 1_000L, 40, if (index == 40) 190 else 70)
        }
        val chart = RideLog.chartOf(samples, maxPoints = 20)
        assertEquals(190, chart.maxOf { it.bpm ?: 0 })
    }

    @Test
    fun historyOpensTheLatestRideThatHasAPulse() {
        val earlier = listOf(RideSample(0L, 30, 140))
        val current = listOf(RideSample(20_000L, 10, null))
        assertEquals(0, RideLog.indexWithHeart(listOf(earlier, current)))
        assertEquals(
            1,
            RideLog.indexWithHeart(
                listOf(earlier, listOf(RideSample(20_000L, 50, 100))),
            ),
        )
    }
}
