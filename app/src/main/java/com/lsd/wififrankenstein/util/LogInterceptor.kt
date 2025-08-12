package com.lsd.wififrankenstein.util

import com.lsd.wififrankenstein.util.Log
import java.lang.reflect.Method

object LogInterceptor {
    private var originalLogMethods: Map<String, Method>? = null

    fun setupLogInterceptor() {
        try {
            val logClass = Log::class.java

            originalLogMethods = mapOf(
                "d" to logClass.getMethod("d", String::class.java, String::class.java),
                "i" to logClass.getMethod("i", String::class.java, String::class.java),
                "w" to logClass.getMethod("w", String::class.java, String::class.java),
                "e" to logClass.getMethod("e", String::class.java, String::class.java),
                "v" to logClass.getMethod("v", String::class.java, String::class.java)
            )

        } catch (e: Exception) {
            android.util.Log.e("LogInterceptor", "Failed to setup log interceptor", e)
        }
    }
}