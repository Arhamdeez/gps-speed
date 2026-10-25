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

    /** True only while the speed screen is on the watch. Leaving the app clears it. */
    @Volatile
    var sessionOpen: Boolean = false

    private val filter = SpeedFilter()
    private val trendTracker = SpeedTrendTracker()
    private var satellites = 0
    private var watchLocation: Location? = null
    private var phoneLocation: Location? = null
    private var shownSource: FixSource? = null

    @Synchronized
    fun setMode(next: LocationMode) {
        if (mode.value == next) return
        mode.value = next
        republish(resetFilter = true)
    }

    @Synchronized
    fun setGpsEnabled(enabled: Boolean) {
        gpsEnabled.value = enabled
        if (!enabled) {
            filter.reset()
            trendTracker.reset()
            satellites = 0
            watchLocation = null
            phoneLocation = null
            shownSource = null
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
        val copy = Location(location)
        if (source == FixSource.WATCH) watchLocation = copy else phoneLocation = copy
        if (source == FixSource.PHONE && mode.value != LocationMode.BOTH) return
        val before = shownSource
        val chosen = choose(holding = before) ?: return
        if (chosen == before && source != chosen) return
        republish(resetFilter = false)
    }

    @Synchronized
    private fun republish(resetFilter: Boolean) {
        val chosen = choose(holding = if (resetFilter) null else shownSource)
        if (chosen == null) {
            shownSource = null
            if (resetFilter) {
                filter.reset()
                trendTracker.reset()
            }
            sample.value = SpeedSample(satellitesUsed = satellites)
            return
        }
        if (resetFilter || chosen != shownSource) {
            filter.reset()
            trendTracker.reset()
        }
        shownSource = chosen
        val location = if (chosen == FixSource.PHONE) phoneLocation else watchLocation
        if (location != null) emit(location, chosen)
    }

    private fun choose(holding: FixSource?): FixSource? {
        val now = SystemClock.elapsedRealtimeNanos()
        return preferredSource(
            mode = mode.value,
            holding = holding,
            watch = watchLocation?.let { offer(it, now) },
            phone = phoneLocation?.let { offer(it, now) },
        )
    }

    private fun offer(location: Location, nowNanos: Long): SourceOffer {
        val age = (nowNanos - location.elapsedRealtimeNanos).coerceAtLeast(0L)
        val speed = if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0f) {
            location.speed
        } else {
            null
        }
        return SourceOffer(ageNanos = age, speedMps = speed)
    }

    private fun emit(location: Location, source: FixSource) {
        val hasDopplerSpeed = location.hasSpeed() && location.speed.isFinite()
        val speed = if (hasDopplerSpeed && location.speed >= 0f) location.speed else null
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
