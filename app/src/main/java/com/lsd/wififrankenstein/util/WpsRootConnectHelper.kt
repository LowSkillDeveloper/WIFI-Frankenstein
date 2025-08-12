package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.lsd.wififrankenstein.util.Log
import com.lsd.wififrankenstein.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class WpsRootConnectHelper(
    private val context: Context,
    private val callbacks: WpsConnectCallbacks
) {

    companion object {
        private const val TAG = "WpsRootConnectHelper"
        private const val CONFIG_FILE = "wps_connect.conf"
        private const val CONNECTION_TIMEOUT = 60000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val binaryDir = context.filesDir.absolutePath
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var supplicantProcess: Process? = null
    private var supplicantOutput: BufferedReader? = null
    private var connectionJob: Job? = null

    interface WpsConnectCallbacks {
        fun onConnectionProgress(message: String)
        fun onConnectionSuccess(ssid: String)
        fun onConnectionFailed(error: String)
        fun onLogEntry(message: String)
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
            val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

            val requiredBinaries = listOf(
                "wpa_supplicant$arch",
                "wpa_cli$arch"
            )

            val requiredLibraries = if (arch.isEmpty()) {
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

            val allFiles = requiredBinaries + requiredLibraries

            allFiles.all { fileName ->
                val file = java.io.File(binaryDir, fileName)
                file.exists() && file.length() > 0 && file.canRead()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking binary files", e)
            false
        }
    }

    fun copyBinariesFromAssets() {
        scope.launch {
            try {
                callbacks.onConnectionProgress(context.getString(R.string.wps_root_copying_binaries))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val binaries = listOf(
                    "wpa_supplicant$arch",
                    "wpa_cli$arch"
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

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_binaries_ready))

            } catch (e: Exception) {
                Log.e(TAG, "Error copying binaries", e)
                callbacks.onConnectionFailed(context.getString(R.string.wps_root_error_copying_binaries))
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
            Shell.cmd("cd $binaryDir && ln -sf $sourceFile $linkName").exec()

        } catch (e: Exception) {
            Log.e(TAG, "Error creating symlink $linkName: ${e.message}", e)
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

    fun connectToNetworkWps(network: ScanResult, wpsPin: String? = null, interfaceName: String = "wlan0") {
        if (connectionJob?.isActive == true) {
            Log.w(TAG, "Connection already in progress")
            return
        }

        connectionJob = scope.launch {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_starting_connection, network.SSID))

                if (!checkRootAccess()) {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_no_root))
                    return@launch
                }

                if (!checkBinaryFiles()) {
                    copyBinariesFromAssets()
                    delay(3000)

                    if (!checkBinaryFiles()) {
                        callbacks.onConnectionFailed(context.getString(R.string.wps_root_binaries_missing))
                        return@launch
                    }
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_preparing))

                stopSystemWifi()
                delay(2000)

                val socketDir = getSocketDirectory()
                if (!startSupplicant(socketDir, interfaceName)) {
                    restoreSystemWifi()
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_supplicant_failed))
                    return@launch
                }

                delay(3000)

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_connecting))

                val success = if (wpsPin != null) {
                    performWpsPinConnection(network, socketDir, wpsPin)
                } else {
                    performWpsPbcConnection(network, socketDir)
                }

                stopOurProcesses()
                restoreSystemWifi()

                if (success) {
                    callbacks.onConnectionSuccess(network.SSID)
                } else {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_connection_failed))
                }

            } catch (e: Exception) {
                Log.e(TAG, "WPS connection failed", e)
                stopOurProcesses()
                restoreSystemWifi()
                callbacks.onConnectionFailed(context.getString(R.string.wps_root_connection_error, e.message ?: "Unknown"))
            } finally {
                connectionJob = null
            }
        }
    }

    private suspend fun stopSystemWifi() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_stopping_system_wifi))

                wifiManager.isWifiEnabled = false
                delay(2000)

                val stopCommands = listOf(
                    "stop wpa_supplicant",
                    "killall -9 wpa_supplicant",
                    "pkill -9 -f wpa_supplicant"
                )

                stopCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(500)
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error stopping system WiFi", e)
            }
        }
    }

    private suspend fun restoreSystemWifi() {
        withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_restoring_wifi))

                delay(2000)
                wifiManager.isWifiEnabled = true
                delay(3000)

            } catch (e: Exception) {
                Log.w(TAG, "Error restoring system WiFi", e)
            }
        }
    }

    private fun getSocketDirectory(): String {
        return if (Build.VERSION.SDK_INT >= 28) {
            "/data/vendor/wifi/wpa/wpsconnect/"
        } else {
            "/data/misc/wifi/wpsconnect/"
        }
    }

    private suspend fun startSupplicant(socketDir: String, interfaceName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                callbacks.onLogEntry(context.getString(R.string.wps_root_starting_supplicant))

                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"

                Shell.cmd("rm -rf $socketDir").exec()
                Shell.cmd("mkdir -p $socketDir").exec()
                Shell.cmd("chmod 777 $socketDir").exec()

                val configPath = createWpsConfig()

                val command = """
                cd $binaryDir && \
                export LD_LIBRARY_PATH=$binaryDir && \
                ./wpa_supplicant$arch -dd -K -Dnl80211,wext -i $interfaceName -c$configPath -O$socketDir
            """.trimIndent()

                supplicantProcess = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                supplicantOutput = BufferedReader(InputStreamReader(supplicantProcess!!.inputStream))

                scope.launch {
                    var line: String?
                    while (supplicantOutput?.readLine().also { line = it } != null) {
                        line?.let { parseLine(it) }
                    }
                }

                val socketFile = "$socketDir/$interfaceName"
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 15000) {
                    if (Shell.cmd("test -S $socketFile").exec().isSuccess) {
                        callbacks.onLogEntry(context.getString(R.string.wps_root_supplicant_started))
                        return@withContext true
                    }
                    delay(500)
                }

                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start supplicant", e)
                false
            }
        }
    }

    private suspend fun createWpsConfig(): String {
        return withContext(Dispatchers.IO) {
            val configContent = """
                ctrl_interface_group=wifi
                update_config=1
                ap_scan=1
            """.trimIndent()

            val configPath = "$binaryDir/$CONFIG_FILE"

            try {
                context.openFileOutput(CONFIG_FILE, Context.MODE_PRIVATE).use { output ->
                    output.write(configContent.toByteArray())
                }
                Shell.cmd("chmod 644 $configPath").exec()
                configPath
            } catch (e: Exception) {
                Log.e(TAG, "Error creating config", e)
                configPath
            }
        }
    }

    private suspend fun performWpsPbcConnection(network: ScanResult, socketDir: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketPath = "$socketDir/wlan0"

                callbacks.onLogEntry(context.getString(R.string.wps_root_starting_pbc, network.BSSID))

                val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath wps_pbc ${network.BSSID}"

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    waitForConnection()
                } else {
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "WPS PBC failed", e)
                false
            }
        }
    }

    private suspend fun performWpsPinConnection(network: ScanResult, socketDir: String, pin: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val arch = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "" else "-32"
                val socketPath = "$socketDir/wlan0"

                callbacks.onLogEntry(context.getString(R.string.wps_root_starting_pin, network.BSSID, pin))

                val command = "cd $binaryDir && export LD_LIBRARY_PATH=$binaryDir && ./wpa_cli$arch -g$socketPath wps_pin ${network.BSSID} $pin"

                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    waitForConnection()
                } else {
                    false
                }

            } catch (e: Exception) {
                Log.e(TAG, "WPS PIN failed", e)
                false
            }
        }
    }

    private suspend fun waitForConnection(): Boolean {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < CONNECTION_TIMEOUT) {
                delay(1000)
            }
            true
        }
    }

    private fun parseLine(line: String) {
        Log.d(TAG, "WPS: $line")

        when {
            line.contains("WPS-SUCCESS") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_wps_success))
            }
            line.contains("WPS-FAIL") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_wps_failed))
            }
            line.contains("WPS-TIMEOUT") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_wps_timeout))
            }
            line.contains("CTRL-EVENT-CONNECTED") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_connected))
            }
            line.contains("CTRL-EVENT-DISCONNECTED") -> {
                callbacks.onLogEntry(context.getString(R.string.wps_root_disconnected))
            }
        }
    }

    private suspend fun stopOurProcesses() {
        withContext(Dispatchers.IO) {
            try {
                supplicantProcess?.destroy()
                supplicantOutput?.close()
                supplicantProcess = null
                supplicantOutput = null

                val killCommands = listOf(
                    "pkill -9 -f $binaryDir",
                    "rm -rf /data/vendor/wifi/wpa/wpsconnect/",
                    "rm -rf /data/misc/wifi/wpsconnect/"
                )

                killCommands.forEach { command ->
                    Shell.cmd(command).exec()
                    delay(200)
                }

            } catch (e: Exception) {
                Log.w(TAG, "Error stopping processes", e)
            }
        }
    }

    fun stopConnection() {
        connectionJob?.cancel()
        scope.launch {
            stopOurProcesses()
            restoreSystemWifi()
        }
    }

    fun cleanup() {
        stopConnection()
        scope.cancel()
    }

    fun isConnecting(): Boolean = connectionJob?.isActive == true
}