package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import kotlinx.serialization.Serializable

@Serializable
data class WifiNetwork(
    val id: Long,
    val wifiName: String,
    val macAddress: String,
    val wifiPassword: String?,
    val wpsCode: String?,
    val adminPanel: String?,
    val latitude: Double?,
    val longitude: Double?
)