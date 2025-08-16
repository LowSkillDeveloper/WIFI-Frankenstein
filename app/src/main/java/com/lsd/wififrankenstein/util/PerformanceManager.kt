package com.lsd.wififrankenstein.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import kotlin.math.max
import kotlin.math.min

object PerformanceManager {
    private var availableMemoryMB: Long = 0
    private var coreCount: Int = 0
    private var isLowRamDevice: Boolean = false

    fun initialize(context: Context) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        availableMemoryMB = memoryInfo.totalMem / (1024 * 1024)
        coreCount = Runtime.getRuntime().availableProcessors()

        isLowRamDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            activityManager.isLowRamDevice
        } else {
            availableMemoryMB < 1024
        }

        Log.d("PerformanceManager", "Available RAM: ${availableMemoryMB}MB, Cores: $coreCount, LowRAM: $isLowRamDevice")
    }

    fun getDatabaseThreadCount(): Int {
        return when {
            isLowRamDevice -> 2
            coreCount >= 8 -> min(6, coreCount - 2)
            coreCount >= 4 -> min(4, coreCount - 1)
            else -> 2
        }
    }

    fun getClusteringThreadCount(): Int {
        return when {
            isLowRamDevice -> 1
            coreCount >= 8 -> min(3, coreCount / 3)
            coreCount >= 4 -> 2
            else -> 1
        }
    }

    fun getIOThreadCount(): Int {
        return when {
            isLowRamDevice -> 1
            coreCount >= 8 -> min(4, coreCount / 2)
            coreCount >= 4 -> 2
            else -> 1
        }
    }

    fun getOptimalChunkSize(): Int {
        return when {
            availableMemoryMB >= 6000 -> 50000
            availableMemoryMB >= 4000 -> 30000
            availableMemoryMB >= 2000 -> 20000
            availableMemoryMB >= 1000 -> 10000
            else -> 5000
        }
    }

    fun getOptimalCacheSize(): Int {
        return when {
            availableMemoryMB >= 6000 -> 100000
            availableMemoryMB >= 4000 -> 75000
            availableMemoryMB >= 2000 -> 50000
            availableMemoryMB >= 1000 -> 25000
            else -> 10000
        }
    }

    fun shouldUseAdvancedCaching(): Boolean {
        return availableMemoryMB >= 2000 && !isLowRamDevice
    }

    fun getMaxPointsPerOperation(): Int {
        return when {
            availableMemoryMB >= 8000 -> Int.MAX_VALUE
            availableMemoryMB >= 6000 -> 1000000
            availableMemoryMB >= 4000 -> 500000
            availableMemoryMB >= 2000 -> 250000
            availableMemoryMB >= 1000 -> 100000
            else -> 50000
        }
    }
}