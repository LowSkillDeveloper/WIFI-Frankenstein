package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.ChrootManager
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ChrootInstallService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private lateinit var chrootManager: ChrootManager
    private var installationJob: Job? = null
    private var currentFileSize: Long = 0L

    companion object {
        private const val TAG = "ChrootInstallService"
        private const val CHANNEL_ID = "chroot_install_channel"
        private const val NOTIFICATION_ID = 3001

        const val ACTION_START_INSTALL = "start_chroot_install"
        const val ACTION_CANCEL_INSTALL = "cancel_chroot_install"

        const val BROADCAST_CHROOT_PROGRESS = "chroot_progress"
        const val BROADCAST_CHROOT_STATUS = "chroot_status"
        const val BROADCAST_CHROOT_COMPLETED = "chroot_completed"
        const val BROADCAST_CHROOT_FAILED = "chroot_failed"
        const val BROADCAST_CHROOT_CANCELLED = "chroot_cancelled"
        const val BROADCAST_CHROOT_DIAGNOSTIC = "chroot_diagnostic"

        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_FILE_SIZE = "file_size"
        const val EXTRA_DIAG_NAME = "diag_name"
        const val EXTRA_DIAG_ICON = "diag_icon"
        const val EXTRA_DIAG_RESULT = "diag_result"
        const val EXTRA_DIAG_OUTPUT = "diag_output"

        fun startInstallation(context: Context) {
            val intent = Intent(context, ChrootInstallService::class.java).apply {
                action = ACTION_START_INSTALL
            }
            context.startService(intent)
        }

        fun cancelInstallation(context: Context) {
            val intent = Intent(context, ChrootInstallService::class.java).apply {
                action = ACTION_CANCEL_INSTALL
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        chrootManager = ChrootManager(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_INSTALL -> startInstallation()
            ACTION_CANCEL_INSTALL -> cancelInstallation()
        }
        return START_NOT_STICKY
    }

    private fun startInstallation() {
        if (installationJob?.isActive == true) {
            Log.w(TAG, "Installation already in progress")
            return
        }

        showInitialNotification()

        installationJob = serviceScope.launch {
            try {
                val chrootInfo = chrootManager.getChrootInfo()
                val archive = chrootInfo?.let {
                    if (chrootManager.isAarch64()) it.aarch64 else it.armhf
                }
                currentFileSize = archive?.size ?: 0L

                val success = chrootManager.downloadAndInstall(
                    onProgress = { progress ->
                        updateNotification(progress)
                        broadcastProgress(progress, currentFileSize)
                    },
                    onStatusUpdate = { status ->
                        updateNotificationStatus(status)
                        broadcastStatus(status)
                    },
                    onCancelled = { false },
                    onDiagnosticUpdate = { name, icon, result, fullOutput ->
                        broadcastDiagnostic(name, icon, result, fullOutput)
                    }
                )

                if (success) {
                    broadcastCompleted(true)
                    showCompletionNotification()
                } else {
                    broadcastFailed()
                    showFailureNotification()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Installation failed", e)
                broadcastFailed()
                showFailureNotification()
            } finally {
                installationJob = null
                stopSelf()
            }
        }
    }

    private fun cancelInstallation() {
        installationJob?.cancel()
        installationJob = null
        broadcastCancelled()
        cancelNotification()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.chroot_installation_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.chroot_notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showInitialNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(getString(R.string.chroot_installation_title))
            .setContentText(getString(R.string.chroot_preparing))
            .setProgress(100, 0, true)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show notification", e)
        }
    }

    private fun updateNotification(progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(getString(R.string.chroot_installation_title))
            .setContentText(getString(R.string.chroot_downloading))
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun updateNotificationStatus(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_update)
            .setContentTitle(getString(R.string.chroot_installation_title))
            .setContentText(status)
            .setOngoing(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun showCompletionNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(getString(R.string.chroot_installation_title))
            .setContentText(getString(R.string.chroot_installation_completed))
            .setProgress(100, 100, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show completion notification", e)
        }
    }

    private fun showFailureNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_error)
            .setContentTitle(getString(R.string.chroot_installation_title))
            .setContentText(getString(R.string.chroot_installation_failed))
            .setProgress(100, 0, false)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show failure notification", e)
        }
    }

    private fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(true)
    }

    private fun broadcastProgress(progress: Int, fileSize: Long) {
        val intent = Intent(BROADCAST_CHROOT_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_FILE_SIZE, fileSize)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent(BROADCAST_CHROOT_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastDiagnostic(
        name: String,
        icon: String,
        result: String,
        fullOutput: String = result
    ) {
        val intent = Intent(BROADCAST_CHROOT_DIAGNOSTIC).apply {
            putExtra(EXTRA_DIAG_NAME, name)
            putExtra(EXTRA_DIAG_ICON, icon)
            putExtra(EXTRA_DIAG_RESULT, result)
            putExtra(EXTRA_DIAG_OUTPUT, fullOutput)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastCompleted(success: Boolean) {
        val intent = Intent(BROADCAST_CHROOT_COMPLETED).apply {
            putExtra(EXTRA_SUCCESS, success)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastFailed() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_CHROOT_FAILED))
    }

    private fun broadcastCancelled() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_CHROOT_CANCELLED))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        cancelNotification()
    }
}
