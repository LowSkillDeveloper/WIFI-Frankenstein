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
    var allRecords: List<NetworkRecord> = emptyList(),
    var name: String? = null,
    var auth: String? = null,
    var radioOff: Boolean? = null,
    var hidden: Boolean? = null,
    var lanIp: String? = null,
    var wanIp: String? = null,
    var quadkey: Long? = null,
    val clusterCount: Int = 1,
    val isCluster: Boolean = false
) {
    val displayLatitude: Double get() = latitude + offsetLatitude
    val displayLongitude: Double get() = longitude + offsetLongitude
}

data class MapPoint(
    val bssidDecimal: Long,
    val latitude: Double,
    val longitude: Double,
    val color: Int,
    val clusterCount: Int = 1,
    val isCluster: Boolean = false,
    val databaseId: String
)

data class NetworkRecord(
    val essid: String?,
    val password: String?,
    val wpsPin: String?,
    val routerModel: String?,
    val adminCredentials: List<AdminCredential>,
    val isHidden: Boolean,
    val isWifiDisabled: Boolean,
    val timeAdded: String?,
    val security: String?,
    val lanMask: String?,
    val wanMask: String?,
    val wanGateway: String?,
    val dns1: String?,
    val dns2: String?,
    val dns3: String?,
    val noWifiKey: Int?,
    val noBssid: Int?,
    val noWps: Int?,
    val ip: String?,
    val lanIp: String?,
    val wanIp: String?,
    val iprange: Int?,
    val port: Int?,
    val time: Long?,
    val cmtid: Int?,
    val source: String?,
    val sourceRaw: Int?,
    val comment: String?,
    val rawData: Map<String, Any?>,
    val databaseColor: Int = 0,
    val databaseName: String? = null,
    val ipRaw: Long? = null,
    val lanIpRaw: Long? = null,
    val wanIpRaw: Long? = null,
    val lanMaskRaw: Long? = null,
    val wanMaskRaw: Long? = null,
    val wanGatewayRaw: Long? = null,
    val dns1Raw: Long? = null,
    val dns2Raw: Long? = null,
    val dns3Raw: Long? = null
)

data class AdminCredential(
    val login: String,
    val password: String
)
