package com.lsd.wififrankenstein.ui.localnetwork

data class SubnetInfo(
    val gateway: String,
    val subnet: String,
    val cidr: String,
    val wlanInterface: String = "wlan0",
    val localIp: String = ""
)

data class ScanProgress(
    val phase: String = "",
    val current: Int = 0,
    val total: Int = 0,
    val line: String = ""
)
