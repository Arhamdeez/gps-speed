package com.watchspeed.live

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.isActive

private val ScreenBlack = Color(0xFF000000)
private val AmbientInk = Color(0xFFD0D0D0)
private val Muted = Color(0xFF8A8A8A)
private val LiveUnit = Color(0xFF3DDCFF)
private val WeakSpeed = Color(0xFFFFC14D)
private val EmptySpeed = Color(0xFF4A4A4A)

@Composable
fun SpeedScreen(
    isAmbient: Boolean,
    burnInProtection: Boolean,
    lowBitAmbient: Boolean,
    ambientTick: Int,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onToggleUnit: () -> Unit,
) {
    if (!hasPermission) {
        PromptScreen(
            title = "Speed needs GPS",
            action = "Tap to allow location",
            onClick = onRequestPermission,
        )
        return
    }

    val gpsEnabled by SpeedHub.gpsEnabled.collectAsStateWithLifecycle()
    if (!gpsEnabled) {
        PromptScreen(
            title = "Location is off",
            action = "Tap to turn on GPS",
            onClick = onOpenLocationSettings,
        )
        return
    }

    val sample by SpeedHub.sample.collectAsStateWithLifecycle()
    val useMph by SpeedHub.useMph.collectAsStateWithLifecycle()
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
    val speedText = SpeedFormat.format(if (stale) null else sample.metersPerSecond, useMph)
    val weak = !stale && (sample.accuracyMeters ?: 0f) > 25f
    val numberColor = when {
        isAmbient && lowBitAmbient -> Color.White
        isAmbient -> AmbientInk
        speedText == "--.-" -> EmptySpeed
        weak -> WeakSpeed
        else -> Color.White
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack),
    ) {
        val shiftX = if (isAmbient && burnInProtection) ((ambientTick % 3) - 1) * 6 else 0
        val shiftY = if (isAmbient && burnInProtection) (((ambientTick / 3) % 3) - 1) * 6 else 0
        val speedSize = maxWidth.value * if (speedText.length >= 5) 0.22f else 0.30f

        BasicText(
            text = clockText(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight * 0.14f)
                .offset(x = shiftX.dp, y = shiftY.dp),
            style = textStyle(
                size = 13f,
                color = if (lowBitAmbient && isAmbient) Color.White else Muted,
                weight = FontWeight.Medium,
            ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = shiftX.dp, y = shiftY.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            BasicText(
                text = speedText,
                style = textStyle(
                    size = speedSize,
                    color = numberColor,
                    weight = FontWeight.Bold,
                ),
            )
            BasicText(
                text = SpeedFormat.unit(useMph),
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 2.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleUnit,
                    )
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                style = textStyle(
                    size = 16f,
                    color = if (isAmbient) Muted else LiveUnit,
                    weight = FontWeight.SemiBold,
                ),
            )
            if (!isAmbient) {
                BasicText(
                    text = statusText(sample, ageMs, stale, waiting, weak),
                    modifier = Modifier.padding(top = 2.dp),
                    style = textStyle(size = 12f, color = Muted, weight = FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
private fun PromptScreen(title: String, action: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
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
    ageMs: Long,
    stale: Boolean,
    waiting: Boolean,
    weak: Boolean,
): String {
    if (!sample.hasFix || ageMs < 0L) return "Searching for GPS"
    if (stale) return "No signal"
    val age = SpeedFormat.age(ageMs)
    if (waiting) return "Waiting  ·  $age"
    if (weak) {
        val meters = sample.accuracyMeters?.toInt() ?: 0
        return "Weak GPS  ·  ${meters}m  ·  $age"
    }
    val sats = if (sample.satellitesUsed > 0) "${sample.satellitesUsed} sats  ·  " else ""
    return "${sample.sourceLabel}  ·  $sats$age"
}

private fun textStyle(size: Float, color: Color, weight: FontWeight): TextStyle {
    return TextStyle(
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        fontFeatureSettings = "tnum",
        textAlign = TextAlign.Center,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
}
