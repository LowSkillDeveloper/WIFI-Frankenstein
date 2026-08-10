package com.lsd.wififrankenstein.util

import com.lsd.wififrankenstein.data.RouterScanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object RouterScanUtil {
    const val RS_PATH = "/opt/RouterScan/rs"
    const val AUTH_BASIC = "/opt/RouterScan/auth_basic.txt"
    const val AUTH_DIGEST = "/opt/RouterScan/auth_digest.txt"
    const val AUTH_FORM = "/opt/RouterScan/auth_form.txt"
    const val BATCH_SIZE = 255
    const val NMAP_TIMEOUT = 30_000L

    val SSID_REGEX = Regex("^SSID:\\s*(.+)$")
    val BSSID_REGEX = Regex("^BSSID:\\s*(.+)$")
    val AUTH_REGEX = Regex("^Auth:\\s*(.+)$")
    val KEY_REGEX = Regex("^Key:\\s*(.+)$")
    val WPS_REGEX = Regex("^WPS:\\s*(.+)$")
    val TITLE_REGEX = Regex("^Title:\\s*(.+)$")
    val SEC_REGEX = Regex("^Sec:\\s*(.+)$")
    val SERVER_STATUS_REGEX = Regex("^Status:\\s*(.+)$")
    val SERVER_TYPE_REGEX = Regex("^Type:\\s*(.+)$")

    fun parseRouterOutput(
        output: String,
        ip: String,
        port: String,
        fullOutput: String
    ): RouterScanResult {
        var ssid = ""
        var bssid = ""
        var auth = ""
        var sec = ""
        var psk = ""
        var wps = ""
        var title = ""
        var serverStatus = ""
        var serverType = ""
        var success = false

        for (line in output.split("\n")) {
            val trimmed = line.trim()

            SSID_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                ssid = it
                success = true
            }

            BSSID_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                bssid = it
            }

            AUTH_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                auth = it
                success = true
            }

            SEC_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                sec = it
            }

            KEY_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                psk = it
                success = true
            }

            WPS_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                wps = it
                success = true
            }

            TITLE_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                title = it
            }

            SERVER_STATUS_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                serverStatus = it
            }

            SERVER_TYPE_REGEX.find(trimmed)?.groups?.get(1)?.value?.let {
                serverType = it
            }
        }

        val isDoneWithoutData = !success && serverStatus == "Done"
        return RouterScanResult(
            ip = ip,
            port = port,
            ssid = ssid,
            bssid = bssid,
            auth = auth,
            sec = sec,
            psk = psk,
            wps = wps,
            title = title,
            serverType = serverType,
            success = success,
            status = if (success) "Success" else if (isDoneWithoutData) "Done" else serverStatus.ifBlank { "Failed" },
            type = if (success) 1 else if (isDoneWithoutData) 0 else 2,
            fullOutput = fullOutput
        )
    }

    fun parseNmapOutput(output: List<String>): Set<String> {
        val upIps = mutableSetOf<String>()
        var currentIp: String? = null

        for (line in output) {
            val trimmed = line.trim('\r')
            if (trimmed.startsWith("Nmap scan report for")) {
                val rest = trimmed.substringAfter("Nmap scan report for").trim()
                val lastCloseParen = rest.lastIndexOf(')')
                val lastOpenParen =
                    if (lastCloseParen > 0) rest.lastIndexOf('(', lastCloseParen) else -1
                if (lastCloseParen > 0 && lastOpenParen > 0 && lastCloseParen > lastOpenParen) {
                    currentIp = rest.substring(lastOpenParen + 1, lastCloseParen).trim()
                } else {
                    val ipMatch = Regex("^([\\d\\.]+)$").find(rest)
                    currentIp = ipMatch?.groups?.get(1)?.value
                }
            }
            if (currentIp != null && trimmed.contains("Host is up")) {
                upIps.add(currentIp!!)
                currentIp = null
            }
        }
        return upIps
    }

    suspend fun pingIp(ip: String, port: Int, timeout: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("http://$ip:$port")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = timeout.toInt()
                connection.readTimeout = timeout.toInt()
                connection.doOutput = false
                connection.instanceFollowRedirects = false
                val responseCode = connection.responseCode
                connection.disconnect()
                responseCode >= 200 && responseCode != 404 && responseCode != 403
            } catch (e: Exception) {
                false
            }
        }
}
