package com.watchspeed.live

import kotlin.math.abs

/**
 * Shows the latest GPS Doppler speed on the same fix that measured it.
 * One impossible jump is held back until the next fix agrees, so a single
 * bad sample cannot move the number. Jitter while stopped stays at zero.
 */
class SpeedFilter {
    private var lastAccepted: Float? = null
    private var pending: Float? = null
    private var lastAdmitMs = Long.MIN_VALUE
    private var output: Float? = null
    private var holdingZero = true

    fun reset() {
        lastAccepted = null
        pending = null
        lastAdmitMs = Long.MIN_VALUE
        output = null
        holdingZero = true
    }

    fun update(
        timeMillis: Long,
        speedMps: Float?,
        speedAccuracyMps: Float?,
        horizontalAccuracyMeters: Float?,
    ): Float? {
        if (lastAdmitMs != Long.MIN_VALUE && timeMillis + 2_000L < lastAdmitMs) {
            reset()
        }
        if (lastAdmitMs != Long.MIN_VALUE && timeMillis < lastAdmitMs) return output
        if (lastAdmitMs != Long.MIN_VALUE && timeMillis - lastAdmitMs > STALE_MS) {
            reset()
        }

        val speed = speedMps ?: return output
        if (!speed.isFinite() || speed < 0f || speed > MAX_SPEED_MPS) return output
        if (!trustworthy(speedAccuracyMps, horizontalAccuracyMeters)) return output

        val accepted = accept(timeMillis, speed) ?: return output
        lastAccepted = accepted
        lastAdmitMs = timeMillis
        output = clampStill(accepted)
        return output
    }

    private fun accept(timeMillis: Long, speed: Float): Float? {
        val previous = lastAccepted
        if (previous == null || lastAdmitMs == Long.MIN_VALUE) {
            pending = null
            return speed
        }
        val dtSeconds = ((timeMillis - lastAdmitMs) / 1000f).coerceAtLeast(MIN_DT_SECONDS)
        val limit = (MAX_ACCEL_MPS2 * dtSeconds).coerceAtLeast(MIN_STEP_MPS)
        if (abs(speed - previous) <= limit) {
            pending = null
            return speed
        }
        val pendingSpeed = pending
        if (pendingSpeed != null && abs(speed - pendingSpeed) <= AGREE_MPS) {
            pending = null
            return speed
        }
        pending = speed
        return null
    }

    /**
     * Doppler speed accuracy is the quality signal. Position error is used
     * only when the chip did not report how sure it is about speed.
     */
    private fun trustworthy(speedAccuracyMps: Float?, horizontalAccuracyMeters: Float?): Boolean {
        if (speedAccuracyMps != null) {
            return speedAccuracyMps.isFinite() &&
                speedAccuracyMps >= 0f &&
                speedAccuracyMps <= MAX_SPEED_ACCURACY_MPS
        }
        return horizontalAccuracyMeters == null || horizontalAccuracyMeters <= MAX_HORIZONTAL_M
    }

    private fun clampStill(speed: Float): Float {
        if (holdingZero) {
            if (speed >= STILL_EXIT_MPS) holdingZero = false
        } else if (speed <= STILL_ENTER_MPS) {
            holdingZero = true
        }
        return if (holdingZero) 0f else speed
    }

    private companion object {
        const val STALE_MS = 8_000L
        const val MIN_DT_SECONDS = 0.05f
        const val MIN_STEP_MPS = 1f
        const val MAX_ACCEL_MPS2 = 14f
        const val AGREE_MPS = 2.5f
        const val MAX_HORIZONTAL_M = 40f
        const val MAX_SPEED_ACCURACY_MPS = 4f
        const val MAX_SPEED_MPS = 120f
        const val STILL_ENTER_MPS = 0.5f
        const val STILL_EXIT_MPS = 1.25f
    }
}
