package com.lsd.wififrankenstein.network.bettercap

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class WifiState(
    val aps: List<BettercapAP> = emptyList()
)

@Serializable
data class BettercapAP(
    val mac: String = "",
    val hostname: String = "",
    val frequency: Int = 0,
    val channel: Int = 0,
    val rssi: Int = 0,
    val encryption: String = "",
    val cipher: String = "",
    val authentication: String = "",
    val wps: Map<String, String> = emptyMap(),
    val clients: List<BettercapClientStation> = emptyList(),
    val handshake: Boolean = false,
    val sent: Long = 0,
    val received: Long = 0,
    val vendor: String = "",
    val alias: String = "",
    val first_seen: String = "",
    val last_seen: String = ""
)

@Serializable
data class BettercapClientStation(
    val mac: String = "",
    val frequency: Int = 0,
    val channel: Int = 0,
    val rssi: Int = 0,
    val vendor: String = "",
    val alias: String = "",
    val encryption: String = "",
    val last_seen: String = ""
)

@Serializable
data class BettercapEvent(
    val tag: String = "",
    val time: String = "",
    val data: JsonElement? = null
)

@Serializable
data class SessionResponse(
    val success: Boolean = false,
    val msg: String = ""
)

@Serializable
data class ModuleState(
    val name: String = "",
    val started: Boolean = false
)

enum class DaemonStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
    RESTARTING
}

enum class CaptureMode {
    HOPPING,
    SINGLE,
    CHANNEL_SET
}

enum class EventTag(val tag: String) {
    AP_NEW("wifi.ap.new"),
    AP_LOST("wifi.ap.lost"),
    CLIENT_NEW("wifi.client.new"),
    CLIENT_LOST("wifi.client.lost"),
    CLIENT_PROBE("wifi.client.probe"),
    CLIENT_HANDSHAKE("wifi.client.handshake"),
    DEAUTH("wifi.deauthentication"),
    MOD_STARTED("mod.started"),
    MOD_STOPPED("mod.stopped");

    companion object {
        fun fromTag(tag: String): EventTag? = entries.find { it.tag == tag }
    }
}
