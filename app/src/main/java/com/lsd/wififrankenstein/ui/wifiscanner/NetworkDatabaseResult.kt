package com.lsd.wififrankenstein.ui.wifiscanner

import android.net.wifi.ScanResult

data class NetworkDatabaseResult(
    val network: ScanResult,
    val databaseInfo: Map<String, Any?>,
    val databaseName: String,
    val resultType: ResultType = ResultType.DATABASE
)

enum class ResultType {
    DATABASE,
    WPA_ALGORITHM,
    WPS_ALGORITHM
}