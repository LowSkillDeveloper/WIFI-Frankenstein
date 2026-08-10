package com.lsd.wififrankenstein.ui.dbsetup

import org.osmdroid.util.BoundingBox

interface MapHelper {
    val TAG: String
    suspend fun checkMapSupport(): Boolean
    suspend fun getPointsInBoundingBox(
        boundingBox: BoundingBox,
        zoom: Double,
        maxPoints: Int = Int.MAX_VALUE
    ): List<MapPointData>

    suspend fun getPointDetails(bssidDecimal: Long): Map<String, Any?>?
}

data class MapPointData(
    val id: String,
    val bssidDecimal: Long,
    val count: Int,
    val latitude: Double,
    val longitude: Double,
    val popupHtml: String? = null,
    val essid: String? = null,
    val password: String? = null,
    val securityType: String? = null,
    val bssid: String? = null
)
