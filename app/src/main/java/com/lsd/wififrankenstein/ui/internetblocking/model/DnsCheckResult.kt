package com.lsd.wififrankenstein.ui.internetblocking.model

data class DnsCheckResult(
    val domain: String,
    val udpIps: List<String>,
    val jsonIps: List<String>,
    val wireIps: List<String>,
    val udpStatus: String?,
    val jsonStatus: String?,
    val wireStatus: String?,
    val status: CheckStatus,
    val details: String? = null,
    val totalUniqueIps: Int = 0,
    val jsonRawResponse: String? = null
)
