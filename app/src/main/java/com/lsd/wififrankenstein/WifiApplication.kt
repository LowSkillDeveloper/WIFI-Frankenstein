package com.lsd.wififrankenstein

import android.app.Application
import android.os.Environment
import android.os.Process
import com.lsd.wififrankenstein.util.FileLogger
import com.lsd.wififrankenstein.util.GlobalExceptionHandler
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class WifiApplication : Application() {

    companion object {
        private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        var isRootAvailable: Boolean? = null
            private set

        suspend fun checkRootAccess(): Boolean {
            isRootAvailable?.let { return it }
            return try {
                Shell.getShell().isRoot.also { isRootAvailable = it }
            } catch (_: Exception) {
                false.also { isRootAvailable = false }
            }
        }

        fun resetRootCache() {
            isRootAvailable = null
        }
    }

    override fun onCreate() {
        super.onCreate()

        createStorageDir()

        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setInitializers(com.lsd.wififrankenstein.shell.ShellInitializer::class.java)
                .setTimeout(15)
        )

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler(defaultHandler))
        setupNotificationWorker()

        com.lsd.wififrankenstein.util.Log.i("WifiApplication", "Application started")
        val exceptionHandler = CoroutineExceptionHandler { _, t ->
            com.lsd.wififrankenstein.util.Log.e("AppCoroutine", "Unhandled coroutine exception", t)
        }
    }

    private fun createStorageDir() {
        try {
            val base = Environment.getExternalStorageDirectory()
            val dirs = listOf(
                File(base, "WIFI-Frankenstein/captured"),
                File(base, "WIFI-Frankenstein/handshakes-storage"),
                File(base, "WIFI-Frankenstein/logs")
            )
            for (dir in dirs) {
                if (!dir.exists()) {
                    dir.mkdirs()
                    com.lsd.wififrankenstein.util.Log.i(
                        "WifiApplication",
                        "Created storage dir: ${dir.absolutePath}"
                    )
                }
            }
        } catch (e: Exception) {
            com.lsd.wififrankenstein.util.Log.e(
                "WifiApplication",
                "Failed to create storage dir",
                e
            )
        }
    }

    override fun onTerminate() {
        com.lsd.wififrankenstein.util.Log.i("WifiApplication", "Application terminating")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.stop()
        }
        super.onTerminate()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.lsd.wififrankenstein.util.Log.w("WifiApplication", "Low memory warning")
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.logMemoryInfo()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.lsd.wififrankenstein.util.Log.w(
            "WifiApplication",
            "Memory trim requested, level: $level"
        )
        if (FileLogger.isLoggingEnabled()) {
            FileLogger.logMemoryInfo()
        }
    }

    suspend fun fixDataDirOwnershipIfNeeded() {
        try {
            val uid = Process.myUid()
            val dataDir = filesDir.absolutePath
            val parentDir = filesDir.parentFile?.absolutePath ?: return

            val result = Shell.cmd(
                "stat -c '%u' '$dataDir'",
                "stat -c '%u' '$parentDir'"
            ).exec()

            val fileUid = result.out.getOrNull(0)?.trim()
            val parentUid = result.out.getOrNull(1)?.trim()

            if (fileUid != null && fileUid != uid.toString()) {
                com.lsd.wififrankenstein.util.Log.w(
                    "WifiApplication",
                    "Fixing files dir ownership: $fileUid → $uid"
                )
                Shell.cmd("chown -R $uid:$uid '$dataDir' 2>/dev/null || true").exec()
            }
            if (parentUid != null && parentUid != uid.toString()) {
                com.lsd.wififrankenstein.util.Log.w(
                    "WifiApplication",
                    "Fixing data dir ownership: $parentUid → $uid"
                )
                Shell.cmd("chown $uid:$uid '$parentDir' 2>/dev/null || true").exec()
            }
        } catch (e: Exception) {
            com.lsd.wififrankenstein.util.Log.e(
                "WifiApplication",
                "Failed to fix data dir ownership", e
            )
        }
    }

    private fun setupNotificationWorker() {
        val workRequest =
            androidx.work.PeriodicWorkRequestBuilder<com.lsd.wififrankenstein.workers.NotificationWorker>(
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