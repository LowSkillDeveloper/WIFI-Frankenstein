package com.lsd.wififrankenstein.ui.internetblocking.model

data class TcpCheckResult(
    val id: String,
    val provider: String,
    val ip: String,
    val port: Int,
    val alive: Boolean,
    val blockKb: Int?,
    val rtt: Float?,
    val status: CheckStatus,
    val blockDetail: String? = null,
    val blockLabel: String? = null,
    val asn: String? = null
)
