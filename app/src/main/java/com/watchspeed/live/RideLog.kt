package com.watchspeed.live

import kotlin.math.abs

/**
 * One saved moment: the speed on screen and the heart rate at that time.
 * A blank speed or heart rate means that side had no reading yet.
 */
data class RideSample(
    val timeMillis: Long,
    val speedKmh: Int?,
    val bpm: Int?,
)

/**
 * Groups saved moments into rides and finds the heart-rate peak with the
 * speed that was showing then.
 */
object RideLog {
    const val GAP_MS = 5L * 60L * 1000L
    const val SPEED_LINK_MS = 5_000L

    fun formatLine(sample: RideSample): String {
        val speed = sample.speedKmh?.toString().orEmpty()
        val bpm = sample.bpm?.toString().orEmpty()
        return "${sample.timeMillis},$speed,$bpm"
    }

    fun parseLine(line: String): RideSample? {
        val parts = line.split(',')
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        if (time <= 0L) return null
        val speed = parts[1].toIntOrNull()
        val bpm = parts[2].toIntOrNull()
        if (speed != null && speed !in 0..400) return null
        if (bpm != null && bpm !in 30..220) return null
        return RideSample(time, speed, bpm)
    }

    fun ridesOf(samples: List<RideSample>): List<List<RideSample>> {
        val ordered = samples.sortedBy { it.timeMillis }
        if (ordered.isEmpty()) return emptyList()
        val rides = mutableListOf<MutableList<RideSample>>()
        var current = mutableListOf(ordered.first())
        rides.add(current)
        for (sample in ordered.drop(1)) {
            val gap = sample.timeMillis - current.last().timeMillis
            if (gap > GAP_MS) {
                current = mutableListOf(sample)
                rides.add(current)
            } else {
                current.add(sample)
            }
        }
        return rides
    }

    /** The ride most worth opening: the latest one that actually has a pulse. */
    fun indexWithHeart(rides: List<List<RideSample>>): Int {
        val found = rides.indexOfLast { ride -> ride.any { (it.bpm ?: 0) > 0 } }
        if (found >= 0) return found
        return rides.lastIndex.coerceAtLeast(0)
    }

    /**
     * Fastest moment in the ride. If that sample had no pulse, the nearest
     * heart rate within a few seconds is used.
     */
    fun speedPeakOf(samples: List<RideSample>): RideSample? {
        val peak = samples
            .filter { (it.speedKmh ?: 0) > 0 }
            .maxWithOrNull(compareBy<RideSample> { it.speedKmh }.thenBy { it.timeMillis })
            ?: return null
        if (peak.bpm != null) return peak
        val bpm = samples
            .filter { it.bpm != null && abs(it.timeMillis - peak.timeMillis) <= SPEED_LINK_MS }
            .minByOrNull { abs(it.timeMillis - peak.timeMillis) }
            ?.bpm
        return peak.copy(bpm = bpm)
    }

    /**
     * Highest pulse in the ride. If that moment had no speed, the nearest
     * speed within a few seconds is used so the peak stays tied to the ride.
     */
    fun peakOf(samples: List<RideSample>): RideSample? {
        val peak = samples
            .filter { (it.bpm ?: 0) > 0 }
            .maxWithOrNull(compareBy<RideSample> { it.bpm }.thenBy { it.timeMillis })
            ?: return null
        if (peak.speedKmh != null) return peak
        val speed = samples
            .filter { it.speedKmh != null && abs(it.timeMillis - peak.timeMillis) <= SPEED_LINK_MS }
            .minByOrNull { abs(it.timeMillis - peak.timeMillis) }
            ?.speedKmh
        return peak.copy(speedKmh = speed)
    }

    /**
     * Fewer points for the graph. Each bucket keeps its highest pulse so a
     * short spike is not averaged away.
     */
    fun chartOf(samples: List<RideSample>, maxPoints: Int = 80): List<RideSample> {
        if (samples.size <= maxPoints || maxPoints < 2) return samples
        val start = samples.first().timeMillis
        val span = (samples.last().timeMillis - start).coerceAtLeast(1L)
        val buckets = Array(maxPoints) { mutableListOf<RideSample>() }
        for (sample in samples) {
            val index = ((sample.timeMillis - start) * maxPoints / span)
                .toInt()
                .coerceIn(0, maxPoints - 1)
            buckets[index].add(sample)
        }
        return buckets.mapNotNull { bucket ->
            if (bucket.isEmpty()) {
                null
            } else {
                val loudest = bucket.maxBy { it.bpm ?: -1 }
                val speed = loudest.speedKmh ?: bucket.lastOrNull { it.speedKmh != null }?.speedKmh
                loudest.copy(speedKmh = speed)
            }
        }
    }
}
