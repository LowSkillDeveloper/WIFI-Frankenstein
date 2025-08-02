package com.lsd.wififrankenstein.ui.iwscanner

data class IwLinkInfo(
    val connected: Boolean = false,
    val ssid: String = "",
    val bssid: String = "",
    val frequency: String = "",
    val signal: String = "",
    val txBitrate: String = ""
)

data class IwCapabilities(
    val capabilities: String = "",
    val maxScanSSIDs: String = "",
    val maxScanIEsLength: String = "",
    val supportedCiphers: List<String> = emptyList(),
    val supportedInterfaceModes: List<String> = emptyList(),
    val supportedCommands: List<String> = emptyList()
)

data class IwBand(
    val bandNumber: String = "",
    val capabilities: String = "",
    val frequencies: List<String> = emptyList(),
    val bitrates: List<String> = emptyList()
)

data class IwDeviceInfo(
    val wiphy: String = "",
    val bands: List<IwBand> = emptyList(),
    val capabilities: IwCapabilities? = null
)

data class IwSecurityInfo(
    val wpa: String = "",
    val rsn: String = "",
    val wps: String = ""
)

data class IwCapabilitiesInfo(
    val htCapabilities: String = "",
    val vhtCapabilities: String = "",
    val heCapabilities: String = ""
)

data class IwNetworkInfo(
    val bssid: String,
    val ssid: String,
    val frequency: String,
    val channel: String,
    val signal: String,
    val capability: String,
    val lastSeen: String,
    val isAssociated: Boolean = false,
    val security: IwSecurityInfo = IwSecurityInfo(),
    val capabilities: IwCapabilitiesInfo = IwCapabilitiesInfo(),
    val supportedRates: List<String> = emptyList(),
    val extendedRates: List<String> = emptyList(),
    val country: String = "",
    val rawData: String = ""
)

sealed class IwScanState {
    object Idle : IwScanState()
    object Scanning : IwScanState()
    object LoadingDeviceInfo : IwScanState()
    data class Completed(val networks: List<IwNetworkInfo>) : IwScanState()
    data class Failed(val error: String) : IwScanState()
}

data class IwInterface(
    val name: String,
    val type: String = "",
    val addr: String = "",
    val isActive: Boolean = false
)

data class IwInterfaceSelection(
    val selectedInterface: String,
    val availableInterfaces: List<IwInterface>,
    val isManualInput: Boolean = false
)