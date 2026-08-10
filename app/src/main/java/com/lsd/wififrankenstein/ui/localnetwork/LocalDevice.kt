package com.lsd.wififrankenstein.ui.localnetwork

enum class OSType(val label: String) {
    UNKNOWN("Unknown"),
    WINDOWS("Windows"),
    LINUX("Linux"),
    ANDROID("Android"),
    IOS("iOS"),
    MACOS("macOS"),
    PRINTER("Printer"),
    CAMERA("Camera"),
    ROUTER("Router"),
    EMBEDDED("Embedded"),
    OTHER("Other")
}

data class LocalDevice(
    val ip: String,
    val mac: String = "",
    val vendor: String = "",
    val hostname: String = "",
    val openPorts: List<Int> = emptyList(),
    val os: String = "",
    val osType: OSType = OSType.UNKNOWN,
    val osFamily: String = "",
    val osCpe: String = "",
    val deviceType: String = "",
    val networkDistance: String = "",
    val netbiosName: String = "",
    val responseTimeMs: Long = 0,
    val ttl: Int = 0,
    val isAlive: Boolean = false,
    val isGateway: Boolean = false
)
