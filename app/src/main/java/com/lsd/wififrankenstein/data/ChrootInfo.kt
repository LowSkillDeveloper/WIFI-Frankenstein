package com.lsd.wififrankenstein.data

import kotlinx.serialization.Serializable

@Serializable
data class ChrootInfo(
    val version: String,
    val arm64: ChrootArchive,
    val armhf: ChrootArchive
)

@Serializable
data class ChrootArchive(
    val filename: String,
    val download_url: String
)