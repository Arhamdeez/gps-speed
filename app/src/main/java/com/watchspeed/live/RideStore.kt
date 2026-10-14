package com.watchspeed.live

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

/** Append-only ride log in app storage. It stays on the watch between launches. */
object RideStore {
    val currentPeak = MutableStateFlow<RideSample?>(null)
    val currentSpeedPeak = MutableStateFlow<RideSample?>(null)

    private val lock = Any()
    private var writes = 0
    private var lastRecordMs = 0L
    private var lastBpm: Int? = null
    private var lastBpmMs = 0L

    fun record(context: Context, timeMillis: Long, speedKmh: Int?, bpm: Int?) {
        val sample = RideSample(timeMillis, speedKmh, bpm)
        synchronized(lock) {
            val file = file(context)
            file.appendText(RideLog.formatLine(sample) + "\n")
            writes++
            if (writes % TRIM_EVERY == 0) trim(file)
            notePeak(sample)
        }
    }

    fun warm(context: Context) {
        if (currentPeak.value != null && currentSpeedPeak.value != null) return
        val samples = load(context)
        synchronized(lock) {
            val rides = RideLog.ridesOf(samples)
            if (rides.isEmpty()) return
            if (lastRecordMs == 0L) lastRecordMs = rides.last().last().timeMillis
            if (currentPeak.value == null) {
                val index = RideLog.indexWithHeart(rides)
                currentPeak.value = RideLog.peakOf(rides[index])
            }
            if (currentSpeedPeak.value == null) {
                currentSpeedPeak.value = RideLog.speedPeakOf(rides.last())
            }
            if (lastBpm == null) {
                val beat = rides.last().lastOrNull { it.bpm != null } ?: return
                lastBpm = beat.bpm
                lastBpmMs = beat.timeMillis
            }
        }
    }

    private fun notePeak(sample: RideSample) {
        val newRide = lastRecordMs != 0L && sample.timeMillis - lastRecordMs > RideLog.GAP_MS
        lastRecordMs = sample.timeMillis
        if (sample.bpm != null) {
            lastBpm = sample.bpm
            lastBpmMs = sample.timeMillis
        }
        noteHeartPeak(sample, newRide)
        noteSpeedPeak(sample, newRide)
    }

    private fun noteHeartPeak(sample: RideSample, newRide: Boolean) {
        val bpm = sample.bpm ?: run {
            if (newRide) currentPeak.value = null
            return
        }
        val previous = currentPeak.value
        if (newRide || previous == null || bpm >= (previous.bpm ?: 0)) {
            currentPeak.value = if (sample.speedKmh != null || previous == null || newRide) {
                sample
            } else {
                sample.copy(speedKmh = previous.speedKmh)
            }
        }
    }

    private fun noteSpeedPeak(sample: RideSample, newRide: Boolean) {
        val speed = sample.speedKmh
        if (speed == null || speed <= 0) {
            if (newRide) currentSpeedPeak.value = null
            return
        }
        val linkedBpm = sample.bpm ?: lastBpm?.takeIf {
            sample.timeMillis - lastBpmMs in 0..RideLog.SPEED_LINK_MS
        }
        val shown = if (linkedBpm == null || linkedBpm == sample.bpm) sample else sample.copy(bpm = linkedBpm)
        val previous = currentSpeedPeak.value
        if (newRide || previous == null || speed >= (previous.speedKmh ?: 0)) {
            currentSpeedPeak.value = shown
        }
    }

    fun load(context: Context): List<RideSample> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return synchronized(lock) {
            file.readLines().mapNotNull(RideLog::parseLine)
        }
    }

    private fun trim(file: File) {
        val lines = file.readLines()
        if (lines.size <= MAX_LINES) return
        file.writeText(lines.takeLast(MAX_LINES).joinToString(separator = "\n", postfix = "\n"))
    }

    private fun file(context: Context): File {
        return File(context.applicationContext.filesDir, "rides.csv")
    }

    private const val MAX_LINES = 20_000
    private const val TRIM_EVERY = 60
}
