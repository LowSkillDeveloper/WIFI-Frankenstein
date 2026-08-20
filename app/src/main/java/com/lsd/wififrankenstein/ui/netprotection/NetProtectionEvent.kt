package com.lsd.wififrankenstein.ui.netprotection

data class NetProtectionEvent(
    val timestamp: Long,
    val type: EventType,
    val message: String
)

enum class EventType { ARP_SPOOF, PORT_SCAN, CONNECTION_SPIKE, INFO, ERROR }
