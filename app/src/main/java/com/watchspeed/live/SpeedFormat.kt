package com.watchspeed.live

import java.util.Locale

object SpeedFormat {
    fun format(metersPerSecond: Float?, useMph: Boolean): String {
        if (metersPerSecond == null || metersPerSecond.isNaN()) return PLACEHOLDER
        val value = if (useMph) metersPerSecond * MPH_PER_MPS else metersPerSecond * KMH_PER_MPS
        return String.format(Locale.US, "%.1f", value)
    }

    fun unit(useMph: Boolean): String = if (useMph) "mph" else "km/h"

    fun age(ageMs: Long): String = String.format(Locale.US, "%.1fs", ageMs / 1000.0)

    private const val KMH_PER_MPS = 3.6f
    private const val MPH_PER_MPS = 2.2369363f
    private const val PLACEHOLDER = "--.-"
}
