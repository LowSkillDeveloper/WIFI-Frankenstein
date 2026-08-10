package com.lsd.wififrankenstein.ui.internetblocking.model

data class YouTubeCheckResult(
    val endpointResults: List<YouTubeEndpointResult>,
    val downloadSpeedKbps: Float?,
    val downloadUrlUsed: String?,
    val downloadBytes: Long?,
    val status: CheckStatus,
    val totalDurationMs: Long
) {
    val endpointTotal: Int get() = endpointResults.size
    val endpointReachableCount: Int get() = endpointResults.count { it.reachable }
}

data class YouTubeEndpointResult(
    val label: String,
    val ip: String,
    val reachable: Boolean
)
