package com.lsd.wififrankenstein

import android.app.Application
import com.lsd.wififrankenstein.util.FileLogger
import com.lsd.wififrankenstein.util.GlobalExceptionHandler
import java.io.PrintStream

class WifiApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))

        FileLogger.init(this)
        android.util.Log.i("WifiApplication", getString(R.string.app_started))

        val originalOut = System.out
        val originalErr = System.err

        System.setOut(object : PrintStream(originalOut) {
            override fun println(x: String?) {
                originalOut.println(x)
                x?.let { FileLogger.i("System.out", it) }
            }
        })

        System.setErr(object : PrintStream(originalErr) {
            override fun println(x: String?) {
                originalErr.println(x)
                x?.let { FileLogger.e("System.err", it) }
            }
        })
    }

    override fun onTerminate() {
        FileLogger.stop()
        super.onTerminate()
    }
}