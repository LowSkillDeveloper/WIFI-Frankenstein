package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build
import java.io.File

object CompatibilityHelper {
    private const val TAG = "CompatibilityHelper"

    fun isLowMemoryDevice(): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        return maxMemory < 256 * 1024 * 1024
    }

    fun getRecommendedChunkSize(): Int {
        return if (isLowMemoryDevice()) 500 else 1000
    }

    fun canHandleLargeFiles(): Boolean {
        return !isLowMemoryDevice() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }

    fun isFileAccessible(file: File): Boolean {
        return try {
            file.exists() && file.canRead() && file.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Error checking file accessibility: ${file.path}", e)
            false
        }
    }

    fun createTempFileWithFallback(context: Context, prefix: String, suffix: String): File? {
        return try {
            File.createTempFile(prefix, suffix, context.cacheDir)
        } catch (e: Exception) {
            try {
                File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}$suffix")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create temp file", e2)
                null
            }
        }
    }

    fun safeFileOperation(operation: () -> Unit): Boolean {
        return try {
            operation()
            true
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError during file operation", e)
            System.gc()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error during file operation", e)
            false
        }
    }
}