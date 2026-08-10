package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.lsd.wififrankenstein.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class WpsChrootConnectResult(
    val connected: Boolean,
    val psk: String?,
    val binMissing: Boolean,
    val supplicantFailed: Boolean,
    val rawOutput: String
)

class WpsChrootConnectRunner(private val context: Context) {

    private val chrootManager = ChrootManager(context)

    companion object {
        private const val TAG = "WpsChrootConnectRunner"
        private const val MAX_POLL_SECONDS = 90L
        private const val POLL_INTERVAL_SECONDS = 2L
        private const val SUPPLICANT_WAIT_SECONDS = 5L
    }

    suspend fun connectToNetworkWps(
        network: ScanResult,
        wpsPin: String,
        interfaceName: String = "wlan0",
        callbacks: WpsRootConnectHelper.WpsConnectCallbacks
    ): Boolean {
        return withContext(Dispatchers.IO) {
            Log.d(TAG, "=== WPS CHROOT CONNECT START ===")
            var wifiReenabled = false
            try {
                val bssid = network.BSSID
                if (bssid.isNullOrEmpty()) {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_chroot_no_bssid))
                    return@withContext false
                }

                val chrootType = chrootManager.getChrootType()
                if (chrootType !is ChrootType.Root) {
                    Log.e(TAG, "Chroot is not available: $chrootType")
                    callbacks.onConnectionFailed(context.getString(R.string.wps_chroot_not_installed))
                    return@withContext false
                }

                if (!isValidPin(wpsPin)) {
                    callbacks.onConnectionFailed(context.getString(R.string.wps_chroot_invalid_pin))
                    return@withContext false
                }

                callbacks.onConnectionProgress(context.getString(R.string.wps_chroot_starting))

                if (!chrootManager.disableWifiOnHost()) {
                    Log.e(TAG, "Failed to disable WiFi on host — aborting")
                    callbacks.onConnectionFailed(
                        context.getString(R.string.wps_chroot_wifi_disable_failed)
                    )
                    return@withContext false
                }
                delay(2000)

                val cmd = buildConnectScript(bssid, interfaceName, wpsPin)
                Log.d(TAG, "Executing chroot WPS connect script:\n$cmd")

                val result = chrootManager.executePersistentSession(
                    cmd,
                    onOutput = { line ->
                        Log.d(TAG, "Chroot output: $line")
                        callbacks.onLogEntry(line)
                        if (line.contains("WPS_CONNECTED")) {
                            callbacks.onConnectionProgress(
                                context.getString(R.string.wps_chroot_connected)
                            )
                        }
                    },
                    sessionTimeout = MAX_POLL_SECONDS + 210_000L
                )

                val parsed = parseOutput(result.stdout)
                Log.d(
                    TAG,
                    "Parsed result: connected=${parsed.connected}, psk=${parsed.psk?.length} chars, " +
                            "binMissing=${parsed.binMissing}, supplicantFailed=${parsed.supplicantFailed}"
                )

                if (parsed.binMissing) {
                    callbacks.onConnectionFailed(
                        context.getString(R.string.wps_chroot_binaries_missing)
                    )
                    return@withContext false
                }

                if (parsed.supplicantFailed) {
                    callbacks.onConnectionFailed(
                        context.getString(R.string.wps_root_supplicant_failed)
                    )
                    return@withContext false
                }

                val psk = parsed.psk
                if (psk != null) {
                    chrootManager.enableWifiOnHost()
                    waitForWifiEnabled()
                    wifiReenabled = true

                    callbacks.onConnectionProgress(context.getString(R.string.wps_chroot_psk_found))
                    val registered = connectAndroidByPsk(network, psk, callbacks)
                    Log.d(TAG, "Android registration via recovered PSK: $registered")
                    callbacks.onWpsResult(wpsPin, psk)
                    if (registered) {
                        callbacks.onConnectionSuccess(network.SSID)
                        return@withContext true
                    }
                    callbacks.onConnectionFailed(
                        context.getString(R.string.wps_chroot_android_connect_failed)
                    )
                    return@withContext false
                }

                if (parsed.connected) {
                    Log.w(TAG, "WPS completed but PSK could not be recovered — reporting failure")
                    callbacks.onWpsResult(wpsPin, null)
                    callbacks.onConnectionFailed(context.getString(R.string.wps_chroot_no_psk))
                    return@withContext false
                }

                Log.w(TAG, "WPS chroot connect failed — not connected")
                callbacks.onWpsResult(wpsPin, null)
                callbacks.onConnectionFailed(context.getString(R.string.wps_chroot_failed))
                false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "WPS chroot connect threw exception", e)
                callbacks.onConnectionFailed(
                    context.getString(R.string.wps_chroot_error, e.message ?: "Unknown")
                )
                false
            } finally {
                if (!wifiReenabled) {
                    try {
                        chrootManager.enableWifiOnHost()
                        delay(2000)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to re-enable WiFi", e)
                    }
                }
            }
        }
    }

    suspend fun cancel() {
        Log.d(TAG, "WPS chroot connect cancellation requested")
        chrootManager.cancelSession()
        chrootManager.forceCleanup()
    }

    private fun isValidPin(pin: String): Boolean {
        return pin.isBlank() || (pin.length in 4..8 && pin.all { it.isDigit() })
    }

    private suspend fun waitForWifiEnabled(timeoutMs: Long = 10_000) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (wifiManager.isWifiEnabled) {
                Log.d(TAG, "WiFi is enabled on host")
                return
            }
            delay(500)
        }
        Log.w(TAG, "Timed out waiting for host WiFi to become enabled")
    }

    private fun buildConnectScript(bssid: String, iface: String, pin: String): String {
        val pinCommand = if (pin.isBlank()) {
            "wpa_cli -i $iface wps_pin $bssid"
        } else {
            "wpa_cli -i $iface wps_pin $bssid $pin"
        }
        val pollSteps = (MAX_POLL_SECONDS / POLL_INTERVAL_SECONDS).toInt()
        val iterations = (1..pollSteps).joinToString(" ")
        val supplicantWaitSteps = (1..SUPPLICANT_WAIT_SECONDS).joinToString(" ")
        return """
if [ -x /sbin/wpa_supplicant ] && [ -x /sbin/wpa_cli ]; then
  echo WPS_BIN_OK
else
  echo WPS_BIN_MISSING
  echo WPS_CHROOT_DONE
  exit 0
fi
rm -rf /var/run/wpa_supplicant
mkdir -p /var/run/wpa_supplicant
cat > /tmp/wps_chroot.conf <<EOF
ctrl_interface=/var/run/wpa_supplicant
update_config=1
ap_scan=1
EOF
wpa_supplicant -B -D nl80211,wext -i $iface -c /tmp/wps_chroot.conf -O /var/run/wpa_supplicant
for i in $supplicantWaitSteps; do
  if [ -S /var/run/wpa_supplicant/$iface ]; then
    echo WPS_SUPPLICANT_OK
    break
  fi
  sleep 1
done
if [ ! -S /var/run/wpa_supplicant/$iface ]; then
  echo WPS_SUPPLICANT_FAIL
  pkill -f wpa_supplicant 2>/dev/null
  echo WPS_CHROOT_DONE
  exit 0
fi
$pinCommand
for i in $iterations; do
  wpa_cli -i $iface status > /tmp/wps_status.txt 2>/dev/null
  if grep -q "wpa_state=COMPLETED" /tmp/wps_status.txt; then
    echo WPS_CONNECTED
    echo WPS_PSK_BEGIN
    wpa_cli -i $iface get_network 0 psk
    echo WPS_PSK_END
    break
  fi
  sleep $POLL_INTERVAL_SECONDS
done
wpa_cli -i $iface terminate 2>/dev/null
pkill -f wpa_supplicant 2>/dev/null
echo WPS_CHROOT_DONE
        """.trimIndent()
    }

    private fun parseOutput(lines: List<String>): WpsChrootConnectResult {
        var connected = false
        var binMissing = false
        var supplicantFailed = false
        for (line in lines) {
            val t = line.trim()
            when {
                t.contains("WPS_BIN_MISSING") -> binMissing = true
                t.contains("WPS_SUPPLICANT_FAIL") -> supplicantFailed = true
                t.contains("WPS_CONNECTED") -> connected = true
                t.contains("wpa_state=COMPLETED") -> connected = true
                t.contains("CTRL-EVENT-CONNECTED") -> connected = true
            }
        }

        val joined = lines.joinToString("\n")
        val beginMarker = "WPS_PSK_BEGIN"
        val endMarker = "WPS_PSK_END"
        val beginIdx = joined.indexOf(beginMarker)
        val endIdx = joined.indexOf(endMarker)
        var psk: String? = null
        if (beginIdx != -1 && endIdx != -1 && endIdx > beginIdx) {
            val block = joined.substring(beginIdx + beginMarker.length, endIdx)
            for (rawLine in block.lines()) {
                val l = rawLine.trim()
                if (l.startsWith("psk=")) {
                    val candidate = l.removePrefix("psk=").trim('"').trim()
                    if (candidate.isNotEmpty() && !candidate.equals("FAIL", ignoreCase = true)) {
                        psk = candidate
                        break
                    }
                }
            }
        }

        return WpsChrootConnectResult(connected, psk, binMissing, supplicantFailed, joined)
    }

    private suspend fun connectAndroidByPsk(
        network: ScanResult,
        psk: String,
        callbacks: WpsRootConnectHelper.WpsConnectCallbacks
    ): Boolean {
        val trimmed = psk.trim()
        return if (trimmed.length in 8..63) {
            val helper = WiFiConnectionHelper(context)
            helper.connectToNetwork(
                network,
                trimmed,
                object : WiFiConnectionHelper.ConnectionCallback {
                    override fun onConnectionStarted() {
                        callbacks.onLogEntry(context.getString(R.string.wps_chroot_psk_found))
                    }

                    override fun onConnectionSuccess(ssid: String) {
                        callbacks.onLogEntry("PSK-based Android connect succeeded: $ssid")
                    }

                    override fun onConnectionFailed(error: String) {
                        Log.w(TAG, "PSK-based Android connect failed: $error")
                    }

                    override fun onConnectionTimeout() {
                        Log.w(TAG, "PSK-based Android connect timed out")
                    }
                }
            )
        } else {
            connectAndroidWithRawPsk(network, trimmed)
        }
    }

    private fun connectAndroidWithRawPsk(network: ScanResult, pskHex: String): Boolean {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiConfig = WifiConfiguration().apply {
                SSID = "\"${network.SSID}\""
                BSSID = network.BSSID
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                preSharedKey = pskHex
            }
            val networkId = wifiManager.addNetwork(wifiConfig)
            if (networkId == -1) {
                Log.w(TAG, "addNetwork failed for raw PSK")
                return false
            }
            wifiManager.disconnect()
            val enabled = wifiManager.enableNetwork(networkId, true)
            wifiManager.reconnect()
            Log.d(TAG, "Raw PSK connect: networkId=$networkId, enabled=$enabled")
            enabled
        } catch (e: Exception) {
            Log.w(TAG, "Raw PSK connect failed", e)
            false
        }
    }
}
