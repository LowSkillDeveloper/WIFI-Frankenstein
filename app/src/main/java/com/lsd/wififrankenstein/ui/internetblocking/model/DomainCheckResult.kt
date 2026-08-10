package com.lsd.wififrankenstein.ui.internetblocking.model

data class DomainCheckResult(
    val domain: String,
    val tls13Status: CheckStatus,
    val tls12Status: CheckStatus,
    val httpStatus: CheckStatus,
    val details: String? = null,
    val dnsResolvedIp: String? = null,
    val dnsIpType: String? = null,
    val dnsStatus: String? = null,
    val tls13Elapsed: Long = 0,
    val tls12Elapsed: Long = 0,
    val httpElapsed: Long = 0,
    val bytesReceived: Long = 0,
    val tls13Detail: String? = null,
    val tls12Detail: String? = null,
    val httpDetail: String? = null,
    val httpStub: Boolean = false,
    val tls13Trace: List<StageTrace> = emptyList(),
    val tls12Trace: List<StageTrace> = emptyList(),
    val httpTrace: List<StageTrace> = emptyList()
)
