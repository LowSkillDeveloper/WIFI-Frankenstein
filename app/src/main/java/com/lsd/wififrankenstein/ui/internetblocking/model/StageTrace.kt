package com.lsd.wififrankenstein.ui.internetblocking.model





data class StageTrace(
    val stage: String,
    val elapsedMs: Long,
    val note: String? = null
)
