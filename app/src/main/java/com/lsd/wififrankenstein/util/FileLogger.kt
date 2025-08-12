package com.lsd.wififrankenstein.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import android.util.Log as AndroidLog
import kotlinx.coroutines.*
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.lang.reflect.Method

object FileLogger {
    private const val TAG = "FileLogger"
    private var isInitialized = false
    private var context: Context? = null
    private var logWriter: BufferedWriter? = null
    private val logQueue = ConcurrentLinkedQueue<String>()
    private var writerJob: Job? = null
    private var logcatJob: Job? = null
    private var originalSystemOut: PrintStream? = null
    private var originalSystemErr: PrintStream? = null

    private var logMethods: Map<String, Method>? = null
    private var isLogIntercepted = false

    fun init(appContext: Context) {
        if (isInitialized) return

        context = appContext.applicationContext
        isInitialized = true

        CoroutineScope(Dispatchers.IO).launch {
            initializeWriter()
            interceptSystemStreams()
            setupLogInterception()
            startLogcatCapture()
            startQueueProcessor()
        }

        AndroidLog.d("FileLogger", "FileLogger initialized with advanced interception")
    }

    private suspend fun initializeWriter() {
        try {
            val packageName = context?.packageName ?: return
            val logFileName = "wifi_frankenstein_detailed_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"

            val outputStream = createLogFile(logFileName) ?: return
            logWriter = BufferedWriter(OutputStreamWriter(outputStream))

            val header = """
=== WiFi Frankenstein Detailed Log Started ===
Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
Package: $packageName
Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
Device: ${Build.MANUFACTURER} ${Build.MODEL}
Build: ${Build.DISPLAY}
Hardware: ${Build.HARDWARE}
Product: ${Build.PRODUCT}
Process PID: ${android.os.Process.myPid()}
Thread ID: ${Thread.currentThread().id}
Thread Name: ${Thread.currentThread().name}
Available Processors: ${Runtime.getRuntime().availableProcessors()}
Max Memory: ${Runtime.getRuntime().maxMemory() / 1024 / 1024} MB
=====================================

""".trimIndent()

            logQueue.offer(header)

        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error initializing writer", e)
        }
    }

    private fun interceptSystemStreams() {
        try {
            originalSystemOut = System.out
            originalSystemErr = System.err

            System.setOut(object : PrintStream(originalSystemOut!!) {
                override fun print(s: String?) {
                    originalSystemOut?.print(s)
                    s?.let { logSystemOutput("System.out", it, false) }
                }

                override fun println(s: String?) {
                    originalSystemOut?.println(s)
                    s?.let { logSystemOutput("System.out", it, true) }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    originalSystemOut?.write(b, off, len)
                    val output = String(b, off, len)
                    logSystemOutput("System.out", output, false)
                }
            })

            System.setErr(object : PrintStream(originalSystemErr!!) {
                override fun print(s: String?) {
                    originalSystemErr?.print(s)
                    s?.let { logSystemOutput("System.err", it, false) }
                }

                override fun println(s: String?) {
                    originalSystemErr?.println(s)
                    s?.let { logSystemOutput("System.err", it, true) }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    originalSystemErr?.write(b, off, len)
                    val output = String(b, off, len)
                    logSystemOutput("System.err", output, false)
                }
            })

        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error intercepting system streams", e)
        }
    }

    private fun logSystemOutput(source: String, message: String, isNewLine: Boolean) {
        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val stackTrace = getCallerInfo()
        val entry = if (isNewLine) {
            "$timestamp [SYSTEM] $source: $message [Called from: $stackTrace]\n"
        } else {
            "$timestamp [SYSTEM] $source: $message [Called from: $stackTrace]"
        }
        logQueue.offer(entry)
    }

    private fun setupLogInterception() {
        try {
            val logClass = AndroidLog::class.java
            logMethods = mapOf(
                "d" to logClass.getMethod("d", String::class.java, String::class.java),
                "i" to logClass.getMethod("i", String::class.java, String::class.java),
                "w" to logClass.getMethod("w", String::class.java, String::class.java),
                "e" to logClass.getMethod("e", String::class.java, String::class.java),
                "v" to logClass.getMethod("v", String::class.java, String::class.java)
            )
            isLogIntercepted = true
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Could not setup log interception", e)
        }
    }

    private suspend fun startLogcatCapture() {
        logcatJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val packageName = context?.packageName ?: return@launch

                val commands = arrayOf(
                    "logcat", "-v", "threadtime", "-T", "1",
                    "$packageName:V", "AndroidRuntime:E", "System.err:W", "DEBUG:V", "*:S"
                )

                val process = Runtime.getRuntime().exec(commands)
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                logQueue.offer("--------- logcat capture started ---------\n")

                var line: String?
                while (reader.readLine().also { line = it } != null && isActive) {
                    line?.let { logLine ->
                        if (logLine.contains(packageName) ||
                            logLine.contains("AndroidRuntime") ||
                            logLine.contains("System.err") ||
                            logLine.contains("FATAL EXCEPTION") ||
                            logLine.contains("DEBUG") ||
                            logLine.contains("beginning of")) {

                            logQueue.offer("[LOGCAT] $logLine\n")
                        }
                    }
                }
            } catch (e: Exception) {
                logQueue.offer("Logcat capture failed: ${e.message}\n")
                AndroidLog.w(TAG, "Logcat capture failed", e)
            }
        }
    }

    private suspend fun startQueueProcessor() {
        writerJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val batch = mutableListOf<String>()
                    repeat(50) {
                        val entry = logQueue.poll()
                        if (entry != null) {
                            batch.add(entry)
                        }
                    }

                    if (batch.isNotEmpty()) {
                        logWriter?.apply {
                            batch.forEach { entry ->
                                write(entry)
                            }
                            flush()
                        }
                    }

                    delay(100)
                } catch (e: Exception) {
                    AndroidLog.e(TAG, "Error processing log queue", e)
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
            AndroidLog.e(TAG, "Error creating log file", e)
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

    private fun getCallerInfo(): String {
        return try {
            val stack = Thread.currentThread().stackTrace

            for (i in 2 until minOf(stack.size, 10)) {
                val element = stack[i]
                val className = element.className

                if (!className.contains("FileLogger") &&
                    !className.contains("Log") &&
                    !className.contains("PrintStream") &&
                    !className.contains("Thread")) {

                    return "${className.substringAfterLast('.')}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
            }
            "Unknown"
        } catch (e: Exception) {
            "Error getting caller info"
        }
    }

    private fun writeDetailedLog(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val pid = android.os.Process.myPid()
        val tid = Thread.currentThread().id
        val threadName = Thread.currentThread().name
        val callerInfo = getCallerInfo()

        val logEntry = if (throwable != null) {
            "$timestamp $pid-$tid/$level $tag: $message [Thread: $threadName] [Caller: $callerInfo]\n${AndroidLog.getStackTraceString(throwable)}\n"
        } else {
            "$timestamp $pid-$tid/$level $tag: $message [Thread: $threadName] [Caller: $callerInfo]\n"
        }

        logQueue.offer(logEntry)
    }

    fun stop() {
        logcatJob?.cancel()
        writerJob?.cancel()

        try {
            originalSystemOut?.let { System.setOut(it) }
            originalSystemErr?.let { System.setErr(it) }

            val stopMessage = "\n=== Detailed logging stopped at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} ===\n"
            logWriter?.apply {
                write(stopMessage)
                flush()
                close()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error stopping logger", e)
        }

        logWriter = null
        isInitialized = false
    }

    fun logMemoryInfo() {
        val runtime = Runtime.getRuntime()
        val memoryInfo = """
Memory Info:
- Used Memory: ${(runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024} MB
- Free Memory: ${runtime.freeMemory() / 1024 / 1024} MB  
- Total Memory: ${runtime.totalMemory() / 1024 / 1024} MB
- Max Memory: ${runtime.maxMemory() / 1024 / 1024} MB
""".trimIndent()

        writeDetailedLog("I", "MemoryInfo", memoryInfo)
    }

    fun logThreadInfo() {
        val threadInfo = """
Thread Info:
- Active Threads: ${Thread.activeCount()}
- Current Thread: ${Thread.currentThread().name} (ID: ${Thread.currentThread().id})
- Thread State: ${Thread.currentThread().state}
- Priority: ${Thread.currentThread().priority}
""".trimIndent()

        writeDetailedLog("I", "ThreadInfo", threadInfo)
    }

    fun d(tag: String, message: String) {
        AndroidLog.d(tag, message)
        writeDetailedLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        AndroidLog.i(tag, message)
        writeDetailedLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        AndroidLog.w(tag, message)
        writeDetailedLog("W", tag, message)
    }

    fun e(tag: String, message: String) {
        AndroidLog.e(tag, message)
        writeDetailedLog("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        AndroidLog.e(tag, message, throwable)
        writeDetailedLog("E", tag, message, throwable)
    }

    fun v(tag: String, message: String) {
        AndroidLog.v(tag, message)
        writeDetailedLog("V", tag, message)
    }

    fun wtf(tag: String, message: String) {
        AndroidLog.wtf(tag, message)
        writeDetailedLog("WTF", tag, message)
    }
}