package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.netprotection.ArpDetector
import com.lsd.wififrankenstein.ui.netprotection.CapabilityDetector
import com.lsd.wififrankenstein.ui.netprotection.ConnectionMonitor
import com.lsd.wififrankenstein.ui.netprotection.EventType
import com.lsd.wififrankenstein.ui.netprotection.NetProtectionEvent
import com.lsd.wififrankenstein.ui.netprotection.PortScanDetector
import com.lsd.wififrankenstein.util.stopForegroundCompat

class NetProtectionService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val arpDetector = ArpDetector()
    private val portScanDetector = PortScanDetector()
    private val connectionMonitor = ConnectionMonitor()
    private var detectionResult: com.lsd.wififrankenstein.ui.netprotection.DetectionResult? = null
    private var gatewayIp: String = ""
    private var events = mutableListOf<NetProtectionEvent>()

    private var arpEnabled = true
    private var portScanEnabled = true
    private var connectionMonitorEnabled = true
    private var notificationHidden = false
    private var notificationPriority = NotificationManager.IMPORTANCE_LOW

    private var isRunning = false
    private var arpIntervalMs = ARP_INTERVAL
    private var portScanIntervalMs = PORT_SCAN_INTERVAL

    private val arpRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !arpEnabled) return
            checkArp()
            handler.postDelayed(this, arpIntervalMs)
        }
    }

    private val portScanRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !portScanEnabled) return
            checkPortScan()
            handler.postDelayed(this, PORT_SCAN_INTERVAL)
        }
    }

    private val connectionRunnable = object : Runnable {
        override fun run() {
            if (!isRunning || !connectionMonitorEnabled) return
            checkConnections()
            handler.postDelayed(this, CONNECTION_INTERVAL)
        }
    }

    companion object {
        private const val TAG = "NetProtectionService"
        private const val CHANNEL_ID = "net_protection_channel"
        private const val NOTIFICATION_ID = 5001

        private const val ARP_INTERVAL = 10_000L
        private const val PORT_SCAN_INTERVAL = 5_000L
        private const val CONNECTION_INTERVAL = 10_000L

        private const val ARP_INTERVAL_LIMITED = 15_000L
        private const val PORT_SCAN_INTERVAL_LIMITED = 10_000L

        const val ACTION_START = "net_protection_start"
        const val ACTION_STOP = "net_protection_stop"
        const val ACTION_UPDATE_CONFIG = "net_protection_update_config"

        const val EXTRA_ARP_ENABLED = "arp_enabled"
        const val EXTRA_PORT_SCAN_ENABLED = "port_scan_enabled"
        const val EXTRA_CONNECTION_MONITOR_ENABLED = "connection_monitor_enabled"
        const val EXTRA_NOTIFICATION_HIDDEN = "notification_hidden"
        const val EXTRA_NOTIFICATION_PRIORITY = "notification_priority"

        const val BROADCAST_STATUS = "net_protection_status"
        const val BROADCAST_EVENT = "net_protection_event"

        const val EXTRA_STATUS_RUNNING = "status_running"
        const val EXTRA_EVENT_TEXT = "event_text"
        const val EXTRA_EVENT_TYPE = "event_type"
        const val EXTRA_ARP_METHOD = "arp_method"
        const val EXTRA_PORT_SCAN_METHOD = "port_scan_method"
        const val EXTRA_PORT_SCAN_SOURCE = "port_scan_source"
        const val EXTRA_CONNECTION_METHOD = "connection_method"
        const val EXTRA_DETECTION_RESULT = "detection_result"

        fun start(context: Context) {
            val intent = Intent(context, NetProtectionService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, NetProtectionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateConfig(
            context: Context,
            arpEnabled: Boolean,
            portScanEnabled: Boolean,
            connectionMonitorEnabled: Boolean,
            notificationHidden: Boolean,
            notificationPriority: Int
        ) {
            val intent = Intent(context, NetProtectionService::class.java).apply {
                action = ACTION_UPDATE_CONFIG
                putExtra(EXTRA_ARP_ENABLED, arpEnabled)
                putExtra(EXTRA_PORT_SCAN_ENABLED, portScanEnabled)
                putExtra(EXTRA_CONNECTION_MONITOR_ENABLED, connectionMonitorEnabled)
                putExtra(EXTRA_NOTIFICATION_HIDDEN, notificationHidden)
                putExtra(EXTRA_NOTIFICATION_PRIORITY, notificationPriority)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_STOP -> handleStop()
            ACTION_UPDATE_CONFIG -> handleUpdateConfig(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        if (isRunning) return
        isRunning = true

        detectionResult = CapabilityDetector.detect(this)
        resolveGateway()

        val notification = buildNotification(
            getString(R.string.np_svc_title),
            getString(R.string.np_svc_running)
        )
        startForeground(NOTIFICATION_ID, notification)

        connectionMonitor.startMonitoring(this) { count ->
            broadcastStatus()
        }

        val isFull =
            detectionResult?.overallLevel == com.lsd.wififrankenstein.ui.netprotection.DetectorCapability.FULL
        arpIntervalMs = if (isFull) ARP_INTERVAL else ARP_INTERVAL_LIMITED
        portScanIntervalMs = if (isFull) PORT_SCAN_INTERVAL else PORT_SCAN_INTERVAL_LIMITED

        if (arpEnabled) handler.postDelayed(arpRunnable, 1000)
        if (portScanEnabled) handler.postDelayed(portScanRunnable, 2000)
        if (connectionMonitorEnabled) handler.postDelayed(connectionRunnable, 1500)

        addEvent(EventType.INFO, getString(R.string.np_event_info))
        broadcastStatus()
    }

    private fun handleStop() {
        isRunning = false
        handler.removeCallbacks(arpRunnable)
        handler.removeCallbacks(portScanRunnable)
        handler.removeCallbacks(connectionRunnable)
        connectionMonitor.stopMonitoring(this)
        stopForegroundCompat()
        stopSelf()
        broadcastStatus()
    }

    private fun handleUpdateConfig(intent: Intent) {
        arpEnabled = intent.getBooleanExtra(EXTRA_ARP_ENABLED, true)
        portScanEnabled = intent.getBooleanExtra(EXTRA_PORT_SCAN_ENABLED, true)
        connectionMonitorEnabled = intent.getBooleanExtra(EXTRA_CONNECTION_MONITOR_ENABLED, true)
        notificationHidden = intent.getBooleanExtra(EXTRA_NOTIFICATION_HIDDEN, false)
        notificationPriority =
            intent.getIntExtra(EXTRA_NOTIFICATION_PRIORITY, NotificationManager.IMPORTANCE_LOW)

        updateNotificationChannel(notificationPriority)

        if (!isRunning) return

        handler.removeCallbacks(arpRunnable)
        handler.removeCallbacks(portScanRunnable)
        handler.removeCallbacks(connectionRunnable)

        if (arpEnabled) handler.post(arpRunnable)
        if (portScanEnabled) handler.post(portScanRunnable)
        if (connectionMonitorEnabled) handler.post(connectionRunnable)

        updateNotification(getString(R.string.np_svc_title), getString(R.string.np_svc_running))
        broadcastStatus()
    }

    @Suppress("DEPRECATION")
    private fun resolveGateway() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork ?: return
                val lp = cm.getLinkProperties(network) ?: return
                for (route in lp.routes) {
                    if (route.isDefaultRoute && route.hasGateway()) {
                        gatewayIp = route.gateway?.hostAddress ?: ""
                        if (gatewayIp.isNotEmpty()) {
                            Log.d(TAG, "Gateway resolved via ConnectivityManager: $gatewayIp")
                            return
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ConnectivityManager gateway failed: ${e.message}")
            }
        }
        resolveGatewayFromProc()
    }

    private fun resolveGatewayFromProc() {
        try {
            val reader = java.io.BufferedReader(java.io.FileReader("/proc/net/route"))
            reader.use { r ->
                r.readLine()
                for (line in r.readLines()) {
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 4 && parts[1] == "00000000") {
                        val gwHex = parts[2].padStart(8, '0')
                        val bytes = gwHex.chunked(2).map { it.toInt(16) }
                        if (bytes.size == 4) {
                            gatewayIp = "${bytes[3]}.${bytes[2]}.${bytes[1]}.${bytes[0]}"
                            Log.d(TAG, "Gateway resolved via /proc/net/route: $gatewayIp")
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/net/route: ${e.message}")
        }
    }

    private fun checkArp() {
        if (gatewayIp.isEmpty()) {
            resolveGateway()
            if (gatewayIp.isEmpty()) {
                Log.w(TAG, "ARP check: no gateway resolved")
                return
            }
        }

        Log.d(TAG, "ARP check: gateway=$gatewayIp")
        val result = arpDetector.checkForSpoof(gatewayIp)
        if (result.changed) {
            val msg = getString(R.string.np_event_arp_changed, result.oldMac, result.newMac)
            Log.w(TAG, "ARP SPOOF ALERT: $msg")
            addEvent(EventType.ARP_SPOOF, msg)
            updateNotification(getString(R.string.np_svc_title_alert), msg)
            broadcastEvent(msg, EventType.ARP_SPOOF)
        }
    }

    private fun checkPortScan() {
        Log.d(TAG, "Port scan check starting")
        val result = portScanDetector.checkPortScan(this)
        if (result.detected) {
            val msg = getString(R.string.np_event_port_scan, result.uniqueIps.size)
            Log.w(TAG, "PORT SCAN ALERT: $msg")
            addEvent(EventType.PORT_SCAN, msg)
            updateNotification(getString(R.string.np_svc_title_alert), msg)
            broadcastEvent(msg, EventType.PORT_SCAN)
        }
    }

    private fun checkConnections() {
        val result = connectionMonitor.checkConnectionSpike(this)
        if (result.spikeDetected) {
            val msg = getString(R.string.np_event_connection_spike, result.currentConnections)
            Log.w(TAG, "CONNECTION SPIKE ALERT: $msg")
            addEvent(EventType.CONNECTION_SPIKE, msg)
            updateNotification(getString(R.string.np_svc_title_alert), msg)
            broadcastEvent(msg, EventType.CONNECTION_SPIKE)
        }
    }

    private fun addEvent(type: EventType, message: String) {
        val event = NetProtectionEvent(System.currentTimeMillis(), type, message)
        events.add(0, event)
        if (events.size > 50) events.removeAt(events.size - 1)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        connectionMonitor.stopMonitoring(this)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.np_svc_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotificationChannel(importance: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.np_svc_channel),
                importance
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_net_protection)
            .setContentIntent(pendingIntent)
            .setOngoing(!notificationHidden)
            .setSilent(true)
            .setPriority(
                when (notificationPriority) {
                    NotificationManager.IMPORTANCE_MIN -> NotificationCompat.PRIORITY_MIN
                    NotificationManager.IMPORTANCE_LOW -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                }
            )
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun broadcastStatus() {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS_RUNNING, isRunning)
            putExtra(EXTRA_PORT_SCAN_SOURCE, portScanDetector.getDataSource().name)
            detectionResult?.let { result ->
                putExtra(EXTRA_ARP_METHOD, result.arpCapability.name)
                putExtra(EXTRA_PORT_SCAN_METHOD, result.portScanCapability.name)
                putExtra(EXTRA_CONNECTION_METHOD, result.connectionMonitorCapability.name)
            }
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastEvent(text: String, type: EventType) {
        val intent = Intent(BROADCAST_EVENT).apply {
            putExtra(EXTRA_EVENT_TEXT, text)
            putExtra(EXTRA_EVENT_TYPE, type.name)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun getEvents(): List<NetProtectionEvent> = events.toList()
}
