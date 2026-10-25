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
    private val heartAllowed = mutableStateOf(false)
    private val showHeartHistory = mutableStateOf(false)
    private var screenVisible = false

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
        if (result.containsKey(HeartRateAccess.permission())) {
            prefs.edit().putBoolean(KEY_HEART_ASKED, true).apply()
        }
        heartAllowed.value = heartGranted()
        if (granted) {
            if (screenVisible) startSpeedService()
        } else if (
            askedBefore &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        ) {
            openAppSettings()
        }
    }

    private val heartPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_HEART_ASKED, true).apply()
        heartAllowed.value = granted
        if (granted && screenVisible) startSpeedService(SpeedService.ACTION_HEART)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        hasPermission.value = locationGranted()
        heartAllowed.value = heartGranted()

        setContent {
            if (showHeartHistory.value) {
                RideScreen(
                    isAmbient = isAmbient.value,
                    burnInProtection = burnInProtection.value,
                    lowBitAmbient = lowBitAmbient.value,
                    ambientTick = ambientTick.intValue,
                    onBack = { showHeartHistory.value = false },
                )
            } else {
                SpeedScreen(
                    isAmbient = isAmbient.value,
                    burnInProtection = burnInProtection.value,
                    lowBitAmbient = lowBitAmbient.value,
                    ambientTick = ambientTick.intValue,
                    hasPermission = hasPermission.value,
                    onRequestPermission = ::requestLocationPermission,
                    onOpenLocationSettings = ::openLocationSettings,
                    heartAllowed = heartAllowed.value,
                    onOpenHeart = { showHeartHistory.value = true },
                    onRequestHeart = { requestHeartPermission(fromUser = true) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        screenVisible = true
        if (intent?.action == ACTION_AMBIENT_TICK && !isAmbient.value) {
            stopSpeedService()
            finish()
            return
        }
        if (locationGranted()) {
            hasPermission.value = true
            heartAllowed.value = heartGranted()
            startSpeedService()
        }
    }

    override fun onStop() {
        screenVisible = false
        if (!isChangingConfigurations) stopSpeedService()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action != ACTION_AMBIENT_TICK) return
        if (!isAmbient.value || !screenVisible) {
            stopSpeedService()
            finish()
            return
        }
        ambientTick.intValue++
        scheduleAmbientRefresh()
    }

    override fun onDestroy() {
        cancelAmbientRefresh()
        if (isFinishing) stopSpeedService()
        super.onDestroy()
    }

    private fun requestLocationPermission() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(HeartRateAccess.permission())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestHeartPermission(fromUser: Boolean) {
        if (heartGranted()) {
            heartAllowed.value = true
            if (screenVisible) startSpeedService(SpeedService.ACTION_HEART)
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val asked = prefs.getBoolean(KEY_HEART_ASKED, false)
        val rationale = shouldShowRequestPermissionRationale(HeartRateAccess.permission())
        if (fromUser && asked && !rationale) {
            openAppSettings()
            return
        }
        if (!fromUser && asked) return
        heartPermissionLauncher.launch(HeartRateAccess.permission())
    }

    private fun heartGranted(): Boolean = HeartRateAccess.granted(this)

    private fun locationGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startSpeedService(action: String? = null) {
        SpeedHub.sessionOpen = true
        val intent = Intent(this, SpeedService::class.java)
        if (action != null) intent.action = action
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopSpeedService() {
        SpeedHub.sessionOpen = false
        cancelAmbientRefresh()
        stopService(Intent(this, SpeedService::class.java))
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
     * always-on screen. While the screen is on, each GPS fix is drawn as it arrives.
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
        const val KEY_ASKED = "asked_location"
        const val KEY_HEART_ASKED = "asked_heart"
    }
}
