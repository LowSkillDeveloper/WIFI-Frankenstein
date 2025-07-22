package com.lsd.wififrankenstein.util

import androidx.annotation.DrawableRes
import com.lsd.wififrankenstein.R

enum class SecurityProtocol(@DrawableRes val iconRes: Int) {
    OPEN(R.drawable.ic_lock_open),
    WPS(R.drawable.ic_lock_outline),
    WEP(R.drawable.ic_lock_outline),
    WPA(R.drawable.ic_lock),
    WPA2(R.drawable.ic_lock),
    WPA3(R.drawable.ic_lock)
}

data class NetworkSecurityInfo(val capabilities: String) {

    val protocols: Set<SecurityProtocol>
        get() = parseSecurityProtocols()

    val mainProtocol: SecurityProtocol
        get() = protocols.maxByOrNull { it.ordinal } ?: SecurityProtocol.OPEN

    val hasWps: Boolean
        get() = protocols.contains(SecurityProtocol.WPS)

    private fun parseSecurityProtocols(): Set<SecurityProtocol> {
        val upperCaps = capabilities.uppercase()
        val protocols = mutableSetOf<SecurityProtocol>()

        when {
            upperCaps.contains("WPA3") -> protocols.add(SecurityProtocol.WPA3)
            upperCaps.contains("WPA2") -> protocols.add(SecurityProtocol.WPA2)
            upperCaps.contains("WPA") -> protocols.add(SecurityProtocol.WPA)
            upperCaps.contains("WEP") -> protocols.add(SecurityProtocol.WEP)
            !upperCaps.contains("WPA") && !upperCaps.contains("WEP") -> protocols.add(SecurityProtocol.OPEN)
        }

        if (upperCaps.contains("WPS")) {
            protocols.add(SecurityProtocol.WPS)
        }

        return protocols
    }

    fun getSecurityString(): String {
        return protocols.filter { it != SecurityProtocol.WPS }
            .joinToString("/") { it.name }
    }
}