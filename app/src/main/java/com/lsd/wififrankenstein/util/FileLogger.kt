package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

object FileLogger {
    private const val TAG = "FileLogger"
    private var isInitialized = false
    private var context: Context? = null
    private var logWriter: BufferedWriter? = null
    private val logQueue = ConcurrentLinkedQueue<String>()
    private var writerJob: Job? = null
    private var logcatJob: Job? = null

    fun init(appContext: Context) {
        if (isInitialized) return

        context = appContext.applicationContext
        isInitialized = true

        CoroutineScope(Dispatchers.IO).launch {
            initializeWriter()
            startLogcatCapture()
            startQueueProcessor()
        }

        d("FileLogger", "FileLogger initialized")
    }

    private suspend fun initializeWriter() {
        try {
            val packageName = context?.packageName ?: return
            val logFileName = "wifi_frankenstein_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"

            val outputStream = createLogFile(logFileName) ?: return
            logWriter = BufferedWriter(OutputStreamWriter(outputStream))

            val header = """
=== WiFi Frankenstein Log Started ===
Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
Package: $packageName
Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
Device: ${Build.MANUFACTURER} ${Build.MODEL}
=====================================

""".trimIndent()

            logQueue.offer(header)

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing writer", e)
        }
    }

    private suspend fun startLogcatCapture() {
        logcatJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageName = context?.packageName ?: return@launch

                val process = Runtime.getRuntime().exec("logcat -v threadtime")
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                logQueue.offer("--------- beginning of main\n")
                logQueue.offer("--------- beginning of system\n")

                var line: String?
                while (reader.readLine().also { line = it } != null && isActive) {
                    line?.let { logLine ->
                        if (logLine.contains(packageName) ||
                            logLine.contains("AndroidRuntime") ||
                            logLine.contains("System.err") ||
                            logLine.contains("FATAL EXCEPTION")) {

                            logQueue.offer("$logLine\n")
                        }
                    }
                }
            } catch (e: Exception) {
                logQueue.offer("Logcat capture failed: ${e.message}, using internal logging only\n")
                android.util.Log.w(TAG, "Logcat capture failed, using internal logging only", e)
            }
        }
    }

    private suspend fun startQueueProcessor() {
        writerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    while (logQueue.isNotEmpty()) {
                        val logEntry = logQueue.poll()
                        logEntry?.let { entry ->
                            logWriter?.apply {
                                write(entry)
                                flush()
                            }
                        }
                    }
                    delay(100)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing log queue", e)
                }
            }
        }
    }

    private fun createLogFile(fileName: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createFileWithMediaStore(fileName)
            } else {
                createFileWithLegacyStorage(fileName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating log file", e)
            null
        }
    }

    private fun createFileWithMediaStore(fileName: String): OutputStream? {
        val resolver = context?.contentResolver ?: return null

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WiFi_Frankenstein_Logs")
        }

        val uri: Uri? = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        return uri?.let { resolver.openOutputStream(it) }
    }

    private fun createFileWithLegacyStorage(fileName: String): OutputStream? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val logDir = File(downloadsDir, "WiFi_Frankenstein_Logs")

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val file = File(logDir, fileName)
        return FileOutputStream(file)
    }

    fun stop() {
        logcatJob?.cancel()
        writerJob?.cancel()

        try {
            val stopMessage = "\n=== Logging stopped at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} ===\n"
            logWriter?.apply {
                write(stopMessage)
                flush()
                close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping logger", e)
        }

        logWriter = null
        isInitialized = false
    }

    private fun writeLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val pid = android.os.Process.myPid()
        val tid = Thread.currentThread().id

        val logEntry = if (throwable != null) {
            "$timestamp $pid-$tid/$level $tag: $message\n${android.util.Log.getStackTraceString(throwable)}\n"
        } else {
            "$timestamp $pid-$tid/$level $tag: $message\n"
        }

        logQueue.offer(logEntry)
    }

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        writeLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        writeLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
        writeLog("W", tag, message)
    }

    fun e(tag: String, message: String) {
        android.util.Log.e(tag, message)
        writeLog("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        android.util.Log.e(tag, message, throwable)
        writeLog("E", tag, message, throwable)
    }

    fun v(tag: String, message: String) {
        android.util.Log.v(tag, message)
        writeLog("V", tag, message)
    }

    fun wtf(tag: String, message: String) {
        android.util.Log.wtf(tag, message)
        writeLog("WTF", tag, message)
    }
}