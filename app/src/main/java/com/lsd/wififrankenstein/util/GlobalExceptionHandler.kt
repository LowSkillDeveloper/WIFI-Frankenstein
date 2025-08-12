package com.lsd.wififrankenstein.util

class GlobalExceptionHandler(private val defaultHandler: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            FileLogger.e("UncaughtException", "Fatal exception in thread ${thread.name}", exception)
            Thread.sleep(1000)
        } catch (e: Exception) {
            android.util.Log.e("GlobalExceptionHandler", "Error logging fatal exception", e)
        } finally {
            defaultHandler?.uncaughtException(thread, exception)
        }
    }
}