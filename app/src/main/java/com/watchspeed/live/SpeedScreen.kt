package com.watchspeed.live

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val ScreenBlack = Color(0xFF000000)
private val AmbientInk = Color(0xFFD0D0D0)
private val Muted = Color(0xFF8A8A8A)
private val LiveUnit = Color(0xFF3DDCFF)
private val WeakSpeed = Color(0xFFFFC14D)
private val EmptySpeed = Color(0xFF4A4A4A)
private val BrakeRed = Color(0xFFFF3B30)
private val AccelCool = Color(0xFF2EE0FF)
private val HeartPink = Color(0xFFFF8FA3)

@Composable
fun SpeedScreen(
    isAmbient: Boolean,
    burnInProtection: Boolean,
    lowBitAmbient: Boolean,
    ambientTick: Int,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    heartAllowed: Boolean,
    onOpenHeart: () -> Unit,
    onRequestHeart: () -> Unit,
) {
    val context = LocalContext.current
    val mode by SpeedHub.mode.collectAsStateWithLifecycle()
    val speedPeak by RideStore.currentSpeedPeak.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RideStore.warm(context) }
    }

    if (!hasPermission) {
        PromptScreen(
            title = "Speed needs GPS",
            action = "Tap to allow location",
            note = mode.title(),
            onClick = onRequestPermission,
        )
        return
    }

    val gpsEnabled by SpeedHub.gpsEnabled.collectAsStateWithLifecycle()
    if (!gpsEnabled) {
        PromptScreen(
            title = "Location is off",
            action = "Tap to turn on GPS",
            note = mode.title(),
            onClick = onOpenLocationSettings,
        )
        return
    }

    val sample by SpeedHub.sample.collectAsStateWithLifecycle()
    val heart by HeartHub.reading.collectAsStateWithLifecycle()
    var frameClock by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    if (!isAmbient) {
        LaunchedEffect(Unit) {
            while (isActive) {
                withFrameNanos { frameClock = SystemClock.elapsedRealtimeNanos() }
            }
        }
    }
    val nowElapsedMs = if (isAmbient) SystemClock.elapsedRealtime() else frameClock / 1_000_000L
    val nowNanos = if (isAmbient) SystemClock.elapsedRealtimeNanos() else frameClock
    val ageMs = if (!sample.hasFix || sample.elapsedRealtimeNanos == 0L) {
        -1L
    } else {
        (nowNanos - sample.elapsedRealtimeNanos) / 1_000_000L
    }
    val stale = ageMs > 8_000L
    val waiting = ageMs > 3_000L
    val speedText = SpeedFormat.format(if (stale) null else sample.metersPerSecond)
    val weak = !stale && (sample.accuracyMeters ?: 0f) > 25f
    val trendColor = when (sample.trend) {
        SpeedTrend.BRAKING -> BrakeRed
        SpeedTrend.ACCELERATING -> AccelCool
        SpeedTrend.STEADY -> null
    }
    val numberColor = when {
        isAmbient && lowBitAmbient -> Color.White
        speedText == "--" -> if (isAmbient) AmbientInk else EmptySpeed
        trendColor != null -> trendColor
        isAmbient -> AmbientInk
        weak -> WeakSpeed
        else -> Color.White
    }
    val heartLabel = when {
        !heartAllowed -> "Allow HR"
        else -> {
            val bpm = heart.freshBpm(nowElapsedMs)
            if (bpm == null) "-- bpm" else "$bpm bpm"
        }
    }
    val heartColor = when {
        isAmbient && lowBitAmbient -> Color.White
        isAmbient -> AmbientInk
        else -> HeartPink
    }
    var numberOnly by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack),
    ) {
        val shiftX = if (isAmbient && burnInProtection) ((ambientTick % 3) - 1) * 6 else 0
        val shiftY = if (isAmbient && burnInProtection) (((ambientTick / 3) % 3) - 1) * 6 else 0
        val boardHeight = maxHeight
        val boardWidth = maxWidth

        if (numberOnly) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = shiftX.dp, y = shiftY.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { numberOnly = false })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val speedSize = min(boardWidth.value, boardHeight.value) * when (speedText.length) {
                    1 -> 0.68f
                    2 -> 0.54f
                    3 -> 0.40f
                    else -> 0.30f
                }
                BasicText(
                    text = speedText,
                    maxLines = 1,
                    softWrap = false,
                    style = textStyle(
                        size = speedSize,
                        color = numberColor,
                        weight = FontWeight.Bold,
                        letterSpacing = (-0.04).em,
                    ).copy(lineHeight = (speedSize * 1.05f).sp),
                )
            }
            return@BoxWithConstraints
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = boardHeight * 0.12f, bottom = boardHeight * 0.16f)
                .offset(x = shiftX.dp, y = shiftY.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    text = clockText(),
                    style = textStyle(
                        size = 13f,
                        color = if (lowBitAmbient && isAmbient) Color.White else Muted,
                        weight = FontWeight.Medium,
                        letterSpacing = 0.08.em,
                    ),
                )
                BasicText(
                    text = heartLabel,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (isAmbient) {
                                Modifier
                            } else {
                                Modifier.pointerInput(heartAllowed, onOpenHeart, onRequestHeart) {
                                    detectTapGestures(onTap = {
                                        if (heartAllowed) onOpenHeart() else onRequestHeart()
                                    })
                                }
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = textStyle(
                        size = 16f,
                        color = heartColor,
                        weight = FontWeight.Bold,
                        letterSpacing = 0.02.em,
                        tight = false,
                    ),
                )
                BasicText(
                    text = placeLabel(mode),
                    modifier = Modifier.padding(top = 1.dp),
                    maxLines = 1,
                    softWrap = false,
                    style = textStyle(
                        size = 9f,
                        color = Muted,
                        weight = FontWeight.Normal,
                        letterSpacing = 0.04.em,
                        tight = false,
                    ),
                )
                val fastest = speedPeak
                val fastestKmh = fastest?.speedKmh
                if (!isAmbient && fastest != null && fastestKmh != null) {
                    val atPeak = fastest.bpm?.let { "$it bpm" } ?: "no pulse"
                    BasicText(
                        text = "$fastestKmh km/h · $atPeak",
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .pointerInput(onOpenHeart) {
                                detectTapGestures(onTap = { onOpenHeart() })
                            }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1,
                        softWrap = false,
                        style = textStyle(
                            size = 9f,
                            color = AccelCool,
                            weight = FontWeight.Normal,
                            letterSpacing = 0.02.em,
                            tight = false,
                        ),
                    )
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { numberOnly = true })
                    },
                contentAlignment = Alignment.Center,
            ) {
                val speedSize = maxHeight.value * when (speedText.length) {
                    1, 2 -> 0.78f
                    3 -> 0.64f
                    else -> 0.52f
                }
                BasicText(
                    text = speedText,
                    maxLines = 1,
                    softWrap = false,
                    style = textStyle(
                        size = speedSize,
                        color = numberColor,
                        weight = FontWeight.Bold,
                        letterSpacing = (-0.04).em,
                    ).copy(lineHeight = (speedSize * 1.12f).sp),
                )
            }
            Box(
                modifier = Modifier.height(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!isAmbient) {
                    BasicText(
                        text = statusText(sample, ageMs, stale, waiting, weak),
                        maxLines = 1,
                        softWrap = false,
                        style = textStyle(
                            size = 11f,
                            color = Muted,
                            weight = FontWeight.Medium,
                            letterSpacing = 0.02.em,
                            tight = false,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromptScreen(
    title: String,
    action: String,
    note: String?,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack)
            .pointerInput(onClick) {
                detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText(
                text = title,
                style = textStyle(size = 22f, color = Color.White, weight = FontWeight.SemiBold),
            )
            BasicText(
                text = action,
                modifier = Modifier.padding(top = 10.dp),
                style = textStyle(size = 14f, color = LiveUnit, weight = FontWeight.Medium),
            )
            if (note != null) {
                BasicText(
                    text = note,
                    modifier = Modifier.padding(top = 14.dp),
                    style = textStyle(size = 13f, color = LiveUnit, weight = FontWeight.Medium, letterSpacing = 0.06.em),
                )
            }
        }
    }
}

@Composable
private fun clockText(): String {
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val minute = System.currentTimeMillis() / 60_000L
    return remember(minute, is24Hour) {
        val pattern = if (is24Hour) "HH:mm" else "h:mm"
        LocalTime.now().format(DateTimeFormatter.ofPattern(pattern))
    }
}

private fun placeLabel(mode: LocationMode): String {
    return if (mode == LocationMode.BOTH) "Watch + phone" else "Watch only"
}

private fun statusText(
    sample: SpeedSample,
    ageMs: Long,
    stale: Boolean,
    waiting: Boolean,
    weak: Boolean,
): String {
    if (!sample.hasFix || ageMs < 0L) return "Searching"
    if (stale) return "No signal"
    val age = SpeedFormat.age(ageMs)
    if (waiting) return age
    if (weak) {
        val meters = sample.accuracyMeters?.toInt() ?: 0
        return "${meters}m  ·  $age"
    }
    val sats = if (sample.satellitesUsed > 0) "${sample.satellitesUsed} sats  ·  " else ""
    return "$sats$age"
}

private fun textStyle(
    size: Float,
    color: Color,
    weight: FontWeight,
    letterSpacing: TextUnit = 0.em,
    tight: Boolean = true,
): TextStyle {
    return TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        letterSpacing = letterSpacing,
        lineHeight = if (tight) size.sp else (size * 1.35f).sp,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}
