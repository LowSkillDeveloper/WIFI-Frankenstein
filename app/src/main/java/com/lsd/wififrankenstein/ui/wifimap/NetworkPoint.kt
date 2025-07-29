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
    var routerModel: String? = null,
    var adminCredentials: String? = null,
    var isHidden: Boolean = false,
    var isWifiDisabled: Boolean = false,
    var isDataLoaded: Boolean = false,
    var color: Int = 0,
    var offsetLatitude: Double = 0.0,
    var offsetLongitude: Double = 0.0,
    var allRecords: List<NetworkRecord> = emptyList()
) {
    val displayLatitude: Double get() = latitude + offsetLatitude
    val displayLongitude: Double get() = longitude + offsetLongitude
}

data class NetworkRecord(
    val essid: String?,
    val password: String?,
    val wpsPin: String?,
    val routerModel: String?,
    val adminCredentials: List<AdminCredential>,
    val isHidden: Boolean,
    val isWifiDisabled: Boolean,
    val timeAdded: String?,
    val rawData: Map<String, Any?>
)

data class AdminCredential(
    val login: String,
    val password: String
)