package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.WpsMethodSelector.Companion.NULL_PIN_IDENTIFIER
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class WpsRootConnectHelperMethod3(
    private val context: Context,
    private val callbacks: WpsRootConnectHelper.WpsConnectCallbacks
) {

    private companion object {
        private const val TAG = "WpsMethod3"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var connectionJob: Job? = null

    fun connectToNetworkWps(network: ScanResult, wpsPin: String? = null) {
        if (connectionJob?.isActive == true) {
            Log.w(TAG, "connectToNetworkWps: connection already in progress, ignoring new request")
            return
        }

        Log.d(
            TAG,
            "connectToNetworkWps: entry ssid='${network.SSID}' bssid='${network.BSSID}' " +
                    "wpsPin='${wpsPin.orEmpty()}'"
        )

        connectionJob = scope.launch {
            try {
                callbacks.onConnectionProgress(
                    context.getString(
                        R.string.wps_root_starting_connection,
                        network.SSID
                    )
                )

                if (!checkRootAccess()) {
                    Log.e(TAG, "connectToNetworkWps: root access not available, aborting")
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_no_root))
                    return@launch
                }

                if (!ensureWpaCliBinaries()) {
                    Log.e(TAG, "connectToNetworkWps: failed to prepare WPA CLI binaries, aborting")
                    callbacks.onConnectionFailed("Failed to prepare WPA CLI binaries")
                    return@launch
                }

                if (!wifiManager.isWifiEnabled) {
                    Log.d(TAG, "connectToNetworkWps: WiFi disabled, enabling")
                    callbacks.onConnectionProgress("Enabling WiFi...")
                    wifiManager.isWifiEnabled = true
                    delay(2000)
                }
                Log.d(TAG, "connectToNetworkWps: isWifiEnabled=${wifiManager.isWifiEnabled}")

                val success = connectWithWpsRoot(network.BSSID, wpsPin)
                Log.d(TAG, "connectToNetworkWps: final result success=$success")

                var psk: String? = null
                var connected = success
                if (success) {
                    Log.d(TAG, "connectToNetworkWps: extracting PSK from system supplicant")
                    psk = WpsPskConnectHelper(context).extractPskFromSystem(
                        ctrlDir = WpsSocketUtils.ctrlDirForWpaCli(),
                        wpaCliPath = getWpaCliPath()
                    ) { msg -> callbacks.onLogEntry(msg) }

                    if (psk != null) {
                        Log.d(TAG, "connectToNetworkWps: attempting PSK-based Android connect")
                        connected = WpsPskConnectHelper(context).connectWithPsk(
                            network,
                            psk!!
                        ) { msg -> callbacks.onLogEntry(msg) }
                    }
                }

                callbacks.onWpsResult(wpsPin, psk)

                if (connected) {
                    callbacks.onConnectionSuccess(network.SSID)
                } else {
                    callbacks.onConnectionFailed("Recommended Method failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "connectToNetworkWps: error", e)
                callbacks.onConnectionFailed("Recommended Method error: ${e.message}")
            } finally {
                connectionJob = null
            }
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val shell = Shell.getShell()
            val result = shell.isRoot && shell.isAlive
            Log.d(
                TAG,
                "checkRootAccess: isRoot=${shell.isRoot} isAlive=${shell.isAlive} result=$result"
            )
            result
        } catch (e: Exception) {
            Log.w(TAG, "checkRootAccess: error", e)
            false
        }
    }

    private fun isSystemWpaCliAvailable(): Boolean {
        return try {
            val result = Shell.cmd("wpa_cli -v").exec()
            Log.d(
                TAG,
                "isSystemWpaCliAvailable: success=${result.isSuccess} out=${
                    result.out.joinToString("|")
                }"
            )
            result.isSuccess
        } catch (e: Exception) {
            Log.w(TAG, "isSystemWpaCliAvailable: error", e)
            false
        }
    }

    private suspend fun ensureWpaCliBinaries(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                NativeWifiBinaries.ensure(context)
                val present = NativeWifiBinaries.allBinariesPresent(context)
                Log.d(TAG, "ensureWpaCliBinaries: present=$present")
                true
            } catch (e: Exception) {
                Log.e(TAG, "ensureWpaCliBinaries: failed to copy WPA CLI binary", e)
                false
            }
        }
    }

    private fun getWpaCliAssetName(): String {
        val name = if (NativeWifiBinaries.archSuffix().isEmpty()) {
            NativeWifiBinaries.WPA_CLI
        } else {
            NativeWifiBinaries.WPA_CLI_32
        }
        Log.d(
            TAG,
            "getWpaCliAssetName: archSuffix='${NativeWifiBinaries.archSuffix()}' name='$name'"
        )
        return name
    }

    private suspend fun getWpaCliPath(): String = withContext(Dispatchers.IO) {
        if (isSystemWpaCliAvailable()) {
            Log.d(TAG, "getWpaCliPath: using system wpa_cli")
            return@withContext "wpa_cli"
        }
        val path = "${context.filesDir.absolutePath}/${getWpaCliAssetName()}"
        Log.d(TAG, "getWpaCliPath: using bundled wpa_cli at '$path'")
        return@withContext path
    }

    private suspend fun connectWithWpsRoot(bssid: String?, pin: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (bssid.isNullOrEmpty()) {
                Log.w(TAG, "connectWithWpsRoot: BSSID is null or empty, aborting")
                callbacks.onLogEntry("BSSID is null or empty")
                return@withContext false
            }

            Log.d(TAG, "connectWithWpsRoot: entry bssid='$bssid' pin='${pin.orEmpty()}'")

            val wpaCliPath = getWpaCliPath()
            val file = File(wpaCliPath)

            if (!file.exists() || !file.canExecute()) {
                Log.e(
                    TAG,
                    "connectWithWpsRoot: wpa_cli missing or not executable: '$wpaCliPath' exists=${file.exists()} exec=${file.canExecute()}"
                )
                callbacks.onLogEntry("wpa_cli file does not exist or is not executable")
                return@withContext false
            }

            callbacks.onConnectionProgress("Executing WPS command...")

            val ctrlDir = WpsSocketUtils.ctrlDirForWpaCli()
            Log.d(TAG, "connectWithWpsRoot: wpaCliPath='$wpaCliPath' ctrlDir='$ctrlDir'")

            val command = when {
                pin == NULL_PIN_IDENTIFIER -> {
                    "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pin $bssid"
                }

                !pin.isNullOrEmpty() -> {
                    "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pin $bssid $pin"
                }

                pin != null && pin.isEmpty() -> {
                    "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pin $bssid \"\""
                }

                else -> {
                    "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pbc $bssid"
                }
            }

            callbacks.onLogEntry("Executing command: $command")
            Log.d(TAG, "connectWithWpsRoot: executing command: $command")

            try {
                val result = Shell.cmd(command).exec()
                Log.d(
                    TAG,
                    "connectWithWpsRoot: result success=${result.isSuccess} " +
                            "out=${result.out.joinToString("|")} err=${result.err.joinToString("|")}"
                )

                if (result.isSuccess) {
                    callbacks.onLogEntry("WPS command executed successfully")
                    callbacks.onConnectionProgress("Waiting for connection...")

                    Log.d(
                        TAG,
                        "connectWithWpsRoot: command accepted, waiting 15000ms then checking connection"
                    )
                    delay(15000)

                    return@withContext checkWiFiConnectionStatus(bssid)
                } else {
                    callbacks.onLogEntry("WPS command failed: ${result.err.joinToString()}")
                    return@withContext false
                }

            } catch (e: Exception) {
                Log.e(TAG, "connectWithWpsRoot: error executing WPS command", e)
                callbacks.onLogEntry("Error executing WPS command: ${e.message}")
                return@withContext false
            }
        }

    private suspend fun checkWiFiConnectionStatus(expectedBSSID: String): Boolean =
        withContext(Dispatchers.IO) {
            return@withContext try {
                val wifiInfo = wifiManager.connectionInfo
                val networkInfo = connectivityManager.activeNetworkInfo
                Log.d(
                    TAG,
                    "checkWiFiConnectionStatus: expected='$expectedBSSID' wifiInfo.ssid='${wifiInfo?.ssid}' " +
                            "wifiInfo.bssid='${wifiInfo?.bssid}' networkInfo connected=${networkInfo?.isConnected} " +
                            "type=${networkInfo?.type}"
                )

                if (networkInfo != null && networkInfo.isConnected && networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                    val currentBSSID = wifiInfo.bssid
                    if (currentBSSID?.equals(expectedBSSID, ignoreCase = true) == true) {
                        callbacks.onLogEntry("Connected to target network: ${wifiInfo.ssid}")
                        Log.d(
                            TAG,
                            "checkWiFiConnectionStatus: MATCH — connected to target, returning true"
                        )
                        true
                    } else {
                        Log.d(
                            TAG,
                            "checkWiFiConnectionStatus: MISMATCH — connected to different network, returning false"
                        )
                        callbacks.onLogEntry("Connected to different network. Expected: $expectedBSSID, Current: $currentBSSID")
                        false
                    }
                } else {
                    Log.d(TAG, "checkWiFiConnectionStatus: not connected to WiFi, returning false")
                    callbacks.onLogEntry("Not connected to WiFi")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkWiFiConnectionStatus: error", e)
                callbacks.onLogEntry("Error checking connection status: ${e.message}")
                false
            }
        }

    fun onDestroy() {
        scope.cancel()
    }
}