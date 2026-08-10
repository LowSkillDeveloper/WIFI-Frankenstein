package com.lsd.wififrankenstein.ui.internetblocking.model

data class SweepResult(
    val targetId: String,
    val targetProvider: String,
    val targetIp: String,
    val targetPort: Int,
    val workingSni: String,
    val rtt: Float,
    val status: CheckStatus
)
