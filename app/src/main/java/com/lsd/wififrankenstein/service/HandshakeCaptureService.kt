package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.airodump.CaptureLocationProvider
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeItem
import com.lsd.wififrankenstein.ui.handshakecapture.HandshakeStorageManager
import com.lsd.wififrankenstein.ui.iwwifi.IwWifiManager
import com.lsd.wififrankenstein.util.CaptureFormat
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.HandshakeCaptureRunner
import com.lsd.wififrankenstein.util.HandshakeHash
import com.lsd.wififrankenstein.util.HandshakeType
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class HandshakeCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var captureJob: Job? = null
    private var deauthLoopJob: Job? = null
    private var pollingJob: Job? = null
    private val started = AtomicBoolean(false)

    private lateinit var captureRunner: HandshakeCaptureRunner
    private lateinit var storageManager: HandshakeStorageManager
    private lateinit var captureLocationProvider: CaptureLocationProvider
    private lateinit var iwWifiManager: IwWifiManager
    private lateinit var chrootManager: ChrootManager

    private var latestStats: com.lsd.wififrankenstein.util.CaptureStats? = null

    @Volatile
    private var handshakeDetectedAtMs: Long? = null

    @Volatile
    private var pmkidDetected = false

    @Volatile
    private var handshakeConfirmed = false

    @Volatile
    private var activeCapture: ActiveCapture? = null
    private val consoleHistory = mutableListOf<String>()
    private val consoleLock = Any()

    data class ActiveCapture(
        val iface: String,
        val deauthIface: String,
        val bssid: String,
        val ssid: String,
        val channel: String,
        val outputDir: String,
        val startedAtMs: Long
    )

    companion object {
        private const val TAG = "HandshakeCaptureService"
        private const val CHANNEL_ID = "handshake_capture_channel"
        private const val NOTIFICATION_ID = 4101
        private const val CAPTURE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val AUTO_SAVE_DELAY_MS = 10_000L
        private const val DEAUTH_INTERVAL_MS = 20_000L
        private const val DEAUTH_START_DELAY_MS = 10_000L
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLL_ATTEMPTS = 200

        const val ACTION_START = "start_capture"
        const val ACTION_STOP = "stop_capture"

        const val EXTRA_IFACE = "iface"
        const val EXTRA_DEAUTH_IFACE = "deauth_iface"
        const val EXTRA_BSSID = "bssid"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_ESSID = "essid"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_AUTO_DEAUTH_CLIENTS = "auto_deauth_clients"
        const val EXTRA_AUTO_DEAUTH_BROADCAST = "auto_deauth_broadcast"
        const val EXTRA_DEAUTH_COUNT = "deauth_count"
        const val EXTRA_KEEP_HOST_WIFI = "keep_host_wifi"
        const val EXTRA_EXCLUDE_SELF = "exclude_self"
        const val EXTRA_DEVICE_MAC = "device_mac"

        const val BROADCAST_CAPTURE_LINE = "handshake_capture_line"
        const val BROADCAST_CAPTURE_EVENT = "handshake_capture_event"
        const val BROADCAST_CAPTURE_COMPLETE = "handshake_capture_complete"
        const val BROADCAST_CAPTURE_ERROR = "handshake_capture_error"

        const val EXTRA_LINE = "line"
        const val EXTRA_EVENT = "event"
        const val EXTRA_SAVED_PATH = "saved_path"
        const val EXTRA_VALID = "valid"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        @Volatile
        private var serviceInstance: HandshakeCaptureService? = null

        private const val MAX_CONSOLE_LINES = 2000

        fun isActive(): Boolean = serviceInstance?.activeCapture != null

        fun getActive(): ActiveCapture? = serviceInstance?.activeCapture

        fun getConsoleHistory(): List<String> =
            serviceInstance?.snapshotConsoleHistory() ?: emptyList()

        fun getLatestStats(): com.lsd.wififrankenstein.util.CaptureStats? =
            serviceInstance?.latestStats

        fun start(
            context: Context,
            iface: String,
            deauthIface: String,
            bssid: String,
            channel: String,
            essid: String,
            format: CaptureFormat = CaptureFormat.DEFAULT,
            autoDeauthClients: Boolean = true,
            autoDeauthBroadcast: Boolean = true,
            deauthCount: Int = 5,
            keepHostWifi: Boolean = true,
            excludeSelf: Boolean = false,
            deviceMac: String? = null
        ) {
            val intent = Intent(context, HandshakeCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IFACE, iface)
                putExtra(EXTRA_DEAUTH_IFACE, deauthIface)
                putExtra(EXTRA_BSSID, bssid)
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_ESSID, essid)
                putExtra(EXTRA_FORMAT, format.name)
                putExtra(EXTRA_AUTO_DEAUTH_CLIENTS, autoDeauthClients)
                putExtra(EXTRA_AUTO_DEAUTH_BROADCAST, autoDeauthBroadcast)
                putExtra(EXTRA_DEAUTH_COUNT, deauthCount.coerceAtLeast(1))
                putExtra(EXTRA_KEEP_HOST_WIFI, keepHostWifi)
                putExtra(EXTRA_EXCLUDE_SELF, excludeSelf)
                putExtra(EXTRA_DEVICE_MAC, deviceMac)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, HandshakeCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        serviceInstance = this
        captureRunner = HandshakeCaptureRunner(this)
        storageManager = HandshakeStorageManager(this)
        captureLocationProvider = CaptureLocationProvider(this)
        iwWifiManager = IwWifiManager(this)
        chrootManager = ChrootManager.get(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(intent: Intent) {
        if (!started.compareAndSet(false, true)) {
            Log.w(TAG, "Capture already running in service")
            return
        }

        val iface = intent.getStringExtra(EXTRA_IFACE) ?: run { markStopped(); return }
        intentIface = iface
        val deauthIface = intent.getStringExtra(EXTRA_DEAUTH_IFACE) ?: iface
        val bssid = intent.getStringExtra(EXTRA_BSSID) ?: run { markStopped(); return }
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: "1"
        val essid = intent.getStringExtra(EXTRA_ESSID) ?: ""
        val format = try {
            CaptureFormat.valueOf(intent.getStringExtra(EXTRA_FORMAT) ?: "")
        } catch (e: Exception) {
            CaptureFormat.DEFAULT
        }
        val autoDeauthClients = intent.getBooleanExtra(EXTRA_AUTO_DEAUTH_CLIENTS, true)
        val autoDeauthBroadcast = intent.getBooleanExtra(EXTRA_AUTO_DEAUTH_BROADCAST, true)
        val deauthCount = intent.getIntExtra(EXTRA_DEAUTH_COUNT, 5)
        val keepHostWifi = intent.getBooleanExtra(EXTRA_KEEP_HOST_WIFI, true)
        val excludeSelf = intent.getBooleanExtra(EXTRA_EXCLUDE_SELF, false)
        val deviceMac = intent.getStringExtra(EXTRA_DEVICE_MAC)

        val notification = buildNotification("Starting capture", "$essid ($bssid)").build()
        startForeground(NOTIFICATION_ID, notification)

        captureLocationProvider.start()

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedBssid = bssid.replace(":", "")
        val outputDir = "${HandshakeCaptureRunner.OUTPUT_BASE}/${sanitizedBssid}_$timestamp"

        activeCapture = ActiveCapture(
            iface = iface,
            deauthIface = deauthIface,
            bssid = bssid,
            ssid = essid,
            channel = channel,
            outputDir = outputDir,
            startedAtMs = System.currentTimeMillis()
        )

        addLine("[*] Background capture started: $bssid ch=$channel")
        addLine("[*] Output: $outputDir")

        captureJob = serviceScope.launch {
            try {
                setupMonitorMode(iface, deauthIface, channel)
                runCaptureAndPoll(
                    iface = iface,
                    deauthIface = deauthIface,
                    bssid = bssid,
                    channel = channel,
                    essid = essid,
                    format = format,
                    outputDir = outputDir,
                    autoDeauthClients = autoDeauthClients,
                    autoDeauthBroadcast = autoDeauthBroadcast,
                    deauthCount = deauthCount,
                    excludeSelf = excludeSelf,
                    deviceMac = deviceMac
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Background capture failed", e)
                broadcastError(e.message ?: "Capture error")
                updateNotification("Error", e.message ?: "Capture error")
            } finally {
                stopCapture()
            }
        }
    }

    private suspend fun setupMonitorMode(
        iface: String,
        deauthIface: String,
        channel: String
    ) {
        val currentMode = iwWifiManager.getInterfaceMode(iface)
        val monExists = iwWifiManager.findMonitorInterface(iface) != null
        if (currentMode != IwWifiManager.MODE_MONITOR && !monExists) {
            addLine("[*] Switching $iface to monitor mode (channel $channel)...")
            val switched =
                iwWifiManager.setInterfaceMode(iface, IwWifiManager.MODE_MONITOR, channel)
            if (switched) {
                delay(1000)
                addLine("[+] $iface switched to monitor mode")
            } else {
                addLine("[-] Failed to switch $iface to monitor mode: ${iwWifiManager.lastModeSwitchError ?: "unknown"}")
            }
        }

        if (deauthIface != iface && (deauthIface.isNotEmpty())) {
            val deauthMode = iwWifiManager.getInterfaceMode(deauthIface)
            val deauthMonExists = iwWifiManager.findMonitorInterface(deauthIface) != null
            if (deauthMode != IwWifiManager.MODE_MONITOR && !deauthMonExists) {
                iwWifiManager.setInterfaceMode(deauthIface, IwWifiManager.MODE_MONITOR, channel)
                delay(1000)
            }
        }
    }

    private suspend fun runCaptureAndPoll(
        iface: String,
        deauthIface: String,
        bssid: String,
        channel: String,
        essid: String,
        format: CaptureFormat,
        outputDir: String,
        autoDeauthClients: Boolean,
        autoDeauthBroadcast: Boolean,
        deauthCount: Int,
        excludeSelf: Boolean,
        deviceMac: String?
    ) {
        captureRunner.startCaptureAsync(
            iface = iface,
            bssid = bssid,
            channel = channel,
            outputDir = outputDir,
            outputFormat = format,
            onProgress = { line ->
                addLine(line)
                updateNotification("Capture", line)
            },
            onStats = { stats ->
                latestStats = stats
                if (stats.pmkidFound) pmkidDetected = true
            },
            onEvent = { event ->
                when (event) {
                    "HANDSHAKE" -> {
                        addLine("[+] Handshake detected by airodump")
                        updateNotification("Capture", "Handshake detected!")
                    }

                    "PMKID" -> {
                        pmkidDetected = true
                        addLine("[+] PMKID detected by airodump")
                    }
                }
                broadcastEvent(event)
            }
        )

        if (autoDeauthClients || autoDeauthBroadcast) {
            deauthLoopJob = serviceScope.launch(Dispatchers.IO) {
                try {
                    delay(DEAUTH_START_DELAY_MS)
                    var deauthAttempt = 0
                    while (isActive && captureRunner.isAirodumpRunning() && handshakeDetectedAtMs == null) {
                        val clients = latestStats?.clients?.toList() ?: emptyList()

                        if (autoDeauthClients) {
                            for (client in clients) {
                                if (!isActive || !captureRunner.isAirodumpRunning()) break
                                if (excludeSelf && deviceMac != null &&
                                    client.mac.equals(deviceMac, ignoreCase = true)
                                ) continue
                                captureRunner.sendDeauth(
                                    iface = deauthIface,
                                    bssid = bssid,
                                    clientMac = client.mac,
                                    count = deauthCount,
                                    channel = channel
                                )
                            }
                        }

                        if (autoDeauthBroadcast && isActive && captureRunner.isAirodumpRunning()) {
                            if (clients.isNotEmpty() || deauthAttempt == 0) {
                                captureRunner.sendDeauth(
                                    iface = deauthIface,
                                    bssid = bssid,
                                    clientMac = null,
                                    count = deauthCount,
                                    channel = channel
                                )
                            }
                        }
                        deauthAttempt++
                        delay(DEAUTH_INTERVAL_MS)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Deauth loop error: ${e.message}")
                }
            }
        }

        pollingJob = serviceScope.launch(Dispatchers.IO) {
            var cappedFile: String? = null
            var attempts = 0
            val startMs = System.currentTimeMillis()
            var timeoutFired = false

            while (isActive && attempts < MAX_POLL_ATTEMPTS) {
                if (!captureRunner.isAirodumpRunning()) {
                    addLine("[!] Airodump process ended")
                    break
                }

                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed >= CAPTURE_TIMEOUT_MS && !timeoutFired) {
                    timeoutFired = true
                    addLine("[!] Capture timeout (5 min)")
                    cappedFile = captureRunner.findCapFile(outputDir)
                    autoSaveAndFinish(cappedFile, essid, bssid)
                    break
                }

                cappedFile = captureRunner.findCapFile(outputDir)

                if (cappedFile != null && (handshakeConfirmed || pmkidDetected ||
                            latestStats?.handshakeFound == true)
                ) {
                    if (handshakeDetectedAtMs == null) {
                        addLine("[+] Handshake detected. Verifying...")
                        val verified = try {
                            captureRunner.verifyHandshakeWithHcxpcapngtool(cappedFile)
                        } catch (e: Exception) {
                            Log.e(TAG, "Verify failed", e)
                            false
                        }
                        if (verified) {
                            handshakeConfirmed = true
                            handshakeDetectedAtMs = System.currentTimeMillis()
                            addLine("[+] Handshake confirmed. Auto-saving in ${AUTO_SAVE_DELAY_MS / 1000}s...")
                            updateNotification("Capture", "Handshake confirmed!")
                        }
                    } else if (System.currentTimeMillis() - handshakeDetectedAtMs!! >= AUTO_SAVE_DELAY_MS) {
                        addLine("[+] Auto-saving now")
                        autoSaveAndFinish(cappedFile, essid, bssid)
                        break
                    }
                }

                attempts++
                delay(POLL_INTERVAL_MS)
            }
        }

        pollingJob?.join()
    }

    private suspend fun autoSaveAndFinish(capFile: String?, essid: String, bssid: String) {
        if (capFile == null) {
            addLine("[-] No capture file found — nothing to save")
            return
        }

        var verifyValid = handshakeConfirmed
        var verifyPmkid = pmkidDetected
        if (!verifyValid && !verifyPmkid) {
            verifyValid = try {
                captureRunner.verifyHandshakeWithHcxpcapngtool(capFile)
            } catch (e: Exception) {
                false
            }
        }

        val shouldSave = verifyValid || verifyPmkid
        if (!shouldSave) {
            addLine("[-] No valid handshake data captured")
            return
        }

        addLine("[*] Stopping capture before saving...")
        captureRunner.stopCapture()
        delay(300)

        val saved = storageManager.moveToStorage(capFile, essid, bssid)
        val finalPath = saved ?: capFile
        if (saved != null) {
            addLine("[+] Saved to storage: $saved")
            try {
                chrootManager.executeInChroot("rm -rf '${capFile.substringBeforeLast('/')}' 2>/dev/null; true")
            } catch (_: Exception) {
            }
        } else {
            addLine("[-] Save failed, file remains at: $capFile")
        }

        broadcastComplete(finalPath, essid, bssid, verifyValid)

        try {
            val fileName = File(finalPath).name
            val statResult = chrootManager.executeInChroot("stat -c '%s' '$finalPath' 2>/dev/null")
            val fileSize = statResult.out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
            val raw = captureRunner.getHcxpcapngtoolOutput(finalPath)
            val hashLines = raw.lines()
            val allHashes = hashLines.mapNotNull { HandshakeHash.parse22000Line(it.trim()) }
            val hash22000 = allHashes.map { it.to22000Line() }.distinct().joinToString("\n")
                .takeIf { it.isNotBlank() }
            val hashPmkid = allHashes.firstOrNull { it.type == HandshakeType.PMKID }
                ?.pmkidOrMic?.takeIf { it.length == 32 }
            val eapolCount = allHashes.count { it.type == HandshakeType.EAPOL }
            val pmkidCount = allHashes.count { it.type == HandshakeType.PMKID }
            val loc = captureLocationProvider.getLastKnownLocation()
            storageManager.saveHandshakeMetadata(
                HandshakeItem(
                    filePath = finalPath,
                    fileName = fileName,
                    bssid = bssid,
                    essid = essid,
                    fileSize = fileSize,
                    lastModified = System.currentTimeMillis(),
                    hash22000 = hash22000,
                    hashPmkid = hashPmkid,
                    isValid = if (verifyValid) true else null,
                    latitude = loc?.latitude,
                    longitude = loc?.longitude,
                    handshakeCount = allHashes.size,
                    eapolCount = eapolCount,
                    pmkidCount = pmkidCount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Metadata save failed", e)
        }
    }

    private fun stopCapture() {
        Log.d(TAG, "stopCapture")
        deauthLoopJob?.cancel()
        deauthLoopJob = null
        pollingJob?.cancel()
        pollingJob = null
        captureJob?.cancel()
        captureJob = null
        try {
            if (::captureRunner.isInitialized) captureRunner.stopCapture()
        } catch (_: Exception) {
        }
        try {
            if (::captureLocationProvider.isInitialized) captureLocationProvider.stop()
        } catch (_: Exception) {
        }
        try {
            if (::iwWifiManager.isInitialized && ::captureRunner.isInitialized) {
                val iface = intentIface
                if (iface != null) {
                    serviceScope.launch {
                        captureRunner.disableMonitor(iface)
                    }
                }
            }
        } catch (_: Exception) {
        }
        markStopped()
        stopForegroundCompat()
        stopSelf()
    }

    private var intentIface: String? = null

    private fun markStopped() {
        started.set(false)
        activeCapture = null
    }

    private fun snapshotConsoleHistory(): List<String> =
        synchronized(consoleLock) { consoleHistory.toList() }

    override fun onDestroy() {
        deauthLoopJob?.cancel()
        pollingJob?.cancel()
        captureJob?.cancel()
        serviceScope.cancel()
        serviceInstance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.handshake_capture_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): NotificationCompat.Builder {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_airodump", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HandshakeCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.handshake_capture_stop),
                stopIntent
            )
    }

    private fun updateNotification(title: String, content: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title, content).build())
    }

    private fun addLine(line: String) {
        Log.d(TAG, line)
        synchronized(consoleLock) {
            consoleHistory.add(line)
            if (consoleHistory.size > MAX_CONSOLE_LINES) {
                consoleHistory.removeAt(0)
            }
        }
        val intent = Intent(BROADCAST_CAPTURE_LINE).apply {
            putExtra(EXTRA_LINE, line)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastEvent(event: String) {
        val intent = Intent(BROADCAST_CAPTURE_EVENT).apply {
            putExtra(EXTRA_EVENT, event)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastComplete(savedPath: String, essid: String, bssid: String, valid: Boolean) {
        val intent = Intent(BROADCAST_CAPTURE_COMPLETE).apply {
            putExtra(EXTRA_SAVED_PATH, savedPath)
            putExtra(EXTRA_ESSID, essid)
            putExtra(EXTRA_BSSID, bssid)
            putExtra(EXTRA_VALID, valid)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        updateNotification("Capture", if (valid) "Saved: $savedPath" else "Not saved")
    }

    private fun broadcastError(message: String) {
        val intent = Intent(BROADCAST_CAPTURE_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
