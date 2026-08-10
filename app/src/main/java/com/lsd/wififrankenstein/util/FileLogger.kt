package com.lsd.wififrankenstein.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import android.util.Log as AndroidLog

object FileLogger {
    private const val TAG = "FileLogger"
    private const val LOG_DIR_CHROOT = "/sdcard/WIFI-Frankenstein/logs"
    private const val FALLBACK_MEDIASTORE_DIR = "Download/WiFi_Frankenstein_Logs"
    private val logDirHost: File
        get() = File(Environment.getExternalStorageDirectory(), "WIFI-Frankenstein/logs")
    private var isInitialized = false
    private var isEnabled = false
    private var context: Context? = null
    private var logWriter: BufferedWriter? = null
    private val logQueue = ConcurrentLinkedQueue<String>()
    private var loggerJob: Job? = null
    private var writerJob: Job? = null
    private var logcatJob: Job? = null
    private var originalSystemOut: PrintStream? = null
    private var originalSystemErr: PrintStream? = null
    private var currentLogFileName: String? = null
    private var rootShellProcess: Process? = null

    private var logMethods: Map<String, Method>? = null
    private var isLogIntercepted = false

    fun enableLogging(appContext: Context) {
        if (isEnabled) return

        isEnabled = true
        context = appContext.applicationContext
        loggerJob = SupervisorJob()

        CoroutineScope(Dispatchers.IO + (loggerJob ?: SupervisorJob())).launch {
            initializeWriter()
            interceptSystemStreams()
            setupLogInterception()
            startLogcatCapture()
            startQueueProcessor()
        }

        AndroidLog.d("FileLogger", "FileLogger enabled with advanced interception")
    }

    fun disableLogging() {
        if (!isEnabled) return

        isEnabled = false
        stop()
        AndroidLog.d("FileLogger", "FileLogger disabled")
    }

    fun isLoggingEnabled(): Boolean = isEnabled

    private suspend fun initializeWriter() {
        try {
            val packageName = context?.packageName ?: return
            val logFileName = "wifi_frankenstein_detailed_log_${
                SimpleDateFormat(
                    "yyyyMMdd_HHmmss",
                    Locale.getDefault()
                ).format(Date())
            }.txt"
            currentLogFileName = logFileName

            val outputStream = createLogFile(logFileName) ?: return
            logWriter = BufferedWriter(OutputStreamWriter(outputStream))
            isInitialized = true

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

    fun deleteLogFolder(): Boolean {
        return try {
            val canUseFileApi = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

            val mainDeleted = when {
                canUseFileApi -> deleteLogFolderFileApi()
                isRootAvailable() -> deleteLogFolderRootShell()
                else -> deleteLogFolderMediaStore()
            }
            val legacyDeleted = deleteLegacyDownloadsLogFolder()
            AndroidLog.d(TAG, "deleteLogFolder: main=$mainDeleted legacy=$legacyDeleted")
            mainDeleted || legacyDeleted
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error deleting log folder", e)
            false
        }
    }

    private fun deleteLogFolderFileApi(): Boolean {
        return try {
            val logDir = logDirHost
            if (logDir.exists() && logDir.isDirectory) {
                val deleted = logDir.deleteRecursively()
                AndroidLog.d(
                    TAG,
                    "File API delete result: $deleted for path: ${logDir.absolutePath}"
                )
                deleted
            } else {
                AndroidLog.d(TAG, "Log directory does not exist: ${logDir.absolutePath}")
                true
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error deleting folder via File API", e)
            false
        }
    }

    private fun deleteLogFolderRootShell(): Boolean {
        return try {
            val result = Shell.cmd("rm -rf '$LOG_DIR_CHROOT'").exec()
            result.isSuccess
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error deleting log folder via root shell", e)
            false
        }
    }

    private fun deleteLogFolderMediaStore(): Boolean {
        return try {
            val resolver = context?.contentResolver ?: return false
            val uri = MediaStore.Files.getContentUri("external")
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%WiFi_Frankenstein_Logs%")

            resolver.delete(uri, selection, selectionArgs)
            true
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error deleting with MediaStore", e)
            false
        }
    }

    private fun deleteLegacyDownloadsLogFolder(): Boolean {
        return try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, "WiFi_Frankenstein_Logs")

            if (logDir.exists() && logDir.isDirectory) {
                val deleted = logDir.deleteRecursively()
                AndroidLog.d(TAG, "Legacy delete result: $deleted for path: ${logDir.absolutePath}")
                deleted
            } else {
                AndroidLog.d(TAG, "Legacy log directory does not exist: ${logDir.absolutePath}")
                true
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error deleting folder legacy", e)
            false
        }
    }

    fun getLastLogFile(): File? {
        return try {
            val canUseFileApi = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

            when {
                canUseFileApi -> getLastLogFileFileApi()
                isRootAvailable() -> getLastLogFileRootShell()
                else -> getLastLogFileMediaStore()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error getting last log file", e)
            null
        }
    }

    private fun getLastLogFileFileApi(): File? {
        return try {
            val logDir = logDirHost
            if (!logDir.exists()) return null

            logDir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".txt") && it.name.contains("wifi_frankenstein")
            }?.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error getting last log file via File API", e)
            null
        }
    }

    private fun getLastLogFileRootShell(): File? {
        return try {
            val listResult =
                Shell.cmd("ls -1t '$LOG_DIR_CHROOT'/*.txt 2>/dev/null | head -n1").exec()
            val name = listResult.out.firstOrNull()?.trim()?.substringAfterLast('/')
            if (name.isNullOrEmpty()) return null

            val cachedFile = File(context?.cacheDir, "last_log.txt")
            cachedFile.parentFile?.mkdirs()
            val cpResult =
                Shell.cmd("cp '$LOG_DIR_CHROOT/$name' '${cachedFile.absolutePath}'").exec()
            if (cpResult.isSuccess && cachedFile.exists()) cachedFile else null
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error getting last log file via root shell", e)
            null
        }
    }

    private fun getLastLogFileMediaStore(): File? {
        return try {
            val context = this.context ?: return null
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri("external")

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.RELATIVE_PATH
            )

            val selection =
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%WiFi_Frankenstein_Logs%", "%.txt")
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            resolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn =
                            cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val pathColumn =
                            cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)

                        val fileName = cursor.getString(nameColumn)
                        val relativePath = cursor.getString(pathColumn)

                        val fullPath = Environment.getExternalStorageDirectory()
                            .toString() + "/" + relativePath + fileName
                        val file = File(fullPath)

                        if (file.exists()) {
                            return file
                        }
                    }
                }

            getLastLogFileLegacy()
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error getting last log file with MediaStore", e)
            getLastLogFileLegacy()
        }
    }

    private fun getLastLogFileLegacy(): File? {
        return try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadsDir, "WiFi_Frankenstein_Logs")

            if (!logDir.exists()) return null

            logDir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".txt") && it.name.contains("wifi_frankenstein")
            }?.maxByOrNull { it.lastModified() }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error getting last log file legacy", e)
            null
        }
    }

    fun getCurrentLogFileName(): String? = currentLogFileName

    private fun interceptSystemStreams() {
        if (!isEnabled) return

        try {
            originalSystemOut = System.out
            originalSystemErr = System.err

            System.setOut(object : PrintStream(originalSystemOut!!) {
                override fun print(s: String?) {
                    originalSystemOut?.print(s)
                    if (isEnabled) s?.let { logSystemOutput("System.out", it, false) }
                }

                override fun println(s: String?) {
                    originalSystemOut?.println(s)
                    if (isEnabled) s?.let { logSystemOutput("System.out", it, true) }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    originalSystemOut?.write(b, off, len)
                    if (isEnabled) {
                        val output = String(b, off, len)
                        logSystemOutput("System.out", output, false)
                    }
                }
            })

            System.setErr(object : PrintStream(originalSystemErr!!) {
                override fun print(s: String?) {
                    originalSystemErr?.print(s)
                    if (isEnabled) s?.let { logSystemOutput("System.err", it, false) }
                }

                override fun println(s: String?) {
                    originalSystemErr?.println(s)
                    if (isEnabled) s?.let { logSystemOutput("System.err", it, true) }
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    originalSystemErr?.write(b, off, len)
                    if (isEnabled) {
                        val output = String(b, off, len)
                        logSystemOutput("System.err", output, false)
                    }
                }
            })

        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error intercepting system streams", e)
        }
    }

    private fun logSystemOutput(source: String, message: String, isNewLine: Boolean) {
        if (!isEnabled) return

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
        if (!isEnabled) return

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
        if (!isEnabled) return

        logcatJob = CoroutineScope(Dispatchers.IO + (loggerJob ?: SupervisorJob())).launch {
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
                while (reader.readLine().also { line = it } != null && isActive && isEnabled) {
                    line?.let { logLine ->
                        if (logLine.contains(packageName) ||
                            logLine.contains("AndroidRuntime") ||
                            logLine.contains("System.err") ||
                            logLine.contains("FATAL EXCEPTION") ||
                            logLine.contains("DEBUG") ||
                            logLine.contains("beginning of")
                        ) {

                            logQueue.offer("[LOGCAT] $logLine\n")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isEnabled) {
                    logQueue.offer("Logcat capture failed: ${e.message}\n")
                    AndroidLog.w(TAG, "Logcat capture failed", e)
                }
            }
        }
    }

    private suspend fun startQueueProcessor() {
        if (!isEnabled) return

        writerJob = CoroutineScope(Dispatchers.IO + (loggerJob ?: SupervisorJob())).launch {
            while (isActive && isEnabled) {
                try {
                    val batch = mutableListOf<String>()
                    repeat(50) {
                        val entry = logQueue.poll()
                        if (entry != null) {
                            batch.add(entry)
                        }
                    }

                    if (batch.isNotEmpty() && isEnabled) {
                        logWriter?.apply {
                            batch.forEach { entry ->
                                write(entry)
                            }
                            flush()
                        }
                    }

                    delay(100)
                } catch (e: Exception) {
                    if (isEnabled) {
                        AndroidLog.e(TAG, "Error processing log queue", e)
                    }
                }
            }
        }
    }

    private fun createLogFile(fileName: String): OutputStream? {
        return try {
            val canUseFileApi = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

            when {
                canUseFileApi -> createFileWithFileApi(fileName)
                isRootAvailable() -> createFileWithRootShell(fileName)
                else -> createFileWithMediaStore(fileName)
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
            put(MediaStore.MediaColumns.RELATIVE_PATH, FALLBACK_MEDIASTORE_DIR)
        }

        val uri: Uri? = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
        return uri?.let { resolver.openOutputStream(it) }
    }

    private fun createFileWithFileApi(fileName: String): OutputStream? {
        val logDir = logDirHost

        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        val file = File(logDir, fileName)
        return FileOutputStream(file)
    }

    private fun createFileWithRootShell(fileName: String): OutputStream? {
        val mkdirResult = Shell.cmd("mkdir -p '$LOG_DIR_CHROOT'").exec()
        if (!mkdirResult.isSuccess) {
            AndroidLog.e(
                TAG,
                "Failed to create log dir via root shell: ${mkdirResult.err.lastOrNull()}"
            )
        }

        val process = Runtime.getRuntime().exec("su")
        val stdin = process.outputStream
        stdin.write("cat > '$LOG_DIR_CHROOT/$fileName'\n".toByteArray())
        stdin.flush()
        rootShellProcess = process
        return stdin
    }

    private fun isRootAvailable(): Boolean {
        return try {
            Shell.isAppGrantedRoot() ?: Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
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
                    !className.contains("Thread")
                ) {

                    return "${className.substringAfterLast('.')}.${element.methodName}(${element.fileName}:${element.lineNumber})"
                }
            }
            "Unknown"
        } catch (e: Exception) {
            "Error getting caller info"
        }
    }

    private fun writeDetailedLog(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        if (!isEnabled) return

        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val pid = android.os.Process.myPid()
        val tid = Thread.currentThread().id
        val threadName = Thread.currentThread().name
        val callerInfo = getCallerInfo()

        val logEntry = if (throwable != null) {
            "$timestamp $pid-$tid/$level $tag: $message [Thread: $threadName] [Caller: $callerInfo]\n${
                AndroidLog.getStackTraceString(
                    throwable
                )
            }\n"
        } else {
            "$timestamp $pid-$tid/$level $tag: $message [Thread: $threadName] [Caller: $callerInfo]\n"
        }

        logQueue.offer(logEntry)
    }

    fun stop() {
        logcatJob?.cancel()
        writerJob?.cancel()
        loggerJob?.cancel()
        loggerJob = null

        try {
            originalSystemOut?.let { System.setOut(it) }
            originalSystemErr?.let { System.setErr(it) }

            if (isInitialized) {
                val stopMessage = "\n=== Detailed logging stopped at ${
                    SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()
                    ).format(Date())
                } ===\n"
                logWriter?.apply {
                    write(stopMessage)
                    flush()
                    close()
                }
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error stopping logger", e)
        }

        try {
            rootShellProcess?.destroy()
        } catch (_: Exception) {
        }
        rootShellProcess = null

        logWriter = null
        isInitialized = false
    }

    fun logMemoryInfo() {
        if (!isEnabled) return

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
        if (!isEnabled) return

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
        if (isEnabled) writeDetailedLog("D", tag, message)
    }

    fun i(tag: String, message: String) {
        AndroidLog.i(tag, message)
        if (isEnabled) writeDetailedLog("I", tag, message)
    }

    fun w(tag: String, message: String) {
        AndroidLog.w(tag, message)
        if (isEnabled) writeDetailedLog("W", tag, message)
    }

    fun e(tag: String, message: String) {
        AndroidLog.e(tag, message)
        if (isEnabled) writeDetailedLog("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        AndroidLog.e(tag, message, throwable)
        if (isEnabled) writeDetailedLog("E", tag, message, throwable)
    }

    fun v(tag: String, message: String) {
        AndroidLog.v(tag, message)
        if (isEnabled) writeDetailedLog("V", tag, message)
    }

    fun wtf(tag: String, message: String) {
        AndroidLog.wtf(tag, message)
        if (isEnabled) writeDetailedLog("WTF", tag, message)
    }
}