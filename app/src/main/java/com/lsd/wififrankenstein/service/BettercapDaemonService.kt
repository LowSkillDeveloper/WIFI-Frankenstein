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
import com.lsd.wififrankenstein.network.bettercap.BettercapClient
import com.lsd.wififrankenstein.util.BettercapManager
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.util.stopForegroundCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BettercapDaemonService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private lateinit var bettercapManager: BettercapManager
    private lateinit var client: BettercapClient
    private var daemonJob: Job? = null

    companion object {
        private const val TAG = "BettercapDaemonSvc"
        private const val CHANNEL_ID = "bettercap_channel"
        private const val NOTIFICATION_ID = 5001

        const val ACTION_START = "bettercap_start"
        const val ACTION_STOP = "bettercap_stop"
        const val EXTRA_IFACE = "iface"
        const val EXTRA_CHANNEL_MODE = "channel_mode"

        const val BROADCAST_STATUS = "bettercap_status"
        const val EXTRA_STATUS = "status"

        fun start(context: Context, iface: String, channelMode: String = "auto") {
            val intent = Intent(context, BettercapDaemonService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IFACE, iface)
                putExtra(EXTRA_CHANNEL_MODE, channelMode)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BettercapDaemonService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bettercapManager = BettercapManager(applicationContext)
        client = BettercapClient()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val iface = intent.getStringExtra(EXTRA_IFACE) ?: "wlan0"
                val channelMode = intent.getStringExtra(EXTRA_CHANNEL_MODE) ?: "auto"
                val notification = buildNotification("Starting", "Interface: $iface").build()
                startForeground(NOTIFICATION_ID, notification)
                startDaemon(iface, channelMode)
            }

            ACTION_STOP -> {
                stopDaemon()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDaemon(iface: String, channelMode: String) {
        Log.d(TAG, "startDaemon: iface=$iface, channelMode=$channelMode")
        daemonJob = serviceScope.launch {
            try {
                Log.d(TAG, "startDaemon: step 1 — broadcasting STARTING status")
                broadcastStatus("starting")
                updateNotification("Starting...", "Mounting chroot on $iface")

                Log.d(TAG, "startDaemon: step 2 — calling bettercapManager.startDaemon()")
                val monitorIface = bettercapManager.startDaemon(iface, channelMode)

                Log.d(TAG, "startDaemon: step 2 result — monitorIface=$monitorIface")
                if (monitorIface == null) {
                    Log.e(TAG, "startDaemon: bettercapManager.startDaemon returned false")
                    broadcastStatus("error")
                    updateNotification("Failed", "Could not start bettercap")
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }

                Log.d(TAG, "startDaemon: step 3 — waiting for REST API (30s timeout)")
                broadcastStatus("starting")
                updateNotification(
                    "Starting...",
                    "Waiting for REST API at http://127.0.0.1:8081..."
                )

                val ready = withContext(Dispatchers.IO) {
                    client.waitForReady(30_000)
                }

                Log.d(TAG, "startDaemon: step 3 result — ready=$ready, isRunning=${isRunning()}")
                if (!ready || !isRunning()) {
                    Log.e(TAG, "startDaemon: waitForReady failed — REST API not available")
                    broadcastStatus("error")
                    updateNotification("Failed", "REST API not ready within 30s")
                    Log.d(TAG, "startDaemon: stopping daemon and service")
                    bettercapManager.stopDaemon()
                    stopForegroundCompat()
                    stopSelf()
                    return@launch
                }

                Log.d(TAG, "startDaemon: step 4 — broadcasting RUNNING status")
                broadcastStatus("running", monitorIface)
                updateNotification("Running", "Interface: $monitorIface")
                Log.d(TAG, "Bettercap daemon started successfully on $monitorIface")

                healthcheckLoop()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startDaemon: exception", e)
                broadcastStatus("error")
                updateNotification("Error", e.message ?: "Unknown error")
                try {
                    bettercapManager.stopDaemon()
                } catch (_: Exception) {
                }
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun healthcheckLoop() {
        var failCount = 0
        while (isRunning()) {
            delay(10_000)
            try {
                val alive = withContext(Dispatchers.IO) { client.ping() }
                if (alive) {
                    failCount = 0
                } else {
                    failCount++
                    Log.w(TAG, "Healthcheck failed ($failCount/3)")
                    if (failCount >= 3) {
                        Log.e(TAG, "Bettercap not responding — reporting error")
                        broadcastStatus("error")
                        updateNotification("Error", "Healthcheck failed")
                        bettercapManager.stopDaemon()
                        stopForegroundCompat()
                        stopSelf()
                        return
                    }
                }
            } catch (e: Exception) {
                failCount++
                Log.w(TAG, "Healthcheck error ($failCount/3)", e)
                if (failCount >= 3) {
                    Log.e(TAG, "Bettercap not responding — reporting error")
                    broadcastStatus("error")
                    updateNotification("Error", "Healthcheck failed")
                    bettercapManager.stopDaemon()
                    stopForegroundCompat()
                    stopSelf()
                    return
                }
            }
        }
    }

    private fun stopDaemon() {
        daemonJob?.cancel()
        daemonJob = null
        serviceScope.launch {
            bettercapManager.stopDaemon()
            broadcastStatus("stopped")
            notificationManager.cancel(NOTIFICATION_ID)
            stopForegroundCompat()
            stopSelf()
        }
    }

    override fun onDestroy() {
        daemonJob?.cancel()
        daemonJob = null
        serviceScope.launch(NonCancellable) {
            try {
                bettercapManager.stopDaemon()
            } catch (_: Exception) {
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isRunning(): Boolean = daemonJob?.isActive == true

    private fun broadcastStatus(status: String, iface: String? = null) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            if (iface != null) putExtra(EXTRA_IFACE, iface)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Bettercap Daemon",
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bettercap: $title")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
    }

    private fun updateNotification(title: String, content: String) {
        val notification = buildNotification(title, content).build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
