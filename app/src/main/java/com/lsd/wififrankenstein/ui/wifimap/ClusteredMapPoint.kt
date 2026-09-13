package com.lsd.wififrankenstein.ui.wifimap

data class ClusteredMapPoint(
    val bssidDecimal: Long,
    val latitude: Double,
    val longitude: Double,
    val count: Int,
    val isCluster: Boolean,
    val essid: String? = null,

    val hasCrossData: Boolean = false,

    val wpasecKnown: Boolean = false,

    val isOpen: Boolean = false
)
