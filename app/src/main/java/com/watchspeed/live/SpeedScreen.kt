package com.watchspeed.live

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val ScreenBlack = Color(0xFF000000)
private val AmbientInk = Color(0xFFD0D0D0)
private val Muted = Color(0xFF8A8A8A)
private val LiveUnit = Color(0xFF3DDCFF)
private val WeakSpeed = Color(0xFFFFC14D)
private val EmptySpeed = Color(0xFF4A4A4A)
private val BrakeRed = Color(0xFFFF3B30)
private val AccelCool = Color(0xFF2EE0FF)

@Composable
fun SpeedScreen(
    isAmbient: Boolean,
    burnInProtection: Boolean,
    lowBitAmbient: Boolean,
    ambientTick: Int,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onCycleLocationMode: () -> Unit,
) {
    val context = LocalContext.current
    var modeAnnouncement by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(modeAnnouncement) {
        if (modeAnnouncement != null) {
            delay(1_700)
            modeAnnouncement = null
        }
    }
    val switchMode = remember(context, onCycleLocationMode) {
        {
            strongSwitchHaptic(context)
            onCycleLocationMode()
            modeAnnouncement = SpeedHub.mode.value.title()
        }
    }
    val holdToSwitch = Modifier.pointerInput(switchMode) {
        detectTapGestures(onLongPress = { switchMode() })
    }

    if (!hasPermission) {
        PromptScreen(
            title = "Speed needs GPS",
            action = "Tap to allow location",
            note = modeAnnouncement,
            onClick = onRequestPermission,
            onHold = switchMode,
        )
        return
    }

    val gpsEnabled by SpeedHub.gpsEnabled.collectAsStateWithLifecycle()
    if (!gpsEnabled) {
        PromptScreen(
            title = "Location is off",
            action = "Tap to turn on GPS",
            note = modeAnnouncement,
            onClick = onOpenLocationSettings,
            onHold = switchMode,
        )
        return
    }

    val sample by SpeedHub.sample.collectAsStateWithLifecycle()
    val mode by SpeedHub.mode.collectAsStateWithLifecycle()
    var frameClock by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
    if (!isAmbient) {
        LaunchedEffect(Unit) {
            while (isActive) {
                withFrameNanos { frameClock = SystemClock.elapsedRealtimeNanos() }
            }
        }
    }
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
    val unitColor = when {
        isAmbient -> if (lowBitAmbient) Color.White else Muted
        trendColor != null && speedText != "--" -> trendColor
        else -> LiveUnit
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack)
            .then(holdToSwitch),
    ) {
        val shiftX = if (isAmbient && burnInProtection) ((ambientTick % 3) - 1) * 6 else 0
        val shiftY = if (isAmbient && burnInProtection) (((ambientTick / 3) % 3) - 1) * 6 else 0
        val speedSize = maxWidth.value * when (speedText.length) {
            1, 2 -> 0.48f
            3 -> 0.39f
            else -> 0.30f
        }
        val drift = Modifier.offset(x = shiftX.dp, y = shiftY.dp)

        BasicText(
            text = clockText(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight * 0.15f)
                .then(drift),
            style = textStyle(
                size = 14f,
                color = if (lowBitAmbient && isAmbient) Color.White else Muted,
                weight = FontWeight.Medium,
                letterSpacing = 0.08.em,
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = shiftX.dp, y = shiftY.dp - maxHeight * 0.03f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = speedText,
                maxLines = 1,
                softWrap = false,
                style = textStyle(
                    size = speedSize,
                    color = numberColor,
                    weight = FontWeight.Bold,
                    letterSpacing = (-0.04).em,
                ),
            )
            BasicText(
                text = SpeedFormat.unit(),
                modifier = Modifier.padding(top = 2.dp),
                style = textStyle(
                    size = 15f,
                    color = unitColor,
                    weight = FontWeight.SemiBold,
                    letterSpacing = 0.12.em,
                ),
            )
        }

        if (!isAmbient || modeAnnouncement != null) {
            BasicText(
                text = modeAnnouncement ?: statusText(sample, mode, ageMs, stale, waiting, weak),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = maxHeight * 0.13f)
                    .then(drift),
                maxLines = 1,
                softWrap = false,
                style = textStyle(
                    size = if (modeAnnouncement != null) 13f else 12f,
                    color = if (modeAnnouncement != null && !isAmbient) LiveUnit else Muted,
                    weight = FontWeight.Medium,
                    letterSpacing = if (modeAnnouncement != null) 0.06.em else 0.02.em,
                ),
            )
        }
    }
}

@Composable
private fun PromptScreen(
    title: String,
    action: String,
    note: String?,
    onClick: () -> Unit,
    onHold: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack)
            .pointerInput(onClick, onHold) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onHold() },
                )
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

private fun statusText(
    sample: SpeedSample,
    mode: LocationMode,
    ageMs: Long,
    stale: Boolean,
    waiting: Boolean,
    weak: Boolean,
): String {
    val source = when {
        sample.sourceLabel == "Phone" -> "Phone"
        mode == LocationMode.BOTH -> "Both"
        else -> "Watch"
    }
    if (!sample.hasFix || ageMs < 0L) return "Searching  ·  $source"
    if (stale) return "No signal  ·  $source"
    val age = SpeedFormat.age(ageMs)
    if (waiting) return "$source  ·  $age"
    if (weak) {
        val meters = sample.accuracyMeters?.toInt() ?: 0
        return "$source  ·  ${meters}m  ·  $age"
    }
    val sats = if (sample.satellitesUsed > 0) "${sample.satellitesUsed} sats  ·  " else ""
    return "$source  ·  $sats$age"
}

private const val SWITCH_HAPTIC_MS = 80L
private const val SWITCH_HAPTIC_AMPLITUDE = 255

private fun strongSwitchHaptic(context: Context) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    if (!vibrator.hasVibrator()) return
    val effect = VibrationEffect.createOneShot(SWITCH_HAPTIC_MS, SWITCH_HAPTIC_AMPLITUDE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        vibrator.vibrate(
            effect,
            VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_TOUCH)
                .build(),
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(effect)
    }
}

private fun textStyle(
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
        lineHeight = size.sp,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}
