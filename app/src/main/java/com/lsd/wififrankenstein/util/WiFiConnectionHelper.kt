package com.lsd.wififrankenstein.util

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
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
        fun onSuggestionApprovalRequired() {}
    }

    interface DisconnectionCallback {
        fun onDisconnectionSuccess()
        fun onDisconnectionFailed(error: String)
        fun onNetworkForgotten()
    }

    companion object {
        private const val TAG = "WiFiConnect"
    }

    private val dev: String =
        "SDK=${Build.VERSION.SDK_INT}(${Build.VERSION.RELEASE}) ${Build.MANUFACTURER} ${Build.MODEL}"

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectionReceiver: BroadcastReceiver? = null
    private var completed = false

    private val prefs = context.getSharedPreferences("wifi_connection_helper", Context.MODE_PRIVATE)
    private val managedSsidsKey = "managed_ssids"

    private fun getManagedSsids(): MutableSet<String> =
        prefs.getStringSet(managedSsidsKey, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun trackManagedSsid(ssid: String) {
        val set = getManagedSsids()
        set.add(ssid)
        prefs.edit().putStringSet(managedSsidsKey, set).apply()
        Log.d(TAG, "[trackManagedSsid] added ssid='$ssid' total=${set.size} $dev")
    }

    private fun untrackManagedSsid(ssid: String) {
        val set = getManagedSsids()
        set.remove(ssid)
        prefs.edit().putStringSet(managedSsidsKey, set).apply()
        Log.d(TAG, "[untrackManagedSsid] removed ssid='$ssid' total=${set.size} $dev")
    }

    private fun cleanup() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null

        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                Log.d(TAG, "[cleanup] unregistered network callback")
            } catch (e: Exception) {
                Log.w(TAG, "[cleanup] Failed to unregister network callback", e)
            }
        }
        networkCallback = null

        connectionReceiver?.let {
            try {
                context.unregisterReceiver(it)
                Log.d(TAG, "[cleanup] unregistered broadcast receiver")
            } catch (e: Exception) {
                Log.w(TAG, "[cleanup] Failed to unregister receiver", e)
            }
        }
        connectionReceiver = null
    }

    private fun completeWithFailure(
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        error: String
    ) {
        if (completed) {
            Log.d(TAG, "[completeWithFailure] IGNORED (already completed) error='$error' $dev")
            return
        }
        completed = true
        cleanup()
        Log.d(TAG, "[completeWithFailure] error='$error' $dev")
        callback.onConnectionFailed(error)
        continuation.resume(false)
    }

    private fun completeWithTimeout(
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>
    ) {
        if (completed) {
            Log.d(TAG, "[completeWithTimeout] IGNORED (already completed) $dev")
            return
        }
        completed = true
        cleanup()
        Log.d(TAG, "[completeWithTimeout] timeout fired $dev")
        callback.onConnectionTimeout()
        continuation.resume(false)
    }

    private fun completeWithSuccess(
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        ssid: String
    ) {
        if (completed) {
            Log.d(TAG, "[completeWithSuccess] IGNORED (already completed) ssid='$ssid' $dev")
            return
        }
        completed = true
        cleanup()
        Log.d(TAG, "[completeWithSuccess] ssid='$ssid' $dev")
        callback.onConnectionSuccess(ssid)
        continuation.resume(true)
    }

    suspend fun connectToNetwork(
        scanResult: ScanResult,
        password: String,
        callback: ConnectionCallback
    ) = suspendCancellableCoroutine<Boolean> { continuation ->

        completed = false

        Log.d(
            TAG,
            "[connectToNetwork] START ssid='${scanResult.SSID}' bssid='${scanResult.BSSID}' " +
                    "caps='${scanResult.capabilities}' pwdLen=${password.length} $dev"
        )

        val hasPerms = hasRequiredPermissions()
        Log.d(TAG, "[connectToNetwork] hasRequiredPermissions=$hasPerms $dev")
        if (!hasPerms) {
            completeWithFailure(callback, continuation, "Missing required permissions")
            return@suspendCancellableCoroutine
        }

        val sdkInt = Build.VERSION.SDK_INT
        val locationEnabled = if (sdkInt >= Build.VERSION_CODES.Q) isLocationEnabled() else true
        Log.d(TAG, "[connectToNetwork] sdk=$sdkInt locationEnabled=$locationEnabled $dev")
        if (sdkInt >= Build.VERSION_CODES.Q && !locationEnabled) {
            completeWithFailure(
                callback,
                continuation,
                "Location services must be enabled to connect"
            )
            return@suspendCancellableCoroutine
        }

        callback.onConnectionStarted()
        Log.d(TAG, "[connectToNetwork] onConnectionStarted fired $dev")

        val originalConnectedNetwork = getCurrentConnectedNetwork()
        Log.d(TAG, "[connectToNetwork] originalConnectedNetwork='$originalConnectedNetwork' $dev")

        timeoutRunnable = Runnable {
            completeWithTimeout(callback, continuation)
        }
        handler.postDelayed(timeoutRunnable!!, 30000)
        Log.d(TAG, "[connectToNetwork] timeout scheduled 30000ms $dev")

        continuation.invokeOnCancellation {
            Log.d(TAG, "[connectToNetwork] coroutine cancelled, cleanup $dev")
            completed = true
            cleanup()
        }


        Log.d(TAG, "[connectToNetwork] branch=LEGACY_FIRST (sdk=$sdkInt) $dev")
        connectWithWifiConfiguration(
            scanResult,
            password,
            callback,
            continuation,
            originalConnectedNetwork
        )
    }

    private fun connectWithNetworkSuggestion(
        scanResult: ScanResult,
        password: String,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?
    ) {
        try {
            val caps = scanResult.capabilities
            Log.d(TAG, "[connectWithNetworkSuggestion] caps='$caps' pwdLen=${password.length} $dev")

            if (!isValidPskPassword(password)) {
                completeWithFailure(
                    callback,
                    continuation,
                    "Password must be between 8 and 63 characters"
                )
                return
            }

            val suggestions = mutableListOf<WifiNetworkSuggestion>()
            when {
                caps.contains("WPA3") || caps.contains("SAE") -> {
                    Log.d(
                        TAG,
                        "[connectWithNetworkSuggestion] security=WPA3/SAE (dual WPA2+WPA3) $dev"
                    )
                    suggestions.add(
                        WifiNetworkSuggestion.Builder()
                            .setSsid(scanResult.SSID)
                            .setWpa2Passphrase(password)
                            .build()
                    )
                    suggestions.add(
                        WifiNetworkSuggestion.Builder()
                            .setSsid(scanResult.SSID)
                            .setWpa3Passphrase(password)
                            .build()
                    )
                }

                caps.contains("WPA2") || caps.contains("WPA") -> {
                    Log.d(TAG, "[connectWithNetworkSuggestion] security=WPA2/WPA $dev")
                    suggestions.add(
                        WifiNetworkSuggestion.Builder()
                            .setSsid(scanResult.SSID)
                            .setWpa2Passphrase(password)
                            .build()
                    )
                }

                caps.contains("WEP") -> {
                    Log.d(
                        TAG,
                        "[connectWithNetworkSuggestion] security=WEP -> unsupported on 12+ $dev"
                    )
                    completeWithFailure(
                        callback,
                        continuation,
                        "WEP networks are not supported on Android 12+"
                    )
                    return
                }

                else -> {
                    Log.d(TAG, "[connectWithNetworkSuggestion] security=OPEN/UNKNOWN $dev")
                    completeWithFailure(
                        callback,
                        continuation,
                        "Open networks don't require password"
                    )
                    return
                }
            }

            removeExistingSuggestion(scanResult.SSID)

            val status = wifiManager.addNetworkSuggestions(suggestions)
            Log.d(
                TAG,
                "[connectWithNetworkSuggestion] addNetworkSuggestions count=${suggestions.size} status=$status $dev"
            )
            if (status != WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS &&
                status != WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
            ) {
                completeWithFailure(
                    callback,
                    continuation,
                    "Failed to add network suggestion (code $status)"
                )
                return
            }

            Log.d(
                TAG,
                "[connectWithNetworkSuggestion] monitoring connection for ssid='${scanResult.SSID}' $dev"
            )
            monitorConnection(scanResult.SSID, callback, continuation, originalNetwork)

        } catch (e: Throwable) {
            val msg = if (e is NoSuchMethodError || e is NoClassDefFoundError) {
                "Wi-Fi suggestion API is not available on this device/Android version"
            } else {
                "Error: ${e.message}"
            }
            Log.e(TAG, "[connectWithNetworkSuggestion] EXCEPTION: ${e.message} $dev", e)
            completeWithFailure(callback, continuation, msg)
        }
    }

    private fun removeExistingSuggestion(ssid: String) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
            val existing = wifiManager.networkSuggestions.filter { it.ssid == ssid }
            Log.d(
                TAG,
                "[removeExistingSuggestion] found ${existing.size} existing suggestion(s) for ssid='$ssid' $dev"
            )
            if (existing.isNotEmpty()) {
                wifiManager.removeNetworkSuggestions(existing)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[removeExistingSuggestion] failed: ${e.message} $dev", e)
        }
    }

    private fun isValidPskPassword(password: String): Boolean = password.length in 8..63

    private fun buildWifiConfig(
        scanResult: ScanResult,
        password: String,
        withBssid: Boolean = true
    ): Pair<WifiConfiguration?, String> {
        val caps = scanResult.capabilities
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"${scanResult.SSID}\""
            if (withBssid) {
                BSSID = scanResult.BSSID
            }
        }

        var security = "OPEN"
        when {
            caps.contains("WPA3") || caps.contains("SAE") -> {
                security = "WPA3_SAE"
                if (!isValidPskPassword(password)) return Pair(null, security)
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                wifiConfig.allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                wifiConfig.preSharedKey = "\"$password\""
            }

            caps.contains("WEP") -> {
                security = "WEP"
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                wifiConfig.allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                wifiConfig.allowedProtocols.set(WifiConfiguration.Protocol.WPA)
                wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                wifiConfig.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
                wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
                wifiConfig.wepKeys[0] = "\"$password\""
                wifiConfig.wepTxKeyIndex = 0
            }

            caps.contains("WPA") || caps.contains("PSK") -> {
                security = "WPA_PSK"
                if (!isValidPskPassword(password)) return Pair(null, security)
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                wifiConfig.preSharedKey = "\"$password\""
            }

            else -> {
                security = "OPEN"
                wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
        }
        return Pair(wifiConfig, security)
    }

    private fun tryAddOrUpdateExisting(ssid: String, config: WifiConfiguration): Int {
        return try {
            val configurations = wifiManager.configuredNetworks ?: emptyList()
            val existing = configurations.firstOrNull { it.SSID?.replace("\"", "") == ssid }
            Log.d(
                TAG,
                "[tryAddOrUpdateExisting] ssid='$ssid' configured=${configurations.size} existing=${existing != null} $dev"
            )
            if (existing != null) {
                val updateConfig = WifiConfiguration(config)
                updateConfig.networkId = existing.networkId
                val updatedId = wifiManager.addNetwork(updateConfig)
                Log.d(
                    TAG,
                    "[tryAddOrUpdateExisting] update networkId=${existing.networkId} -> $updatedId $dev"
                )
                if (updatedId != -1) return updatedId
            }
            -1
        } catch (e: Exception) {
            Log.w(TAG, "[tryAddOrUpdateExisting] EXCEPTION: ${e.message} $dev", e)
            -1
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
            val caps = scanResult.capabilities
            Log.d(TAG, "[connectWithWifiConfiguration] caps='$caps' pwdLen=${password.length} $dev")

            val (wifiConfig, detectedSecurity) = buildWifiConfig(scanResult, password)
            Log.d(TAG, "[connectWithWifiConfiguration] detectedSecurity=$detectedSecurity $dev")
            if (wifiConfig == null) {
                completeWithFailure(
                    callback,
                    continuation,
                    "Password must be between 8 and 63 characters"
                )
                return
            }

            var networkId = wifiManager.addNetwork(wifiConfig)
            Log.d(TAG, "[connectWithWifiConfiguration] addNetwork -> networkId=$networkId $dev")
            if (networkId == -1) {
                Log.d(
                    TAG,
                    "[connectWithWifiConfiguration] addNetwork failed, retrying without BSSID $dev"
                )
                val noBssidConfig = buildWifiConfig(scanResult, password, withBssid = false).first
                if (noBssidConfig != null) {
                    networkId = wifiManager.addNetwork(noBssidConfig)
                    Log.d(
                        TAG,
                        "[connectWithWifiConfiguration] addNetwork(no BSSID) -> networkId=$networkId $dev"
                    )
                }
            }
            if (networkId == -1) {
                Log.d(
                    TAG,
                    "[connectWithWifiConfiguration] addNetwork failed, trying existing config fallback $dev"
                )
                networkId = tryAddOrUpdateExisting(scanResult.SSID, wifiConfig)
                Log.d(TAG, "[connectWithWifiConfiguration] fallback -> networkId=$networkId $dev")
            }
            if (networkId == -1) {
                fallbackToSuggestion(
                    scanResult,
                    password,
                    callback,
                    continuation,
                    originalNetwork,
                    "addNetwork failed"
                )
                return
            }
            trackManagedSsid(scanResult.SSID)

            val disconnected = wifiManager.disconnect()
            Log.d(TAG, "[connectWithWifiConfiguration] disconnect -> $disconnected $dev")
            if (!disconnected) {
                wifiManager.removeNetwork(networkId)
                fallbackToSuggestion(
                    scanResult,
                    password,
                    callback,
                    continuation,
                    originalNetwork,
                    "disconnect failed"
                )
                return
            }

            val enabled = wifiManager.enableNetwork(networkId, true)
            Log.d(TAG, "[connectWithWifiConfiguration] enableNetwork($networkId) -> $enabled $dev")
            if (!enabled) {
                wifiManager.removeNetwork(networkId)
                fallbackToSuggestion(
                    scanResult,
                    password,
                    callback,
                    continuation,
                    originalNetwork,
                    "enableNetwork failed"
                )
                return
            }

            val reconnected = wifiManager.reconnect()
            Log.d(TAG, "[connectWithWifiConfiguration] reconnect -> $reconnected $dev")
            if (!reconnected) {
                wifiManager.removeNetwork(networkId)
                fallbackToSuggestion(
                    scanResult,
                    password,
                    callback,
                    continuation,
                    originalNetwork,
                    "reconnect failed"
                )
                return
            }

            Log.d(
                TAG,
                "[connectWithWifiConfiguration] monitoring legacy for ssid='${scanResult.SSID}' networkId=$networkId $dev"
            )
            monitorConnectionLegacy(
                scanResult.SSID,
                networkId,
                callback,
                continuation,
                originalNetwork
            )

        } catch (e: Exception) {
            Log.e(TAG, "[connectWithWifiConfiguration] EXCEPTION: ${e.message} $dev", e)
            completeWithFailure(callback, continuation, "Error: ${e.message}")
        }
    }

    private fun fallbackToSuggestion(
        scanResult: ScanResult,
        password: String,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?,
        reason: String
    ) {
        val sdk = Build.VERSION.SDK_INT
        Log.d(
            TAG,
            "[connectWithWifiConfiguration] falling back to network suggestion ($reason) sdk=$sdk $dev"
        )
        if (sdk <= Build.VERSION_CODES.R) {
            callback.onSuggestionApprovalRequired()
        }
        connectWithNetworkSuggestion(scanResult, password, callback, continuation, originalNetwork)
    }

    private fun monitorConnection(
        targetSSID: String,
        callback: ConnectionCallback,
        continuation: kotlin.coroutines.Continuation<Boolean>,
        originalNetwork: String?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Log.d(
                TAG,
                "[monitorConnection] registering NetworkCallback targetSSID='$targetSSID' $dev"
            )

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val networkInfo = connectivityManager.getNetworkCapabilities(network)
                    if (networkInfo?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        val currentSSID = getCurrentConnectedNetwork()
                        Log.d(
                            TAG,
                            "[monitorConnection] onAvailable currentSSID='$currentSSID' target='$targetSSID' $dev"
                        )
                        if (currentSSID == targetSSID) {
                            completeWithSuccess(callback, continuation, targetSSID)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "[monitorConnection] onLost network=$network $dev")
                    super.onLost(network)
                }
            }

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } else {
            Log.d(TAG, "[monitorConnection] SDK<${Build.VERSION_CODES.N} -> legacy monitor $dev")
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
        Log.d(
            TAG,
            "[monitorConnectionLegacy] registering BroadcastReceiver targetSSID='$targetSSID' networkId=$networkId $dev"
        )

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        val currentSSID = getCurrentConnectedNetwork()
                        val currentNetworkId = wifiManager.connectionInfo?.networkId ?: -1
                        Log.d(
                            TAG,
                            "[monitorConnectionLegacy] NETWORK_STATE_CHANGED currentSSID='$currentSSID' target='$targetSSID' " +
                                    "currentNetworkId=$currentNetworkId targetNetworkId=$networkId $dev"
                        )
                        val ssidMatch = currentSSID == targetSSID
                        val idMatch = networkId != -1 && currentNetworkId == networkId
                        if (ssidMatch || idMatch) {
                            completeWithSuccess(callback, continuation, targetSSID)
                        }
                    }

                    WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                        val errorCode = intent.getIntExtra(
                            WifiManager.EXTRA_SUPPLICANT_ERROR,
                            -1
                        )
                        Log.d(
                            TAG,
                            "[monitorConnectionLegacy] SUPPLICANT_STATE_CHANGED errorCode=$errorCode $dev"
                        )
                        if (errorCode == WifiManager.ERROR_AUTHENTICATING) {
                            if (networkId != -1) {
                                wifiManager.removeNetwork(networkId)
                            }
                            completeWithFailure(callback, continuation, "Authentication failed")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(connectionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(connectionReceiver, filter)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[monitorConnectionLegacy] registerReceiver failed: ${e.message} $dev", e)
        }
    }

    private fun getCurrentConnectedNetwork(): String? {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val raw = wifiInfo?.ssid
            val result = raw?.replace("\"", "")
            Log.d(TAG, "[getCurrentConnectedNetwork] raw='$raw' -> '$result' $dev")
            result
        } catch (e: Exception) {
            Log.w(TAG, "[getCurrentConnectedNetwork] EXCEPTION: ${e.message} $dev", e)
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

        val allGranted = permissions.all { permission ->
            val granted = ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.d(TAG, "[hasRequiredPermissions] MISSING permission=$permission $dev")
            }
            granted
        }
        Log.d(TAG, "[hasRequiredPermissions] result=$allGranted permissions=$permissions $dev")
        return allGranted
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationManager =
                context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val enabled = locationManager?.isLocationEnabled ?: false
            Log.d(TAG, "[isLocationEnabled] enabled=$enabled $dev")
            enabled
        } catch (e: Exception) {
            Log.w(TAG, "[isLocationEnabled] EXCEPTION: ${e.message} $dev", e)
            false
        }
    }

    fun isConnectedViaApp(ssid: String, bssid: String): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            isConnectedViaSuggestion(ssid, bssid) || isConnectedViaConfiguration(ssid)
        } else {
            isConnectedViaConfiguration(ssid)
        }
        Log.d(TAG, "[isConnectedViaApp] ssid='$ssid' bssid='$bssid' result=$result $dev")
        return result
    }

    private fun isConnectedViaSuggestion(ssid: String, bssid: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

        return try {
            val currentSSID = getCurrentConnectedNetwork()
            Log.d(
                TAG,
                "[isConnectedViaSuggestion] ssid='$ssid' bssid='$bssid' currentSSID='$currentSSID' $dev"
            )
            if (currentSSID != ssid) return false

            val suggestions = wifiManager.networkSuggestions
            val result = suggestions.any { suggestion ->
                suggestion.ssid == ssid &&
                        (suggestion.bssid == null || suggestion.bssid.toString()
                            .equals(bssid, ignoreCase = true))
            }
            Log.d(
                TAG,
                "[isConnectedViaSuggestion] suggestions=${suggestions.size} result=$result $dev"
            )
            result
        } catch (e: Throwable) {
            Log.w(TAG, "[isConnectedViaSuggestion] EXCEPTION: ${e.message} $dev", e)
            false
        }
    }

    private fun isConnectedViaConfiguration(ssid: String): Boolean {
        return try {
            val currentSSID = getCurrentConnectedNetwork()
            Log.d(
                TAG,
                "[isConnectedViaConfiguration] ssid='$ssid' currentSSID='$currentSSID' managed=${
                    getManagedSsids().contains(ssid)
                } $dev"
            )
            if (currentSSID != ssid) return false
            if (!getManagedSsids().contains(ssid)) return false

            val configurations = wifiManager.configuredNetworks ?: return false
            val result = configurations.any { config ->
                config.SSID?.replace("\"", "") == ssid
            }
            Log.d(
                TAG,
                "[isConnectedViaConfiguration] configured=${configurations.size} result=$result $dev"
            )
            result
        } catch (e: Throwable) {
            Log.w(TAG, "[isConnectedViaConfiguration] EXCEPTION: ${e.message} $dev", e)
            false
        }
    }

    fun disconnectAndForgetNetwork(ssid: String, bssid: String, callback: DisconnectionCallback) {
        Log.d(TAG, "[disconnectAndForgetNetwork] ssid='$ssid' bssid='$bssid' $dev")
        val handled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            disconnectAndForgetSuggestion(ssid, bssid, callback)
        } else {
            false
        }
        if (!handled) {
            disconnectAndForgetConfiguration(ssid, callback)
        }
    }

    private fun disconnectAndForgetSuggestion(
        ssid: String,
        bssid: String,
        callback: DisconnectionCallback
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

        return try {
            val suggestions = wifiManager.networkSuggestions
            val matchingSuggestions = suggestions.filter { suggestion ->
                suggestion.ssid == ssid &&
                        (suggestion.bssid == null || suggestion.bssid.toString()
                            .equals(bssid, ignoreCase = true))
            }
            Log.d(
                TAG,
                "[disconnectAndForgetSuggestion] matched=${matchingSuggestions.size} of ${suggestions.size} $dev"
            )

            if (matchingSuggestions.isNotEmpty()) {
                val result = wifiManager.removeNetworkSuggestions(matchingSuggestions)
                Log.d(
                    TAG,
                    "[disconnectAndForgetSuggestion] removeNetworkSuggestions result=$result $dev"
                )
                if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    wifiManager.disconnect()
                    callback.onNetworkForgotten()
                    callback.onDisconnectionSuccess()
                } else {
                    callback.onDisconnectionFailed("Failed to remove network suggestions")
                }
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[disconnectAndForgetSuggestion] EXCEPTION: ${e.message} $dev", e)
            callback.onDisconnectionFailed("Error: ${e.message}")
            true
        }
    }

    private fun disconnectAndForgetConfiguration(ssid: String, callback: DisconnectionCallback) {
        try {
            val configurations = wifiManager.configuredNetworks ?: emptyList()
            val matchingConfig = configurations.find { config ->
                config.SSID?.replace("\"", "") == ssid
            }
            Log.d(
                TAG,
                "[disconnectAndForgetConfiguration] configured=${configurations.size} matched=${matchingConfig != null} $dev"
            )

            if (matchingConfig != null) {
                val removed = wifiManager.removeNetwork(matchingConfig.networkId)
                Log.d(
                    TAG,
                    "[disconnectAndForgetConfiguration] removeNetwork(${matchingConfig.networkId}) -> $removed $dev"
                )
                if (removed) {
                    untrackManagedSsid(ssid)
                    wifiManager.disconnect()
                    callback.onNetworkForgotten()
                    callback.onDisconnectionSuccess()
                } else {
                    callback.onDisconnectionFailed("Failed to remove network configuration")
                }
            } else {
                callback.onDisconnectionFailed("Network configuration not found")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "[disconnectAndForgetConfiguration] EXCEPTION: ${e.message} $dev", e)
            callback.onDisconnectionFailed("Error: ${e.message}")
        }
    }
}
