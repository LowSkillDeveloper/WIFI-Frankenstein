package com.lsd.wififrankenstein.data

data class RouterScanResult(
    val ip: String = "",
    val port: String = "80",
    val ssid: String = "",
    val bssid: String = "",
    val auth: String = "",
    val sec: String = "",
    val psk: String = "",
    val wps: String = "",
    val title: String = "",
    val serverType: String = "",
    val success: Boolean = false,
    val status: String = "Pending",
    val lon: String = "N/A",
    val lat: String = "N/A",
    val type: Int = 0,
    val scanned: Boolean = false,
    val fullOutput: String = ""
)
