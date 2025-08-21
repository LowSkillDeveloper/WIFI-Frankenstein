package com.lsd.wififrankenstein.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.convertdumps.ConversionEngine
import com.lsd.wififrankenstein.ui.convertdumps.ConversionMode
import com.lsd.wififrankenstein.ui.convertdumps.DumpFileType
import com.lsd.wififrankenstein.ui.convertdumps.IndexingOption
import com.lsd.wififrankenstein.ui.convertdumps.SelectedFile
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class ConversionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeConversions = ConcurrentHashMap<String, Job>()
    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    companion object {

        const val EXTRA_OUTPUT_LOCATION = "output_location"
        private const val TAG = "ConversionService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "conversion_channel"

        const val ACTION_START_CONVERSION = "start_conversion"
        const val ACTION_CANCEL_CONVERSION = "cancel_conversion"

        const val EXTRA_FILES_JSON = "files_json"
        const val EXTRA_MODE = "mode"
        const val EXTRA_INDEXING = "indexing"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_OUTPUT_FILE = "output_file"
        const val EXTRA_ERROR_MESSAGE = "error_message"

        const val BROADCAST_CONVERSION_PROGRESS = "conversion_progress"
        const val BROADCAST_CONVERSION_COMPLETE = "conversion_complete"
        const val BROADCAST_CONVERSION_ERROR = "conversion_error"
        const val BROADCAST_CONVERSION_CANCELLED = "conversion_cancelled"

        const val ACTION_CHECK_STATUS = "check_status"
        const val BROADCAST_CONVERSION_STATUS = "conversion_status"
        const val EXTRA_IS_CONVERTING = "is_converting"

        const val EXTRA_OPTIMIZATION = "optimization"

        fun checkStatus(context: Context) {
            val intent = Intent(context, ConversionService::class.java).apply {
                action = ACTION_CHECK_STATUS
            }
            context.startService(intent)
        }

        fun startConversion(
            context: Context,
            files: List<SelectedFile>,
            mode: ConversionMode,
            indexing: IndexingOption,
            outputLocation: Uri? = null,
            optimizationEnabled: Boolean = true
        ) {
            Log.d(TAG, "startConversion called with ${files.size} files")

            val filesJson = files.joinToString(separator = "|||") { file ->
                "${file.uri}::${file.name}::${file.size}::${file.type.name}"
            }

            Log.d(TAG, "Files JSON: $filesJson")

            val intent = Intent(context, ConversionService::class.java).apply {
                action = ACTION_START_CONVERSION
                putExtra(EXTRA_FILES_JSON, filesJson)
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_INDEXING, indexing.name)
                putExtra(EXTRA_OPTIMIZATION, optimizationEnabled)
                if (outputLocation != null) {
                    putExtra(EXTRA_OUTPUT_LOCATION, outputLocation.toString())
                }
            }
            context.startService(intent)
        }

        fun cancelConversion(context: Context) {
            val intent = Intent(context, ConversionService::class.java).apply {
                action = ACTION_CANCEL_CONVERSION
            }
            context.startService(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_CONVERSION -> {
                try {
                    val filesJson = intent.getStringExtra(EXTRA_FILES_JSON)
                    if (filesJson.isNullOrEmpty()) {
                        Log.e(TAG, "No files JSON found in intent")
                        return START_NOT_STICKY
                    }

                    Log.d(TAG, "Found files JSON: $filesJson")

                    val files = parseFilesFromJson(filesJson)
                    Log.d(TAG, "Parsed ${files.size} files")

                    val mode = ConversionMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: ConversionMode.ECONOMY.name)
                    val indexing = IndexingOption.valueOf(intent.getStringExtra(EXTRA_INDEXING) ?: IndexingOption.BASIC.name)
                    val optimizationEnabled = intent.getBooleanExtra(EXTRA_OPTIMIZATION, true)

                    val outputLocationString = intent.getStringExtra(EXTRA_OUTPUT_LOCATION)
                    val outputLocation = if (outputLocationString != null) Uri.parse(outputLocationString) else null

                    Log.d(TAG, "Starting conversion: mode=$mode, indexing=$indexing, optimization=$optimizationEnabled, outputLocation=$outputLocation")
                    startConversion(files, mode, indexing, outputLocation, optimizationEnabled)

                } catch (e: Exception) {
                    Log.e(TAG, "Error starting conversion", e)
                    broadcastError("Error starting conversion: ${e.message}")
                    return START_NOT_STICKY
                }
            }
            ACTION_CANCEL_CONVERSION -> {
                Log.d(TAG, "Cancelling conversion")
                cancelAllConversions()
            }
            ACTION_CHECK_STATUS -> {
                broadcastStatus()
            }
        }
        return START_STICKY
    }

    private fun broadcastStatus() {
        val intent = Intent(BROADCAST_CONVERSION_STATUS).apply {
            putExtra(EXTRA_IS_CONVERTING, activeConversions.isNotEmpty())
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startConversion(files: List<SelectedFile>, mode: ConversionMode, indexing: IndexingOption, outputLocation: Uri?, optimizationEnabled: Boolean) {
        Log.d(TAG, "Starting conversion with ${files.size} files, mode: $mode, indexing: $indexing, optimization: $optimizationEnabled")

        val job = serviceScope.launch {
            try {
                Log.d(TAG, "Conversion job started")
                showNotification(getString(R.string.conversion_in_progress), 0)

                val converter = ConversionEngine(
                    context = this@ConversionService,
                    mode = mode,
                    indexing = indexing,
                    outputLocation = outputLocation,
                    optimizationEnabled = optimizationEnabled
                ) { fileName: String, progress: Int ->
                    Log.d(TAG, "Progress update: $fileName - $progress%")
                    broadcastProgress(fileName, progress)
                    updateNotification(getString(R.string.processing_file, fileName, progress), progress)
                }

                Log.d(TAG, "Starting file conversion...")
                val outputFile = converter.convertFiles(files)
                Log.d(TAG, "Conversion completed: $outputFile")

                if (isActive) {
                    val fileName = outputFile.substringAfterLast('/')
                    broadcastComplete(fileName)
                    showCompletionNotification(fileName)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Conversion failed", e)
                broadcastError(e.message ?: getString(R.string.conversion_error))
            } finally {
                activeConversions.clear()
                if (activeConversions.isEmpty()) {
                    stopSelf()
                }
            }
        }

        activeConversions["main"] = job
    }

    private fun parseFilesFromJson(filesJson: String): List<SelectedFile> {
        if (filesJson.isEmpty()) return emptyList()

        return filesJson.split("|||").mapNotNull { fileString ->
            try {
                val parts = fileString.split("::")
                if (parts.size >= 4) {
                    SelectedFile(
                        uri = Uri.parse(parts[0]),
                        name = parts[1],
                        size = parts[2].toLongOrNull() ?: 0L,
                        type = DumpFileType.valueOf(parts[3])
                    )
                } else null
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing file: $fileString", e)
                null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun cancelAllConversions() {
        activeConversions.values.forEach { it.cancel() }
        activeConversions.clear()
        broadcastCancelled()
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.conversion_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.conversion_notification_desc)
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

    private fun showNotification(text: String, progress: Int) {
        if (!hasNotificationPermission()) {
            Log.w(TAG, "No notification permission")
            return
        }

        Log.d(TAG, "Showing notification: $text ($progress%)")

        val cancelIntent = Intent(this, ConversionService::class.java).apply {
            action = ACTION_CANCEL_CONVERSION
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transform)
            .setContentTitle(getString(R.string.convert_dumps_title))
            .setContentText(text)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(TAG, "Notification shown successfully")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show notification", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            throw e
        }
    }

    private fun updateNotification(text: String, progress: Int) {
        if (!hasNotificationPermission()) {
            return
        }

        Log.d(TAG, "Updating notification: $text ($progress%)")

        val cancelIntent = Intent(this, ConversionService::class.java).apply {
            action = ACTION_CANCEL_CONVERSION
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            0,
            cancelIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transform)
            .setContentTitle(getString(R.string.convert_dumps_title))
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .addAction(R.drawable.ic_close, getString(R.string.cancel), cancelPendingIntent)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Notification updated successfully to $progress%")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    private fun showCompletionNotification(outputFile: String) {
        if (!hasNotificationPermission()) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle(getString(R.string.conversion_complete))
            .setContentText(getString(R.string.output_database, outputFile))
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to show completion notification", e)
        }
    }

    private fun broadcastProgress(fileName: String, progress: Int) {
        val intent = Intent(BROADCAST_CONVERSION_PROGRESS).apply {
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_PROGRESS, progress)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastComplete(outputFile: String) {
        val intent = Intent(BROADCAST_CONVERSION_COMPLETE).apply {
            putExtra(EXTRA_OUTPUT_FILE, outputFile)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastError(errorMessage: String) {
        val intent = Intent(BROADCAST_CONVERSION_ERROR).apply {
            putExtra(EXTRA_ERROR_MESSAGE, errorMessage)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun broadcastCancelled() {
        val intent = Intent(BROADCAST_CONVERSION_CANCELLED)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}