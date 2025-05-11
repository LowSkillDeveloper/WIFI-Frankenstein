package com.lsd.wififrankenstein.ui.databasefinder

data class SearchResult(
    val ssid: String,
    val bssid: String,
    val password: String?,
    val wpsPin: String?,
    val source: String,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    override fun toString(): String {
        return "SearchResult(ssid='$ssid', bssid='$bssid', password=$password, wpsPin=$wpsPin, source='$source', lat=$latitude, lon=$longitude)"
    }
}