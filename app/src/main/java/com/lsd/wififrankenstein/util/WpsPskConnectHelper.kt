package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File






class WpsPskConnectHelper(private val context: Context) {

    companion object {
        private const val TAG = "WpsPskConnectHelper"
    }






    suspend fun extractPskFromSupplicant(
        socketDir: String,
        interfaceName: String = "wlan0",
        configFile: String,
        onLog: (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        val dir = NativeWifiBinaries.binaryDir(context)
        val wpaCli = "./${NativeWifiBinaries.WPA_CLI_32}"

        fun cli(cmd: String): Shell.Result? = try {
            Shell.cmd("cd $dir && export LD_LIBRARY_PATH=$dir && $wpaCli -p$socketDir -i $interfaceName $cmd")
                .exec()
        } catch (e: Exception) {
            Log.w(TAG, "cli failed: $cmd", e)
            null
        }

        onLog("[psk] extracting PSK from custom supplicant socketDir=$socketDir")

        var psk = extractPskValue(cli("get_network 0 psk"))
        if (psk == null) {
            onLog("[psk] get_network 0 empty, retrying")
            psk = extractPskValue(cli("get_network 0 psk"))
        }

        if (psk == null) {
            onLog("[psk] PSK not in control interface — trying save_config...")
            cli("save_config")
            psk = parsePskFromConfig(File(dir, configFile))
        }

        if (psk != null) {
            onLog("[+] WPA PSK extracted: $psk")
        } else {
            onLog("[-] No PSK obtained from custom supplicant")
        }
        psk
    }






    suspend fun extractPskFromSystem(
        ctrlDir: String,
        wpaCliPath: String,
        interfaceName: String = "wlan0",
        onLog: (String) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        fun cli(cmd: String): Shell.Result? = try {
            Shell.cmd("$wpaCliPath -p$ctrlDir IFNAME=$interfaceName $cmd").exec()
        } catch (e: Exception) {
            Log.w(TAG, "system cli failed: $cmd", e)
            null
        }

        onLog("[psk] extracting PSK from system supplicant ctrlDir=$ctrlDir")

        var psk: String? = null
        for (id in 0..4) {
            psk = extractPskValue(cli("get_network $id psk"))
            if (psk != null) {
                onLog("[psk] get_network $id psk -> found")
                break
            }
        }

        if (psk == null) {
            onLog("[psk] PSK not via control interface — reading wpa_supplicant.conf via root")
            psk = parsePskFromSystemConfig()
        }

        if (psk != null) {
            onLog("[+] WPA PSK extracted: $psk")
        } else {
            onLog("[-] No PSK obtained from system supplicant")
        }
        psk
    }






    suspend fun connectWithPsk(
        network: ScanResult,
        psk: String,
        onLog: (String) -> Unit = {}
    ): Boolean {
        val trimmed = psk.trim()
        return if (trimmed.length in 8..63) {
            val helper = WiFiConnectionHelper(context)
            helper.connectToNetwork(
                network,
                trimmed,
                object : WiFiConnectionHelper.ConnectionCallback {
                    override fun onConnectionStarted() {
                        onLog("[connect] PSK connect started")
                    }

                    override fun onConnectionSuccess(ssid: String) {
                        onLog("[+] PSK-based Android connect succeeded: $ssid")
                    }

                    override fun onConnectionFailed(error: String) {
                        Log.w(TAG, "PSK-based Android connect failed: $error")
                        onLog("[-] PSK-based Android connect failed: $error")
                    }

                    override fun onConnectionTimeout() {
                        Log.w(TAG, "PSK-based Android connect timed out")
                        onLog("[-] PSK-based Android connect timed out")
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

    private fun extractPskValue(result: Shell.Result?): String? {
        if (result == null) return null
        val line = result.out.firstOrNull { l ->
            val t = l.trim()
            t.isNotEmpty() &&
                    t != "OK" &&
                    !t.startsWith("Selected interface") &&
                    !t.startsWith("Using interface") &&
                    !t.contains("UNKNOWN COMMAND", ignoreCase = true) &&
                    !t.contains("FAIL", ignoreCase = true)
        } ?: return null
        val v = line.trim().removePrefix("psk=").trim('"').trim()
        return v.ifEmpty { null }
    }

    private fun parsePskFromConfig(conf: File): String? {
        if (!conf.exists()) return null
        return try {
            conf.readLines()
                .map { it.trim() }
                .filter { it.startsWith("psk=") }
                .map { it.removePrefix("psk=").trim('"').trim() }
                .firstOrNull { it.isNotEmpty() && !it.contains("FAIL", ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse psk from config", e)
            null
        }
    }

    private fun parsePskFromSystemConfig(): String? {
        val candidates = listOf(
            "/data/misc/wifi/wpa_supplicant.conf",
            "/data/misc/wifi/wpa/wpa_supplicant.conf",
            "/data/system/wpa_supplicant.conf"
        )
        for (path in candidates) {
            try {
                val result = Shell.cmd("cat $path 2>/dev/null").exec()
                val psk = result.out
                    .map { it.trim() }
                    .filter { it.startsWith("psk=") }
                    .map { it.removePrefix("psk=").trim('"').trim() }
                    .firstOrNull { it.isNotEmpty() && !it.contains("FAIL", ignoreCase = true) }
                if (psk != null) return psk
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read $path", e)
            }
        }
        return null
    }
}
