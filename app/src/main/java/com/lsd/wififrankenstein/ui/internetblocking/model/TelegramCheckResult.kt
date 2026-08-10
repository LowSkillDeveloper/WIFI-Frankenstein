package com.lsd.wififrankenstein.ui.internetblocking.model

data class TelegramCheckResult(
    val dcResults: List<DcResult>,
    val downloadSpeedKbps: Float?,
    val downloadUrlUsed: String?,
    val downloadBytes: Long?,
    val uploadSpeedKbps: Float?,
    val uploadBytes: Long?,
    val status: CheckStatus,
    val totalDurationMs: Long
) {
    val dcReachable: Boolean get() = dcResults.any { it.reachable }
    val dcTotal: Int get() = dcResults.size
    val dcReachableCount: Int get() = dcResults.count { it.reachable }
}

data class DcResult(
    val label: String,
    val ip: String,
    val reachable: Boolean
)
