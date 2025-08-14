package com.lsd.wififrankenstein

import android.app.Application
import com.lsd.wififrankenstein.util.FileLogger
import com.lsd.wififrankenstein.util.GlobalExceptionHandler

class WifiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
        setupNotificationWorker()
        android.util.Log.i("WifiApplication", "Application started")
    }

    override fun onTerminate() {
        android.util.Log.i("WifiApplication", "Application terminating")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.stop()
        }
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        android.util.Log.w("WifiApplication", "Low memory warning")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.logMemoryInfo()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        android.util.Log.w("WifiApplication", "Memory trim requested, level: $level")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.logMemoryInfo()
        }
    }

    private fun setupNotificationWorker() {
        val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.lsd.wififrankenstein.workers.NotificationWorker>(
            12, java.util.concurrent.TimeUnit.HOURS,
            2, java.util.concurrent.TimeUnit.HOURS
        ).build()

        androidx.work.WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "notification_check",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
    }
}