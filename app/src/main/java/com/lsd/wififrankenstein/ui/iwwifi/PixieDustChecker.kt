package com.lsd.wififrankenstein.ui.iwwifi

import com.lsd.wififrankenstein.ui.iwwifi.models.IwWifiNetwork

object PixieDustChecker {

    private val VULNERABLE_MODELS = setOf(
        "RT2860", "AIR3G", "AirLive",
        "Archer A9", "Archer A2", "Archer A5",
        "Archer C2", "Archer C5", "Archer C6U", "Archer C20", "Archer C50",
        "Archer MR200", "Archer VR300", "Archer VR400",
        "B-LINK", "Belkin", "WPSRouter", "RalinkWirelessAccessPoint",
        "DAP-1360", "DIR-819", "DIR-842", "DWR-921C3", "DIR-",
        "EC120-F5", "F6D4230-4",
        "HomeInternetCenter",
        "JWNR2000v2", "Keenetic", "EA7500", "WRT110",
        "NBG-419N", "Netgear", "R6220", "NR6260",
        "WR-AC1210", "RTL8196E", "RTL8xxx",
        "RT-G32", "300N", "123456",
        "RA300R4",
        "TD-W8151N", "TD-W8901N", "TD-W8951ND", "TD-W9960", "TD-W9960v", "TD-W8968",
        "TL-MR3020", "TL-MR3420", "TL-MR6400", "TL-WA855RE",
        "TL-WR840N", "TL-WR841N", "TL-WR841HP", "TL-WR842N", "TL-WR845N", "TL-WR850N", "TL-WR1042N",
        "TEW-625br", "TEW-651br",
        "WAP300N", "WAP3205",
        "RT-AC1200G", "RT-N10U", "RT-N12", "RT-N12D1", "RT-N12VP",
        "WirelessWPSRouter",
        "WN3000RP", "WN-200R", "RT-N65U",
        "DSL-AC51", "DSL-AC52U", "DSL-AC55U", "DSL-N14U", "DSL-N16", "DSL-N17U",
        "RT-AC750", "RT-AC1200", "RT-AC1750", "RT-AC750L", "RT-AC1750U",
        "RT-AC51", "RT-AC51U", "RT-AC52U", "RT-AC53", "RT-AC57U",
        "RT-AC65P", "RT-AC85P", "RT-N11P", "RT-N14U", "RT-N56U", "RT-N56UB1", "RT-N300",
        "NBG-416N", "NBG-418N", "NBG-417N",
        "VMG8623", "TL-WA801ND",
        "ADSL", "RTL-8671",
        "Internet Sharing", "EnGenius"
    )

    fun isPixieDustVulnerable(network: IwWifiNetwork): Boolean {
        val deviceName = network.wpsDeviceName.trim().lowercase()
        val model = network.wpsModel.trim().lowercase()
        val manufacturer = network.wpsManufacturer.trim().lowercase()
        val modelNumber = network.wpsModelNumber.trim().lowercase()

        if (deviceName.isEmpty() && model.isEmpty() && manufacturer.isEmpty() && modelNumber.isEmpty()) {
            return false
        }

        val allFields = listOf(deviceName, model, manufacturer, modelNumber)
        return allFields.any { field ->
            field.isNotEmpty() && VULNERABLE_MODELS.any { pattern ->
                field.contains(pattern.lowercase())
            }
        }
    }
}
