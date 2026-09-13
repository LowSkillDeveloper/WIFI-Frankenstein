package com.lsd.wififrankenstein.ui.personalmap

import kotlinx.serialization.Serializable

@Serializable
data class PersonalMapLogEntry(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val isReliable: Boolean,
    val isNew: Boolean,
    val timestamp: Long,
    val id: Long = 0L,
    val otherDbState: Int = 0,
    val wpasecState: Int = 0,
    val isWps: Boolean = false,
    val isPasspoint: Boolean = false,
    val isHidden: Boolean = false,
    val isOpen: Boolean = false,
    val tags: List<String> = emptyList()
)

data class PersonalSessionStats(
    val scans: Int = 0,
    val measurements: Int = 0,
    val distinctNetworks: Int = 0,
    val distinctCapped: Boolean = false,
    val startedAt: Long = 0L,
    val lastFound: Int = 0,
    val lastNew: Int = 0,
    val lastUpdated: Int = 0,
    val lastScanTime: Long = 0L
)

data class PersonalGpsDetail(
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val satellites: Int = 0,
    val fixAgeMs: Long = 0L
)

data class DbMatchEntry(
    val source: String,
    val ssid: String,
    val bssid: String,
    val password: String? = null,
    val wps: String? = null,
    val extra: String = "",
    val databaseId: String = "",
    val databaseName: String = "",
    val color: Int = 0
)
