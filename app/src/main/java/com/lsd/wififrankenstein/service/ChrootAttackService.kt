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
import com.lsd.wififrankenstein.data.RouterScanResult
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.NativePixieDustRunner
import com.lsd.wififrankenstein.util.PixieDustResult
import com.lsd.wififrankenstein.util.PixieDustRunner
import com.lsd.wififrankenstein.util.RouterScanConfig
import com.lsd.wififrankenstein.util.RouterScanRunner
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

enum class ChrootAttackType {
    PIXIE_DUST,
    ROUTER_SCAN
}

class ChrootAttackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var attackJob: Job? = null
    private var runner: Any? = null
    private var lastNotificationUpdate = 0L

    companion object {
        private const val TAG = "ChrootAttackSvc"
        private const val CHANNEL_ID = "chroot_attack_channel"
        private const val NOTIFICATION_ID = 6001

        const val ACTION_START = "chroot_attack_start"
        const val ACTION_CANCEL = "chroot_attack_cancel"

        const val EXTRA_ATTACK_TYPE = "attack_type"
        const val EXTRA_BSSID = "bssid"
        const val EXTRA_SSID = "ssid"
        const val EXTRA_INTERFACE = "interface"
        const val EXTRA_PIN = "pin"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_FREQ = "freq"
        const val EXTRA_OUTPUT_DIR = "output_dir"
        const val EXTRA_CAPTURE_FORMAT = "capture_format"
        const val EXTRA_DISABLE_WIFI = "disable_wifi"
        const val EXTRA_USE_NATIVE = "use_native_pixie"
        const val EXTRA_IPS = "ips"
        const val EXTRA_PORTS = "ports"
        const val EXTRA_MAX_THREADS = "max_threads"
        const val EXTRA_RS_TIMEOUT = "rs_timeout"
        const val EXTRA_PING_BEFORE_SCAN = "ping_before_scan"

        const val BROADCAST_PROGRESS = "chroot_attack_progress"
        const val BROADCAST_ROUTER_RESULT = "chroot_attack_router_result"
        const val BROADCAST_COMPLETE = "chroot_attack_complete"
        const val BROADCAST_ERROR = "chroot_attack_error"

        const val EXTRA_PROGRESS_TEXT = "progress_text"
        const val EXTRA_PROGRESS_PERCENT = "progress_percent"
        const val EXTRA_RESULT_PIN = "result_pin"
        const val EXTRA_RESULT_PSK = "result_psk"
        const val EXTRA_RESULT_SUCCESS = "result_success"
        const val EXTRA_RESULT_RAW = "result_raw"
        const val EXTRA_RESULT_REASON = "result_reason"
        const val EXTRA_RESULT_IP = "result_ip"
        const val EXTRA_RESULT_PORT = "result_port"
        const val EXTRA_RESULT_SSID = "result_ssid"
        const val EXTRA_RESULT_BSSID = "result_bssid"
        const val EXTRA_RESULT_AUTH = "result_auth"
        const val EXTRA_RESULT_SEC = "result_sec"
        const val EXTRA_RESULT_WPS = "result_wps"
        const val EXTRA_RESULT_TITLE = "result_title"
        const val EXTRA_RESULT_SERVER_TYPE = "result_server_type"
        const val EXTRA_RESULT_LAN_IP = "result_lan_ip"
        const val EXTRA_RESULT_LAN_MASK = "result_lan_mask"
        const val EXTRA_RESULT_WAN_IP = "result_wan_ip"
        const val EXTRA_RESULT_WAN_MASK = "result_wan_mask"
        const val EXTRA_RESULT_WAN_GATE = "result_wan_gate"
        const val EXTRA_RESULT_DNS = "result_dns"
        const val EXTRA_RESULT_STATUS = "result_status"
        const val EXTRA_RESULT_TYPE = "result_type"
        const val EXTRA_RESULT_LAT = "result_lat"
        const val EXTRA_RESULT_LON = "result_lon"
        const val EXTRA_RESULT_SCANNED = "result_scanned"
        const val EXTRA_RESULT_FULL_OUTPUT = "result_full_output"

        const val EXTRA_ERROR_MESSAGE = "error_message"

        fun startPixieDust(
            context: Context,
            bssid: String,
            iface: String = "wlan0",
            pin: String? = null,
            disableWifi: Boolean = true,
            useNative: Boolean = false,
            ssid: String? = null,
            freq: Int? = null
        ) {
            val intent = Intent(context, ChrootAttackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.PIXIE_DUST.name)
                putExtra(EXTRA_BSSID, bssid)
                putExtra(EXTRA_INTERFACE, iface)
                putExtra(EXTRA_DISABLE_WIFI, disableWifi)
                putExtra(EXTRA_USE_NATIVE, useNative)
                pin?.let { putExtra(EXTRA_PIN, it) }
                ssid?.let { putExtra(EXTRA_SSID, it) }
                freq?.let { putExtra(EXTRA_FREQ, it) }
            }
            context.startService(intent)
        }

        fun startRouterScan(
            context: Context,
            ips: ArrayList<String>,
            ports: ArrayList<String>,
            maxThreads: Int = 10,
            rsTimeout: Long = 120_000,
            pingBeforeScan: Boolean = false
        ) {
            val intent = Intent(context, ChrootAttackService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.ROUTER_SCAN.name)
                putStringArrayListExtra(EXTRA_IPS, ips)
                putStringArrayListExtra(EXTRA_PORTS, ports)
                putExtra(EXTRA_MAX_THREADS, maxThreads)
                putExtra(EXTRA_RS_TIMEOUT, rsTimeout)
                putExtra(EXTRA_PING_BEFORE_SCAN, pingBeforeScan)
            }
            context.startService(intent)
        }

        fun cancelAttack(context: Context) {
            val intent = Intent(context, ChrootAttackService::class.java).apply {
                action = ACTION_CANCEL
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
            ACTION_START -> handleStartAttack(intent)
            ACTION_CANCEL -> handleCancelAttack()
        }
        return START_NOT_STICKY
    }

    private fun handleStartAttack(intent: Intent) {
        val typeStr = intent.getStringExtra(EXTRA_ATTACK_TYPE) ?: return
        val type = try {
            ChrootAttackType.valueOf(typeStr)
        } catch (e: Exception) {
            return
        }

        val label = getTypeLabel(type)
        val notification = buildNotification(label, getString(R.string.svc_starting)).build()
        startForeground(NOTIFICATION_ID, notification)

        attackJob = serviceScope.launch {
            try {
                when (type) {
                    ChrootAttackType.PIXIE_DUST -> runPixieDust(intent)
                    ChrootAttackType.ROUTER_SCAN -> runRouterScan(intent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Attack failed", e)
                broadcastError(e.message ?: getString(R.string.svc_unknown_error))
                updateNotification(getTypeLabel(type), getString(R.string.svc_failed, e.message))
            } finally {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun runPixieDust(intent: Intent) {
        val bssid = intent.getStringExtra(EXTRA_BSSID) ?: return
        val iface = intent.getStringExtra(EXTRA_INTERFACE) ?: "wlan0"
        val pin = intent.getStringExtra(EXTRA_PIN)
        val disableWifi = intent.getBooleanExtra(EXTRA_DISABLE_WIFI, true)
        val useNative = intent.getBooleanExtra(EXTRA_USE_NATIVE, false)
        val ssid = intent.getStringExtra(EXTRA_SSID)
        val freq = intent.getIntExtra(EXTRA_FREQ, 0).takeIf { it > 0 }
        lastNotificationUpdate = 0L

        val result: PixieDustResult = if (useNative) {
            val nativeRunner = NativePixieDustRunner(this)
            runner = nativeRunner
            updateNotification(getString(R.string.svc_pixie_title), getString(R.string.svc_attacking_native, bssid))
            nativeRunner.runAttack(
                bssid = bssid,
                interfaceName = iface,
                ssid = ssid,
                freqMHz = freq,
                disableWifiBeforeAttack = disableWifi,
                onProgress = { line ->
                    broadcastProgress(line)
                    maybeUpdateNotification(getString(R.string.svc_pixie_title), line.take(120))
                    if (line.contains("WPS PIN:", ignoreCase = true)) {
                        broadcastProgressPercent(1f)
                    }
                }
            )
        } else {
            val pixieRunner = PixieDustRunner(this)
            runner = pixieRunner

            updateNotification(getString(R.string.svc_pixie_title), getString(R.string.svc_attacking, bssid))

            pixieRunner.runAttack(
                bssid = bssid,
                interfaceName = iface,
                disableWifiBeforeAttack = disableWifi,
                pin = pin,
                onProgress = { line ->
                    broadcastProgress(line)
                    maybeUpdateNotification(getString(R.string.svc_pixie_title), line.take(120))
                    if (line.contains("WPS PIN:", ignoreCase = true) ||
                        line.contains("WPA PSK:", ignoreCase = true)
                    ) {
                        broadcastProgressPercent(1f)
                    }
                }
            )
        }

        val successIntent = Intent(BROADCAST_COMPLETE).apply {
            putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.PIXIE_DUST.name)
            putExtra(EXTRA_RESULT_PIN, result.wpsPin)
            putExtra(EXTRA_RESULT_PSK, result.wpaPsk)
            putExtra(EXTRA_RESULT_SUCCESS, result.success)
            putExtra(EXTRA_RESULT_RAW, result.rawOutput)
            putExtra(EXTRA_RESULT_REASON, result.reason)
            putExtra(EXTRA_PROGRESS_TEXT, if (result.success) getString(R.string.svc_pin, result.wpsPin) else getString(R.string.svc_failed_short))
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(successIntent)

        updateNotification(
            getString(R.string.svc_pixie_title),
            if (result.success) getString(R.string.svc_pin, result.wpsPin) else getString(R.string.svc_not_found)
        )
    }

    private suspend fun runRouterScan(intent: Intent) {
        val ips = intent.getStringArrayListExtra(EXTRA_IPS) ?: return
        val ports = intent.getStringArrayListExtra(EXTRA_PORTS) ?: return
        val maxThreads = intent.getIntExtra(EXTRA_MAX_THREADS, 10)
        val rsTimeout = intent.getLongExtra(EXTRA_RS_TIMEOUT, 120_000L)
        val pingBeforeScan = intent.getBooleanExtra(EXTRA_PING_BEFORE_SCAN, false)

        val config = RouterScanConfig(
            maxThreads = maxThreads,
            timeout = 1000,
            rsTimeout = rsTimeout,
            pingBeforeScan = pingBeforeScan
        )

        val scanRunner = RouterScanRunner(this)
        runner = scanRunner

        val ipPortCombinations = ips.flatMap { ip ->
            ports.map { port -> Pair(ip, port) }
        }
        val total = ipPortCombinations.size
        updateNotification(getString(R.string.svc_router_scan_title), getString(R.string.svc_scanning_targets, total))

        val allResults = mutableListOf<RouterScanResult>()
        var completedCount = 0

        val lbm = LocalBroadcastManager.getInstance(this)

        scanRunner.scanMultipleRouters(
            ipPortCombinations = ipPortCombinations,
            config = config,
            onProgress = { line ->
                maybeUpdateNotification(getString(R.string.svc_router_scan_title), line.take(120))
                val progressIntent = Intent(BROADCAST_PROGRESS).apply {
                    putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.ROUTER_SCAN.name)
                    putExtra(EXTRA_PROGRESS_TEXT, line)
                }
                lbm.sendBroadcast(progressIntent)
            },
            onResult = { result ->
                allResults.add(result)
                completedCount++
                val percent = if (total > 0) completedCount.toFloat() / total else 0f
                updateNotification(
                    getString(R.string.svc_router_scan_title),
                    getString(R.string.svc_scan_progress, completedCount, total, allResults.count { it.success })
                )
                broadcastProgressPercent(percent)

                val resultIntent = Intent(BROADCAST_ROUTER_RESULT).apply {
                    putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.ROUTER_SCAN.name)
                    putExtra(EXTRA_RESULT_IP, result.ip)
                    putExtra(EXTRA_RESULT_PORT, result.port)
                    putExtra(EXTRA_RESULT_SSID, result.ssid)
                    putExtra(EXTRA_RESULT_BSSID, result.bssid)
                    putExtra(EXTRA_RESULT_AUTH, result.auth)
                    putExtra(EXTRA_RESULT_SEC, result.sec)
                    putExtra(EXTRA_RESULT_PSK, result.psk)
                    putExtra(EXTRA_RESULT_WPS, result.wps)
                    putExtra(EXTRA_RESULT_TITLE, result.title)
                    putExtra(EXTRA_RESULT_SERVER_TYPE, result.serverType)
                    putExtra(EXTRA_RESULT_LAN_IP, result.lanIp)
                    putExtra(EXTRA_RESULT_LAN_MASK, result.lanMask)
                    putExtra(EXTRA_RESULT_WAN_IP, result.wanIp)
                    putExtra(EXTRA_RESULT_WAN_MASK, result.wanMask)
                    putExtra(EXTRA_RESULT_WAN_GATE, result.wanGate)
                    putExtra(EXTRA_RESULT_DNS, result.dns)
                    putExtra(EXTRA_RESULT_SUCCESS, result.success)
                    putExtra(EXTRA_RESULT_STATUS, result.status)
                    putExtra(EXTRA_RESULT_TYPE, result.type)
                    putExtra(EXTRA_RESULT_LAT, result.lat)
                    putExtra(EXTRA_RESULT_LON, result.lon)
                    putExtra(EXTRA_RESULT_SCANNED, result.scanned)
                    putExtra(EXTRA_RESULT_FULL_OUTPUT, result.fullOutput)
                }
                lbm.sendBroadcast(resultIntent)
            }
        )

        val successIntent = Intent(BROADCAST_COMPLETE).apply {
            putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.ROUTER_SCAN.name)
            putExtra(EXTRA_RESULT_SUCCESS, allResults.isNotEmpty())
            putExtra(
                EXTRA_PROGRESS_TEXT,
                getString(R.string.svc_scan_complete, allResults.count { it.success }, total)
            )
        }
        lbm.sendBroadcast(successIntent)

        updateNotification(
            getString(R.string.svc_router_scan_title),
            getString(R.string.svc_found_count, allResults.count { it.success }, total)
        )
    }

    private fun handleCancelAttack() {
        attackJob?.cancel()
        attackJob = null
        val activeRunner = runner
        runner = null
        if (activeRunner is PixieDustRunner) {
            serviceScope.launch {
                activeRunner.cancel()
            }
        } else if (activeRunner is NativePixieDustRunner) {
            serviceScope.launch {
                activeRunner.cancel()
            }
        }

        notificationManager.cancel(NOTIFICATION_ID)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        attackJob?.cancel()
        attackJob = null
        runner = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.chroot_attack_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, content: String): NotificationCompat.Builder {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ChrootAttackService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.chroot_attack_cancel),
                cancelPendingIntent
            )
            .setProgress(0, 0, true)
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content).build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun maybeUpdateNotification(title: String, content: String) {
        val now = System.currentTimeMillis()
        val isMilestone = content == "PIXIE_DONE" ||
                content.startsWith("[+]") || content.startsWith("[-]") ||
                content.contains("WPS PIN:", ignoreCase = true)
        if (!isMilestone && now - lastNotificationUpdate < 500) return
        lastNotificationUpdate = now
        updateNotification(title, content)
    }

    private fun broadcastProgress(text: String) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.PIXIE_DUST.name)
            putExtra(EXTRA_PROGRESS_TEXT, text)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastProgressPercent(percent: Float) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_ATTACK_TYPE, ChrootAttackType.PIXIE_DUST.name)
            putExtra(EXTRA_PROGRESS_TEXT, getString(R.string.svc_percent, (percent * 100).toInt()))
            putExtra(EXTRA_PROGRESS_PERCENT, percent)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(message: String) {
        val intent = Intent(BROADCAST_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, message)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun getTypeLabel(type: ChrootAttackType): String {
        return getString(
            when (type) {
                ChrootAttackType.PIXIE_DUST -> R.string.chroot_attack_pixie_dust
                ChrootAttackType.ROUTER_SCAN -> R.string.chroot_attack_router_scan
            }
        )
    }

    private fun isActive(): Boolean = attackJob?.isActive == true
}
