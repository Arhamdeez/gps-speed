package com.watchspeed.live

import java.util.Locale
import kotlin.math.roundToInt

object SpeedFormat {
    fun format(metersPerSecond: Float?): String {
        val rounded = kilometersPerHour(metersPerSecond) ?: return PLACEHOLDER
        return rounded.toString()
    }

    fun kilometersPerHour(metersPerSecond: Float?): Int? = rounded(metersPerSecond)

    private fun rounded(metersPerSecond: Float?): Int? {
        if (metersPerSecond == null || metersPerSecond.isNaN() || metersPerSecond.isInfinite()) {
            return null
        }
        return (metersPerSecond.toDouble() * KMH_PER_MPS).roundToInt().coerceAtLeast(0)
    }

    fun unit(): String = "km/h"

    fun age(ageMs: Long): String = String.format(Locale.US, "%.1fs", ageMs / 1000.0)

    private const val KMH_PER_MPS = 3.6
    private const val PLACEHOLDER = "--"
}
