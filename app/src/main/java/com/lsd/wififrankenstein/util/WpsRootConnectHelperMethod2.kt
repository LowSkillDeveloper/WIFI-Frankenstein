package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
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
import java.io.FileOutputStream

class WpsRootConnectHelperMethod2(
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

                val success = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    trySystemWpsConnection(network, wpsPin)
                } else {
                    tryRootWpsConnection(network, wpsPin)
                }

                if (success) {
                    callbacks.onConnectionSuccess(network.SSID)
                } else {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_method2_failed))
                }

            } catch (e: Exception) {
                callbacks.onConnectionFailed(context.getString(R.string.wps_root_connection_error, e.message ?: "Unknown"))
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

    private suspend fun ensureWpaCliBinaries(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val (rawResId, targetFileName) = getWpaCliResourceInfo()
                val targetFile = context.getFileStreamPath(targetFileName)

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
                Log.e("WpsMethod2", "Failed to copy WPA CLI binary", e)
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

    private suspend fun trySystemWpsConnection(network: ScanResult, wpsPin: String?): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                var connectionResult = false

                val wpsConfig = WpsInfo().apply {
                    if (wpsPin != null) {
                        setup = WpsInfo.KEYPAD
                        pin = wpsPin
                    } else {
                        setup = WpsInfo.PBC
                    }
                    BSSID = network.BSSID
                }

                wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                    override fun onStarted(pin: String?) {
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_started))
                    }

                    override fun onSucceeded() {
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_succeeded))
                        connectionResult = true
                    }

                    override fun onFailed(reason: Int) {
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_failed, reason))
                        connectionResult = false
                    }
                })

                delay(30000)
                connectionResult
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun tryRootWpsConnection(network: ScanResult, wpsPin: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                callbacks.onConnectionProgress(context.getString(R.string.wps_root_disconnecting))

                val currentNetwork = connectivityManager.activeNetworkInfo
                if (currentNetwork?.type == ConnectivityManager.TYPE_WIFI &&
                    currentNetwork.isConnected &&
                    wifiManager.connectionInfo.bssid.equals(network.BSSID, ignoreCase = true)) {
                    return@withContext true
                }

                if (currentNetwork?.type == ConnectivityManager.TYPE_WIFI && currentNetwork.isConnected) {
                    wifiManager.disconnect()
                    var attempts = 0
                    while (connectivityManager.activeNetworkInfo?.isConnected == true && attempts < 10) {
                        delay(1000)
                        attempts++
                    }
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_executing_command))

                val (_, targetFileName) = getWpaCliResourceInfo()
                val wpaCliPath = context.getFileStreamPath(targetFileName).absolutePath
                val command = if (wpsPin != null) {
                    if (wpsPin.isEmpty()) {
                        "$wpaCliPath IFNAME=wlan0 wps_pin ${network.BSSID} \"\""
                    } else {
                        "$wpaCliPath IFNAME=wlan0 wps_reg ${network.BSSID} $wpsPin"
                    }
                } else {
                    "$wpaCliPath IFNAME=wlan0 wps_pbc ${network.BSSID}"
                }

                callbacks.onLogEntry("Executing: $command")

                val result = Shell.cmd(command).exec()
                if (!result.isSuccess || result.out.isEmpty() || !result.out[0].contains("OK")) {
                    val fallbackCommand = command.replace("IFNAME=wlan0 ", "")
                    callbacks.onLogEntry("Fallback: $fallbackCommand")
                    Shell.cmd(fallbackCommand).exec()
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_waiting_connection))

                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 15000) {
                    val networkInfo = connectivityManager.activeNetworkInfo
                    if (networkInfo?.type == ConnectivityManager.TYPE_WIFI &&
                        networkInfo.isConnected &&
                        wifiManager.connectionInfo.bssid.equals(network.BSSID, ignoreCase = true)) {
                        return@withContext true
                    }
                    delay(1000)
                }

                false
            } catch (e: Exception) {
                if (e.message?.contains("EPIPE") == true || e.message?.contains("Stream closed") == true) {
                    callbacks.onLogEntry(context.getString(R.string.wps_root_epipe_error))
                    false
                } else {
                    throw e
                }
            }
        }
    }
}