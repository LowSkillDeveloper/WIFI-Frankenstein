package com.lsd.wififrankenstein.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.lsd.wififrankenstein.MainActivity
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.dbsetup.DatabaseDownloadManager
import com.lsd.wififrankenstein.ui.dbsetup.DownloadStatus
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while background database
 * downloads are running and shows progress notifications.
 *
 * The actual download orchestration lives in [DatabaseDownloadManager]; this
 * service only renders its state as notifications. Tapping any notification
 * opens the Database Setup screen (the page with the active downloads card)
 * via the "open_db_setup" extra handled in MainActivity.
 */
class DatabaseDownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }
    private var lastActionableCount = 0
    private var successCount = 0

    @Volatile
    private var commandReceived = false

    companion object {
        private const val TAG = "DbDownloadService"

        const val ACTION_START_QUEUE = "com.lsd.wififrankenstein.action.START_DB_DOWNLOAD_QUEUE"
        const val ACTION_CANCEL_ITEM = "com.lsd.wififrankenstein.action.CANCEL_DB_DOWNLOAD_ITEM"
        const val ACTION_CANCEL_ALL = "com.lsd.wififrankenstein.action.CANCEL_ALL_DB_DOWNLOADS"
        const val EXTRA_DB_ID = "db_id"

        private const val CHANNEL_ID = "db_background_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1101
        private const val DONE_NOTIFICATION_ID = 1102
        private const val REQUEST_CODE_CONTENT = 3101
        private const val REQUEST_CODE_CANCEL_ALL = 3102

        fun start(context: Context) {
            val intent = Intent(context, DatabaseDownloadService::class.java)
                .setAction(ACTION_START_QUEUE)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        // Cancellations go straight to the process-wide manager; the service
        // action variants exist for notification PendingIntents.
        fun cancelAll(context: Context) = manager(context).cancelAll()

        fun cancelItem(context: Context, dbId: String) = manager(context).cancel(dbId)

        private fun manager(context: Context): DatabaseDownloadManager =
            DatabaseDownloadManager.getOrCreate(context)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val manager = DatabaseDownloadManager.getOrCreate(this)
        serviceScope.launch {
            manager.downloads.collect { render(it) }
        }
        serviceScope.launch {
            manager.events.collect { event ->
                if (event is DatabaseDownloadManager.Event.Completed ||
                    event is DatabaseDownloadManager.Event.NeedsSetupReady
                ) {
                    successCount++
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        commandReceived = true
        // Must be called promptly after startForegroundService()
        startForegroundCompat()

        when (intent?.action) {
            ACTION_START_QUEUE -> Unit // state collector handles rendering

            ACTION_CANCEL_ITEM -> {
                val dbId = intent.getStringExtra(EXTRA_DB_ID)
                if (dbId != null) {
                    DatabaseDownloadManager.getOrCreate(this).cancel(dbId)
                }
            }

            ACTION_CANCEL_ALL -> {
                DatabaseDownloadManager.getOrCreate(this).cancelAll()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- rendering

    private fun startForegroundCompat() {
        val initial = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.db_dl_notif_preparing))
            .setOngoing(true)
            .setContentIntent(buildContentIntent())
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                initial,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
        }
    }

    private fun render(downloads: List<com.lsd.wififrankenstein.ui.dbsetup.PendingDownload>) {
        val active = downloads.filter {
            it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.EXTRACTING
        }
        val queued = downloads.filter { it.status == DownloadStatus.QUEUED }
        val waiting = downloads.filter { it.status == DownloadStatus.WAITING_QUOTA }
        val actionableCount = active.size + queued.size + waiting.size

        if (!hasNotificationPermission()) {
            if (actionableCount == 0 && commandReceived) stopService()
            return
        }

        if (actionableCount == 0) {
            if (lastActionableCount > 0 && successCount > 0) {
                showDoneNotification()
            }
            lastActionableCount = 0
            if (commandReceived) stopService()
            return
        }
        lastActionableCount = actionableCount

        val total = actionableCount
        val current = active.firstOrNull()
        val notification = when {
            current != null && current.status == DownloadStatus.EXTRACTING -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_file_download)
                    .setContentTitle(getString(R.string.db_dl_notif_extracting_title))
                    .setContentText(current.name)
                    .setProgress(100, current.progressPercent, false)
                    .setOngoing(true)
                    .setContentIntent(buildContentIntent())
                    .addAction(0, getString(R.string.db_dl_cancel_all), buildCancelAllIntent())
                    .build()
            }

            current != null -> {
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_file_download)
                    .setContentTitle(
                        getString(R.string.db_dl_notif_active_title, total - queued.size, total)
                    )
                    .setContentText(
                        getString(R.string.db_dl_notif_text_current, current.name, current.progressPercent)
                    )
                    .setProgress(100, current.progressPercent.coerceIn(0, 100), false)
                    .setOngoing(true)
                    .setContentIntent(buildContentIntent())
                    .addAction(0, getString(R.string.db_dl_cancel_all), buildCancelAllIntent())
                    .build()
            }

            else -> {
                // Nothing actively downloading: everything waits for MEGA quota
                val names = waiting.joinToString(", ") { it.name }
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_file_download)
                    .setContentTitle(getString(R.string.db_dl_notif_quota_title))
                    .setContentText(getString(R.string.db_dl_notif_quota_text, names))
                    .setOngoing(true)
                    .setContentIntent(buildContentIntent())
                    .addAction(0, getString(R.string.db_dl_cancel_all), buildCancelAllIntent())
                    .build()
            }
        }
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    private fun showDoneNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_file_download)
            .setContentTitle(getString(R.string.db_dl_notif_done_title))
            .setContentText(getString(R.string.db_dl_notif_done_text))
            .setAutoCancel(true)
            .setContentIntent(buildContentIntent())
            .build()
        notificationManager.notify(DONE_NOTIFICATION_ID, notification)
    }

    private fun stopService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("open_db_setup", true)
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_CODE_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildCancelAllIntent(): PendingIntent {
        val intent = Intent(this, DatabaseDownloadService::class.java)
            .setAction(ACTION_CANCEL_ALL)
        return PendingIntent.getService(
            this,
            REQUEST_CODE_CANCEL_ALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.db_dl_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.db_dl_notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
