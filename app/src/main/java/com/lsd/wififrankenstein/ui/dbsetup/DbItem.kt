package com.lsd.wififrankenstein.ui.dbsetup

import kotlinx.serialization.Serializable

@Serializable
enum class DbType {
    SQLITE_FILE_3WIFI,
    SQLITE_FILE_CUSTOM,
    WIFI_API,
    SMARTLINK_SQLITE_FILE_3WIFI,
    SMARTLINK_SQLITE_FILE_CUSTOM,
    LOCAL_APP_DB
}

@Serializable
enum class AuthMethod {
    API_KEYS,
    LOGIN_PASSWORD,
    NO_AUTH
}

@Serializable
data class DbItem(
    val id: String,
    val path: String,
    val directPath: String?,
    val type: String,
    val dbType: DbType,
    var isMain: Boolean = false,
    val apiKey: String? = null,
    val apiReadKey: String? = null,
    val apiWriteKey: String? = null,
    val login: String? = null,
    val password: String? = null,
    val authMethod: AuthMethod? = null,
    val userNick: String? = null,
    val userLevel: Int? = null,
    val originalSizeInMB: Float,
    var cachedSizeInMB: Float,
    val tableName: String? = null,
    val columnMap: Map<String, String>? = null,
    val idJson: String? = null,
    val version: String? = null,
    val updateUrl: String? = null,
    val smartlinkType: String? = null,
    var isIndexed: Boolean = false,
    var supportsMapApi: Boolean = false
)