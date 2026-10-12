package com.watchspeed.live

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the watch GPS chip running while the speed screen is open, including
 * always-on / ambient. A partial wake lock stops the CPU from sleeping between fixes.
 */
class SpeedService : Service() {
    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var gpsExecutor: ExecutorService

    private val tracking = AtomicBoolean(false)
    private val phoneStarted = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastNotifyElapsed = 0L
    private var lastWakeElapsed = 0L

    private val wakeLock by lazy {
        (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchspeed:gps")
            .apply { setReferenceCounted(false) }
    }

    private val gpsListener = LocationListener { location ->
        SpeedHub.publish(location, FixSource.WATCH)
        touchWakeLock()
        maybeNotify()
    }

    private val phoneCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            SpeedHub.publish(location, FixSource.PHONE)
            touchWakeLock()
            maybeNotify()
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (index in 0 until status.satelliteCount) {
                if (status.usedInFix(index)) used++
            }
            SpeedHub.setSatellites(used)
        }
    }

    private val providerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            refreshTracking()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LocationManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        gpsExecutor = Executors.newSingleThreadExecutor()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("Searching GPS")
        registerProviderReceiver()
        refreshTracking()
        applyLocationMode()
        touchWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        tracking.set(false)
        stopUpdates()
        unregisterProviderReceiver()
        gpsExecutor.shutdownNow()
        if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun refreshTracking() {
        val watchGps = gpsProviderEnabled()
        val canTrack = watchGps || SpeedHub.mode.value == LocationMode.BOTH
        SpeedHub.setGpsEnabled(canTrack)
        if (watchGps) startUpdates() else stopWatchUpdates()
        applyLocationMode()
        maybeNotify(force = true)
    }

    private fun gpsProviderEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (!tracking.compareAndSet(false, true)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = android.location.LocationRequest.Builder(GPS_INTERVAL_MS)
                    .setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
                    .setMaxUpdateDelayMillis(0L)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    request,
                    gpsExecutor,
                    gpsListener,
                )
            } else {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_MIN_INTERVAL_MS,
                    0f,
                    gpsExecutor,
                    gpsListener,
                )
            }
        } catch (_: Exception) {
            tracking.set(false)
        }
        try {
            locationManager.registerGnssStatusCallback(gpsExecutor, gnssCallback)
        } catch (_: Exception) {
            // Satellite count is optional. Speed still works without it.
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationMode() {
        if (SpeedHub.mode.value == LocationMode.BOTH) {
            startPhoneUpdates()
        } else {
            stopPhoneUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPhoneUpdates() {
        if (!phoneStarted.compareAndSet(false, true)) return
        try {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
                .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
                .setMaxUpdateDelayMillis(0L)
                .setWaitForAccurateLocation(false)
                .build()
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, phoneCallback, Looper.getMainLooper())
        } catch (_: Exception) {
            phoneStarted.set(false)
        }
    }

    private fun stopPhoneUpdates() {
        if (!phoneStarted.compareAndSet(true, false)) return
        try {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(phoneCallback)
        } catch (_: Exception) {
        }
    }

    private fun stopWatchUpdates() {
        tracking.set(false)
        try {
            locationManager.removeUpdates(gpsListener)
        } catch (_: Exception) {
        }
        try {
            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }
    }

    private fun stopUpdates() {
        stopWatchUpdates()
        stopPhoneUpdates()
    }

    private fun touchWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeElapsed < WAKE_RENEW_MS && wakeLock.isHeld) return
        lastWakeElapsed = now
        try {
            if (wakeLock.isHeld) wakeLock.release()
            wakeLock.acquire(WAKE_HOLD_MS)
        } catch (_: RuntimeException) {
        }
    }

    private fun registerProviderReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(providerReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(providerReceiver, filter)
            }
        } catch (error: RuntimeException) {
            receiverRegistered.set(false)
            throw error
        }
    }

    private fun unregisterProviderReceiver() {
        try {
            unregisterReceiver(providerReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun maybeNotify(force: Boolean = false) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            maybeNotifyOnMain(force)
        } else {
            mainHandler.post { maybeNotifyOnMain(force) }
        }
    }

    private fun maybeNotifyOnMain(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyElapsed < NOTIFY_INTERVAL_MS) return
        lastNotifyElapsed = now
        val notification = buildNotification(currentSpeedText())
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun currentSpeedText(): String {
        val sample = SpeedHub.sample.value
        if (!SpeedHub.gpsEnabled.value) return "Location off"
        if (!sample.hasFix || sample.metersPerSecond == null) return "Searching GPS"
        val speed = SpeedFormat.format(sample.metersPerSecond)
        return "$speed ${SpeedFormat.unit()}"
    }

    private fun startAsForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): android.app.Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_speed)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        val status = Status.Builder()
            .addTemplate("#speed#")
            .addPart("speed", Status.TextPart(text))
            .build()
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_stat_speed)
            .setTouchIntent(pendingIntent)
            .setStatus(status)
            .build()
            .apply(this)
        return builder.build()
    }

    companion object {
        const val ACTION_APPLY_MODE = "com.watchspeed.live.APPLY_MODE"
        private const val CHANNEL_ID = "live_speed"
        const val NOTIFICATION_ID = 42
        const val GPS_INTERVAL_MS = 200L
        const val GPS_MIN_INTERVAL_MS = 100L
        const val NOTIFY_INTERVAL_MS = 1_000L
        const val WAKE_HOLD_MS = 60L * 60L * 1_000L
        const val WAKE_RENEW_MS = 10L * 60L * 1_000L
    }
}
