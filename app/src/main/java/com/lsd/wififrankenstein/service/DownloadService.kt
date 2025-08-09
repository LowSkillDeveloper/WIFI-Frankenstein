package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
                    cancelNotification()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for $fileName", e)
                broadcastError(fileName, e.message ?: getString(R.string.download_error_unknown))
                cancelNotification()
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

        if (activeDownloads.isEmpty()) {
            cancelNotification()
            stopSelf()
        }
    }

    private fun cancelAllDownloads() {
        activeDownloads.values.forEach { it.cancel() }
        activeDownloads.clear()
        cancelNotification()
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

    private fun showNotification(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setContentText(fileName)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(fileName: String, progress: Int) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.downloading_file))
            .setContentText(fileName)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun cancelNotification() {
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

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}