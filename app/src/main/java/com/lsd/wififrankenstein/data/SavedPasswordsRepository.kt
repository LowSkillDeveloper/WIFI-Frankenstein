package com.lsd.wififrankenstein.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class SavedPasswordsRepository(private val context: Context) {

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun getSavedPasswords(useRoot: Boolean = true): List<SavedWifiPassword> {
        return withContext(Dispatchers.IO) {
            if (useRoot && Shell.isAppGrantedRoot() == true) {
                getRootPasswords()
            } else {
                getNonRootPasswords()
            }
        }
    }

    private suspend fun getRootPasswords(): List<SavedWifiPassword> {
        return suspendCancellableCoroutine { continuation ->
            try {
                val passwords = mutableListOf<SavedWifiPassword>()
                val paths = getWifiPaths()

                if (paths.isEmpty()) {
                    continuation.resume(emptyList())
                    return@suspendCancellableCoroutine
                }

                processPathsSequentially(paths, 0, passwords) { result ->
                    val uniquePasswords = result.distinctBy { "${it.ssid}:${it.bssid ?: "null"}" }
                        .filter { it.ssid.isNotEmpty() }
                    continuation.resume(uniquePasswords)
                }
            } catch (e: Exception) {
                Log.e("SavedPasswordsRepository", "Error getting root passwords", e)
                continuation.resume(emptyList())
            }
        }
    }

    private fun getWifiPaths(): List<String> {
        return listOf(
            "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
            "/data/misc/wifi/WifiConfigStore.xml",
            "/data/misc/wifi/wpa_supplicant.conf",
            "/data/wifi/WifiConfigStore.xml",
            "/data/vendor/wifi/WifiConfigStore.xml",
            "/data/hwdata/wifi/WifiConfigStore.xml",
            "/data/oppo/wifi/WifiConfigStore.xml",
            "/data/oplus/wifi/WifiConfigStore.xml",
            "/system/etc/wifi/wpa_supplicant.conf",
            "/data/wifi/bcm_supp.conf",
            "/data/misc/wifi/WifiBackupRestore.xml"
        )
    }

    private fun processPathsSequentially(
        paths: List<String>,
        index: Int,
        passwords: MutableList<SavedWifiPassword>,
        callback: (List<SavedWifiPassword>) -> Unit
    ) {
        if (index >= paths.size) {
            callback(passwords)
            return
        }

        val path = paths[index]

        Shell.cmd("test -f $path && cat $path").submit { result ->
            try {
                if (result.isSuccess && result.out.isNotEmpty()) {
                    val content = result.out.joinToString("\n")
                    if (content.isNotBlank()) {
                        val parsedPasswords = if (path.endsWith(".xml")) {
                            parseXmlConfig(result.out)
                        } else {
                            parseSupplicantConfig(result.out)
                        }
                        passwords.addAll(parsedPasswords)
                        Log.d("SavedPasswordsRepository", "Found ${parsedPasswords.size} passwords in $path")
                    }
                }
            } catch (e: Exception) {
                Log.e("SavedPasswordsRepository", "Error parsing $path", e)
            }

            processPathsSequentially(paths, index + 1, passwords, callback)
        }
    }

    private fun parseXmlConfig(lines: List<String>): List<SavedWifiPassword> {
        val passwords = mutableListOf<SavedWifiPassword>()
        var currentSsid = ""
        var currentPassword = ""
        var currentSecurity = SavedWifiPassword.SECURITY_UNKNOWN
        var currentBssid: String? = null
        var currentConfigKey: String? = null
        var isInNetworkBlock = false

        for (i in lines.indices) {
            val line = lines[i].trim()

            when {
                line.contains("<Network") || line.contains("<WifiConfiguration") -> {
                    isInNetworkBlock = true
                    currentSsid = ""
                    currentPassword = ""
                    currentSecurity = SavedWifiPassword.SECURITY_UNKNOWN
                    currentBssid = null
                    currentConfigKey = null
                }

                isInNetworkBlock && line.contains("name=\"SSID\"") -> {
                    currentSsid = extractXmlValue(line, lines.getOrNull(i + 1))
                        .removePrefix("\"").removeSuffix("\"")
                }

                isInNetworkBlock && line.contains("name=\"PreSharedKey\"") -> {
                    currentPassword = extractXmlValue(line, lines.getOrNull(i + 1))
                        .removePrefix("\"").removeSuffix("\"")
                }

                isInNetworkBlock && line.contains("name=\"ConfigKey\"") -> {
                    currentConfigKey = extractXmlValue(line, lines.getOrNull(i + 1))
                    currentSecurity = determineSecurityFromConfigKey(currentConfigKey ?: "")
                }

                isInNetworkBlock && line.contains("name=\"BSSID\"") -> {
                    currentBssid = extractXmlValue(line, lines.getOrNull(i + 1))
                }

                isInNetworkBlock && line.contains("name=\"AllowedKeyManagement\"") -> {
                    val value = extractXmlValue(line, lines.getOrNull(i + 1))
                    currentSecurity = determineSecurityFromKeyMgmt(value)
                }

                line.contains("</Network>") || line.contains("</WifiConfiguration>") -> {
                    if (isInNetworkBlock && currentSsid.isNotEmpty()) {
                        if (currentSecurity == SavedWifiPassword.SECURITY_UNKNOWN && currentPassword.isEmpty()) {
                            currentSecurity = SavedWifiPassword.SECURITY_OPEN
                        }

                        passwords.add(SavedWifiPassword(
                            ssid = currentSsid,
                            password = currentPassword,
                            securityType = currentSecurity,
                            bssid = currentBssid,
                            configKey = currentConfigKey
                        ))
                    }
                    isInNetworkBlock = false
                }
            }
        }

        return passwords
    }

    private fun parseSupplicantConfig(lines: List<String>): List<SavedWifiPassword> {
        val passwords = mutableListOf<SavedWifiPassword>()
        var currentSsid = ""
        var currentPassword = ""
        var currentSecurity = SavedWifiPassword.SECURITY_WPA
        var inNetwork = false

        for (line in lines) {
            val trimmedLine = line.trim()

            when {
                trimmedLine.startsWith("network={") || trimmedLine == "network={" -> {
                    inNetwork = true
                    currentSsid = ""
                    currentPassword = ""
                    currentSecurity = SavedWifiPassword.SECURITY_WPA
                }

                inNetwork && trimmedLine.startsWith("ssid=") -> {
                    currentSsid = trimmedLine.substringAfter("ssid=")
                        .trim()
                        .removePrefix("\"")
                        .removeSuffix("\"")
                }

                inNetwork && trimmedLine.startsWith("psk=") -> {
                    currentPassword = trimmedLine.substringAfter("psk=")
                        .trim()
                        .removePrefix("\"")
                        .removeSuffix("\"")
                }

                inNetwork && (trimmedLine == "key_mgmt=NONE" || trimmedLine.contains("key_mgmt=NONE")) -> {
                    currentSecurity = SavedWifiPassword.SECURITY_OPEN
                    currentPassword = ""
                }

                inNetwork && trimmedLine == "}" -> {
                    if (currentSsid.isNotEmpty()) {
                        passwords.add(SavedWifiPassword(
                            ssid = currentSsid,
                            password = currentPassword,
                            securityType = currentSecurity
                        ))
                    }
                    inNetwork = false
                }
            }
        }

        return passwords
    }

    private fun extractXmlValue(line: String, nextLine: String?): String {
        val patterns = listOf(
            "&quot;(.+?)&quot;",
            "value=\"(.+?)\"",
            ">(.+?)<"
        )

        for (pattern in patterns) {
            val regex = Regex(pattern)
            val match = regex.find(line) ?: nextLine?.let { regex.find(it) }
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return ""
    }

    private fun determineSecurityFromConfigKey(configKey: String): String {
        return when {
            configKey.contains("WEP", ignoreCase = true) -> SavedWifiPassword.SECURITY_WEP
            configKey.contains("WPA3", ignoreCase = true) -> SavedWifiPassword.SECURITY_WPA3
            configKey.contains("WPA2", ignoreCase = true) -> SavedWifiPassword.SECURITY_WPA2
            configKey.contains("WPA", ignoreCase = true) -> SavedWifiPassword.SECURITY_WPA
            configKey.contains("NONE", ignoreCase = true) -> SavedWifiPassword.SECURITY_OPEN
            else -> SavedWifiPassword.SECURITY_WPA2
        }
    }

    private fun determineSecurityFromKeyMgmt(keyMgmt: String): String {
        return when {
            keyMgmt.contains("WPA2", ignoreCase = true) -> SavedWifiPassword.SECURITY_WPA2
            keyMgmt.contains("WPA3", ignoreCase = true) -> SavedWifiPassword.SECURITY_WPA3
            keyMgmt.contains("WPA", ignoreCase = true) -> SavedWifiPassword.SECURITY_WPA
            keyMgmt.contains("NONE", ignoreCase = true) -> SavedWifiPassword.SECURITY_OPEN
            else -> SavedWifiPassword.SECURITY_WPA2
        }
    }

    @SuppressLint("LongLogTag")
    @Suppress("DEPRECATION")
    private fun getNonRootPasswords(): List<SavedWifiPassword> {
        val passwords = mutableListOf<SavedWifiPassword>()

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                val configurations = wifiManager.configuredNetworks
                configurations?.forEach { config ->
                    val ssid = config.SSID?.replace("\"", "") ?: ""
                    if (ssid.isNotEmpty()) {
                        val security = determineSecurityType(config)
                        passwords.add(SavedWifiPassword(
                            ssid = ssid,
                            password = "",
                            securityType = security,
                            bssid = config.BSSID,
                            priority = config.priority
                        ))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("SavedPasswordsRepository", "Security exception accessing WiFi configurations", e)
        } catch (e: Exception) {
            Log.e("SavedPasswordsRepository", "Error accessing WiFi configurations", e)
        }

        return passwords
    }

    @Suppress("DEPRECATION")
    private fun determineSecurityType(config: WifiConfiguration): String {
        return when {
            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK) ||
                    config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK) -> SavedWifiPassword.SECURITY_WPA2

            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) &&
                    config.wepKeys != null && config.wepKeys.any { it != null } -> SavedWifiPassword.SECURITY_WEP

            config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) -> SavedWifiPassword.SECURITY_OPEN

            else -> SavedWifiPassword.SECURITY_UNKNOWN
        }
    }
}