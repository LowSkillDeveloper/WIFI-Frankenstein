package com.lsd.wififrankenstein.util

import android.os.Build
import java.util.concurrent.TimeUnit

object ProcessCompat {

    private const val POLL_INTERVAL_MS = 25L

    fun isAlive(process: Process): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.isAlive
        }
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    fun waitFor(process: Process, timeout: Long, unit: TimeUnit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeout, unit)
        }
        val deadline = System.currentTimeMillis() + unit.toMillis(timeout)
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(process)) return true
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return !isAlive(process)
            }
        }
        return !isAlive(process)
    }

    fun destroyForcibly(process: Process): Process {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.destroyForcibly()
        }
        process.destroy()
        return process
    }
}
