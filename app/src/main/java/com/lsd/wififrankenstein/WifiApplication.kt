package com.lsd.wififrankenstein

import android.app.Application
import com.lsd.wififrankenstein.util.FileLogger
import com.lsd.wififrankenstein.util.GlobalExceptionHandler

class WifiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))

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
}