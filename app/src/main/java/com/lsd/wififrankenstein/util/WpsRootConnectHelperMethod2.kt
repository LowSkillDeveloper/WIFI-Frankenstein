package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.os.Build
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

class WpsRootConnectHelperMethod2(
    private val context: Context,
    private val callbacks: WpsRootConnectHelper.WpsConnectCallbacks
) {

    private companion object {
        private const val TAG = "WpsMethod2"
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
                    "wpsPin='${wpsPin.orEmpty()}' sdk=${Build.VERSION.SDK_INT} (Q=${Build.VERSION_CODES.Q})"
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
                    callbacks.onConnectionFailed(context.getString(R.string.wps_connect_failed_prepare))
                    return@launch
                }

                val useSystem = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                Log.d(
                    TAG,
                    "connectToNetworkWps: selected method=${if (useSystem) "system WPS API" else "root wpa_cli"}"
                )
                val success = if (useSystem) {
                    trySystemWpsConnection(network, wpsPin)
                } else {
                    tryRootWpsConnection(network, wpsPin)
                }
                Log.d(TAG, "connectToNetworkWps: final result success=$success")

                var psk: String? = null
                var connected = success
                if (success) {
                    Log.d(TAG, "connectToNetworkWps: extracting PSK from system supplicant")
                    psk = WpsPskConnectHelper(context).extractPskFromSystem(
                        ctrlDir = WpsSocketUtils.ctrlDirForWpaCli(),
                        wpaCliPath = wpaCliPath()
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
                    callbacks.onConnectionFailed(context.getString(R.string.wps_root_method2_failed))
                }

            } catch (e: Exception) {
                Log.e(TAG, "connectToNetworkWps: error", e)
                callbacks.onConnectionFailed(
                    context.getString(
                        R.string.wps_root_connection_error,
                        e.message ?: "Unknown"
                    )
                )
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

    private suspend fun ensureWpaCliBinaries(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                NativeWifiBinaries.ensure(context)
                val present = NativeWifiBinaries.allBinariesPresent(context)
                Log.d(TAG, "ensureWpaCliBinaries: present=$present wpaCli=${wpaCliPath()}")
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

    private fun wpaCliPath(): String =
        "${context.filesDir.absolutePath}/${getWpaCliAssetName()}"

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

                Log.d(
                    TAG,
                    "trySystemWpsConnection: startWps setup=${wpsConfig.setup} " +
                            "pin='${wpsConfig.pin.orEmpty()}' bssid='${wpsConfig.BSSID}'"
                )

                wifiManager.startWps(wpsConfig, object : WifiManager.WpsCallback() {
                    override fun onStarted(pin: String?) {
                        Log.d(
                            TAG,
                            "trySystemWpsConnection: WpsCallback.onStarted pin='${pin.orEmpty()}'"
                        )
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_started))
                    }

                    override fun onSucceeded() {
                        Log.d(TAG, "trySystemWpsConnection: WpsCallback.onSucceeded")
                        callbacks.onLogEntry(context.getString(R.string.wps_root_system_wps_succeeded))
                        connectionResult = true
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(TAG, "trySystemWpsConnection: WpsCallback.onFailed reason=$reason")
                        callbacks.onLogEntry(
                            context.getString(
                                R.string.wps_root_system_wps_failed,
                                reason
                            )
                        )
                        connectionResult = false
                    }
                })

                Log.d(TAG, "trySystemWpsConnection: waiting 30000ms for WPS callback")
                delay(30000)
                Log.d(TAG, "trySystemWpsConnection: return connectionResult=$connectionResult")
                connectionResult
            } catch (e: Exception) {
                Log.e(TAG, "trySystemWpsConnection: error", e)
                false
            }
        }
    }

    private suspend fun tryRootWpsConnection(network: ScanResult, wpsPin: String?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                callbacks.onConnectionProgress(context.getString(R.string.wps_root_disconnecting))

                val currentNetwork = connectivityManager.activeNetworkInfo
                val currentBssid = wifiManager.connectionInfo.bssid
                Log.d(
                    TAG,
                    "tryRootWpsConnection: currentNetwork type=${currentNetwork?.type} connected=${currentNetwork?.isConnected} " +
                            "currentBssid='$currentBssid' targetBssid='${network.BSSID}'"
                )

                if (currentNetwork?.type == ConnectivityManager.TYPE_WIFI &&
                    currentNetwork.isConnected &&
                    currentBssid.equals(network.BSSID, ignoreCase = true)
                ) {
                    Log.d(
                        TAG,
                        "tryRootWpsConnection: already connected to target BSSID, returning true"
                    )
                    return@withContext true
                }

                if (currentNetwork?.type == ConnectivityManager.TYPE_WIFI && currentNetwork.isConnected) {
                    Log.d(TAG, "tryRootWpsConnection: disconnecting from current WiFi")
                    wifiManager.disconnect()
                    var attempts = 0
                    while (connectivityManager.activeNetworkInfo?.isConnected == true && attempts < 10) {
                        Log.d(
                            TAG,
                            "tryRootWpsConnection: waiting for disconnect, attempt=${attempts + 1}"
                        )
                        delay(1000)
                        attempts++
                    }
                    Log.d(TAG, "tryRootWpsConnection: disconnect complete after $attempts attempts")
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_executing_command))

                val wpaCliPath = wpaCliPath()
                val ctrlDir = WpsSocketUtils.ctrlDirForWpaCli()
                Log.d(TAG, "tryRootWpsConnection: wpaCliPath='$wpaCliPath' ctrlDir='$ctrlDir'")

                val command = when {
                    wpsPin == NULL_PIN_IDENTIFIER -> {
                        "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pin ${network.BSSID}"
                    }

                    !wpsPin.isNullOrEmpty() -> {
                        "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_reg ${network.BSSID} $wpsPin"
                    }

                    wpsPin != null && wpsPin.isEmpty() -> {
                        "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pin ${network.BSSID} \"\""
                    }

                    else -> {
                        "$wpaCliPath -p$ctrlDir IFNAME=wlan0 wps_pbc ${network.BSSID}"
                    }
                }

                callbacks.onLogEntry("Executing: $command")
                Log.d(TAG, "tryRootWpsConnection: executing command: $command")

                val result = Shell.cmd(command).exec()
                Log.d(
                    TAG,
                    "tryRootWpsConnection: result success=${result.isSuccess} " +
                            "out=${result.out.joinToString("|")} err=${result.err.joinToString("|")}"
                )

                if (!result.isSuccess || result.out.isEmpty() || !result.out[0].contains("OK")) {
                    val fallbackCommand = command.replace("IFNAME=wlan0 ", "")
                    callbacks.onLogEntry("Fallback: $fallbackCommand")
                    Log.d(
                        TAG,
                        "tryRootWpsConnection: primary command not OK, executing fallback: $fallbackCommand"
                    )
                    val fallbackResult = Shell.cmd(fallbackCommand).exec()
                    Log.d(
                        TAG,
                        "tryRootWpsConnection: fallback result success=${fallbackResult.isSuccess} " +
                                "out=${fallbackResult.out.joinToString("|")} err=${
                                    fallbackResult.err.joinToString(
                                        "|"
                                    )
                                }"
                    )
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_root_waiting_connection))

                Log.d(
                    TAG,
                    "tryRootWpsConnection: polling for connection to '${network.BSSID}' up to 15000ms"
                )
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < 15000) {
                    val networkInfo = connectivityManager.activeNetworkInfo
                    val pollBssid = wifiManager.connectionInfo.bssid
                    Log.d(
                        TAG,
                        "tryRootWpsConnection: poll connected=${networkInfo?.isConnected} " +
                                "type=${networkInfo?.type} bssid='$pollBssid' match=${
                                    pollBssid.equals(
                                        network.BSSID,
                                        ignoreCase = true
                                    )
                                }"
                    )
                    if (networkInfo?.type == ConnectivityManager.TYPE_WIFI &&
                        networkInfo.isConnected &&
                        pollBssid.equals(network.BSSID, ignoreCase = true)
                    ) {
                        Log.d(TAG, "tryRootWpsConnection: connected to target, returning true")
                        return@withContext true
                    }
                    delay(1000)
                }

                Log.d(TAG, "tryRootWpsConnection: timeout waiting for connection, returning false")
                false
            } catch (e: Exception) {
                if (e.message?.contains("EPIPE") == true || e.message?.contains("Stream closed") == true) {
                    Log.w(TAG, "tryRootWpsConnection: EPIPE/Stream closed, returning false")
                    callbacks.onLogEntry(context.getString(R.string.wps_root_epipe_error))
                    false
                } else {
                    throw e
                }
            }
        }
    }

    fun onDestroy() {
        scope.cancel()
    }
}