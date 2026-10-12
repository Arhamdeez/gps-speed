package com.watchspeed.live

import java.util.Locale
import kotlin.math.roundToInt

object SpeedFormat {
    fun format(metersPerSecond: Float?): String {
        val rounded = rounded(metersPerSecond) ?: return PLACEHOLDER
        return rounded.toString()
    }

    private fun rounded(metersPerSecond: Float?): Int? {
        if (metersPerSecond == null || metersPerSecond.isNaN() || metersPerSecond.isInfinite()) {
            return null
        }
        return (metersPerSecond * KMH_PER_MPS).roundToInt().coerceAtLeast(0)
    }

    fun unit(): String = "km/h"

    fun age(ageMs: Long): String = String.format(Locale.US, "%.1fs", ageMs / 1000.0)

    private const val KMH_PER_MPS = 3.6f
    private const val PLACEHOLDER = "--"
}
