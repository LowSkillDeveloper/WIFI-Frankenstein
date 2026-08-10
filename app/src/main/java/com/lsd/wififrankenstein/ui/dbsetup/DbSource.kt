package com.lsd.wififrankenstein.ui.dbsetup

import kotlinx.serialization.Serializable

@Serializable
data class DbSource(
    val id: String,
    val name: String,
    val description: String?,
    val smartlinkUrl: String
)

@Serializable
data class RecommendedSourcesResponse(
    val sources: List<DbSource>? = null
)