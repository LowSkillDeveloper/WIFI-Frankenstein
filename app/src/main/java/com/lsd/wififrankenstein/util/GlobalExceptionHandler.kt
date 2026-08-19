package com.lsd.wififrankenstein.util

class GlobalExceptionHandler(private val defaultHandler: Thread.UncaughtExceptionHandler?) :
    Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            FileLogger.e("UncaughtException", "Fatal exception in thread ${thread.name}", exception)
        } catch (e: Exception) {
            Log.e(
                "GlobalExceptionHandler",
                "Error logging fatal exception",
                e
            )
        } finally {
            try {
                Thread.sleep(1000)
            } catch (e: Exception) {
                Log.w(
                    "GlobalExceptionHandler",
                    "Interrupted during sleep",
                    e
                )
            }
            defaultHandler?.uncaughtException(thread, exception)
        }
    }
}