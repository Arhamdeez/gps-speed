package com.watchspeed.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedFilterTest {
    @Test
    fun steadySpeedStaysPut() {
        val filter = SpeedFilter()
        var shown: Float? = null
        repeat(6) { index ->
            shown = filter.update(index * 1000L, 5f, 0.4f, 8f)
        }
        assertNear(5f, shown)
    }

    @Test
    fun oneSpikeDoesNotMoveTheReading() {
        val filter = SpeedFilter()
        repeat(5) { index ->
            filter.update(index * 1000L, 5f, 0.4f, 8f)
        }
        val spiked = filter.update(5_000L, 40f, 0.4f, 8f)
        val after = filter.update(6_000L, 5f, 0.4f, 8f)
        assertNear(5f, spiked)
        assertNear(5f, after)
    }

    @Test
    fun noisyLowSpeedStaysAtZero() {
        val filter = SpeedFilter()
        val noise = floatArrayOf(0.2f, 0.8f, 0.3f, 1.1f, 0.4f, 0.6f, 0.2f)
        noise.forEachIndexed { index, speed ->
            val shown = filter.update(index * 1000L, speed, 0.6f, 10f)
            assertEquals(0f, shown)
        }
    }

    @Test
    fun realAccelerationShowsOnTheSameFix() {
        val filter = SpeedFilter()
        val ramp = floatArrayOf(0f, 3f, 6f, 9f, 12f, 12f)
        val expected = floatArrayOf(0f, 3f, 6f, 9f, 12f, 12f)
        ramp.forEachIndexed { index, speed ->
            val shown = filter.update(index * 1000L, speed, 0.4f, 8f)
            assertNear(expected[index], shown)
        }
    }

    @Test
    fun brakingShowsOnTheSameFix() {
        val filter = SpeedFilter()
        filter.update(0L, 20f, 0.3f, 5f)
        val shown = filter.update(1_000L, 8f, 0.3f, 5f)
        assertNear(8f, shown)
    }

    @Test
    fun subSecondFixesUpdateImmediately() {
        val filter = SpeedFilter()
        filter.update(0L, 10f, 0.3f, 5f)
        val shown = filter.update(200L, 12f, 0.3f, 5f)
        assertNear(12f, shown)
    }

    @Test
    fun repeatedJumpIsTrustedOnTheNextFix() {
        val filter = SpeedFilter()
        repeat(3) { index ->
            filter.update(index * 1000L, 5f, 0.3f, 5f)
        }
        val held = filter.update(3_000L, 50f, 0.3f, 5f)
        val snapped = filter.update(4_000L, 49f, 0.3f, 5f)
        assertNear(5f, held)
        assertNear(49f, snapped)
    }

    @Test
    fun plausibleChangesMatchTheLatestSpeed() {
        val filter = SpeedFilter()
        val samples = floatArrayOf(4f, 7f, 3.5f, 8f, 5f, 9f, 4.5f, 6f, 22f, 5f)
        val expected = floatArrayOf(4f, 7f, 3.5f, 8f, 5f, 9f, 4.5f, 6f, 6f, 5f)
        samples.forEachIndexed { index, speed ->
            val shown = filter.update(index * 1000L, speed, 0.5f, 8f)
            assertNear(expected[index], shown)
        }
    }

    @Test
    fun poorSpeedAccuracyCannotYankTheSpeed() {
        val filter = SpeedFilter()
        var shown: Float? = null
        repeat(5) { index ->
            shown = filter.update(index * 1000L, 5f, 0.4f, 8f)
        }
        shown = filter.update(5_000L, 30f, 8f, 8f)
        assertNear(5f, shown)
    }

    @Test
    fun goodDopplerSpeedIsUsedEvenWhenPositionIsWeak() {
        val filter = SpeedFilter()
        repeat(3) { index ->
            filter.update(index * 1000L, 5f, 0.4f, 8f)
        }
        val shown = filter.update(3_000L, 12f, 0.3f, 80f)
        assertNear(12f, shown)
    }

    @Test
    fun missingSpeedAccuracyFallsBackToHorizontalAccuracy() {
        val filter = SpeedFilter()
        repeat(4) { index ->
            filter.update(index * 1000L, 5f, 0.4f, 8f)
        }
        val shown = filter.update(4_000L, 8f, null, 80f)
        assertNear(5f, shown)
    }

    @Test
    fun missingSpeedKeepsTheLastReading() {
        val filter = SpeedFilter()
        repeat(4) { index ->
            filter.update(index * 1000L, 8f, 0.4f, 8f)
        }
        val shown = filter.update(4_000L, null, null, 8f)
        assertNear(8f, shown)
    }

    @Test
    fun noFixYetStaysEmpty() {
        val filter = SpeedFilter()
        assertNull(filter.update(0L, null, null, null))
    }

    @Test
    fun phoneIsIgnoredUnlessWatchGpsGoesQuiet() {
        assertFalse(shouldUsePhoneFix(LocationMode.WATCH, null))
        assertFalse(shouldUsePhoneFix(LocationMode.BOTH, 500_000_000L))
        assertTrue(shouldUsePhoneFix(LocationMode.BOTH, null))
        assertTrue(shouldUsePhoneFix(LocationMode.BOTH, 3_000_000_000L))
    }

    private fun assertNear(expected: Float, actual: Float?, delta: Float = 0.05f) {
        assertNotNull(actual)
        assertEquals(expected.toDouble(), actual!!.toDouble(), delta.toDouble())
    }
}
