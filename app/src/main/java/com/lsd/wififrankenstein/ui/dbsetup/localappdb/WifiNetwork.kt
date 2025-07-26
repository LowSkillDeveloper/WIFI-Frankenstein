package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import kotlinx.serialization.Serializable

@Serializable
data class WifiNetwork(
    val id: Long,
    val wifiName: String,
    val macAddress: String,
    val wifiPassword: String? = null,
    val wpsCode: String? = null,
    val adminPanel: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)