package com.lsd.wififrankenstein.util

import android.content.Context
import android.net.wifi.ScanResult
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.lsd.wififrankenstein.R

enum class SecurityProtocol(@DrawableRes val iconRes: Int) {
    NONE(R.drawable.ic_lock_open),
    WPS(R.drawable.ic_lock_outline),
    WEP(R.drawable.ic_lock_outline),
    WPA(R.drawable.ic_lock),
    WPA2(R.drawable.ic_lock),
    WPA3(R.drawable.ic_lock),
    WPA2_WPA3(R.drawable.ic_lock)
}

enum class SecurityType(
    val typeId: Int,
    @StringRes val textResource: Int,
    val mainProtocol: SecurityProtocol = SecurityProtocol.NONE
) {
    UNKNOWN(-1, R.string.security_type_unknown),
    OPEN(0, R.string.security_type_open),
    WEP(1, R.string.security_type_wep, SecurityProtocol.WEP),
    PSK(2, R.string.security_type_psk, SecurityProtocol.WPA2),
    EAP(3, R.string.security_type_eap, SecurityProtocol.WPA2),
    SAE(4, R.string.security_type_sae, SecurityProtocol.WPA3),
    EAP_WPA3_ENTERPRISE_192(5, R.string.security_type_eap_wpa3_enterprise_192_bit, SecurityProtocol.WPA3),
    OWE(6, R.string.security_type_owe, SecurityProtocol.WPA3),
    WAPI_PSK(7, R.string.security_type_wapi_psk),
    WAPI_CERT(8, R.string.security_type_wapi_cert),
    EAP_WPA3_ENTERPRISE(9, R.string.security_type_eap_wpa3_enterprise, SecurityProtocol.WPA3),
    OSEN(10, R.string.security_type_osen),
    PASSPOINT_R1_R2(11, R.string.security_type_passpoint_r1_r2),
    PASSPOINT_R3(12, R.string.security_type_passpoint_r3),
    DPP(13, R.string.security_type_dpp);

    companion object {
        fun fromScanResult(scanResult: ScanResult): Set<SecurityType> {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                scanResult.securityTypes.map { typeId ->
                    entries.firstOrNull { it.typeId == typeId } ?: UNKNOWN
                }.toSet()
            } else {
                emptySet()
            }
        }
    }
}

enum class FastRoamingProtocol(@StringRes val textResource: Int) {
    FR_802_11K(R.string.fast_roaming_k),
    FR_802_11R(R.string.fast_roaming_r),
    FR_802_11V(R.string.fast_roaming_v)
}

data class NetworkSecurityInfo(
    val capabilities: String,
    val securityTypes: Set<SecurityType> = emptySet()
) {

    val mainProtocol: SecurityProtocol
        get() = determineMainProtocol()

    val protocols: Set<SecurityProtocol>
        get() = parseSecurityProtocols()

    val hasWps: Boolean
        get() = capabilities.contains("WPS", ignoreCase = true)

    val isAdHoc: Boolean
        get() = capabilities.contains("IBSS", ignoreCase = true)

    val fastRoamingProtocols: Set<FastRoamingProtocol>
        get() = parseFastRoaming()

    private fun determineMainProtocol(): SecurityProtocol {
        val upper = capabilities.uppercase()
        return when {
            upper.contains("RSN") && upper.contains("SAE") && upper.contains("PSK") -> SecurityProtocol.WPA2_WPA3
            upper.contains("RSN") && upper.contains("SAE") && !upper.contains("PSK") -> SecurityProtocol.WPA3
            upper.contains("WPA3") -> SecurityProtocol.WPA3
            upper.contains("WPA2") || upper.contains("RSN") -> SecurityProtocol.WPA2
            upper.contains("WPA") && !upper.contains("WPA2") -> SecurityProtocol.WPA
            upper.contains("WEP") -> SecurityProtocol.WEP
            hasWps && !upper.contains("WPA") && !upper.contains("WEP") -> SecurityProtocol.WPS
            else -> SecurityProtocol.NONE
        }
    }

    private fun parseSecurityProtocols(): Set<SecurityProtocol> {
        val upperCaps = capabilities.uppercase()
        val protocols = mutableSetOf<SecurityProtocol>()

        when {
            upperCaps.contains("RSN") && upperCaps.contains("SAE") && upperCaps.contains("PSK") -> {
                protocols.add(SecurityProtocol.WPA2_WPA3)
            }
            upperCaps.contains("RSN") && upperCaps.contains("SAE") -> {
                protocols.add(SecurityProtocol.WPA3)
            }
            upperCaps.contains("WPA3") -> {
                protocols.add(SecurityProtocol.WPA3)
            }
            upperCaps.contains("WPA2") || upperCaps.contains("RSN") -> {
                protocols.add(SecurityProtocol.WPA2)
            }
            upperCaps.contains("WPA") -> {
                protocols.add(SecurityProtocol.WPA)
            }
            upperCaps.contains("WEP") -> {
                protocols.add(SecurityProtocol.WEP)
            }
            !upperCaps.contains("WPA") && !upperCaps.contains("WEP") -> {
                protocols.add(SecurityProtocol.NONE)
            }
        }

        if (hasWps) {
            protocols.add(SecurityProtocol.WPS)
        }

        return protocols
    }

    private fun parseFastRoaming(): Set<FastRoamingProtocol> {
        val protocols = mutableSetOf<FastRoamingProtocol>()
        val upper = capabilities.uppercase()

        if (upper.contains("11K")) {
            protocols.add(FastRoamingProtocol.FR_802_11K)
        }
        if (upper.contains("11R") || upper.contains("FT")) {
            protocols.add(FastRoamingProtocol.FR_802_11R)
        }
        if (upper.contains("11V") || upper.contains("BSS_TRANSITION")) {
            protocols.add(FastRoamingProtocol.FR_802_11V)
        }

        return protocols
    }

    fun getSecurityString(): String {
        return when (mainProtocol) {
            SecurityProtocol.WPA2_WPA3 -> "WPA2/WPA3"
            else -> protocols.filter { it != SecurityProtocol.WPS }
                .joinToString("/") { it.name }
        }
    }

    fun getSecurityTypesString(context: Context): String {
        return if (securityTypes.isNotEmpty()) {
            securityTypes.joinToString(" ") { type ->
                context.getString(type.textResource)
            }
        } else {
            context.getString(R.string.security_type_unknown)
        }
    }

    fun getFastRoamingString(context: Context): String {
        return fastRoamingProtocols.joinToString(" ") { protocol ->
            context.getString(protocol.textResource)
        }
    }
}