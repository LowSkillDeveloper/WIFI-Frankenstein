package com.lsd.wififrankenstein.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalMapDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalWifiNetwork
import com.lsd.wififrankenstein.ui.personalmap.PersonalMapLogEntry
import com.lsd.wififrankenstein.ui.personalmap.PersonalMapScanSettings
import com.lsd.wififrankenstein.util.GpsValidator
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.MacAddressUtils
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class WiFiMapScanningService : Service(), LocationListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val wifiManager by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private lateinit var gpsValidator: GpsValidator
    private lateinit var personalDbHelper: PersonalMapDbHelper
    private lateinit var inAppDbHelper: LocalAppDbHelper

    private var lastLocation: Location? = null
    private var scanJob: Job? = null
    private var isSavingEnabled = true
    private val scanInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val throttleStrikes = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var scanRequestAtMs = 0L
    private val dbWriteMutex = Mutex()
    private var lastNotificationMs = 0L
    private var lastNotificationHealth = ""
    private var lastNotificationCount = -1
    private val kalmanFilter = com.lsd.wififrankenstein.util.KalmanFilter()
    @Volatile private var lastScanResultAtMs = 0L
    private val batteryCheckRunnable = object : Runnable {
        override fun run() {
            if (!isSavingEnabled) return
            checkBatteryAndStopIfLow()
            _batteryHandler.postDelayed(this, BATTERY_CHECK_INTERVAL_MS)
        }
    }
    private val _batteryHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun settingsPrefs() =
        getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scanInFlight.set(false)
            lastScanResultAtMs = System.currentTimeMillis()
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            } else {
                true
            }
            if (!success) {

                throttleStrikes.incrementAndGet()
                return
            }

            val pendingResult = goAsync()
            serviceScope.launch(Dispatchers.IO) {
                try {
                    handleScanResults()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "wifi_map_scan_channel"
        private const val NOTIFICATION_ID = 6002
        private const val MAX_LOCATION_AGE_MS = 30_000L
        private const val MAX_LOG_ENTRIES = 20
        private const val NOTIFICATION_THROTTLE_MS = 15_000L

        const val KEY_DB_LOCK = "personal_db_locked"

        const val ACTION_START = "start_scan"
        const val ACTION_STOP = "stop_scan"
        const val EXTRA_SAVE = "save"

        const val BROADCAST_STATE = "wifi_map_scan_state"
        const val EXTRA_SCANNING = "is_scanning"
        const val EXTRA_NETWORKS_COUNT = "networks_count"
        const val EXTRA_GPS_HEALTH = "gps_health"
        const val EXTRA_RECENT_NETWORKS = "recent_networks"
        const val EXTRA_SCAN_TIME = "scan_time"
        const val EXTRA_GPS_ACCURACY = "gps_accuracy"
        const val EXTRA_GPS_SPEED = "gps_speed"
        const val EXTRA_GPS_SATELLITES = "gps_satellites"
        const val EXTRA_GPS_FIX_AGE = "gps_fix_age"
        const val KEY_BATTERY_KILL_ENABLED = "personal_battery_kill_enabled"
        const val KEY_BATTERY_KILL_PERCENT = "personal_battery_kill_percent"
        const val DEFAULT_BATTERY_KILL_PERCENT = 15
        private const val BATTERY_CHECK_INTERVAL_MS = 30_000L
        private const val TAG = "WiFiMapScanSvc"
    }

    override fun onCreate() {
        super.onCreate()
        gpsValidator = GpsValidator(this)
        personalDbHelper = PersonalMapDbHelper(this)
        inAppDbHelper = LocalAppDbHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isSavingEnabled = intent.getBooleanExtra(EXTRA_SAVE, true)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }
                startScanning()
            }
            ACTION_STOP -> {
                stopScanning()
                stopForegroundCompat()
                stopSelf()
            }
            else -> {

                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startScanning() {
        if (scanJob?.isActive == true) return

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiScanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiScanReceiver, filter)
        }

        requestLocationUpdates()
        startBatteryCheck()
        kalmanFilter.reset()

        scanJob = serviceScope.launch {
            while (isActive) {
                val interval = PersonalMapScanSettings.intervalFor(
                    settingsPrefs(), lastLocation?.takeIf { it.hasSpeed() }?.speed
                )

                if (scanInFlight.get() &&
                    System.currentTimeMillis() - scanRequestAtMs > maxOf(interval * 3, 30_000L)
                ) {
                    Log.w(TAG, "scan result watchdog: resetting stuck flag")
                    scanInFlight.set(false)
                }

                if (scanInFlight.get() &&
                    lastScanResultAtMs > 0 &&
                    System.currentTimeMillis() - lastScanResultAtMs > interval * 3
                ) {
                    try {
                        Log.w(TAG, "WiFi jam detected, toggling WiFi")
                        wifiManager.isWifiEnabled = false
                        delay(500)
                        wifiManager.isWifiEnabled = true
                        delay(1000)
                    } catch (e: Exception) {
                        Log.w(TAG, "WiFi toggle failed: ${e.message}")
                    }
                    lastScanResultAtMs = System.currentTimeMillis()
                }

                if (scanInFlight.compareAndSet(false, true)) {
                    scanRequestAtMs = System.currentTimeMillis()
                    val started = try {
                        wifiManager.startScan()
                    } catch (e: Exception) {
                        Log.e(TAG, "startScan failed", e)
                        notifyState(true, 0)
                        false
                    }

                    if (!started) {
                        scanInFlight.set(false)
                        throttleStrikes.incrementAndGet()
                    }
                }

                delay(if (throttleStrikes.get() >= 3) interval * 2 else interval)
            }
        }
        notifyState(true, 0)
    }

    private fun stopScanning() {
        scanJob?.cancel()
        stopBatteryCheck()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {
        }
        removeLocationUpdates()
        notifyState(false, 0)
    }

    private fun startBatteryCheck() {
        val enabled = settingsPrefs().getBoolean(KEY_BATTERY_KILL_ENABLED, false)
        if (enabled) {
            _batteryHandler.post(batteryCheckRunnable)
        }
    }

    private fun stopBatteryCheck() {
        _batteryHandler.removeCallbacks(batteryCheckRunnable)
    }

    private fun checkBatteryAndStopIfLow() {
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val threshold = settingsPrefs().getInt(KEY_BATTERY_KILL_PERCENT, DEFAULT_BATTERY_KILL_PERCENT)
        if (level <= threshold && !charging) {
            Log.w(TAG, "Battery low ($level% <= $threshold%), stopping scan")
            stopScanning()
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun requestLocationUpdates() {

        try {
            val gpsLast = runCatching {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }.getOrNull()
            val netLast = runCatching {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()
            lastLocation = listOfNotNull(gpsLast, netLast).maxByOrNull { it.time } ?: lastLocation
        } catch (_: SecurityException) {
        }

        val minTimeMs = PersonalMapScanSettings.LOCATION_UPDATE_MIN_MS
            .coerceAtLeast(PersonalMapScanSettings.MIN_MS)
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    5f,
                    this
                )
            }
        } catch (e: SecurityException) {
            Log.e("WiFiMapScanningService", "Location permission missing", e)
        }
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    minTimeMs,
                    10f,
                    this
                )
            }
        } catch (e: SecurityException) {
            Log.e("WiFiMapScanningService", "Location permission missing", e)
        }
    }

    private fun removeLocationUpdates() {
        try {
            locationManager.removeUpdates(this)
        } catch (e: SecurityException) {
            Log.e("WiFiMapScanningService", "Location permission missing on remove", e)
        } catch (_: Exception) {
        }
    }

    private suspend fun handleScanResults() {
        try {
            val results = wifiManager.scanResults
            var location = lastLocation

            if (results.isEmpty()) {

                throttleStrikes.incrementAndGet()
                val health = location?.let { gpsValidator.validateLocation(it) }
                    ?: GpsValidator.GpsHealth.BAD
                val now = System.currentTimeMillis()
                notifyState(
                    isScanning = true,
                    count = 0,
                    gpsHealth = health.name,
                    scanTime = now,
                    gpsAccuracy = location?.accuracy ?: 0f,
                    gpsSpeed = location?.speed ?: 0f,
                    gpsSatellites = runCatching { gpsValidator.getSatelliteCount() }.getOrDefault(0),
                    gpsFixAgeMs = if (location != null && location.time > 0L) now - location.time else 0L
                )
                updateNotification(0, health.name)
                return
            }
            throttleStrikes.set(0)

            run {
                val health = location?.let { gpsValidator.validateLocation(it) } ?: GpsValidator.GpsHealth.BAD
                val gpsProviderReliable = location?.provider == LocationManager.GPS_PROVIDER

                val isGpsReliable = gpsProviderReliable &&
                        health != GpsValidator.GpsHealth.UNRELIABLE && health != GpsValidator.GpsHealth.BAD

                var wifiFallbackUsed = false
                if (!isGpsReliable) {
                    val scans = results.map { Pair(it.BSSID, it.level) }
                    val wifiLocation = dbWriteMutex.withLock {
                        personalDbHelper.findReliableLocation(scans, inAppDbHelper)
                    }
                    if (wifiLocation != null) {

                        val mockLocation = Location("WiFi_Ref").apply {
                            latitude = wifiLocation.first
                            longitude = wifiLocation.second
                            accuracy = 50f
                            time = System.currentTimeMillis()
                        }
                        location = mockLocation
                        wifiFallbackUsed = true
                        Log.d("WiFiMapScanningService", "Anti-Spoofing active: Using database location")
                    }
                }

                var recentEntries: List<PersonalMapLogEntry> = emptyList()
                if (location != null && isSavingEnabled) {
                    val finalLoc = location!!

                    val validCoords = finalLoc.latitude.isFinite() && finalLoc.longitude.isFinite() &&
                            !(finalLoc.latitude == 0.0 && finalLoc.longitude == 0.0) &&
                            finalLoc.latitude in -90.0..90.0 && finalLoc.longitude in -180.0..180.0
                    if (!validCoords) {
                        Log.d("WiFiMapScanningService", "Skipping invalid location fix")
                    } else if (runCatching { getCacheDir().usableSpace }.getOrDefault(Long.MAX_VALUE) < 10L * 1024 * 1024) {
                        Log.w("WiFiMapScanningService", "Skipping write: low storage")
                    } else if (settingsPrefs().getBoolean(KEY_DB_LOCK, false)) {

                        Log.d("WiFiMapScanningService", "Skipping write: database locked by backup/restore")
                    } else {

                    val locationAge = System.currentTimeMillis() - finalLoc.time
                    val isFresh = finalLoc.time <= 0L || locationAge <= MAX_LOCATION_AGE_MS
                    if (isFresh) {
                        val timestamp = System.currentTimeMillis()

                        val batch = ArrayList<PersonalWifiNetwork>(results.size)
                        val normalizedList = ArrayList<String>(results.size)
                        results.forEach { result ->
                            val normalized = MacAddressUtils
                                .formatToColonSeparated(result.BSSID)
                                ?: result.BSSID.uppercase()
                            normalizedList.add(normalized)
                            batch.add(
                                PersonalWifiNetwork(
                                    wifiName = result.SSID,
                                    macAddress = normalized,
                                    latitude = finalLoc.latitude,
                                    longitude = finalLoc.longitude,
                                    timestamp = timestamp,
                                    level = result.level,
                                    accuracy = finalLoc.accuracy,
                                    isReliable = isGpsReliable,
                                    security = result.capabilities,
                                    frequency = result.frequency,
                                    source = PersonalMapDbHelper.SOURCE_SCAN,
                                    isWps = result.capabilities?.contains("WPS", ignoreCase = true) == true,
                                    alt = finalLoc.altitude,
                                    isPasspoint = result.capabilities?.contains("HS20", ignoreCase = true) == true
                                            || result.capabilities?.contains("Passpoint", ignoreCase = true) == true,
                                    isHidden = result.SSID.isBlank() && !result.capabilities.isNullOrBlank(),
                                    band = PersonalMapDbHelper.bandForFrequency(result.frequency)
                                )
                            )
                        }
                        val writeResult = dbWriteMutex.withLock {
                            personalDbHelper.addPersonalRecords(batch)
                        }
                        recentEntries = batch.mapIndexed { index, network ->
                            PersonalMapLogEntry(
                                ssid = network.wifiName,
                                bssid = network.macAddress,
                                level = network.level,
                                isReliable = network.isReliable,
                                isNew = normalizedList[index] in writeResult.newMacs,
                                timestamp = network.timestamp,
                                isWps = network.isWps,
                                isOpen = PersonalMapDbHelper.classifySecurity(network.security) ==
                                    PersonalMapDbHelper.SECURITY_OPEN
                            )
                        }.take(MAX_LOG_ENTRIES)
                    } else {
                        Log.d("WiFiMapScanningService", "Skipping stale location fix (${locationAge}ms)")
                    }
                    }
                }

                val gpsHealthName = if (wifiFallbackUsed) "WIFI_RECOVERED" else health.name
                val gpsFix = location
                notifyState(
                    isScanning = true,
                    count = results.size,
                    gpsHealth = gpsHealthName,
                    recentNetworks = recentEntries,
                    scanTime = System.currentTimeMillis(),
                    gpsAccuracy = gpsFix?.accuracy ?: 0f,
                    gpsSpeed = gpsFix?.speed ?: 0f,
                    gpsSatellites = runCatching { gpsValidator.getSatelliteCount() }.getOrDefault(0),
                    gpsFixAgeMs = if (gpsFix != null && gpsFix.time > 0L) {
                        System.currentTimeMillis() - gpsFix.time
                    } else 0L
                )
                updateNotification(results.size, gpsHealthName)
            }
        } catch (e: Exception) {
            Log.e("WiFiMapScanningService", "Error during scan results handling", e)
        }
    }

    private fun notifyState(
        isScanning: Boolean,
        count: Int,
        gpsHealth: String = "UNKNOWN",
        recentNetworks: List<PersonalMapLogEntry> = emptyList(),
        scanTime: Long = 0L,
        gpsAccuracy: Float = 0f,
        gpsSpeed: Float = 0f,
        gpsSatellites: Int = 0,
        gpsFixAgeMs: Long = 0L
    ) {
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_SCANNING, isScanning)
            putExtra(EXTRA_NETWORKS_COUNT, count)
            putExtra(EXTRA_GPS_HEALTH, gpsHealth)
            putExtra(EXTRA_SCAN_TIME, scanTime)
            putExtra(EXTRA_GPS_ACCURACY, gpsAccuracy)
            putExtra(EXTRA_GPS_SPEED, gpsSpeed)
            putExtra(EXTRA_GPS_SATELLITES, gpsSatellites)
            putExtra(EXTRA_GPS_FIX_AGE, gpsFixAgeMs)
            if (recentNetworks.isNotEmpty()) {
                putExtra(EXTRA_RECENT_NETWORKS, Json.encodeToString(recentNetworks))
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onLocationChanged(location: Location) {
        kalmanFilter.process(
            location.latitude, location.longitude,
            location.accuracy, location.time
        )
        location.latitude = kalmanFilter.latitude
        location.longitude = kalmanFilter.longitude
        location.accuracy = kalmanFilter.accuracy
        lastLocation = location
    }

    private fun createNotification(count: Int = 0, gpsHealth: String = ""): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {

        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = Intent(this, WiFiMapScanningService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val statusText = if (gpsHealth.isNotEmpty()) {
            getString(R.string.scanning_active) + " | GPS: $gpsHealth | Nets: $count"
        } else {
            getString(R.string.scanning_active)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.personal_map_title))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_menu_mapmode)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_close, getString(R.string.stop), stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(count: Int, gpsHealth: String) {
        val now = System.currentTimeMillis()

        if (gpsHealth == lastNotificationHealth && count == lastNotificationCount &&
            now - lastNotificationMs < NOTIFICATION_THROTTLE_MS
        ) return
        lastNotificationMs = now
        lastNotificationHealth = gpsHealth
        lastNotificationCount = count
        notificationManager.notify(NOTIFICATION_ID, createNotification(count, gpsHealth))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.personal_map_title),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {

        stopScanning()
        stopForegroundCompat()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        gpsValidator.unregister()
        serviceScope.cancel()

        // BUG-2 FIX: serviceScope is already cancelled above, so there is no
        // in-flight work left to wait for. Never block the main thread here.

        try {
            if (::personalDbHelper.isInitialized) {
                personalDbHelper.close()
            }
        } catch (_: Exception) {
        }
        try {
            if (::inAppDbHelper.isInitialized) {
                inAppDbHelper.close()
            }
        } catch (_: Exception) {
        }
    }
}
