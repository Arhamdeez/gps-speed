package com.watchspeed.live

enum class SpeedTrend {
    BRAKING,
    ACCELERATING,
    STEADY,
}

/**
 * Watches the displayed speed and reports whether it is clearly falling,
 * rising, or holding. Small GPS jitter stays steady.
 */
class SpeedTrendTracker {
    private val samples = ArrayDeque<Sample>()
    private var trend = SpeedTrend.STEADY

    fun reset() {
        samples.clear()
        trend = SpeedTrend.STEADY
    }

    fun update(timeMillis: Long, speedMps: Float?): SpeedTrend {
        if (speedMps == null || !speedMps.isFinite()) {
            reset()
            return SpeedTrend.STEADY
        }
        val newest = samples.lastOrNull()
        if (newest != null && timeMillis + 2_000L < newest.timeMs) {
            reset()
        }
        if (newest != null && timeMillis < newest.timeMs) return trend

        samples.addLast(Sample(timeMillis, speedMps))
        while (samples.size > 1 && timeMillis - samples.first().timeMs > WINDOW_MS) {
            samples.removeFirst()
        }
        val anchor = samples.first()
        val dtSeconds = (timeMillis - anchor.timeMs) / 1000f
        if (dtSeconds < MIN_DT_SECONDS) return trend
        trend = nextTrend(trend, (speedMps - anchor.speed) / dtSeconds)
        return trend
    }

    private fun nextTrend(current: SpeedTrend, accel: Float): SpeedTrend {
        return when (current) {
            SpeedTrend.STEADY -> when {
                accel <= -ENTER_MPS2 -> SpeedTrend.BRAKING
                accel >= ENTER_MPS2 -> SpeedTrend.ACCELERATING
                else -> SpeedTrend.STEADY
            }
            SpeedTrend.BRAKING -> when {
                accel >= ENTER_MPS2 -> SpeedTrend.ACCELERATING
                accel > -EXIT_MPS2 -> SpeedTrend.STEADY
                else -> SpeedTrend.BRAKING
            }
            SpeedTrend.ACCELERATING -> when {
                accel <= -ENTER_MPS2 -> SpeedTrend.BRAKING
                accel < EXIT_MPS2 -> SpeedTrend.STEADY
                else -> SpeedTrend.ACCELERATING
            }
        }
    }

    private data class Sample(val timeMs: Long, val speed: Float)

    private companion object {
        const val WINDOW_MS = 1_200L
        const val MIN_DT_SECONDS = 0.3f
        const val ENTER_MPS2 = 0.7f
        const val EXIT_MPS2 = 0.3f
    }
}
