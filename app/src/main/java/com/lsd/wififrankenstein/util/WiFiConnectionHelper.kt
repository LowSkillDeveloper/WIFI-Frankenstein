package com.lsd.wififrankenstein.util

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class WiFiConnectionHelper(private val context: Context) {

    interface ConnectionCallback {
        fun onConnectionStarted()
        fun onConnectionSuccess(ssid: String)
        fun onConnectionFailed(error: String)
        fun onConnectionTimeout()
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectionReceiver: BroadcastReceiver? = null

    suspend fun connectToNetwork(
        scanResult: ScanResult,
        password: String,
        callback: ConnectionCallback
    ) = suspendCancellableCoroutine<Boolean> { continuation ->

        if (!hasRequiredPermissions()) {
            callback.onConnectionFailed("Missing required permissions")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        callback.onConnectionStarted()

        val originalConnectedNetwork = getCurrentConnectedNetwork()

        timeoutRunnable = Runnable {
            cleanup()
            callback.onConnectionTimeout()
            continuation.resume(false)
        }
        handler.postDelayed(timeoutRunnable!!, 30000)

        continuation.invokeOnCancellation {
            cleanup()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithNetworkSuggestion(scanResult, password, callback, continuation, originalConnectedNetwork)
        } else {
            connectWithWifiConfiguration(scanResult, password, callback, continuation, originalConnectedNetwork)
        }
    }

    private fun connectWithNetworkSuggestion(
        scanResult: ScanResult,
        password: String,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?
    ) {
        try {
            val suggestionBuilder = WifiNetworkSuggestion.Builder()
                .setSsid(scanResult.SSID)
                .setBssid(android.net.MacAddress.fromString(scanResult.BSSID))

            when {
                scanResult.capabilities.contains("WPA3") -> {
                    suggestionBuilder.setWpa3Passphrase(password)
                }
                scanResult.capabilities.contains("WPA2") || scanResult.capabilities.contains("WPA") -> {
                    suggestionBuilder.setWpa2Passphrase(password)
                }
                scanResult.capabilities.contains("WEP") -> {
                    callback.onConnectionFailed("WEP networks are not supported on Android 10+")
                    continuation.resume(false)
                    return
                }
                else -> {
                    callback.onConnectionFailed("Open networks don't require password")
                    continuation.resume(false)
                    return
                }
            }

            val suggestion = suggestionBuilder.build()
            val suggestions = listOf(suggestion)

            val status = wifiManager.addNetworkSuggestions(suggestions)
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                callback.onConnectionFailed("Failed to add network suggestion")
                continuation.resume(false)
                return
            }

            monitorConnection(scanResult.SSID, callback, continuation, originalNetwork)

        } catch (e: Exception) {
            callback.onConnectionFailed("Error: ${e.message}")
            continuation.resume(false)
        }
    }

    private fun connectWithWifiConfiguration(
        scanResult: ScanResult,
        password: String,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?
    ) {
        try {
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"${scanResult.SSID}\""
                BSSID = scanResult.BSSID

                when {
                    scanResult.capabilities.contains("WEP") -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                        allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                        allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                        allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                        allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                        allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                        wepKeys[0] = "\"$password\""
                        wepTxKeyIndex = 0
                    }
                    scanResult.capabilities.contains("WPA") || scanResult.capabilities.contains("PSK") -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        preSharedKey = "\"$password\""
                    }
                    else -> {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                }
            }

            val networkId = wifiManager.addNetwork(wifiConfig)
            if (networkId == -1) {
                callback.onConnectionFailed("Failed to add network configuration")
                continuation.resume(false)
                return
            }

            val disconnected = wifiManager.disconnect()
            if (!disconnected) {
                wifiManager.removeNetwork(networkId)
                callback.onConnectionFailed("Failed to disconnect from current network")
                continuation.resume(false)
                return
            }

            val enabled = wifiManager.enableNetwork(networkId, true)
            if (!enabled) {
                wifiManager.removeNetwork(networkId)
                callback.onConnectionFailed("Failed to enable network")
                continuation.resume(false)
                return
            }

            val reconnected = wifiManager.reconnect()
            if (!reconnected) {
                wifiManager.removeNetwork(networkId)
                callback.onConnectionFailed("Failed to initiate connection")
                continuation.resume(false)
                return
            }

            monitorConnectionLegacy(scanResult.SSID, networkId, callback, continuation, originalNetwork)

        } catch (e: Exception) {
            callback.onConnectionFailed("Error: ${e.message}")
            continuation.resume(false)
        }
    }

    private fun monitorConnection(
        targetSSID: String,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val networkInfo = connectivityManager.getNetworkCapabilities(network)
                    if (networkInfo?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        val currentSSID = getCurrentConnectedNetwork()
                        if (currentSSID == targetSSID) {
                            cleanup()
                            callback.onConnectionSuccess(targetSSID)
                            continuation.resume(true)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                }
            }

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } else {
            monitorConnectionLegacy(targetSSID, -1, callback, continuation, originalNetwork)
        }
    }

    private fun monitorConnectionLegacy(
        targetSSID: String,
        networkId: Int,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?
    ) {
        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val currentSSID = getCurrentConnectedNetwork()
                        if (currentSSID == targetSSID) {
                            cleanup()
                            callback.onConnectionSuccess(targetSSID)
                            continuation.resume(true)
                        }
                    }
                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        val error = intent.getParcelableExtra<android.net.wifi.SupplicantState>(WifiManager.EXTRA_SUPPLICANT_ERROR)
                        if (error != null) {
                            cleanup()
                            if (networkId != -1) {
                                wifiManager.removeNetwork(networkId)
                            }
                            callback.onConnectionFailed("Authentication failed")
                            continuation.resume(false)
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }

        context.registerReceiver(connectionReceiver, filter)
    }

    private fun getCurrentConnectedNetwork(): String? {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            wifiInfo?.ssid?.replace("\"", "")
        } catch (e: Exception) {
            null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        networkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        networkCallback = null

        connectionReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {

            }
        }
        connectionReceiver = null
    }
}