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
    private var movingSinceMs = Long.MIN_VALUE
    private var fastSinceMs = Long.MIN_VALUE
    private var stoppedSinceMs = Long.MIN_VALUE

    fun reset() {
        lastAccepted = null
        pending = null
        lastAdmitMs = Long.MIN_VALUE
        output = null
        holdingZero = true
        movingSinceMs = Long.MIN_VALUE
        fastSinceMs = Long.MIN_VALUE
        stoppedSinceMs = Long.MIN_VALUE
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
        output = clampStill(timeMillis, accepted)
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
     * A wide error bar still carries the best speed estimate, so only a
     * reading the chip itself does not trust is dropped.
     */
    private fun trustworthy(speedAccuracyMps: Float?, horizontalAccuracyMeters: Float?): Boolean {
        if (speedAccuracyMps != null) {
            return speedAccuracyMps.isFinite() &&
                speedAccuracyMps >= 0f &&
                speedAccuracyMps <= MAX_SPEED_ACCURACY_MPS
        }
        return horizontalAccuracyMeters == null || horizontalAccuracyMeters <= MAX_HORIZONTAL_M
    }

    /**
     * A still watch reports Doppler noise as random km/h. One spike stays at
     * zero. Walking or riding has to hold for a moment, and a wide error bar
     * does not hide that speed. Once you are moving, each new fix shows at
     * once, and one low sample does not snap the reading back to zero.
     */
    private fun clampStill(timeMillis: Long, speed: Float): Float {
        val aboveNoise = speed >= STILL_EXIT_MPS
        if (holdingZero) {
            stoppedSinceMs = Long.MIN_VALUE
            if (speed >= FAST_EXIT_MPS && aboveNoise) {
                if (fastSinceMs == Long.MIN_VALUE) fastSinceMs = timeMillis
            } else {
                fastSinceMs = Long.MIN_VALUE
            }
            if (aboveNoise) {
                if (movingSinceMs == Long.MIN_VALUE) movingSinceMs = timeMillis
                val held = timeMillis - movingSinceMs
                val fastHeld = if (fastSinceMs == Long.MIN_VALUE) 0L else timeMillis - fastSinceMs
                if (held >= CONFIRM_MS || fastHeld >= MIN_FAST_MS) {
                    holdingZero = false
                    movingSinceMs = Long.MIN_VALUE
                    fastSinceMs = Long.MIN_VALUE
                }
            } else {
                movingSinceMs = Long.MIN_VALUE
                fastSinceMs = Long.MIN_VALUE
            }
        } else if (speed <= STILL_ENTER_MPS) {
            movingSinceMs = Long.MIN_VALUE
            if (stoppedSinceMs == Long.MIN_VALUE) {
                stoppedSinceMs = timeMillis
            } else if (timeMillis - stoppedSinceMs >= CONFIRM_MS) {
                holdingZero = true
                stoppedSinceMs = Long.MIN_VALUE
            }
        } else {
            movingSinceMs = Long.MIN_VALUE
            stoppedSinceMs = Long.MIN_VALUE
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
        const val MAX_SPEED_ACCURACY_MPS = 20f
        const val MAX_SPEED_MPS = 120f
        const val STILL_ENTER_MPS = 0.8f
        const val STILL_EXIT_MPS = 1.2f
        const val FAST_EXIT_MPS = 3.5f
        const val MIN_FAST_MS = 200L
        const val CONFIRM_MS = 1_000L
    }
}
