package com.lsd.wififrankenstein.ui.pixiedust

import android.net.wifi.ScanResult

data class WpsNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val level: Int,
    val frequency: Int,
    val scanResult: ScanResult
) {
    val isWpsEnabled: Boolean
        get() = capabilities.contains("WPS")

    val signalStrength: Int
        get() = level

    val securityType: String
        get() = when {
            capabilities.contains("WPA3") -> "WPA3"
            capabilities.contains("WPA2") -> "WPA2"
            capabilities.contains("WPA") -> "WPA"
            capabilities.contains("WEP") -> "WEP"
            else -> "Open"
        }
}

data class PixieAttackData(
    val peerPublicKey: String,
    val ownPublicKey: String,
    val hashOne: String,
    val hashTwo: String,
    val authenticationKey: String,
    val enrolleeNonce: String
) {
    fun toCommandArgs(): String {
        return "--pke $peerPublicKey --pkr $ownPublicKey --e-hash1 $hashOne --e-hash2 $hashTwo --authkey $authenticationKey --e-nonce $enrolleeNonce"
    }
}

data class PixieResult(
    val network: WpsNetwork,
    val pin: String?,
    val success: Boolean,
    val duration: Long,
    val timestamp: Long = System.currentTimeMillis()
)

sealed class PixieAttackState {
    object Idle : PixieAttackState()
    object Scanning : PixieAttackState()
    object CheckingRoot : PixieAttackState()
    object Preparing : PixieAttackState()
    object ExtractingData : PixieAttackState()
    object RunningAttack : PixieAttackState()
    data class Completed(val result: PixieResult) : PixieAttackState()
    data class Failed(val error: String, val errorCode: Int = -1) : PixieAttackState()
}