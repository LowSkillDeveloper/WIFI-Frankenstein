package com.lsd.wififrankenstein

import android.app.Application
import com.lsd.wififrankenstein.util.FileLogger
import com.lsd.wififrankenstein.util.GlobalExceptionHandler

class WifiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))

        FileLogger.init(this)

        FileLogger.logMemoryInfo()
        FileLogger.logThreadInfo()
        FileLogger.i("WifiApplication", "Application started with detailed logging")
    }

    override fun onTerminate() {
        FileLogger.i("WifiApplication", "Application terminating")
        FileLogger.stop()
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FileLogger.w("WifiApplication", "Low memory warning")
        FileLogger.logMemoryInfo()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FileLogger.w("WifiApplication", "Memory trim requested, level: $level")
        FileLogger.logMemoryInfo()
    }
}