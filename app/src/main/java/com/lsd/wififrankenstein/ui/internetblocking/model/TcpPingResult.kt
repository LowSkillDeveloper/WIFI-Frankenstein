package com.lsd.wififrankenstein.ui.internetblocking.model

data class TcpPingResult(
    val ip: String,
    val port: Int,
    val label: String,
    val reachable: Boolean,
    val latencyMs: Long?,
    val status: CheckStatus
)
