package com.lsd.wififrankenstein.ui.wifiscanner

import android.net.wifi.ScanResult
import com.lsd.wififrankenstein.ui.wpagenerator.WpaResult
import com.lsd.wififrankenstein.ui.wpsgenerator.WPSPin

data class NetworkDatabaseResult(
    val network: ScanResult,
    val databaseInfo: Map<String, Any?>,
    val databaseName: String,
    val resultType: ResultType = ResultType.DATABASE,
    val wpaResult: WpaResult? = null,
    val wpsPin: WPSPin? = null
)

enum class ResultType {
    DATABASE,
    WPA_ALGORITHM,
    WPS_ALGORITHM
}