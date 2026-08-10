package com.lsd.wififrankenstein.ui.wifimap

data class ClusteredMapPoint(
    val bssidDecimal: Long,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    val isCluster: Boolean
)
