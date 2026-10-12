package com.watchspeed.live

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTrendTest {
    @Test
    fun steadyCruiseStaysSteady() {
        val tracker = SpeedTrendTracker()
        var trend = SpeedTrend.STEADY
        repeat(6) { index ->
            trend = tracker.update(index * 1000L, 12f + if (index % 2 == 0) 0.2f else -0.2f)
        }
        assertEquals(SpeedTrend.STEADY, trend)
    }

    @Test
    fun brakingTurnsRedSignal() {
        val tracker = SpeedTrendTracker()
        tracker.update(0L, 20f)
        val trend = tracker.update(1_000L, 18f)
        assertEquals(SpeedTrend.BRAKING, trend)
    }

    @Test
    fun accelerationTurnsCoolSignal() {
        val tracker = SpeedTrendTracker()
        tracker.update(0L, 10f)
        val trend = tracker.update(1_000L, 12f)
        assertEquals(SpeedTrend.ACCELERATING, trend)
    }

    @Test
    fun levelingOffReturnsToSteady() {
        val tracker = SpeedTrendTracker()
        tracker.update(0L, 10f)
        tracker.update(1_000L, 13f)
        val trend = tracker.update(2_000L, 13.1f)
        assertEquals(SpeedTrend.STEADY, trend)
    }

    @Test
    fun brakeOverridesAcceleration() {
        val tracker = SpeedTrendTracker()
        tracker.update(0L, 10f)
        tracker.update(1_000L, 13f)
        val trend = tracker.update(2_000L, 11f)
        assertEquals(SpeedTrend.BRAKING, trend)
    }

    @Test
    fun lostFixClearsTheTrend() {
        val tracker = SpeedTrendTracker()
        tracker.update(0L, 10f)
        tracker.update(1_000L, 8f)
        assertEquals(SpeedTrend.STEADY, tracker.update(2_000L, null))
    }
}
