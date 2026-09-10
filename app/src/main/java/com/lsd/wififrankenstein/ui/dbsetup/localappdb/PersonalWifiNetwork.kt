package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import kotlinx.serialization.Serializable

@Serializable
data class PersonalWifiNetwork(
    val id: Long = 0,
    val wifiName: String,
    val macAddress: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val level: Int,
    val accuracy: Float,
    val isReliable: Boolean,
    val noiseLevel: Float = 0f,
    val satellites: Int = 0,
    val speed: Float = 0f,
    val measureCount: Int = 1
)
