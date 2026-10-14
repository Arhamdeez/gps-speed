package com.watchspeed.live

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Wear OS 6 replaced the body-sensor permission with a heart-rate permission.
 * Older watches still use the body-sensor permission.
 */
object HeartRateAccess {
    fun permission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            READ_HEART_RATE
        } else {
            Manifest.permission.BODY_SENSORS
        }
    }

    fun granted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, permission()) ==
            PackageManager.PERMISSION_GRANTED
    }

    private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
}

data class HeartReading(
    val bpm: Int? = null,
    val elapsedRealtimeMs: Long = 0L,
) {
    fun freshBpm(nowElapsedMs: Long): Int? {
        val beats = bpm ?: return null
        if (elapsedRealtimeMs == 0L) return null
        val age = nowElapsedMs - elapsedRealtimeMs
        if (age < 0L || age > STALE_MS) return null
        return beats
    }

    private companion object {
        const val STALE_MS = 15_000L
    }
}

/** Latest heart rate from the watch sensor, shared with the speed screen. */
object HeartHub {
    val reading = MutableStateFlow(HeartReading())

    @Synchronized
    fun publish(bpm: Int, elapsedRealtimeMs: Long) {
        if (bpm !in MIN_BPM..MAX_BPM) return
        reading.value = HeartReading(bpm, elapsedRealtimeMs)
    }

    private const val MIN_BPM = 30
    private const val MAX_BPM = 220
}
