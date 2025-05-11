package com.lsd.wififrankenstein.ui.wifimap

data class NetworkPoint(
    val latitude: Double,
    val longitude: Double,
    val bssidDecimal: Long,
    val source: String,
    val databaseId: String,
    var essid: String? = null,
    var password: String? = null,
    var wpsPin: String? = null,
    var isDataLoaded: Boolean = false,
    var color: Int = 0,
    var offsetLatitude: Double = 0.0,
    var offsetLongitude: Double = 0.0
) {
    val displayLatitude: Double get() = latitude + offsetLatitude
    val displayLongitude: Double get() = longitude + offsetLongitude
}