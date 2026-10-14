package com.watchspeed.live

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val ScreenBlack = Color(0xFF000000)
private val Muted = Color(0xFF8A8A8A)
private val LiveUnit = Color(0xFF3DDCFF)
private val HeartPink = Color(0xFFFF5C7A)
private val SpeedLine = Color(0xFF2EE0FF)

@Composable
fun RideScreen(
    isAmbient: Boolean,
    burnInProtection: Boolean,
    lowBitAmbient: Boolean,
    ambientTick: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var rides by remember { mutableStateOf<List<List<RideSample>>>(emptyList()) }
    var index by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val loaded = withContext(Dispatchers.IO) {
                RideLog.ridesOf(RideStore.load(context))
            }
            rides = loaded
            if (index > loaded.lastIndex) index = loaded.lastIndex
            delay(2_000)
        }
    }
    val shownIndex = when {
        rides.isEmpty() -> 0
        index < 0 -> RideLog.indexWithHeart(rides)
        else -> index.coerceIn(0, rides.lastIndex)
    }
    val ride = rides.getOrNull(shownIndex).orEmpty()
    val peak = RideLog.peakOf(ride)
    val speedPeak = RideLog.speedPeakOf(ride)
    val chart = RideLog.chartOf(ride)
    val is24Hour = DateFormat.is24HourFormat(context)
    val ink = if (lowBitAmbient && isAmbient) Color.White else Color.White
    val heartColor = if (isAmbient && lowBitAmbient) Color.White else HeartPink
    val speedColor = if (isAmbient && lowBitAmbient) Color.White else SpeedLine

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack),
    ) {
        val shiftX = if (isAmbient && burnInProtection) ((ambientTick % 3) - 1) * 6 else 0
        val shiftY = if (isAmbient && burnInProtection) (((ambientTick / 3) % 3) - 1) * 6 else 0
        val boardWidth = maxWidth
        val boardHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = boardWidth * 0.1f, vertical = boardHeight * 0.08f)
                .offset(x = shiftX.dp, y = shiftY.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            BasicText(
                text = if (shownIndex == rides.lastIndex || rides.isEmpty()) "This ride" else "Earlier ride",
                style = rideText(12f, Muted, FontWeight.Medium),
            )
            if (speedPeak?.speedKmh == null && peak?.bpm == null) {
                BasicText(
                    text = "No ride yet",
                    style = rideText(18f, ink, FontWeight.SemiBold),
                )
                BasicText(
                    text = "Peak speed and the heart rate then are saved once you move.",
                    style = rideText(12f, Muted, FontWeight.Medium),
                )
            } else if (speedPeak?.speedKmh != null) {
                BasicText(
                    text = clockAt(speedPeak.timeMillis, is24Hour),
                    style = rideText(20f, ink, FontWeight.Bold),
                )
                BasicText(
                    text = "${speedPeak.speedKmh} km/h",
                    style = rideText(18f, speedColor, FontWeight.SemiBold),
                )
                BasicText(
                    text = heartAtSpeed(speedPeak),
                    style = rideText(15f, if (speedPeak.bpm == null) Muted else heartColor, FontWeight.SemiBold),
                )
            } else if (peak?.bpm != null) {
                BasicText(
                    text = clockAt(peak.timeMillis, is24Hour),
                    style = rideText(22f, ink, FontWeight.Bold),
                )
                BasicText(
                    text = "Peak ${peak.bpm} bpm",
                    style = rideText(16f, heartColor, FontWeight.SemiBold),
                )
                BasicText(
                    text = peakSpeedLine(peak),
                    style = rideText(13f, ink, FontWeight.Medium),
                )
            }

            if (chart.size >= 2) {
                RideChart(
                    samples = chart,
                    peak = peak,
                    speedPeak = speedPeak,
                    heartColor = heartColor,
                    speedColor = speedColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(boardHeight * 0.24f)
                        .padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BasicText(text = "Heart", style = rideText(11f, heartColor, FontWeight.Medium))
                    BasicText(text = "Speed", style = rideText(11f, speedColor, FontWeight.Medium))
                }
                val length = rideLength(ride)
                val from = clockAt(ride.first().timeMillis, is24Hour)
                val to = clockAt(ride.last().timeMillis, is24Hour)
                BasicText(
                    text = if (length.isEmpty()) "$from – $to" else "$from – $to  ·  $length",
                    style = rideText(11f, Muted, FontWeight.Medium),
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                if (shownIndex > 0) {
                    BasicText(
                        text = "Older",
                        modifier = Modifier.pointerInput(shownIndex) {
                            detectTapGestures(onTap = { index = shownIndex - 1 })
                        },
                        style = rideText(13f, Muted, FontWeight.Medium),
                    )
                }
                BasicText(
                    text = "Back",
                    modifier = Modifier.pointerInput(onBack) {
                        detectTapGestures(onTap = { onBack() })
                    },
                    style = rideText(14f, if (isAmbient) Muted else LiveUnit, FontWeight.SemiBold),
                )
                if (rides.isNotEmpty() && shownIndex < rides.lastIndex) {
                    BasicText(
                        text = "Newer",
                        modifier = Modifier.pointerInput(shownIndex) {
                            detectTapGestures(onTap = { index = shownIndex + 1 })
                        },
                        style = rideText(13f, Muted, FontWeight.Medium),
                    )
                }
            }
        }
    }
}

@Composable
private fun RideChart(
    samples: List<RideSample>,
    peak: RideSample?,
    speedPeak: RideSample?,
    heartColor: Color,
    speedColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val start = samples.first().timeMillis
        val span = (samples.last().timeMillis - start).coerceAtLeast(1L)
        drawSeries(samples.map { it.speedKmh?.toFloat() }, speedColor, samples.size)
        drawSeries(samples.map { it.bpm?.toFloat() }, heartColor, samples.size)
        val marked = speedPeak ?: peak
        if (marked != null) {
            val fraction = (marked.timeMillis - start).coerceIn(0L, span).toFloat() / span
            val x = size.width * fraction
            drawLine(
                color = speedColor.copy(alpha = 0.45f),
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        markPoint(samples.map { it.speedKmh?.toFloat() }, speedPeak?.speedKmh?.toFloat(), speedPeak, start, span, speedColor)
        markPoint(samples.map { it.bpm?.toFloat() }, peak?.bpm?.toFloat(), peak, start, span, heartColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.markPoint(
    series: List<Float?>,
    value: Float?,
    sample: RideSample?,
    start: Long,
    span: Long,
    color: Color,
) {
    if (sample == null || value == null) return
    val known = series.mapNotNull { it }
    if (known.isEmpty()) return
    val min = known.min().coerceAtMost(value)
    val max = known.max().coerceAtLeast(value).coerceAtLeast(min + 1f)
    val fraction = (sample.timeMillis - start).coerceIn(0L, span).toFloat() / span
    val x = size.width * fraction
    val y = size.height - size.height * (value - min) / (max - min)
    val center = androidx.compose.ui.geometry.Offset(x, y)
    drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = center)
    drawCircle(color = color, radius = 2.2.dp.toPx(), center = center)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    values: List<Float?>,
    color: Color,
    count: Int,
) {
    val known = values.mapIndexedNotNull { index, value -> value?.let { index to it } }
    if (known.isEmpty() || count < 2) return
    val min = known.minOf { it.second }
    val max = known.maxOf { it.second }.coerceAtLeast(min + 1f)
    val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    var path = Path()
    var open = false
    values.forEachIndexed { index, value ->
        if (value == null) {
            if (open) {
                drawPath(path, color, style = stroke)
                path = Path()
                open = false
            }
        } else {
            val x = size.width * index / (count - 1)
            val y = size.height - size.height * (value - min) / (max - min)
            if (!open) {
                path.moveTo(x, y)
                open = true
            } else {
                path.lineTo(x, y)
            }
        }
    }
    if (open) drawPath(path, color, style = stroke)
}

private fun heartAtSpeed(peak: RideSample): String {
    return when (peak.bpm) {
        null -> "Heart rate not saved then"
        else -> "${peak.bpm} bpm"
    }
}

private fun peakSpeedLine(peak: RideSample): String {
    return when (peak.speedKmh) {
        null -> "Speed not saved with this beat"
        0 -> "Stopped"
        else -> "${peak.speedKmh} km/h"
    }
}

private fun rideLength(samples: List<RideSample>): String {
    if (samples.size < 2) return ""
    val minutes = ((samples.last().timeMillis - samples.first().timeMillis) / 60_000L).toInt()
    return if (minutes < 1) "under a minute" else "$minutes min"
}

private fun clockAt(millis: Long, is24Hour: Boolean): String {
    val pattern = if (is24Hour) "HH:mm" else "h:mm a"
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))
}

private fun rideText(
    size: Float,
    color: Color,
    weight: FontWeight,
    letterSpacing: TextUnit = 0.em,
): TextStyle {
    return TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = letterSpacing,
        lineHeight = (size * 1.25f).sp,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}
