package com.watchspeed.live

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

enum class LocationMode {
    WATCH,
    BOTH,
}

enum class FixSource {
    WATCH,
    PHONE,
}

fun LocationMode.title(): String = when (this) {
    LocationMode.WATCH -> "Watch only"
    LocationMode.BOTH -> "Watch + phone"
}

/** Phone fixes are used only while watch GPS has gone quiet, so the two cannot flicker. */
internal fun shouldUsePhoneFix(mode: LocationMode, watchAgeNanos: Long?): Boolean {
    if (mode != LocationMode.BOTH) return false
    val age = watchAgeNanos ?: return true
    return age > PHONE_AFTER_WATCH_NANOS
}

private const val PHONE_AFTER_WATCH_NANOS = 2_500_000_000L

data class SpeedSample(
    val metersPerSecond: Float? = null,
    val accuracyMeters: Float? = null,
    val elapsedRealtimeNanos: Long = 0L,
    val hasFix: Boolean = false,
    val satellitesUsed: Int = 0,
    val sourceLabel: String = "GPS",
    val trend: SpeedTrend = SpeedTrend.STEADY,
)

/**
 * Latest speed shared by the location service and the watch screen.
 * Both run in the same process, so a flow is enough; no binder is required.
 */
object SpeedHub {
    val sample = MutableStateFlow(SpeedSample())
    val gpsEnabled = MutableStateFlow(true)
    val mode = MutableStateFlow(LocationMode.WATCH)

    private val filter = SpeedFilter()
    private val trendTracker = SpeedTrendTracker()
    private var satellites = 0
    private var lastWatchSpeedRealtimeNanos = 0L

    @Synchronized
    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled.value = enabled
        if (!enabled) {
            filter.reset()
            trendTracker.reset()
            satellites = 0
            lastWatchSpeedRealtimeNanos = 0L
            sample.value = SpeedSample()
        }
    }

    @Synchronized
    fun setSatellites(count: Int) {
        satellites = count
        sample.value = sample.value.copy(satellitesUsed = count)
    }

    @Synchronized
    fun publish(location: Location, source: FixSource) {
        val watchAge = if (lastWatchSpeedRealtimeNanos == 0L) {
            null
        } else {
            SystemClock.elapsedRealtimeNanos() - lastWatchSpeedRealtimeNanos
        }
        if (source == FixSource.PHONE && !shouldUsePhoneFix(mode.value, watchAge)) return

        val hasDopplerSpeed = location.hasSpeed() && !location.speed.isNaN()
        if (source == FixSource.WATCH && hasDopplerSpeed) {
            lastWatchSpeedRealtimeNanos = location.elapsedRealtimeNanos
        }
        val speed = if (hasDopplerSpeed) location.speed else null
        val speedAccuracy = if (location.hasSpeedAccuracy()) {
            location.speedAccuracyMetersPerSecond
        } else {
            null
        }
        val horizontalAccuracy = if (location.hasAccuracy()) location.accuracy else null
        val timeMillis = location.elapsedRealtimeNanos / 1_000_000L
        val filtered = filter.update(
            timeMillis = timeMillis,
            speedMps = speed,
            speedAccuracyMps = speedAccuracy,
            horizontalAccuracyMeters = horizontalAccuracy,
        )
        val trend = trendTracker.update(timeMillis, filtered)
        sample.value = SpeedSample(
            metersPerSecond = filtered,
            accuracyMeters = horizontalAccuracy,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            hasFix = filtered != null,
            satellitesUsed = satellites,
            sourceLabel = if (source == FixSource.PHONE) "Phone" else "Watch",
            trend = trend,
        )
    }
}
