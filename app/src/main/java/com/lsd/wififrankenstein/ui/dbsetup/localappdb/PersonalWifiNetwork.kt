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
    val measureCount: Int = 1,
    val firstSeen: Long = 0,

    val security: String? = null,

    val securityType: String? = null,
    val frequency: Int = 0,
    val channel: Int = 0,

    val source: String = PersonalMapDbHelper.SOURCE_SCAN,
    val otherDbState: Int = PersonalMapDbHelper.STATE_UNKNOWN,
    val wpasecState: Int = PersonalMapDbHelper.STATE_UNKNOWN,
    val isWps: Boolean = false,

    val alt: Double = 0.0,
    val isPasspoint: Boolean = false,
    val isHidden: Boolean = false,
    val band: String = "",
    val vendor: String = ""
)

/** Full detail for a single saved personal-map point (one row per MAC). */
data class PersonalPointDetail(
    val id: Long,
    val ssid: String,
    val bssid: String,
    val latitude: Double,
    val longitude: Double,
    val level: Int,
    val accuracy: Float,
    val isReliable: Boolean,
    val measureCount: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val security: String?,
    val securityType: String?,
    val frequency: Int,
    val channel: Int,
    val source: String,
    val otherDbState: Int,
    val wpasecState: Int,
    val crossCheckedAt: Long,
    val wpasecCheckedAt: Long,
    val isWps: Boolean,
    val alt: Double,
    val isPasspoint: Boolean,
    val isHidden: Boolean,
    val band: String,
    val vendor: String
)
