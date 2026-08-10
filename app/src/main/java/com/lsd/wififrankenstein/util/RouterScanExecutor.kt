package com.lsd.wififrankenstein.util

import com.lsd.wififrankenstein.data.RouterScanResult

interface RouterScanExecutor {
    suspend fun scanRouter(
        ip: String,
        port: String = "80",
        onProgress: ((String) -> Unit)? = null,
        sessionTimeout: Long = 120_000
    ): RouterScanResult

    suspend fun scanMultipleRouters(
        ips: List<String>? = null,
        port: String = "80",
        ipPortCombinations: List<Pair<String, String>>? = null,
        config: RouterScanConfig = RouterScanConfig(),
        onProgress: ((String) -> Unit)? = null,
        onResult: ((RouterScanResult) -> Unit)? = null
    ): List<RouterScanResult>

    suspend fun checkRsBinary(): Boolean
}
