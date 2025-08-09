package com.lsd.wififrankenstein.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, Job>()
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }

    companion object {
        private const val TAG = "DownloadService"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_NOTIFICATION_BASE_ID = 2000
        private const val CHANNEL_ID = "download_channel"

        const val ACTION_START_DOWNLOAD = "start_download"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        const val ACTION_CANCEL_ALL_DOWNLOADS = "cancel_all_downloads"

        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_DOWNLOAD_URL = "download_url"
        const val EXTRA_SERVER_VERSION = "server_version"

        const val BROADCAST_DOWNLOAD_PROGRESS = "download_progress"
        const val BROADCAST_DOWNLOAD_COMPLETE = "download_complete"
        const val BROADCAST_DOWNLOAD_ERROR = "download_error"
        const val BROADCAST_DOWNLOAD_CANCELLED = "download_cancelled"

        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        fun startDownload(context: Context, fileName: String, downloadUrl: String, serverVersion: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
                putExtra(EXTRA_SERVER_VERSION, serverVersion)
            }
            context.startService(intent)
        }

        fun cancelDownload(context: Context, fileName: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_FILE_NAME, fileName)
            }
            context.startService(intent)
        }

        fun cancelAllDownloads(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL_DOWNLOADS
            }
            context.startService(intent)
        }

        fun hasActiveDownloads(context: Context): Boolean {
            return try {
                val service = context as? DownloadService
                service?.activeDownloads?.isNotEmpty() ?: false
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
                val serverVersion = intent.getStringExtra(EXTRA_SERVER_VERSION) ?: return START_NOT_STICKY

                if (!activeDownloads.containsKey(fileName)) {
                    startDownload(fileName, downloadUrl, serverVersion)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
                cancelDownload(fileName)
            }
            ACTION_CANCEL_ALL_DOWNLOADS -> {
                cancelAllDownloads()
            }
        }

        return START_STICKY
    }

    private fun startDownload(fileName: String, downloadUrl: String, serverVersion: String) {
        val job = serviceScope.launch {
            try {
                showNotification(fileName, 0)

                val file = File(filesDir, fileName)
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                val fileSize = connection.contentLength

                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        var totalBytesRead = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (!isActive) break

                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val progress = if (fileSize > 0) (totalBytesRead * 100 / fileSize) else 0

                            updateNotification(fileName, progress)
                            broadcastProgress(fileName, progress)
                        }
                    }
                }

                if (isActive) {
                    updateFileVersion(fileName, serverVersion)
                    broadcastComplete(fileName)
                    cancelNotification(fileName)
                    showCompletionNotification(fileName)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $fileName", e)
                broadcastError(fileName, e.message ?: getString(R.string.download_error_unknown))
                cancelNotification(fileName)
            } finally {
                activeDownloads.remove(fileName)
                if (activeDownloads.isEmpty()) {
                    stopSelf()
                }
            }
        }

        activeDownloads[fileName] = job
    }

    private fun cancelDownload(fileName: String) {
        activeDownloads[fileName]?.cancel()
        activeDownloads.remove(fileName)
        broadcastCancelled(fileName)
        cancelNotification(fileName)

        if (activeDownloads.isEmpty()) {
            stopSelf()
        }
    }

    private fun cancelAllDownloads() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        cancelAllNotifications()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun showNotification(fileName: String, progress: Int) {
        if (!hasNotificationPermission()) {
            Log.w(TAG, "No notification permission, skipping notification")
            return
        }

        val notificationId = getNotificationId(fileName)
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            notificationId,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setContentText(fileName)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()

        try {
            if (activeDownloads.size == 1) {
                startForeground(notificationId, notification)
            } else {
                notificationManager.notify(notificationId, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification due to missing permission", e)
        }
    }

    private fun updateNotification(fileName: String, progress: Int) {
        if (!hasNotificationPermission()) {
            return
        }

        val notificationId = getNotificationId(fileName)
        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            notificationId,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setContentText(fileName)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()

        try {
            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update notification due to missing permission", e)
        }
    }

    private fun showCompletionNotification(fileName: String) {
        if (!hasNotificationPermission()) {
            return
        }

        val completionId = getCompletionNotificationId(fileName)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(getString(R.string.download_complete))
            .setContentText(getString(R.string.file_downloaded_successfully, fileName))
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            notificationManager.notify(completionId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show completion notification due to missing permission", e)
        }
    }

    private fun cancelNotification(fileName: String) {
        val notificationId = getNotificationId(fileName)
        notificationManager.cancel(notificationId)
    }

    private fun cancelAllNotifications() {
        activeDownloads.keys.forEach { fileName ->
            cancelNotification(fileName)
        }
        notificationManager.cancelAll()
        stopForeground(true)
    }

    private fun broadcastProgress(fileName: String, progress: Int) {
        val intent = Intent(BROADCAST_DOWNLOAD_PROGRESS).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_PROGRESS, progress)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastComplete(fileName: String) {
        val intent = Intent(BROADCAST_DOWNLOAD_COMPLETE).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(fileName: String, errorMessage: String) {
        val intent = Intent(BROADCAST_DOWNLOAD_ERROR).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastCancelled(fileName: String) {
        val intent = Intent(BROADCAST_DOWNLOAD_CANCELLED).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun updateFileVersion(fileName: String, newVersion: String) {
        val versionFileName = "${fileName.substringBeforeLast(".")}_version.json"
        val versionJson = JSONObject().put("version", newVersion)
        openFileOutput(versionFileName, Context.MODE_PRIVATE).use { output ->
            output.write(versionJson.toString().toByteArray())
        }
    }

    fun hasActiveDownloads(): Boolean = activeDownloads.isNotEmpty()

    private fun getNotificationId(fileName: String): Int {
        return NOTIFICATION_ID + fileName.hashCode() % 1000
    }

    private fun getCompletionNotificationId(fileName: String): Int {
        return COMPLETION_NOTIFICATION_BASE_ID + fileName.hashCode() % 1000
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        cancelAllNotifications()
    }
}