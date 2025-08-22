package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.lsd.wififrankenstein.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class WpsRootConnectHelperMethod3(
    private val context: Context,
    private val callbacks: WpsRootConnectHelper.WpsConnectCallbacks
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var connectionJob: Job? = null

    fun connectToNetworkWps(network: ScanResult, wpsPin: String? = null) {
        if (connectionJob?.isActive == true) {
            return
        }

        connectionJob = scope.launch {
            try {
                callbacks.onConnectionProgress(context.getString(R.string.wps_root_starting_connection, network.SSID))

                if (!checkRootAccess()) {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_no_root))
                    return@launch
                }

                if (!ensureWpaCliBinaries()) {
                    callbacks.onConnectionFailed("Failed to prepare WPA CLI binaries")
                    return@launch
                }

                if (!wifiManager.isWifiEnabled) {
                    callbacks.onConnectionProgress("Enabling WiFi...")
                    wifiManager.isWifiEnabled = true
                    delay(2000)
                }

                val success = connectWithWpsRoot(network.BSSID, wpsPin)

                if (success) {
                    callbacks.onConnectionSuccess(network.SSID)
                } else {
                    callbacks.onConnectionFailed("Method 3 failed")
                }

            } catch (e: Exception) {
                callbacks.onConnectionFailed("Method 3 error: ${e.message}")
            } finally {
                connectionJob = null
            }
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            shell.isRoot && shell.isAlive
        } catch (e: Exception) {
            false
        }
    }

    private fun isSystemWpaCliAvailable(): Boolean {
        return try {
            val result = Shell.cmd("wpa_cli -v").exec()
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun ensureWpaCliBinaries(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val (rawResId, targetFileName) = getWpaCliResourceInfo()
                val targetFile = context.getFileStreamPath("${targetFileName}_method3")

                if (!targetFile.exists()) {
                    context.resources.openRawResource(rawResId).use { inputStream ->
                        FileOutputStream(targetFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    targetFile.setExecutable(true)
                }

                true
            } catch (e: Exception) {
                Log.e("WpsMethod3", "Failed to copy WPA CLI binary", e)
                false
            }
        }
    }

    private fun getWpaCliResourceInfo(): Pair<Int, String> {
        return when {
            Build.VERSION.SDK_INT >= 23 -> Pair(R.raw.wpa_cli_23_method2, "wpa_cli_23")
            Build.VERSION.SDK_INT >= 19 -> Pair(R.raw.wpa_cli_19_method2, "wpa_cli_19")
            Build.VERSION.SDK_INT >= 16 -> Pair(R.raw.wpa_cli_16_method2, "wpa_cli_16")
            else -> Pair(R.raw.wpa_cli_14_method2, "wpa_cli_14")
        }
    }

    private suspend fun getWpaCliPath(): String = withContext(Dispatchers.IO) {
        if (isSystemWpaCliAvailable()) {
            return@withContext "wpa_cli"
        }

        val (_, targetFileName) = getWpaCliResourceInfo()
        return@withContext context.getFileStreamPath("${targetFileName}_method3").absolutePath
    }

    private suspend fun connectWithWpsRoot(bssid: String?, pin: String?): Boolean = withContext(Dispatchers.IO) {
        if (bssid.isNullOrEmpty()) {
            callbacks.onLogEntry("BSSID is null or empty")
            return@withContext false
        }

        val wpaCliPath = getWpaCliPath()
        val file = File(wpaCliPath)

        if (!file.exists() || !file.canExecute()) {
            callbacks.onLogEntry("wpa_cli file does not exist or is not executable")
            return@withContext false
        }

        callbacks.onConnectionProgress("Executing WPS command...")

        val command = if (!pin.isNullOrEmpty()) {
            "$wpaCliPath IFNAME=wlan0 wps_pin $bssid $pin"
        } else if (pin != null && pin.isEmpty()) {
            "$wpaCliPath IFNAME=wlan0 wps_pin $bssid \"\""
        } else {
            "$wpaCliPath IFNAME=wlan0 wps_pbc $bssid"
        }

        callbacks.onLogEntry("Executing command: $command")

        try {
            val result = Shell.cmd(command).exec()

            if (result.isSuccess) {
                callbacks.onLogEntry("WPS command executed successfully")
                callbacks.onConnectionProgress("Waiting for connection...")

                delay(15000)

                return@withContext checkWiFiConnectionStatus(bssid)
            } else {
                callbacks.onLogEntry("WPS command failed: ${result.err.joinToString()}")
                return@withContext false
            }

        } catch (e: Exception) {
            callbacks.onLogEntry("Error executing WPS command: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun checkWiFiConnectionStatus(expectedBSSID: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val wifiInfo = wifiManager.connectionInfo
            val networkInfo = connectivityManager.activeNetworkInfo

            if (networkInfo != null && networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                val currentBSSID = wifiInfo.bssid
                if (currentBSSID?.equals(expectedBSSID, ignoreCase = true) == true) {
                    callbacks.onLogEntry("Connected to target network: ${wifiInfo.ssid}")
                    true
                } else {
                    callbacks.onLogEntry("Connected to different network. Expected: $expectedBSSID, Current: $currentBSSID")
                    false
                }
            } else {
                callbacks.onLogEntry("Not connected to WiFi")
                false
            }
        } catch (e: Exception) {
            callbacks.onLogEntry("Error checking connection status: ${e.message}")
            false
        }
    }
}