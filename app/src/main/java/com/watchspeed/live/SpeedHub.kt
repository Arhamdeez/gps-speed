package com.watchspeed.live

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow

data class SpeedSample(
    val metersPerSecond: Float? = null,
    val accuracyMeters: Float? = null,
    val elapsedRealtimeNanos: Long = 0L,
    val hasFix: Boolean = false,
    val satellitesUsed: Int = 0,
    val sourceLabel: String = "GPS",
)

/**
 * Latest speed shared by the location service and the watch screen.
 * Both run in the same process, so a flow is enough; no binder is required.
 */
object SpeedHub {
    val sample = MutableStateFlow(SpeedSample())
    val gpsEnabled = MutableStateFlow(true)
    val useMph = MutableStateFlow(false)

    private var lastLocation: Location? = null
    private var satellites = 0
    private var holdingZero = false

    @Synchronized
    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled.value = enabled
        if (!enabled) {
            holdingZero = false
            lastLocation = null
            sample.value = sample.value.copy(
                hasFix = false,
                metersPerSecond = null,
                satellitesUsed = 0,
            )
        }
    }

    @Synchronized
    fun setSatellites(count: Int) {
        satellites = count
        sample.value = sample.value.copy(satellitesUsed = count)
    }

    @Synchronized
    fun publish(location: Location, sourceLabel: String) {
        val shown = applyStillness(rawSpeed(location))
        lastLocation = location
        sample.value = SpeedSample(
            metersPerSecond = shown,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            hasFix = shown != null,
            satellitesUsed = satellites,
            sourceLabel = sourceLabel,
        )
    }

    private fun rawSpeed(location: Location): Float? {
        if (location.hasSpeed() && location.speed >= 0f && !location.speed.isNaN()) {
            return location.speed
        }
        val previous = lastLocation ?: return null
        val elapsedSeconds = (location.time - previous.time) / 1000f
        if (elapsedSeconds < 0.05f || elapsedSeconds > 15f) return null
        return previous.distanceTo(location) / elapsedSeconds
    }

    /**
     * GPS jitters by about 1 km/h when you are standing still.
     * Hold 0 until the fix clearly starts moving, then release.
     */
    private fun applyStillness(raw: Float?): Float? {
        raw ?: return null
        if (holdingZero) {
            if (raw >= STILL_EXIT_MPS) holdingZero = false
        } else if (raw <= STILL_ENTER_MPS) {
            holdingZero = true
        }
        return if (holdingZero) 0f else raw
    }

    private const val STILL_ENTER_MPS = 0.35f
    private const val STILL_EXIT_MPS = 0.6f
}
