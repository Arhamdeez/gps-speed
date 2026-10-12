package com.watchspeed.live

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.wear.ambient.AmbientLifecycleObserver

class MainActivity : ComponentActivity() {
    private val isAmbient = mutableStateOf(false)
    private val burnInProtection = mutableStateOf(false)
    private val lowBitAmbient = mutableStateOf(false)
    private val ambientTick = mutableIntStateOf(0)
    private val hasPermission = mutableStateOf(false)

    private val alarmManager by lazy { getSystemService(AlarmManager::class.java) }

    private val ambientTickIntent by lazy {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_AMBIENT_TICK
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        PendingIntent.getActivity(
            this,
            7,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient.value = true
            burnInProtection.value = ambientDetails.burnInProtectionRequired
            lowBitAmbient.value = ambientDetails.deviceHasLowBitAmbient
            ambientTick.intValue++
            scheduleAmbientRefresh()
        }

        override fun onUpdateAmbient() {
            ambientTick.intValue++
            scheduleAmbientRefresh()
        }

        override fun onExitAmbient() {
            isAmbient.value = false
            cancelAmbientRefresh()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasPermission.value = granted
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val askedBefore = prefs.getBoolean(KEY_ASKED, false)
        prefs.edit().putBoolean(KEY_ASKED, true).apply()
        if (granted) {
            startSpeedService()
        } else if (
            askedBefore &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            openAppSettings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        SpeedHub.useMph.value = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_MPH, false)
        hasPermission.value = locationGranted()
        if (hasPermission.value) startSpeedService()

        setContent {
            SpeedScreen(
                isAmbient = isAmbient.value,
                burnInProtection = burnInProtection.value,
                lowBitAmbient = lowBitAmbient.value,
                ambientTick = ambientTick.intValue,
                hasPermission = hasPermission.value,
                onRequestPermission = ::requestLocationPermission,
                onOpenLocationSettings = ::openLocationSettings,
                onToggleUnit = ::toggleUnit,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (locationGranted()) {
            hasPermission.value = true
            startSpeedService()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_AMBIENT_TICK && isAmbient.value) {
            ambientTick.intValue++
            scheduleAmbientRefresh()
        }
    }

    override fun onDestroy() {
        cancelAmbientRefresh()
        if (isFinishing) {
            stopService(Intent(this, SpeedService::class.java))
        }
        super.onDestroy()
    }

    private fun requestLocationPermission() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun toggleUnit() {
        val next = !SpeedHub.useMph.value
        SpeedHub.useMph.value = next
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MPH, next).apply()
    }

    private fun locationGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSpeedService() {
        ContextCompat.startForegroundService(this, Intent(this, SpeedService::class.java))
    }

    private fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = android.net.Uri.fromParts("package", packageName, null)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /**
     * Ambient mode normally repaints about once a minute. A one-second alarm
     * wakes the watch often enough to push the latest GPS speed onto the
     * always-on screen. The GPS chip itself still reports about once a second.
     */
    private fun scheduleAmbientRefresh() {
        if (!isAmbient.value) return
        val triggerAt = System.currentTimeMillis() + AMBIENT_REFRESH_MS
        try {
            val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    ambientTickIntent,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    ambientTickIntent,
                )
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                ambientTickIntent,
            )
        }
    }

    private fun cancelAmbientRefresh() {
        alarmManager.cancel(ambientTickIntent)
    }

    private companion object {
        const val ACTION_AMBIENT_TICK = "com.watchspeed.live.AMBIENT_TICK"
        const val AMBIENT_REFRESH_MS = 1_000L
        const val PREFS = "speed"
        const val KEY_MPH = "use_mph"
        const val KEY_ASKED = "asked_location"
    }
}
