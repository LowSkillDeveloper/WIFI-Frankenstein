package com.lsd.wififrankenstein.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.database.sqlite.transaction
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.LocalAppDbHelper
import com.lsd.wififrankenstein.ui.dbsetup.localappdb.PersonalWifiNetwork
import com.lsd.wififrankenstein.util.GpsValidator
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.*

class WiFiMapScanningService : Service(), LocationListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val wifiManager by lazy { applicationContext.getSystemService(WIFI_SERVICE) as WifiManager }
    private val locationManager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private lateinit var gpsValidator: GpsValidator
    private lateinit var dbHelper: LocalAppDbHelper
    
    private var lastLocation: Location? = null
    private var scanJob: Job? = null
    private var scanIntervalMs: Long = MIN_SCAN_INTERVAL_MS
    private var isSavingEnabled = true

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            } else {
                true // On older versions, the broadcast itself implies some results are available
            }
            if (success) {
                handleScanResults()
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "wifi_map_scan_channel"
        private const val NOTIFICATION_ID = 5001
        private const val MIN_SCAN_INTERVAL_MS = 5000L
        
        const val ACTION_START = "start_scan"
        const val ACTION_STOP = "stop_scan"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_SAVE = "save"

        const val BROADCAST_STATE = "wifi_map_scan_state"
        const val EXTRA_SCANNING = "is_scanning"
        const val EXTRA_NETWORKS_COUNT = "networks_count"
        const val EXTRA_GPS_HEALTH = "gps_health"
    }

    override fun onCreate() {
        super.onCreate()
        gpsValidator = GpsValidator(this)
        dbHelper = LocalAppDbHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requestedInterval = intent.getLongExtra(EXTRA_INTERVAL, MIN_SCAN_INTERVAL_MS)
                scanIntervalMs = Math.max(MIN_SCAN_INTERVAL_MS, requestedInterval)
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
        }
        return START_STICKY
    }

    private fun startScanning() {
        if (scanJob?.isActive == true) return
        
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, filter)
        
        requestLocationUpdates()
        
        scanJob = serviceScope.launch {
            while (isActive) {
                wifiManager.startScan()
                delay(scanIntervalMs)
            }
        }
        notifyState(true, 0)
    }

    private fun stopScanning() {
        scanJob?.cancel()
        try {
            unregisterReceiver(wifiScanReceiver)
        } catch (_: Exception) {
            // Ignore if not registered
        }
        removeLocationUpdates()
        notifyState(false, 0)
    }

    private fun requestLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0f,
                this
            )
        } catch (e: SecurityException) {
            Log.e("WiFiMapScanningService", "Location permission missing", e)
        }
    }

    private fun removeLocationUpdates() {
        locationManager.removeUpdates(this)
    }

    private fun handleScanResults() {
        try {
            val results = wifiManager.scanResults
            var location = lastLocation
            
            if (results.isNotEmpty()) {
                val health = location?.let { gpsValidator.validateLocation(it) } ?: GpsValidator.GpsHealth.BAD
                val isGpsReliable = health != GpsValidator.GpsHealth.UNRELIABLE && health != GpsValidator.GpsHealth.BAD
                
                // Anti-Spoofing: Если GPS врет, пробуем найти локацию по Wi-Fi
                if (!isGpsReliable) {
                    val scans = results.map { Pair(it.BSSID, it.level) }
                    val wifiLocation = dbHelper.findReliableLocation(scans)
                    if (wifiLocation != null) {
                        // Создаем фиктивную локацию на основе данных из базы
                        val mockLocation = Location("WiFi_Ref").apply {
                            latitude = wifiLocation.first
                            longitude = wifiLocation.second
                            accuracy = 50f // Примерная точность
                            time = System.currentTimeMillis()
                        }
                        location = mockLocation
                        Log.d("WiFiMapScanningService", "Anti-Spoofing active: Using database location")
                    }
                }

                if (location != null && isSavingEnabled) {
                    val finalLoc = location!!
                    val timestamp = System.currentTimeMillis()
                    val noise = gpsValidator.getAverageNoise()
                    val satellites = gpsValidator.getSatelliteCount()
                    
                    dbHelper.writableDatabase.transaction {
                        results.forEach { result ->
                            val network = PersonalWifiNetwork(
                                wifiName = result.SSID,
                                macAddress = result.BSSID,
                                latitude = finalLoc.latitude,
                                longitude = finalLoc.longitude,
                                timestamp = timestamp,
                                level = result.level,
                                accuracy = finalLoc.accuracy,
                                isReliable = isGpsReliable,
                                noiseLevel = noise,
                                satellites = satellites,
                                speed = finalLoc.speed
                            )
                            dbHelper.addPersonalRecord(network)
                        }
                    }
                }
                
                val gpsHealthName = if (!isGpsReliable && location != null) "WIFI_RECOVERED" else health.name
                notifyState(true, results.size, gpsHealthName)
                updateNotification(results.size, gpsHealthName)
            }
        } catch (e: Exception) {
            Log.e("WiFiMapScanningService", "Error during scan results handling", e)
        }
    }

    private fun notifyState(isScanning: Boolean, count: Int, gpsHealth: String = "UNKNOWN") {
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_SCANNING, isScanning)
            putExtra(EXTRA_NETWORKS_COUNT, count)
            putExtra(EXTRA_GPS_HEALTH, gpsHealth)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    private fun createNotification(count: Int = 0, gpsHealth: String = ""): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            // Add extra to navigate to PersonalWiFiMapFragment if needed
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
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
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(count: Int, gpsHealth: String) {
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

    override fun onDestroy() {
        super.onDestroy()
        stopScanning()
        gpsValidator.unregister()
        serviceScope.cancel()
    }
}
