package com.lsd.wififrankenstein.ui.dbsetup.localappdb

data class PersonalMapStats(
    val total: Long = 0,
    val reliable: Long = 0,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
    val avgAccuracy: Float = 0f,
    val openCount: Long = 0,
    val crossFound: Long = 0,
    val wpasecFound: Long = 0,
    val wpsCount: Long = 0
)

data class PersonalWriteResult(
    val inserted: Int = 0,
    val updated: Int = 0,
    val newMacs: Set<String> = emptySet()
)
