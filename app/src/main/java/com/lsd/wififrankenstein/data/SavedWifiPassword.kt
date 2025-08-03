package com.lsd.wififrankenstein.data

import kotlinx.serialization.Serializable

@Serializable
data class SavedWifiPassword(
    val ssid: String,
    val password: String,
    val securityType: String,
    val bssid: String? = null,
    val configKey: String? = null,
    val isShared: Boolean = false,
    val priority: Int = 0,
    val frequency: Int? = null
) {
    companion object {
        const val SECURITY_OPEN = "Open"
        const val SECURITY_WEP = "WEP"
        const val SECURITY_WPA = "WPA"
        const val SECURITY_WPA2 = "WPA2"
        const val SECURITY_WPA3 = "WPA3"
        const val SECURITY_UNKNOWN = "Unknown"
    }

    val isOpenNetwork: Boolean
        get() = securityType == SECURITY_OPEN || password.isEmpty()

    val displayName: String
        get() = ssid.ifEmpty { "Unknown Network" }
}