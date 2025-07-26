package com.lsd.wififrankenstein.ui.wpsgenerator

data class WPSPin(
    var mode: Int,
    var name: String,
    var pin: String = "",
    var sugg: Boolean = false,
    var score: Double = 0.0,
    var additionalData: Map<String, Any?> = emptyMap(),
    var isFrom3WiFi: Boolean = false,
    var isExperimental: Boolean = false
)